"""Host exclusion contracts, including late starts and PID reuse; no Minecraft timing claims."""
import threading
from types import SimpleNamespace
import unittest

from native_workload_guard import JavaWorkloadGuard


def row(pid=10, created='100', cpu=0):
    return dict(pid=pid, creationFileTime100ns=created, kernelCpuNanos=0, userCpuNanos=cpu, executable='java.exe')


class Inventory:
    def __init__(self): self.rows=[]; self.daemons=[]; self.error=None; self.visited=threading.Event()
    def snapshot(self):
        self.visited.set()
        if self.error: raise self.error
        return list(self.rows)
    def idle_daemon_candidates(self): return list(self.daemons)
    def identity_for(self,pid):
        return next(r for r in self.rows if r['pid']==pid)


class GuardTest(unittest.TestCase):
    def setUp(self):
        self.api=Inventory();self.now=0
    def clock(self): return self.now
    def guard(self,interval=5): return JavaWorkloadGuard(lambda:self.api,self.clock,interval)
    def test_existing_foreign_java_refuses_launch(self):
        self.api.rows=[row()];g=self.guard();g.observe()
        with self.assertRaisesRegex(RuntimeError,'foreign-java'):
            with g.launch():self.fail('must not launch')
        g.close();self.assertFalse(g.report()['passed'])
    def test_idle_preexisting_daemon_is_retained_and_cpu_checked(self):
        self.api.rows=self.api.daemons=[row()];g=self.guard();g.observe()
        self.now=5_000_000_000;self.api.rows=[row(cpu=10_000_000)]
        g.observe();self.now+=1;g.close();g.require_clean()
        self.assertEqual(g.report()['observations'][1]['java'][0]['cpuDeltaNanos'],10_000_000)
    def test_late_foreign_launch_invalidates_observation(self):
        g=self.guard();g.observe();self.api.rows=[row()];self.now+=5_000_000_000;g.observe()
        self.assertEqual(g.report()['issues'][0]['kind'],'foreign-java')
    def test_owned_process_uses_creation_identity(self):
        g=self.guard()
        with g.launch():
            self.api.rows=[row()];g.register(SimpleNamespace(pid=10,poll=lambda:None))
        g.observe();g.require_clean()
        self.api.rows=[row(created='200')];g.observe()
        self.assertEqual(g.report()['issues'][0]['kind'],'foreign-java')
    def test_daemon_pid_reuse_is_not_exempt(self):
        self.api.daemons=[row()];g=self.guard();self.api.rows=[row(created='200')];g.observe()
        self.assertFalse(g.report()['passed'])
    def test_busy_gradle_daemon_invalidates_run(self):
        self.api.rows=self.api.daemons=[row()];g=self.guard();g.observe()
        self.now=5_000_000_000;self.api.rows=[row(cpu=600_000_000)];g.observe()
        self.assertEqual(g.report()['issues'][0]['kind'],'active-gradle-daemon')
    def test_cpu_counter_regression_is_observer_failure(self):
        self.api.rows=self.api.daemons=[row(cpu=100)];g=self.guard();g.observe()
        self.now=1_000_000_000;self.api.rows=[row(cpu=99)];g.observe()
        self.assertEqual(g.report()['issues'][0]['kind'],'invalid-cpu-observation')
    def test_inventory_failure_is_not_a_clean_machine(self):
        self.api.error=PermissionError('denied');g=self.guard();g.observe()
        self.assertEqual(g.report()['issues'][0]['kind'],'observation-failed')
    def test_unavailable_platform_refuses_run(self):
        def unavailable():raise OSError('not supported')
        g=JavaWorkloadGuard(unavailable,self.clock);g.observe();g.close()
        with self.assertRaisesRegex(RuntimeError,'observer-unavailable'):g.require_clean()
    def test_registration_failure_returns_for_caller_cleanup(self):
        g=self.guard();g.register(SimpleNamespace(pid=99,poll=lambda:None))
        self.assertEqual(g.report()['issues'][0]['kind'],'owned-registration-failed')
        with self.assertRaises(RuntimeError):g.require_clean()
    def test_final_observation_detects_late_overlap_and_close_is_idempotent(self):
        g=self.guard();g.observe();self.api.rows=[row()];g.close();g.close()
        self.assertFalse(g.report()['passed']);self.assertTrue(g.report()['closed'])
        self.assertEqual(len(g.report()['observations']),2)
    def test_background_observer_checks_late_launch(self):
        g=self.guard(.01);g.start();self.api.rows=[row()];self.api.visited.clear()
        self.assertTrue(self.api.visited.wait(1));g.close()
        self.assertFalse(g.report()['passed']);self.assertFalse(g.thread.is_alive())
    def test_launch_registration_cannot_race_enumeration(self):
        g=self.guard();entered=threading.Event()
        def scan():entered.set();g.observe()
        with g.launch():
            self.api.rows=[row()];thread=threading.Thread(target=scan);thread.start()
            self.assertTrue(entered.wait(1));g.register(SimpleNamespace(pid=10,poll=lambda:None))
        thread.join(1);self.assertFalse(thread.is_alive());g.require_clean()


if __name__=='__main__':unittest.main()
