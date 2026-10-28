#!/usr/bin/env python3
"""Inspect one retained native phase's call paths, waits and optional Java 26 method traces."""
import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
from pathlib import Path
import subprocess

from analyze_cubic_profile import EVENTS, duration_ns, iter_jfr_events, methods, timestamp_ns

PREFIX = 'io.github.opencubicchunks.cubicchunks.'
SERVER_TICK = 'net.minecraft.server.MinecraftServer.func_71217_p'
TICK_STAGES = {
    PREFIX + 'core.server.CubeProviderServer.func_73156_b': 'provider',
    PREFIX + 'core.server.PlayerCubeMap.func_72693_b': 'streaming',
    PREFIX + 'core.server.chunkio.async.forge.AsyncWorldIOExecutor.tick': 'completion',
    PREFIX + 'lab.LabJob.tick': 'fixture',
}
SEMANTICS = {
    'scope': 'Exact server scenario UTC windows; startup/shutdown excluded. Event intervals are clipped to window overlaps.',
    'samples': 'Java execution stack counts, not exclusive CPU time. Native samples remain separate. Thread identity checks sampledThread before eventThread.',
    'traces': 'Method wall durations include waits, GC, scheduling and tracing overhead. Only fully contained method spans enter method quantiles; boundary spans are counted separately. Nested durations overlap.',
    'ticks': 'Fully contained MinecraftServer tick traces split into union-covered provider, streaming, completion and fixture spans, with remainder other. These include tracing overhead and differ from the lab tick timer.',
    'boundaries': 'The original lab startUtc is set at the first fixture END handler, while the first tick timer starts at START. That first tick is not fully covered by the UTC window. initialLoadedCubes is captured before warmup, not at capture start.',
    'limits': 'No per-coordinate request identifiers exist in MethodTrace. Call paths and worker waits establish mechanisms; exact per-request queue decomposition needs correlated events. Thresholded I/O events do not cover every filesystem operation. No optimization acceptance from traced timings.',
}


def fingerprint(path):
    with path.open('rb') as stream:
        return {'path': str(path), 'bytes': path.stat().st_size,
                'sha256': hashlib.file_digest(stream, 'sha256').hexdigest()}


def method_name(value):
    return value['type']['name'].replace('/', '.') + '.' + value['name']


def stats(values):
    ordered = sorted(values)
    def quantile(p):
        # Match the lab's nearest-rank convention.
        import math
        return ordered[max(0, math.ceil(len(ordered) * p) - 1)] / 1e6
    return {'count': len(ordered), 'totalMs': sum(ordered) / 1e6,
            'p50Ms': quantile(.5), 'p95Ms': quantile(.95), 'maxMs': ordered[-1] / 1e6}


def sample_stage(stack):
    if PREFIX + 'lab.LabJob.tick' in stack:
        return 'fixture'
    if PREFIX + 'core.server.chunkio.async.forge.AsyncWorldIOExecutor.onWorldTick' in stack:
        return 'completion'
    if PREFIX + 'core.server.PlayerCubeMap.func_72693_b' in stack:
        if PREFIX + 'core.server.CubeProviderServer.generateCube' in stack:
            return 'streaming: terrain generation'
        if PREFIX + 'core.server.CubeProviderServer.calculateDiffuseSkylight' in stack:
            return 'streaming: first light'
        if PREFIX + 'core.world.cube.Cube.trackSurface' in stack:
            return 'streaming: surface tracking'
        return 'streaming: other'
    if PREFIX + 'core.server.CubeProviderServer.func_73156_b' in stack:
        return 'provider: cube tick / eligibility scan'
    return 'other server work'


def split_tick(start, end, children):
    """Partition wall time once, even if a callback reenters a traced stage."""
    intervals = [(max(start, a), min(end, b), stage) for a, b, stage in children if a < end and b > start]
    points = sorted({start, end, *(a for a, _, _ in intervals), *(b for _, b, _ in intervals)})
    result = Counter()
    for a, b in zip(points, points[1:]):
        covering = [(y - x, stage) for x, y, stage in intervals if x <= a and y >= b]
        result[min(covering)[1] if covering else 'other'] += b - a
    assert sum(result.values()) == end - start
    return {stage + 'Ms': result[stage] / 1e6 for stage in ('provider', 'streaming', 'completion', 'fixture', 'other')}


