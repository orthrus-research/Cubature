#!/usr/bin/env python3
"""Stream/filter dedicated storage worker JFR events and exact trial write windows."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

from analyze_cubic_profile import EVENTS, iter_jfr_events


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); lab = args.lab_directory.resolve()
    manifest = json.loads((lab / 'lab.json').read_text())
    result = json.loads((lab / 'runs/write/result.json').read_text())
    scenarios = [row for row in result['scenarios'] if row['scenario'] == 'storage-strategies']
    if not result['passed'] or not result.get('jfr') or len(scenarios) != 1:
        raise ValueError('Requires a passed write phase with storage-strategies and --jfr')
    trials = scenarios[0]['server']['storageStrategyChecks']['trials']
    if len(trials) != 21 or not all(row['passed'] for row in trials): raise ValueError('Incomplete trial matrix')
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    exports = lab / 'profile-exports' / ('storage-worker-' + output.name); exports.mkdir(parents=True, exist_ok=False)
    temporary = exports / 'java-tmp'; temporary.mkdir()
    base = [manifest['java'], '-Djava.io.tmpdir=' + str(temporary), '-m', 'jdk.jfr/jdk.jfr.internal.tool.Main']
    source = lab / 'runs/write/server.jfr'
    target = exports / 'storage-worker.json'
    counts = {}
    # jfr scrub's eventThread filter drops sampledThread-only execution/native samples.
    # Inspect both fields while streaming; never materialize a whole expanded recording.
    command = base + ['print', '--json', '--stack-depth', '64', '--events', EVENTS, str(source)]
    with (exports / 'jfr-print.log').open('w', encoding='utf-8') as log, target.open('w', encoding='utf-8') as stream:
        stream.write('{"recording":{"events":['); first = True
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=log, text=True, encoding='utf-8') as process:
            try:
                for event in iter_jfr_events(process.stdout):
                    values = event['values']; thread = values.get('sampledThread') or values.get('eventThread')
                    if not thread or thread.get('javaName') != 'CubicLab storage strategies': continue
                    if not first: stream.write(',')
                    json.dump(event, stream); first = False
                    kind = event['type']; counts[kind] = counts.get(kind, 0) + 1
            except BaseException:
                process.kill(); process.wait(); raise
            if process.wait(): raise RuntimeError('JFR print failed; inspect private export log')
        stream.write(']}}\n')
    if not counts: raise ValueError('No worker events retained; inspect JFR filtering')
    windows = exports / 'windows.json'
    windows.write_text(json.dumps({'loader': 'cleanroom', 'phases': [
        {'name': row['variant'] + '-r' + str(row['repetition']), 'startUtc': row['writeStartUtc'],
         'endUtc': row['writeEndUtc'], 'elapsedNanos': row['writeNanos'] + row['flushNanos'], 'operations': row['cubes']}
        for row in trials]}, indent=2) + '\n', encoding='utf-8')
    gc_target = exports / 'server-gc.json'
    with gc_target.open('w', encoding='utf-8') as stream:
        subprocess.run(base + ['print', '--json', '--stack-depth', '16', '--events',
                               'jdk.GarbageCollection,jdk.GCPhasePause', str(source)], stdout=stream, check=True)
    subprocess.run([sys.executable, '-B', str(Path(__file__).with_name('analyze_cubic_profile.py')),
                    '--no-charts', '--output', str(output), '--jfr', 'storage-worker=' + str(target),
                    '--probe', 'storage-worker=' + str(windows), '--jfr', 'server-gc=' + str(gc_target),
                    '--probe', 'server-gc=' + str(windows)], check=True)
    (output / 'export-scope.json').write_text(json.dumps({'sourceRecording': str(source),
        'thread': 'CubicLab storage strategies', 'eventCounts': counts,
        'filter': 'Streaming JSON selection checks sampledThread and eventThread; no intermediate full-recording JSON',
        'boundary': 'Trial write windows only; full-worker scope also includes warmup/reopen reads. '
                    'Execution/native events are sample counts, allocations are sampled weights. '
                    'File events are thresholded; region commit groups are not force/syscall counts. '
                    'Separate server-gc scopes show coincident pauses across the whole JVM; no exclusive cause or server-thread CPU attribution.'}, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(counts))


if __name__ == '__main__': main()
