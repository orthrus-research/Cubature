"""Read-only Java workload exclusion for native timing runs; never stops foreign processes."""
from contextlib import contextmanager
import ctypes
from datetime import datetime, timezone
import hashlib
import json
import subprocess
import threading
import time

from native_process_lifecycle import WindowsProcessApi


class ProcessEntry(ctypes.Structure):
    _fields_ = [('dwSize', ctypes.c_uint32), ('cntUsage', ctypes.c_uint32),
        ('th32ProcessID', ctypes.c_uint32), ('th32DefaultHeapID', ctypes.c_size_t),
        ('th32ModuleID', ctypes.c_uint32), ('cntThreads', ctypes.c_uint32),
        ('th32ParentProcessID', ctypes.c_uint32), ('pcPriClassBase', ctypes.c_int32),
        ('dwFlags', ctypes.c_uint32), ('szExeFile', ctypes.c_wchar * 260)]


class WindowsJavaInventory(WindowsProcessApi):
    def __init__(self):
        super().__init__()
        self.kernel.CreateToolhelp32Snapshot.argtypes = [ctypes.c_uint32, ctypes.c_uint32]
        self.kernel.CreateToolhelp32Snapshot.restype = ctypes.c_void_p
        for name in ['Process32FirstW', 'Process32NextW']:
            method = getattr(self.kernel, name)
            method.argtypes = [ctypes.c_void_p, ctypes.POINTER(ProcessEntry)]
            method.restype = ctypes.c_int32

    def identity_for(self, pid):
        handle = self.open(pid)
        try: return self.identity(handle)
        finally: self.close(handle)

    def snapshot(self):
        handle = self.kernel.CreateToolhelp32Snapshot(2, 0)
        if handle == ctypes.c_void_p(-1).value: raise self.failure('CreateToolhelp32Snapshot')
        rows = []
        try:
            entry = ProcessEntry(); entry.dwSize = ctypes.sizeof(entry)
            more = self.kernel.Process32FirstW(handle, ctypes.byref(entry))
            while more:
                if entry.szExeFile.lower() in ('java.exe', 'javaw.exe'):
                    try: rows.append({**self.identity_for(entry.th32ProcessID), 'executable': entry.szExeFile})
                    except OSError as error:
                        # A process can exit between enumeration and OpenProcess. Access denial is not absence.
                        if error.errno != 87: raise
                more = self.kernel.Process32NextW(handle, ctypes.byref(entry))
            if ctypes.get_last_error() != 18: raise self.failure('Process32NextW')
            return rows
        finally: self.close(handle)

    def idle_daemon_candidates(self):
        # Only before native startup. Do not run WMI/PowerShell in the periodic observer.
        before = {row['pid']: row['creationFileTime100ns'] for row in self.snapshot()}
        command = "$ErrorActionPreference='Stop'; @(Get-CimInstance Win32_Process -Filter \"name='java.exe' OR name='javaw.exe'\" | Select-Object ProcessId,CommandLine) | ConvertTo-Json -Compress"
        result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', command],
            capture_output=True, text=True, check=True, timeout=15, creationflags=subprocess.CREATE_NO_WINDOW)
        rows = json.loads(result.stdout) if result.stdout.strip() else []
        if isinstance(rows, dict): rows = [rows]
        candidates = []
        for row in rows:
            text = row.get('CommandLine') or ''
            # A known pre-existing daemon may stay resident, but its CPU use is checked on every interval.
            if 'org.gradle.launcher.daemon.bootstrap.GradleDaemon' in text.split():
                try: identity = self.identity_for(int(row['ProcessId']))
                except OSError as error:
                    if error.errno == 87: continue
                    raise
                if before.get(identity['pid']) != identity['creationFileTime100ns']: continue
                candidates.append({**identity, 'kind': 'pre-existing-gradle-daemon',
                    'commandSha256': hashlib.sha256(text.encode('utf8')).hexdigest()})
        return candidates


