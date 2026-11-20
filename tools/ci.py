"""Cubature's local CI: check a commit and record that its tree passed, for the pre-push gate.

The commit (HEAD by default) is checked out in a worktree of its own under the main checkout's
`.local/ci/builds/`, so what this checkout has checked out or uncommitted plays no part. It gets
the GitHub workflow's checks on that commit's own files, and the whole tool suite besides:

  1. its compile-only inputs (`tools/fetch_build_inputs.py`, checked against `cleanroom/build-inputs.json`);
  2. the Cleanroom build with its focused regressions on Java 26 and the JAR checks;
  3. the lab observer built and checked against that JAR;
  4. every Python tool test, in `.local/ci/venv` (made from `tools/requirements.txt`).

Gradle runs on a JDK 25 (JAVA_HOME, or --java25) and is told where the JDK 26 for the
regressions is (--java26; otherwise Gradle looks among the installed JDKs). A pass is recorded in
`.local/ci/passes.json` under the commit's tree, which `tools/push_gate.py` reads; logs go to
`.local/ci/logs/<tree>/`. One run at a time per checkout (`.local/ci/.lock`).

  python tools/ci.py               # HEAD
  python tools/ci.py <commit>
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import venv
from pathlib import Path
from xml.etree import ElementTree

FORK = Path(__file__).resolve().parents[1]
WINDOWS = os.name == "nt"
CHECKS = ["compile-only inputs", "cleanroom build and regressions", "lab build and checks", "tool tests"]


def git(directory: Path, *arguments: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(directory), *arguments], capture_output=True, text=True)


def main_checkout(root: Path = FORK) -> Path:
    """The checkout whose `.local` holds the records: this one, or the one a worktree belongs to."""
    common = git(root, "rev-parse", "--path-format=absolute", "--git-common-dir")
    return Path(common.stdout.strip()).parent if common.returncode == 0 else root


def ci_home(root: Path = FORK) -> Path:
    return main_checkout(root) / ".local/ci"


def passes(home: Path) -> dict:
    """The recorded passes by tree; empty when none are recorded or the record is unreadable."""
    try:
        recorded = json.loads((home / "passes.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return recorded if isinstance(recorded, dict) else {}


def record_pass(home: Path, tree: str, entry: dict) -> None:
    home.mkdir(parents=True, exist_ok=True)
    recorded = passes(home)
    recorded[tree] = entry
    temporary = home / "passes.json.tmp"
    temporary.write_text(json.dumps(recorded, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, home / "passes.json")


def java_feature(home: Path) -> int | None:
    """The feature version a JDK's `release` file names (25 for JAVA_VERSION="25.0.5")."""
    try:
        for line in (home / "release").read_text(encoding="utf-8").splitlines():
            if line.startswith("JAVA_VERSION="):
                return int(line.split("=", 1)[1].strip('"').split(".")[0])
    except (OSError, ValueError):
        pass
    return None


def tool_python(home: Path, requirements: Path) -> Path:
    """The CI's Python for the tool tests, remade whenever tools/requirements.txt changes."""
    environment = home / "venv"
    python = environment / ("Scripts/python.exe" if WINDOWS else "bin/python")
    stamp = environment / "requirements.txt"
    wanted = requirements.read_text(encoding="utf-8")
    if python.is_file() and stamp.is_file() and stamp.read_text(encoding="utf-8") == wanted:
        return python
    venv.create(environment, with_pip=True, clear=environment.exists())
    subprocess.run([str(python), "-m", "pip", "install", "-q", "-r", str(requirements)], check=True)
    stamp.write_text(wanted, encoding="utf-8")
    return python


def remove_tree(path: Path) -> OSError | None:
    """Remove a folder and everything in it; the error when something stays. On Windows the path
    takes the extended form, since a build's deepest files pass 260 characters and long paths are
    off unless the system enables them."""
    text = os.path.abspath(path)
    try:
        shutil.rmtree("\\\\?\\" + text if WINDOWS and not text.startswith("\\\\") else text)
    except FileNotFoundError:
        pass
    except OSError as error:
        return error
    return None


def drop(work: Path) -> None:
    """Remove a check's worktree. Windows can hold a just-built tree's files for a while, so this
    retries; a tree that still cannot go is left for the next run's sweep."""
    if work.exists():
        git(FORK, "worktree", "remove", "--force", str(work))
    for attempt in range(10):
        error = remove_tree(work)
        if error is None:
            break
        if attempt == 9:
            print(f"note: could not remove {work} yet ({error}); the next run sweeps it")
            break
        time.sleep(1)
    git(FORK, "worktree", "prune")


def test_counts(build: Path) -> dict:
    """Tests, failures, errors and skipped over a Gradle build's JUnit reports."""
    counts = dict.fromkeys(["tests", "failures", "errors", "skipped"], 0)
    for report in build.glob("**/test-results/**/TEST-*.xml"):
        suite = ElementTree.parse(report).getroot()
        for name in counts:
            counts[name] += int(suite.get(name, "0"))
    return counts


