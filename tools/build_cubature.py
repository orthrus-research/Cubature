"""Build Cubature and its lab observer, run their tests and record the artifacts.

Default: the Cleanroom main artifact with its Java 26 regression tests, then the lab observer
built against that exact artifact. `--forge` adds the Forge main and lab builds (Java 8 tests).
`--skip-tests` assembles only. Writes `.local/builds/cubature-latest.json` in the Riven
workspace with artifact hashes, the git state and per-module test counts. `--freeze <label>`
copies the artifacts to the workspace's `.local/frozen/<label>/` beside Riven's.

Examples (from the Cubature checkout or anywhere):
  python tools/build_cubature.py
  python tools/build_cubature.py --forge --freeze candidate-01
"""

from lab_files import sha
import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ElementTree
from pathlib import Path

FORK = Path(__file__).resolve().parents[1]
WRAPPER = FORK / "cleanroom" / ("gradlew.bat" if os.name == "nt" else "gradlew")


def workspace_settings(workspace: Path) -> dict:
    path = workspace / ".local/settings.json"
    if not path.is_file():
        sys.exit(f"Missing {path}; pass --workspace <Riven root>")
    data = json.loads(path.read_text(encoding="utf-8"))
    for key in ("java25", "java26"):
        if not Path(data.get(key, "")).is_file():
            sys.exit(f"Missing JDK executable in {path}: {key}")
    return data


def git_state() -> dict:
    def git(*args):
        result = subprocess.run(["git", *args], cwd=FORK, capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else None

    return {
        "commit": git("rev-parse", "HEAD"),
        "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
        "dirty": bool(git("status", "--porcelain")),
    }


def single_jar(directory: Path, prefix: str, loader: str) -> Path:
    # Match the current archive name only; older builds can leave renamed JARs beside it.
    matches = [p for p in directory.glob(f"{prefix}*-{loader}.jar") if "-dev" not in p.name]
    if len(matches) != 1:
        found = [p.name for p in matches]
        sys.exit(f"Expected one {prefix}*-{loader}.jar in {directory}, found {found}")
    return matches[0]


def test_summary(results: Path, since: float) -> dict | None:
    """Totals from the module's reports; `fresh` is false when Gradle found tests up to date."""
    totals: dict = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "fresh": False}
    found = False
    for report in results.rglob("TEST-*.xml"):
        found = True
        totals["fresh"] = totals["fresh"] or report.stat().st_mtime >= since
        suite = ElementTree.parse(report).getroot()
        for key in ("tests", "failures", "errors", "skipped"):
            totals[key] += int(suite.get(key, 0))
    return totals if found else None


def gradle(directory: Path, arguments: list, environment: dict, label: str):
    print(f"== {label}", flush=True)
    command = [str(WRAPPER), *arguments, "--console=plain"]
    if subprocess.run(command, cwd=directory, env=environment).returncode != 0:
        sys.exit(f"{label} failed")


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--workspace", type=Path, default=FORK.parents[1], help="Riven root")
    parser.add_argument("--forge", action="store_true", help="also build the Forge targets")
    parser.add_argument("--skip-tests", action="store_true", help="assemble without tests")
    parser.add_argument("--no-lab", action="store_true", help="skip the lab observer")
    parser.add_argument("--freeze", metavar="LABEL", help="copy artifacts to .local/frozen/LABEL")
    parser.add_argument(
        "--no-daemon", action="store_true", help="leave no Gradle daemon (for native captures)"
    )
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    local = workspace_settings(workspace)
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(Path(local["java25"]).parent.parent)
    java26_home = Path(local["java26"]).parent.parent
    installations = f"-Porg.gradle.java.installations.paths={java26_home}"
    if args.no_daemon:
        installations = [installations, "--no-daemon"]
    else:
        installations = [installations]
    task = "assemble" if args.skip_tests else "build"
    started_wall = time.time()
    started = time.monotonic()
    artifacts, tests = {}, {}
    loaders = ["cleanroom"] + (["forge"] if args.forge else [])
    for loader in loaders:
        main_args = [f"-PcubicLoader={loader}", task, *installations]
        if loader == "cleanroom":
            main_args.insert(1, "-PcubicTestJava=26")
        gradle(FORK / "cleanroom", main_args, environment, f"Cubature {loader} {task}")
        main_jar = single_jar(FORK / f"cleanroom/build/{loader}/libs", "Cubature-", loader)
        artifacts[f"cubature-{loader}"] = main_jar
        tests[f"cubature-{loader}"] = test_summary(
            FORK / f"cleanroom/build/{loader}/test-results", started_wall
        )
        if args.no_lab:
            continue
        lab_args = [f"-PcubicLoader={loader}", f"-PcubicArtifact={main_jar}", task, *installations]
        gradle(FORK / "lab", lab_args, environment, f"Lab observer {loader} {task}")
        artifacts[f"lab-{loader}"] = single_jar(
            FORK / f"lab/build/{loader}/libs", "cubicchunks-lab-", loader
        )
        tests[f"lab-{loader}"] = test_summary(
            FORK / f"lab/build/{loader}/test-results", started_wall
        )
    failed = [k for k, v in tests.items() if v and (v["failures"] or v["errors"])]
    manifest = {
        "builtAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "seconds": round(time.monotonic() - started, 1),
        "git": git_state(),
        "artifacts": {
            k: {"path": str(p), "bytes": p.stat().st_size, "sha256": sha(p)}
            for k, p in artifacts.items()
        },
        "tests": tests,
    }
    if args.freeze:
        target = workspace / ".local/frozen" / args.freeze
        target.mkdir(parents=True, exist_ok=True)
        for name, entry in manifest["artifacts"].items():
            destination = target / f"{name}.jar"
            if destination.exists() and sha(destination) != entry["sha256"]:
                sys.exit(f"Refusing to overwrite a different frozen artifact: {destination}")
            shutil.copy2(entry["path"], destination)
            entry["frozen"] = str(destination)
        combined_path = target / "manifest.json"
        combined = (
            json.loads(combined_path.read_text(encoding="utf-8")) if combined_path.exists() else {}
        )
        combined["cubature"] = manifest
        combined_path.write_text(json.dumps(combined, indent=2), encoding="utf-8")
        manifest["frozenTo"] = str(target)
    out = workspace / ".local/builds/cubature-latest.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    for name, entry in manifest["artifacts"].items():
        counts = tests.get(name)
        summary = (
            f"{counts['tests']} tests, {counts['failures'] + counts['errors']} failed"
            + ("" if counts["fresh"] else ", up to date")
            if counts
            else "no tests run"
        )
        print(f"{name}: {Path(entry['path']).name}  sha256 {entry['sha256'][:16]}  ({summary})")
    if args.freeze:
        print(f"frozen: {manifest['frozenTo']}")
    state = "dirty" if manifest["git"]["dirty"] else "clean"
    print(
        f"Cubature built in {manifest['seconds']} s from {manifest['git']['commit'][:8]} ({state})"
    )
    if failed:
        sys.exit("Test failures in: " + ", ".join(failed))


if __name__ == "__main__":
    main()
