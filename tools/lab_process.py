"""Native process control for the lab: launch commands for prepared instances, owned processes with
their console lines and lifecycle receipts, and console commands."""
from contextlib import nullcontext
from datetime import datetime, timezone
import hashlib
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import uuid
from lab_files import write_json
from native_process_lifecycle import ProcessLifecycle

PLAYER = 'CubicLab'


def argv(manifest, client, recording=None, jfr_settings=None):
    directory = Path(manifest['client'] if client else manifest['server'])
    temporary = directory / 'java-tmp'; temporary.mkdir(exist_ok=True)
    result = [manifest['java'], *manifest.get('jvmProfile', {}).get('arguments', ['-Xms1G', '-Xmx6G']),
              '-Djava.io.tmpdir=' + str(temporary), '-Dfile.encoding=UTF-8']
    result += ['-D' + name + '=' + value for name, value in manifest.get('labProperties', {}).items()]
    if manifest.get('networkReadTimeoutSeconds',30)!=30:result.append('-Dfml.readTimeout='+str(manifest['networkReadTimeoutSeconds']))
    if tuple(int(x) for x in manifest.get('runtimeVersion', '0.6.13').split('.')[:2]) >= (0, 7):
        result.append('-Dcubiclab.cleanroom070=true')
    if recording:
        repository = directory / 'jfr-repository'; repository.mkdir(exist_ok=True)
        result += ['-XX:FlightRecorderOptions=repository=' + str(repository),
                   '-XX:StartFlightRecording=filename=' + str(recording) + ',settings=' + str(jfr_settings or 'profile')
                   + ',dumponexit=true,maxsize=' + str(manifest.get('jfrMaxSizeMiB', 256)) + 'm']
    if not client:
        product_version = manifest.get('runtimeVersion', manifest['release']['tag_name'])
        modern_args = directory / 'libraries/com/cleanroommc/cleanroom' / product_version / ('win_args.txt' if os.name == 'nt' else 'unix_args.txt')
        if modern_args.exists():
            return result + ['-Djava.awt.headless=true', '@user_jvm_args.txt', '@' + str(modern_args), 'nogui']
        return result + ['-Djava.awt.headless=true', '-jar', 'cleanroom-' + manifest.get('runtimeVersion', manifest['release']['tag_name']) + '.jar', 'nogui']
    runtime = manifest['clientRuntime']
    classpath = os.pathsep.join([runtime['minecraft']] + [library['path'] for library in runtime['libraries']])
    player_uuid = str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + PLAYER).encode()).digest(), version=3))
    return result + ['--enable-native-access=ALL-UNNAMED', '-Djava.library.path=' + runtime['natives'], '-cp', classpath,
        runtime['mainClass'], '--tweakClass', 'net.minecraftforge.fml.common.launcher.FMLTweaker',
        '--username', PLAYER, '--uuid', player_uuid, '--accessToken', '0', '--userType', 'legacy', '--versionType', 'custom',
        '--version', 'Cleanroom-' + manifest['release']['tag_name'], '--gameDir', str(directory),
        '--assetsDir', runtime['assets'], '--assetIndex', runtime['assetIndex'], '--width', '1280', '--height', '720',
        '--server', '127.0.0.1', '--port', str(manifest['port'])]


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
