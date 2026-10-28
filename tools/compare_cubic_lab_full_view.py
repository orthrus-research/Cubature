#!/usr/bin/env python3
"""Compare every saved cube in two matched full views, only after both worlds are stopped.

Independent RegionLib/NBT readback; no game classes, marker shortcuts, generation or mutation.
"""
import argparse
from collections import defaultdict
import hashlib
import io
import json
import math
from pathlib import Path
import struct

import nbtlib
import numpy as np
from inspect_cubic_lab_world import read_regionlib_entries


FLAGS = ('populated', 'fullyPopulated', 'initLightDone', 'isSurfaceTracked')
CUBE_FIELDS = ('blockArraysSha256', 'stateNamesMetadataSha256', 'savedLightSha256', 'tileEntitiesSha256', 'cubeBiomesSha256')
COLUMN_FIELDS = ('biomesSha256', 'biomeNamesSha256', 'opacityIndexSha256')
MAX_EXAMPLES = 32


def sha(value):
    return hashlib.sha256(value).hexdigest()


def framed(value):
    return struct.pack('>I', len(value)) + value


def canonical_nbt(tag):
    """Sort compounds only; retain all list ordering, primitive types and array bytes."""
    def ordered(value, depth=0):
        if depth > 64: raise ValueError('NBT nesting exceeds supported bound')
        if isinstance(value, nbtlib.Compound):
            return nbtlib.Compound({key: ordered(value[key], depth + 1) for key in sorted(value)})
        if isinstance(value, nbtlib.List):
            return type(value)([ordered(child, depth + 1) for child in value])
        if isinstance(value, (nbtlib.Byte, nbtlib.Short, nbtlib.Int, nbtlib.Long, nbtlib.Float, nbtlib.Double,
                              nbtlib.ByteArray, nbtlib.String, nbtlib.IntArray, nbtlib.LongArray)):
            return value
        raise ValueError('Unsupported NBT type: ' + type(value).__name__)
    stream = io.BytesIO()
    nbtlib.File({'value': ordered(tag)}).write(stream)
    return stream.getvalue()


def integer(tag, name):
    value = tag.get(name)
    if not isinstance(value, nbtlib.Int): raise ValueError('Missing/invalid integer: ' + name)
    return int(value)


def byte_array(tag, name, length=None):
    value = tag.get(name)
    if not isinstance(value, nbtlib.ByteArray) or length is not None and len(value) != length:
        raise ValueError('Missing/invalid byte array: ' + name)
    return value.tobytes()


def registry_map(level, name):
    try: entries = level['FML']['Registries'][name]['ids']
    except KeyError as error: raise ValueError('Missing saved registry: ' + name) from error
    result, names = {}, set()
    if not isinstance(entries, nbtlib.List): raise ValueError('Unsupported registry ids list: ' + name)
    for entry in entries:
        ident = integer(entry, 'V')
        value = entry.get('K')
        if not isinstance(value, nbtlib.String) or not str(value) or not 0 <= ident <= 65535:
            raise ValueError('Invalid saved registry entry: ' + name)
        value = str(value)
        if ident in result or value in names: raise ValueError('Duplicate saved registry id/name: ' + name)
        result[ident] = value; names.add(value)
    if not result: raise ValueError('Empty saved registry: ' + name)
    return result


def geometry(manifest):
    properties = manifest.get('labProperties', {})
    try: position = tuple(float(properties['cubiclab.view' + axis]) for axis in ('X', 'Y', 'Z'))
    except (ValueError, TypeError, KeyError) as error: raise ValueError('Manifest must record explicit full-view camera coordinates') from error
    if not all(math.isfinite(value) for value in position) or abs(position[0]) > 29999000 or abs(position[2]) > 29999000 or not -128 <= position[1] < 384:
        raise ValueError('Invalid full-view camera')
    if manifest.get('schema') != 1 or manifest.get('viewDistance') != {'horizontal': 12, 'vertical': 12, 'clientRender': 12}:
        raise ValueError('Full-view manifest requires all recorded view distances 12')
    center = tuple(math.floor(value / 16) for value in position)
    return {'camera': list(position), 'centerCube': list(center), 'distance': 12, 'expectedCubes': 15625, 'expectedColumns': 625,
            'minimumCube': [value - 12 for value in center], 'maximumCube': [value + 12 for value in center]}


