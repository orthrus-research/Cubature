#!/usr/bin/env python3
"""Prepare and run isolated native Cleanroom clients/servers for the optional cube lab. Python 3.11+."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import time
import uuid
from native_workload_guard import JavaWorkloadGuard
from lab_files import sha, write_json
from lab_input_snapshot import retain_prelaunch_inputs
from lab_instance import FORK, export_client, load_lab, prime, record_inputs
from lab_process import (PLAYER, argv, await_line, capture_process_endpoint, retain_process_lifecycles, send, spawn,
                         write_process_receipt)
from lab_profiles import validate_jvm_capture, validate_view_distance_capture
import lab_workload



@contextmanager
def native_measurement_lease(root, name):
    """One measured pair per workspace; the OS releases the lease if a controller exits."""
    path = root / '.local/cubic-lab/native-measurement.lock'
    path.parent.mkdir(parents=True, exist_ok=True)
    with os.fdopen(os.open(path, os.O_RDWR | os.O_CREAT, 0o600), 'r+b') as stream:
        if path.stat().st_size == 0: stream.write(b' '); stream.flush()
        stream.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise RuntimeError('Another native lab owns this workspace measurement lease; preserve that run') from error
        try:
            stream.seek(1); stream.write(json.dumps({'pid':os.getpid(),'name':name,'startedUtc':datetime.now(timezone.utc).isoformat()}).encode())
            stream.truncate(); stream.flush()
            yield
        finally:
            stream.seek(0)
            if os.name == 'nt': msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else: fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def selected_scenarios(args, manifest):
    workload = manifest.get('benchmarkWorkload', {}).get('value')
    scenarios = args.scenarios.split(',') if args.scenarios else (workload['scenarios'].get(args.phase, []) if workload else (
        ['idle','generate','lighting-snapshot','resident','save','horizontal','vertical'] if args.phase == 'write' else ['idle','load-sync','load-async','resident']))
    if not scenarios or any(not lab_workload.scenario_valid(n) for n in scenarios): raise ValueError('No valid scenarios selected for this phase')
    if any(n in {'storage-fault-matrix','storage-content-write','storage-content-read','storage-two-worlds'} for n in scenarios) and manifest.get('labProperties',{}).get('cubiclab.storageDiagnostics')!='true':
        raise ValueError('Storage fault/content fixtures require a fresh --storage-diagnostics preparation')
    if 'route' in scenarios and (not workload or 'route' not in workload): raise ValueError('route requires a prepared workload')
    return scenarios


def run(args, *, load=None, prime_lab=None, select=None, record=None, retain=None, validate=None):
    """Run the selected scenarios natively and retain their evidence. A caller that prepares its own
    instances passes its preparation boundary: load returns (target, manifest), prime_lab primes the
    instance, select names the scenarios, record hashes the inputs before launch, retain keeps their
    proofs and validate checks each capture. Each defaults to this lab's own."""
    load, prime_lab, select = load or load_lab, prime_lab or prime, select or selected_scenarios
    record, retain = record or record_inputs, retain or retain_prelaunch_inputs
    validate = validate or lab_workload.validate_capture
    from lab_storage_evidence import lifecycle_names, retain_lifecycle
    target, prepared = load(args)
    scenarios = select(args, prepared)
    guard = JavaWorkloadGuard()
    guard.observe()
    preflight = target / 'runs' / ('java-workload-preflight-' + uuid.uuid4().hex + '.json')
    write_json(preflight, guard.report())
    guard.require_clean()
    prime_lab(args)
    target, manifest = load(args)
    server_dir, client_dir = Path(manifest['server']), Path(manifest['client'])
    lifecycle_before = lifecycle_names(server_dir)
    phase = args.phase
    if phase == 'write' and (server_dir / 'riven-dev/level.dat').exists(): raise RuntimeError('Write requires a fresh world')
    if phase == 'read' and not (server_dir / 'riven-dev/level.dat').exists(): raise RuntimeError('Read requires a saved fixture')
    evidence = target / 'runs' / phase
    evidence.mkdir(parents=True, exist_ok=False)
    server_messages, client_messages = queue.Queue(), queue.Queue()
    processes = []; result = {'schema': 1, 'phase': phase, 'passed': False, 'scenarios': [], 'jfr': args.jfr,
        'inputHashesBeforeLaunch': record(manifest)}
    result['scenarioSequence'] = scenarios
    result['javaWorkloadPreflight'] = {'path':str(preflight), 'sha256':sha(preflight)}
    if manifest.get('benchmarkWorkload'):
        result['benchmarkWorkload'] = {k: manifest['benchmarkWorkload'][k] for k in ['schema','id','seed','sha256']}
    result['preLaunchInputSnapshots'] = retain(manifest, result['inputHashesBeforeLaunch'], evidence)
    server_jfr_settings = None
    if getattr(args, 'server_jfr_settings', None):
        server_jfr_settings = evidence / 'server-settings.jfc'
        shutil.copy2(args.server_jfr_settings, server_jfr_settings)
        result['serverJfrSettings'] = {'source': str(args.server_jfr_settings.resolve()),
            'retained': str(server_jfr_settings), 'sha256': sha(server_jfr_settings),
            'scope': 'Diagnostic method tracing can change timings; not performance acceptance'}
    client_jfr_settings = None
    if getattr(args, 'client_jfr_settings', None):
        client_jfr_settings = evidence / 'client-settings.jfc'
        shutil.copy2(args.client_jfr_settings, client_jfr_settings)
        result['clientJfrSettings'] = {'source': str(args.client_jfr_settings.resolve()),
            'retained': str(client_jfr_settings), 'sha256': sha(client_jfr_settings),
            'scope': 'Diagnostic method tracing can change timings; not performance acceptance'}
    try:
        guard.start()
        server_command = argv(manifest, False, evidence / 'server.jfr' if args.jfr else None, server_jfr_settings)
        server = spawn(server_command, server_dir, evidence / 'server.log', server_messages, lifecycle_role='server', workload_guard=guard); processes.append(server)
        write_process_receipt(evidence, server)
        await_line(server_messages, 'Done (', processes, 240, workload_guard=guard)
        capture_process_endpoint(evidence, server, 'ready')
        client_command = argv(manifest, True, evidence / 'client.jfr' if args.jfr else None, client_jfr_settings)
        client = spawn(client_command, client_dir, evidence / 'client.log', client_messages, lifecycle_role='client', workload_guard=guard); processes.append(client)
        write_process_receipt(evidence, client)
        await_line(server_messages, PLAYER + ' joined the game', processes, 240, workload_guard=guard)
        await_line(client_messages, 'CUBICLAB_CLIENT_VIEW screen=none', processes, 45, workload_guard=guard)
        capture_process_endpoint(evidence, client, 'ready')
        print('Real native client joined the loopback server.', flush=True)
        for scenario in scenarios:
            start_request = {'scenario': scenario, 'requestedUtc': datetime.now(timezone.utc).isoformat(),
                             'timeoutSeconds': args.scenario_start_timeout, 'acknowledged': False}
            result.setdefault('scenarioStartRequests', []).append(start_request)
            request_started = time.monotonic()
            try:
                send(server, 'cubiclab run ' + scenario)
                started = await_line(server_messages, 'CUBICLAB_STARTED', processes,
                                     args.scenario_start_timeout, workload_guard=guard)
                if 'scenario=' + scenario not in started.split():
                    raise RuntimeError('Unexpected scenario acknowledgement: ' + started.strip())
                start_request.update(acknowledged=True, receipt=started.strip(),
                                     acknowledgedUtc=datetime.now(timezone.utc).isoformat())
            finally:
                start_request['waitSeconds'] = time.monotonic() - request_started
            run_id = re.search(r'runId=([0-9a-f-]{36})', started)[1]
            start_request['runId'] = run_id
            view_timeout = int(manifest.get('labProperties', {}).get('cubiclab.fullViewTimeoutSeconds', '840'))
            ended = await_line(server_messages, 'CUBICLAB_SERVER_RESULT runId=' + run_id, processes,
                               manifest['benchmarkWorkload']['value'].get('timeoutSeconds',840)+90 if manifest.get('benchmarkWorkload') else
                               view_timeout + 90 if scenario == 'full-view' else
                               int(manifest.get('labProperties',{}).get('cubiclab.streamTicks','240')) * .15 + 90 if scenario in ('horizontal','vertical') else
                               210, workload_guard=guard)
            if ' PASS' not in ended: raise RuntimeError('Scenario failed: ' + ended.strip())
            client_wait_started=time.monotonic()
            try:
                await_line(server_messages, 'CUBICLAB_CLIENT_RESULT runId=' + run_id, processes, args.client_report_timeout, workload_guard=guard)
            except TimeoutError:
                diagnostic_tool=Path(manifest['java']).with_name('jcmd.exe' if os.name=='nt' else 'jcmd')
                for name,arguments in [('client-timeout-threads.txt',['Thread.print']),('client-timeout-jfr.txt',['JFR.dump','filename='+str(evidence/'client-timeout.jfr')])]:
                    try:
                        with (evidence/name).open('w',encoding='utf-8') as diagnostic_log:
                            subprocess.run([str(diagnostic_tool),str(client.pid),*arguments],stdout=diagnostic_log,stderr=subprocess.STDOUT,timeout=20,check=False)
                    except (OSError,subprocess.TimeoutExpired):pass
                raise
            server_data = json.loads((server_dir / 'cubiclab-results' / run_id / 'server.json').read_text())
            client_data = json.loads((client_dir / 'cubiclab-results' / run_id / 'client.json').read_text())
            validate_view_distance_capture(manifest, server_data, client_data)
            validate_jvm_capture(server_data, client_data, manifest)
            from lab_storage_evidence import validate_complete_ticks
            validate_complete_ticks(manifest,server_data)
            validate(manifest, server_data, client_data)
            if 'seed' in manifest and server_data.get('worldSeed') != manifest['seed']:
                raise RuntimeError('Native world seed differs from preparation')
            if not client_data['frameIntervals']['count']: raise RuntimeError('No real rendered frames captured')
            if client_data['framesWithGui']: raise RuntimeError('A GUI covered the world during measurement; preserve this run and resolve first-use setup')
            if client_data.get('framesWithOverlay',0) or client_data.get('overlayProbeFailure'):
                raise RuntimeError('A known title/arrival overlay covered measurement or could not be observed; retain this failed capture')
            if scenario in ('horizontal', 'vertical') and not client_data['packets']['cubeCount']:
                raise RuntimeError('Streaming test received no cubes')
            row = {'scenario': scenario, 'runId': run_id, 'server': server_data, 'client': client_data,
                   'scenarioStartRequest': dict(start_request),
                   'clientReportTimeoutSeconds':args.client_report_timeout,'clientReportWaitSeconds':time.monotonic()-client_wait_started}
            result['scenarios'].append(row)
            # Retain raw chronology from both JVMs, without moving timed writes into the capture.
            for side, directory in [('server', server_dir), ('client', client_dir)]:
                source = directory / 'cubiclab-results' / run_id
                for path in [*source.glob('*.csv'), *source.glob('*-route-camera.json')]:
                    destination = evidence / 'raw' / run_id / side / path.name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(path, destination)
            print(f'{scenario}: PASS; {"complete server tick" if "completeServerTicks" in server_data else "Forge-event tick"} p95={server_data.get("completeServerTicks",server_data["serverTicks"])["p95Ms"]:.2f} ms, '
                  f'frame p95={client_data["frameIntervals"]["p95Ms"]:.2f} ms, cubes={client_data["packets"]["cubeCount"]}', flush=True)
        for process in processes: capture_process_endpoint(evidence, process, 'endBeforeQuit')
        send(server, 'tp ' + PLAYER + ' 0 80 0'); time.sleep(2)
        send(server, 'cubiclab quit'); client.wait(timeout=45)
        send(server, 'save-all'); send(server, 'stop'); server.wait(timeout=90)
        result['clientExitCode'] = client.returncode; result['serverExitCode'] = server.returncode
        result['passed'] = client.returncode == 0 and server.returncode == 0
    except Exception as error:
        result['failure'] = str(error)
        raise
    finally:
        try: guard.close()
        except Exception as error: guard.issue('observer-close-failed', str(error))
        result['hostWorkloadGuard'] = guard.report()
        if not result['hostWorkloadGuard']['passed']:
            result['passed'] = False
            result.setdefault('failure', 'Java workload guard excluded the native timing run')
        for process in processes: capture_process_endpoint(evidence, process, 'endBeforeQuit')
        if processes and processes[0].poll() is None:
            try:
                send(processes[0], 'cubiclab cancel'); time.sleep(.15)
                send(processes[0], 'cubiclab quit'); send(processes[0], 'save-all'); send(processes[0], 'stop')
                processes[0].wait(timeout=45)
            except (OSError, subprocess.TimeoutExpired): pass
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
        retain_process_lifecycles(evidence, processes, result)
        retain_incomplete_scenarios(result, server_dir, client_dir, evidence)
        result['storageLifecycleFiles'] = retain_lifecycle(server_dir, lifecycle_before, evidence)
        write_json(evidence / 'result.json', result)
    print(evidence / 'result.json')
    return 0 if result['passed'] else 1


