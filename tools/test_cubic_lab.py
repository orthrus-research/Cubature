"""Control-plane regressions; these do not simulate Minecraft performance."""
import queue
import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile
import cubic_lab as lab
import lab_files
import lab_profiles as profiles

class LabControlTest(unittest.TestCase):
    def test_foreign_workload_preflight_retains_failure_without_launching(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);guard=mock.Mock()
            guard.report.return_value={'passed':False,'issues':[{'kind':'foreign-java'}]}
            guard.require_clean.side_effect=RuntimeError('foreign-java')
            with mock.patch.object(lab,'load_lab',return_value=(root,{})), mock.patch.object(lab,'selected_scenarios',return_value=['route']), mock.patch.object(lab,'JavaWorkloadGuard',return_value=guard), mock.patch.object(lab,'prime') as prime, mock.patch.object(lab,'spawn') as spawn:
                with self.assertRaisesRegex(RuntimeError,'foreign-java'):lab.run(argparse.Namespace(phase='write'))
            prime.assert_not_called();spawn.assert_not_called()
            receipt=list((root/'runs').glob('java-workload-preflight-*.json'))
            self.assertEqual(len(receipt),1);self.assertFalse(json.loads(receipt[0].read_text())['passed'])

    def test_wait_checks_guard_before_accepting_success_marker(self):
        messages=queue.Queue();messages.put('SUCCESS')
        guard=mock.Mock();guard.require_clean.side_effect=RuntimeError('foreign-java')
        with self.assertRaisesRegex(RuntimeError,'foreign-java'):lab.await_line(messages,'SUCCESS',[],1,workload_guard=guard)

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
            patches=[mock.patch.object(lab,'load_lab',return_value=(root,manifest)),mock.patch.object(lab,'selected_scenarios',return_value=['route']),
                mock.patch.object(lab,'JavaWorkloadGuard',return_value=guard),mock.patch.object(lab,'prime'),mock.patch.object(lab,'record_inputs',return_value={}),
                mock.patch.object(lab,'retain_prelaunch_inputs',return_value={}),mock.patch.object(lab,'argv',return_value=['java']),
                mock.patch.object(lab,'spawn',return_value=process),mock.patch.object(lab,'await_line',side_effect=RuntimeError('foreign-java'))]
            from contextlib import ExitStack
            with ExitStack() as stack:
                for patch in patches:stack.enter_context(patch)
                with self.assertRaisesRegex(RuntimeError,'foreign-java'):lab.run(argparse.Namespace(phase='write',jfr=False))
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
            patches=[mock.patch.object(lab,name,unused) for name in ['load_lab','selected_scenarios','prime','record_inputs','retain_prelaunch_inputs']]
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
                with self.assertRaisesRegex(RuntimeError,'retained'):
                    lab.survey(argparse.Namespace(native_operation='pregenerate',survey_id='fixture',jfr=False),
                               load=load,prime_lab=prime_lab,record=record,retain=retain_survey)
                self.assertEqual(calls,['prime','load','record','retain'])
            self.assertFalse(process.alive)

    def test_measurement_lease_rejects_overlap_and_releases_after_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            with self.assertRaisesRegex(ValueError,'fixture'):
                with lab.native_measurement_lease(root,'first'):
                    with self.assertRaisesRegex(RuntimeError,'Another native lab'):
                        with lab.native_measurement_lease(root,'overlap'): self.fail('overlapping lease')
                    raise ValueError('fixture failure')
            with lab.native_measurement_lease(root,'second'): pass
            value=json.loads((root/'.local/cubic-lab/native-measurement.lock').read_bytes()[1:])
            self.assertEqual(value['name'],'second')

    def test_reviewed_jvm_experiments_keep_equal_max_heap_and_verify_effective_collectors(self):
        for variant in profiles.JVM_VARIANTS:
            profile = profiles.jvm_profile(variant)
            with tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary); profiles.configure_jvm(root, profile)
                manifest={'server':str(root),'jvmProfile':profile}
                profiles.validate_jvm_inputs(manifest)
                capture={'runtime':{'maxHeapBytes':8*1024**3,'garbageCollectors':[profile['collector']+' Young Generation']}}
                profiles.validate_jvm_capture(capture,capture,manifest)
                self.assertEqual(profile['initialHeapMiB'],1024 if '-' not in variant else 8192)
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

    def test_native_capture_requires_effective_heap_and_collector_on_both_sides(self):
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

    def test_old_java_lab_is_not_java26_qualification(self):
        with self.assertRaisesRegex(RuntimeError, 'newly prepared'):
            lab.validate_java26_inputs({'java': 'historical-java25.exe'})

    def test_changed_java26_critical_input_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            files = [Path(temporary) / name for name in ('java.exe', 'release', 'modules', 'jvm.dll')]
            for p in files: p.write_bytes(p.name.encode())
            manifest = {'java': str(files[0]), 'javaRuntime': {'major': 26, 'criticalArtifacts': [
                {'path': str(p.resolve()), 'sha256': lab_files.sha(p)} for p in files]}}
            lab.validate_java26_inputs(manifest)
            files[3].write_bytes(b'changed JVM')
            with self.assertRaisesRegex(RuntimeError, 'runtime changed'):
                lab.validate_java26_inputs(manifest)

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

    def test_changed_prepared_runtime_is_rejected_before_launch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); directory = root / '.local/cubic-lab/check'
            directory.mkdir(parents=True)
            artifact = root / 'loader.jar'; artifact.write_bytes(b'reviewed loader bytes')
            manifest = {'runtimeArtifacts': {'client': {'path': str(artifact), 'sha256': lab_files.sha(artifact)}}}
            (directory / 'lab.json').write_text(json.dumps(manifest), encoding='utf-8')
            args = argparse.Namespace(riven_workspace=root, name='check')
            self.assertEqual(lab.load_lab(args)[1], manifest)
            artifact.write_bytes(b'changed loader bytes')
            with self.assertRaises(RuntimeError): lab.load_lab(args)

    def test_same_tag_asset_replacement_requires_review_before_installation(self):
        hashes = {'installer': 'a' * 64, 'universal': 'b' * 64, 'prism': 'c' * 64}
        release = {'assets': [
            {'name': 'cleanroom-0.7.0-alpha-installer.jar', 'digest': 'sha256:' + hashes['installer']},
            {'name': 'cleanroom-0.7.0-alpha-universal.jar', 'digest': 'sha256:' + hashes['universal']},
            {'name': 'cleanroom-0.7.0-alpha.zip', 'digest': 'sha256:' + hashes['prism']} ]}
        self.assertEqual(set(lab.runtime_assets(release, '0.7.0-alpha', hashes)), set(hashes))
        release['assets'][0]['digest'] = 'sha256:' + 'd' * 64
        with self.assertRaises(RuntimeError): lab.runtime_assets(release, '0.7.0-alpha', hashes)
        with self.assertRaises(RuntimeError): lab.runtime_assets(release, '0.7.0', hashes)

    def test_reviewed_07_versions_keep_their_compatibility_bridge(self):
        self.assertTrue(lab.needs_070_compat('0.7.0'))
        self.assertTrue(lab.needs_070_compat('0.7.0-alpha'))
        self.assertTrue(lab.needs_070_compat('0.7.1-alpha'))
        self.assertTrue(lab.needs_070_compat('0.7.2-alpha'))
        self.assertTrue(lab.needs_070_compat('0.7.4-alpha'))
        self.assertTrue(lab.needs_070_compat('0.7.5-alpha'))
        self.assertTrue(lab.needs_070_compat('0.7.6-alpha'))
        self.assertTrue(lab.needs_070_compat('0.7.7-alpha'))
        self.assertTrue(lab.needs_070_compat('0.7.8-alpha'))
        self.assertTrue(lab.needs_070_compat('0.7.9-alpha'))
        self.assertFalse(lab.needs_070_compat('0.6.13-alpha'))
        self.assertFalse(lab.needs_070_compat('0.8.0'))

    def test_color_coded_join_is_detected(self):
        messages = queue.Queue(); messages.put('\x1b[0;33;1mCubicLab\x1b[m\x1b[0;33;1m joined the game\x1b[m')
        self.assertIn('CubicLab joined the game', lab.await_line(messages, 'CubicLab joined the game', [], 1))
    def test_artifact_and_native_entries_merge_without_losing_java_classes(self):
        components = [{'libraries': [
            {'name': 'org.lwjgl:lwjgl:3.4.3', 'downloads': {'artifact': {'sha1': 'abc'}}},
            {'name': 'org.lwjgl:lwjgl:3.4.3', 'downloads': {'classifiers': {'natives-windows': {'sha1': 'def'}}},
             'natives': {'windows': 'natives-windows'}}]}]
        result = lab.merged_libraries(components, 'windows', 'x86_64')
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]['downloads']['artifact']['sha1'], 'abc')
        self.assertEqual(result[0]['downloads']['classifiers']['natives-windows']['sha1'], 'def')
    def test_loader_library_overrides_base_version(self):
        components = [{'libraries': [{'name': 'a:b:1', 'downloads': {'artifact': {'sha1': 'old'}}}]},
                      {'libraries': [{'name': 'a:b:2', 'downloads': {'artifact': {'sha1': 'new'}}}]}]
        self.assertEqual(lab.merged_libraries(components, 'windows', 'x86_64')[0]['name'], 'a:b:2')
    def test_windows_x64_excludes_other_native_architectures(self):
        for suffix in ['natives-windows-arm64', 'natives-windows-x86']:
            self.assertFalse(lab.library_allowed({'name': 'org.lwjgl:lwjgl:3:' + suffix}, 'windows', 'x86_64'))
        self.assertTrue(lab.library_allowed({'name': 'org.lwjgl:lwjgl:3:natives-windows'}, 'windows', 'x86_64'))
    def test_generated_library_must_match_retained_embedded_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); mods = root / 'mods'; (mods / '1.12.2').mkdir(parents=True)
            payload = b'the exact embedded library bytes'
            with zipfile.ZipFile(mods / 'owner.jar', 'w') as archive:
                archive.writestr('lib/runtime.jar', payload)
            extracted = mods / '1.12.2/runtime.jar'; extracted.write_bytes(payload)
            manifest = {'server': str(root), 'client': str(root)}
            record = lab.record_generated_libraries(manifest)['client']['1.12.2/runtime.jar']
            self.assertEqual(record['owners'], [{'mod': 'owner.jar', 'embeddedEntry': 'lib/runtime.jar'}])
            extracted.write_bytes(b'a changed library must fail qualification')
            with self.assertRaises(RuntimeError): lab.record_generated_libraries(manifest)
    def test_client_addons_do_not_change_server_or_legacy_hash_contract(self):
        manifest = {'modHashes': {'core.jar': 'a'}, 'clientModHashes': {'core.jar': 'a', 'renderer.jar': 'b'}}
        self.assertEqual(lab.expected_mod_hashes(manifest, 'server'), {'core.jar': 'a'})
        self.assertEqual(lab.expected_mod_hashes(manifest, 'client'), {'core.jar': 'a', 'renderer.jar': 'b'})
        self.assertEqual(lab.expected_mod_hashes({'modHashes': {'core.jar': 'a'}}, 'client'), {'core.jar': 'a'})
    def test_combined_renderer_profile_includes_demonica_exact_pin(self):
        baseline = lab.read_client_profile('baseline')
        combined = lab.read_client_profile('celeritas-demonica')
        self.assertEqual(baseline['mods'], [])
        self.assertEqual([mod['slug'] for mod in combined['mods']], ['celeritas', 'demonica'])
        self.assertTrue(all(mod['side'] == 'client' for mod in combined['mods']))
        self.assertEqual(combined['mods'][0]['sha256'], '5bf123a6da6190f6e061cd3fd069a2bddcbf6ebf57894381b6c888c06a94f982')

if __name__ == '__main__': unittest.main()
