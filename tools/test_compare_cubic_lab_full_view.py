"""Synthetic RegionLib/NBT contracts; never open a real Minecraft world."""
import copy
import gzip
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import nbtlib
import compare_cubic_lab_full_view as compare
from inspect_cubic_lab_world import read_regionlib_entries


class FullViewComparisonTest(unittest.TestCase):
    @staticmethod
    def manifest(x=10112.5, y=128, z=4256.5):
        return {'schema': 1, 'viewDistance': {'horizontal': 12, 'vertical': 12, 'clientRender': 12},
                'labProperties': {'cubiclab.viewX': str(x), 'cubiclab.viewY': str(y), 'cubiclab.viewZ': str(z)}}

    @staticmethod
    def cube(position=(0, 0, 0), empty=False):
        value = nbtlib.Compound({'v': nbtlib.Byte(1), **{key: nbtlib.Int(cell) for key, cell in zip(('x', 'y', 'z'), position)},
            **{key: nbtlib.Byte(1) for key in compare.FLAGS}, 'TileEntities': nbtlib.List[nbtlib.Compound]([])})
        if not empty:
            value['Sections'] = nbtlib.List[nbtlib.Compound]([nbtlib.Compound({
                'Blocks': nbtlib.ByteArray([0] * 4096), 'Data': nbtlib.ByteArray([0] * 2048),
                'BlockLight': nbtlib.ByteArray([0] * 2048), 'SkyLight': nbtlib.ByteArray([0] * 2048)})])
        return value

    @staticmethod
    def column(position=(0, 0)):
        return nbtlib.Compound({'v': nbtlib.Byte(1), 'x': nbtlib.Int(position[0]), 'z': nbtlib.Int(position[1]),
            'Biomes': nbtlib.ByteArray([1] * 256), 'OpacityIndex': nbtlib.ByteArray([0] * 2560)})

    @staticmethod
    def snapshot(cube):
        return compare.cube_snapshot(cube, (0, 0, 0), {0: 'minecraft:air', 1: 'minecraft:stone', 0x1241: 'riven:test_block'}, {1: 'minecraft:plains', 2: 'minecraft:forest'})

    @staticmethod
    def region(path, records, entries=4096):
        header, body = bytearray(entries * 4), bytearray()
        sector = entries * 4 // 512
        for index, value in sorted(records.items()):
            raw = io.BytesIO(); nbtlib.File({'Level': value}).write(raw)
            compressed = gzip.compress(raw.getvalue(), mtime=0)
            size = (len(compressed) + 4 + 511) // 512
            struct.pack_into('>I', header, index * 4, sector << 8 | size)
            payload = struct.pack('>I', len(compressed)) + compressed
            body.extend(payload + b'\0' * (size * 512 - len(payload))); sector += size
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(header + body)

    def test_exact_camera_derived_set_and_negative_floor(self):
        view = compare.geometry(self.manifest())
        cubes, columns = compare.coordinates(view)
        self.assertEqual((len(cubes), len(columns)), (15625, 625))
        self.assertEqual(view['minimumCube'], [620, -4, 254])
        self.assertEqual(view['maximumCube'], [644, 20, 278])
        self.assertIn((620, -4, 254), cubes); self.assertIn((644, 20, 278), cubes)
        negative = compare.geometry(self.manifest(-.5, -1, -16.5))
        self.assertEqual(negative['centerCube'], [-1, -1, -2])
        for mutation in [self.manifest(y=float('nan')), self.manifest(y=384), {'schema': 1}]:
            with self.assertRaises(ValueError): compare.geometry(mutation)
        wrong = self.manifest(); wrong['viewDistance']['vertical'] = 11
        with self.assertRaises(ValueError): compare.geometry(wrong)

    def test_block_metadata_and_both_extension_nibbles_are_resolved(self):
        first = self.cube(); baseline = self.snapshot(first)
        changed = copy.deepcopy(first); section = changed['Sections'][0]
        section['Blocks'][0] = 0x41
        section['Add'] = nbtlib.ByteArray([2] + [0] * 2047)
        section['Add2'] = nbtlib.ByteArray([1] + [0] * 2047)
        high = self.snapshot(changed)
        self.assertNotEqual(baseline['stateNamesMetadataSha256'], high['stateNamesMetadataSha256'])
        section['Data'][0] = 7
        metadata = self.snapshot(changed)
        self.assertNotEqual(metadata['stateNamesMetadataSha256'], high['stateNamesMetadataSha256'])
        self.assertNotEqual(metadata['blockArraysSha256'], high['blockArraysSha256'])
        self.assertEqual(metadata['savedLightSha256'], baseline['savedLightSha256'])

    def test_optional_zero_arrays_keep_raw_and_semantic_fingerprints_separate(self):
        cube = self.cube(); before = self.snapshot(cube)
        cube['Sections'][0]['Add'] = nbtlib.ByteArray([0] * 2048)
        after = self.snapshot(cube)
        self.assertEqual(before['stateNamesMetadataSha256'], after['stateNamesMetadataSha256'])
        self.assertNotEqual(before['blockArraysSha256'], after['blockArraysSha256'])

    def test_each_light_array_difference_is_explicit_and_not_a_block_change(self):
        baseline = self.snapshot(self.cube())
        for key in ('BlockLight', 'SkyLight'):
            cube = self.cube(); cube['Sections'][0][key][0] = 1
            changed = self.snapshot(cube)
            self.assertNotEqual(baseline['savedLightSha256'], changed['savedLightSha256'])
            self.assertEqual(baseline['stateNamesMetadataSha256'], changed['stateNamesMetadataSha256'])
            self.assertEqual(baseline['blockArraysSha256'], changed['blockArraysSha256'])

    def test_absent_storage_is_supported_without_inventing_saved_light(self):
        empty, explicit = self.snapshot(self.cube(empty=True)), self.snapshot(self.cube())
        self.assertEqual(empty['stateNamesMetadataSha256'], explicit['stateNamesMetadataSha256'])
        self.assertNotEqual(empty['savedLightSha256'], explicit['savedLightSha256'])
        self.assertEqual(empty['lightRepresentation'], 'absent-storage-section')

    def test_missing_malformed_and_unknown_cube_data_fail(self):
        mutations = [lambda c: c.pop('TileEntities'), lambda c: c.pop('fullyPopulated'),
            lambda c: c.__setitem__('v', nbtlib.Byte(2)), lambda c: c.__setitem__('x', nbtlib.Int(1)),
            lambda c: c['Sections'][0].pop('SkyLight'), lambda c: c['Sections'][0].__setitem__('Data', nbtlib.ByteArray([0])),
            lambda c: c['Sections'][0].__setitem__('UnsupportedPalette', nbtlib.Int(1)),
            lambda c: c.__setitem__('Sections', nbtlib.List[nbtlib.Compound]([])),
            lambda c: c['Sections'][0]['Blocks'].__setitem__(0, 2)]
        for mutate in mutations:
            cube = self.cube(); mutate(cube)
            with self.assertRaises(ValueError): self.snapshot(cube)

    def test_complete_flags_remain_visible_when_false(self):
        cube = self.cube(); cube['initLightDone'] = nbtlib.Byte(0)
        self.assertFalse(self.snapshot(cube)['flags']['initLightDone'])
        cube['initLightDone'] = nbtlib.Byte(2)
        with self.assertRaises(ValueError): self.snapshot(cube)

    def test_tile_entity_order_and_compound_order_are_canonical_but_contents_and_types_matter(self):
        cube = self.cube()
        entities = [nbtlib.Compound({'id': nbtlib.String('minecraft:chest'), 'x': nbtlib.Int(x), 'y': nbtlib.Int(1), 'z': nbtlib.Int(1),
            'Items': nbtlib.List[nbtlib.Int]([1, 2]), 'Energy': nbtlib.Long(4)}) for x in (1, 2)]
        cube['TileEntities'] = nbtlib.List[nbtlib.Compound](entities)
        first = self.snapshot(cube)['tileEntitiesSha256']
        cube['TileEntities'] = nbtlib.List[nbtlib.Compound]([nbtlib.Compound(dict(reversed(list(value.items())))) for value in reversed(entities)])
        self.assertEqual(first, self.snapshot(cube)['tileEntitiesSha256'])
        cube['TileEntities'][0]['Energy'] = nbtlib.Int(4)
        self.assertNotEqual(first, self.snapshot(cube)['tileEntitiesSha256'])
        cube['TileEntities'][0]['x'] = nbtlib.Int(16)
        with self.assertRaises(ValueError): self.snapshot(cube)

    def test_column_biomes_and_opacity_context_are_independent(self):
        original = self.column(); biomes = {1: 'minecraft:plains', 2: 'minecraft:forest'}
        baseline = compare.column_snapshot(original, (0, 0), biomes)
        original['Biomes'][0] = 2
        changed = compare.column_snapshot(original, (0, 0), biomes)
        self.assertNotEqual(baseline['biomesSha256'], changed['biomesSha256'])
        self.assertNotEqual(baseline['biomeNamesSha256'], changed['biomeNamesSha256'])
        self.assertEqual(baseline['opacityIndexSha256'], changed['opacityIndexSha256'])
        original['OpacityIndex'][0] = 1
        self.assertNotEqual(changed['opacityIndexSha256'], compare.column_snapshot(original, (0, 0), biomes)['opacityIndexSha256'])
        original['Biomes'] = nbtlib.ByteArray([1])
        with self.assertRaises(ValueError): compare.column_snapshot(original, (0, 0), biomes)

    def test_cube_biomes_are_not_silently_dropped(self):
        cube = self.cube(); first = self.snapshot(cube)
        cube['Biomes3D'] = nbtlib.ByteArray([1] * 64)
        self.assertNotEqual(first['cubeBiomesSha256'], self.snapshot(cube)['cubeBiomesSha256'])
        cube['Biomes3D'][0] = 2
        changed = self.snapshot(cube)
        cube['Biomes3D'][0] = 1
        self.assertNotEqual(changed['cubeBiomesSha256'], self.snapshot(cube)['cubeBiomesSha256'])

    def test_regionlib_reader_and_indexing_at_negative_coordinates(self):
        with tempfile.TemporaryDirectory() as temp:
            world = Path(temp); path = world / 'region3d/-1.-1.-1.3dr'
            self.region(path, {4095: self.cube((-1, -1, -1)), 0: self.cube((-16, -16, -16))})
            rows = list(compare.selected_records(world, {(-1, -1, -1)}, 3))
            self.assertEqual(len(rows), 1); self.assertEqual(rows[0][0], (-1, -1, -1))
            self.assertEqual(int(rows[0][1]['x']), -1)
            self.assertEqual(len(rows[0][2]['payloadSha256']), 64)
            self.assertEqual(list(compare.selected_records(world, {(30, 0, 0)}, 3)), [])

    def test_regionlib_missing_entries_overflow_truncation_and_inflation_bounds(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / '0.0.0.3dr'
            self.region(path, {0: self.cube()})
            self.assertEqual(list(read_regionlib_entries(path, [1], 4096)), [])
            with self.assertRaises(ValueError): list(read_regionlib_entries(path, [0], 4096, maximum_nbt_bytes=16))
            body = bytearray(path.read_bytes()); struct.pack_into('>I', body, 0, 0xffffffff); path.write_bytes(body)
            with self.assertRaisesRegex(ValueError, 'special'): list(read_regionlib_entries(path, [0], 4096))
            path.write_bytes(b'\0' * 10)
            with self.assertRaisesRegex(ValueError, 'header'): list(read_regionlib_entries(path, [0], 4096))

    def test_concurrent_region_mutation_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / '0.0.0.3dr'; self.region(path, {0: self.cube()})
            reader = read_regionlib_entries(path, [0], 4096); next(reader)
            with path.open('r+b') as stream: stream.write(b'\0\0\0\0')
            with self.assertRaisesRegex(ValueError, 'changed'): list(reader)

    def test_registry_duplicates_and_missing_identity_fail(self):
        entries = nbtlib.List[nbtlib.Compound]([nbtlib.Compound({'K': nbtlib.String('minecraft:air'), 'V': nbtlib.Int(0)})])
        level = {'FML': {'Registries': {'minecraft:blocks': {'ids': entries}}}}
        self.assertEqual(compare.registry_map(level, 'minecraft:blocks'), {0: 'minecraft:air'})
        entries.append(entries[0])
        with self.assertRaisesRegex(ValueError, 'Duplicate'): compare.registry_map(level, 'minecraft:blocks')
        with self.assertRaisesRegex(ValueError, 'Missing'): compare.registry_map(level, 'minecraft:biomes')

    def test_empty_generator_options_remain_distinct_from_json(self):
        with tempfile.TemporaryDirectory() as directory:
            world = Path(directory)
            (world / 'data').mkdir()
            for name in ('level.dat', 'data/cubicChunksData.dat'):
                (world / name).write_bytes(b'metadata-hash-fixture')
            level = {'Data': {'RandomSeed': 8675309, 'generatorName': 'riven', 'generatorOptions': ''},
                     'FML': {'Registries': {name: {'ids': nbtlib.List[nbtlib.Compound]([
                         nbtlib.Compound({'K': nbtlib.String(entry), 'V': nbtlib.Int(0)})])}
                             for name, entry in [('minecraft:blocks', 'minecraft:air'), ('minecraft:biomes', 'minecraft:plains')]}}}
            cubic = {'data': {'isCubicChunks': 1, 'minHeight': -128, 'maxHeight': 384, 'storageFormat': 'anvil3d'}}
            with patch.object(compare.nbtlib, 'load', side_effect=lambda path: level if path.name == 'level.dat' else cubic):
                self.assertEqual(compare.world_metadata(world)['generatorOptions'], '')
                level['Data']['generatorOptions'] = '{}'
                self.assertEqual(compare.world_metadata(world)['generatorOptions'], {})
                for invalid in (' ', 'not-json'):
                    level['Data']['generatorOptions'] = invalid
                    with self.assertRaises(json.JSONDecodeError): compare.world_metadata(world)

    def test_all_15625_coordinates_are_required_and_light_mismatch_fails(self):
        view = compare.geometry(self.manifest()); cubes, columns = compare.coordinates(view)
        row = self.snapshot(self.cube()); column = compare.column_snapshot(self.column(), (0, 0), {1: 'minecraft:plains'})
        metadata = {key: 'same' for key in ('seed', 'generator', 'generatorOptions', 'storage', 'minY', 'maxYExclusive', 'blockRegistry', 'biomeRegistry')}
        first = {'world': 'synthetic-baseline', 'metadata': metadata, 'cubes': {pos: row for pos in cubes}, 'columns': {pos: column for pos in columns},
                 'missingCubes': [], 'missingColumns': [], 'incompleteCubes': [], 'sources': []}
        second = {**first, 'world': 'synthetic-candidate', 'cubes': dict(first['cubes'])}
        self.assertTrue(compare.compare_snapshots(first, second, view)['passed'])
        coordinate = (620, -4, 254)
        second['cubes'][coordinate] = {**row, 'savedLightSha256': 'ab' * 32}
        report = compare.compare_snapshots(first, second, view)
        self.assertFalse(report['passed']); self.assertTrue(report['completeSavedCoverage'])
        self.assertEqual(report['differences']['savedLightSha256']['coordinates'], [list(coordinate)])
        self.assertEqual(report['differences']['stateNamesMetadataSha256']['count'], 0)
        del second['cubes'][coordinate]
        self.assertFalse(compare.compare_snapshots(first, second, view)['completeSavedCoverage'])

    def test_equal_hashes_cannot_override_registry_mismatch_or_incomplete_flags(self):
        view = compare.geometry(self.manifest()); cubes, columns = compare.coordinates(view)
        row = self.snapshot(self.cube()); column = compare.column_snapshot(self.column(), (0, 0), {1: 'minecraft:plains'})
        metadata = {key: 'same' for key in ('seed', 'generator', 'generatorOptions', 'storage', 'minY', 'maxYExclusive', 'blockRegistry', 'biomeRegistry')}
        first = {'world': 'synthetic', 'metadata': metadata, 'cubes': {pos: row for pos in cubes}, 'columns': {pos: column for pos in columns},
                 'missingCubes': [], 'missingColumns': [], 'incompleteCubes': [], 'sources': []}
        second = {**first, 'metadata': {**metadata, 'blockRegistry': 'different'}}
        report = compare.compare_snapshots(first, second, view)
        self.assertFalse(report['passed']); self.assertEqual(report['metadataMismatches'], ['blockRegistry'])
        second = {**first, 'incompleteCubes': [[620, -4, 254]]}
        self.assertFalse(compare.compare_snapshots(first, second, view)['passed'])


if __name__ == '__main__': unittest.main()
