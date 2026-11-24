"""Native process control for the lab: the command lines of a session's instance, owned processes with their console
lines and lifecycle receipts, and console commands."""
from contextlib import nullcontext
from datetime import datetime, timezone
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
from lab_files import write_json
from native_process_lifecycle import ProcessLifecycle

PLAYER = 'CubicLab'


def argv(manifest, client, recording=None, jfr_settings=None):
    """The command line of the manifest's server or client: the receipt's (lab_session), with the lab's JVM properties,
    the network timeout and a JFR recording spliced in where the receipt takes a session's JVM options."""
    side = 'client' if client else 'server'
    entry = manifest['commands'][side]
    directory = Path(manifest[side])
    (directory / 'java-tmp').mkdir(exist_ok=True)
    extra = ['-D' + name + '=' + value for name, value in manifest.get('labProperties', {}).items()]
    if manifest.get('networkReadTimeoutSeconds',30)!=30:extra.append('-Dfml.readTimeout='+str(manifest['networkReadTimeoutSeconds']))
    if recording:
        repository = directory / 'jfr-repository'; repository.mkdir(exist_ok=True)
        extra += ['-XX:FlightRecorderOptions=repository=' + str(repository),
                  '-XX:StartFlightRecording=filename=' + str(recording) + ',settings=' + str(jfr_settings or 'profile')
                  + ',dumponexit=true,maxsize=' + str(manifest.get('jfrMaxSizeMiB', 256)) + 'm']
    command = list(entry['command'])
    at = int(entry['jvm_at'])
    command[at:at] = extra
    return command


def spawn(command, directory, log, messages, lifecycle_role=None, workload_guard=None):
    launched_utc = datetime.now(timezone.utc).isoformat()
    launched_monotonic_ns = time.monotonic_ns()
    with workload_guard.launch() if workload_guard else nullcontext():
        process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace',
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        if workload_guard: workload_guard.register(process)
    lifecycle = ProcessLifecycle(process, lifecycle_role, launched_monotonic_ns, launched_utc) if lifecycle_role else None
    process.lab_lifecycle = lifecycle
    def reader():
        with log.open('w', encoding='utf-8') as stream:
            for line in process.stdout:
                if lifecycle is not None: lifecycle.observe_line(line)
                stream.write(line); stream.flush(); messages.put(line)
        messages.put(None)
    threading.Thread(target=reader, daemon=True).start()
    return process


def write_process_receipt(evidence, process):
    lifecycle = getattr(process, 'lab_lifecycle', None)
    if lifecycle is not None:
        try:
            write_json(evidence / (lifecycle.role + '-process.json'),
                {'pid': process.pid, 'command': process.args, 'lifecycle': lifecycle.report()})
        except OSError as error:
            # Optional observation persistence must not prevent owned process cleanup.
            lifecycle.receipt_failure = str(error)


def capture_process_endpoint(evidence, process, endpoint):
    lifecycle = getattr(process, 'lab_lifecycle', None)
    if lifecycle is not None:
        lifecycle.capture(endpoint)
        write_process_receipt(evidence, process)


def retain_process_lifecycles(evidence, processes, result):
    records = {}
    for process in processes:
        lifecycle = getattr(process, 'lab_lifecycle', None)
        if lifecycle is not None:
            lifecycle.close()
            write_process_receipt(evidence, process)
            records[lifecycle.role] = lifecycle.report()
    result['processLifecycle'] = records


def await_line(messages, needle, processes, timeout, workload_guard=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if workload_guard: workload_guard.require_clean()
        try: line = messages.get(timeout=.5)
        except queue.Empty:
            if any(p.poll() is not None for p in processes): raise RuntimeError('Native process exited before ' + needle)
            continue
        if line is not None:
            plain = re.sub(r'\x1b\[[0-9;]*m', '', line)
            if needle in plain: return plain
    raise TimeoutError('Timed out waiting for ' + needle)


def send(server, command):
    server.stdin.write(command + '\n'); server.stdin.flush()
