"""Control-plane regressions; these do not simulate Minecraft performance."""
import queue
import re
import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile
import cubic_lab as lab
import lab_files
import lab_process
import lab_profiles as profiles


NMT_FIXTURE = """Native Memory Tracking:

Total: reserved=1200MB, committed=80MB
       malloc: 3MB #100, peak=3MB #100
       mmap:   reserved=1197MB, committed=77MB

-                 Java Heap (reserved=1024MB, committed=64MB)
"""
SYSTEM_MAP_FIXTURE = """from               to                        vsize prot  state      offset vminfo/file
===========================================================================================
0x00000000c0000000-0x00000000c4000000     67108864 rw-   c-pvt           0 JAVAHEAP 
0x00000000c4000000-0x0000000100000000   1006632960 ---   r-pvt   0x4000000 JAVAHEAP 
0x0000000013000000-0x0000000013a00000     10485760 rw-   c-pvt           0 CODE 
0x0000020000000000-0x0000020000600000      6291456 rw-W  c-pvt           0 
0x00007ff75a0f0000-0x00007ff75a0f1000         4096 r--   c-img           0 C:\jdk\bin\java.exe
"""
VMMAP_FIXTURE = """"Process:","java.exe"

"Type","Size","Committed","Private","Total WS","Private WS","Shareable WS","Shared WS","Locked WS","Blocks","Largest",

"Total","2,000,000","94,208","88,064","90,000","80,000","","","","10",""
"Heap","6,000","5,120","5,120","5,000","5,000","","","","2","1,024"
"Image","4","4","","4","","4","4","","1","4"
"Mapped File","1,024","1,024","","1,024","","","","","1","1,024"
"Private Data","1,100,000","81,920","81,920","80,000","80,000","","","","5","1,048,576"
"Stack","1,024","1,024","1,024","1,000","1,000","","","","1","1,024"

"Address","Type","Size","Committed","Private","Total WS","Private WS","Shareable WS","Shared WS","Locked WS","Blocks","Protection","Details",
"""

