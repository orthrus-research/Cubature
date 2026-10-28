"""Bounded workload inputs shared by native preparation and serial campaigns."""
import hashlib
import json
import math
import re
from pathlib import Path

RELATIVE_PATH = 'config/cubiclab-workload.json'
SCENARIOS = {'idle', 'generate', 'resident', 'save', 'load-sync', 'load-async', 'horizontal',
    'vertical', 'route', 'lighting-snapshot', 'lighting-save-order', 'scheduled-ticks', 'save-burst', 'autosave',
    'storage-failure', 'storage-strategies', 'map-churn', 'generation-contracts', 'first-light',
    'full-view', 'maintenance-contracts', 'save-command', 'save-clean', 'autosave-natural', 'storage-fault-matrix',
    'storage-content-write', 'storage-content-read', 'storage-two-worlds',
    'spectator-flight', 'lifecycle-settle', 'lifecycle-save-purge'}


def scenario_valid(name):
    return isinstance(name, str) and name in SCENARIOS


def keys(value, allowed, required=()):
    if not isinstance(value, dict) or set(value) - set(allowed) or set(required) - set(value):
        raise ValueError('Unknown or missing workload fields; allowed: ' + ', '.join(allowed))


def integer(value, low, high, label):
    if type(value) is not int or not low <= value <= high:
        raise ValueError(f'{label} must be an integer in {low}..{high}')
    return value


def pose(value):
    if not isinstance(value, list) or len(value) != 5 or any(
            type(n) not in (int, float) or not math.isfinite(n) for n in value):
        raise ValueError('Pose requires five finite numbers: [x,y,z,yaw,pitch]')
    x, y, z, yaw, pitch = value
    if abs(x) > 29999000 or abs(z) > 29999000 or not -128 <= y < 384 or abs(yaw) > 360 or abs(pitch) > 90:
        raise ValueError('Pose exceeds world/height/angle bounds')
    return value


