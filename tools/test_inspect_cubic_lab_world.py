"""Check that outcome fingerprints distinguish block changes from asynchronous lighting."""
import unittest
import copy
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import nbtlib
from inspect_cubic_lab_world import (section_state_bytes, section_light_bytes, maintenance_block_digest,
    normalize_maintenance_witness, load_maintenance_witness, inspect_maintenance_cube, inspect)


class SectionFingerprintTest(unittest.TestCase):
    def test_tag_order_and_lighting_do_not_change_block_state_fingerprint(self):
        first = nbtlib.Compound({'Y': nbtlib.Byte(0), 'Blocks': nbtlib.ByteArray([1, 2]), 'SkyLight': nbtlib.ByteArray([0])})
        second = nbtlib.Compound({'SkyLight': nbtlib.ByteArray([15]), 'Blocks': nbtlib.ByteArray([1, 2]), 'Y': nbtlib.Byte(0)})
        self.assertEqual(section_state_bytes([first]), section_state_bytes([second]))
        second['Blocks'][1] = 3
        self.assertNotEqual(section_state_bytes([first]), section_state_bytes([second]))

    def test_exact_light_fingerprint_ignores_blocks_but_detects_both_light_arrays(self):
        first = nbtlib.Compound({'Y': nbtlib.Byte(-1), 'Blocks': nbtlib.ByteArray([1]),
            'BlockLight': nbtlib.ByteArray([0] * 2048), 'SkyLight': nbtlib.ByteArray([0] * 2048)})
        second = nbtlib.Compound(dict(reversed(list(first.items()))))
        second['Blocks'] = nbtlib.ByteArray([2])
        self.assertEqual(section_light_bytes([first]), section_light_bytes([second]))
        for key in ('BlockLight', 'SkyLight'):
            changed = nbtlib.Compound(dict(first)); changed[key] = nbtlib.ByteArray([0] * 2047 + [15])
            self.assertNotEqual(section_light_bytes([first]), section_light_bytes([changed]))
        second['Y'] = nbtlib.Byte(0)
        self.assertNotEqual(section_light_bytes([first]), section_light_bytes([second]))

    def test_light_fingerprint_rejects_missing_or_truncated_nibble_arrays(self):
        for section in [nbtlib.Compound(), nbtlib.Compound({'BlockLight': nbtlib.ByteArray([0])})]:
            with self.assertRaises(ValueError): section_light_bytes([section])


