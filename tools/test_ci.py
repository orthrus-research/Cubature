"""tools/ci.py's records and the pre-push gate that reads them, in scratch folders and repositories."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import ci
import push_gate

ZERO = "0" * 40


def scratch_repository(folder: Path) -> tuple[str, str]:
    """A repository with one commit; its commit and tree."""
    def git(*arguments: str) -> str:
        return subprocess.run(["git", "-C", str(folder), *arguments], check=True, capture_output=True, text=True).stdout.strip()

    git("init", "-q")
    (folder / "file.txt").write_text("one\n", encoding="utf-8")
    git("add", "file.txt")
    git("-c", "user.name=Check", "-c", "user.email=check@example.invalid", "commit", "-q", "-m", "One")
    return git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}")


class GateTests(unittest.TestCase):
    def test_a_tip_whose_tree_passed_goes_and_one_that_did_not_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            commit, tree = scratch_repository(root)
            line = f"refs/heads/main {commit} refs/heads/main {ZERO}"
            self.assertEqual(push_gate.refusals([line], {tree: {}}, root), [])
            refused = push_gate.refusals([line], {}, root)
            self.assertEqual(len(refused), 1)
            self.assertIn(commit[:12], refused[0])

    def test_a_deleted_ref_needs_no_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            scratch_repository(root)
            line = f"(delete) {ZERO} refs/heads/old {'1' * 40}"
            self.assertEqual(push_gate.refusals([line], {}, root), [])

    def test_passes_are_recorded_by_tree_and_kept(self):
        with tempfile.TemporaryDirectory() as folder:
            home = Path(folder) / "ci"
            ci.record_pass(home, "a" * 40, {"commit": "1"})
            ci.record_pass(home, "b" * 40, {"commit": "2"})
            self.assertEqual(set(ci.passes(home)), {"a" * 40, "b" * 40})

    def test_an_unreadable_record_counts_as_no_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            home = Path(folder)
            (home / "passes.json").write_text("[1, 2", encoding="utf-8")
            self.assertEqual(ci.passes(home), {})

    def test_a_worktrees_records_live_in_its_main_checkout(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / "main"
            root.mkdir()
            commit, _ = scratch_repository(root)
            work = Path(folder) / "work"
            subprocess.run(["git", "-C", str(root), "worktree", "add", "-q", "--detach", str(work), commit], check=True)
            self.assertEqual(ci.main_checkout(work).resolve(), root.resolve())

    def test_test_counts_add_up_every_report(self):
        with tempfile.TemporaryDirectory() as folder:
            build = Path(folder)
            for task, tests in [("test", 3), ("labTest", 2)]:
                results = build / "test-results" / task
                results.mkdir(parents=True)
                (results / "TEST-x.xml").write_text(
                    f'<testsuite name="x" tests="{tests}" failures="0" errors="0" skipped="1"/>', encoding="utf-8"
                )
            self.assertEqual(ci.test_counts(build), {"tests": 5, "failures": 0, "errors": 0, "skipped": 2})
            self.assertEqual(ci.test_counts(build / "absent")["tests"], 0)

    def test_a_tree_deeper_than_260_characters_is_removed(self):
        with tempfile.TemporaryDirectory() as folder:
            top = Path(folder) / "build"
            deep = os.path.abspath(top / ("d" * 60) / ("e" * 60) / ("f" * 60) / ("g" * 60))
            extended = "\\\\?\\" + deep if os.name == "nt" else deep
            os.makedirs(extended)
            with open(os.path.join(extended, "h" * 40 + ".class"), "w", encoding="utf-8") as out:
                out.write("x")
            self.assertGreater(len(deep), 260)
            self.assertIsNone(ci.remove_tree(top))
            self.assertFalse(top.exists())

    def test_a_jdks_feature_version_is_read_from_its_release_file(self):
        with tempfile.TemporaryDirectory() as folder:
            home = Path(folder)
            (home / "release").write_text('IMPLEMENTOR="x"\nJAVA_VERSION="25.0.5"\n', encoding="utf-8")
            self.assertEqual(ci.java_feature(home), 25)
            self.assertIsNone(ci.java_feature(home / "absent"))


if __name__ == "__main__":
    unittest.main()
