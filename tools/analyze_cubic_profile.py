#!/usr/bin/env python3
"""Analyze exported JFR events and native CubicChunks probe results.

Export with `jfr print --json --stack-depth 128 --events` and EVENTS below.
Use repeated --jfr LABEL=FILE and --probe LABEL=FILE arguments to associate
recordings with workload results. Probe files may contain a `phases` list;
each phase uses name, startUtc, endUtc, elapsedNanos, optional iteration and operations.
Unwindowed probe timings are still reported; no JFR time range is guessed.
Only matplotlib is required beyond Python's standard library, for charts.
"""

import argparse
from collections import Counter, defaultdict
import csv
from datetime import datetime
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
from statistics import median


EVENTS = ','.join('jdk.' + name for name in (
    'ExecutionSample', 'NativeMethodSample', 'ObjectAllocationSample',
    'GarbageCollection', 'GCPhasePause', 'FileRead', 'FileWrite', 'FileForce',
    'JavaMonitorEnter', 'ThreadPark'))


def trim_svg(path):
    if path.suffix == '.svg':
        path.write_text('\n'.join(line.rstrip() for line in path.read_text(encoding='utf-8').splitlines()) + '\n', encoding='utf-8')


SUBSYSTEMS = ('CubicChunks', 'Riven', 'RegionLib', 'GregTech', 'vanilla', 'other')
PREFIXES = (
    ('io.github.opencubicchunks.cubicchunks.', 'CubicChunks'),
    ('cc.orthrus.riven.', 'Riven'),
    ('supersymmetry.riven.', 'Riven'),  # captures recorded before 2026-10-28
    ('cubicchunks.regionlib.', 'RegionLib'),
    ('gregtech.', 'GregTech'),
    ('net.minecraft.', 'vanilla'))
SEMANTICS = {
    'execution': 'jdk.ExecutionSample event counts, not elapsed CPU time or CPU utilization. '
                 'Sampling excludes threads not executing Java code.',
    'native': 'jdk.NativeMethodSample event counts, separate from execution samples; '
              'native samples can include executing or waiting threads.',
    'attribution': 'First recognized subsystem frame scanning from stack leaf toward root; '
                   'unrecognized library leaf frames are skipped. This is stack-path attribution, '
                   'not exclusive method CPU cost. Inclusive tracked-method counts overlap. '
                   'Injected methods in transformed vanilla classes retain vanilla attribution; '
                   'overloaded methods are grouped by class and method name.',
    'allocation': 'jdk.ObjectAllocationSample.weight sums estimate allocation pressure in bytes; '
                  'not exact allocations, retained heap, or a memory leak measurement.',
    'duration_events': 'Phase membership uses event startTime in [startUtc, endUtc). '
                       'Full durations of selected I/O, park, monitor and GC events are retained, '
                       'so they may extend beyond a phase boundary. Durations can overlap '
                       'across threads and must not be summed into total wall time.',
    'io': 'Only recorded file I/O events are counted; recording thresholds may omit short '
          'operations. Recorded bytes and durations are not total filesystem throughput.',
    'gc': 'Top-level GCPhasePause durations and GarbageCollection.sumOfPauses are separate '
          'summaries of potentially overlapping observations; never add them together.',
    'scope': 'Full-recording views include startup/shutdown. Probe views use explicit UTC '
             'windows. All-thread sample scopes can include concurrent background work.',
    'timings': 'Probe elapsedNanos are observed workload wall times, including recorder '
               'overhead where active. A single run is not a controlled speedup result.',
}


def duration_ns(value):
    """Read ISO-8601 duration strings emitted by jfr print --json, exactly."""
    if value is None:
        return 0
    if isinstance(value, (int, float)):
        raise ValueError('Expected JFR ISO-8601 duration, not an untyped number')
    match = re.fullmatch(r'(-?)P(?:(\d+)D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?', value)
    if not match:
        raise ValueError('Unsupported JFR duration: ' + str(value))
    sign, days, hours, minutes, seconds = match.groups()
    total = (Decimal(days or 0) * 86400 + Decimal(hours or 0) * 3600
             + Decimal(minutes or 0) * 60 + Decimal(seconds or 0))
    return int(total * 1_000_000_000) * (-1 if sign else 1)


