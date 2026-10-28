#!/usr/bin/env python3
"""Export measured native client/server lab results and cube-height diagnostics."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
import shutil
from cubic_lab import validate_view_distance_capture


def fingerprint(path):
    with path.open('rb') as stream:
        return {'path': str(path), 'sha256': hashlib.file_digest(stream, 'sha256').hexdigest(), 'bytes': path.stat().st_size}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); lab = args.lab_directory.resolve(); output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((lab / 'lab.json').read_text())
    runs, rows = [], []
    for phase in ['write', 'read']:
        path = lab / 'runs' / phase / 'result.json'
        data = json.loads(path.read_text())
        if not data['passed']: raise ValueError('Incomplete native suite: ' + phase)
        for scenario in data['scenarios']:
            server, client = scenario['server'], scenario['client']
            if manifest.get('viewDistance'): validate_view_distance_capture(manifest, server, client)
            if client['framesWithGui'] or not client['frameIntervals']['count']:
                raise ValueError('Client capture is not an unobstructed world view')
            row = {'phase': phase, 'scenario': scenario['scenario'], 'run_id': scenario['runId'],
                'diagnostic_only': scenario['scenario'] in ('lighting-snapshot', 'map-churn', 'generation-contracts'),
                'operations': server['operations'], 'server_tick_count': server['serverTicks']['count'],
                'horizontal_view_distance': server['horizontalViewDistance'], 'vertical_view_distance': server['verticalViewDistance'],
                'client_render_distance': client['renderDistance'], 'client_vertical_distance': client['verticalViewDistance'],
                'server_tick_mean_ms': server['serverTicks']['meanMs'], 'server_tick_p95_ms': server['serverTicks']['p95Ms'],
                'server_tick_p99_ms': server['serverTicks']['p99Ms'], 'server_tick_max_ms': server['serverTicks']['maxMs'],
                'frame_count': client['frameIntervals']['count'], 'frame_mean_ms': client['frameIntervals']['meanMs'],
                'frame_p95_ms': client['frameIntervals']['p95Ms'], 'frame_p99_ms': client['frameIntervals']['p99Ms'],
                'render_work_mean_ms': client['renderEventWork'].get('meanMs'), 'client_tick_p95_ms': client['clientTicks'].get('p95Ms'),
                'received_cubes': client['packets']['cubeCount'], 'received_payload_bytes': client['packets']['serializedPayloadBytes'],
                'packet_decode_ns': client['packets']['serializationNanos'], 'cube_apply_ns': client['packets']['applyNanos'],
                'cube_load_events': client['cubeLoadEvents'], 'cube_unload_events': client['cubeUnloadEvents'],
                'loads_outside_build_height': client.get('loadsOutsideBuildHeight'),
                'server_data_load_events': server['cubeDataLoadEvents'], 'server_data_save_events': server['cubeDataSaveEvents'],
                'save_submit_ns': server['saveSubmissionNanos'], 'blocking_flush_ns': server['blockingFlushNanos'],
                'gl_renderer': client['glRenderer'], 'width': client['width'], 'height': client['height'], 'frame_cap': client['frameCap']}
            rows.append(row)
            screenshot = Path(manifest['client']) / 'screenshots' / ('cubiclab-' + scenario['runId'] + '.png')
            if scenario['scenario'] in ['horizontal', 'vertical']:
                shutil.copy2(screenshot, output / (scenario['scenario'] + '-in-game.png'))
        files = [lab / 'runs' / phase / name for name in ['server.log', 'client.log', 'server.jfr', 'client.jfr']]
        runtime_versions = {}
        for side in ['server', 'client']:
            log = lab / 'runs' / phase / (side + '.log')
            match = re.search(r'Cleanroom version (\S+)', log.read_text(encoding='utf-8', errors='replace'))
            runtime_versions[side] = match[1] if match else None
        runs.append({'phase': phase, 'result': data, 'input': fingerprint(path),
                     'observedRuntimeVersions': runtime_versions,
                     'recordings_and_logs': [fingerprint(p) for p in files if p.exists()]})
    with (output / 'metrics.csv').open('w', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
    host = json.loads((lab / 'host.json').read_text(encoding='utf-8-sig')) if (lab / 'host.json').exists() else None
    report = {'schema': 'cubiclab.report.v1', 'environment': manifest, 'host': host, 'runs': runs, 'rows': rows,
        'limits': ['One host and one native process pair per phase, not a controlled speedup study.',
            'Client capture requires a world, rendered frames and zero GUI-covered frames.',
            'Frame intervals include the 120 FPS cap, host scheduling and recorder overhead.',
            'Tick durations include the bounded test work. Async latency includes tick dispatch and queueing.',
            'Packet byte counts are uncompressed PacketCubes payloads, not wire traffic.',
            'Reload starts new JVMs; filesystem caches are not purged.',
            'Lighting-snapshot is a two-operation correctness check; one server tick and a brief client capture are not a latency distribution. Missing optional client-tick observations remain null.',
            'Scheduled-ticks is a correctness fixture; observed state-transition timings include fixture preparation and tick dispatch.',
            'Map-churn includes an explicit flush and loaded-index audit; its timings are correctness work, not isolated map throughput.',
            'The reduced content profile and lab-specific 0.7 compatibility overlays do not qualify the full Supersymmetry pack.']}
    (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'svg.fonttype': 'none', 'svg.hashsalt': 'cubiclab.v1', 'font.size': 10})
    fig, axes = plt.subplots(1, 2, figsize=(13, 8), layout='constrained')
    fig.get_layout_engine().set(rect=(0, .16, 1, .84))
    plot_rows = [row for row in rows if not row['diagnostic_only']]
    labels = [row['phase'] + ' / ' + row['scenario'] + (' (1 tick; check)' if row['server_tick_count'] == 1 else '') for row in plot_rows]
    for axis, prefix, title, budget in [(axes[0], 'server_tick', 'Server tick duration', 50),
                                      (axes[1], 'frame', 'Client frame interval', 1000 / 120)]:
        p95 = [row[prefix + '_p95_ms'] for row in plot_rows]; p99 = [row[prefix + '_p99_ms'] for row in plot_rows]
        axis.barh(range(len(plot_rows)), p95, color='#2f6690', label='p95')
        axis.scatter(p99, range(len(plot_rows)), facecolors='none', edgecolors='#262626', marker='o', label='p99', zorder=3)
        axis.set_yticks(range(len(plot_rows)), labels if prefix == 'server_tick' else [''] * len(plot_rows)); axis.invert_yaxis()
        axis.axvline(budget, color='#777777', linestyle='--', linewidth=1, label='20 TPS budget' if prefix == 'server_tick' else '120 FPS interval')
        axis.set_title(title, loc='left'); axis.set_xlabel('Measured milliseconds'); axis.set_xlim(left=0)
        axis.spines[['top', 'right']].set_visible(False); axis.grid(axis='x', alpha=.2); axis.set_axisbelow(True)
        axis.legend(loc='upper center', bbox_to_anchor=(.5, -.08), ncols=3, frameon=False, fontsize=9)
    versions = runs[0]['observedRuntimeVersions']
    runtime_label = ('server ' + versions['server'] + '; client ' + versions['client']
                     if all(versions.values()) and versions['server'] != versions['client'] else manifest['runtimeVersion'])
    fig.suptitle('CubicChunks in-game performance diagnostics\nCleanroom ' + runtime_label + '; real dedicated server and client', x=.01, ha='left')
    jfr_count = sum((lab / 'runs' / phase / (side + '.jfr')).is_file() for phase in ['write', 'read'] for side in ['server', 'client'])
    fig.text(.01, .02, 'Riven height [-128, 384): 32 cubes per column. One host, ' + str(jfr_count) + '/4 JFRs retained, 40 warmup ticks per scenario.\n'
             + rows[0]['gl_renderer'] + '; 1280 x 720, 120 FPS cap, no GUI frames. Descriptive p95/p99; no controlled speedup claim.', fontsize=9)
    save(fig, output, 'frame-and-tick-durations'); plt.close(fig)
    streaming = [scenario for run in runs for scenario in run['result']['scenarios'] if scenario['scenario'] in ['horizontal', 'vertical']]
    if not streaming:
        print(json.dumps({'scenarios': len(rows), 'output': str(output), 'heightChart': 'No streaming route selected'}))
        return
    fig, axis = plt.subplots(figsize=(11, 5.5), layout='constrained')
    for scenario, color, style in zip(streaming, ['#2f6690', '#c89225'], ['-', '--']):
        counts = scenario['client']['observedLoadsByCubeY']; ys = sorted(map(int, counts))
        axis.plot(ys, [counts[str(y)] for y in ys], color=color, linestyle=style, marker='o', markersize=3, label=scenario['scenario'])
    axis.set_title('Observed client cube loads by cube Y', loc='left'); axis.set_xlabel('Cube Y (each cube spans 16 blocks)')
    axis.set_ylabel('Load events during route'); axis.set_ylim(bottom=0); axis.grid(alpha=.2); axis.legend(frameon=False)
    axis.spines[['top', 'right']].set_visible(False)
    distances = sorted({(scenario['server']['horizontalViewDistance'], scenario['server']['verticalViewDistance']) for scenario in streaming})
    distance_label = '; '.join(f'X/Z {horizontal}, vertical {vertical}' for horizontal, vertical in distances)
    fig.text(.01, -.015, 'Six spectator waypoints per route. Counts are events, not unique cubes or retained heap. Effective server view distance: ' + distance_label + '.', fontsize=9)
    save(fig, output, 'cube-height-loads'); plt.close(fig)
    print(json.dumps({'scenarios': len(rows), 'output': str(output)}))


def save(fig, directory, name):
    for suffix in ['png', 'svg']:
        path = directory / (name + '.' + suffix)
        fig.savefig(path, dpi=160, bbox_inches='tight', metadata={'Date': None} if suffix == 'svg' else None)
        if suffix == 'svg': path.write_text('\n'.join(line.rstrip() for line in path.read_text(encoding='utf-8').splitlines()) + '\n', encoding='utf-8')


if __name__ == '__main__': main()