class MaintenanceWitnessTest(unittest.TestCase):
    @staticmethod
    def cube(coords=(0, 0, 0), block_id=41):
        section = nbtlib.Compound({'Y': nbtlib.Byte(coords[1]), 'Blocks': nbtlib.ByteArray([0] * 4096),
                                   'Data': nbtlib.ByteArray([0] * 2048)})
        cell = (1 << 8) | (1 << 4) | 1
        section['Blocks'][cell] = (block_id & 255) if (block_id & 255) < 128 else (block_id & 255) - 256
        for name, shift in [('Add', 8), ('Add2', 12)]:
            if block_id >> shift:
                section[name] = nbtlib.ByteArray([0] * 2048)
                section[name][cell // 2] = ((block_id >> shift) & 15) << 4
        return nbtlib.Compound(dict(zip(('x', 'y', 'z'), (nbtlib.Int(value) for value in coords))) | {
            'Sections': nbtlib.List[nbtlib.Compound]([section]), 'fullyPopulated': nbtlib.Byte(1),
            'initLightDone': nbtlib.Byte(1), 'isSurfaceTracked': nbtlib.Byte(1)})

    @staticmethod
    def witness(cube):
        return {'cube': [int(cube[key]) for key in ('x', 'y', 'z')], 'markerLocal': [1, 1, 1],
                'markerBlock': 'minecraft:gold_block', 'blockArraySha256': maintenance_block_digest(cube)}

    def rows(self):
        return [self.witness(self.cube((x, y, 0))) for x in range(4) for y in range(4)]

    def test_digest_matches_independently_framed_java_protocol_and_excludes_lighting(self):
        cube = self.cube((-2, -1, 3))
        blocks = bytearray(4096); blocks[273] = 41
        encoded = struct.pack('>iiiii', -2, -1, 3, 1, -1)
        for name, data in [(b'Blocks', bytes(blocks)), (b'Data', bytes(2048)), (b'Add', b''), (b'Add2', b'')]:
            encoded += name + struct.pack('>i', len(data)) + data
        expected = hashlib.sha256(encoded).hexdigest()
        self.assertEqual(expected, maintenance_block_digest(cube))
        cube['Sections'][0]['SkyLight'] = nbtlib.ByteArray([7] * 2048)
        cube['Sections'][0]['BlockLight'] = nbtlib.ByteArray([3] * 2048)
        self.assertEqual(expected, maintenance_block_digest(cube))

    def test_digest_detects_coordinates_section_y_and_each_block_array(self):
        cube = self.cube(); original = maintenance_block_digest(cube)
        for key in ('x', 'y', 'z'):
            changed = copy.deepcopy(cube); changed[key] = nbtlib.Int(3)
            self.assertNotEqual(original, maintenance_block_digest(changed))
        changed = copy.deepcopy(cube); changed['Sections'][0]['Y'] = nbtlib.Byte(1)
        self.assertNotEqual(original, maintenance_block_digest(changed))
        for name in ('Blocks', 'Data', 'Add', 'Add2'):
            changed = copy.deepcopy(cube)
            changed['Sections'][0][name] = nbtlib.ByteArray([1] + [0] * (4095 if name == 'Blocks' else 2047))
            self.assertNotEqual(original, maintenance_block_digest(changed))

    def test_digest_rejects_missing_truncated_or_wrong_typed_arrays(self):
        for name in ('Blocks', 'Data', 'Add', 'Add2'):
            changed = self.cube(); changed['Sections'][0][name] = nbtlib.ByteArray([1])
            with self.assertRaises(ValueError): maintenance_block_digest(changed)
        changed = self.cube(); del changed['Sections'][0]['Blocks']
        with self.assertRaises(ValueError): maintenance_block_digest(changed)
        changed = self.cube(); changed['Sections'][0]['Data'] = nbtlib.IntArray([0] * 2048)
        with self.assertRaises(ValueError): maintenance_block_digest(changed)

    def test_witness_requires_all_unique_bounded_cubes_and_passed_reports(self):
        rows = self.rows()
        self.assertEqual(16, len(normalize_maintenance_witness(rows)))
        report = {'schema': 'cubiclab.server.v1', 'scenario': 'maintenance-contracts', 'passed': True,
                  'maintenanceChecks': {'passed': True, 'savedWitnesses': rows}}
        self.assertEqual(normalize_maintenance_witness(rows), normalize_maintenance_witness(report, True))
        invalid = [rows[:-1], rows + [rows[0]], [rows[0]] * 16, {'passed': False, 'savedWitnesses': rows}]
        for document in invalid:
            with self.assertRaises(ValueError): normalize_maintenance_witness(document)
        for key, value in [('passed', False), ('scenario', 'generate'), ('schema', 'other')]:
            changed = copy.deepcopy(report); changed[key] = value
            with self.assertRaises(ValueError): normalize_maintenance_witness(changed, True)
        report['maintenanceChecks']['passed'] = False
        with self.assertRaises(ValueError): normalize_maintenance_witness(report, True)
        for key, value in [('cube', [True, 0, 0]), ('cube', [1 << 31, 0, 0]), ('markerLocal', [16, 1, 1]),
                           ('markerBlock', 'minecraft:air'), ('blockArraySha256', 'not-a-digest')]:
            changed = copy.deepcopy(rows); changed[0][key] = value
            with self.assertRaises(ValueError): normalize_maintenance_witness(changed)

    def test_marker_uses_full_add_and_add2_registry_id(self):
        cube = self.cube(block_id=0x1234); witness = self.witness(cube)
        result = inspect_maintenance_cube(cube, witness, {0x1234: 'minecraft:gold_block'})
        self.assertTrue(result['completeLightFlags'])
        with self.assertRaises(ValueError): inspect_maintenance_cube(cube, witness, {0x1234: 'minecraft:stone'})

    def test_saved_digest_marker_coordinates_and_complete_flags_are_independent_gates(self):
        cube = self.cube(); witness = self.witness(cube); registry = {41: 'minecraft:gold_block'}
        changed = copy.deepcopy(cube); changed['x'] = nbtlib.Int(1)
        with self.assertRaises(ValueError): inspect_maintenance_cube(changed, witness, registry)
        changed = copy.deepcopy(cube); changed['Sections'][0]['Blocks'][0] = 1
        with self.assertRaises(ValueError): inspect_maintenance_cube(changed, witness, registry)
        changed = copy.deepcopy(cube); changed['Sections'][0]['Blocks'][273] = 0
        with self.assertRaises(ValueError): inspect_maintenance_cube(changed, self.witness(changed), registry)
        for flag in ('fullyPopulated', 'initLightDone', 'isSurfaceTracked'):
            changed = copy.deepcopy(cube); changed[flag] = nbtlib.Byte(0)
            with self.assertRaises(ValueError): inspect_maintenance_cube(changed, witness, registry)

    def test_witness_file_retains_exact_source_hash_and_accepts_utf8_bom(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'witness.json'
            data = b'\xef\xbb\xbf' + json.dumps({'savedWitnesses': self.rows()}).encode()
            path.write_bytes(data)
            loaded = load_maintenance_witness(path)
            self.assertEqual(16, len(loaded['entries']))
            self.assertEqual(hashlib.sha256(data).hexdigest(), loaded['source']['sha256'])

    @staticmethod
    def write_nbt(path, root):
        stream = io.BytesIO(); nbtlib.File(root).write(stream)
        path.write_bytes(gzip.compress(stream.getvalue()))

    def world(self, root, omit_last=False):
        (root / 'region3d').mkdir(); (root / 'data').mkdir()
        self.write_nbt(root / 'level.dat', {'Data': nbtlib.Compound({'generatorName': nbtlib.String('test'), 'RandomSeed': nbtlib.Long(7)}),
            'FML': nbtlib.Compound({'Registries': nbtlib.Compound({'minecraft:blocks': nbtlib.Compound({'ids': nbtlib.List[nbtlib.Compound]([
                nbtlib.Compound({'K': nbtlib.String('minecraft:gold_block'), 'V': nbtlib.Int(41)})])})}),
                'ModList': nbtlib.List[nbtlib.Compound]([])})})
        self.write_nbt(root / 'data/cubicChunksData.dat', {'data': nbtlib.Compound({'minHeight': nbtlib.Int(-128),
            'maxHeight': nbtlib.Int(384), 'isCubicChunks': nbtlib.Byte(1), 'storageFormat': nbtlib.String('region')})})
        body = bytearray(16384); cubes = [self.cube((x, y, 0)) for x in range(4) for y in range(4)]
        for cube in cubes[:-1] if omit_last else cubes:
            stream = io.BytesIO(); nbtlib.File({'Level': cube}).write(stream)
            payload = gzip.compress(stream.getvalue()); sectors = (len(payload) + 4 + 511) // 512
            index = (int(cube['x']) << 8) | (int(cube['y']) << 4)
            struct.pack_into('>I', body, index * 4, ((len(body) // 512) << 8) | sectors)
            body.extend(struct.pack('>I', len(payload)) + payload + bytes(sectors * 512 - len(payload) - 4))
        (root / 'region3d/0.0.0.3dr').write_bytes(body)
        return {'entries': normalize_maintenance_witness([self.witness(cube) for cube in cubes])}

    def test_independent_region_reader_verifies_maintenance_without_unrelated_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); witness = self.world(root)
            result = inspect(root, maintenance_witness=witness)
            self.assertTrue(result['passed']); self.assertEqual(16, result['verifiedMarkers'])
            self.assertEqual('maintenance-witness', result['inspectionMode'])
            self.assertEqual(16, result['maintenanceFixture']['verifiedCubes'])
            self.assertFalse(result['defaultFixtureMarkers']['required'])
            self.assertFalse(inspect(root)['passed'])

    def test_independent_reader_rejects_missing_maintenance_cube(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); witness = self.world(root, omit_last=True)
            with self.assertRaisesRegex(ValueError, 'Missing saved maintenance cubes'):
                inspect(root, maintenance_witness=witness)

    def test_maintenance_only_ignores_unrequested_default_markers_in_ordinary_background(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); witness = self.world(root)
            cube = self.cube((128, 0, 128)); cube['Sections'][0]['Blocks'][273] = 0
            stream = io.BytesIO(); nbtlib.File({'Level': cube}).write(stream)
            payload = gzip.compress(stream.getvalue()); sectors = (len(payload) + 4 + 511) // 512
            body = bytearray(16384); struct.pack_into('>I', body, 0, (32 << 8) | sectors)
            body.extend(struct.pack('>I', len(payload)) + payload + bytes(sectors * 512 - len(payload) - 4))
            (root / 'region3d/8.0.8.3dr').write_bytes(body)
            result = inspect(root, maintenance_witness=witness)
            self.assertTrue(result['passed']); self.assertEqual(17, result['savedCubes'])
            self.assertEqual(16, result['maintenanceFixture']['verifiedCubes'])

    def test_maintenance_flag_does_not_weaken_explicit_first_light_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); witness = self.world(root)
            with self.assertRaisesRegex(ValueError, 'Lighting fixture'):
                inspect(root, include_lighting=True, maintenance_witness=witness)

    def test_custom_full_height_negative_cohorts_are_independently_read_and_seed_checked(self):
        from lab_workload import fixture_columns
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.world(root)
            workload={'schema':1,'id':'custom','seed':7,'camera':[0,80,0,0,28],'scenarios':{'write':['generate','save']},
                'fixture':{'originColumn':[-5,-9],'sizeColumns':[1,1],'cohortOffset':[4,0]}}
            bodies={}
            for x,z in fixture_columns(workload):
                for y in range(-8,24):
                    cube=self.cube((x,y,z));cube['Sections'][0]['Blocks'][2184]=41
                    stream=io.BytesIO();nbtlib.File({'Level':cube}).write(stream)
                    payload=gzip.compress(stream.getvalue());sectors=(len(payload)+4+511)//512
                    body=bodies.setdefault((x>>4,y>>4,z>>4),bytearray(16384));index=((x&15)<<8)|((y&15)<<4)|(z&15)
                    struct.pack_into('>I',body,index*4,((len(body)//512)<<8)|sectors)
                    body.extend(struct.pack('>I',len(payload))+payload+bytes(sectors*512-len(payload)-4))
            for region,body in bodies.items():(root/'region3d'/('.'.join(map(str,region))+'.3dr')).write_bytes(body)
            result=inspect(root,workload=workload)
            self.assertTrue(result['passed']);self.assertEqual(64,result['expectedMarkers']);self.assertEqual(64,result['verifiedMarkers'])
            workload['seed']=8
            with self.assertRaisesRegex(ValueError,'seed differs'):inspect(root,workload=workload)


if __name__ == '__main__': unittest.main()
