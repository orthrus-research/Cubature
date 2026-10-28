"""Two endpoint observations of an owned native process; no periodic sampler or process writes."""
import ctypes
from datetime import datetime, timezone
import os
import re
import time


class FileTime(ctypes.Structure):
    _fields_ = [('low', ctypes.c_uint32), ('high', ctypes.c_uint32)]

    def value(self):
        return self.low | self.high << 32


class ProcessMemoryCountersEx(ctypes.Structure):
    _fields_ = [('cb', ctypes.c_uint32), ('pageFaultCount', ctypes.c_uint32)] + [
        (name, ctypes.c_size_t) for name in ('peakWorkingSetSize', 'workingSetSize',
        'quotaPeakPagedPoolUsage', 'quotaPagedPoolUsage', 'quotaPeakNonPagedPoolUsage',
        'quotaNonPagedPoolUsage', 'pagefileUsage', 'peakPagefileUsage', 'privateUsage')]


class WindowsProcessApi:
    """Read-only documented Win32 API, with pointer-sized memory counters on 32/64 bit Python."""
    def __init__(self):
        if os.name != 'nt': raise OSError('Windows process memory API unavailable on this platform')
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        self.psapi = ctypes.WinDLL('psapi', use_last_error=True)
        handle, dword, boolean = ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int32
        self.kernel.OpenProcess.argtypes = [dword, boolean, dword]; self.kernel.OpenProcess.restype = handle
        self.kernel.CloseHandle.argtypes = [handle]; self.kernel.CloseHandle.restype = boolean
        self.kernel.GetProcessId.argtypes = [handle]; self.kernel.GetProcessId.restype = dword
        self.kernel.GetProcessTimes.argtypes = [handle] + [ctypes.POINTER(FileTime)] * 4
        self.kernel.GetProcessTimes.restype = boolean
        self.psapi.GetProcessMemoryInfo.argtypes = [handle, ctypes.POINTER(ProcessMemoryCountersEx), dword]
        self.psapi.GetProcessMemoryInfo.restype = boolean

    @staticmethod
    def failure(operation):
        code = ctypes.get_last_error()
        return OSError(code, operation + ': ' + ctypes.FormatError(code).strip())

    def open(self, pid):
        # Modern Windows documents QUERY_LIMITED_INFORMATION for both APIs. No write/VM-read rights.
        handle = self.kernel.OpenProcess(0x1000, False, pid)
        if not handle: raise self.failure('OpenProcess')
        return handle

    def identity(self, handle):
        pid = self.kernel.GetProcessId(handle)
        if not pid: raise self.failure('GetProcessId')
        created, exited, kernel, user = FileTime(), FileTime(), FileTime(), FileTime()
        if not self.kernel.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited), ctypes.byref(kernel), ctypes.byref(user)):
            raise self.failure('GetProcessTimes')
        # The running process's exit FILETIME is undefined and deliberately never reported.
        return {'pid': pid, 'creationFileTime100ns': str(created.value()),
                'kernelCpuNanos': kernel.value() * 100, 'userCpuNanos': user.value() * 100}

    def memory(self, handle):
        counters = ProcessMemoryCountersEx(); counters.cb = ctypes.sizeof(counters)
        if not self.psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
            raise self.failure('GetProcessMemoryInfo')
        return {'workingSetBytes': int(counters.workingSetSize), 'peakWorkingSetBytes': int(counters.peakWorkingSetSize),
                'privateCommitBytes': int(counters.privateUsage), 'peakPrivateCommitBytes': int(counters.peakPagefileUsage)}

    def close(self, handle):
        if not self.kernel.CloseHandle(handle): raise self.failure('CloseHandle')


