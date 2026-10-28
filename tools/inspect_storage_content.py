"""Independent stopped-world checks for native storage-content witnesses, including overflow."""
import argparse
import hashlib
import json
from pathlib import Path
import uuid

from inspect_cubic_lab_world import read_regionlib_entries


def require(value, message):
    if not value: raise ValueError(message)


def check_caps(level, revision, digest):
    caps = level['ForgeCaps']['cubiclab:storage_contract']
    require(int(caps['revision']) == revision, 'Capability revision mismatch')
    require(hashlib.sha256(caps['payload'].tobytes()).hexdigest() == digest, 'Capability payload mismatch')


def check_cube(level, witness):
    coords = witness['cube']
    require([int(level[k]) for k in ('x','y','z')] == coords, 'Cube coordinate mismatch')
    check_caps(level, 37, witness['capabilityPayloadSha256'])
    if 'saveEventRevision' in witness:require(int(level.get('cubiclabSaveEventRevision',0))==witness['saveEventRevision'],'Save-event NBT witness missing')
    entities = [e for e in level['Entities'] if uuid.UUID(int=((int(e['UUIDMost']) & ((1<<64)-1))<<64) | (int(e['UUIDLeast']) & ((1<<64)-1))) == uuid.UUID(witness['entityUuid'])]
    require(len(entities) == 1, 'Entity missing/duplicated')
    e = entities[0]
    require(str(e['id']) == 'minecraft:armor_stand' and str(e['CustomName']) == witness['name'] and bool(e['NoGravity']), 'Entity payload mismatch')
    chests = [e for e in level['TileEntities'] if [int(e[k]) for k in ('x','y','z')] == witness['chest']]
    require(len(chests) == 1, 'Chest missing/duplicated')
    chest = chests[0]
    require(str(chest['id']) == 'minecraft:chest' and str(chest['CustomName']) == witness['name'], 'Chest identity mismatch')
    items = [item for item in chest['Items'] if int(item['Slot']) == 0]
    require(len(items) == 1 and str(items[0]['id']) == 'minecraft:diamond' and int(items[0]['Count']) == 17, 'Inventory mismatch')
    ticks = [t for t in level['TileTicks'] if [int(t[k]) for k in ('x','y','z')] == witness['delayedTick']]
    require(len(ticks) == 1 and str(ticks[0]['i']) == 'minecraft:stone' and int(ticks[0]['p']) == 3 and 900000 < int(ticks[0]['t']) <= 1000000, 'Delayed tick mismatch')


def inspect(world, report):
    require(report.get('passed') is True and report.get('scenario') in ('storage-content-write','storage-content-read'), 'Need successful native content report')
    content = report['storageContent']
    require(content.get('passed') is True and len(content['witnesses']) == 3, 'Need complete three-level witness')
    require(sorted(w['cube'][1] for w in content['witnesses']) == [-7,8,22], 'Witness height coverage changed')
    rows = []
    for w in content['witnesses']:
        x,y,z = w['cube']; index = ((x&15)<<8)|((y&15)<<4)|(z&15)
        path = world/'region3d'/f'{x>>4}.{y>>4}.{z>>4}.3dr'
        records = list(read_regionlib_entries(path, [index], 4096, allow_overflow=True))
        require(len(records) == 1, 'Cube entry missing')
        _,cube,evidence = records[0]; check_cube(cube,w)
        path = world/'region2d'/f'{x>>5}.{z>>5}.2dr'
        records = list(read_regionlib_entries(path,[((x&31)<<5)|(z&31)],1024,allow_overflow=True))
        require(len(records) == 1, 'Column entry missing')
        column = records[0][1]
        require([int(column[k]) for k in ('x','z')] == [x,z], 'Column coordinate mismatch')
        check_caps(column,91,w['columnPayloadSha256'])
        rows.append(dict(cube=w['cube'],passed=True,storage=evidence))
    require(any(r['storage']['externalFile'] for r in rows), 'Oversized fixture did not exercise external storage')
    return dict(schema=1,passed=True,entries=rows,scope='Independent saved NBT readback. Requires stopped world; not a power-loss guarantee.')


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--world',required=True,type=Path);p.add_argument('--report',required=True,type=Path);p.add_argument('--output',required=True,type=Path)
    args=p.parse_args();report=json.loads(args.report.read_text());result=inspect(args.world,report)
    result['reportSha256']=hashlib.sha256(args.report.read_bytes()).hexdigest()
    with args.output.open('x') as stream:json.dump(result,stream,indent=2)
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
