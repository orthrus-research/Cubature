#!/usr/bin/env python3
"""Plot declared workload geometry. These diagrams are not generated terrain or measured cost."""
import argparse
import json
from pathlib import Path
import lab_workload


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('workloads',nargs='+',type=Path)
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    values=[lab_workload.read(path) for path in args.workloads]
    args.output.mkdir(parents=True,exist_ok=False)
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig,axes=plt.subplots(1,2,figsize=(12,5),layout='constrained')
    for value in values:
        route=value.get('route',{});points=route.get('waypoints',[]);camera=value['camera'];label=value['id']
        if points:
            poses=[p['pose'] for p in points];times=[];elapsed=0
            for point in points:times.append(elapsed);elapsed+=point['ticks']
            axes[0].plot([p[0] for p in poses],[p[2] for p in poses],'-o',label=label)
            if route.get('interpolation','step')=='step':
                axes[1].step([*times,elapsed],[*[p[1] for p in poses],poses[-1][1]],where='post',label=label)
            else:
                tx=[];ys=[]
                for i,point in enumerate(points):
                    tx.extend([times[i],times[i]+point['ticks']-1]);ys.extend([poses[i][1],poses[min(i+1,len(poses)-1)][1]])
                axes[1].plot(tx,ys,label=label)
        else: axes[0].scatter(camera[0],camera[2],marker='*',s=100,label=label+' camera')
    axes[0].set(xlabel='World X (blocks)',ylabel='World Z (blocks)',title='Declared camera/route coordinates');axes[0].set_aspect('equal',adjustable='datalim')
    axes[1].set(xlabel='Observed server ticks (planned)',ylabel='Camera Y (blocks)',title='Route height and dwell schedule')
    for ax in axes:ax.grid(alpha=.25);ax.legend(fontsize=8)
    fig.suptitle('Benchmark workload geometry — proposals, not terrain or performance evidence',fontsize=12)
    for suffix in ['png','svg']:fig.savefig(args.output/('workload-geometry.'+suffix),dpi=160)
    (args.output/'inputs.json').write_text(json.dumps({'workloads':values,'scope':__doc__},indent=2)+'\n')
    plt.close(fig)


if __name__=='__main__':main()
