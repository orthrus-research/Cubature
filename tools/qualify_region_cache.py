"""Run known-bad cache regression detectors in disposable JVMs, never inside the game/test JVM."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--artifact',required=True,type=Path);p.add_argument('--java',action='append',required=True,type=Path);p.add_argument('--javac',required=True,type=Path);p.add_argument('--output',required=True,type=Path)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=False);classes=a.output/'classes';classes.mkdir()
    sources=sorted((Path(__file__).resolve().parents[1]/'lab/probes').glob('RegionCache*Probe.java'))
    if len(sources)!=2:raise ValueError('Cache probe sources missing')
    command=[str(a.javac),'--release','8','-cp',str(a.artifact.resolve()),'-d',str(classes),*[str(s) for s in sources]]
    result={'artifactSha256':hashlib.sha256(a.artifact.read_bytes()).hexdigest(),'sources':{s.name:hashlib.sha256(s.read_bytes()).hexdigest() for s in sources},'compileCommand':command,'runs':[],
            'scope':'Expected-bad baseline detectors against the packaged RegionLib. Fake handles, actual cache and save-section traversal. Detection success is not healthy storage or native multiworld qualification.'}
    with (a.output/'compile.log').open('w') as log:subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=60,check=True)
    for index,java in enumerate(a.java):
        for source in sources:
            command=[str(java),'-cp',str(classes)+os.pathsep+str(a.artifact.resolve()),source.stem]
            run=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=20)
            filename=f'{index}-{source.stem}.log';(a.output/filename).write_text(run.stdout)
            detected=run.returncode==0 and 'CONFIRMED' in run.stdout
            result['runs'].append(dict(command=command,exitCode=run.returncode,knownDefectDetected=detected,log=filename))
    result['detectorsPassed']=all(r['knownDefectDetected'] for r in result['runs'])
    (a.output/'result.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2));return 0 if result['detectorsPassed'] else 1

if __name__=='__main__':raise SystemExit(main())
