import gzip
import hashlib
import io
from pathlib import Path
import struct
import tempfile
import unittest

import nbtlib
import numpy as np

from inspect_cubic_lab_world import inspect_fixture_heightmaps


class HeightmapInspectionTests(unittest.TestCase):
    def fixture(self, root, heightmap, bad_coordinate=False):
        regions = {}
        expected = hashlib.sha256()
        for x, z in sorted((x, z) for base in [128, 160] for x in range(base, base + 4) for z in range(128, 132)):
            column = nbtlib.Compound({'x': nbtlib.Int(x + int(bad_coordinate)), 'z': nbtlib.Int(z),
                                     'OpacityIndex': nbtlib.ByteArray(np.frombuffer(heightmap, dtype=np.int8))})
            stream = io.BytesIO()
            nbtlib.File({'Level': column}).write(stream)
            compressed = gzip.compress(stream.getvalue(), mtime=0)
            key = (x // 32, z // 32)
            body = regions.setdefault(key, bytearray(4096))
            offset = len(body)
            sectors = (len(compressed) + 4 + 511) // 512
            struct.pack_into('>I', body, (((x % 32) * 32) + z % 32) * 4, (offset // 512) * 256 + sectors)
            body.extend(struct.pack('>I', len(compressed)) + compressed)
            body.extend(bytes(offset + sectors * 512 - len(body)))
            expected.update(struct.pack('>iiI', x, z, len(heightmap)))
            expected.update(heightmap)
        directory = root / 'region2d'
        directory.mkdir()
        for (x, z), body in regions.items(): (directory / f'{x}.{z}.2dr').write_bytes(body)
        return expected.hexdigest()

    def test_exact_fixture_hash_and_all_block_columns(self):
        data = struct.pack('>iiH', -128, 63, 0) * 256
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            expected = self.fixture(root, data)
            actual = inspect_fixture_heightmaps(root)
        self.assertEqual((32, 8192, expected), (actual['columns'], actual['blockColumns'], actual['sha256']))
        self.assertTrue(all(row['segmentStarts'] == 0 for row in actual['entries']))

    def test_rejects_invalid_segment_tables(self):
        empty = struct.pack('>iiH', -128, 63, 0)
        bad = [struct.pack('>iiHii', 0, 3, 2, 0, 2), struct.pack('>iiHiii', 0, 3, 3, 0, 2, 1),
               struct.pack('>iiHi', 0, 3, 3, 0)]
        for header in bad:
            with self.subTest(header=header), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                self.fixture(root, header + empty * 255)
                with self.assertRaises(ValueError): inspect_fixture_heightmaps(root)

    def test_rejects_mismatched_column_coordinates(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root, struct.pack('>iiH', 0, 5, 0) * 256, bad_coordinate=True)
            with self.assertRaisesRegex(ValueError, 'coordinate'): inspect_fixture_heightmaps(root)

    def test_rejects_a_truncated_region_entry(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root, struct.pack('>iiH', 0, 5, 0) * 256)
            path = root / 'region2d/4.4.2dr'
            path.write_bytes(path.read_bytes()[:4099])
            with self.assertRaises(ValueError): inspect_fixture_heightmaps(root)


if __name__ == '__main__': unittest.main()