def coordinates(view):
    low, high = view['minimumCube'], view['maximumCube']
    cubes = {(x, y, z) for x in range(low[0], high[0] + 1) for y in range(low[1], high[1] + 1) for z in range(low[2], high[2] + 1)}
    columns = {(x, z) for x, _, z in cubes}
    if len(cubes) != 15625 or len(columns) != 625: raise ValueError('Incomplete full-view coordinate contract')
    return cubes, columns


def names_digest(ids, registry, metadata=None):
    """A compact reversible name palette plus every ordered cell index/metadata byte."""
    unique, inverse = np.unique(ids, return_inverse=True)
    encoded = hashlib.sha256(struct.pack('>I', len(unique)))
    for value in unique:
        if int(value) not in registry: raise ValueError('Unregistered saved id: ' + str(value))
        encoded.update(framed(registry[int(value)].encode('utf-8')))
    encoded.update(inverse.astype('>u2').tobytes())
    if metadata is not None: encoded.update(metadata.tobytes())
    return encoded.hexdigest()


def nibbles(data):
    values = np.frombuffer(data, dtype=np.uint8)
    result = np.empty(len(data) * 2, dtype=np.uint8)
    result[::2], result[1::2] = values & 15, values >> 4
    return result


def cube_snapshot(cube, position, blocks, biomes):
    if tuple(integer(cube, key) for key in ('x', 'y', 'z')) != position: raise ValueError('Cube/index coordinate mismatch')
    if not isinstance(cube.get('v'), nbtlib.Byte) or int(cube['v']) != 1: raise ValueError('Unsupported cube NBT version')
    flags = {}
    for key in FLAGS:
        if not isinstance(cube.get(key), nbtlib.Byte) or int(cube[key]) not in (0, 1): raise ValueError('Missing/invalid complete flag: ' + key)
        flags[key] = bool(cube[key])
    arrays, light = hashlib.sha256(), hashlib.sha256()
    if 'Sections' not in cube:
        air = [key for key, name in blocks.items() if name == 'minecraft:air']
        if len(air) != 1: raise ValueError('Missing unique air registration')
        ids, metadata = np.full(4096, air[0], dtype=np.uint16), np.zeros(4096, dtype=np.uint8)
        arrays.update(b'absent-section'); light.update(b'absent-section:implicit-column-heightmap-light')
        representation = 'absent-storage-section'
    else:
        sections = cube['Sections']
        if not isinstance(sections, nbtlib.List) or len(sections) != 1 or not isinstance(sections[0], nbtlib.Compound):
            raise ValueError('Unsupported cube section count/type')
        section = sections[0]
        if set(section) - {'Y', 'Blocks', 'Data', 'Add', 'Add2', 'BlockLight', 'SkyLight'}:
            raise ValueError('Unsupported section state tags: ' + str(sorted(set(section))))
        ids = np.frombuffer(byte_array(section, 'Blocks', 4096), dtype=np.uint8).astype(np.uint16)
        metadata = nibbles(byte_array(section, 'Data', 2048))
        arrays.update(b'saved-section')
        if 'Y' in section:
            if not isinstance(section['Y'], nbtlib.Byte): raise ValueError('Unsupported section Y type')
            arrays.update(b'Y' + struct.pack('>b', int(section['Y'])))
        for key, length in (('Blocks', 4096), ('Data', 2048), ('Add', 2048), ('Add2', 2048)):
            raw = byte_array(section, key, length) if key in section else b''
            arrays.update(framed(key.encode('ascii')) + framed(raw))
            if key in ('Add', 'Add2') and raw:
                ids |= nibbles(raw).astype(np.uint16) << (8 if key == 'Add' else 12)
        for key in ('BlockLight', 'SkyLight'):
            light.update(framed(key.encode('ascii')) + framed(byte_array(section, key, 2048)))
        representation = 'explicit-block-and-light-arrays'
    entities, seen = [], set()
    tile_entities = cube.get('TileEntities')
    if not isinstance(tile_entities, nbtlib.List): raise ValueError('Missing/invalid TileEntities list')
    for entity in tile_entities:
        if not isinstance(entity, nbtlib.Compound) or not isinstance(entity.get('id'), nbtlib.String): raise ValueError('Invalid saved tile entity')
        cell = tuple(integer(entity, key) for key in ('x', 'y', 'z'))
        if tuple(value >> 4 for value in cell) != position or cell in seen: raise ValueError('Tile entity coordinate/identity mismatch')
        seen.add(cell); entities.append(canonical_nbt(entity))
    te = hashlib.sha256()
    for encoded in sorted(entities): te.update(framed(encoded))
    cube_biomes = hashlib.sha256(b'absent-cube-biomes')
    if 'Biomes' in cube and 'Biomes3D' in cube: raise ValueError('Ambiguous legacy/current cube biomes')
    for key in ('Biomes', 'Biomes3D'):
        if key in cube:
            raw = byte_array(cube, key, 64)
            cube_biomes = hashlib.sha256(framed(key.encode('ascii')) + raw + names_digest(np.frombuffer(raw, dtype=np.uint8), biomes).encode('ascii'))
    return {'blockArraysSha256': arrays.hexdigest(), 'stateNamesMetadataSha256': names_digest(ids, blocks, metadata),
        'savedLightSha256': light.hexdigest(), 'tileEntitiesSha256': te.hexdigest(), 'tileEntities': len(entities),
        'cubeBiomesSha256': cube_biomes.hexdigest(), 'flags': flags, 'lightRepresentation': representation}


