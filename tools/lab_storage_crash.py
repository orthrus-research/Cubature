"""Owned-process termination at isolated native storage boundaries; never a power-loss test."""
import json
import hashlib
from pathlib import Path
import queue
import re
import shutil
import subprocess
import struct
import time


def inspect_crash_files(fixture,boundary):
    # Fixed EntryLocation3D(-17,-9,31), independently decoded from the pinned file layout.
    name='-2.-1.1.3dr';index=(15<<8)|(7<<4)|15
    if boundary=='overflow-rename':payload=(fixture/(name+'.ext')/str(index)).read_bytes()
    else:
        with (fixture/name).open('rb') as stream:
            header=stream.read(16384)
            if len(header)!=16384:raise ValueError('Truncated crash fixture header')
            packed,=struct.unpack_from('>I',header,index*4);offset=(packed>>8)*512;sectors=packed&255
            if not sectors or offset<16384:raise ValueError('Missing/invalid crash fixture entry')
            stream.seek(offset);length,=struct.unpack('>I',stream.read(4))
            if not 0<length<=sectors*512-4:raise ValueError('Invalid crash fixture payload length')
            payload=stream.read(length)
            if len(payload)!=length:raise ValueError('Truncated crash fixture payload')
    digest=lambda b:hashlib.sha256(b).hexdigest()
    actual=digest(payload);old=digest((fixture/'previous.bin').read_bytes());new=digest((fixture/'replacement.bin').read_bytes())
    passed=actual==new if boundary=='after-barrier' else actual in (old,new) if boundary=='headerForce' else actual==old
    return {'passed':passed,'payloadSha256':actual,'previousSha256':old,'replacementSha256':new,
            'observed':'old' if actual==old else 'new' if actual==new else 'unrecognized','scope':'Independent bytes inspected after owned-process death and before a recovery JVM opens the files.'}


def run(args):
    import cubic_lab as lab
    if not args.crash_id or not re.fullmatch('[a-z0-9-]{1,48}',args.crash_id) or not args.crash_boundary:
        raise ValueError('storage-crash requires --crash-id and --crash-boundary')
    lab.prime(args);target,manifest=lab.load_lab(args)
    if manifest.get('labProperties',{}).get('cubiclab.storageDiagnostics')!='true':raise ValueError('Prepare with --storage-diagnostics')
    server_dir=Path(manifest['server']);fixture=server_dir/'cubiclab-crashes'/args.crash_id
    if fixture.exists():raise ValueError('Crash fixture already exists; use a new identity')
    evidence=target/'runs'/('crash-'+args.crash_id);evidence.mkdir(parents=True,exist_ok=False)
    result={'passed':False,'boundary':args.crash_boundary,'inputHashesBeforeLaunch':lab.record_inputs(manifest),'phases':[],
            'scope':'Controller kills only its freshly spawned native server, paused inside isolated region files. No client/performance claim; OS and storage cache stay alive.'}
    result['preLaunchInputSnapshots']=lab.retain_prelaunch_inputs(manifest,result['inputHashesBeforeLaunch'],evidence)
    try:
        for mode in ('crash','recover'):
            phase=evidence/mode;phase.mkdir();messages=queue.Queue();server=None
            row={'mode':mode,'passed':False};result['phases'].append(row)
            try:
                _,manifest=lab.load_lab(args)
                server=lab.spawn(lab.argv(manifest,False),server_dir,phase/'server.log',messages,lifecycle_role='server')
                lab.write_process_receipt(phase,server);lab.await_line(messages,'Done (',[server],300)
                lab.send(server,f'cubiclab storage-{mode} {args.crash_id} {args.crash_boundary} isolated-files')
                if mode=='crash':
                    marker=fixture/'boundary-ready.json';deadline=time.monotonic()+75
                    while not marker.exists():
                        if server.poll() is not None:raise RuntimeError('Server exited before requested crash boundary')
                        if time.monotonic()>deadline:raise TimeoutError('Storage boundary was not reached')
                        time.sleep(.1)
                    # File existence may precede writer close. Wait for a complete, valid receipt.
                    while True:
                        try:ready=json.loads(marker.read_text());break
                        except (ValueError,OSError):
                            if time.monotonic()>deadline:raise TimeoutError('Incomplete storage boundary receipt')
                            time.sleep(.05)
                    if ready['boundary']!=args.crash_boundary:raise RuntimeError('Wrong storage boundary')
                    shutil.copy2(marker,phase/'boundary-ready.json');lab.capture_process_endpoint(phase,server,'endBeforeQuit')
                    row['lifecycleEndReason']='Intentional owned-process termination at the observed storage boundary'
                    if server.poll() is not None:raise RuntimeError('Server exited before controller termination')
                    row['controllerTerminatedPid']=server.pid;server.kill();server.wait(timeout=20)
                    row['independentBeforeRecovery']=inspect_crash_files(fixture,args.crash_boundary)
                    row['passed']=server.returncode!=0 and row['independentBeforeRecovery']['passed']
                else:
                    row['receipt']=lab.await_line(messages,'CUBICLAB_CRASH_RECOVERED id='+args.crash_id+' PASS',[server],60).strip()
                    lab.send(server,'stop');server.wait(timeout=90)
                    recovery=json.loads((fixture/'recovery.json').read_text());shutil.copy2(fixture/'recovery.json',phase/'recovery.json')
                    row['passed']=server.returncode==0 and recovery['passed'] is True
            finally:
                if server is not None:
                    if server.poll() is None:
                        try:lab.send(server,'stop');server.wait(timeout=10)
                        except (OSError,subprocess.TimeoutExpired):server.kill();server.wait(timeout=20)
                    row['exitCode']=server.returncode;lab.retain_process_lifecycles(phase,[server],row)
                    lab.write_json(phase/'result.json',row)
            if not row['passed']:raise RuntimeError('Crash phase failed: '+mode)
            print('STORAGE_CRASH '+args.crash_boundary+' '+mode+' passed',flush=True)
        result['passed']=True
    except Exception as error:result['failure']=str(error);raise
    finally:lab.write_json(evidence/'result.json',result)
    print(evidence/'result.json',flush=True);return 0
