#!/usr/bin/env python3
"""Account for a complete load-async cohort using aggregate trace endpoints, without inventing request IDs."""
import argparse
from collections import Counter
import csv
import json
from pathlib import Path

from analyze_cubic_profile import duration_ns, timestamp_ns
from trace_cubic_lab import fingerprint, method_name

PREFIX = 'io.github.opencubicchunks.cubicchunks.core.'


def population(starts, ends, low, high, nonnegative=True):
    """Integrate a queue exactly; event identity is unnecessary for a complete cohort."""
    if not starts or len(starts) != len(ends):
        raise ValueError('Requires equal nonzero admission/departure counts')
    changes = Counter(starts); changes.subtract(ends)
    if min(changes) < low or max(changes) > high or high <= low:
        raise ValueError('Cohort crosses capture boundaries')
    count = peak = minimum = area = 0; previous = low; points = [(low, 0)]
    for time, delta in sorted(changes.items()):
        area += count * (time - previous)
        count += delta; peak = max(peak, count); minimum = min(minimum, count)
        points.append((time, count)); previous = time
    if count or nonnegative and minimum < 0:
        raise ValueError('Unbalanced or impossible queue population')
    if area != sum(ends) - sum(starts):
        raise AssertionError('Queue area does not equal aggregate endpoint difference')
    points.append((high, 0))
    return {'count': len(starts), 'areaJobNanos': area, 'meanMs': area / len(starts) / 1e6,
            'meanPopulation': area / (high - low), 'peakPopulation': peak,
            'minimumPopulation': minimum, 'points': points}


