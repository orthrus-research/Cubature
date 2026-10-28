"""Pre-launch byte proofs; no JVM or Minecraft work."""
import hashlib
from pathlib import Path
import tempfile
import unittest

from lab_input_snapshot import INPUTS, retain_prelaunch_inputs


class InputSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(); self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.manifest = {side: str(self.root / side) for side in ("server", "client")}
        self.hashes = {"server": {}, "client": {}}
        for side, name in INPUTS:
            path = Path(self.manifest[side]) / name; path.parent.mkdir(parents=True, exist_ok=True)
            data = (side + "\r\n" + name + "\r\n").encode(); path.write_bytes(data)
            self.hashes[side][name] = hashlib.sha256(data).hexdigest()

    def test_snapshot_preserves_exact_bytes_and_relative_paths(self):
        evidence = self.root / "evidence"
        result = retain_prelaunch_inputs(self.manifest, self.hashes, evidence)
        self.assertEqual(result["schema"], 1)
        for side, name in INPUTS:
            record = result["files"][side][name]
            self.assertFalse(Path(record["path"]).is_absolute())
            data = (evidence / record["path"]).read_bytes()
            self.assertEqual(data, (Path(self.manifest[side]) / name).read_bytes())
            self.assertEqual(hashlib.sha256(data).hexdigest(), self.hashes[side][name])
            self.assertEqual(len(data), record["bytes"])
            self.assertTrue(Path(record["source"]).samefile(Path(self.manifest[side]) / name))

    def test_changed_input_fails_without_replacing_existing_evidence(self):
        path = Path(self.manifest["server"]) / "config/cubicchunks.cfg"; path.write_bytes(b"changed")
        with self.assertRaisesRegex(RuntimeError, "input changed"):
            retain_prelaunch_inputs(self.manifest, self.hashes, self.root / "changed-proof")
        path.write_bytes(b"server\r\nconfig/cubicchunks.cfg\r\n")
        evidence = self.root / "retained"
        retain_prelaunch_inputs(self.manifest, self.hashes, evidence)
        with self.assertRaises(FileExistsError):
            retain_prelaunch_inputs(self.manifest, self.hashes, evidence)

    def test_workload_byte_proofs_cover_both_native_sides(self):
        self.manifest['benchmarkWorkload']={'id':'fixture'}
        for side in ['server','client']:
            name='config/cubiclab-workload.json';path=Path(self.manifest[side])/name
            path.write_bytes(b'{"seed":7}\r\n');self.hashes[side][name]=hashlib.sha256(path.read_bytes()).hexdigest()
        evidence=self.root/'workload-proof';result=retain_prelaunch_inputs(self.manifest,self.hashes,evidence)
        for side in ['server','client']:
            record=result['files'][side]['config/cubiclab-workload.json']
            self.assertEqual((evidence/record['path']).read_bytes(),b'{"seed":7}\r\n')

if __name__ == "__main__":
    unittest.main()
