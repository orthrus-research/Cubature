"""Render checked single-run storage diagnostics. No speedup or stable short-window p99 claim."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
from lab_storage_evidence import validate_trace


def collect(path):
    document=json.loads(path.read_text())
    if document.get('passed') is not True:raise ValueError('Need a passed diagnostic protocol run')
    rows={r['scenario']:r for r in document['scenarios']};out={}
    for name in ('save-command','save-clean','autosave-natural','storage-fault-matrix'):
        row=rows[name];server=row['server']
        if server.get('measurementMode')!='diagnostic-storage-profile':raise ValueError('Wrong measurement lane')
        directory=path.parent/'raw'/row['runId']/'server'
        checked=validate_trace(server['storageDiagnostics'],directory)
        out[name]={'validatedTrace':checked,'server':server,'directory':str(directory)}
    return out


def render(path,output):
    data=collect(path);output.mkdir(parents=True,exist_ok=False)
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import numpy as np
    plt.rcParams.update({'font.family':'DejaVu Sans','font.size':10,'axes.spines.top':False,'axes.spines.right':False})
    fig,axes=plt.subplots(1,2,figsize=(12,4.8),layout='constrained')
    names=['save-command','save-clean','autosave-natural'];labels=['Full save command','Five drained flushes','Natural autosave + barrier']
    x=np.arange(3);width=.24
    colors=['#376fa3','#dd8935','#7953a6']
    for i,op in enumerate(['payloadForce','headerForce','regionFlushForce']):
        counts=[data[n]['validatedTrace']['counts'].get(op,0) for n in names]
        bars=axes[0].bar(x+(i-1)*width,counts,width,label=op,color=colors[i]);axes[0].bar_label(bars,padding=2,fontsize=8)
    axes[0].set_xticks(x,labels,rotation=15,ha='right');axes[0].set_ylabel('Observed force calls');axes[0].legend(fontsize=8);axes[0].set_title('Actual calls reveal redundant flush work')
    natural=data['autosave-natural'];server=natural['server'];trace=server['storageDiagnostics']['trace'];directory=Path(natural['directory'])
    ids={v:k for k,v in trace['operations'].items()}
    with (directory/trace['chronologicalCsv']).open(newline='') as stream:spans=list(csv.DictReader(stream))
    for metric,color,label in [('serverTicks','#9dabb8','Forge events'),('completeServerTicks','#276a9b','Whole server tick')]:
        if metric not in server:continue
        summary=server[metric]
        if summary.get('droppedCount') or not summary.get('percentilesCoverAllObservations'):raise ValueError('Truncated tick observations')
        with (directory/summary['chronologicalCsv']).open(newline='') as stream:ticks=list(csv.DictReader(stream))
        if len(ticks)!=summary['count']:raise ValueError('Tick count mismatch')
        t=[(int(r['observationEndOffsetNanos'])+summary['timelineOriginMonotonicNanos']-trace['originMonotonicNanos'])/1e9 for r in ticks]
        axes[1].plot(t,[int(r['durationNanos'])/1e6 for r in ticks],color=color,lw=1,label=label)
    markers={'worldSaveAllChunks':('#b86b2d','Periodic world save'),'writerFlush':('#7853a6','Explicit final barrier')};seen=set();starts=[]
    for span in spans:
        op=ids[int(span['operation'])]
        if op in markers:
            color,label=markers[op];start=int(span['startOffsetNanos'])/1e9;starts.append(start)
            axes[1].axvline(start,color=color,alpha=.7,ls='--',lw=1,label=label if op not in seen else None);seen.add(op)
    if starts:axes[1].set_xlim(min(starts)-.45,max(t)+.08)
    axes[1].axhline(50,color='#a64242',ls=':',lw=1,label='50 ms');axes[1].set_xlabel('Seconds since capture began (save interval detail)');axes[1].set_ylabel('Tick duration (ms)');axes[1].legend(fontsize=8,loc='upper left');axes[1].set_title('Periodic save and final barrier are distinct')
    fig.suptitle('Storage harness qualification — instrumented baseline',fontsize=15)
    fig.savefig(output/'storage-diagnostics.png',dpi=160);plt.close(fig)
    faults=data['storage-fault-matrix']['server']['storageFaultMatrix']['cases']
    fig,ax=plt.subplots(figsize=(10,4.5),layout='constrained');states=[]
    for case in faults:
        states.append([int(case.get('oldValueAfterReopen',case.get('oldValueAfterFailure',False))),int(case.get('newValueAfterReopen',False)),int(bool(case.get('readFailure'))),int(case['retryRecovered'])])
    ax.imshow(states,cmap=matplotlib.colors.ListedColormap(['#eef1f5','#366c95']),vmin=0,vmax=1,aspect='auto')
    ax.set_xticks(range(4),['Old value preserved','New value readable','Corruption detected','Retry recovered']);ax.set_yticks(range(len(faults)),[c['kind'] for c in faults]);ax.set_title('Injected failures: observed baseline behavior, not a durability pass')
    for i,row in enumerate(states):
        for j,value in enumerate(row):ax.text(j,i,'YES' if value else '—',ha='center',va='center',color='white' if value else '#607080')
    fig.savefig(output/'storage-faults.png',dpi=160);plt.close(fig)
    evidence={'source':str(path.resolve()),'sourceSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'scope':'Instrumented single-run observations. Counts are exact completed calls; no performance gain or power-loss claim.',
              'traces':{n:d['validatedTrace'] for n,d in data.items()},'faults':faults}
    (output/'measurements.json').write_text(json.dumps(evidence,indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--result',required=True,type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args();render(a.result,a.output)
