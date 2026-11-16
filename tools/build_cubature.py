"""Build Cubature and its lab observer, run their tests and record the artifacts.

Default: the Cleanroom main artifact with its Java 26 regression tests, then the lab observer
built against that exact artifact. Cleanroom is the only target (Java 25 bytecode, Java 26 tests).
`--skip-tests` assembles only. Writes `.local/builds/cubature-latest.json` in the Riven
workspace with artifact hashes, the git state and per-module test counts, and the same manifest
as `.local/builds/cubature-<branch>.json` for the branch built. `--freeze <label>` copies the
artifacts to the workspace's `.local/frozen/<label>/` beside Riven's.

Every worktree builds into its own checkout but records into the one workspace, so the latest
manifest names whichever worktree built last. Each artifact is therefore also published under a
name that says what it was built from, in `.local/builds/cubature/`:
`Cubature-<version>-<commit>[-dirty-<hash>]-cleanroom.jar`, with a `.sha256` file beside it. A
clean build's name is its commit; a build with uncommitted changes adds `-dirty` and the start of
its own hash, since the same commit can then give different artifacts. A published file is never
replaced by a different one. `--no-publish` leaves the folder alone.

Examples (from the Cubature checkout or anywhere):
  python tools/build_cubature.py
  python tools/build_cubature.py --freeze candidate-01
"""

from lab_files import sha
from riven_workspace import riven_workspace
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


def published_name(artifact: Path, commit: str, dirty: bool, digest: str) -> str:
    """The artifact's name with what it was built from before the loader: `-<commit>[-dirty-<hash>]`."""
    stem, loader = artifact.stem.rsplit("-", 1)
    built_from = commit[:8] + (f"-dirty-{digest[:8]}" if dirty else "")
    return f"{stem}-{built_from}-{loader}{artifact.suffix}"


def publish(artifact: Path, digest: str, folder: Path, commit: str, dirty: bool) -> Path:
    """Copies an artifact to the published folder under its built-from name, with a .sha256 beside it.

    Refuses to replace a published file whose hash differs: the same name must always mean the
    same bytes. Copies to a temporary name first, so a reader never finds a half-written file.
    """
    folder.mkdir(parents=True, exist_ok=True)
    destination = folder / published_name(artifact, commit, dirty, digest)
    checksum = destination.with_name(destination.name + ".sha256")
    if destination.exists():
        if sha(destination) != digest:
            sys.exit(f"Refusing to overwrite a different published artifact: {destination}")
    else:
        temporary = destination.with_name(destination.name + ".tmp")
        shutil.copy2(artifact, temporary)
        os.replace(temporary, destination)
    checksum.write_text(f"{digest}  {destination.name}\n", encoding="utf-8")
    return destination


def branch_manifest_name(branch: str | None) -> str | None:
    """`cubature-<branch>.json`, with the branch's slashes as dashes; None for a detached HEAD."""
    if not branch or branch == "HEAD":
        return None
    return "cubature-" + branch.replace("/", "-").replace("\\", "-") + ".json"


def gradle(directory: Path, arguments: list, environment: dict, label: str):
    print(f"== {label}", flush=True)
    command = [str(WRAPPER), *arguments, "--console=plain"]
    if subprocess.run(command, cwd=directory, env=environment).returncode != 0:
        sys.exit(f"{label} failed")


def merge_frozen_manifest(target: Path, key: str, value: dict) -> None:
    """Sets one key of the frozen folder's manifest.json, safely beside another build doing the same.

    Cubature's and Riven's builds freeze into one folder and may finish together. Each reads the
    manifest, sets its own key and writes it back; done unguarded, the later write drops the other's
    key or reads a half-written file. So the merge runs under a lock on a file both builds know, and
    the result replaces the manifest in one step. Riven's build_riven.py holds the same lock.
    """
    path = target / "manifest.json"
    with open(target / "manifest.lock", "a+b") as lock:
        _lock(lock)
        try:
            combined = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
            combined[key] = value
            temporary = target / f"manifest.json.{key}.tmp"
            temporary.write_text(json.dumps(combined, indent=2), encoding="utf-8")
            os.replace(temporary, path)
        finally:
            _unlock(lock)