def column_snapshot(column, position, biomes):
    if tuple(integer(column, key) for key in ('x', 'z')) != position: raise ValueError('Column/index coordinate mismatch')
    if not isinstance(column.get('v'), nbtlib.Byte) or int(column['v']) != 1: raise ValueError('Unsupported column NBT version')
    raw = byte_array(column, 'Biomes', 256)
    opacity = byte_array(column, 'OpacityIndex')
    if not opacity: raise ValueError('Empty server opacity index')
    return {'biomesSha256': sha(raw), 'biomeNamesSha256': names_digest(np.frombuffer(raw, dtype=np.uint8), biomes),
        'opacityIndexSha256': sha(opacity), 'opacityIndexBytes': len(opacity)}


def world_metadata(world):
    level_path, cubic_path = world / 'level.dat', world / 'data/cubicChunksData.dat'
    level, cubic = nbtlib.load(level_path), nbtlib.load(cubic_path)['data']
    # The lab band [-128,384) must lie inside the world; Riven's world now extends below it.
    min_height, max_height = int(cubic.get('minHeight', 0)), int(cubic.get('maxHeight', 0))
    if not bool(cubic.get('isCubicChunks')) or min_height > -128 or max_height < 384:
        raise ValueError('World allocation must contain the lab band [-128,384)')
    blocks, biomes = registry_map(level, 'minecraft:blocks'), registry_map(level, 'minecraft:biomes')
    data = level['Data']
    options = str(data['generatorOptions'])
    # Minimal generators legitimately save no options. Preserve that value as
    # distinct from a JSON object, and continue rejecting malformed nonempty JSON.
    recipe = '' if options == '' else json.loads(options)
    return {'seed': int(data['RandomSeed']), 'generator': str(data['generatorName']), 'generatorOptions': recipe,
        'storage': str(cubic['storageFormat']), 'minY': -128, 'maxYExclusive': 384,
        'worldMinY': min_height, 'worldMaxYExclusive': max_height,
        'blockRegistry': blocks, 'biomeRegistry': biomes,
        'blockRegistrySha256': sha(json.dumps(blocks, sort_keys=True).encode()), 'biomeRegistrySha256': sha(json.dumps(biomes, sort_keys=True).encode()),
        'metadataFiles': [{'path': str(path.resolve()), 'sha256': sha(path.read_bytes())} for path in (level_path, cubic_path)]}


