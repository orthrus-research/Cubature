#!/usr/bin/env python3
"""Independently inspect a stopped lab world's RegionLib headers, coordinates and saved markers.

Requires nbtlib. External fallback entries require an explicit opt-in for selected-entry reads.
"""
import argparse
from collections import Counter
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import struct

import nbtlib


def read_regionlib_entries(path, indexes, entry_count, maximum_nbt_bytes=16 * 1024 * 1024, allow_overflow=False):
    """Stream selected ordinary RegionLib entries with the independent 512-byte header layout.

    This does not establish that a world is stopped. The caller owns that prerequisite.
    External fallback entries need allow_overflow; concurrent changes, special markers and oversized NBT fail explicitly.
    """
    if entry_count not in (1024, 4096): raise ValueError('Unsupported RegionLib entry count')
    indexes = sorted(set(indexes))
    if any(type(index) is not int or not 0 <= index < entry_count for index in indexes):
        raise ValueError('Invalid RegionLib entry index')
    with path.open('rb') as stream:
        before = os.fstat(stream.fileno())
        header = stream.read(entry_count * 4)
        if len(header) != entry_count * 4: raise ValueError('Truncated RegionLib header: ' + str(path))
        header_hash = hashlib.sha256(header).hexdigest()
        for index in indexes:
            packed, = struct.unpack_from('>I', header, index * 4)
            overflow_path = path.with_name(path.name + '.ext') / str(index)
            if packed == 0xffffffff: raise ValueError('Unsupported special RegionLib entry: ' + str(path))
            if not packed and not overflow_path.exists(): continue
            if packed and overflow_path.exists(): raise ValueError('Ambiguous ordinary and external RegionLib entries: ' + str(path))
            external = None
            if not packed:
                if not allow_overflow: raise ValueError('External RegionLib overflow entry is unsupported: ' + str(path))
                external = overflow_path
                with external.open('rb') as overflow:
                    ext_before = os.fstat(overflow.fileno())
                    payload = overflow.read(maximum_nbt_bytes + 1)
                    ext_after = os.fstat(overflow.fileno())
                if len(payload) > maximum_nbt_bytes: raise ValueError('External compressed payload exceeds supported bound')
                if (ext_before.st_size,ext_before.st_mtime_ns,ext_before.st_ino) != (ext_after.st_size,ext_after.st_mtime_ns,ext_after.st_ino) or external.stat().st_ino != ext_before.st_ino:
                    raise ValueError('External file changed during stopped-world inspection')
                length = len(payload)
            else:
                offset, sectors = (packed >> 8) * 512, packed & 255
                if not sectors or offset < len(header) or offset + 4 > before.st_size:
                    raise ValueError('Invalid RegionLib entry: ' + str(path) + ':' + str(index))
                stream.seek(offset)
                length_bytes = stream.read(4)
                if len(length_bytes) != 4: raise ValueError('Truncated RegionLib length')
                length, = struct.unpack('>I', length_bytes)
                if not length or length + 4 > sectors * 512 or offset + length + 4 > before.st_size:
                    raise ValueError('Truncated/invalid RegionLib payload: ' + str(path) + ':' + str(index))
                payload = stream.read(length)
                if len(payload) != length: raise ValueError('Truncated RegionLib payload')
            with gzip.GzipFile(fileobj=io.BytesIO(payload)) as compressed:
                decoded = compressed.read(maximum_nbt_bytes + 1)
            if len(decoded) > maximum_nbt_bytes: raise ValueError('RegionLib NBT exceeds supported bound')
            raw = io.BytesIO(decoded)
            document = nbtlib.File.parse(raw)
            if raw.tell() != len(decoded) or not isinstance(document.get('Level'), nbtlib.Compound):
                raise ValueError('Missing Level compound or trailing RegionLib NBT')
            yield index, document['Level'], {'file': str(path.resolve()), 'fileBytes': before.st_size,
                'headerSha256': header_hash, 'payloadSha256': hashlib.sha256(payload).hexdigest(), 'compressedBytes': length,
                'externalFile': str(external.resolve()) if external else None}
        after = os.fstat(stream.fileno())
        stream.seek(0)
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or stream.read(len(header)) != header:
            raise ValueError('RegionLib file changed during stopped-world inspection: ' + str(path))