def _lock(stream, patience: float = 120.0) -> None:
    """Waits for an exclusive lock on the first byte of an open file."""
    stream.seek(0)
    if os.name != "nt":
        import fcntl

        fcntl.flock(stream.fileno(), fcntl.LOCK_EX)
        return
    import msvcrt

    deadline = time.monotonic() + patience
    while True:
        try:
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            return
        except OSError:
            if time.monotonic() > deadline:
                raise
            time.sleep(0.05)


def _unlock(stream) -> None:
    stream.seek(0)
    if os.name != "nt":
        import fcntl

        fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
    else:
        import msvcrt

        msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--workspace", type=Path, default=riven_workspace(), help="Riven root")
    parser.add_argument("--skip-tests", action="store_true", help="assemble without tests")
    parser.add_argument("--no-lab", action="store_true", help="skip the lab observer")
    parser.add_argument("--freeze", metavar="LABEL", help="copy artifacts to .local/frozen/LABEL")
    parser.add_argument(
        "--no-publish", action="store_true", help="skip publishing to .local/builds/cubature"
    )
    parser.add_argument(
        "--no-daemon", action="store_true", help="leave no Gradle daemon (for native captures)"
    )
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    local = workspace_settings(workspace)
    environment = os.environ.copy()
    environment["JAVA_HOME"] = str(Path(local["java25"]).parent.parent)
    environment["RIVEN_WORKSPACE"] = str(workspace)  # cleanroom/build.gradle reads dev/pins there
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
    for loader in ["cleanroom"]:
        main_args = ["-PcubicTestJava=26", task, *installations]
        gradle(FORK / "cleanroom", main_args, environment, f"Cubature {loader} {task}")
        main_jar = single_jar(FORK / f"cleanroom/build/{loader}/libs", "Cubature-", loader)
        artifacts[f"cubature-{loader}"] = main_jar
        tests[f"cubature-{loader}"] = test_summary(
            FORK / f"cleanroom/build/{loader}/test-results", started_wall
        )
        if args.no_lab:
            continue
        lab_args = [f"-PcubicArtifact={main_jar}", task, *installations]
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
    builds = workspace / ".local/builds"
    if not args.no_publish and manifest["git"]["commit"]:
        for entry in manifest["artifacts"].values():
            entry["published"] = str(
                publish(
                    Path(entry["path"]),
                    entry["sha256"],
                    builds / "cubature",
                    manifest["git"]["commit"],
                    manifest["git"]["dirty"],
                )
            )
    if args.freeze:
        target = workspace / ".local/frozen" / args.freeze
        target.mkdir(parents=True, exist_ok=True)
        for name, entry in manifest["artifacts"].items():
            destination = target / f"{name}.jar"
            if destination.exists() and sha(destination) != entry["sha256"]:
                sys.exit(f"Refusing to overwrite a different frozen artifact: {destination}")
            shutil.copy2(entry["path"], destination)
            entry["frozen"] = str(destination)
        merge_frozen_manifest(target, "cubature", manifest)
        manifest["frozenTo"] = str(target)
    builds.mkdir(parents=True, exist_ok=True)
    text = json.dumps(manifest, indent=2)
    (builds / "cubature-latest.json").write_text(text, encoding="utf-8")
    pointer = branch_manifest_name(manifest["git"]["branch"])
    if pointer:
        (builds / pointer).write_text(text, encoding="utf-8")
    for name, entry in manifest["artifacts"].items():
        counts = tests.get(name)
        summary = (
            f"{counts['tests']} tests, {counts['failures'] + counts['errors']} failed"
            + ("" if counts["fresh"] else ", up to date")
            if counts
            else "no tests run"
        )
        print(f"{name}: {Path(entry['path']).name}  sha256 {entry['sha256'][:16]}  ({summary})")
        if "published" in entry:
            print(f"  published: {entry['published']}")
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
