"""build_cubature.py's publishing of each artifact under the name of what it was built from."""

import tempfile
import unittest
from pathlib import Path

import build_cubature
from lab_files import sha

COMMIT = "6bf47ee37a74bfa41de6518f2cfeab723d40d9b5"


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root = Path(self.folder.name)
        self.built = self.root / "libs"
        self.built.mkdir()
        self.published = self.root / "builds" / "cubature"

    def tearDown(self):
        self.folder.cleanup()

    def artifact(self, content: bytes) -> tuple[Path, str]:
        path = self.built / "Cubature-1.12.2-0.1.4.0-cleanroom.jar"
        path.write_bytes(content)
        return path, sha(path)

    def test_a_clean_build_is_named_by_its_commit(self):
        path, digest = self.artifact(b"one")
        out = build_cubature.publish(path, digest, self.published, COMMIT, False)
        self.assertEqual(out.name, "Cubature-1.12.2-0.1.4.0-6bf47ee3-cleanroom.jar")
        self.assertEqual(out.read_bytes(), b"one")
        line = (self.published / (out.name + ".sha256")).read_text(encoding="utf-8")
        self.assertEqual(line, f"{digest}  {out.name}\n")

    def test_a_dirty_build_adds_its_own_hash(self):
        path, digest = self.artifact(b"one")
        out = build_cubature.publish(path, digest, self.published, COMMIT, True)
        self.assertEqual(out.name, f"Cubature-1.12.2-0.1.4.0-6bf47ee3-dirty-{digest[:8]}-cleanroom.jar")
        other, other_digest = self.artifact(b"two")
        second = build_cubature.publish(other, other_digest, self.published, COMMIT, True)
        self.assertNotEqual(out, second)
        self.assertEqual(out.read_bytes(), b"one")

    def test_the_same_bytes_publish_again(self):
        path, digest = self.artifact(b"one")
        first = build_cubature.publish(path, digest, self.published, COMMIT, False)
        self.assertEqual(build_cubature.publish(path, digest, self.published, COMMIT, False), first)

    def test_different_bytes_under_a_published_name_are_refused(self):
        path, digest = self.artifact(b"one")
        out = build_cubature.publish(path, digest, self.published, COMMIT, False)
        path, digest = self.artifact(b"two")
        with self.assertRaises(SystemExit):
            build_cubature.publish(path, digest, self.published, COMMIT, False)
        self.assertEqual(out.read_bytes(), b"one")
        self.assertEqual([p.name for p in self.published.glob("*.tmp")], [])

    def test_branch_manifest_names(self):
        self.assertEqual(build_cubature.branch_manifest_name("main"), "cubature-main.json")
        self.assertEqual(
            build_cubature.branch_manifest_name("feat/lod-client-sampler"),
            "cubature-feat-lod-client-sampler.json",
        )
        self.assertIsNone(build_cubature.branch_manifest_name("HEAD"))
        self.assertIsNone(build_cubature.branch_manifest_name(None))


if __name__ == "__main__":
    unittest.main()
