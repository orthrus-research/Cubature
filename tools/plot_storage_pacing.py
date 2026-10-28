#!/usr/bin/env python3
"""Plot the lab's same-candidate explicit burst and paced autosave observations."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding='utf-8'))
    scenarios = {s['scenario']: s for run in report['runs'] for s in run['result']['scenarios']}
    selected = [scenarios[name] for name in ['save-burst', 'autosave']]
    for scenario in selected:
        if not scenario['server']['passed'] or scenario['client']['framesWithGui']:
            raise ValueError('Failed or GUI-covered storage capture')
    args.output.mkdir(parents=True, exist_ok=False)
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    colors = ['#a85b3b', '#2f6690']
    labels = ['Explicit burst', 'Paced autosave']
    plt.rcParams.update({'font.size': 11, 'axes.titlesize': 12, 'svg.hashsalt': 'cubic-storage-pacing'})
    rows = []
    for scenario in selected:
        server = scenario['server']
        rows.append({'scenario': scenario['scenario'], 'measured_ticks': server['serverTicks']['count'],
                     'max_server_tick_ms': server['serverTicks']['maxMs'],
                     'initial_submission_ms': server['saveSubmissionNanos'] / 1e6,
                     'final_flush_ms': server['blockingFlushNanos'] / 1e6,
                     'elapsed_seconds': server['elapsedNanos'] / 1e9})
    with (args.output / 'pacing-metrics.csv').open('w', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, rows[0]); writer.writeheader(); writer.writerows(rows)
    fig, axes = plt.subplots(1, 3, figsize=(14.5, 5.4))
    fig.subplots_adjust(left=.12, right=.96, top=.80, bottom=.23, wspace=.60)
    for axis, field, title, unit in zip(axes,
            ['max_server_tick_ms', 'final_flush_ms', 'elapsed_seconds'],
            ['Largest observed server tick', 'Final blocking flush', 'Scenario completion time'],
            ['Milliseconds', 'Milliseconds', 'Seconds']):
        values = [row[field] for row in rows]
        bars = axis.barh(labels, values, color=colors)
        axis.invert_yaxis(); axis.set_title(title, loc='left'); axis.set_xlabel(unit)
        axis.set_xlim(0, max(values) * 1.28)
        axis.bar_label(bars, labels=[f'{value:.2f}' for value in values], padding=4)
        axis.spines[['top', 'right']].set_visible(False)
        axis.grid(axis='x', alpha=.2); axis.set_axisbelow(True)
    axes[0].axvline(50, color='#777', linestyle='--', linewidth=1)
    fig.suptitle('Storage pacing: same candidate and dirty 1,024-cube fixture', x=.01, ha='left')
    counts = ' / '.join(str(row['measured_ticks']) for row in rows)
    fig.text(.02, .035, f'Burst / autosave measured ticks: {counts}. Largest ticks include world work and the final flush; 50 ms line = 20 TPS.\n'
             'Single native run; saving is spread over time. This is not an old-versus-new throughput benchmark.', fontsize=9)
    save(fig, args.output, 'storage-pacing'); plt.close(fig)

    server = selected[1]['server']; checks = server['savePacingChecks']
    pending = checks['pendingCoordinatesByTick']
    fig, axis = plt.subplots(figsize=(10.5, 5.2))
    fig.subplots_adjust(left=.13, right=.98, top=.86, bottom=.24)
    axis.step(range(1, len(pending) + 1), pending, where='post', color=colors[1], linewidth=2)
    axis.set_title('Paced autosave drains pending cube and column coordinates', loc='left')
    axis.set_xlabel('Observed server tick after save request'); axis.set_ylabel('Pending owner-thread coordinates')
    axis.set_ylim(bottom=0); axis.set_xlim(1, max(2, len(pending)))
    axis.spines[['top', 'right']].set_visible(False); axis.grid(alpha=.2)
    fig.text(.02, .025, f'Configured quota {checks["configuredEntryLimit"]}; observed maximum {checks["maxProcessedCoordinatesPerTick"]} coordinates/tick. '
             f'Soft snapshot budget {checks["softBudgetMillis"]} ms; individual snapshots can exceed it.\n'
             'Coordinates awaiting serialization are distinct from queued NBT and from bytes durably flushed.', fontsize=9)
    save(fig, args.output, 'autosave-backlog'); plt.close(fig)
    provenance = {'report': str(args.report), 'reportSha256': hashlib.sha256(args.report.read_bytes()).hexdigest(),
                  'environment': report['environment'], 'metrics': rows,
                  'limits': ['Same candidate, one host and run, unequal tick sample counts, different completion latency.',
                             'Soft snapshot budget; full scan and explicit flush may exceed it.']}
    (args.output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8')
    archive = shutil.make_archive(str(args.output), 'zip', args.output)
    print(json.dumps({'graphs': 2, 'output': str(args.output), 'archive': archive}))


def save(fig, output, name):
    for suffix in ['png', 'svg']:
        path = output / (name + '.' + suffix)
        fig.savefig(path, dpi=160, bbox_inches='tight',
                    metadata={'Date': None} if suffix == 'svg' else None)
        if suffix == 'svg':
            path.write_text('\n'.join(line.rstrip() for line in path.read_text(encoding='utf-8').splitlines()) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
