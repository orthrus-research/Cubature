#!/usr/bin/env python3
"""Export native lab JFRs and reuse the fork's analyzer with exact per-side scenario windows."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

from analyze_cubic_profile import EVENTS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lab-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    lab = args.lab_directory.resolve()
    manifest = json.loads((lab / 'lab.json').read_text())
    exports = lab / 'profile-exports'; exports.mkdir(exist_ok=True)
    temporary = exports / 'java-tmp'; temporary.mkdir(exist_ok=True)
    analyze = [sys.executable, '-B', str(Path(__file__).with_name('analyze_cubic_profile.py')),
               '--no-charts', '--output', str(args.output.resolve())]
    for phase in ['write', 'read']:
        result = json.loads((lab / 'runs' / phase / 'result.json').read_text())
        if not result['passed']: raise ValueError('Incomplete suite: ' + phase)
        for side in ['server', 'client']:
            label = phase + '-' + side
            recording = lab / 'runs' / phase / (side + '.jfr')
            if not recording.is_file(): raise ValueError('Run with --jfr: ' + str(recording))
            target = exports / (label + '.json')
            # Never infer windows from startup/shutdown; use that side's own measured UTC boundaries.
            phases = [dict(scenario[side], name=scenario['scenario']) for scenario in result['scenarios']]
            windows = exports / (label + '-windows.json')
            windows.write_text(json.dumps({'loader': 'cleanroom', 'phases': phases}, indent=2) + '\n', encoding='utf-8')
            command = [manifest['java'], '-Djava.io.tmpdir=' + str(temporary), '-m',
                       'jdk.jfr/jdk.jfr.internal.tool.Main', 'print', '--json', '--stack-depth', '128',
                       '--events', EVENTS, str(recording)]
            with target.open('w', encoding='utf-8') as output:
                subprocess.run(command, stdout=output, check=True)
            print('Exported ' + label, flush=True)
            analyze += ['--jfr', label + '=' + str(target), '--probe', label + '=' + str(windows)]
    subprocess.run(analyze, check=True)


if __name__ == '__main__': main()