def validate(value):
    keys(value, ['schema', 'id', 'description', 'tags', 'seed', 'camera', 'scenarios', 'warmupTicks',
        'idleTicks', 'residentTicks', 'timeoutSeconds', 'fixture', 'route', 'spectatorFlight', 'waitForOverlays'], ['schema', 'id', 'seed', 'camera', 'scenarios'])
    if type(value.get('waitForOverlays',True)) is not bool: raise ValueError('waitForOverlays must be a boolean')
    integer(value['schema'], 1, 1, 'schema')
    if not isinstance(value['id'], str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,47}', value['id']):
        raise ValueError('Invalid workload id')
    if not isinstance(value.get('description', ''), str) or len(value.get('description', '')) > 2048:
        raise ValueError('Description must be bounded text')
    tags = value.get('tags', [])
    if not isinstance(tags, list) or len(tags) > 16 or any(not isinstance(t, str) or not re.fullmatch(r'[a-z0-9_-]{1,32}', t) for t in tags):
        raise ValueError('Invalid workload tags')
    integer(value['seed'], -(2**63), 2**63 - 1, 'seed')
    pose(value['camera'])
    keys(value['scenarios'], ['write', 'read'], ['write'])
    for phase, names in value['scenarios'].items():
        if not isinstance(names, list) or len(names) > 64 or (phase == 'write' and not names) or any(not scenario_valid(n) for n in names) or len(set(names)) != len(names):
            raise ValueError('Invalid or duplicate scenario list')
    names = [n for rows in value['scenarios'].values() for n in rows]
    lifecycle = ['full-view','spectator-flight','lifecycle-settle','lifecycle-save-purge']
    if any(n in names for n in lifecycle[1:]):
        order = value['scenarios']['write']
        if any(n not in order for n in lifecycle) or sorted(order.index(n) for n in lifecycle) != [order.index(n) for n in lifecycle]:
            raise ValueError('Spectator lifecycle requires full-view, flight, settle and save-purge in write order')
        flight=value.get('spectatorFlight')
        keys(flight,['durationSeconds','blocksPerSecond','settleSeconds'],['durationSeconds','blocksPerSecond','settleSeconds'])
        integer(flight['durationSeconds'],60,60,'durationSeconds')
        integer(flight['blocksPerSecond'],16,16,'blocksPerSecond')
        integer(flight['settleSeconds'],10,60,'settleSeconds')
    if 'storage-two-worlds' in names:
        order = value['scenarios']['write']
        if names.count('storage-two-worlds') != 1 or 'storage-two-worlds' not in order or 'save' not in order or 'generate' not in order or not order.index('generate') < order.index('save') < order.index('storage-two-worlds'):
            raise ValueError('storage-two-worlds requires one write-phase invocation after generate and save')
    if any(n.startswith('load-') for n in value['scenarios'].get('read', [])) and 'save' not in value['scenarios']['write']:
        raise ValueError('Reload workload needs a write save fixture')
    if 'storage-content-read' in value['scenarios'].get('read',[]) and 'storage-content-write' not in value['scenarios']['write']:
        raise ValueError('Storage content reload needs a matching write fixture')
    if 'storage-content-write' in value['scenarios']['write']:
        order=value['scenarios']['write']
        if 'generate' not in order or order.index('generate')>order.index('storage-content-write'):
            raise ValueError('Storage content write requires prior fixture generation')
    for key, default, low, high in [('warmupTicks', 40, 0, 1200), ('idleTicks', 100, 100, 12000),
            ('residentTicks', 100, 100, 12000), ('timeoutSeconds', 840, 30, 3600)]:
        integer(value.get(key, default), low, high, key)
    fixture = value.get('fixture', {})
    keys(fixture, ['originColumn', 'sizeColumns', 'cohortOffset', 'batchSize', 'asyncConcurrency', 'requirement'])
    for key, default, low, high in [('originColumn', [128,128], -1873000, 1873000),
            ('sizeColumns', [4,4], 1, 8), ('cohortOffset', [32,0], -1024, 1024)]:
        pair = fixture.get(key, default)
        if not isinstance(pair, list) or len(pair) != 2: raise ValueError(key + ' requires a pair')
        for n in pair: integer(n, low, high, key)
    width, depth = fixture.get('sizeColumns', [4,4]); dx, dz = fixture.get('cohortOffset', [32,0])
    if abs(dx) < width and abs(dz) < depth: raise ValueError('Fixture cohorts overlap')
    integer(fixture.get('batchSize', 8), 1, 64, 'batchSize')
    integer(fixture.get('asyncConcurrency', 16), 1, 64, 'asyncConcurrency')
    if fixture.get('requirement', 'GENERATE') not in ['GENERATE', 'POPULATE', 'LIGHT']:
        raise ValueError('Unsupported fixture generation requirement')
    route = value.get('route')
    if route is None:
        if 'route' in names: raise ValueError('route scenario requires explicit waypoints')
    else:
        keys(route, ['interpolation', 'waypoints'], ['waypoints'])
        if route.get('interpolation', 'step') not in ['step', 'linear']: raise ValueError('Unknown route interpolation')
        points = route['waypoints']
        if not isinstance(points, list) or not 1 <= len(points) <= 256: raise ValueError('Route requires 1..256 waypoints')
        total = 0
        for point in points:
            keys(point, ['pose', 'ticks'], ['pose', 'ticks']); pose(point['pose'])
            total += integer(point['ticks'], 1, 12000, 'waypoint ticks')
        if total > 12000: raise ValueError('Route exceeds 12000 ticks')
    return value


def read(path):
    if Path(path).stat().st_size > 65536: raise ValueError('Workload exceeds 64 KiB')
    data = Path(path).read_bytes()
    if len(data) > 65536: raise ValueError('Workload exceeds 64 KiB')
    return validate(json.loads(data))


def identity(value, data):
    return {'schema': 1, 'id': value['id'], 'seed': value['seed'], 'sha256': hashlib.sha256(data).hexdigest()}


def fixture_columns(value=None):
    fixture = (value or {}).get('fixture', {})
    x,z = fixture.get('originColumn',[128,128]); width,depth = fixture.get('sizeColumns',[4,4])
    dx,dz = fixture.get('cohortOffset',[32,0])
    return {(x+i+cohort*dx,z+j+cohort*dz) for cohort in range(2) for i in range(width) for j in range(depth)}


def install(value, directory):
    data = (json.dumps(value, ensure_ascii=True, sort_keys=True, indent=2) + '\n').encode('utf-8')
    if len(data) > 65536: raise ValueError('Canonical workload exceeds 64 KiB')
    path = Path(directory) / RELATIVE_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as stream: stream.write(data)
    return dict(identity(value, data), relativePath=RELATIVE_PATH, value=value)