class LabControlTest(unittest.TestCase):
    def test_foreign_workload_preflight_retains_failure_without_launching(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);guard=mock.Mock()
            guard.report.return_value={'passed':False,'issues':[{'kind':'foreign-java'}]}
            guard.require_clean.side_effect=RuntimeError('foreign-java')
            prime=mock.Mock()
            with mock.patch.object(lab,'selected_scenarios',return_value=['route']), mock.patch.object(lab,'JavaWorkloadGuard',return_value=guard), mock.patch.object(lab,'spawn') as spawn:
                with self.assertRaisesRegex(RuntimeError,'foreign-java'):lab.run(argparse.Namespace(phase='write'),load=lambda a:(root,{}),prime_lab=prime)
            prime.assert_not_called();spawn.assert_not_called()
            receipt=list((root/'runs').glob('java-workload-preflight-*.json'))
            self.assertEqual(len(receipt),1);self.assertFalse(json.loads(receipt[0].read_text())['passed'])

    def test_wait_checks_guard_before_accepting_success_marker(self):
        messages=queue.Queue();messages.put('SUCCESS')
        guard=mock.Mock();guard.require_clean.side_effect=RuntimeError('foreign-java')
        with self.assertRaisesRegex(RuntimeError,'foreign-java'):lab_process.await_line(messages,'SUCCESS',[],1,workload_guard=guard)

    def test_guard_close_failure_does_not_skip_owned_process_cleanup(self):
        class Process:
            pid=777
            def __init__(self):self.alive=True;self.stdin=mock.Mock()
            def poll(self):return None if self.alive else 0
            def wait(self,timeout=None):self.alive=False;return 0
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);server=root/'server';server.mkdir();process=Process();guard=mock.Mock()
            guard.report.side_effect=[{'passed':True},{'passed':False,'issues':[{'kind':'observer-close-failed'}]}]
            guard.close.side_effect=RuntimeError('observer close fixture')
            manifest={'server':str(server),'client':str(root/'client')}
            patches=[mock.patch.object(lab,'selected_scenarios',return_value=['route']),
                mock.patch.object(lab,'JavaWorkloadGuard',return_value=guard),mock.patch.object(lab,'record_inputs',return_value={}),
                mock.patch.object(lab,'retain_prelaunch_inputs',return_value={}),mock.patch.object(lab,'argv',return_value=['java']),
                mock.patch.object(lab,'spawn',return_value=process),mock.patch.object(lab,'await_line',side_effect=RuntimeError('foreign-java'))]
            from contextlib import ExitStack
            with ExitStack() as stack:
                for patch in patches:stack.enter_context(patch)
                with self.assertRaisesRegex(RuntimeError,'foreign-java'):lab.run(argparse.Namespace(phase='write',jfr=False),load=lambda a:(root,manifest))
            self.assertFalse(process.alive)
            saved=json.loads((root/'runs/write/result.json').read_text())
            self.assertFalse(saved['passed']);self.assertFalse(saved['hostWorkloadGuard']['passed'])
            self.assertIn('stop\n',[call.args[0] for call in process.stdin.write.call_args_list])

    def test_run_and_survey_use_the_preparation_their_caller_passes(self):
        class Process:
            pid=778
            def __init__(self):self.alive=True;self.stdin=mock.Mock()
            def poll(self):return None if self.alive else 0
            def wait(self,timeout=None):self.alive=False;return 0
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);server=root/'server';client=root/'client';process=Process();guard=mock.Mock()
            guard.report.return_value={'passed':True};run_id='00000000-0000-4000-8000-000000000000'
            for side,directory in [('server',server),('client',client)]:
                (directory/'cubiclab-results'/run_id).mkdir(parents=True)
                (directory/'cubiclab-results'/run_id/(side+'.json')).write_text(json.dumps({'side':side}))
            manifest={'server':str(server),'client':str(client)};calls=[]
            def load(args):calls.append('load');return root,manifest
            def select(args,value):calls.append('select');self.assertIs(value,manifest);return ['route']
            def prime_lab(args):calls.append('prime')
            def record(value):calls.append('record');return {'files':{}}
            def retain(value,hashes,evidence):calls.append('retain');self.assertEqual(hashes,{'files':{}});return {'schema':1}
            def validate(value,server_data,client_data):calls.append('validate');raise RuntimeError('validated '+server_data['side']+' '+client_data['side'])
            lines=iter(['Done (','joined','screen=none','CUBICLAB_STARTED scenario=route runId='+run_id,
                        'CUBICLAB_SERVER_RESULT runId='+run_id+' PASS','CUBICLAB_CLIENT_RESULT runId='+run_id])
            unused=mock.Mock(side_effect=AssertionError('module default used'))
            patches=[mock.patch.object(lab,name,unused) for name in ['selected_scenarios','record_inputs','retain_prelaunch_inputs']]
            patches+=[mock.patch.object(lab.lab_workload,'validate_capture',unused),mock.patch.object(lab,'JavaWorkloadGuard',return_value=guard),
                mock.patch.object(lab,'argv',return_value=['java']),mock.patch.object(lab,'spawn',return_value=process),
                mock.patch.object(lab,'await_line',side_effect=lambda *a,**k:next(lines)),mock.patch.object(lab,'validate_view_distance_capture'),
                mock.patch.object(lab,'validate_jvm_capture'),mock.patch('lab_storage_evidence.validate_complete_ticks')]
            from contextlib import ExitStack
            with ExitStack() as stack:
                for patch in patches:stack.enter_context(patch)
                with self.assertRaisesRegex(RuntimeError,'validated server client'):
                    lab.run(argparse.Namespace(phase='write',jfr=False,scenario_start_timeout=10,client_report_timeout=30),
                            load=load,prime_lab=prime_lab,select=select,record=record,retain=retain,validate=validate)
                self.assertEqual(calls,['load','select','prime','load','record','retain','validate']);calls.clear()
                def retain_survey(value,hashes,evidence):calls.append('retain');raise RuntimeError('retained')
                def record_survey(value):calls.append('record');return dict.fromkeys(lab.PREGENERATION_INPUTS,{})
                with self.assertRaisesRegex(RuntimeError,'retained'):
                    lab.survey(argparse.Namespace(native_operation='pregenerate',survey_id='fixture',jfr=False),
                               load=load,prime_lab=prime_lab,record=record_survey,retain=retain_survey)
                self.assertEqual(calls,['prime','load','record','retain']);calls.clear()
                # A record without the inputs pregeneration compares afterwards stops before anything launches.
                with self.assertRaisesRegex(ValueError,'nothing was launched'):
                    lab.survey(argparse.Namespace(native_operation='pregenerate',survey_id='fixture',jfr=False),
                               load=load,prime_lab=prime_lab,record=record,retain=retain_survey)
                self.assertEqual(calls,['prime','load','record'])
            self.assertFalse(process.alive)

    def test_delivery_modes_match_cubatures_enum(self):
        source = (Path(__file__).resolve().parents[1] / 'src/main/java/io/github/opencubicchunks/cubicchunks/core/CubicChunksConfig.java').read_text(encoding='utf-8')
        constants = re.search(r'public enum DeliveryMode \{\s*([A-Z_,\s]+);', source)[1]
        self.assertEqual(tuple(name.strip() for name in constants.split(',')), profiles.DELIVERY_MODES)

    def test_reviewed_jvm_experiments_keep_equal_max_heap_and_verify_effective_collectors(self):
        for variant in profiles.JVM_VARIANTS:
            profile = profiles.jvm_profile(variant)
            with tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary); profiles.configure_jvm(root, profile)
                manifest={'server':str(root),'jvmProfile':profile}
                profiles.validate_jvm_inputs(manifest)
                capture={'runtime':{'maxHeapBytes':profile['maxHeapMiB']*1024**2,'garbageCollectors':[profile['collector']+' Young Generation']}}
                profiles.validate_jvm_capture(capture,capture,manifest)
                self.assertEqual(profile['initialHeapMiB'],1024 if '-' not in variant else profile['maxHeapMiB'])
                self.assertIn(f"-Xmx{profile['maxHeapMiB'] // 1024}G", profile['arguments'])
                self.assertEqual(profile['maxHeapMiB'], 12288 if variant == 'g1-ship12' else 8192)
                self.assertEqual('-XX:+AlwaysPreTouch' in profile['arguments'],variant.endswith('-pretouch'))

    def test_cube_experiment_inputs_are_fresh_and_detect_configuration_drift(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); (root/'config').mkdir()
            path=root/'config/cubicchunks.cfg'; path.write_text('general {\nI:defaultMinHeight=-128\n}\n')
            args=argparse.Namespace(delivery_mode='MAINTENANCE',cube_send_ms=8,cube_send_bytes=4194304,cube_packet_bytes=262144,cube_packet_cubes=16)
            values=profiles.configure_cube_experiments(root,args)
            self.assertEqual(values['maxCubePacketCubes'], '16')
            self.assertEqual(values['deliveryMode'], 'MAINTENANCE')
            profiles.validate_cube_experiments({'server':str(root),'cubeExperiments':values})
            original=path.read_text()
            for before,after in (('maxCubeSendTimeMillis=8','maxCubeSendTimeMillis=4'),('deliveryMode=MAINTENANCE','deliveryMode=PREFETCH')):
                path.write_text(original.replace(before,after))
                with self.assertRaisesRegex(RuntimeError,'experiment changed'): profiles.validate_cube_experiments({'server':str(root),'cubeExperiments':values})
            args.delivery_mode='prefetch'
            with self.assertRaisesRegex(ValueError,'Delivery mode'): profiles.configure_cube_experiments(root,args)

    def test_installer_argfile_cannot_override_heap_and_collector(self):
        with tempfile.TemporaryDirectory() as temporary:
            server = Path(temporary)
            path = server / 'user_jvm_args.txt'
            path.write_text('-Xmx6G\n-XX:+UseG1GC\n')
            profiles.configure_jvm(server)
            manifest = {'server': str(server), 'jvmProfile': profiles.jvm_profile()}
            profiles.validate_jvm_inputs(manifest)
            path.write_text(path.read_text() + '-Xmx4G\n')
            with self.assertRaisesRegex(RuntimeError, 'arguments changed'):
                profiles.validate_jvm_inputs(manifest)
        with self.assertRaisesRegex(RuntimeError, 'fresh instance'):
            profiles.validate_jvm_inputs({})

    def test_native_memory_profile_records_each_process_summary_after_a_scenario(self):
        self.assertEqual([v for v in profiles.JVM_VARIANTS if profiles.tracks_native_memory({'jvmProfile': profiles.jvm_profile(v)})],
                         ['zgc-fixed-nmt', 'g1-ship8-nmt'])
        outputs = {'VM.native_memory': NMT_FIXTURE, 'System.map': SYSTEM_MAP_FIXTURE}
        for vmmap in (None, 'C:/tools/vmmap.exe'):
            with tempfile.TemporaryDirectory() as temporary:
                evidence = Path(temporary)
                calls = []
                def run(command, stdout, **_):
                    calls.append(command)
                    if command[0] == vmmap: Path(command[-1]).write_text(VMMAP_FIXTURE, encoding='utf-8')
                    else: stdout.write(outputs[command[2]])
                processes = {'server': mock.Mock(pid=11), 'client': mock.Mock(pid=22)}
                with mock.patch.object(lab.subprocess, 'run', side_effect=run), mock.patch.object(lab.shutil, 'which', return_value=vmmap):
                    lab.native_memory_summaries({'java': str(evidence / 'java.exe')}, evidence, 'full-view', processes)
                jvm = [c[1:] for c in calls if c[0] != vmmap]
                self.assertEqual(jvm, [['11', 'VM.native_memory', 'summary', 'scale=MB'], ['11', 'System.map'],
                                       ['22', 'VM.native_memory', 'summary', 'scale=MB'], ['22', 'System.map']])
                self.assertEqual(len(calls) - len(jvm), 0 if vmmap is None else 2)
                accounts = sorted(evidence.glob('*-memory-account.json'))
                self.assertEqual(len(accounts), 0 if vmmap is None else 2)
                if vmmap:
                    private = json.loads(accounts[0].read_text())['privateMiB']
                    self.assertEqual(private['Java heap'], 64)
                    self.assertEqual(private['JVM malloc (NMT; direct buffers, internal)'], 3)
                    self.assertEqual(private['Native malloc beyond the JVM (C heaps: libraries, drivers)'], 2)
                    self.assertEqual(private['Native VirtualAlloc the JVM did not reserve (drivers, libraries)'], 6)
                    self.assertEqual(private['Accounted'], private['VMMap private total'])

    def test_native_capture_requires_effective_heap_and_collector_on_both_sides(self):
        # The default profile is ZGC on a fixed 8 GiB heap.
        self.assertEqual(profiles.jvm_profile()['collector'], 'ZGC')
        self.assertEqual(profiles.jvm_profile()['arguments'], ['-Xms8192M', '-Xmx8G', '-XX:+UseZGC'])
        server = {'runtime': {'maxHeapBytes': 8 * 1024**3, 'garbageCollectors': ['ZGC Minor Cycles', 'ZGC Major Cycles']}}
        client = json.loads(json.dumps(server))
        profiles.validate_jvm_capture(server, client)
        for record in [server, client]:
            record['runtime']['maxHeapBytes'] = 6 * 1024**3
            with self.assertRaisesRegex(RuntimeError, 'heap'): profiles.validate_jvm_capture(server, client)
            record['runtime']['maxHeapBytes'] = 8 * 1024**3
            record['runtime']['garbageCollectors'] = ['G1 Young Generation']
            with self.assertRaisesRegex(RuntimeError, 'collector'): profiles.validate_jvm_capture(server, client)
            record['runtime']['garbageCollectors'] = ['ZGC Minor Cycles', 'ZGC Major Cycles']

    def test_preparation_sets_all_test_distances_and_preserves_world_height(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); server = root / 'server'; client = root / 'client'
            for directory in [server, client]: (directory / 'config').mkdir(parents=True)
            (server / 'server.properties').write_text('level-name=fresh-test\nview-distance=3\n', encoding='utf-8')
            (server / 'config/cubicchunks.cfg').write_text('general {\n    I:defaultMinHeight=-128\n    I:defaultMaxHeight=384\n}\n', encoding='utf-8')
            profiles.configure_view_distance(server)
            (client / 'config/cubicchunks.cfg').write_bytes((server / 'config/cubicchunks.cfg').read_bytes())
            (client / 'options.txt').write_text(profiles.client_options(), encoding='utf-8')
            manifest = {'server': str(server), 'client': str(client), 'viewDistance': profiles.view_distance_profile()}
            profiles.validate_view_distance_inputs(manifest)
            self.assertIn('I:defaultMinHeight=-128', (server / 'config/cubicchunks.cfg').read_text())
            self.assertIn('I:defaultMaxHeight=384', (server / 'config/cubicchunks.cfg').read_text())
            # The manifest cannot conceal a drifted client preference.
            (client / 'options.txt').write_text('renderDistance:3\n', encoding='utf-8')
            with self.assertRaises(RuntimeError): profiles.validate_view_distance_inputs(manifest)

    def test_old_distance_lab_cannot_be_launched_as_current_profile(self):
        with self.assertRaisesRegex(RuntimeError, 'prepare a new instance'):
            profiles.validate_view_distance_inputs({'viewDistance': {'horizontal': 3, 'vertical': 3, 'clientRender': 3}})

    def _prepared(self, root):
        server = root / 'server'; client = root / 'client'
        for directory in [server, client]: (directory / 'config').mkdir(parents=True)
        (server / 'server.properties').write_text('level-name=fresh-test\nview-distance=12\n', encoding='utf-8')
        (server / 'config/cubicchunks.cfg').write_text('general {\n    I:verticalCubeLoadDistance=12\n}\n', encoding='utf-8')
        (client / 'config/cubicchunks.cfg').write_text('general {\n    I:verticalCubeLoadDistance=12\n}\n', encoding='utf-8')
        (client / 'options.txt').write_text(profiles.client_options(), encoding='utf-8')
        return {'server': str(server), 'client': str(client), 'viewDistance': profiles.view_distance_profile()}

    def test_a_run_may_raise_any_distance_and_it_is_recorded(self):
        with tempfile.TemporaryDirectory() as temporary:
            manifest = self._prepared(Path(temporary))
            raised = profiles.view_distance_profile(client_render=20)
            self.assertTrue(profiles.apply_view_distance(manifest, raised))
            profiles.validate_view_distance_inputs(manifest)
            self.assertEqual(manifest['viewDistance'], {'horizontal': 12, 'vertical': 12, 'clientRender': 20})
            self.assertEqual(manifest['viewDistanceLabel'], 'raised: clientRender 20 (baseline 12)')
            options = (Path(manifest['client']) / 'options.txt').read_text()
            self.assertIn('renderDistance:20\nmaxFps:120', options)
            # The server and both vertical distances raise the same way, and the baseline comes back.
            profiles.apply_view_distance(manifest, profiles.view_distance_profile(16, 14, 24))
            profiles.validate_view_distance_inputs(manifest)
            self.assertIn('view-distance=16', (Path(manifest['server']) / 'server.properties').read_text())
            for side in ['server', 'client']:
                self.assertIn('I:verticalCubeLoadDistance=14', (Path(manifest[side]) / 'config/cubicchunks.cfg').read_text())
            self.assertTrue(profiles.apply_view_distance(manifest, profiles.view_distance_profile()))
            profiles.validate_view_distance_inputs(manifest)
            self.assertEqual(manifest['viewDistanceLabel'], 'baseline')
            self.assertFalse(profiles.apply_view_distance(manifest, profiles.view_distance_profile()))

    def test_below_the_baseline_or_above_the_game_is_refused(self):
        for bad in [dict(horizontal=11), dict(vertical=3), dict(client_render=8), dict(horizontal=33),
                    dict(client_render=65), dict(horizontal=12.5)]:
            with self.assertRaises(RuntimeError, msg=str(bad)):
                profiles.view_distance_profile(**bad)
        self.assertEqual(profiles.view_distance_profile(32, 32, 64)['clientRender'], 64)
        with tempfile.TemporaryDirectory() as temporary:
            manifest = self._prepared(Path(temporary))
            with self.assertRaisesRegex(RuntimeError, 'below the baseline'):
                profiles.apply_view_distance(manifest, {'horizontal': 8, 'vertical': 12, 'clientRender': 12})
            self.assertEqual(manifest['viewDistance'], profiles.view_distance_profile())

    def test_a_raised_lab_still_refuses_files_that_drifted_from_its_record(self):
        with tempfile.TemporaryDirectory() as temporary:
            manifest = self._prepared(Path(temporary))
            profiles.apply_view_distance(manifest, profiles.view_distance_profile(client_render=20))
            (Path(manifest['client']) / 'options.txt').write_text('renderDistance:12\n', encoding='utf-8')
            with self.assertRaisesRegex(RuntimeError, 'changed or is ambiguous'):
                profiles.validate_view_distance_inputs(manifest)

    def test_a_raised_capture_is_checked_against_its_own_profile(self):
        manifest = {'viewDistance': profiles.view_distance_profile(16, 14, 20)}
        server = {'horizontalViewDistance': 16, 'verticalViewDistance': 14}
        client = {'renderDistance': 20, 'verticalViewDistance': 14}
        profiles.validate_view_distance_capture(manifest, server, client)
        client['renderDistance'] = 12
        with self.assertRaises(RuntimeError): profiles.validate_view_distance_capture(manifest, server, client)

    def test_a_reduced_profile_is_chosen_at_preparation_and_held_exactly(self):
        with tempfile.TemporaryDirectory() as temporary:
            manifest = self._prepared(Path(temporary))
            profiles.choose_view_profile(manifest, 'near-8')
            self.assertEqual(manifest['viewProfile'], 'near-8')
            self.assertEqual(manifest['viewDistance'], {'horizontal': 8, 'vertical': 8, 'clientRender': 8})
            self.assertTrue(manifest['viewDistanceLabel'].startswith('reduced near-8: '))
            profiles.validate_view_distance_inputs(manifest)
            self.assertIn('view-distance=8', (Path(manifest['server']) / 'server.properties').read_text())
            self.assertIn('renderDistance:8\n', (Path(manifest['client']) / 'options.txt').read_text())
            # Its own values set again change nothing; anything else is refused, the baseline included.
            self.assertFalse(profiles.apply_view_distance(manifest, profiles.reduced_view_profile('near-8')))
            for asked in [profiles.view_distance_profile(), profiles.view_distance_profile(client_render=20)]:
                with self.assertRaisesRegex(RuntimeError, 'reduced view profile near-8'):
                    profiles.apply_view_distance(manifest, asked)
            self.assertEqual(manifest['viewDistance'], {'horizontal': 8, 'vertical': 8, 'clientRender': 8})

    def test_a_reduced_instance_refuses_drift_and_other_values(self):
        with tempfile.TemporaryDirectory() as temporary:
            manifest = self._prepared(Path(temporary))
            profiles.choose_view_profile(manifest, 'near-8')
            (Path(manifest['client']) / 'options.txt').write_text('renderDistance:12\n', encoding='utf-8')
            with self.assertRaisesRegex(RuntimeError, 'changed or is ambiguous'):
                profiles.validate_view_distance_inputs(manifest)
            manifest['viewDistance'] = {'horizontal': 8, 'vertical': 8, 'clientRender': 9}
            with self.assertRaisesRegex(RuntimeError, 'exactly'):
                profiles.validate_view_distance_inputs(manifest)
        with self.assertRaisesRegex(RuntimeError, 'No reduced view profile'):
            profiles.reduced_view_profile('near-3')

    def test_an_instance_without_a_profile_reads_as_baseline(self):
        self.assertEqual(profiles.view_profile_name({}), 'baseline')
        with tempfile.TemporaryDirectory() as temporary:
            manifest = self._prepared(Path(temporary))
            self.assertNotIn('viewProfile', manifest)
            profiles.validate_view_distance_inputs(manifest)
            profiles.choose_view_profile(manifest, 'baseline')
            self.assertNotIn('viewProfile', manifest)

    def test_runs_of_different_profiles_are_not_compared(self):
        baseline = {'viewDistance': profiles.view_distance_profile()}
        raised = {'viewDistance': profiles.view_distance_profile(client_render=20)}
        reduced = {'viewProfile': 'near-8', 'viewDistance': profiles.reduced_view_profile('near-8')}
        profiles.require_same_view_profile(baseline, dict(baseline))
        profiles.require_same_view_profile(reduced, dict(reduced))
        for pair in [(baseline, reduced), (baseline, raised), (raised, reduced)]:
            with self.assertRaisesRegex(RuntimeError, 'not compared'):
                profiles.require_same_view_profile(*pair)

    def test_a_reduced_capture_is_checked_against_its_profile(self):
        manifest = {'viewProfile': 'near-8', 'viewDistance': profiles.reduced_view_profile('near-8')}
        server = {'horizontalViewDistance': 8, 'verticalViewDistance': 8}
        client = {'renderDistance': 8, 'verticalViewDistance': 8}
        profiles.validate_view_distance_capture(manifest, server, client)
        server['horizontalViewDistance'] = 12
        with self.assertRaises(RuntimeError): profiles.validate_view_distance_capture(manifest, server, client)

    def test_duplicate_view_settings_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'server.properties').write_text('view-distance=12\nview-distance=3\n', encoding='utf-8')
            with self.assertRaisesRegex(RuntimeError, 'ambiguous'):
                profiles.validate_view_distance_inputs({'server': str(root), 'client': str(root), 'viewDistance': profiles.view_distance_profile()})

    def test_native_capture_rejects_effective_distance_fallback(self):
        manifest = {'viewDistance': profiles.view_distance_profile()}
        server = {'horizontalViewDistance': 12, 'verticalViewDistance': 12}
        client = {'renderDistance': 12, 'verticalViewDistance': 12}
        profiles.validate_view_distance_capture(manifest, server, client)
        for record, field in [(server, 'horizontalViewDistance'), (server, 'verticalViewDistance'),
                              (client, 'renderDistance'), (client, 'verticalViewDistance')]:
            record[field] = 3
            with self.assertRaises(RuntimeError): profiles.validate_view_distance_capture(manifest, server, client)
            record[field] = 12

    def test_color_coded_join_is_detected(self):
        messages = queue.Queue(); messages.put('\x1b[0;33;1mCubicLab\x1b[m\x1b[0;33;1m joined the game\x1b[m')
        self.assertIn('CubicLab joined the game', lab_process.await_line(messages, 'CubicLab joined the game', [], 1))
if __name__ == '__main__': unittest.main()
