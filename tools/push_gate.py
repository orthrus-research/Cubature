"""Refuse a Cubature push whose commits have not passed `tools/ci.py`: Cubature's pre-push hook.

  python tools/push_gate.py install [--force]   write the hook into this checkout
  python tools/push_gate.py check [remote url]  the hook's check; git lists the refs on stdin

Each pushed tip needs a pass of `tools/ci.py` recorded for its tree in the main checkout's
`.local/ci/passes.json`; a ref being deleted needs nothing. The hook is shared by the checkout's
worktrees and stays local (git never commits .git/hooks); `git push --no-verify` skips it for a
push that deliberately goes without.
"""

import argparse
import sys
from collections.abc import Iterable
from pathlib import Path

import ci

MARKER = "tools/push_gate.py"
HOOK = """#!/bin/sh
# Installed by tools/push_gate.py install: refuses a push whose commits have not passed tools/ci.py.
exec "{python}" "{script}" check "$@"
"""


def refusals(lines: Iterable[str], passed: dict, root: Path = ci.FORK) -> list[str]:
    """The pushed tips with no recorded pass, from git's pre-push lines:
    <local ref> <local commit> <remote ref> <remote commit>."""
    refused = []
    for line in lines:
        fields = line.split()
        if len(fields) != 4 or set(fields[1]) == {"0"}:  # a deleted ref has nothing to check
            continue
        tree = ci.git(root, "rev-parse", f"{fields[1]}^{{tree}}").stdout.strip()
        if tree not in passed:
            refused.append(f"{fields[0]} ({fields[1][:12]}, tree {tree[:12]})")
    return refused


def check(lines: Iterable[str]) -> int:
    refused = refusals(lines, ci.passes(ci.ci_home()))
    for ref in refused:
        print(f"push_gate: refusing {ref}: tools/ci.py has not passed on it.", file=sys.stderr)
    if refused:
        print("Run `python tools/ci.py <commit>` for each and push again.", file=sys.stderr)
    return 1 if refused else 0


def install(force: bool, root: Path = ci.FORK) -> int:
    hooks = Path(ci.git(root, "rev-parse", "--path-format=absolute", "--git-common-dir").stdout.strip()) / "hooks"
    hook = hooks / "pre-push"
    if hook.exists() and not force and MARKER not in hook.read_text(errors="replace"):
        print(f"{hook} is another hook; --force replaces it", file=sys.stderr)
        return 1
    hooks.mkdir(parents=True, exist_ok=True)
    python, script = Path(sys.executable).as_posix(), Path(__file__).resolve().as_posix()
    hook.write_text(HOOK.format(python=python, script=script), encoding="utf-8", newline="\n")
    hook.chmod(0o755)
    print(f"installed {hook}")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    install_command = commands.add_parser("install", help="write the pre-push hook")
    install_command.add_argument("--force", action="store_true", help="replace another hook")
    check_command = commands.add_parser("check", help="the hook's check (refs on stdin)")
    check_command.add_argument("remote", nargs="*", help="git's remote name and URL (unused)")
    args = parser.parse_args()
    sys.exit(install(args.force) if args.command == "install" else check(sys.stdin))


if __name__ == "__main__":
    main()