def timestamp_ns(value):
    """Parse timezone-qualified ISO timestamps without dropping nanoseconds."""
    match = re.fullmatch(r'(.+T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})', value)
    if not match:
        raise ValueError('Expected ISO timestamp with timezone: ' + str(value))
    seconds, fraction, zone = match.groups()
    base = datetime.fromisoformat(seconds + ('+00:00' if zone == 'Z' else zone))
    return int(base.timestamp()) * 1_000_000_000 + int((fraction or '').ljust(9, '0'))


def methods(values):
    frames = (values.get('stackTrace') or {}).get('frames') or []
    result = []
    for frame in frames:
        method = frame.get('method') or {}
        cls = (method.get('type') or {}).get('name', '(unknown class)').replace('/', '.')
        result.append(cls + '.' + method.get('name', '(unknown method)'))
    return result


def subsystem(method):
    return next((name for prefix, name in PREFIXES if method.startswith(prefix)), 'other')


def rows(counter, total=None):
    total = sum(counter.values()) if total is None else total
    return [{'name': name, 'count': count, 'share_percent': count * 100 / total if total else None}
            for name, count in counter.most_common()]


def sample_summary():
    return {'count': 0, 'missing_stacks': 0, 'truncated_stacks': 0,
            'subsystems': Counter(), 'leaf_methods': Counter(), 'first_tracked_methods': Counter(),
            'inclusive_tracked_methods': Counter(), 'threads': Counter()}


def add_sample(summary, values, stack, weight=1):
    summary['count'] += 1
    summary['missing_stacks'] += not bool(stack)
    summary['truncated_stacks'] += bool((values.get('stackTrace') or {}).get('truncated'))
    first = next((method for method in stack if subsystem(method) != 'other'), None)
    summary['subsystems'][subsystem(first) if first else 'other'] += weight
    summary['leaf_methods'][stack[0] if stack else '(missing stack)'] += weight
    summary['first_tracked_methods'][first or '(no tracked subsystem frame)'] += weight
    for method in set(stack):
        if subsystem(method) != 'other':
            summary['inclusive_tracked_methods'][method] += weight
    thread = values.get('sampledThread') or values.get('eventThread') or {}
    summary['threads'][thread.get('javaName') or thread.get('osName') or '(unknown thread)'] += weight


def finish_sample(summary, total=None):
    denominator = summary['count'] if total is None else total
    return {key: rows(value, denominator) if isinstance(value, Counter) else value
            for key, value in summary.items()}


