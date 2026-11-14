"""build_cubature.py's merge into the frozen folder's manifest, beside another build doing the same."""

import json
import tempfile
import threading
import unittest
from pathlib import Path

import build_cubature


class MergeTests(unittest.TestCase):
    def test_a_key_is_added_and_others_are_kept(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder)
            (target / "manifest.json").write_text(json.dumps({"cubature": {"n": 1}}), encoding="utf-8")
            build_cubature.merge_frozen_manifest(target, "riven", {"n": 2})
            merged = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(merged, {"cubature": {"n": 1}, "riven": {"n": 2}})

    def test_merges_at_the_same_time_lose_nothing(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder)
            keys = [f"build-{i}" for i in range(8)]
            start = threading.Barrier(len(keys))
            failures = []

            def merge(key):
                try:
                    start.wait()
                    for round_number in range(25):
                        build_cubature.merge_frozen_manifest(target, key, {"round": round_number})
                except Exception as error:  # noqa: BLE001 - reported by the assertion below
                    failures.append(error)

            threads = [threading.Thread(target=merge, args=(key,)) for key in keys]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()
            self.assertEqual(failures, [])
            merged = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(merged, {key: {"round": 24} for key in keys})
            self.assertEqual([p.name for p in target.glob("*.tmp")], [])


if __name__ == "__main__":
    unittest.main()