def classify_completions(parents, cube_restores):
    assigned = Counter()
    for child in cube_restores:
        owners = [i for i, parent in enumerate(parents)
                  if parent['start'] <= child['start'] and child['end'] <= parent['end']]
        if len(owners) != 1:
            raise ValueError('Cube restore has missing or ambiguous owner completion')
        assigned[owners[0]] += 1
    if any(count != 1 for count in assigned.values()):
        raise ValueError('Multiple cube restores in one completion')
    return ([span for i, span in enumerate(parents) if i in assigned],
            [span for i, span in enumerate(parents) if i not in assigned])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--trace-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source = args.trace_directory.resolve()
    trace = json.loads((source / 'trace.json').read_text(encoding='utf-8'))
    phases = [p for p in trace['scenarios'] if p['name'] == 'load-async']
    if len(phases) != 1 or not phases[0]['server']['passed']:
        raise ValueError('Requires one passed load-async scenario')
    phase = phases[0]; server = phase['server']
    low, high = timestamp_ns(server['startUtc']), timestamp_ns(server['endUtc'])
    spans = []; future_parks = []
    with (source / 'events.jsonl').open(encoding='utf-8') as stream:
        for line in stream:
            event = json.loads(line)
            if event['scenario'] != 'load-async':
                continue
            v = event['values']; start = timestamp_ns(v['startTime'])
            if event['type'] == 'jdk.MethodTrace':
                end = start + duration_ns(v['duration'])
                spans.append({'method': method_name(v['method']), 'thread': event['thread'], 'start': start, 'end': end})
            elif event['type'] == 'jdk.ThreadPark' and event['thread'].startswith('Cube I/O Thread #'):
                frames = (v.get('stackTrace') or {}).get('frames') or []
                if any(method_name(frame['method']) == 'java.util.concurrent.CompletableFuture.get' for frame in frames):
                    future_parks.append(event['overlapNanos'])

    def select(name, thread):
        return sorted([r for r in spans if r['method'] == PREFIX + name and r['thread'].startswith(thread)], key=lambda r:r['start'])
    queue = select('server.chunkio.async.forge.AsyncWorldIOExecutor.queueCubeLoad', 'Server thread')
    reads = select('server.chunkio.RegionCubeStorage.readCube', 'Cube I/O Thread #')
    ready = select('server.chunkio.async.forge.AsyncIOProvider.finishAsync', 'Cube I/O Thread #')
    restores = select('server.chunkio.IONbtReader.readCubeSyncPart', 'Server thread')
    owner, column_owner = classify_completions(
        select('server.chunkio.async.forge.AsyncTaskCompletion.complete', 'Server thread'), restores)
    column_ready = select('server.chunkio.async.forge.AsyncIOProvider.finishAsync', 'Column I/O Thread #')
    column_reads = select('server.chunkio.RegionCubeStorage.readColumn', 'Column I/O Thread #')
    count = server['operations']
    cohorts = {'request': queue, 'regionRead': reads, 'workerReady': ready, 'cubeRestore': restores, 'ownerCompletion': owner}
    if count != 512 or any(len(rows) != count for rows in cohorts.values()):
        raise ValueError('Requires all 512 successful saved-cube fixture stages, once each')
    if len(column_owner) != len(column_ready) or len(column_reads) != len(column_ready) or len(column_ready) != 16:
        raise ValueError('Column cohort is incomplete or contains unidentified completion work')
    if select('server.chunkio.async.forge.AsyncWorldIOExecutor.syncCubeLoad', 'Server thread'):
        raise ValueError('Synchronous steals make this cohort unsuitable for the worker-only accounting')
    if any(span['start'] < low or span['end'] > high for rows in cohorts.values() for span in rows):
        raise ValueError('A required method crosses the capture boundary')
    endpoints = [(queue, 'start'), (reads, 'start'), (reads, 'end'), (ready, 'start'), (owner, 'start'), (owner, 'end')]
    stages = ['beforeRegionRead', 'regionReadAndNbt', 'afterReadToReadyEntry', 'readyToOwnerStartUpper', 'ownerCompletion']
    populations = {}
    for name, (left, left_key), (right, right_key) in zip(stages, endpoints, endpoints[1:]):
        populations[name] = population([r[left_key] for r in left], [r[right_key] for r in right], low, high)
    outstanding = population([r['start'] for r in queue], [r['end'] for r in owner], low, high)
    if outstanding['peakPopulation'] > 16:
        raise ValueError('The fixture exceeds its documented 16-request concurrency cap')
    lower = population([r['end'] for r in ready], [r['start'] for r in owner], low, high, nonnegative=False)
    column_upper = population([r['start'] for r in column_ready], [r['start'] for r in column_owner], low, high)
    column_lower = population([r['end'] for r in column_ready], [r['start'] for r in column_owner], low, high, nonnegative=False)

    # Check conservation at every boundary, not merely the final mean.
    snapshots = {}
    for name, data in {**populations, 'outstanding': outstanding, 'readyToOwnerStartLower': lower}.items():
        for time, value in data['points']:
            snapshots.setdefault(time, {})[name] = value
    latest = {name: 0 for name in [*stages, 'outstanding', 'readyToOwnerStartLower']}; queue_rows = []
    for time, update in sorted(snapshots.items()):
        latest.update(update)
        if sum(latest[name] for name in stages) != latest['outstanding']:
            raise AssertionError('Instantaneous stage populations do not conserve outstanding requests')
        queue_rows.append({'timeMs': (time-low)/1e6, **latest})

    polls = select('server.chunkio.async.forge.AsyncWorldIOExecutor.tick', 'Server thread')
    result = {'profile': Path(trace['lab']).name, 'scope': 'retained Java 26 / Cleanroom ' + trace['runtimeVersion'],
        'trace': fingerprint(source / 'trace.json'), 'events': fingerprint(source / 'events.jsonl'),
        'recording': trace['source'], 'sourceWindows': {'startUtc': server['startUtc'], 'endUtc': server['endUtc']},
        'counts': {name: len(rows) for name, rows in cohorts.items()}, 'columnCount': len(column_ready),
        'methodInstrumentationWarnings': trace['methodInstrumentationWarnings'],
        'stages': {name: {k:v for k,v in data.items() if k != 'points'} for name,data in populations.items()},
        'outstanding': {k:v for k,v in outstanding.items() if k != 'points'},
        'readyToOwnerLowerMeanMs': lower['meanMs'], 'columnReadyToOwnerUpperMeanMs': column_upper['meanMs'],
        'columnReadyToOwnerLowerMeanMs': column_lower['meanMs'],
        'nativeRequestToCallbackMeanMs': server['operationDurations']['meanMs'],
        'directFutureParkTotalMs': sum(future_parks)/1e6, 'futureParkEvents': len(future_parks),
        'semantics': [
            'Means use sum(endpoints)-sum(startpoints) over balanced complete cohorts, without pairing jobs across threads.',
            'The instantaneous count and queue-area identities are checked exactly with integer nanosecond timestamps.',
            'finishAsync writes finished inside its span; ready-to-owner means are bounded by its entry and exit endpoints.',
            'beforeRegionRead includes admission, executor/column waits and loader work before RegionCubeStorage; it is not pure executor queue time.',
            'afterReadToReadyEntry includes reconstruction and remaining worker bookkeeping, without future waiting.',
            'Generic completion spans are classified by their single enclosed cube-NBT restore; other completions must balance the 16-column cohort.',
            'No per-request queue percentiles, exact request IDs, scheduler counterfactuals or performance acceptance are inferred. Tracing overhead remains.',
            'Direct worker park time is not total request waiting: queued jobs can wait behind that same parked worker.'],
        'validation': {'balancedCohorts': True, 'pointwiseConservation': True, 'noSyncSteals': True,
                       'noBoundarySpans': True, 'maximumOutstanding': outstanding['peakPopulation']}}
    if sum(d['areaJobNanos'] for d in populations.values()) != outstanding['areaJobNanos']:
        raise AssertionError('Stage means do not telescope to the complete instrumented latency')
    result['accountedMeanMs'] = outstanding['meanMs']
    result['differenceFromNativeTimerMs'] = outstanding['meanMs'] - server['operationDurations']['meanMs']
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    with (output/'queues.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(queue_rows[0])); writer.writeheader(); writer.writerows(queue_rows)
    with (output/'polls.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=['startMs','endMs']); writer.writeheader()
        writer.writerows({'startMs':(p['start']-low)/1e6,'endMs':(p['end']-low)/1e6} for p in polls)
    (output/'handoff.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(result['profile'], 'balanced 512-cube / 16-column cohort; mean', round(result['accountedMeanMs'],4), 'ms', flush=True)


if __name__ == '__main__':
    main()