def verify(manifest):
    expected = manifest.get('benchmarkWorkload')
    if expected is None: return
    if manifest.get('labProperties',{}).get('cubiclab.workload') != RELATIVE_PATH:
        raise RuntimeError('Prepared workload JVM property changed')
    for side in ['server', 'client']:
        data = (Path(manifest[side]) / RELATIVE_PATH).read_bytes()
        value = validate(json.loads(data))
        if identity(value, data) != {k: expected[k] for k in ['schema', 'id', 'seed', 'sha256']} or value != expected['value']:
            raise RuntimeError('Prepared ' + side + ' workload changed; prepare a fresh lab')
    props = (Path(manifest['server']) / 'server.properties').read_text()
    if re.findall(r'^level-seed=(.*)$', props, re.M) != [str(expected['seed'])]:
        raise RuntimeError('Prepared workload seed changed')


def validate_capture(manifest, server, client):
    expected = manifest.get('benchmarkWorkload')
    if expected is None: return
    descriptor = {k: expected[k] for k in ['schema', 'id', 'seed', 'sha256']}
    if any(row.get('benchmarkWorkload') != descriptor for row in [server, client]) or server.get('worldSeed') != expected['seed']:
        raise RuntimeError('Native workload identity or world seed differs from preparation')
    if client.get('overlayObservationAvailable') is not True or client.get('overlayWaitRequired') != expected['value'].get('waitForOverlays',True) or client.get('framesWithOverlay',0) or client.get('overlayProbeFailure'):
        raise RuntimeError('Known title/arrival overlay covered measurement or its observer was unavailable')
    if server.get('scenario') == 'spectator-flight':
        flight=client.get('spectatorFlight',{})
        authoritative=server.get('spectatorLifecycle',{}).get('clientFlight') or {}
        if not flight.get('passed') or not authoritative.get('passed') or not flight.get('moving') or flight.get('inRoutePositionOverrides') != 0:
            raise RuntimeError('Real client spectator flight did not pass')
        if not 60000000000 <= flight.get('movementElapsedNanos',0) <= 61000000000 or abs(flight.get('distanceX',0)-960)>16 or len(flight.get('observations',[]))<55:
            raise RuntimeError('Spectator flight missed wall duration, speed or trace coverage')
        final=flight.get('finalPose',[])
        if len(final)!=5 or abs(final[1]-expected['value']['camera'][1])>.5 or abs(final[2]-expected['value']['camera'][2])>.5:
            raise RuntimeError('Spectator flight drifted vertically or laterally')
        lifecycle=server.get('spectatorLifecycle',{})
        server_final=lifecycle.get('serverFinalPose',[])
        if len(lifecycle.get('observations',[]))<55 or len(server_final)!=5 or any(abs(a-b)>2 for a,b in zip(final[:3],server_final[:3])):
            raise RuntimeError('Authoritative server flight trace/end position missing or inconsistent')
    if server.get('scenario') == 'lifecycle-save-purge':
        lifecycle=server.get('spectatorLifecycle',{})
        if lifecycle.get('pendingAfterFlush') != 0 or lifecycle.get('explicitPurgeNanos',0)<=0:
            raise RuntimeError('Post-flight save, purge and drained flush receipt missing')
    if server.get('scenario') == 'route':
        route = expected['value']['route']
        if server.get('routeObservationTicks') != sum(p['ticks'] for p in route['waypoints']) or not server.get('routeTrace'):
            raise RuntimeError('Native route window or authoritative trace missing')
        final = client.get('finalCameraPose', [])
        wanted = route['waypoints'][-1]['pose']
        if len(final) != 5 or any(not isinstance(n,(int,float)) or not math.isfinite(n) for n in final) or any(abs(a-b)>.3 for a,b in zip(final[:3],wanted[:3])) or abs((final[3]-wanted[3]+180)%360-180)>2 or abs(final[4]-wanted[4])>2:
            raise RuntimeError('Client did not reach the declared final route pose')
        if not client.get('routeCameraSamples') or client.get('routeCameraSamplesDropped',0):
            raise RuntimeError('Client route camera trace missing or capped')