def write_csv(path, rows, fields):
    with path.open('w', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader(); writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab-directory', type=Path, required=True)
    parser.add_argument('--phase', choices=['read', 'write'], default='read')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    lab = args.lab_directory.resolve(); source = lab / 'runs' / args.phase
    manifest = json.loads((lab / 'lab.json').read_text())
    result = json.loads((source / 'result.json').read_text())
    if not result['passed'] or not result.get('jfr'):
        raise ValueError('Requires a passed phase recorded with --jfr')
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    phases = [dict(name=row['scenario'], server=row['server'],
                   start=timestamp_ns(row['server']['startUtc']), end=timestamp_ns(row['server']['endUtc']),
                   events=[], java=Counter(), native=Counter(), stages=Counter(), paths=Counter()) for row in result['scenarios']]
    helper = Path(__file__).with_name('JfrWindowExport.java')
    commands = [[manifest['java'], '-Djava.io.tmpdir=' + str(output), str(helper), str(source / 'server.jfr'),
                 str(min(p['start'] for p in phases)), str(max(p['end'] for p in phases)),
                 EVENTS + ',jdk.MethodTrace,jdk.ThreadSleep,jdk.JavaMonitorWait,jdk.SafepointBegin,jdk.Compilation',
                 str(output / 'full-method-counts.json')]]
    with (output / 'jfr-print.log').open('w') as log, (output / 'events.jsonl').open('w', encoding='utf-8') as retained:
        command = commands[0]
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=log, text=True, encoding='utf-8') as process:
            try:
                for event in iter_jfr_events(process.stdout):
                    v = event['values']; t = timestamp_ns(v['startTime']); duration = duration_ns(v.get('duration'))
                    thread = v.get('sampledThread') or v.get('eventThread') or {}
                    thread = thread.get('javaName') or thread.get('osName') or '(unknown)'
                    for phase in phases:
                        overlap = max(0, min(t + duration, phase['end']) - max(t, phase['start']))
                        if not (phase['start'] <= t < phase['end'] or overlap):
                            continue
                        stack = methods(v)
                        phase['events'].append((event['type'], thread, t, t + duration, overlap, v, stack))
                        if event['type'] == 'jdk.ExecutionSample':
                            phase['java'][thread] += 1
                            if thread == 'Server thread':
                                phase['stages'][sample_stage(stack)] += 1
                                phase['paths'][';'.join(reversed(stack))] += 1
                        if event['type'] == 'jdk.NativeMethodSample':
                            phase['native'][thread] += 1
                        retained.write(json.dumps({'scenario': phase['name'], 'thread': thread,
                                                   'overlapNanos': overlap, **event}) + '\n')
            except BaseException:
                process.kill(); process.wait(); raise
            if process.wait():
                raise RuntimeError('JFR print failed; retain its log')
    report = {'lab': str(lab), 'phase': args.phase, 'runtimeVersion': manifest.get('runtimeVersion'),
              'source': fingerprint(source / 'server.jfr'), 'result': fingerprint(source / 'result.json'),
              'manifest': fingerprint(lab / 'lab.json'), 'commands': commands,
              'serverJfrSettings': result.get('serverJfrSettings'), 'semantics': SEMANTICS, 'scenarios': []}
    report['fullRecordingMethodCounts'] = json.loads((output / 'full-method-counts.json').read_text())
    report['binaryWindowExporter'] = fingerprint(helper)
    server_log = source / 'server.log'
    report['methodInstrumentationWarnings'] = [line for line in server_log.read_text(encoding='utf-8', errors='replace').splitlines()
                                                if '[jfr,methodtrace]' in line]
    timing_rows, tick_rows, path_rows = [], [], []
    for p in phases:
        methods_by_thread = defaultdict(list); boundary = Counter(); waits = defaultdict(list)
        traces = []
        for kind, thread, start, end, overlap, values, stack in p['events']:
            if kind == 'jdk.MethodTrace':
                method = method_name(values['method']); traces.append((method, thread, start, end))
                if p['start'] <= start and end <= p['end']:
                    methods_by_thread[thread, method].append(end - start)
                else:
                    boundary[thread + ' | ' + method] += 1
            if kind in ('jdk.ThreadPark', 'jdk.ThreadSleep', 'jdk.GCPhasePause', 'jdk.FileRead', 'jdk.JavaMonitorEnter', 'jdk.JavaMonitorWait'):
                waits[kind, thread, ';'.join(reversed(stack))].append(overlap)
        method_rows = [dict(thread=thread, method=method, **stats(values))
                       for (thread, method), values in sorted(methods_by_thread.items())]
        timing_rows += [dict(scenario=p['name'], **r) for r in method_rows]
        children = [(a, b, TICK_STAGES[m]) for m, thread, a, b in traces if thread == 'Server thread' and m in TICK_STAGES]
        for m, thread, a, b in traces:
            if m == SERVER_TICK and thread == 'Server thread' and p['start'] <= a and b <= p['end']:
                tick_rows.append(dict(scenario=p['name'], startEpochNanos=a, durationMs=(b-a)/1e6,
                                      **split_tick(a, b, children)))
        path_rows += [dict(scenario=p['name'], count=count, stackRootToLeaf=stack) for stack, count in p['paths'].most_common()]
        report['scenarios'].append({'name': p['name'], 'server': p['server'],
            'javaSamplesByThread': dict(p['java']), 'nativeSamplesByThread': dict(p['native']),
            'serverJavaStackCoverage': {
                'missing': sum(not bool(stack) for kind, thread, _, _, _, _, stack in p['events']
                               if kind == 'jdk.ExecutionSample' and thread == 'Server thread'),
                'truncated': sum(bool((v.get('stackTrace') or {}).get('truncated'))
                                 for kind, thread, _, _, _, v, _ in p['events']
                                 if kind == 'jdk.ExecutionSample' and thread == 'Server thread')},
            'serverSampleStages': dict(p['stages']), 'methods': method_rows, 'boundaryMethodSpans': dict(boundary),
            'waitsAndReads': [dict(event=kind, thread=thread, stackRootToLeaf=stack, **stats(values))
                             for (kind, thread, stack), values in sorted(waits.items())]})
    write_csv(output / 'methods.csv', timing_rows, ['scenario', 'thread', 'method', 'count', 'totalMs', 'p50Ms', 'p95Ms', 'maxMs'])
    write_csv(output / 'ticks.csv', tick_rows, ['scenario', 'startEpochNanos', 'durationMs', 'providerMs', 'streamingMs', 'completionMs', 'fixtureMs', 'otherMs'])
    write_csv(output / 'server-call-paths.csv', path_rows, ['scenario', 'count', 'stackRootToLeaf'])
    (output / 'trace.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(lab.name, 'analyzed', len(phases), 'scenarios,', len(tick_rows), 'whole tick traces', flush=True)


if __name__ == '__main__':
    main()