def analyze(events, name, start=None, end=None):
    if (start is None) != (end is None):
        raise ValueError('Both start and end are required for a workload window')
    start_ns, end_ns = (timestamp_ns(start), timestamp_ns(end)) if start else (None, None)
    if start and end_ns <= start_ns:
        raise ValueError('Workload window must have positive duration: ' + name)
    counts = Counter()
    execution, native, allocation = sample_summary(), sample_summary(), sample_summary()
    allocated_classes, gc_causes, gc_names = Counter(), Counter(), Counter()
    allocation_weight = gc_sum_pause = gc_longest_pause = gc_phase_pause = 0
    file_io = defaultdict(lambda: {'count': 0, 'bytes': 0, 'duration_ns': 0,
                                   'maximum_duration_ns': 0, 'subsystems': Counter(), 'methods': Counter()})
    waits = defaultdict(lambda: {'count': 0, 'duration_ns': 0, 'maximum_duration_ns': 0,
                                 'methods': Counter(), 'threads': Counter()})
    first_time, last_time = None, None
    for event in events:
        kind, values = event['type'], event['values']
        instant = timestamp_ns(values['startTime'])
        if start_ns is not None and not start_ns <= instant < end_ns:
            continue
        first_time = instant if first_time is None else min(first_time, instant)
        last_time = instant if last_time is None else max(last_time, instant)
        counts[kind] += 1
        stack = methods(values)
        if kind == 'jdk.ExecutionSample':
            add_sample(execution, values, stack)
        elif kind == 'jdk.NativeMethodSample':
            add_sample(native, values, stack)
        elif kind == 'jdk.ObjectAllocationSample':
            weight = int(values['weight'])
            allocation_weight += weight
            add_sample(allocation, values, stack, weight)
            allocated_classes[(values.get('objectClass') or {}).get('name', '(unknown)').replace('/', '.')] += weight
        elif kind == 'jdk.GarbageCollection':
            gc_sum_pause += duration_ns(values.get('sumOfPauses'))
            gc_longest_pause = max(gc_longest_pause, duration_ns(values.get('longestPause')))
            gc_causes[values.get('cause', '(unknown)')] += 1
            gc_names[values.get('name', '(unknown)')] += 1
        elif kind == 'jdk.GCPhasePause':
            gc_phase_pause += duration_ns(values.get('duration'))
        elif kind in ('jdk.FileRead', 'jdk.FileWrite', 'jdk.FileForce'):
            result = file_io[kind]
            duration = duration_ns(values['duration'])
            result['count'] += 1
            result['bytes'] += max(0, int(values.get('bytesRead', values.get('bytesWritten', 0))))
            result['duration_ns'] += duration
            result['maximum_duration_ns'] = max(result['maximum_duration_ns'], duration)
            tracked = next((method for method in stack if subsystem(method) != 'other'), None)
            result['subsystems'][subsystem(tracked) if tracked else 'other'] += 1
            result['methods'][tracked or (stack[0] if stack else '(missing stack)')] += 1
        elif kind in ('jdk.JavaMonitorEnter', 'jdk.ThreadPark'):
            result = waits[kind]
            duration = duration_ns(values['duration'])
            result['count'] += 1
            result['duration_ns'] += duration
            result['maximum_duration_ns'] = max(result['maximum_duration_ns'], duration)
            tracked = next((method for method in stack if subsystem(method) != 'other'), None)
            result['methods'][tracked or (stack[0] if stack else '(missing stack)')] += 1
            thread = values.get('eventThread') or {}
            result['threads'][thread.get('javaName') or thread.get('osName') or '(unknown thread)'] += 1
    def finish_group(groups):
        return {key: {field: rows(value) if isinstance(value, Counter) else value
                      for field, value in values.items()} for key, values in groups.items()}
    return {
        'name': name, 'started_at': start, 'ended_at': end,
        'window_duration_ns': end_ns - start_ns if start else None,
        'selected_event_span_ns': last_time - first_time if first_time is not None else None,
        'event_counts': dict(sorted(counts.items())),
        'execution': finish_sample(execution), 'native': finish_sample(native),
        'allocation': dict(finish_sample(allocation, allocation_weight), weight_bytes=allocation_weight,
                           classes=rows(allocated_classes, allocation_weight)),
        'gc': {'collection_count': counts['jdk.GarbageCollection'], 'reported_sum_of_pauses_ns': gc_sum_pause,
               'reported_longest_pause_ns': gc_longest_pause, 'top_level_pause_count': counts['jdk.GCPhasePause'],
               'top_level_pause_duration_ns': gc_phase_pause, 'causes': rows(gc_causes), 'collectors': rows(gc_names)},
        'file_io': finish_group(file_io), 'waits': finish_group(waits),
    }


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def iter_jfr_events(stream, chunk_size=262144):
    """Decode one expanded JFR event at a time instead of materializing a GB file.

    JFR repeats class-loader/module metadata at each frame. The export remains
    the provenance source; compact_event keeps only fields used by this analysis.
    """
    decoder = json.JSONDecoder()
    buffer = stream.read(chunk_size)
    header = re.match(r'\s*\{\s*"recording"\s*:\s*\{\s*"events"\s*:\s*\[', buffer)
    if not header:
        raise ValueError('Expected jfr print --json recording/events header')
    buffer = buffer[header.end():]
    expect_comma = False
    while True:
        buffer = buffer.lstrip()
        if not buffer:
            buffer = stream.read(chunk_size)
            if not buffer:
                raise ValueError('Truncated JFR event array')
            continue
        if buffer.startswith(']'):
            footer = buffer + stream.read()
            if not re.fullmatch(r'\]\s*}\s*}\s*', footer):
                raise ValueError('Malformed JFR recording footer')
            return
        if expect_comma:
            if not buffer.startswith(','):
                raise ValueError('Missing comma between JFR events')
            buffer = buffer[1:].lstrip()
            expect_comma = False
        try:
            event, consumed = decoder.raw_decode(buffer)
        except json.JSONDecodeError:
            more = stream.read(chunk_size)
            if not more:
                raise ValueError('Malformed or truncated JFR event') from None
            buffer += more
            continue
        yield compact_event(event)
        buffer = buffer[consumed:]
        expect_comma = True


