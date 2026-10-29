#!/usr/bin/env python3
"""Validate/export native storage strategy trials; no game launch or build."""
from lab_files import sha
import argparse
import csv
import json
from pathlib import Path
from statistics import mean, median
import zipfile


VARIANTS = [
    'batch1-column-direct', 'batch16-column-direct', 'batch64-column-direct', 'batch256-column-direct',
    'batch64-region-direct', 'batch64-region-heap512', 'batch64-region-heap8192',
]
LABELS = ['1 cube / column order', '16 cubes / column order', '64 cubes / column order',
          '256 cubes / column order', '64 / region order / direct',
          '64 / region order / heap, GZIP 512', '64 / region order / heap, GZIP 8192']


def validate(report):
    from lab_profiles import validate_view_distance_capture
    scenarios = [row for run in report['runs'] for row in run['result']['scenarios']
                 if row['scenario'] == 'storage-strategies']
    if len(scenarios) != 1:
        raise ValueError('Expected exactly one native storage-strategies capture')
    scenario = scenarios[0]
    validate_view_distance_capture(report['environment'], scenario['server'], scenario['client'])
    if (any(not run['result']['passed'] for run in report['runs']) or not scenario['server']['passed']
            or scenario['client']['framesWithGui'] or not scenario['client']['frameIntervals']['count']):
        raise ValueError('Native capture failed qualification')
    checks = scenario['server']['storageStrategyChecks']
    trials = checks['trials']
    if not checks['passed'] or checks['fixtureCubes'] != 1024 or len(trials) != 21:
        raise ValueError('Incomplete storage strategy experiment')
    expected = {(variant, repetition) for variant in VARIANTS for repetition in range(3)}
    if {(row['variant'], row['repetition']) for row in trials} != expected:
        raise ValueError('Missing or duplicate trial identities')
    for row in trials:
        if not row['passed'] or row['cubes'] != 1024 or any(row[key] != 1024 for key in ['regionVerifiedCubes', 'shuffledVerifiedCubes']):
            raise ValueError('Trial did not verify every cube: ' + row['variant'])
        for key in ['writeNanos', 'compressionNanos', 'regionReadNanos', 'shuffledReadNanos', 'compressedBytes']:
            if row[key] <= 0: raise ValueError('Missing/invalid metric: ' + key)
        if row['writeNanos'] - row['compressionNanos'] != row['writeOutsideCompressionNanos']:
            raise ValueError('Write component accounting differs')
    if len({row['compressedBytes'] for row in trials}) != 1:
        raise ValueError('Compression variants changed the compressed-byte workload; review before comparing')
    return scenario, checks, trials


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding='utf-8'))
    scenario, checks, trials = validate(report)
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    with (output / 'trials.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(trials[0])); writer.writeheader(); writer.writerows(trials)
    groups = {variant: [row for row in trials if row['variant'] == variant] for variant in VARIANTS}
    summary = []
    for variant in VARIANTS:
        rows = groups[variant]
        totals = [(row['writeNanos'] + row['flushNanos']) / 1e6 for row in rows]
        summary.append({'variant': variant, 'repetitions': len(rows), 'cubesPerTrial': 1024,
                        'writeAndFlushMedianMs': median(totals), 'writeAndFlushMinMs': min(totals),
                        'writeAndFlushMaxMs': max(totals),
                        'compressionMedianMs': median(row['compressionNanos'] / 1e6 for row in rows),
                        'regionCommitGroups': sorted({row['calculatedRegionCommitGroups'] for row in rows}),
                        'regionReadMedianMs': median(row['regionReadNanos'] / 1e6 for row in rows),
                        'shuffledReadMedianMs': median(row['shuffledReadNanos'] / 1e6 for row in rows)})
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    contract = {
        'surface': 'Exportable standalone PNG/SVG and ZIP',
        'question': 'Which bounded batches, coordinate orders and compression buffers merit production queue experiments?',
        'writeFigure': {'family': 'comparison and composition', 'variant': 'faceted horizontal stacked mean bars with individual total dots',
                        'grain': '1024 real detached cube NBT writes per trial; three within-JVM repetitions',
                        'x': 'milliseconds; zero baseline; independent panel scales', 'y': 'batch/order/buffer variant',
                        'components': ['compression', 'write work outside compression', 'explicit final flush']},
        'readFigure': {'family': 'comparison and spread', 'variant': 'faceted mean dots, min-max intervals and raw repetition dots',
                       'grain': '1024 verified reopened read calls; equality checks excluded',
                       'x': 'milliseconds; zero baseline', 'y': 'same variants; region and deterministic shuffled read orders'},
        'limits': checks['boundary'],
    }
    (output / 'chart-contract.json').write_text(json.dumps(contract, indent=2) + '\n', encoding='utf-8')
    provenance = {'report': {'path': str(args.report.resolve()), 'sha256': sha(args.report)},
                  'plotter': {'path': str(Path(__file__).resolve()), 'sha256': sha(Path(__file__))},
                  'runId': scenario['runId'], 'runtimeVersion': report['environment']['runtimeVersion'],
                  'cubicJar': report['environment']['cubicJar'], 'labJar': report['environment']['labJar'],
                  'riven': report['environment']['riven'], 'viewDistance': report['environment']['viewDistance'],
                  'snapshotNanosBeforeCapture': checks['snapshotNanos'], 'boundary': checks['boundary']}
    (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8')

    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 10, 'svg.fonttype': 'none', 'svg.hashsalt': 'cubiclab.storage-strategies'})
    java_major = report['environment'].get('javaRuntime', {}).get('major', 25)
    context = 'Cleanroom ' + report['environment']['runtimeVersion'] + ' / Java ' + str(java_major) + '; view distance 12 horizontal / vertical / client'
    jfr_note = 'JFR active' if any(run['result'].get('jfr') for run in report['runs']) else 'JFR inactive'
    footer = ('1,024 identical real cube snapshots per trial; 3 repetitions in one JVM; ' + jfr_note + '. Dots show individual trials.\n'
              'Format and force ordering preserved. Shared RegionLib cache and live-world activity remain; no production TPS speedup claim.')
    fig, axes = plt.subplots(1, 2, figsize=(15, 6.2), layout='constrained')
    fig.get_layout_engine().set(rect=(0, .15, 1, .79))
    for axis, indices, title in [(axes[0], range(4), 'Batch size in fixture column order'),
                                  (axes[1], [2, 4, 5, 6], 'Ordering and compression at batch 64')]:
        indices = list(indices)
        left = [0.0] * len(indices)
        for metric, label, color in [('compressionNanos', 'Compression', '#2f6690'),
                                      ('writeOutsideCompressionNanos', 'Other batch-write work', '#c89225'),
                                      ('flushNanos', 'Final flush', '#7b858d')]:
            values = [mean(row[metric] / 1e6 for row in groups[VARIANTS[index]]) for index in indices]
            axis.barh(range(len(indices)), values, left=left, color=color, label=label)
            left = [a + b for a, b in zip(left, values)]
        for y, index in enumerate(indices):
            totals = [(row['writeNanos'] + row['flushNanos']) / 1e6 for row in groups[VARIANTS[index]]]
            axis.scatter(totals, [y - .12, y, y + .12], s=22, facecolors='none', edgecolors='#111111', zorder=3)
        axis.set_yticks(range(len(indices)), [LABELS[index] for index in indices]); axis.invert_yaxis()
        axis.set_xlim(left=0); axis.set_title(title, loc='left'); axis.set_xlabel('Write + final flush, ms (bars: mean; independent scales)')
        axis.grid(axis='x', alpha=.15); axis.set_axisbelow(True); axis.spines[['top', 'right']].set_visible(False)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc='lower center', bbox_to_anchor=(.53, .105), ncol=3, frameon=False)
    fig.suptitle('CubicChunks storage strategies: batch, order and buffer costs\n' + context, x=.01, ha='left')
    fig.text(.01, .015, footer, fontsize=9)
    save(fig, output, 'write-strategies'); plt.close(fig)

    fig, axes = plt.subplots(1, 2, figsize=(13, 7.5), layout='constrained')
    fig.get_layout_engine().set(rect=(0, .13, 1, .84))
    for axis, metric, title in [(axes[0], 'regionReadNanos', 'Read in region order'),
                                (axes[1], 'shuffledReadNanos', 'Read in shuffled order')]:
        for y, variant in enumerate(VARIANTS):
            values = [row[metric] / 1e6 for row in groups[variant]]; center = mean(values)
            axis.errorbar(center, y, xerr=[[center-min(values)], [max(values)-center]], fmt='D', color='#2f6690', capsize=4)
            axis.scatter(values, [y-.12, y, y+.12], s=18, color='#c89225', zorder=3)
        axis.set_yticks(range(len(VARIANTS)), LABELS if axis is axes[0] else [''] * len(VARIANTS)); axis.invert_yaxis()
        axis.set_xlim(left=0); axis.set_title(title, loc='left'); axis.set_xlabel('1,024 read/decompress/parse calls, ms')
        axis.grid(axis='x', alpha=.15); axis.spines[['top', 'right']].set_visible(False)
    fig.suptitle('Reopened reads: equality verified for every cube\n' + context, x=.01, ha='left')
    fig.text(.01, .015, 'Diamond: mean; line: min–max; small dots: three repetitions. First read order alternates by repetition.\n'
             'OS caches remain warm; close/reopen resets shared RegionLib handles. Verification time is excluded; no cold-disk throughput claim.', fontsize=9)
    save(fig, output, 'reopened-reads'); plt.close(fig)
    (output / 'README.md').write_text('# Native storage strategy graphs\n\n'
        + context + '. See the parent report for qualification and limits.\n\n'
        + '![Write strategies](write-strategies.png)\n\n![Reopened reads](reopened-reads.png)\n\n'
        + 'Exact observations: [trial CSV](trials.csv), [summary](summary.json), [provenance](provenance.json).\n', encoding='utf-8')
    with zipfile.ZipFile(output / 'storage-strategy-graphs.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(output.iterdir()):
            if path.suffix != '.zip': archive.write(path, path.name)
    print(json.dumps({'trials': len(trials), 'verifiedReads': sum(row['regionVerifiedCubes'] + row['shuffledVerifiedCubes'] for row in trials),
                      'compressedBytesPerTrial': trials[0]['compressedBytes'], 'summary': summary}, indent=2))


def save(fig, output, name):
    for suffix in ['png', 'svg']:
        path = output / (name + '.' + suffix)
        fig.savefig(path, dpi=160, bbox_inches='tight', metadata={'Date': None} if suffix == 'svg' else None)
        if suffix == 'svg': path.write_text('\n'.join(line.rstrip() for line in path.read_text(encoding='utf-8').splitlines()) + '\n', encoding='utf-8')


if __name__ == '__main__': main()
