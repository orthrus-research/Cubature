#!/usr/bin/env python3
"""Fresh interactive CC-only worlds using the lab's reviewed loader and launcher."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import time
import uuid

import cubic_lab as lab
import lab_profiles


def prepare(args):
    root = args.workspace.resolve()
    target = root / '.local/cubic-standalone' / args.name
    if target.exists():
        raise RuntimeError('Instance already exists; choose a fresh name')
    donor = json.loads(args.runtime_lab.read_text())
    pins = dict(line.split('=', 1) for line in (lab.FORK / 'lab/runtime.properties').read_text().splitlines()
                if line.strip() and not line.startswith('#'))
    if donor['runtimeVersion'] != pins['runtime_version']:
        raise RuntimeError('Runtime donor is not the reviewed pinned version')
    lab.validate_java26_inputs(donor)
    assets = lab.runtime_assets(donor['release'], pins['runtime_version'],
        {kind: pins[kind + '_sha256'] for kind in ['installer', 'universal', 'prism']})
    files = {}
    for kind in ['installer', 'prism']:
        asset = assets[kind]
        files[kind] = lab.fetch(asset['browser_download_url'], root / '.local/downloads' / asset['name'],
                                pins[kind + '_sha256'], 'sha256')
    if not args.cubic_jar.is_file():
        raise RuntimeError('Missing selected CC artifact')
    server, client = target / 'server', target / 'client'
    server.mkdir(parents=True)
    with (target / 'installer.log').open('w') as output:
        subprocess.run([donor['java'], '-jar', str(files['installer']), 'server', '--dir', str(server),
            '--version', pins['runtime_version'], '--no-gui', '--yes', '--no-provision-java',
            '--java', donor['java'], '--memory', '8G'], stdout=output, stderr=subprocess.STDOUT, check=True)
    (server / 'mods').mkdir(exist_ok=True)
    shutil.copy2(args.cubic_jar, server / 'mods/cubicchunks.jar')
    (server / 'config').mkdir(exist_ok=True)
    (server / 'config/forge_early.cfg').write_text('general {\n    B:DISABLE_PATCH_MOD_CHECK=true\n}\n')
    (server / 'config/cubicchunks.cfg').write_text('''general {
    S:forceLoadCubicChunks=NEW_WORLD
    I:defaultMinHeight=-128
    I:defaultMaxHeight=384
    I:verticalCubeLoadDistance=12
    I:maxCubeSendTimeMillis=0
    I:maxCubeSendBytesPerTick=0
    I:maxCubePacketBytes=0
}
''')
    (server / 'eula.txt').write_text('eula=true\n')
    (server / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nlevel-name=world\n'
        f'level-type=DEFAULT\nlevel-seed={args.seed}\nview-distance=12\ngamemode=1\n'
        'difficulty=0\nallow-flight=true\nmax-players=1\nmax-tick-time=-1\n'
        'enable-command-block=false\nenable-rcon=false\nmotd=CubicChunks only - local performance\n')
    player_uuid = str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + lab.PLAYER).encode()).digest(), version=3))
    lab.write_json(server / 'ops.json', [{'uuid': player_uuid, 'name': lab.PLAYER, 'level': 4,
                                         'bypassesPlayerLimit': False}])
    lab_profiles.configure_jvm(server)
    runtime = lab.prepare_client(server, client, files['prism'], root, donor['java'], donor['release'])
    # Freeze installer output and client libraries; no Riven configs, scripts or lab mod are copied.
    server_files = {str(p.relative_to(server)): lab.sha(p) for p in (server / 'libraries').rglob('*') if p.is_file()}
    universal = server / 'libraries/com/cleanroommc/cleanroom' / pins['runtime_version'] / ('cleanroom-' + pins['runtime_version'] + '-universal.jar')
    if lab.sha(universal) != pins['universal_sha256']:
        raise RuntimeError('Installer output differs from reviewed universal artifact')
    manifest = {key: donor[key] for key in ['release', 'runtimeVersion', 'java', 'javaRuntime']}
    manifest.update(name=args.name, server=str(server), client=str(client), port=args.port, seed=args.seed,
        jvmProfile=lab_profiles.jvm_profile(), clientRuntime=runtime, viewDistance=lab_profiles.view_distance_profile(),
        serverRuntimeHashes=server_files, cubicJar={'source': str(args.cubic_jar.resolve()), 'sha256': lab.sha(args.cubic_jar)},
        purpose='Interactive CC-only: vanilla compatibility terrain, creative/peaceful, no instrumentation mod',
        height={'minY': -128, 'maxYExclusive': 384}, modFiles=['cubicchunks.jar'])
    lab.write_json(target / 'standalone.json', manifest)
    print(target, flush=True)


def load(args):
    target = args.workspace.resolve() / '.local/cubic-standalone' / args.name
    manifest = json.loads((target / 'standalone.json').read_text())
    lab.validate_java26_inputs(manifest)
    lab_profiles.validate_view_distance_inputs(manifest)
    lab_profiles.validate_jvm_inputs(manifest)
    for side in ['server', 'client']:
        directory = Path(manifest[side])
        actual = {str(p.relative_to(directory / 'mods')): lab.sha(p) for p in (directory / 'mods').rglob('*') if p.is_file()}
        if actual != {'cubicchunks.jar': manifest['cubicJar']['sha256']}:
            raise RuntimeError('CC-only mod set changed: ' + side)
    for relative, digest in manifest['serverRuntimeHashes'].items():
        if lab.sha(Path(manifest['server']) / relative) != digest:
            raise RuntimeError('Server runtime changed: ' + relative)
    runtime = manifest['clientRuntime']
    for artifact in runtime['libraries'] + [{'path': runtime['minecraft'], 'sha1': runtime['minecraft_sha1']}]:
        if lab.sha(Path(artifact['path']), 'sha1') != artifact['sha1']:
            raise RuntimeError('Client runtime changed: ' + artifact['path'])
    return target, manifest


def play(args):
    target, manifest = load(args)
    messages = queue.Queue()
    with lab.native_measurement_lease(args.workspace.resolve(), 'standalone-' + args.name):
        command_file = target / 'commands.txt'
        command_file.write_text('')
        server = lab.spawn(lab.argv(manifest, False), Path(manifest['server']), target / 'server-console.log', messages)
        client = None
        try:
            lab.write_json(target / 'processes.json', {'controller': os.getpid(), 'server': server.pid})
            lab.await_line(messages, 'Done (', [server], 600)
            client_messages = queue.Queue()
            client = lab.spawn(lab.argv(manifest, True), Path(manifest['client']), target / 'client-console.log', client_messages)
            lab.write_json(target / 'processes.json', {'controller': os.getpid(), 'server': server.pid, 'client': client.pid})
            print('CC-only server ready; client launched. Closing the client saves and stops this owned server.', flush=True)
            with command_file.open('r', encoding='utf-8') as commands:
                while server.poll() is None and client.poll() is None:
                    for pending in [messages, client_messages]:
                        while not pending.empty():
                            pending.get_nowait()
                    for line in commands:
                        if line.strip():
                            lab.send(server, line.strip())
                    time.sleep(0.5)
        finally:
            if server.poll() is None:
                lab.send(server, 'stop')
                try:
                    server.wait(timeout=180)
                except subprocess.TimeoutExpired:
                    print('Save/stop is still pending; server left alive. Inspect the server log.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'play', 'command'])
    parser.add_argument('--workspace', type=Path, default=lab.FORK.parents[1])
    parser.add_argument('--name', required=True)
    parser.add_argument('--runtime-lab', type=Path)
    parser.add_argument('--cubic-jar', type=Path)
    parser.add_argument('--port', type=int, default=25591)
    parser.add_argument('--seed', type=int, default=8675309)
    parser.add_argument('--text')
    args = parser.parse_args()
    if not args.name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-_' for c in args.name):
        parser.error('Use a lowercase alphanumeric instance name with optional hyphens/underscores')
    if args.command == 'prepare':
        if args.runtime_lab is None or args.cubic_jar is None:
            parser.error('prepare requires --runtime-lab and --cubic-jar')
        prepare(args)
    elif args.command == 'play':
        play(args)
    else:
        if not args.text or '\n' in args.text or '\r' in args.text:
            parser.error('command requires one --text command')
        path = args.workspace / '.local/cubic-standalone' / args.name / 'commands.txt'
        with path.open('a', encoding='utf-8') as output:
            output.write(args.text + '\n')


if __name__ == '__main__':
    main()