def compact_event(event):
    values = event['values']
    selected = {key: value for key, value in values.items()
                if key in ('startTime', 'duration', 'weight', 'bytesRead', 'bytesWritten',
                           'sumOfPauses', 'longestPause', 'cause', 'name', 'amount', 'total')}
    stack = values.get('stackTrace')
    if stack:
        selected['stackTrace'] = {'truncated': stack.get('truncated', False), 'frames': [
            {'method': {'name': frame['method']['name'],
                        'type': {'name': frame['method']['type']['name']}}}
            for frame in stack.get('frames') or []]}
    for key in ('sampledThread', 'eventThread'):
        if values.get(key):
            selected[key] = {name: values[key].get(name) for name in ('javaName', 'osName', 'javaThreadId', 'osThreadId')}
    if values.get('objectClass'):
        selected['objectClass'] = {'name': values['objectClass']['name']}
    if values.get('method'):
        method = values['method']
        selected['method'] = {'name': method['name'], 'type': {'name': method['type']['name']}}
    return {'type': event['type'], 'values': selected}


def read_jfr_events(path):
    with path.open(encoding='utf-8-sig') as stream:
        return list(iter_jfr_events(stream))


def provenance(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            digest.update(block)
    return {'file': path.name, 'bytes': path.stat().st_size, 'sha256': digest.hexdigest()}


def probe_phases(data):
    """Support a top-level timing result or an explicit list of phase timings."""
    return data.get('phases', [data])


def phase_name(phase):
    name = str(phase.get('name', phase.get('phase', phase.get('mode', 'probe'))))
    return name + ' #' + str(phase['iteration']) if 'iteration' in phase else name


def timing_rows(label, phase):
    base = {'run': label, 'phase': phase_name(phase), 'iteration': phase.get('iteration'),
            'operations': phase.get('operations', phase.get('cubeCount')),
            'loaded_cubes': phase.get('loadedCubes'), 'checksum': phase.get('checksum')}
    if 'elapsedNanos' in phase:
        yield dict(base, metric='elapsedNanos', elapsed_ns=int(phase['elapsedNanos']))
    # Other explicitly named nanos fields remain separate; they may overlap.
    for key, value in phase.items():
        if key != 'elapsedNanos' and key.endswith('Nanos') and isinstance(value, (int, float)):
            yield dict(base, metric=key, elapsed_ns=int(value))


def write_csv(path, records, fields):
    with path.open('w', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(records)


def export_tables(report, output):
    samples, hotspots, metrics = [], [], []
    for run in report['runs']:
        for scope in run['scopes']:
            base = {'run': run['label'], 'scope': scope['name']}
            for event in ('execution', 'native', 'allocation'):
                group = scope[event]
                denominator = group.get('weight_bytes', group['count'])
                for row in group['subsystems']:
                    samples.append(dict(base, event=event, subsystem=row['name'], value=row['count'],
                                        denominator=denominator, share_percent=row['share_percent'],
                                        unit='sample_weight_bytes' if event == 'allocation' else 'sample_count'))
                for frame_type in ('leaf_methods', 'first_tracked_methods', 'inclusive_tracked_methods'):
                    for row in group[frame_type]:
                        hotspots.append(dict(base, event=event, attribution=frame_type,
                                             method=row['name'], value=row['count'], denominator=denominator,
                                             share_percent=row['share_percent']))
            for key, value in scope['gc'].items():
                if isinstance(value, int):
                    metrics.append(dict(base, category='gc', metric=key, value=value))
            for category in ('file_io', 'waits'):
                for event, group in scope[category].items():
                    for key, value in group.items():
                        if isinstance(value, int):
                            metrics.append(dict(base, category=event, metric=key, value=value))
    write_csv(output / 'subsystems.csv', samples,
              ['run', 'scope', 'event', 'subsystem', 'value', 'denominator', 'share_percent', 'unit'])
    write_csv(output / 'hotspots.csv', hotspots,
              ['run', 'scope', 'event', 'attribution', 'method', 'value', 'denominator', 'share_percent'])
    write_csv(output / 'recorded-events.csv', metrics, ['run', 'scope', 'category', 'metric', 'value'])
    write_csv(output / 'workload-timings.csv', report['timings'],
              ['run', 'phase', 'iteration', 'metric', 'elapsed_ns', 'operations', 'loaded_cubes', 'checksum'])


def combined_chart_scopes(report):
    """Pool repeated non-overlapping phase windows for legible chart rows only."""
    groups = {}
    for run in report['runs']:
        phases = [scope for scope in run['scopes'] if scope['name'] != 'full recording']
        for scope in phases or run['scopes']:
            phase = re.sub(r' #\d+$', '', scope['name'])
            key = (run['label'], phase)
            group = groups.setdefault(key, {'name': phase, 'phase_count': 0,
                                            'execution': {'count': 0, 'subsystems': Counter()},
                                            'allocation': {'count': 0, 'subsystems': Counter()}})
            group['phase_count'] += 1
            for kind in ('execution', 'allocation'):
                group[kind]['count'] += scope[kind]['count']
                group[kind]['subsystems'].update({row['name']: row['count'] for row in scope[kind]['subsystems']})
    result = []
    for (label, phase), group in groups.items():
        group['name'] = phase + (f" ({group['phase_count']} windows)" if group['phase_count'] > 1 else '')
        for kind in ('execution', 'allocation'):
            group[kind]['subsystems'] = rows(group[kind]['subsystems'])
        result.append((label, group))
    return result


def combined_chart_timings(report):
    groups = defaultdict(list)
    run_loaders = {run['label']: next((probe['result'].get('loader') for probe in run['probes']
                                     if probe['result'].get('loader')), 'unspecified') for run in report['runs']}
    for row in report['timings']:
        if row['metric'] == 'elapsedNanos':
            groups[(row['run'], re.sub(r' #\d+$', '', row['phase']))].append(row)
    result = []
    for (label, phase), group in groups.items():
        values = [row['elapsed_ns'] for row in group]
        operations = {row['operations'] for row in group}
        result.append({'run': label, 'loader': run_loaders.get(label, 'unspecified'),
                       'phase': phase, 'repetitions': len(group),
                       'median_ns': median(values), 'minimum_ns': min(values), 'maximum_ns': max(values),
                       'operations_per_repetition': operations.pop() if len(operations) == 1 else None})
    return result


def charts(report, output):
    """Export count/timing bar charts, preserving zero baselines and missing data."""
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 10, 'text.color': '#262626',
                         'axes.labelcolor': '#262626', 'svg.fonttype': 'none'})
    scoped = combined_chart_scopes(report)
    chart_files = []
    if scoped:
        # Charts compare composition, not elapsed CPU time; denominators remain explicit.
        fig, axes = plt.subplots(1, 2, figsize=(14, max(4.8, len(scoped) * .7 + 2)), layout='constrained')
        fig.get_layout_engine().set(rect=(0, .16, 1, .84))
        labels = [label + '\n' + scope['name'] for label, scope in scoped]
        colors = {'CubicChunks': '#2f6690', 'Riven': '#c89225', 'RegionLib': '#b76c36',
                  'GregTech': '#7b8355', 'vanilla': '#b279a2', 'other': '#aaaaaa'}
        hatches = ('', '//', '..', '\\\\', 'xx', '')
        for axis, kind, title, unit in (
            (axes[0], 'execution', 'Java execution samples', 'Recorded samples'),
            (axes[1], 'allocation', 'Allocation sample weights', 'Sample weight (MiB)')):
            offsets = [0.0] * len(scoped)
            divisor = 1048576 if kind == 'allocation' else 1
            for system, hatch in zip(SUBSYSTEMS, hatches):
                values = [next((row['count'] for row in scope[kind]['subsystems'] if row['name'] == system), 0) / divisor
                          for _, scope in scoped]
                axis.barh(range(len(scoped)), values, left=offsets, label=system, color=colors[system],
                          edgecolor='white', linewidth=.5, hatch=hatch)
                offsets = [a + b for a, b in zip(offsets, values)]
            axis.set_yticks(range(len(scoped)), labels if kind == 'execution' else [''] * len(scoped))
            axis.invert_yaxis()
            axis.set_title(title, loc='left', fontweight='bold')
            axis.set_xlabel(unit)
            axis.set_xlim(left=0, right=max(offsets, default=0) * 1.25 or 1)
            for index, ((_, scope), value) in enumerate(zip(scoped, offsets)):
                count = scope[kind]['count']
                annotation = f'{count:,} samples' if kind == 'execution' else f'{value:,.1f} MiB; n={count:,}'
                axis.annotate(annotation, (value, index), xytext=(5, 0), textcoords='offset points', va='center', fontsize=8)
            axis.grid(axis='x', alpha=.2)
            axis.set_axisbelow(True)
            axis.spines[['top', 'right']].set_visible(False)
        handles, legend_labels = axes[0].get_legend_handles_labels()
        legend = fig.legend(handles, legend_labels, loc='lower center', bbox_to_anchor=(.5, .07), ncols=6, frameon=False)
        legend.set_in_layout(False)
        fig.suptitle('CubicChunks native workload profile\nFirst recognized subsystem in each recorded stack; all threads',
                     x=.01, ha='left', fontsize=14)
        fig.text(.01, .014, 'JFR from the declared runtime. Execution counts are not elapsed CPU time. Allocation weights are not retained heap.\n'
                 'Probe UTC windows exclude startup when supplied; full recording includes startup otherwise. Native samples remain separate in JSON/CSV.', fontsize=9)
        for extension in ('png', 'svg'):
            name = 'workload-samples.' + extension
            fig.savefig(output / name, dpi=160, bbox_inches='tight')
            trim_svg(output / name)
            chart_files.append(name)
        plt.close(fig)
    timings = combined_chart_timings(report)
    write_csv(output / 'timing-chart-data.csv', timings,
              ['run', 'loader', 'phase', 'repetitions', 'median_ns', 'minimum_ns', 'maximum_ns', 'operations_per_repetition'])
    if timings:
        by_loader = defaultdict(list)
        for row in timings:
            by_loader[row['loader']].append(row)
        height = max(4.8, max(map(len, by_loader.values())) * .55 + 2)
        fig, axes = plt.subplots(1, len(by_loader), figsize=(9 * len(by_loader), height),
                                 squeeze=False, layout='constrained')
        fig.get_layout_engine().set(rect=(0, .15, 1, .85))
        maximum = max(row['maximum_ns'] for row in timings) / 1e9 * 1.4 or 1
        for axis, (loader, entries) in zip(axes[0], by_loader.items()):
            labels = [row['run'].replace('-' + loader, '') + ' / ' + row['phase'] for row in entries]
            values = [row['median_ns'] / 1e9 for row in entries]
            lower = [(row['median_ns'] - row['minimum_ns']) / 1e9 for row in entries]
            upper = [(row['maximum_ns'] - row['median_ns']) / 1e9 for row in entries]
            axis.barh(labels, values, color='#2f6690', xerr=[lower, upper], capsize=3,
                      error_kw={'ecolor': '#262626', 'linewidth': 1})
            axis.invert_yaxis()
            axis.set_xlabel('Observed wall time (seconds)')
            axis.set_xlim(0, maximum)
            axis.spines[['top', 'right']].set_visible(False)
            axis.grid(axis='x', alpha=.2)
            axis.set_axisbelow(True)
            for index, (row, value) in enumerate(zip(entries, values)):
                suffix = f"; n={row['repetitions']}"
                axis.annotate(f'{value:.3f} s' + suffix, (row['maximum_ns'] / 1e9, index), xytext=(5, 0),
                              textcoords='offset points', va='center', fontsize=9)
            axis.set_title(loader.title() + (' / recorded JVM / JFR' if loader == 'cleanroom' else
                                            ' / Java 8 / no JFR' if loader == 'forge' else ''), loc='left', pad=16)
        fig.suptitle('CubicChunks native probe workload durations\nMedian and full observed range across batches within each run',
                     x=.01, ha='left', fontsize=14)
        fig.text(.01, .018, 'Measured probe elapsedNanos; recorder overhead included where active. Single host, no controlled speedup claim.\n'
                 '512 cubes per generation/load batch; 1M resident lookups per batch. Saves cover current world dirty work; pending counts vary.\n'
                 'Batches use different coordinates and JIT/cache state; range is descriptive, not a confidence interval.', fontsize=9)
        for extension in ('png', 'svg'):
            name = 'workload-timings.' + extension
            fig.savefig(output / name, dpi=160, bbox_inches='tight')
            trim_svg(output / name)
            chart_files.append(name)
        plt.close(fig)
    return chart_files


