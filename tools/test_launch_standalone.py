"""project.toml's [launch.standalone] and its launch/standalone folder say what the lab's tools expect of an instance:
the reviewed JVM profile, the baseline view distance, the player and the world's height range; [launch.lab]'s drivers
are commands of tools/cubic_lab.py."""
from pathlib import Path
import re
import tomllib
import unittest

import lab_process
import lab_profiles

ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / 'launch/standalone'


def key(path, name, separator='='):
    found = re.findall('^\\s*' + re.escape(name) + re.escape(separator) + '(.*)$', path.read_text(encoding='utf-8'),
                       flags=re.M)
    if len(found) != 1: raise AssertionError(f'{path.name} names {name} {len(found)} times')
    return found[0].strip()


class LaunchStandalone(unittest.TestCase):
    def setUp(self):
        project = tomllib.loads((ROOT / 'project.toml').read_text(encoding='utf-8'))
        self.base = project['launch']['standalone']
        self.member = project['launch']['member']

    def test_the_jvm_profile_is_the_reviewed_default(self):
        profile = dict(self.base['jvm_profile'])
        self.assertEqual(profile.pop('name'), lab_profiles.DEFAULT_VARIANT)
        self.assertEqual(profile, lab_profiles.jvm_profile(lab_profiles.DEFAULT_VARIANT))
        self.assertTrue(self.base['jvm_args_file'].startswith('# Owned lab profile'))

    def test_the_files_hold_the_baseline_view_distance_and_the_views_name_them(self):
        baseline = str(lab_profiles.VIEW_DISTANCE)
        self.assertEqual(key(OVERLAY / 'server/server.properties', 'view-distance'), baseline)
        self.assertEqual(key(OVERLAY / 'both/config/cubicchunks.cfg', 'I:verticalCubeLoadDistance'), baseline)
        self.assertEqual(key(OVERLAY / 'client/options.txt', 'renderDistance', ':'), baseline)
        names = {'horizontal': 'horizontal', 'vertical': 'vertical', 'clientRender': 'client_render'}
        self.assertEqual(set(self.base['views']), {names[k] for k in lab_profiles.VIEW_DISTANCE_KEYS})
        for lines in self.base['views'].values():
            for line in lines:
                side_file, _, name = line.partition(':')
                side, _, relative = side_file.partition('/')
                path = next(OVERLAY / part / relative for part in (side, 'both') if (OVERLAY / part / relative).is_file())
                separator = ':' if relative.endswith('options.txt') else '='
                self.assertEqual(key(path, name, separator), baseline, line)

    def test_the_player_and_the_height_range_are_the_tools(self):
        self.assertEqual(self.base['player'], lab_process.PLAYER)
        config = OVERLAY / 'both/config/cubicchunks.cfg'
        self.assertEqual((key(config, 'I:defaultMinHeight'), key(config, 'I:defaultMaxHeight')), ('-128', '384'))
        self.assertEqual(key(OVERLAY / 'server/server.properties', 'server-port'), '{port}')

    def test_the_lab_launch_starts_from_the_standalone_world_and_its_drivers_are_cubic_lab_commands(self):
        project = tomllib.loads((ROOT / 'project.toml').read_text(encoding='utf-8'))
        launch = project['launch']['lab']
        self.assertEqual(launch['base'], 'cubature:standalone')
        self.assertEqual(set(launch['members']), {'cubature:mod@both', 'cubature:lab-observer@both'})
        commands = re.search(r"add_argument\('command', choices=(\[[^]]*\])", (ROOT / 'tools/cubic_lab.py').read_text(
            encoding='utf-8')).group(1)
        for name, driver in launch['drivers'].items():
            self.assertEqual(driver['command'][:2], ['{python}', '{root}/tools/cubic_lab.py'], name)
            self.assertIn(repr(driver['command'][2]), commands, name)

    def test_the_lab_launch_tours_with_the_lab_observers_tour_client(self):
        tour = tomllib.loads((ROOT / 'project.toml').read_text(encoding='utf-8'))['launch']['lab']['tour']
        source = (ROOT / 'lab/src/main/java/io/github/opencubicchunks/cubicchunks/lab/LabTourClient.java').read_text(
            encoding='utf-8')
        switch = re.search(r'PROPERTY = "([^"]+)"', source).group(1)
        self.assertEqual(tour['client_jvm'], [f'-D{switch}=true'])
        self.assertEqual(tour['captured'], re.search(r'CAPTURED = "([^"]+)"', source).group(1))

    def test_the_refusal_line_is_cubatures(self):
        source = (ROOT / 'src/main/java/io/github/opencubicchunks/cubicchunks/core/client/RendererRequirement.java')
        title = re.search(r'TITLE = "([^"]+)"', source.read_text(encoding='utf-8')).group(1)
        self.assertTrue(any(re.search(pattern, title) for pattern in self.member['failures']), title)


if __name__ == '__main__':
    unittest.main()