def run(step: str, command: list[str], cwd: Path, log: Path, environment: dict) -> None:
    print(f"== {step}", flush=True)
    with log.open("w", encoding="utf-8") as out:
        code = subprocess.run(command, cwd=cwd, env=environment, stdout=out, stderr=subprocess.STDOUT).returncode
    if code != 0:
        tail = "\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-25:])
        sys.exit(f"{step} failed (exit {code}); log {log}\n{tail}")


def check(commit: str, tree: str, home: Path, java25: Path, java26: Path | None) -> dict:
    """Run every check on commit in a worktree of its own; exits on the first that fails."""
    logs = home / "logs" / tree[:12]
    logs.mkdir(parents=True, exist_ok=True)
    builds = home / "builds"
    for stale in builds.iterdir() if builds.is_dir() else []:  # what earlier runs could not remove
        if error := remove_tree(stale):
            print(f"note: {stale} is still held ({error})")
    git(FORK, "worktree", "prune")
    work = builds / f"{commit[:12]}-{os.getpid()}"
    builds.mkdir(parents=True, exist_ok=True)
    added = git(FORK, "worktree", "add", "--detach", str(work), commit)
    if added.returncode != 0:
        sys.exit(f"git worktree add failed: {added.stderr.strip()}")
    started = time.monotonic()
    try:
        environment = {**os.environ, "JAVA_HOME": str(java25)}
        installations = ",".join(str(jdk) for jdk in [java25, java26] if jdk)
        gradle = [
            str(work / "cleanroom" / ("gradlew.bat" if WINDOWS else "gradlew")),
            "--no-daemon",
            "--no-watch-fs",
            "--console=plain",
            f"-Porg.gradle.java.installations.paths={installations}",
        ]
        steps = [
            ([sys.executable, "-B", "tools/fetch_build_inputs.py"], work, "inputs"),
            ([*gradle, "-PcubicTestJava=26", "build"], work / "cleanroom", "cleanroom"),
            ([*gradle, "build"], work / "lab", "lab"),
        ]
        for name, (command, cwd, log) in zip(CHECKS, steps):
            run(name, command, cwd, logs / f"{log}.log", environment)
        counted = {build: test_counts(work / build / "build") for build in ["cleanroom", "lab"]}
        for build, counts in counted.items():
            if counts["tests"] == 0:  # a build that ran no tests has checked nothing
                sys.exit(f"The {build} build reported no tests; log {logs / f'{build}.log'}")
        python = tool_python(home, work / "tools/requirements.txt")
        tests = [str(python), "-B", "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py"]
        run(CHECKS[3], tests, work, logs / "tools.log", environment)
    finally:
        drop(work)
    return {
        "commit": commit,
        "utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "seconds": round(time.monotonic() - started, 1),
        "checks": CHECKS,
        "tests": counted,
    }


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("commit", nargs="?", default="HEAD", help="the commit to check (default HEAD)")
    parser.add_argument("--java25", type=Path, help="the JDK 25 Gradle runs on (default JAVA_HOME)")
    parser.add_argument("--java26", type=Path, help="the JDK 26 the focused regressions run on")
    args = parser.parse_args(argv)
    found = git(FORK, "rev-parse", "--verify", "--quiet", f"{args.commit}^{{commit}}")
    if found.returncode != 0:
        sys.exit(f"No commit {args.commit}")
    commit = found.stdout.strip()
    tree = git(FORK, "rev-parse", f"{commit}^{{tree}}").stdout.strip()
    home = ci_home()
    if tree in passes(home):
        print(f"Tree {tree[:12]} ({commit[:12]}) already passed at {passes(home)[tree].get('utc')}")
        return
    java25 = args.java25 or (Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    if java25 is None or java_feature(java25) != 25:
        sys.exit(f"Gradle runs on a JDK 25: set JAVA_HOME to one or pass --java25 (now {java25})")
    if args.java26 and java_feature(args.java26) != 26:
        sys.exit(f"--java26 {args.java26} is not a JDK 26")
    home.mkdir(parents=True, exist_ok=True)
    lock = home / ".lock"
    try:
        descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        owner = lock.read_text(encoding="utf-8", errors="replace").strip()
        sys.exit(f"Another ci run holds {lock} ({owner}); delete it if that run is gone")
    os.write(descriptor, f"pid {os.getpid()} since {time.strftime('%H:%M:%S')}".encode())
    os.close(descriptor)
    try:
        entry = check(commit, tree, home, java25, args.java26)
    finally:
        lock.unlink(missing_ok=True)
    record_pass(home, tree, entry)
    print(f"Tree {tree[:12]} ({commit[:12]}) passed in {entry['seconds']} s; recorded in {home / 'passes.json'}")


if __name__ == "__main__":
    main()