class JavaWorkloadGuard:
    """Five-second OS reads, with owned PID+creation identity and fail-closed observer errors."""
    def __init__(self, api_factory=WindowsJavaInventory, clock=time.monotonic_ns, interval_seconds=5):
        self.clock = clock
        self.interval_seconds = interval_seconds
        self.lock = threading.RLock()
        self.stop_event = threading.Event()
        self.thread = None
        self.api = None
        self.owned = set()
        self.daemons = {}
        self.previous = {}
        self.observations = []
        self.issues = []
        self.closed = False
        try:
            self.api = api_factory()
            self.daemons = {self.key(row): row for row in self.api.idle_daemon_candidates()}
        except Exception as error:
            self.issue('observer-unavailable', str(error))

    @staticmethod
    def key(row): return int(row['pid']), str(row['creationFileTime100ns'])

    def issue(self, kind, detail, identity=None):
        record = {'kind': kind, 'detail': detail, 'identity': identity}
        if record not in self.issues: self.issues.append(record)

    def observe(self):
        with self.lock:
            if self.closed: raise RuntimeError('Java workload guard already closed')
            started = self.clock()
            observation = {'utc': datetime.now(timezone.utc).isoformat(), 'monotonicNanos': started, 'java': []}
            try:
                if self.api is None: raise RuntimeError('Java inventory unavailable')
                for row in self.api.snapshot():
                    key = self.key(row)
                    cpu = row['kernelCpuNanos'] + row['userCpuNanos']
                    role = 'owned' if key in self.owned else 'idle-daemon-candidate' if key in self.daemons else 'foreign'
                    current = {**row, 'role': role}
                    observation['java'].append(current)
                    if role == 'foreign':
                        self.issue('foreign-java', 'Unowned Java process overlaps the native run', {'pid':key[0], 'creationFileTime100ns':key[1]})
                    elif role == 'idle-daemon-candidate' and key in self.previous:
                        previous_time, previous_cpu = self.previous[key]
                        elapsed = started - previous_time
                        delta = cpu - previous_cpu
                        current.update(cpuDeltaNanos=delta, observationIntervalNanos=elapsed)
                        if delta < 0 or elapsed <= 0:
                            self.issue('invalid-cpu-observation', 'Non-monotonic daemon CPU observation', row)
                        elif delta > max(100_000_000, elapsed // 10):
                            self.issue('active-gradle-daemon', 'Pre-existing daemon exceeded 10% of one CPU over the observation interval (100ms minimum allowance)', {'pid':key[0], 'creationFileTime100ns':key[1]})
                    self.previous[key] = started, cpu
            except Exception as error:
                observation['error'] = str(error)
                self.issue('observation-failed', str(error))
            observation['observerReadNanos'] = self.clock() - started
            self.observations.append(observation)
            return observation

    @contextmanager
    def launch(self):
        # Prevent enumeration between Popen and registering its creation identity.
        with self.lock:
            self.require_clean()
            yield

    def register(self, process):
        with self.lock:
            try:
                identity = self.api.identity_for(process.pid)
                if process.poll() is not None: raise RuntimeError('Owned process exited during guard registration')
                self.owned.add(self.key(identity))
            except Exception as error:
                # The caller must still append this Popen to its cleanup list before require_clean raises.
                self.issue('owned-registration-failed', str(error))

    def start(self):
        self.observe()
        self.require_clean()
        if self.thread is not None: raise RuntimeError('Java workload observer already started')
        def monitor():
            while not self.stop_event.wait(self.interval_seconds): self.observe()
        self.thread = threading.Thread(target=monitor, name='CubicLab Java workload observer', daemon=True)
        self.thread.start()

    def require_clean(self):
        with self.lock:
            if self.issues: raise RuntimeError('Native timing excluded by Java workload guard: ' + self.issues[0]['kind'] + ': ' + self.issues[0]['detail'])

    def close(self):
        if self.closed: return
        self.stop_event.set()
        if self.thread is not None: self.thread.join()
        with self.lock:
            self.observe()
            self.closed = True

    def report(self):
        with self.lock:
            return {'schema':'cubiclab.java-workload-guard.v1', 'passed':not self.issues,
                'policy':{'intervalSeconds':self.interval_seconds,'daemonCpuFractionOfOneCore':.1,'daemonCpuMinimumAllowanceNanos':100_000_000},
                'ownedIdentities':[{'pid':pid,'creationFileTime100ns':created} for pid,created in sorted(self.owned)],
                'preExistingDaemons':list(self.daemons.values()), 'issues':list(self.issues),
                'observations':list(self.observations), 'closed':self.closed,
                'scope':'Read-only snapshots of Java/javaw process identities and CPU counters. Foreign JVMs, active pre-existing Gradle daemons and observer errors exclude the run. No process is stopped or reprioritized. Five-second polling can miss shorter foreign processes; non-Java workloads and GPU contention are not excluded. A passing guard is not proof of an otherwise idle host.'}
