"""Preserve diagnostic shutdown evidence and validate complete storage span exports."""
import csv
import hashlib
from pathlib import Path
import shutil


def validate_complete_ticks(manifest, server):
    if manifest.get('labProperties',{}).get('cubiclab.tickTimer')!='complete-v1':return
    summary=server.get('completeServerTicks',{})
    if not server.get('completeServerTickScope') or summary.get('count',0)<=0 or summary.get('count')!=server.get('serverTicks',{}).get('count') or summary.get('droppedCount',0):
        raise RuntimeError('Complete server tick observer missing, truncated or inconsistent')


def lifecycle_names(server):return {p.name for p in (Path(server)/'cubiclab-lifecycle').glob('*') if p.is_file()}


def retain_lifecycle(server, before, evidence):
    source=Path(server)/'cubiclab-lifecycle';rows=[]
    for path in sorted(source.glob('*')):
        if path.name in before or not path.is_file():continue
        target=Path(evidence)/'storage-lifecycle'/path.name;target.parent.mkdir(exist_ok=True)
        shutil.copy2(path,target);rows.append({'file':str(target.relative_to(evidence)),'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'bytes':target.stat().st_size})
    return rows


def validate_trace(summary, directory):
    trace=summary['trace'];name=trace['chronologicalCsv']
    if not isinstance(name,str) or Path(name).name!=name or '/' in name or '\\' in name:raise ValueError('Unsafe trace path')
    if trace['droppedCount'] or trace['totalCount']!=trace['storedCount']:raise ValueError('Storage trace was truncated')
    with (Path(directory)/name).open(newline='') as stream:rows=list(csv.DictReader(stream))
    if len(rows)!=trace['storedCount']:raise ValueError('Storage trace count mismatch')
    operations={value:key for key,value in trace['operations'].items()};counts={};failures={};durations={}
    if len(operations)!=len(trace['operations']) or set(operations.values())!=set(summary['timings']):
        raise ValueError('Storage operation catalog mismatch')
    for row in rows:
        operation=operations[int(row['operation'])];start=int(row['startOffsetNanos']);end=int(row['endOffsetNanos']);success=int(row['success'])
        if start<0 or end<start or success not in (0,1):raise ValueError('Invalid span')
        counts[operation]=counts.get(operation,0)+1;durations[operation]=durations.get(operation,0)+end-start
        if not success:failures[operation]=failures.get(operation,0)+1
    for op,timing in summary['timings'].items():
        if timing['count']!=counts.get(op,0):raise ValueError('Storage operation count mismatch: '+op)
        if timing['totalNanos']!=durations.get(op,0):raise ValueError('Storage duration mismatch: '+op)
    if failures!=summary['failures']:raise ValueError('Storage failure count mismatch')
    return {'spanCount':len(rows),'counts':counts,'inclusiveNanos':durations,'failures':failures,'unfinished':summary.get('unfinishedSpansByOperation',{}),
            'scope':'Completed inclusive spans only; do not add nested operations into exclusive time.'}
