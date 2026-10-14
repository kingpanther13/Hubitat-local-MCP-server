"""Consume only the exact revision's public Software E2E status."""

import argparse
import http.client
import json
import os
import re
import sys
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
    if not isinstance(event, dict) or (event.get("repository") or {}).get("full_name") != REPOSITORY:
        return None
    if event_name == "pull_request":
        request = event.get("pull_request") or {}
        head = request.get("head") or {}
        repository = head.get("repo") or {}
        if ((request.get("user") or {}).get("login") != "kingpanther13"
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
    # The endpoint is addressed by SHA, so another revision's payload is never worth waiting on.
    if not isinstance(payload, dict) or payload.get("sha") != sha:
        return UNAVAILABLE
    statuses = payload.get("statuses")
    if not isinstance(statuses, list) or any(not isinstance(status, dict) for status in statuses):
        return UNAVAILABLE
    matching = [status for status in statuses if status.get("context") == CONTEXT]
    if not matching:
        return WAITING
    if len(matching) != 1:
        return UNAVAILABLE
    return {"success": PASSED, "failure": FAILED, "error": FAILED, "pending": WAITING}.get(matching[0].get("state"), UNAVAILABLE)


def describe(error):
    """Class name and HTTP status only: API messages, URLs and bodies are never logged."""
    code = getattr(error, "code", None)
    return type(error).__name__ + (f" {code}" if isinstance(code, int) else "")


def log(message):
    print(f"Software E2E status read: {message}", file=sys.stderr)


def _seconds(value):
    return int(value) if isinstance(value, str) and re.fullmatch(r"[0-9]+", value.strip()) else None


def rate_limit_delay(error, now):
    """Seconds GitHub asks the reader to back off, or None when the error is not a rate limit."""
    headers = error.headers or {}
    retry_after = _seconds(headers.get("retry-after"))
    exhausted = headers.get("x-ratelimit-remaining") == "0"
    if error.code != 429 and not (error.code == 403 and (headers.get("retry-after") is not None or exhausted)):
        return None
    if retry_after is not None:
        return retry_after
    reset = _seconds(headers.get("x-ratelimit-reset"))
    return reset - now() if exhausted and reset is not None else 0


def wait_for_result(sha, fetch, timeout=7800, interval=30, monotonic=time.monotonic, sleep=time.sleep, now=time.time):
    validate_sha(sha)
    if not 0 < timeout <= 7800 or interval <= 0:
        raise ValueError("Invalid waiting bounds.")
    deadline = monotonic() + timeout
    transient = 0
    while True:
        if monotonic() >= deadline:
            return UNAVAILABLE
        delay = interval
        try:
            outcome = result_state(fetch(), sha)
        except urllib.error.HTTPError as error:
            backoff = rate_limit_delay(error, now)
            if backoff is None and not 500 <= error.code < 600:
                log(f"{describe(error)}, giving up.")
                return UNAVAILABLE
            transient += 1
            delay = max(interval, backoff or 0)
            log(f"{describe(error)}{' rate limited' if backoff is not None else ''}, retrying in {int(delay)} s (transient failure {transient}).")
            outcome = WAITING
        except (OSError, http.client.IncompleteRead, json.JSONDecodeError) as error:
            transient += 1
            log(f"{describe(error)}, retrying (transient failure {transient}).")
            outcome = WAITING
        except Exception as error:
            log(f"{describe(error)}, giving up.")
            return UNAVAILABLE
        if outcome != WAITING:
            return outcome
        remaining = deadline - monotonic()
        if remaining <= 0:
            return UNAVAILABLE
        sleep(min(delay, remaining))


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
    except Exception as error:
        print(f"Software E2E check failed: {describe(error)}", file=sys.stderr)
        outcome = UNAVAILABLE
    print(MESSAGES[outcome])
    return 0 if outcome == PASSED else 1


if __name__ == "__main__":
    raise SystemExit(main())
