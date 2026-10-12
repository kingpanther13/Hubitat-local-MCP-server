"""Exercise Renovate's real extraction/lookups against current and stale copies.

Run by dependency-updates-check.yml with a read-only token. No branches, issues,
PRs, Gradle execution, or build-file edits in the checkout are performed.
"""

import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD_FILES = ("build.gradle", "ci/groovy2x-spock/build.gradle", "ci/groovy24-parse/build.gradle")
GROOVY = "org.codehaus.groovy:groovy-all"
SPOCK = "org.spockframework:spock-core"
SCHEMA = "com.networknt:json-schema-validator"
CHROMECAST = "su.litvak.chromecast:api-v2"
FORKS = ("eighty20results/hubitat_ci", "joelwetzel/hubitat_ci")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def lookup(work, evidence, name):
    log = evidence / f"{name}.jsonl"
    env = dict(os.environ, LOG_FILE=str(log), LOG_FILE_LEVEL="debug", LOG_LEVEL="warn")
    result = subprocess.run(
        [os.environ["RENOVATE_BINARY"], "--platform=local", "--dry-run=lookup"],
        cwd=work, env=env, check=False, timeout=600,
    )
    require(result.returncode == 0, f"Renovate failed in {name}: {result.returncode}")
    records = [json.loads(line) for line in log.read_text().splitlines() if line.strip()]
    errors = [r for r in records if r.get("level", 0) >= 40]
    require(not errors, f"Renovate warnings/errors in {name}: {[r.get('msg') for r in errors]}")
    packages = [r["config"] for r in records if r.get("msg") == "packageFiles with updates"]
    require(len(packages) == 1, f"Missing/ambiguous lookup result in {name}")
    (evidence / f"{name}-packages.json").write_text(json.dumps(packages[0], indent=2) + "\n")
    return packages[0]


def validate(packages, stale):
    found = {}
    for manager, files in packages.items():
        for file in files:
            for dep in file["deps"]:
                key = (file["packageFile"], dep["depName"])
                found.setdefault(key, []).append(dep)
                if dep["depName"].startswith("com.github.") and manager == "gradle":
                    require(not dep.get("updates"), f"Fork also updated as a Maven version: {key}")
                    continue
                require(not dep.get("warnings"), f"Lookup warnings for {key}: {dep.get('warnings')}")
                require(not dep.get("skipReason"), f"Dependency silently skipped: {key}: {dep.get('skipReason')}")
                for update in dep.get("updates", []):
                    value = update.get("newValue", "")
                    if dep["depName"] == GROOVY:
                        line = dict(zip(BUILD_FILES, ("3.0.", "2.5.", "2.4."), strict=True))[file["packageFile"]]
                        require(value.startswith(line), f"Groovy compatibility changed: {key}: {value}")
                    if dep["depName"] == SPOCK:
                        suffix = "-groovy-3.0" if file["packageFile"] == BUILD_FILES[0] else "-groovy-2.5"
                        require(re.fullmatch(r"2\.[0-9]+(?:\.[0-9]+)?" + re.escape(suffix), value),
                                f"Wrong Spock variant or prerelease: {key}: {value}")
                    if dep["depName"] == SCHEMA:
                        require(value.startswith("2."), f"Schema validator exceeds Java 11 boundary: {value}")
                    if dep["depName"] == CHROMECAST:
                        raise AssertionError(f"Chromecast pin changed: {value}")

    expected = [(file, GROOVY) for file in BUILD_FILES]
    expected += [(file, dep) for file in BUILD_FILES[:2] for dep in (SPOCK, SCHEMA, CHROMECAST)]
    expected += [(BUILD_FILES[0], FORKS[0]), (BUILD_FILES[1], FORKS[1])]
    expected += [("gradle/wrapper/gradle-wrapper.properties", "gradle")]
    expected += [(".github/workflows/dependency-updates-check.yml", "renovate")]
    for key in expected:
        require(key in found, f"Dependency not discovered: {key}")

    release = found[(BUILD_FILES[0], FORKS[0])][0]
    commit = found[(BUILD_FILES[1], FORKS[1])][0]
    require(release.get("datasource") == "github-releases", "Wrong release source")
    require(commit.get("datasource") == "git-refs" and commit.get("currentValue") == "master",
            "Commit pin is not tracking master")
    if stale:
        for file in BUILD_FILES[:2]:
            for dep in (SPOCK, SCHEMA):
                require(any(d.get("updates") for d in found[(file, dep)]), f"No update found for stale {file}: {dep}")
        require(release.get("updates"), "No release update found for stale eighty20results tag")
        require(commit.get("updates"), "No commit update found for stale joelwetzel SHA")
        for update in commit["updates"]:
            require(update.get("updateType") == "digest", "Commit tracking changed branch instead of SHA")
            require(re.fullmatch(r"[a-f0-9]{7,40}", update.get("newDigest", "")), "Missing immutable SHA update")


def main():
    evidence = Path(os.environ["EVIDENCE_DIR"]).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="renovate-check-") as directory:
        work = Path(directory)
        files = (*BUILD_FILES, "settings.gradle", "ci/groovy2x-spock/settings.gradle",
                 "ci/groovy24-parse/settings.gradle", ".github/renovate.json",
                 "gradle/wrapper/gradle-wrapper.properties", ".github/workflows/dependency-updates-check.yml")
        for name in files:
            target = work / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        validate(lookup(work, evidence, "current"), stale=False)

        # Real older upstream identifiers, only in this disposable copy. These
        # demonstrate update discovery even when the repository's pins are current.
        for name in BUILD_FILES[:2]:
            file = work / name
            text = file.read_text()
            text = re.sub(r"(org.spockframework:spock-core:)[^']+", r"\g<1>2.3-groovy-" +
                          ("3.0" if name == BUILD_FILES[0] else "2.5"), text)
            text = re.sub(r"(com.networknt:json-schema-validator:)[^']+", r"\g<1>2.0.4", text)
            text = re.sub(r"(testImplementation 'com.github.eighty20results:hubitat_ci:)[^']+", r"\g<1>v0.28.5", text)
            text = re.sub(r"(testImplementation 'com.github.joelwetzel:hubitat_ci:)[^']+", r"\g<1>e718baed2c", text)
            file.write_text(text)
        validate(lookup(work, evidence, "stale"), stale=True)
    print("Verified current dependency discovery, compatibility boundaries, and updates from stale Spock/schema/fork pins.")


if __name__ == "__main__":
    main()
