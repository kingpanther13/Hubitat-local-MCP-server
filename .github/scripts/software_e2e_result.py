"""Consume only the exact revision's public Software E2E status."""

import argparse
import json
import os
import re
import time
import urllib.error
import urllib.request
from pathlib import Path

REPOSITORY = "kingpanther13/Hubitat-local-MCP-server"
CONTEXT = "Software E2E"
PASSED, FAILED, WAITING, UNAVAILABLE = "passed", "failed", "waiting", "unavailable"
MESSAGES = {
    PASSED: "Software E2E passed for this revision.",
    FAILED: "Software E2E failed for this revision.",
    UNAVAILABLE: "Software E2E result unavailable for this revision.",
}


def validate_sha(sha):
    if not isinstance(sha, str) or re.fullmatch(r"[0-9a-f]{40}", sha) is None:
        raise ValueError("Invalid revision.")
    return sha


def select_revision(event_name, event, fallback_sha):
    if not isinstance(event, dict) or event.get("repository", {}).get("full_name") != REPOSITORY:
        return None
    if event_name == "pull_request":
        request = event.get("pull_request") or {}
        head = request.get("head") or {}
        repository = head.get("repo") or {}
        if (request.get("user", {}).get("login") != "kingpanther13"
                or repository.get("full_name") != REPOSITORY):
            return None
        return validate_sha(head.get("sha"))
    if event_name == "push":
        if event.get("ref") != "refs/heads/main":
            return None
        sha = validate_sha(fallback_sha)
        if event.get("after") != sha:
            raise ValueError("Push revision mismatch.")
        return sha
    if event_name == "workflow_dispatch":
        return validate_sha(fallback_sha)
    raise ValueError("Unsupported event.")


def result_state(payload, sha):
    validate_sha(sha)
    if not isinstance(payload, dict) or payload.get("sha") != sha:
        return WAITING
    statuses = payload.get("statuses")
    if not isinstance(statuses, list) or any(not isinstance(status, dict) for status in statuses):
        return UNAVAILABLE
    matching = [status for status in statuses if status.get("context") == CONTEXT]
    if not matching:
        return WAITING
    if len(matching) != 1:
        return UNAVAILABLE
    return {"success": PASSED, "failure": FAILED, "error": FAILED, "pending": WAITING}.get(matching[0].get("state"), UNAVAILABLE)


def wait_for_result(sha, fetch, timeout=7800, interval=30, monotonic=time.monotonic, sleep=time.sleep):
    validate_sha(sha)
    if not 0 < timeout <= 7800 or interval <= 0:
        raise ValueError("Invalid waiting bounds.")
    deadline = monotonic() + timeout
    while True:
        if monotonic() >= deadline:
            return UNAVAILABLE
        try:
            payload = fetch()
            outcome = result_state(payload, sha)
        except urllib.error.HTTPError as error:
            if not 500 <= error.code < 600:
                return UNAVAILABLE
            outcome = WAITING
        except OSError:
            outcome = WAITING
        except Exception:
            return UNAVAILABLE
        if outcome != WAITING:
            return outcome
        remaining = deadline - monotonic()
        if remaining <= 0:
            return UNAVAILABLE
        sleep(min(interval, remaining))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch_status(sha, token):
    validate_sha(sha)
    if not isinstance(token, str) or not token or any(ord(char) < 33 or ord(char) > 126 for char in token):
        raise ValueError("Status reader unavailable.")
    endpoint = f"https://api.github.com/repos/{REPOSITORY}/commits/{sha}/status"
    request = urllib.request.Request(endpoint, headers={
        "Accept": "application/vnd.github+json", "Authorization": "Bearer " + token,
        "X-GitHub-Api-Version": "2022-11-28",
    })
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-file", type=Path)
    parser.add_argument("--event-name")
    parser.add_argument("--fallback-sha")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--sha")
    parser.add_argument("--timeout-seconds", type=int, default=7800)
    args = parser.parse_args()
    try:
        if args.event_file is not None:
            revision = select_revision(args.event_name, json.loads(args.event_file.read_text(encoding="utf-8")), args.fallback_sha)
            if args.output is not None:
                with args.output.open("a", encoding="utf-8") as output:
                    output.write(f"eligible={'true' if revision else 'false'}\nsha={revision or ''}\n")
            print("Software E2E revision selected." if revision else "Software E2E is not scheduled for this event source.")
            return 0
        sha = validate_sha(args.sha)
        outcome = wait_for_result(sha, lambda: fetch_status(sha, os.environ.get("GITHUB_TOKEN")), args.timeout_seconds)
    except Exception:
        outcome = UNAVAILABLE
    print(MESSAGES[outcome])
    return 0 if outcome == PASSED else 1


if __name__ == "__main__":
    raise SystemExit(main())
