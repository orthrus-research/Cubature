#!/usr/bin/env python3
"""Plot retained native CubicChunks report data without building or launching Minecraft."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import zipfile

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch

BLUE, INK, GRID = '#2f6690', '#262626', '#e4e4e4'
QUANTILES = [('p50Ms', 'Median'), ('p95Ms', 'p95'), ('p99Ms', 'p99'), ('maxMs', 'Maximum')]


def fingerprint(path):
    with path.open('rb') as stream:
        return {'path': str(path), 'sha256': hashlib.file_digest(stream, 'sha256').hexdigest()}


def read_report(path):
    report = json.loads(path.read_text(encoding='utf-8'))
    if report.get('schema') != 'cubiclab.report.v1':
        raise ValueError('Expected a retained cubiclab.report.v1 report')
    scenarios = []
    for run in report['runs']:
        if not run['result']['passed']:
            raise ValueError('A phase did not pass native capture checks')
        for scenario in run['result']['scenarios']:
            client = scenario['client']
            if client['framesWithGui'] or not client['frameIntervals']['count']:
                raise ValueError('Capture is covered or has no frame intervals')
            scenario = dict(scenario, phase=run['phase'])
            for side, timer in [('server', 'serverTicks'), ('client', 'frameIntervals'), ('server', 'operationDurations')]:
                stats = scenario[side][timer]
                if not stats['count']:
                    continue
                if stats['droppedCount']:
                    raise ValueError('Percentile samples were dropped; review before plotting')
                values = [stats[key] for key, _ in QUANTILES]
                if values != sorted(values) or values[0] < 0:
                    raise ValueError('Invalid timing quantiles')
            scenarios.append(scenario)
    return report, scenarios


def scaffold(axis):
    axis.spines[['top', 'right']].set_visible(False)
    axis.spines[['bottom', 'left']].set_color('#888888')
    axis.grid(axis='x', color=GRID, linewidth=.7)
    axis.set_axisbelow(True)
    axis.tick_params(axis='y', length=0)
    axis.tick_params(axis='x', labelsize=10)


def header(fig, title, context):
    fig.text(.025, .97, title, fontsize=19, fontweight='bold', va='top')
    fig.text(.025, .918, context, fontsize=10, va='top', color='#555555')


def save(fig, output, name):
    files = []
    for suffix in ['png', 'svg']:
        path = output / (name + '.' + suffix)
        fig.savefig(path, dpi=160, facecolor='white', metadata={'Date': None} if suffix == 'svg' else None)
        if suffix == 'svg':
            path.write_text('\n'.join(line.rstrip() for line in path.read_text(encoding='utf-8').splitlines()) + '\n', encoding='utf-8')
        files.append(path)
    plt.close(fig)
    return files


def duration_chart(scenarios, side, output, context, recording_note):
    # Flush/audit fixtures and one-tick snapshots do not measure ordinary streaming tails.
    items = [s for s in scenarios if s['server']['serverTicks']['count'] > 1 and s['scenario'] != 'map-churn']
    timer = 'serverTicks' if side == 'server' else 'frameIntervals'
    stats = [s[side][timer] for s in items]
    labels = [s['phase'] + ' / ' + s['scenario'] for s in items]
    fig = plt.figure(figsize=(12.4, 7.4))
    axis = fig.add_axes([.235, .20, .52, .60])
    table = fig.add_axes([.79, .20, .19, .60])
    p95 = [s['p95Ms'] for s in stats]
    p99 = [s['p99Ms'] for s in stats]
    maxima = [s['maxMs'] for s in stats]
    ys = list(range(len(items)))
    axis.barh(ys, p95, height=.52, color=BLUE, edgecolor=INK, linewidth=.6)
    axis.scatter(p99, ys, s=35, facecolors='white', edgecolors=INK, linewidths=1, zorder=4)
    axis.scatter(maxima, ys, s=35, marker='^', color=INK, zorder=5)
    budget = 50 if side == 'server' else 1000 / 120
    axis.axvline(budget, color=INK, linestyle='--', linewidth=1, zorder=2)
    axis.set_yticks(ys, labels)
    axis.set_ylim(len(items) - .5, -.5)
    axis.set_xlim(0, max(max(maxima) * 1.10, budget * 1.13))
    axis.set_xlabel('Milliseconds per ' + ('server tick' if side == 'server' else 'client frame interval'))
    scaffold(axis)
    table.set_xlim(0, 1); table.set_ylim(axis.get_ylim()); table.axis('off')
    for x, label in [(.02, 'Samples'), (.56, 'p95 ms'), (1, 'Max ms')]:
        table.text(x, 1.045, label, transform=table.transAxes, ha='right' if x > .1 else 'left', fontsize=10)
    for y, stat in zip(ys, stats):
        for x, value in [(.02, f"{stat['count']:,}"), (.56, f"{stat['p95Ms']:.2f}"), (1, f"{stat['maxMs']:.2f}")]:
            table.text(x, y, value, ha='right' if x > .1 else 'left', va='center', fontsize=10, family='DejaVu Sans Mono')
    reference = '20 TPS target: 50 ms' if side == 'server' else '120 FPS interval: 8.33 ms'
    legend = [Patch(facecolor=BLUE, edgecolor=INK, label='p95'),
              Line2D([], [], marker='o', linestyle='none', markerfacecolor='white', markeredgecolor=INK, color=INK, label='p99'),
              Line2D([], [], marker='^', linestyle='none', color=INK, label='Maximum'),
              Line2D([], [], color=INK, linestyle='--', linewidth=1, label=reference)]
    fig.legend(handles=legend, loc='upper left', bbox_to_anchor=(.225, .873), ncols=4, frameon=False, fontsize=10)
    header(fig, ('Server tick durations' if side == 'server' else 'Client frame intervals') + ' by workload', context)
    note = ('Ticks include Riven/world work and bounded lab work. The 50 ms line is the 20 TPS budget.' if side == 'server'
            else 'Frame intervals include the 120 FPS cap and host scheduling; they are not exclusive GPU time.')
    fig.text(.025, .09, note + '\n' + recording_note + ' Snapshot/map-churn correctness probes are excluded. Source: results.json.', fontsize=10, va='top')
    return save(fig, output, side + '-timing-tails')


def quantile_bars(axis, stats, scale=1, digits=2):
    values = [stats[key] * scale for key, _ in QUANTILES]
    axis.barh(range(4), values, height=.52, color=BLUE, edgecolor=INK, linewidth=.6)
    axis.set_yticks(range(4), [label for _, label in QUANTILES])
    axis.set_ylim(3.5, -.5); axis.set_xlim(0, max(values) * 1.24)
    for y, value in enumerate(values):
        axis.text(value + max(values) * .02, y, f'{value:.{digits}f}', va='center', family='DejaVu Sans Mono', fontsize=11)
    scaffold(axis)


def reload_chart(lookup, output, context):
    sync = lookup[('read', 'load-sync')]['server']['operationDurations']
    asynchronous = lookup[('read', 'load-async')]['server']['operationDurations']
    fig = plt.figure(figsize=(12.4, 6.6))
    axes = [fig.add_axes([.12, .26, .34, .46]), fig.add_axes([.62, .26, .34, .46])]
    for axis, stats, title in [(axes[0], sync, 'Synchronous read call'), (axes[1], asynchronous, 'Asynchronous request to callback')]:
        quantile_bars(axis, stats, digits=3 if axis is axes[0] else 2)
        axis.set_xlabel('Measured milliseconds; independent panel scale')
        axis.set_title(title + f"\n{stats['count']:,} cube requests", loc='left', fontsize=12, pad=13)
    header(fig, 'Stored-cube reload latency', context)
    fig.text(.025, .14, 'Sync: eight owner-thread reads per tick. Async: up to 16 outstanding; latency includes queueing and tick dispatch.\n'
             'Separate 512-cube cohorts in one fresh JVM. OS caches were not purged. These timers measure different boundaries.', fontsize=10, va='top')
    fig.text(.025, .065, 'All 1,024 stored markers recovered. One host/run, JFR active. Source: server.operationDurations in results.json.', fontsize=10, va='top')
    return save(fig, output, 'cube-reload-latency')


def save_chart(lookup, output, context):
    server = lookup[('write', 'save')]['server']
    fig = plt.figure(figsize=(12.4, 6.6))
    left = fig.add_axes([.20, .29, .29, .41]); right = fig.add_axes([.66, .29, .30, .41])
    totals = [server['saveSubmissionNanos'] / 1e6, server['blockingFlushNanos'] / 1e6]
    left.barh(range(2), totals, height=.43, color=BLUE, edgecolor=INK, linewidth=.6)
    left.set_yticks(range(2), ['Submission calls\n(total)', 'Final blocking flush\n(single call)'])
    left.set_ylim(1.6, -.6); left.set_xlim(0, max(totals) * 1.24)
    for y, value in enumerate(totals):
        left.text(value + max(totals) * .02, y, f'{value:.2f}', va='center', family='DejaVu Sans Mono', fontsize=11)
    left.set_xlabel('Measured milliseconds'); scaffold(left)
    left.set_title('Submission and flush\n1,024 cube markers', loc='left', fontsize=12, pad=13)
    quantile_bars(right, server['operationDurations'], scale=1000, digits=1)
    right.set_xlabel('Microseconds per cube submission')
    right.set_title('Individual cube submissions\n1,024 measured calls', loc='left', fontsize=12, pad=13)
    header(fig, 'Cube save submission and storage flush', context)
    fig.text(.025, .17, f"Maximum server tick during save: {server['serverTicks']['maxMs']:.2f} ms; p95: {server['serverTicks']['p95Ms']:.2f} ms.\n"
             'Submission total includes the final dirty-column/global request. Per-cube timings exclude marker edits and that final request.', fontsize=10, va='top')
    fig.text(.025, .087, 'Flush blocks for queued storage completion. These are call totals, not whole-workload wall time or disk throughput.\n'
             'Marker durability passed fresh-JVM reload and independent inspection. One host/run, JFR active. Source: results.json.', fontsize=10, va='top')
    return save(fig, output, 'cube-save-costs')


def export_table(scenarios, output):
    rows = []
    for scenario in scenarios:
        operation_grain = {'generate': 'one synchronous cube generation request',
                           'resident': 'one batch of 10000 loaded-cube lookups',
                           'save': 'one cube submission; excludes final column/global request',
                           'load-sync': 'one owner-thread cube read call',
                           'load-async': 'one async request-to-callback interval; includes queueing/dispatch',
                           'lighting-snapshot': 'one cube-to-NBT correctness probe'}.get(scenario['scenario'], 'bounded operation')
        for side, timer, grain in [('server', 'serverTicks', 'server tick'), ('client', 'frameIntervals', 'client frame interval'),
                                   ('server', 'operationDurations', operation_grain)]:
            stats = scenario[side][timer]
            if not stats['count']: continue
            rows.append(dict(phase=scenario['phase'], scenario=scenario['scenario'], run_id=scenario['runId'], side=side,
                             timer=timer, sample_grain=grain, operations=scenario['server']['operations'],
                             diagnostic_only=scenario['scenario'] in ('lighting-snapshot', 'map-churn'),
                             **stats))
    with (output / 'graph-data.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True, help='Existing report_cubic_lab.py results.json')
    parser.add_argument('--output', type=Path, required=True, help='New directory for figures and their source data')
    parser.add_argument('--timings-only', action='store_true', help='Export server/client timing tails for a validated scenario subset')
    args = parser.parse_args(); report_path = args.report.resolve(); output = args.output.resolve()
    report, scenarios = read_report(report_path)
    environment = report['environment']; lookup = {(s['phase'], s['scenario']): s for s in scenarios}
    for key in ([] if args.timings_only else [('read', 'load-sync'), ('read', 'load-async'), ('write', 'save')]):
        if key not in lookup:
            raise ValueError('Detailed figures need the complete default suite, including ' + '/'.join(key))
    output.mkdir(parents=True, exist_ok=False)
    date = report['runs'][0]['result']['scenarios'][0]['server']['startUtc'][:10]
    versions = report['runs'][0].get('observedRuntimeVersions', {})
    runtime = (versions.get('server') or environment['runtimeVersion'])
    if versions.get('client') and versions['client'] != runtime:
        runtime = 'server ' + runtime + ', client ' + versions['client']
    java_major = environment.get('javaRuntime', {}).get('major', 25)
    context = f"{environment['name']}  |  {date}  |  Cleanroom {runtime} / Java {java_major}  |  native client + dedicated server"
    observed_distances = sorted({(s['server']['horizontalViewDistance'], s['server']['verticalViewDistance'],
                                 s['client']['renderDistance'], s['client']['verticalViewDistance']) for s in scenarios})
    context += '  |  server X/Z,Y ' + '; '.join(f'{h},{v}' for h, v, _, _ in observed_distances)
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11, 'text.color': INK, 'axes.labelcolor': INK,
                         'xtick.color': INK, 'ytick.color': INK, 'svg.fonttype': 'none', 'svg.hashsalt': 'cubiclab.performance.v1'})
    contract = {'schema': 'cubiclab.graph-contracts.v1', 'surface': 'Standalone scientific PNG/SVG figures and a downloadable ZIP',
                'palette': {'policy': 'single-root preferred', 'blue': BLUE, 'neutral': INK},
                'charts': [
                    {'file': 'server-timing-tails', 'family': 'comparison', 'variant': 'p95 bars with p99/max markers',
                     'question': 'Which workload windows exceed the server tick budget or contain long tails?',
                     'grain': f"{sum(s['server']['serverTicks']['count'] > 1 for s in scenarios)} scenario windows, excluding single-tick correctness probes", 'units': 'ms per tick', 'referenceMs': 50},
                    {'file': 'client-timing-tails', 'family': 'comparison', 'variant': 'p95 bars with p99/max markers',
                     'question': 'How do client frame interval tails vary by workload?', 'grain': 'same windows; exact per-window frame counts are shown',
                     'units': 'ms per frame interval', 'referenceMs': 1000 / 120},
                    {'file': 'cube-reload-latency', 'family': 'comparison', 'variant': 'separate quantile bars with explicit independent scales',
                     'question': 'What do owner-thread read calls and queued async completion latency look like?',
                     'grain': '512 requests per separate cohort; different timing boundaries', 'units': 'ms'},
                    {'file': 'cube-save-costs', 'family': 'comparison', 'variant': 'stage call totals and per-cube quantile bars',
                     'question': 'How much measured time is spent submitting saves and blocking on the final flush?',
                     'grain': '1024 cube submission calls plus one final column/global request and one flush', 'units': 'ms totals; microseconds per cube'}],
                'limits': ['One host/run, no matched before/after speedup claim; actual recording count retained below.',
                           'Only summary quantiles are retained; no histogram or timeline is reconstructed.',
                           'Rendering qualification remains separate from frame/packet checks.']}
    if args.timings_only: contract['charts'] = contract['charts'][:2]
    jfr_count = sum(item['path'].endswith('.jfr') for run in report['runs'] for item in run.get('recordings_and_logs', []))
    contract['retainedJfrRecordings'] = jfr_count
    recording_note = f'One host/run; {jfr_count} JFR recordings retained.'
    (output / 'chart-contracts.json').write_text(json.dumps(contract, indent=2) + '\n', encoding='utf-8')
    files = duration_chart(scenarios, 'server', output, context, recording_note) + duration_chart(scenarios, 'client', output, context, recording_note)
    if not args.timings_only: files += reload_chart(lookup, output, context) + save_chart(lookup, output, context)
    export_table(scenarios, output)
    provenance = {'source': fingerprint(report_path), 'plotter': fingerprint(Path(__file__)),
                  'environment': {key: environment[key] for key in ['name', 'runtimeVersion', 'height', 'cubicJar', 'labJar', 'riven']},
                  'observedRuntimeVersions': [run.get('observedRuntimeVersions') for run in report['runs']],
                  'observedViewDistances': [{'serverHorizontal': h, 'serverVertical': v, 'clientRender': c, 'clientVertical': cv}
                                            for h, v, c, cv in observed_distances],
                  'figures': [fingerprint(path) for path in files]}
    (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8')
    subset_flag = ' --timings-only' if args.timings_only else ''
    extra_figures = '' if args.timings_only else ('![Stored-cube reload latency](cube-reload-latency.png)\n\n'
                                                 '![Cube save submission and flush](cube-save-costs.png)\n')
    (output / 'README.md').write_text('# Native performance graphs\n\n' + context + '\n\n'
        f'Reproduce these figures with `tools/plot_cubic_lab_results.py --report <retained-results.json> --output <new-directory>{subset_flag}`. '
        'This command builds nothing and launches no game.\n\n'
        'PNG files provide previews; SVG files preserve editable vector text. `graph-data.csv` retains exact timing fields, sample counts and run IDs. '
        'Snapshot and map-churn correctness probes remain in the CSV but are excluded from the timing plots. '
        'Source/plotter hashes and exact mod inputs are in `provenance.json`.\n\n'
        'Tick windows include world/lab work. Async latency includes queueing and tick dispatch; sync timers measure a different boundary. '
        'Save-call totals and flush time are not whole-workload duration or disk throughput. '
        f'{recording_note} Descriptive evidence, not a controlled speedup study.\n\n'
        '![Server tick durations](server-timing-tails.png)\n\n'
        '![Client frame intervals](client-timing-tails.png)\n\n' + extra_figures, encoding='utf-8')
    readme = output / 'README.md'
    readme.write_text(readme.read_text(encoding='utf-8').rstrip() + '\n', encoding='utf-8')
    with zipfile.ZipFile(output / 'performance-graphs.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(output.iterdir()):
            if path.suffix != '.zip': archive.write(path, path.name)
    print(json.dumps({'figures': len(files) // 2, 'output': str(output), 'source': str(report_path)}))


if __name__ == '__main__': main()