def maintenance_block_digest(cube):
    """Match LabMaintenanceProbe's explicitly framed SHA-256 over saved block arrays."""
    digest = hashlib.sha256(struct.pack('>iii', *(int(cube[key]) for key in ('x', 'y', 'z'))))
    sections = sorted(cube.get('Sections', []), key=lambda section: int(section.get('Y', 0)))
    digest.update(struct.pack('>i', len(sections)))
    for section in sections:
        y = int(section.get('Y', 0))
        if not -128 <= y <= 127: raise ValueError('Maintenance section Y is not a signed byte')
        digest.update(struct.pack('>i', y))
        for key, required_length in (('Blocks', 4096), ('Data', 2048), ('Add', 2048), ('Add2', 2048)):
            tag = section.get(key)
            if tag is None and key in ('Add', 'Add2'):
                data = b''
            elif not isinstance(tag, nbtlib.ByteArray) or len(tag) != required_length:
                raise ValueError('Missing/invalid maintenance block array: ' + key)
            else:
                data = tag.tobytes()
            digest.update(key.encode('ascii'))
            digest.update(struct.pack('>i', len(data)))
            digest.update(data)
    return digest.hexdigest()


def normalize_maintenance_witness(document, report=False):
    """Require the complete bounded 16-cube witness, rejecting failed or ambiguous reports."""
    if report:
        if not isinstance(document, dict) or document.get('schema') != 'cubiclab.server.v1' or document.get('scenario') != 'maintenance-contracts':
            raise ValueError('Expected an individual maintenance-contracts server report')
        if document.get('passed') is not True:
            raise ValueError('Maintenance server report did not pass')
        document = document.get('maintenanceChecks')
        if not isinstance(document, dict) or document.get('passed') is not True:
            raise ValueError('Maintenance contract checks did not pass')
    if isinstance(document, dict):
        if 'passed' in document and document['passed'] is not True:
            raise ValueError('Maintenance witness came from failed checks')
        document = document.get('savedWitnesses')
    if not isinstance(document, list) or len(document) != 16:
        raise ValueError('Maintenance witness must contain exactly 16 saved cubes')
    entries, seen = [], set()
    for row in document:
        if not isinstance(row, dict): raise ValueError('Invalid maintenance witness entry')
        coords, local, digest = row.get('cube'), row.get('markerLocal'), row.get('blockArraySha256')
        if not isinstance(coords, list) or len(coords) != 3 or any(type(value) is not int or not -(1 << 31) <= value < (1 << 31) for value in coords):
            raise ValueError('Invalid maintenance witness coordinates')
        if tuple(coords) in seen: raise ValueError('Duplicate maintenance witness coordinate')
        seen.add(tuple(coords))
        if not isinstance(local, list) or len(local) != 3 or any(type(value) is not int or not 0 <= value < 16 for value in local):
            raise ValueError('Invalid maintenance marker position')
        if row.get('markerBlock') != 'minecraft:gold_block': raise ValueError('Unsupported maintenance marker block')
        if not isinstance(digest, str) or re.fullmatch('[0-9a-fA-F]{64}', digest) is None:
            raise ValueError('Invalid maintenance block-array SHA-256')
        entries.append({'cube': coords, 'markerLocal': local, 'markerBlock': row['markerBlock'], 'blockArraySha256': digest.lower()})
    return sorted(entries, key=lambda entry: entry['cube'])


def load_maintenance_witness(path, report=False):
    with path.open('rb') as stream:
        data = stream.read(4 * 1024 * 1024 + 1)
    if len(data) > 4 * 1024 * 1024: raise ValueError('Maintenance witness/report exceeds 4 MiB')
    return {'entries': normalize_maintenance_witness(json.loads(data.decode('utf-8-sig')), report),
            'source': {'path': str(path.resolve()), 'sha256': hashlib.sha256(data).hexdigest(),
                       'kind': 'server-report' if report else 'saved-witness'}}