def labeled_paths(arguments):
    result = defaultdict(list)
    for argument in arguments:
        label, separator, name = argument.partition('=')
        if not separator or not label or not name:
            raise ValueError('Use LABEL=FILE: ' + argument)
        path = Path(name).resolve()
        if not path.is_file():
            raise FileNotFoundError(path)
        result[label].append(path)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jfr', action='append', default=[], metavar='LABEL=JSON')
    parser.add_argument('--probe', action='append', default=[], metavar='LABEL=JSON')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--no-charts', action='store_true')
    args = parser.parse_args()
    recordings, probes = labeled_paths(args.jfr), labeled_paths(args.probe)
    if not recordings and not probes:
        parser.error('Supply at least one JFR export or probe result')
    report = {'schema': 'cubicchunks.native-profile.v1', 'semantics': SEMANTICS,
              'analyzer': provenance(Path(__file__)), 'runs': [], 'timings': []}
    for label in dict.fromkeys([*recordings, *probes]):
        paths = recordings.get(label, [])
        if len(paths) > 1:
            raise ValueError('Use a distinct label for each JFR recording: ' + label)
        run = {'label': label, 'jfr_export': provenance(paths[0]) if paths else None,
               'probes': [], 'scopes': []}
        events = None
        if paths:
            events = read_jfr_events(paths[0])
            run['scopes'].append(analyze(events, 'full recording'))
        scope_names = {'full recording'}
        for path in probes.get(label, []):
            data = read_json(path)
            run['probes'].append({'input': provenance(path), 'result': data})
            for phase in probe_phases(data):
                name = phase_name(phase)
                report['timings'].extend(timing_rows(label, phase))
                start = phase.get('startUtc', phase.get('startedAt'))
                end = phase.get('endUtc', phase.get('endedAt'))
                if events is not None and start and end:
                    if name in scope_names:
                        raise ValueError('Duplicate phase name within one recording: ' + label + '/' + name)
                    scope_names.add(name)
                    run['scopes'].append(analyze(events, name, start, end))
        report['runs'].append(run)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    export_tables(report, output)
    report['charts'] = [] if args.no_charts else charts(report, output)
    (output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'runs': len(report['runs']), 'timings': len(report['timings']),
                      'charts': report['charts'], 'output': str(output)}, indent=2))


if __name__ == '__main__':
    main()