class ProcessLifecycle:
    """Bind once to an owned Popen identity; query memory only at ready and pre-quit endpoints."""
    def __init__(self, process, role, launched_monotonic_ns, launched_utc, api_factory=WindowsProcessApi, clock=time.monotonic_ns):
        if role not in ('server', 'client'): raise ValueError('Unknown owned process role')
        self.process, self.role, self.launch_ns, self.launch_utc, self.clock = process, role, launched_monotonic_ns, launched_utc, clock
        self.pid = process.pid
        self.marker = 'Done (' if role == 'server' else 'CUBICLAB_CLIENT_VIEW screen=none'
        self.ready_ns, self.handle, self.api, self.bound, self.error, self.close_error = None, None, None, None, None, None
        self.endpoints, self.closed = {}, False
        self.receipt_failure = None
        try:
            if process.poll() is not None: raise OSError('Owned process exited before lifecycle binding')
            self.api = api_factory(); self.handle = self.api.open(process.pid)
            self.bound = self.api.identity(self.handle)
            if not str(self.bound.get('creationFileTime100ns', '')).isdigit() or int(self.bound['creationFileTime100ns']) <= 0:
                raise OSError('Missing/invalid process creation identity')
            if self.bound['pid'] != process.pid or process.poll() is not None:
                raise OSError('Owned process exited or identity changed during lifecycle binding')
        except Exception as error:
            self.error = str(error)
            self._close_handle()

    def observe_line(self, line):
        # No Win32 calls or memory samples in the log-reader thread or during timed scenarios.
        if not self.closed and self.ready_ns is None and self.marker in re.sub(r'\x1b\[[0-9;]*m', '', line):
            self.ready_ns = self.clock()

    @staticmethod
    def same_identity(first, second):
        return all(first[key] == second[key] for key in ('pid', 'creationFileTime100ns'))

    def capture(self, endpoint):
        if endpoint not in ('ready', 'endBeforeQuit'): raise ValueError('Unsupported lifecycle endpoint')
        if endpoint in self.endpoints: return self.endpoints[endpoint]
        started = self.clock()
        row = {'available': False, 'capturedUtc': datetime.now(timezone.utc).isoformat(),
               'captureMonotonicOffsetNanos': started - self.launch_ns}
        try:
            if self.closed: raise OSError('Lifecycle handle already closed')
            if self.error is not None: raise OSError(self.error)
            if endpoint == 'ready' and self.ready_ns is None: raise OSError('Expected world/server-ready marker was not observed')
            if self.process.poll() is not None: raise OSError('Owned process exited before endpoint; memory unavailable')
            before = self.api.identity(self.handle)
            if not self.same_identity(before, self.bound): raise OSError('Owned process handle/creation identity mismatch')
            memory = self.api.memory(self.handle)
            after = self.api.identity(self.handle)
            if not self.same_identity(after, self.bound): raise OSError('Owned process identity changed during endpoint')
            if self.process.poll() is not None: raise OSError('Owned process exited during endpoint; memory unavailable')
            row.update(memory); row.update(after); row['available'] = True
        except Exception as error:
            row['failure'] = str(error)
            row['failureType'] = type(error).__name__
        row['observerReadNanos'] = self.clock() - started
        self.endpoints[endpoint] = row
        return row

    def _close_handle(self):
        if self.handle is not None:
            handle, self.handle = self.handle, None
            try: self.api.close(handle)
            except OSError as error: self.close_error = str(error)

    def close(self):
        if not self.closed:
            self._close_handle(); self.closed = True; self.process = None

    def report(self):
        return {'schema': 'cubiclab.process-lifecycle.v1', 'pid': self.pid, 'role': self.role,
            'launchMonotonicNanos': self.launch_ns, 'launchUtc': self.launch_utc,
            'readyMarker': self.marker, 'readyObserved': self.ready_ns is not None,
            'launchToReadyNanos': None if self.ready_ns is None else self.ready_ns - self.launch_ns,
            'boundIdentity': self.bound, 'bindingAvailable': self.error is None, 'bindingFailure': self.error,
            'endpoints': dict(self.endpoints), 'observerClosed': self.closed,
            'handleClosed': self.closed and self.close_error is None, 'closeFailure': self.close_error,
            'receiptFailure': self.receipt_failure,
            'scope': 'Monotonic controller log observation from immediately before Popen. Ready/pre-quit memory endpoint attempts outside successful timed captures; OS lifetime peak through each endpoint, not a sampled curve or a sum of concurrent peaks. WorkingSet includes shared resident pages; PrivateUsage is private commit, not resident heap. Client screen=none is world/UI readiness, not full-view meshes or presentation. No GC, heap or working-set intervention.'}
