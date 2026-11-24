"""lab_session on a small instance and session folder as Workbench writes them: the receipt's command lines with the
roots filled in, the runtime cache found by the asset index's hash, a fresh copy and the lab's record."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest

import lab_process
import lab_session


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')
    return path


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class SessionFixture(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='lab-session-'))
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.instance, self.cache, self.jdk = self.root / 'instances/riven-default', self.root / 'out/cache/runtime', self.root / 'jdk'
        self.folder = self.root / 'sessions/20261124-101500-a1b2c3'
        index = write(self.cache / 'assets/indexes/5.json', '{"objects": {}}')
        write(self.instance / 'server/server.properties', 'server-port=40001\nlevel-name=world\nlevel-seed=8675309\n'
                                                           'view-distance=12\n')
        write(self.instance / 'server/config/cubicchunks.cfg', 'general {\n    I:verticalCubeLoadDistance=12\n}\n')
        write(self.instance / 'client/config/cubicchunks.cfg', 'general {\n    I:verticalCubeLoadDistance=12\n}\n')
        write(self.instance / 'client/options.txt', 'renderDistance:12\n')
        write(self.instance / 'server/user_jvm_args.txt', '-Xmx8G\n')
        mod = write(self.instance / 'server/mods/cubature.jar', 'cubature')
        write(self.instance / 'client/mods/cubature.jar', 'cubature')
        write(self.instance / 'server/world/level.dat', 'world')
        write(self.instance / 'server/logs/latest.log', 'old')
        launch = write(self.instance / 'launch.json', json.dumps({'members': [
            {'member': 'cubature:mod@both', 'name': 'cubature', 'artifact': 'mod', 'sha256': digest(mod),
             'commit': 'c0ffee'}]}))
        receipt = {
            'schema': lab_session.INSTANCE_SCHEMA, 'key': 'k1',
            'launch': {'sha256': digest(launch), 'project': 'riven', 'launch': 'default', 'commit': 'abc', 'set': 's',
                       'members': {'cubature:mod@both': digest(mod)}},
            'jdk': {'26': {'build': '26.0.2.1+1'}}, 'player': {'name': lab_process.PLAYER},
            'runtime': {'asset_index': {'path': '{cache}/assets/indexes/5.json'}},
            'jvm_profile': {'name': 'zgc-fixed', 'collector': 'ZGC', 'initialHeapMiB': 8192, 'maxHeapMiB': 8192,
                            'arguments': ['-Xms8192M', '-Xmx8G', '-XX:+UseZGC']},
            'members': [{'member': 'cubature:mod@both', 'sha256': digest(mod),
                         'placed': ['{instance}/server/mods/cubature.jar', '{instance}/client/mods/cubature.jar']}],
            'server': {'cwd': '{instance}/server', 'jvm_at': 1,
                       'command': ['{jdk:26}/bin/java', '-Xmx8G', '@{instance}/server/args.txt', 'nogui']},
            'client': {'cwd': '{instance}/client', 'jvm_at': 2,
                       'command': ['{jdk:26}/bin/java', '-Dquadrature.distant=own', '-Xmx8G', '-cp',
                                   '{cache}/libraries/a.jar', 'Main', '--port', '{port}']},
            'files': {'{cache}/assets/indexes/5.json': {'sha256': digest(index)}},
        }
        self.receipt = write(self.instance / 'instance.json', json.dumps(receipt))
        self.folder.mkdir(parents=True)
        shutil.copy2(launch, self.folder / 'launch.json')
        write(self.folder / 'session.json', json.dumps({
            'schema': lab_session.SESSION_SCHEMA, 'id': self.folder.name, 'kind': 'driver',
            'instance': {'name': self.instance.name, 'receipt_sha256': digest(self.receipt)},
            'jdk': {'major': '26', 'build': '26.0.2.1+1', 'home': str(self.jdk)}, 'inputs': {'port': 40001}}))
        self.environ = {'WB_INSTANCE': str(self.receipt), 'WB_SESSION': str(self.folder),
                        'WB_LAUNCH': str(self.folder / 'launch.json'), 'WB_LAB_HOME': str(self.root)}

    def session(self):
        old = dict(os.environ)
        try:
            os.environ.clear()
            os.environ.update(self.environ)
            return lab_session.Session.from_environment()
        finally:
            os.environ.clear()
            os.environ.update(old)


class LabSession(SessionFixture):
    def test_outside_a_driver_session_nothing_starts(self):
        with self.assertRaisesRegex(lab_session.SessionError, 'not in a driver session'):
            lab_session.Session.from_environment({})

    def test_the_receipts_commands_are_filled_in_from_the_session(self):
        session = self.session()
        self.assertEqual(session.cache, self.cache)
        argv, at, cwd = session.command('client')
        self.assertEqual(argv, [f'{self.jdk}/bin/java', '-Dquadrature.distant=own', '-Xmx8G', '-cp',
                                f'{self.cache}/libraries/a.jar', 'Main', '--port', '40001'])
        self.assertEqual((at, cwd), (2, self.instance / 'client'))
        self.assertEqual(session.member('cubature')['placed'][0], self.instance / 'server/mods/cubature.jar')

    def test_the_cache_is_taken_only_by_the_asset_index_hash(self):
        write(self.cache / 'assets/indexes/5.json', 'changed')
        with self.assertRaisesRegex(lab_session.SessionError, 'no runtime cache'):
            self.session()
        self.environ['WB_RUNTIME_CACHE'] = str(self.root / 'elsewhere')
        with self.assertRaisesRegex(lab_session.SessionError, 'does not hold'):
            self.session()

    def test_a_session_on_another_receipt_is_refused(self):
        write(self.receipt, self.receipt.read_text() + ' ')
        with self.assertRaisesRegex(lab_session.SessionError, 'another receipt'):
            self.session()

    def test_a_copy_links_what_the_game_reads_and_leaves_the_world_behind(self):
        copy = self.session().copy()
        made = copy.instance
        self.assertEqual(made, self.folder / 'instance')
        self.assertTrue(os.path.samefile(made / 'server/mods/cubature.jar', self.instance / 'server/mods/cubature.jar'))
        self.assertFalse(os.path.samefile(made / 'server/server.properties', self.instance / 'server/server.properties'))
        self.assertFalse((made / 'server/world').exists())
        self.assertFalse((made / 'server/logs').exists())
        self.assertEqual(copy.command('server')[0][2], f'@{made}/server/args.txt')
        with self.assertRaisesRegex(lab_session.SessionError, 'exists already'):
            self.session().copy()

    def test_the_labs_record_carries_the_command_lines_and_the_runner_splices_its_options(self):
        manifest = self.session().copy().manifest()
        self.assertEqual(manifest['seed'], 8675309)
        self.assertEqual(manifest['viewDistance'], {'horizontal': 12, 'vertical': 12, 'clientRender': 12})
        self.assertEqual(manifest['jvmProfile']['maxHeapMiB'], 8192)
        self.assertEqual(manifest['modHashes']['server'], {'cubature.jar': hashlib.sha256(b'cubature').hexdigest()})
        manifest['labProperties'] = {'cubiclab.threadCosts': 'true'}
        client = lab_process.argv(manifest, True)
        self.assertEqual(client[:4], [f'{self.jdk}/bin/java', '-Dquadrature.distant=own',
                                      '-Dcubiclab.threadCosts=true', '-Xmx8G'])
        server = lab_process.argv(manifest, False, recording=self.folder / 'server.jfr')
        self.assertEqual(server[0:2], [f'{self.jdk}/bin/java', '-Dcubiclab.threadCosts=true'])
        self.assertTrue(server[2].startswith('-XX:FlightRecorderOptions=repository='))
        self.assertEqual(server[-1], 'nogui')
        self.assertTrue((Path(manifest['server']) / 'java-tmp').is_dir())


class CubicLabDriver(SessionFixture):
    def test_run_measures_a_fresh_copy_and_keeps_its_record_in_the_session_folder(self):
        import cubic_lab
        from unittest import mock
        seen = {}

        def run(args, *, load):
            seen['target'], seen['manifest'] = load(args)
            return 0
        environ = {**self.environ, 'LAB_DRIVER_ARGS': '--property cubiclab.threadCosts=true'}
        with mock.patch.dict(os.environ, environ, clear=True), mock.patch.object(cubic_lab, 'run', run):
            with self.assertRaises(SystemExit) as done:
                cubic_lab.main(['run'])
        self.assertEqual(done.exception.code, 0)
        self.assertEqual(seen['target'], self.folder)
        self.assertEqual(seen['manifest']['server'], str(self.folder / 'instance/server'))
        self.assertEqual(seen['manifest']['labProperties'], {'cubiclab.threadCosts': 'true'})
        self.assertEqual(json.loads((self.folder / 'lab.json').read_text()), seen['manifest'])

    def test_a_session_below_the_baseline_view_distance_is_refused_before_launch(self):
        import cubic_lab
        from unittest import mock
        write(self.instance / 'client/options.txt', 'renderDistance:8\n')
        with mock.patch.dict(os.environ, self.environ, clear=True), mock.patch.object(cubic_lab, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'below the baseline'):
                cubic_lab.main(['run'])
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