def inspect_maintenance_cube(cube, witness, registry):
    coords = tuple(int(cube[key]) for key in ('x', 'y', 'z'))
    if coords != tuple(witness['cube']): raise ValueError('Maintenance cube coordinate mismatch')
    if not all(bool(cube.get(key)) for key in ('fullyPopulated', 'initLightDone', 'isSurfaceTracked')):
        raise ValueError('Maintenance cube lost complete LIGHT flags: ' + str(coords))
    digest = maintenance_block_digest(cube)
    if digest != witness['blockArraySha256']:
        raise ValueError('Maintenance block-array digest mismatch: ' + str(coords))
    sections = cube.get('Sections', [])
    if len(sections) != 1: raise ValueError('Maintenance marker requires one saved cube section')
    section = sections[0]
    x, y, z = witness['markerLocal']
    cell = (y << 8) | (z << 4) | x
    block_id = int(section['Blocks'][cell]) & 255
    for key, shift in (('Add', 8), ('Add2', 12)):
        if key in section:
            block_id |= ((int(section[key][cell // 2]) & 255) >> (4 * (cell & 1)) & 15) << shift
    if registry.get(block_id) != witness['markerBlock']:
        raise ValueError('Maintenance saved marker missing: ' + str(coords))
    return {'cube': list(coords), 'blockArraySha256': digest, 'markerBlock': registry[block_id],
            'markerLocal': list(witness['markerLocal']), 'completeLightFlags': True}


def section_state_bytes(sections):
    """Canonical stored block-state tags; lighting can legitimately settle at different times."""
    sections = sorted(sections, key=lambda section: int(section.get('Y', 0)))
    state = nbtlib.List[nbtlib.Compound]([
        nbtlib.Compound({key: section[key] for key in sorted(section) if key not in ('BlockLight', 'SkyLight')})
        for section in sections])
    encoded = io.BytesIO()
    nbtlib.File({'sections': state}).write(encoded)
    return encoded.getvalue()


def section_light_bytes(sections):
    """Require actual nibble arrays, retaining their exact bytes and section coordinates."""
    light = []
    for section in sorted(sections, key=lambda section: int(section.get('Y', 0))):
        tags = {'Y': nbtlib.Byte(int(section.get('Y', 0)))}
        for key in ('BlockLight', 'SkyLight'):
            if key not in section or len(section[key]) != 2048:
                raise ValueError('Missing/invalid saved light array: ' + key)
            tags[key] = section[key]
        light.append(nbtlib.Compound(tags))
    encoded = io.BytesIO()
    nbtlib.File({'sections': nbtlib.List[nbtlib.Compound](light)}).write(encoded)
    return encoded.getvalue()


def inspect_fixture_heightmaps(world, workload=None):
    """Read the 32 fixture columns using the pinned RegionLib 2D key/header layout."""
    rows, digest = [], hashlib.sha256()
    from lab_workload import fixture_columns
    for x,z in sorted(fixture_columns(workload)):
        path = world / 'region2d' / f'{x >> 5}.{z >> 5}.2dr'
        index = ((x & 31) << 5) | (z & 31)
        records = list(read_regionlib_entries(path, [index], 1024))
        if len(records) != 1: raise ValueError(f'Missing column entry: {path}:{index}')
        _, column, _ = records[0]
        if (int(column['x']), int(column['z'])) != (x, z): raise ValueError('Column coordinate mismatch')
        data = column['OpacityIndex'].tobytes()
        position, non_uniform, starts_count = 0, 0, 0
        for _ in range(256):
            if position + 10 > len(data): raise ValueError('Truncated heightmap header')
            minimum, maximum, count = struct.unpack_from('>iiH', data, position)
            position += 10
            if minimum > maximum or (count and not count & 1) or position + count * 4 > len(data):
                raise ValueError('Invalid heightmap bounds/count')
            starts = struct.unpack_from('>' + str(count) + 'i', data, position)
            if count and (starts[0] != minimum or starts[-1] > maximum or any(a >= b for a, b in zip(starts, starts[1:]))):
                raise ValueError('Invalid heightmap segment starts')
            position += count * 4
            non_uniform += bool(count)
            starts_count += count
        if position != len(data): raise ValueError('Trailing heightmap data')
        digest.update(struct.pack('>iiI', x, z, len(data)))
        digest.update(data)
        rows.append({'x': x, 'z': z, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                     'nonUniformBlockColumns': non_uniform, 'segmentStarts': starts_count})
    return {'columns': len(rows), 'blockColumns': 256 * len(rows), 'sha256': digest.hexdigest(), 'entries': rows,
            'scope': 'Exact serialized server OpacityIndex bytes at the requested fixture columns; validates bounds and alternating segment starts. Permanent and staged surface coverage can differ between timed runs. Exact cross-run hashes require matched surface-tracking coverage and save state; not whole-world lighting parity.'}


def inspect(world, include_heightmaps=False, include_lighting=False, maintenance_witness=None, workload=None):
    regions = world / 'region3d'
    if list(regions.glob('*.ext')):
        raise ValueError('External overflow entries are unsupported by this reader')
    level = nbtlib.load(world / 'level.dat')
    metadata = nbtlib.load(world / 'data/cubicChunksData.dat')['data']
    registry = {int(entry['V']): str(entry['K']) for entry in level['FML']['Registries']['minecraft:blocks']['ids']}
    from lab_workload import fixture_columns,validate
    if workload is not None:
        validate(workload)
        if int(level['Data']['RandomSeed']) != workload['seed']: raise ValueError('Saved world seed differs from workload')
    expected = {(x,y,z) for x,z in fixture_columns(workload) for y in range(-8,24)}
    maintenance_expected = ({tuple(entry['cube']): entry for entry in normalize_maintenance_witness(maintenance_witness['entries'])}
                            if maintenance_witness is not None else {})
    maintenance_verified = {}
    default_required = maintenance_witness is None or include_heightmaps or include_lighting
    verified, heights, files, fixture_states = set(), Counter(), [], {}
    fixture_lights, light_bytes = {}, Counter()
    for path in sorted(regions.glob('*.3dr')):
        region = tuple(map(int, path.stem.split('.')))
        body = path.read_bytes()
        if len(body) < 16384: raise ValueError('Truncated header: ' + str(path))
        files.append({'name': path.name, 'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest()})
        for index, (packed,) in enumerate(struct.iter_unpack('>I', body[:16384])):
            if not packed: continue
            offset, sectors = (packed >> 8) * 512, packed & 255
            if not sectors or offset < 16384 or offset + 4 > len(body):
                raise ValueError(f'Invalid entry: {path}:{index}')
            length, = struct.unpack_from('>I', body, offset)
            if length + 4 > sectors * 512 or offset + 4 + length > len(body):
                raise ValueError(f'Truncated payload: {path}:{index}')
            cube = nbtlib.File.parse(io.BytesIO(gzip.decompress(body[offset + 4:offset + 4 + length])))['Level']
            coords = tuple(int(cube[key]) for key in ['x', 'y', 'z'])
            indexed = (region[0] * 16 + (index >> 8), region[1] * 16 + ((index >> 4) & 15), region[2] * 16 + (index & 15))
            if coords != indexed: raise ValueError(f'Coordinate mismatch: {path}:{index}')
            heights[coords[1]] += 1
            if coords in maintenance_expected:
                if coords in maintenance_verified: raise ValueError('Duplicate saved maintenance cube: ' + str(coords))
                maintenance_verified[coords] = inspect_maintenance_cube(cube, maintenance_expected[coords], registry)
            if default_required and coords in expected:
                fixture_states[coords] = section_state_bytes(cube['Sections'])
                if include_lighting:
                    if not all(bool(cube.get(key)) for key in ('initLightDone', 'isSurfaceTracked', 'fullyPopulated')):
                        raise ValueError('Fixture cube has not reached fully tracked LIGHT: ' + str(coords))
                    fixture_lights[coords] = section_light_bytes(cube['Sections'])
                    for section in cube['Sections']:
                        for key in ('BlockLight', 'SkyLight'):
                            light_bytes[key] += sum(int(value) != 0 for value in section[key])
                section, = cube['Sections']
                cell = (8 << 8) | (8 << 4) | 8
                block_id = int(section['Blocks'][cell]) & 255
                if 'Add' in section:
                    block_id |= ((int(section['Add'][cell // 2]) & 255) >> (4 * (cell & 1)) & 15) << 8
                if registry.get(block_id) != 'minecraft:gold_block':
                    raise ValueError('Saved marker missing at cube ' + str(coords))
                verified.add(coords)
    if maintenance_expected.keys() != maintenance_verified.keys():
        raise ValueError('Missing saved maintenance cubes: ' + str(sorted(maintenance_expected.keys() - maintenance_verified.keys())))
    passed = ((not default_required or verified == expected) and int(metadata['minHeight']) == -128
              and int(metadata['maxHeight']) == 384 and bool(metadata['isCubicChunks']))
    state_hash = hashlib.sha256()
    for coords, encoded in sorted(fixture_states.items()):
        state_hash.update(struct.pack('>iiiI', *coords, len(encoded)))
        state_hash.update(encoded)
    result = {'schema': 'cubiclab.world-inspection.v1', 'passed': passed, 'world': str(world.resolve()),
            'generator': str(level['Data']['generatorName']), 'seed': int(level['Data']['RandomSeed']),
            'minY': int(metadata['minHeight']), 'maxYExclusive': int(metadata['maxHeight']),
            'storage': str(metadata['storageFormat']), 'expectedMarkers': len(expected), 'verifiedMarkers': len(verified),
            'savedCubes': sum(heights.values()), 'savedCubeCountsByY': dict(sorted(heights.items())), 'regions': files,
            'fixtureSectionStates': {'cubes': len(fixture_states), 'sha256': state_hash.hexdigest(),
                'blockRegistrySha256': hashlib.sha256(json.dumps(registry, sort_keys=True).encode()).hexdigest(),
                'scope': 'Canonical serialized section state tags at fixture coordinates; excludes BlockLight/SkyLight. Compare only with the same registry, seed, generator and marker procedure; not whole-world parity.'},
            'registeredSusyDeposits': 'susy:deposit_block' in registry.values(),
            'registeredSusyHostRock': 'susy:susy_stone_smooth' in registry.values(),
            'loadedModIds': sorted(str(entry['ModId']) for entry in level['FML']['ModList']),
            'limits': 'Stopped-world inspection. Verifies RegionLib coordinates and every lab gold marker; not terrain parity, biome correctness or progression balance.'}
    if workload is not None: result['benchmarkWorkloadId']=workload['id'];result['fixtureDefinition']=workload.get('fixture',{})
    if include_heightmaps: result['fixtureColumnHeightmaps'] = inspect_fixture_heightmaps(world,workload)
    if maintenance_witness is not None:
        entries = [entry for _, entry in sorted(maintenance_verified.items())]
        result['inspectionMode'] = 'default-and-maintenance' if default_required else 'maintenance-witness'
        result['defaultFixtureMarkers'] = {'required': bool(default_required), 'expected': len(expected), 'verified': len(verified)}
        result['maintenanceFixture'] = {'expectedCubes': len(maintenance_expected), 'verifiedCubes': len(entries),
            'entries': entries, 'witnessSource': maintenance_witness.get('source'),
            'scope': 'Independent stopped-world RegionLib/NBT verification of 16 witness block-array SHA-256 digests, registry-resolved gold markers and complete LIGHT flags. Excludes lighting arrays/entities/biomes; native unload quotas and autosave fairness remain separate contract evidence.'}
        if not default_required:
            result['expectedMarkers'], result['verifiedMarkers'] = len(maintenance_expected), len(entries)
            result['limits'] += ' Maintenance-only mode does not inspect or qualify the unrelated 1024-cube fixture.'
            result['fixtureSectionStates']['scope'] += ' Unrequested in maintenance-only mode; zero cubes is not a default-fixture qualification.'
    if include_lighting:
        light_hash = hashlib.sha256()
        for coords, encoded in sorted(fixture_lights.items()):
            light_hash.update(struct.pack('>iiiI', *coords, len(encoded)))
            light_hash.update(encoded)
        if not all(light_bytes[key] for key in ('BlockLight', 'SkyLight')):
            raise ValueError('Lighting fixture has trivial all-zero light arrays')
        result['fixtureLighting'] = {'cubes': len(fixture_lights), 'sha256': light_hash.hexdigest(),
            'nonZeroBytes': dict(light_bytes),
            'scope': 'Exact saved BlockLight/SkyLight nibble arrays, section coordinates and LIGHT completion flags at the requested fixture cubes. Compare matched first-light geometry/order and fully drained saves; not whole-world or renderer lighting parity.'}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('world', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--heightmaps', action='store_true', help='Also validate and hash the 32 fixture columns\' server opacity heightmaps')
    parser.add_argument('--lighting', action='store_true', help='Require completed first-light fixture and hash exact saved light arrays')
    parser.add_argument('--workload',type=Path,help='Prepared workload.json defining the saved marker cohorts; verifies saved world seed')
    maintenance = parser.add_mutually_exclusive_group()
    maintenance.add_argument('--maintenance-witness', type=Path, help='Verify 16 saved maintenance witnesses from a JSON list or probe summary')
    maintenance.add_argument('--maintenance-report', type=Path, help='Read 16 witnesses from a passed maintenance-contracts server JSON')
    args = parser.parse_args()
    witness_path = args.maintenance_witness or args.maintenance_report
    witness = load_maintenance_witness(witness_path, bool(args.maintenance_report)) if witness_path else None
    from lab_workload import read
    workload=read(args.workload) if args.workload else None
    result = inspect(args.world, args.heightmaps, args.lighting, witness,workload)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: result[key] for key in ['passed', 'savedCubes', 'verifiedMarkers']}))
    raise SystemExit(0 if result['passed'] else 1)