def retain_incomplete_scenarios(result, server_dir, client_dir, evidence):
    """Keep failed/partial reports without adding them to validated scenarios."""
    completed_ids = {row['runId'] for row in result['scenarios']}
    incomplete = []
    for request in result.get('scenarioStartRequests', []):
        run_id = request.get('runId')
        if not run_id or run_id in completed_ids:
            continue
        row = {'scenario': request['scenario'], 'runId': run_id,
               'validationStatus': 'incomplete-or-failed', 'readErrors': []}
        for side, directory in [('server', server_dir), ('client', client_dir)]:
            source = directory / 'cubiclab-results' / run_id
            try:
                path = source / (side + '.json')
                if path.is_file():
                    row[side] = json.loads(path.read_text(encoding='utf-8'))
                for path in [*source.glob('*.json'), *source.glob('*.csv')]:
                    destination = evidence / 'raw' / run_id / side / path.name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(path, destination)
            except (OSError, ValueError) as error:
                row['readErrors'].append(side + ': ' + str(error))
        incomplete.append(row)
    result['incompleteScenarios'] = incomplete


def survey(args, *, load=None, prime_lab=None, record=None, retain=None):
    """Resume an explicitly bounded native pregeneration job using the reviewed launcher. A caller that
    prepares its own instances passes load, prime_lab, record and retain, as for run."""
    load, prime_lab = load or load_lab, prime_lab or prime
    record, retain = record or record_inputs, retain or retain_prelaunch_inputs
    operation = getattr(args, 'native_operation', 'pregenerate')
    if operation != 'pregenerate': raise ValueError('Unsupported native bulk operation')
    terrain = operation == 'pregenerate'
    prefix = 'CUBICLAB_PREGEN_' if terrain else 'CUBICLAB_SURVEY_'
    prime_lab(args)
    target, manifest = load(args)
    evidence = target / 'runs' / (operation + '-' + args.survey_id + '-' + uuid.uuid4().hex[:8])
    evidence.mkdir(parents=True, exist_ok=False)
    messages = queue.Queue(); processes = []
    result = {'passed': False, 'inputHashesBeforeLaunch': record(manifest),
              'scope': ('Native LIGHT/save/unload job; independent saved coverage and client inspection remain separate.' if terrain else 'Native generated and saved cubes; population/lighting and settled gameplay costs are separate.')}
    if terrain: result['preLaunchInputSnapshots'] = retain(manifest, result['inputHashesBeforeLaunch'], evidence)
    try:
        command = argv(manifest, False, evidence / 'server.jfr' if args.jfr else None)
        server = spawn(command, Path(manifest['server']), evidence / 'server.log', messages); processes.append(server)
        write_json(evidence / 'server-process.json', {'pid': server.pid, 'command': command})
        await_line(messages, 'Done (', processes, 300)
        send(server, f'cubiclab {operation} {args.survey_id} {args.survey_origin_x} {args.survey_origin_z} {args.survey_width} {args.survey_height} {args.survey_max_columns}')
        await_line(messages, prefix + 'STARTED', processes, 30)
        started = time.monotonic(); last_progress = started
        while True:
            try: line = messages.get(timeout=1)
            except queue.Empty:
                if server.poll() is not None: raise RuntimeError('Native survey server exited')
                if time.monotonic()-last_progress > 900: raise TimeoutError('No durable survey progress for 15 minutes')
                continue
            if line is None: continue
            plain = re.sub(r'\x1b\[[0-9;]*m', '', line)
            if prefix + 'PROGRESS' in plain:
                last_progress=time.monotonic(); print(plain.strip(),flush=True)
            if prefix + 'RESULT' in plain:
                result['receipt']=plain.strip()
                if ' PASS ' not in plain: raise RuntimeError(plain.strip())
                break
        result['seconds']=time.monotonic()-started
        if terrain:
            result['inputHashesAfterWork'] = record(manifest)
            for key in ('mods', 'controllerSources', 'generationInputsSha256'):
                if result['inputHashesBeforeLaunch'][key] != result['inputHashesAfterWork'][key]:
                    raise RuntimeError('Pregeneration inputs changed during native work: ' + key)
        send(server,'save-all');send(server,'stop');server.wait(timeout=180)
        result['serverExitCode']=server.returncode;result['passed']=server.returncode==0
        result['surveyDirectory']=str(Path(manifest['server'])/('cubiclab-pregeneration' if terrain else 'cubiclab-surveys')/args.survey_id)
        if terrain:
            checkpoint = json.loads((Path(result['surveyDirectory'])/'checkpoint.json').read_text())
            result['mapComplete'] = checkpoint['complete']
            result['committedColumns'] = checkpoint['committedColumns']
    except Exception as error:
        result['failure']=str(error);raise
    finally:
        if processes and processes[0].poll() is None:
            try:
                send(processes[0],'cubiclab cancel');send(processes[0],'stop');processes[0].wait(timeout=180)
            except (OSError,subprocess.TimeoutExpired):pass
        for process in processes:
            if process.poll() is None: process.terminate();process.wait(timeout=20)
        write_json(evidence/'result.json',result)
    print(evidence/'result.json',flush=True)
    return 0 if result['passed'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prime', 'export', 'run', 'storage-crash', 'server', 'client'])
    parser.add_argument('--crash-id', help='New bounded identity for an isolated native process-crash fixture.')
    parser.add_argument('--crash-boundary', choices=['payloadForce','headerForce','overflow-rename','after-barrier'])
    parser.add_argument('--riven-workspace', type=Path, default=FORK.parents[1])
    parser.add_argument('--name', required=True)
    parser.add_argument('--phase', choices=['write', 'read'], default='write')
    parser.add_argument('--scenarios', help='Comma-separated scenario override; prerequisites still apply')
    parser.add_argument('--jfr', action='store_true')
    parser.add_argument('--server-jfr-settings', type=Path,
                        help='Opt-in server diagnostic JFC for run --jfr; retained and hashed.')
    parser.add_argument('--client-jfr-settings', type=Path,
                        help='Opt-in client diagnostic JFC for run --jfr; retained and hashed.')
    parser.add_argument('--client-report-timeout',type=int,default=30,choices=range(30,181),metavar='30..180',help='Bounded client report deadline; timeout retains owned-client thread/JFR diagnostics. Does not relax frame, GUI or view-distance checks.')
    parser.add_argument('--scenario-start-timeout',type=int,default=10,choices=range(10,181),metavar='10..180',help='Bounded command acknowledgement wait; default 10 seconds. Retains request/wait receipts, including failure. Does not change the native job deadline; startup latency remains separate evidence.')
    parser.add_argument('--survey-id',default='pregeneration',help='Stable journal name; unchanged bounds and settings permit restart and resume.')
    parser.add_argument('--survey-origin-x',type=int,default=-625,help='Minimum chunk X, inclusive; default -10000 blocks.')
    parser.add_argument('--survey-origin-z',type=int,default=-625,help='Minimum chunk Z, inclusive.')
    parser.add_argument('--survey-width',type=int,default=1250,help='Chunk columns; 1250 gives 20000 blocks.')
    parser.add_argument('--survey-height',type=int,default=1250)
    parser.add_argument('--survey-max-columns',type=int,default=1562500,help='Stop cleanly after this many additional columns; resume the same journal later.')
    args = parser.parse_args(); args.riven_workspace = args.riven_workspace.resolve()
    for field in ['server_jfr_settings', 'client_jfr_settings']:
        selected = getattr(args, field)
        if selected:
            selected = selected.resolve()
            setattr(args, field, selected)
            if args.command != 'run' or not args.jfr:
                parser.error('--' + field.replace('_', '-') + ' requires run --jfr')
            if not selected.is_file() or any(c in str(selected) for c in ',\r\n'):
                parser.error('JFC must be an existing file with no comma/newline in its path')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', args.name): parser.error('Invalid lab name')
    if args.scenarios and any(not lab_workload.scenario_valid(s) for s in args.scenarios.split(',')):
        parser.error('Unknown scenario in --scenarios')
    if args.command == 'prime':
        with native_measurement_lease(args.riven_workspace,args.name): prime(args)
        return
    if args.command == 'export': export_client(args); return
    if args.command == 'storage-crash':
        from lab_storage_crash import run as storage_crash
        with native_measurement_lease(args.riven_workspace,args.name):raise SystemExit(storage_crash(args))
    if args.command == 'run':
        with native_measurement_lease(args.riven_workspace,args.name):
            raise SystemExit(run(args))
    _, manifest = load_lab(args)
    client = args.command == 'client'
    raise SystemExit(subprocess.call(argv(manifest, client), cwd=manifest['client' if client else 'server']))


if __name__ == '__main__': main()