def selected_records(world, positions, dimensions):
    groups = defaultdict(dict)
    suffix, count, edge, folder = ('3dr', 4096, 16, 'region3d') if dimensions == 3 else ('2dr', 1024, 32, 'region2d')
    region_folder = world / folder
    if list(region_folder.glob('*.ext')): raise ValueError('Unsupported external RegionLib overflow files in ' + str(region_folder))
    for position in positions:
        region = tuple(value // edge for value in position)
        index = 0
        for value in position: index = index * edge + (value % edge)
        groups[region][index] = position
    for region, wanted in sorted(groups.items()):
        path = region_folder / ('.'.join(map(str, region)) + '.' + suffix)
        if not path.is_file(): continue
        for index, record, source in read_regionlib_entries(path, wanted, count):
            yield wanted[index], record, source


def scan_world(world, view):
    metadata = world_metadata(world)
    cubes, columns = coordinates(view)
    rows, column_rows, sources, incomplete = {}, {}, {}, []
    for dimensions, wanted, target in ((3, cubes, rows), (2, columns, column_rows)):
        for position, record, source in selected_records(world, wanted, dimensions):
            if position in target: raise ValueError('Duplicate saved coordinate')
            row = cube_snapshot(record, position, metadata['blockRegistry'], metadata['biomeRegistry']) if dimensions == 3 else column_snapshot(record, position, metadata['biomeRegistry'])
            target[position] = row
            if dimensions == 3 and not all(row['flags'].values()): incomplete.append(list(position))
            path = source['file']
            if path not in sources:
                sources[path] = {'path': path, 'fileBytes': source['fileBytes'], 'headerSha256': source['headerSha256'], 'selectedPayloadSha256': hashlib.sha256(), 'selectedEntries': 0}
            source_row = sources[path]
            source_row['selectedPayloadSha256'].update(struct.pack('>I', len(position)) + b''.join(struct.pack('>i', value) for value in position) + bytes.fromhex(source['payloadSha256']))
            source_row['selectedEntries'] += 1
    for source in sources.values(): source['selectedPayloadSha256'] = source['selectedPayloadSha256'].hexdigest()
    return {'world': str(world.resolve()), 'metadata': metadata, 'cubes': rows, 'columns': column_rows,
        'missingCubes': [list(pos) for pos in sorted(cubes - rows.keys())], 'missingColumns': [list(pos) for pos in sorted(columns - column_rows.keys())],
        'incompleteCubes': sorted(incomplete), 'sources': list(sources.values())}


def aggregate(rows, fields):
    result = {}
    for field in fields:
        digest = hashlib.sha256()
        for position, row in sorted(rows.items()):
            digest.update(b''.join(struct.pack('>i', value) for value in position) + bytes.fromhex(row[field]))
        result[field] = digest.hexdigest()
    return result


def compare_snapshots(first, second, view):
    metadata_fields = ('seed', 'generator', 'generatorOptions', 'storage', 'minY', 'maxYExclusive', 'blockRegistry', 'biomeRegistry')
    mismatches = [key for key in metadata_fields if first['metadata'][key] != second['metadata'][key]]
    differences = {}
    for group, fields in (('cubes', CUBE_FIELDS), ('columns', COLUMN_FIELDS)):
        shared = first[group].keys() & second[group].keys()
        for field in fields:
            changed = [list(pos) for pos in sorted(shared) if first[group][pos][field] != second[group][pos][field]]
            differences[field] = {'count': len(changed), 'coordinates': changed,
                'examples': [{'coordinate': list(pos), 'baseline': first[group][tuple(pos)][field], 'candidate': second[group][tuple(pos)][field]} for pos in changed[:MAX_EXAMPLES]]}
    flag_changes = [list(pos) for pos in sorted(first['cubes'].keys() & second['cubes'].keys()) if first['cubes'][pos]['flags'] != second['cubes'][pos]['flags']]
    differences['completeFlags'] = {'count': len(flag_changes), 'coordinates': flag_changes}
    worlds = []
    for snapshot in (first, second):
        meta = {key: value for key, value in snapshot['metadata'].items() if key not in ('blockRegistry', 'biomeRegistry')}
        worlds.append({'world': snapshot['world'], 'metadata': meta, 'savedCubes': len(snapshot['cubes']), 'savedColumns': len(snapshot['columns']),
            'missingCubes': snapshot['missingCubes'], 'missingColumns': snapshot['missingColumns'], 'incompleteCubes': snapshot['incompleteCubes'],
            'tileEntities': sum(row['tileEntities'] for row in snapshot['cubes'].values()),
            'absentStorageSections': sum(row['lightRepresentation'] == 'absent-storage-section' for row in snapshot['cubes'].values()),
            'cubeDigests': aggregate(snapshot['cubes'], CUBE_FIELDS), 'columnDigests': aggregate(snapshot['columns'], COLUMN_FIELDS), 'sources': snapshot['sources']})
    complete = all(world['savedCubes'] == 15625 and world['savedColumns'] == 625 and not world['missingCubes'] and not world['missingColumns'] and not world['incompleteCubes'] for world in worlds)
    return {'schema': 'cubiclab.full-view-saved-comparison.v1', 'passed': complete and not mismatches and not any(row['count'] for row in differences.values()),
        'geometry': view, 'completeSavedCoverage': complete, 'metadataMismatches': mismatches, 'worlds': worlds, 'differences': differences,
        'scope': 'Independent stopped-world readback of exactly all 15625 camera-derived cubes and 625 columns. Exact saved block arrays, registry-resolved block names/metadata, saved BlockLight/SkyLight representation, complete flags, cube/column biomes, opacity context and canonical tile entities. No marker shortcut.',
        'limits': 'Compares final saved state after later activity, not the live delivery-time snapshot. Absent storage sections are explicit empty-cube representations with implicit column-heightmap light; no nibble values are invented. Exact light and opacity differences fail parity. Excludes entity simulation, scheduled ticks, capabilities, item progression, render meshes and presentation. File-stability checks do not establish that the server is stopped.'}


def compare_worlds(first_lab, first_world, second_lab, second_world):
    manifests = [json.loads(path.read_text(encoding='utf-8-sig')) for path in (first_lab, second_lab)]
    first_view, second_view = map(geometry, manifests)
    if first_view != second_view: raise ValueError('Lab manifests require exactly the same camera-derived full-view geometry')
    first = scan_world(first_world, first_view)
    second = scan_world(second_world, second_view)
    result = compare_snapshots(first, second, first_view)
    result['manifests'] = [{'path': str(path.resolve()), 'sha256': sha(path.read_bytes()), 'cubicJar': manifest.get('cubicJar'),
        'labJar': manifest.get('labJar'), 'riven': manifest.get('riven')} for path, manifest in zip((first_lab, second_lab), manifests)]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-lab', type=Path, required=True)
    parser.add_argument('--baseline-world', type=Path, required=True)
    parser.add_argument('--candidate-lab', type=Path, required=True)
    parser.add_argument('--candidate-world', type=Path, required=True)
    parser.add_argument('--stopped-worlds', action='store_true', help='Operator declaration: both owned servers have stopped and saves have drained')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.stopped_worlds: parser.error('Both worlds must be stopped before independent readback; supply --stopped-worlds only after confirming this')
    try: result = compare_worlds(args.baseline_lab, args.baseline_world, args.candidate_lab, args.candidate_world)
    except (OSError, ValueError, KeyError, TypeError, EOFError) as error:
        result = {'schema': 'cubiclab.full-view-saved-comparison.v1', 'passed': False, 'error': str(error), 'scope': 'Failed stopped-world readback; no full-view parity is established'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: result[key] for key in ('passed', 'geometry', 'completeSavedCoverage', 'metadataMismatches', 'error') if key in result}, indent=2))
    if 'differences' in result: print(json.dumps({key: row['count'] for key, row in result['differences'].items()}, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__': raise SystemExit(main())
