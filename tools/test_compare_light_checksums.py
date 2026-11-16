"""compare_light_checksums.py: only cubes interior to both dumps are compared."""

import tempfile
import unittest
from pathlib import Path

import compare_light_checksums as compare


def write(path: Path, cubes: dict) -> Path:
    lines = ["x\ty\tz\tchecksum\tstorage\tfirstLight"]
    lines += [f"{x}\t{y}\t{z}\t{c}\t1\t1" for (x, y, z), c in cubes.items()]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def block(size: int, checksum: str = "a") -> dict:
    return {(x, y, z): checksum for x in range(size) for y in range(size) for z in range(size)}


class CompareTests(unittest.TestCase):
    def test_only_interior_cubes_count(self):
        with tempfile.TemporaryDirectory() as folder:
            a = block(4)
            b = block(4)
            b[(0, 0, 0)] = "edge"
            b[(1, 1, 1)] = "inner"
            result = compare.compare(
                compare.read(write(Path(folder) / "a.tsv", a)), compare.read(write(Path(folder) / "b.tsv", b))
            )
            self.assertEqual(result["compared"], 8)
            self.assertEqual(result["differing"], [(1, 1, 1)])
            self.assertEqual(result["byCubeY"], {1: 1})

    def test_a_cube_held_by_one_side_only_is_not_compared(self):
        with tempfile.TemporaryDirectory() as folder:
            a = block(3)
            b = block(3)
            del b[(0, 0, 0)]
            result = compare.compare(
                compare.read(write(Path(folder) / "a.tsv", a)), compare.read(write(Path(folder) / "b.tsv", b))
            )
            self.assertEqual(result["compared"], 0)


if __name__ == "__main__":
    unittest.main()
