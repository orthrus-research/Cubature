import argparse
import json
from pathlib import Path
import tempfile
import struct
import unittest
from unittest.mock import patch
import cubic_lab
from lab_storage_crash import run,inspect_crash_files


class Process:
    def __init__(self,pid):self.pid=pid;self.returncode=None;self.killed=False
    def poll(self):return self.returncode
    def kill(self):self.killed=True;self.returncode=-9
    def wait(self,timeout=None):
        if self.returncode is None:self.returncode=0
        return self.returncode


class CrashControllerTest(unittest.TestCase):
    def test_independent_before_recovery_checks_acknowledged_barrier_and_corruption(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'previous.bin').write_bytes(b'old');(root/'replacement.bin').write_bytes(b'new')
            header=bytearray(16384);struct.pack_into('>I',header,((15<<8)|(7<<4)|15)*4,(32<<8)|1)
            path=root/'-2.-1.1.3dr';path.write_bytes(header+struct.pack('>I',3)+b'old')
            self.assertTrue(inspect_crash_files(root,'payloadForce')['passed']);self.assertFalse(inspect_crash_files(root,'after-barrier')['passed'])
            path.write_bytes(header+struct.pack('>I',3)+b'new');self.assertTrue(inspect_crash_files(root,'after-barrier')['passed'])
            path.write_bytes(header+struct.pack('>I',3)+b'bad');self.assertFalse(inspect_crash_files(root,'headerForce')['passed'])
            path.write_bytes(header+struct.pack('>I',3000)+b'x')
            with self.assertRaises(ValueError):inspect_crash_files(root,'headerForce')

    def exercise(self,root,wrong_boundary=False):
        server=root/'server';server.mkdir();target=root/'lab';target.mkdir()
        fixture=server/'cubiclab-crashes/fixture';processes=[]
        manifest={'server':str(server),'labProperties':{'cubiclab.storageDiagnostics':'true'}}
        args=argparse.Namespace(crash_id='fixture',crash_boundary='payloadForce')
        def spawn(*a,**k):
            p=Process(120+len(processes));processes.append(p);return p
        def send(process,command):
            if command.startswith('cubiclab storage-crash'):
                fixture.mkdir(parents=True);(fixture/'boundary-ready.json').write_text(json.dumps({'boundary':'headerForce' if wrong_boundary else 'payloadForce'}))
            elif command.startswith('cubiclab storage-recover'):(fixture/'recovery.json').write_text('{"passed":true}')
        def endpoint(evidence,process,name):
            # Keep the real lifecycle API's supported endpoints; do not hide an integration mismatch.
            self.assertIn(name,('ready','endBeforeQuit'))
        patches={'prime':lambda a:None,'load_lab':lambda a:(target,manifest),'record_inputs':lambda m:{},
                 'retain_prelaunch_inputs':lambda *a:{},'argv':lambda *a:['owned-java'],
                 'spawn':spawn,'write_process_receipt':lambda *a:None,'await_line':lambda *a:'CUBICLAB_CRASH_RECOVERED id=fixture PASS',
                 'send':send,'capture_process_endpoint':endpoint,'retain_process_lifecycles':lambda *a:None}
        from contextlib import ExitStack
        with ExitStack() as stack:
            stack.enter_context(patch('lab_storage_crash.inspect_crash_files',return_value={'passed':True}))
            for name,value in patches.items():stack.enter_context(patch.object(cubic_lab,name,value))
            if wrong_boundary:
                with self.assertRaisesRegex(RuntimeError,'Wrong storage boundary'):run(args)
            else:self.assertEqual(0,run(args))
        return processes,json.loads((target/'runs/crash-fixture/result.json').read_text())

    def test_only_owned_crash_process_is_killed_and_recovery_must_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            processes,result=self.exercise(Path(temp));self.assertTrue(result['passed']);self.assertEqual([True,False],[p.killed for p in processes])
            self.assertEqual(120,result['phases'][0]['controllerTerminatedPid'])

    def test_wrong_boundary_prevents_intentional_termination_and_retains_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            processes,result=self.exercise(Path(temp),True);self.assertFalse(result['passed']);self.assertFalse(processes[0].killed)
            self.assertIn('Wrong storage boundary',result['failure'])

if __name__=='__main__':unittest.main()
