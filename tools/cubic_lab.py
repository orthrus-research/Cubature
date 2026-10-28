#!/usr/bin/env python3
"""Prepare and run isolated native Cleanroom clients/servers for the optional cube lab. Python 3.11+."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager, nullcontext
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import queue
import re
import shutil
import subprocess
import threading
import time
import urllib.request
import uuid
import zipfile
from native_process_lifecycle import ProcessLifecycle
from native_workload_guard import JavaWorkloadGuard
from lab_input_snapshot import retain_prelaunch_inputs
import lab_workload

FORK = Path(__file__).resolve().parents[1]
PLAYER = 'CubicLab'
VIEW_DISTANCE = 12
JVM_ARGUMENTS = ['-Xms1G', '-Xmx8G', '-XX:+UseZGC']


@contextmanager
def native_measurement_lease(root, name):
    """One measured pair per workspace; the OS releases the lease if a controller exits."""
    path = root / '.local/cubic-lab/native-measurement.lock'
    path.parent.mkdir(parents=True, exist_ok=True)
    with os.fdopen(os.open(path, os.O_RDWR | os.O_CREAT, 0o600), 'r+b') as stream:
        if path.stat().st_size == 0: stream.write(b' '); stream.flush()
        stream.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise RuntimeError('Another native lab owns this workspace measurement lease; preserve that run') from error
        try:
            stream.seek(1); stream.write(json.dumps({'pid':os.getpid(),'name':name,'startedUtc':datetime.now(timezone.utc).isoformat()}).encode())
            stream.truncate(); stream.flush()
            yield
        finally:
            stream.seek(0)
            if os.name == 'nt': msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else: fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


JVM_VARIANTS = ['zgc', 'zgc-fixed', 'zgc-pretouch', 'g1', 'g1-fixed', 'g1-pretouch']


def jvm_profile(variant='zgc'):
    if variant not in JVM_VARIANTS: raise ValueError('Unknown JVM experiment')
    initial = 1024 if '-' not in variant else 8192
    collector = 'G1' if variant.startswith('g1') else 'ZGC'
    arguments = [f'-Xms{initial}M', '-Xmx8G', '-XX:+UseG1GC' if collector == 'G1' else '-XX:+UseZGC']
    # Keep the established default manifest/argfile representation stable.
    if variant == 'zgc': arguments = list(JVM_ARGUMENTS)
    if variant.endswith('-pretouch'): arguments.append('-XX:+AlwaysPreTouch')
    return {'initialHeapMiB': initial, 'maxHeapMiB': 8192, 'collector': collector, 'arguments': arguments}


def configure_jvm(server, profile=None):
    # The installer argfile is expanded after command-line flags and can override them.
    (server / 'user_jvm_args.txt').write_text(
        '# Owned lab profile: same heap and collector on server and client.\n'
        + '\n'.join((profile or jvm_profile())['arguments']) + '\n', encoding='utf-8')


def validate_jvm_inputs(manifest):
    profile = manifest.get('jvmProfile')
    if profile not in [jvm_profile(variant) for variant in JVM_VARIANTS]:
        raise RuntimeError('Native testing requires a reviewed equal-8-GiB JVM profile; prepare a fresh instance')
    path = Path(manifest['server']) / 'user_jvm_args.txt'
    arguments = [line.strip() for line in path.read_text().splitlines()
                 if line.strip() and not line.lstrip().startswith('#')]
    if arguments != profile['arguments']:
        raise RuntimeError('Prepared server JVM arguments changed; prepare a fresh instance')


def validate_jvm_capture(server, client, manifest=None):
    collector = (manifest or {}).get('jvmProfile', jvm_profile())['collector']
    for side, capture in [('server', server), ('client', client)]:
        runtime = capture.get('runtime', {})
        if runtime.get('maxHeapBytes') != 8192 * 1024 * 1024:
            raise RuntimeError('Effective ' + side + ' heap differs from 8 GiB')
        collectors = runtime.get('garbageCollectors', [])
        if not collectors or not all(name.startswith(collector) for name in collectors):
            raise RuntimeError('Effective ' + side + ' collector differs from ' + collector + ': ' + str(collectors))


def configure_cube_experiments(server, args):
    if not 1 <= getattr(args, 'cube_packet_cubes', 1024) <= 1024:
        raise ValueError('Packet cube count must be 1..1024')
    values = {'incrementalMaintenance': ('B', 'true' if args.incremental_maintenance else 'false'),
              'maxCubeSendTimeMillis': ('I', args.cube_send_ms),
              'maxCubeSendBytesPerTick': ('I', args.cube_send_bytes),
              'maxCubePacketBytes': ('I', args.cube_packet_bytes),
              'maxCubePacketCubes': ('I', getattr(args, 'cube_packet_cubes', 1024))}
    path = server / 'config/cubicchunks.cfg'
    content = path.read_text()
    for name, (kind, value) in values.items():
        setting = f'    {kind}:{name}={value}'
        pattern = rf'^\s*{kind}:{name}=.*$'
        if re.search(pattern, content, flags=re.M): content = re.sub(pattern, setting, content, flags=re.M)
        else:
            content, count = re.subn(r'general\s*\{', 'general {\n' + setting, content, count=1)
            if count != 1: raise RuntimeError('Missing CubicChunks general config category')
    path.write_text(content, encoding='utf-8')
    return {name: str(value) for name, (_, value) in values.items()}


def validate_cube_experiments(manifest):
    for name, expected in manifest.get('cubeExperiments', {}).items():
        path = Path(manifest['server']) / 'config/cubicchunks.cfg'
        actual = re.findall(r'^\s*[BI]:' + re.escape(name) + r'=(.*?)\s*$', path.read_text(), flags=re.M)
        if actual != [expected]: raise RuntimeError('Prepared cube experiment changed: ' + name)


def view_distance_profile():
    return {'horizontal': VIEW_DISTANCE, 'vertical': VIEW_DISTANCE, 'clientRender': VIEW_DISTANCE}


def configure_view_distance(server):
    """Apply the shared test distance only to a newly owned preparation directory."""
    properties = server / 'server.properties'
    text = re.sub(r'^view-distance=.*\n?', '', properties.read_text(), flags=re.M)
    properties.write_text(text + f'view-distance={VIEW_DISTANCE}\n', encoding='utf-8')
    config = server / 'config/cubicchunks.cfg'
    text = config.read_text()
    setting = f'    I:verticalCubeLoadDistance={VIEW_DISTANCE}'
    if re.search(r'^\s*I:verticalCubeLoadDistance=.*$', text, flags=re.M):
        text = re.sub(r'^\s*I:verticalCubeLoadDistance=.*$', setting, text, flags=re.M)
    else:
        text, count = re.subn(r'general\s*\{', 'general {\n' + setting, text, count=1)
        if count != 1: raise RuntimeError('Prepared CubicChunks config has no general category')
    config.write_text(text, encoding='utf-8')


def client_options():
    return (f'renderDistance:{VIEW_DISTANCE}\nmaxFps:120\nenableVsync:false\nfancyGraphics:false\n'
            'renderClouds:false\nfullscreen:false\npauseOnLostFocus:false\nshowSubtitles:false\ntutorialStep:none\n')


def validate_view_distance_inputs(manifest):
    if manifest.get('viewDistance') != view_distance_profile():
        raise RuntimeError('Native testing requires view distance 12; preserve this older lab and prepare a new instance')
    server, client = Path(manifest['server']), Path(manifest['client'])
    checks = [(server / 'server.properties', r'^view-distance=(\d+)\s*$'),
              (client / 'options.txt', r'^renderDistance:(\d+)\s*$')]
    checks += [(directory / 'config/cubicchunks.cfg', r'^\s*I:verticalCubeLoadDistance=(\d+)\s*$')
               for directory in [server, client]]
    for path, pattern in checks:
        values = re.findall(pattern, path.read_text(), flags=re.M)
        if values != [str(VIEW_DISTANCE)]:
            raise RuntimeError('Test view distance changed or is ambiguous in ' + str(path) + '; prepare a new revision')


def validate_view_distance_capture(manifest, server, client):
    expected = manifest.get('viewDistance')
    if expected != view_distance_profile(): raise RuntimeError('Missing current view-distance profile')
    observed = {'horizontal': server.get('horizontalViewDistance'), 'vertical': server.get('verticalViewDistance'),
                'clientRender': client.get('renderDistance')}
    if observed != expected or client.get('verticalViewDistance') != VIEW_DISTANCE:
        raise RuntimeError('Measured view distances differ from required 12: ' + str(observed)
                           + '; client vertical=' + str(client.get('verticalViewDistance')))


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


def sha(path, algorithm='sha256'):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def fetch(url, target, expected, algorithm='sha1', candidates=()):
    if target.is_file() and sha(target, algorithm) == expected:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    for candidate in candidates:
        if candidate.is_file() and sha(candidate, algorithm) == expected:
            temporary = target.with_name(target.name + '.' + uuid.uuid4().hex + '.partial')
            shutil.copy2(candidate, temporary)
            temporary.replace(target)
            return target
    request = urllib.request.Request(url, headers={'User-Agent': 'CubicChunks-development-lab'})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read()
    if hashlib.new(algorithm, data).hexdigest() != expected:
        raise RuntimeError('Checksum mismatch: ' + url)
    temporary = target.with_name(target.name + '.' + uuid.uuid4().hex + '.partial')
    temporary.write_bytes(data)
    temporary.replace(target)
    return target


def runtime_assets(release, product_version, expected_hashes=None):
    """Reject same-tag asset replacement before allocating an instance or running its installer."""
    names = {'installer': f'cleanroom-{product_version}-installer.jar',
             'universal': f'cleanroom-{product_version}-universal.jar', 'prism': f'cleanroom-{product_version}.zip'}
    assets = {}
    for kind, name in names.items():
        matches = [asset for asset in release['assets'] if asset['name'] == name]
        if len(matches) != 1 or not re.fullmatch(r'sha256:[0-9a-f]{64}', matches[0].get('digest') or ''):
            raise RuntimeError('Review runtime asset naming/digest before preparing: ' + name)
        asset = matches[0]
        if expected_hashes is not None and asset['digest'] != 'sha256:' + expected_hashes.get(kind, ''):
            raise RuntimeError('Runtime asset changed under the reviewed tag; review lab/runtime.properties: ' + name)
        assets[kind] = asset
    return assets


def needs_070_compat(version):
    return version in ('0.7.0', '0.7.0-alpha', '0.7.1-alpha', '0.7.2-alpha', '0.7.4-alpha', '0.7.5-alpha',
                       '0.7.6-alpha', '0.7.7-alpha', '0.7.8-alpha', '0.7.9-alpha')


def inspect_java26(path):
    path = Path(path).resolve()
    completed = subprocess.run([str(path), '-version'], text=True, capture_output=True, check=True, timeout=15)
    version = completed.stderr + completed.stdout
    match = re.search(r'(?m)^(?:openjdk|java) version "(\d+)[^\"]*"', version)
    if not match or int(match[1]) != 26:
        raise RuntimeError('New qualification labs require Java 26; select its java executable with --java')
    home = path.parent.parent
    critical = [path, home / 'release', home / 'lib/modules', home / ('bin/server/jvm.dll' if os.name == 'nt' else 'lib/server/libjvm.so')]
    return {'major': 26, 'version': match[0], 'criticalArtifacts': [
        {'path': str(p), 'sha256': sha(p)} for p in critical]}


def validate_java26_inputs(manifest):
    runtime = manifest.get('javaRuntime', {})
    if runtime.get('major') != 26:
        raise RuntimeError('Java 26 qualification requires a newly prepared instance; preserve historical Java 25 evidence')
    artifacts = runtime.get('criticalArtifacts', [])
    if len(artifacts) != 4 or str(Path(manifest['java']).resolve()) != artifacts[0]['path']:
        raise RuntimeError('Missing or mismatched Java 26 runtime receipt')
    for artifact in artifacts:
        path = Path(artifact['path'])
        if not path.is_file() or sha(path) != artifact['sha256']:
            raise RuntimeError('Prepared Java 26 runtime changed; prepare a fresh qualification instance')


def library_allowed(library, system, architecture):
    allowed = not library.get('rules')
    for rule in library.get('rules', []):
        match = True
        spec = rule.get('os', {})
        if 'name' in spec:
            match = spec['name'] in (system, system + '-arm64' if architecture == 'aarch64' else system)
        if 'arch' in spec:
            match &= bool(re.search(spec['arch'], architecture))
        if 'version' in spec:
            match &= bool(re.search(spec['version'], platform.release()))
        if match:
            allowed = rule['action'] == 'allow'
    # MMC metadata can declare several native architectures under the same OS rule.
    classifier = library['name'].split(':')[3:]
    if classifier and classifier[0].startswith('natives-'):
        name = classifier[0]
        if architecture == 'x86_64' and (name.endswith('-x86') or '-arm' in name):
            return False
        if architecture == 'aarch64' and not name.endswith('-arm64'):
            return False
    return allowed


def merged_libraries(components, system, architecture):
    selected, priority = {}, {}
    for component_index, component in enumerate(components):
        for library in component.get('libraries', []):
            if not library_allowed(library, system, architecture): continue
            parts = library['name'].split(':'); key = (parts[0], parts[1], ':'.join(parts[3:]))
            prior = selected.get(key)
            if prior and prior['name'] == library['name']:
                library = dict(prior, **library)
                library['downloads'] = dict(prior.get('downloads', {}), **library.get('downloads', {}))
            selected[key] = library; priority[key] = component_index
    return [selected[key] for key in sorted(selected, key=lambda key: priority[key], reverse=True)]


def prepare_client(server, target, base_zip, root, java, release):
    target.mkdir(parents=True, exist_ok=False)
    for folder in ['mods', 'config', 'groovy', 'local', 'resources', 'resourcepacks']:
        if (server / folder).exists():
            shutil.copytree(server / folder, target / folder)
    with zipfile.ZipFile(base_zip) as archive:
        components = [json.loads(archive.read(name)) for name in archive.namelist()
                      if name.startswith('patches/') and name.endswith('.json')]
        embedded = {Path(name).name: archive.read(name) for name in archive.namelist() if name.startswith('libraries/') and name.endswith('.jar')}
    if not any(component.get('uid') == 'net.minecraft' for component in components):
        with urllib.request.urlopen('https://meta.prismlauncher.org/v1/net.minecraft/1.12.2.json', timeout=30) as response:
            components.insert(0, json.load(response))
    write_json(target / 'resolved-components.json', components)
    system = {'Windows': 'windows', 'Linux': 'linux', 'Darwin': 'osx'}[platform.system()]
    architecture = 'aarch64' if platform.machine().lower() in ('arm64', 'aarch64') else 'x86_64'
    cache = root / '.local/cubic-lab/cache'
    home = Path.home()
    caches = [root / '.local/instances/kernel/libraries', server / 'libraries',
              home / '.gradle/caches/modules-2/files-2.1',
              home / 'AppData/Roaming/PrismLauncher/libraries']
    by_name = {}
    for directory in caches:
        if directory.exists():
            for path in directory.rglob('*.jar'):
                by_name.setdefault(path.name, []).append(path)
    artifacts = {}
    for library in merged_libraries(components, system, architecture):
        name = library['name']; downloads = library.get('downloads', {})
        if library.get('MMC-hint') == 'local':
            parts = name.split(':'); filename = parts[1] + '-' + parts[2] + '.jar'
            body = embedded[filename]
            relative = parts[0].replace('.', '/') + '/' + parts[1] + '/' + parts[2] + '/' + filename
            path = cache / 'libraries' / relative; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(body)
            artifacts[(name, hashlib.sha1(body).hexdigest())] = (name, {'url': path.as_uri(), 'sha1': hashlib.sha1(body).hexdigest(), 'path': relative})
            continue
        if 'artifact' in downloads:
            artifacts[(name, downloads['artifact']['sha1'])] = (name, downloads['artifact'])
        native_key = system + '-arm64' if architecture == 'aarch64' else system
        classifier = library.get('natives', {}).get(native_key)
        if classifier:
            classifier = classifier.replace('${arch}', '64')
            artifact = downloads['classifiers'][classifier]
            artifacts[(name + ':' + classifier, artifact['sha1'])] = (name + ':' + classifier, artifact)
    def library_job(item):
        name, artifact = item
        parts = name.split(':')
        relative = artifact.get('path') or '/'.join([parts[0].replace('.', '/'), parts[1], parts[2], artifact['url'].rsplit('/', 1)[-1]])
        path = fetch(artifact['url'], cache / 'libraries' / relative, artifact['sha1'],
                     candidates=by_name.get(Path(relative).name, []))
        return {'name': name, 'path': str(path), 'sha1': artifact['sha1']}
    with ThreadPoolExecutor(max_workers=8) as pool:
        libraries = list(pool.map(library_job, artifacts.values()))
    minecraft = next(c for c in components if c.get('uid') == 'net.minecraft')
    main_jar = minecraft['mainJar']['downloads']['artifact']
    jar = fetch(main_jar['url'], cache / 'minecraft-client-1.12.2.jar', main_jar['sha1'], candidates=[
        home / '.gradle/caches/unimined/net/minecraft/minecraft/1.12.2/minecraft-1.12.2-client.jar'])
    assets = cache / 'assets'
    asset_roots = [home / '.gradle/caches/unimined/assets', home / 'AppData/Roaming/PrismLauncher/assets']
    index = minecraft['assetIndex']
    index_path = fetch(index['url'], assets / 'indexes' / (index['id'] + '.json'), index['sha1'],
                       candidates=[p / 'indexes' / (index['id'] + '.json') for p in asset_roots])
    objects = json.loads(index_path.read_text())['objects']
    def asset_job(entry):
        digest = entry['hash']; relative = digest[:2] + '/' + digest
        return fetch('https://resources.download.minecraft.net/' + relative, assets / 'objects' / relative, digest,
                     candidates=[p / 'objects' / relative for p in asset_roots])
    with ThreadPoolExecutor(max_workers=12) as pool:
        list(pool.map(asset_job, {entry['hash']: entry for entry in objects.values()}.values()))
    natives = target / 'natives'; natives.mkdir()
    for library in libraries:
        if ':natives-' in library['name']:
            with zipfile.ZipFile(library['path']) as archive:
                for member in archive.namelist():
                    if member.lower().endswith(('.dll', '.so', '.dylib')):
                        (natives / Path(member).name).write_bytes(archive.read(member))
    (target / 'options.txt').write_text(client_options(), encoding='utf-8')
    result = {'schema': 1, 'java': java, 'release': release['tag_name'], 'libraries': libraries,
              'minecraft': str(jar), 'minecraft_sha1': main_jar['sha1'], 'assets': str(assets), 'assetIndex': index['id'],
              'natives': str(natives), 'system': system, 'architecture': architecture,
              'assetObjects': len(objects), 'mainClass': 'top.outlands.foundation.boot.Foundation',
              'authMode': 'offline local diagnostic; no account credentials used'}
    write_json(target / 'client-runtime.json', result)
    return result


def read_client_profile(name):
    path = FORK / 'lab/profiles/client-renderers.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    if name not in data['profiles']: raise RuntimeError('Unknown client profile: ' + name)
    selected = data['profiles'][name]
    mods = [dict(data['mods'][slug], slug=slug, side='client') for slug in selected]
    for mod in mods:
        if any(dependency not in selected for dependency in mod.get('requires', [])):
            raise RuntimeError('Client profile is missing a dependency for ' + mod['slug'])
    return {'name': name, 'definitionSha256': sha(path), 'mods': mods,
            'ownership': 'External unmodified runtime compatibility inputs; no source or responsibilities embedded into CubicChunks'}


def expected_mod_hashes(manifest, side):
    return manifest.get('clientModHashes', manifest['modHashes']) if side == 'client' else manifest['modHashes']


def script_hashes(directory):
    directory = Path(directory)
    paths = list((directory / 'groovy').rglob('*.groovy'))
    config = directory / 'groovy/runConfig.json'
    if config.is_file(): paths.append(config)
    return {path.relative_to(directory).as_posix(): sha(path) for path in sorted(paths)}


def package_client(target, manifest, base_zip):
    """Export owned client inputs, excluding worlds, caches, accounts and measurement outputs."""
    export = Path(manifest['prismImport'])
    if export.exists():
        retained = export.with_name('CubicChunks-Lab-Prism-unprimed.zip')
        if not retained.exists(): export.rename(retained)
    temporary = export.with_suffix('.partial')
    client = Path(manifest['client'])
    with zipfile.ZipFile(base_zip) as base, zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as output:
        for name in base.namelist():
            if not name.endswith('/') and name != 'instance.cfg': output.writestr(name, base.read(name))
        output.writestr('instance.cfg', 'InstanceType=OneSix\nname=CubicChunks Lab ' + manifest['name']
            + '\nOverrideJava=true\nJavaPath=' + manifest['java'].replace('\\', '/')
            + '\nOverrideMemory=true\nMinMemAlloc=' + str(manifest.get('jvmProfile', {}).get('initialHeapMiB', 1024))
            + '\nMaxMemAlloc=' + str(manifest.get('jvmProfile', {}).get('maxHeapMiB', 6144))
            + '\nOverrideJavaArgs=true\nJvmArgs='
            + ' '.join(a for a in manifest.get('jvmProfile', {}).get('arguments', []) if not a.startswith(('-Xms','-Xmx'))) + ' '
            + ' '.join('-D' + name + '=' + value for name, value in manifest.get('labProperties', {}).items()) + ' '
            + ('-Dcubiclab.cleanroom070=true' if needs_070_compat(manifest.get('runtimeVersion')) else '')
            + (' -Dfml.readTimeout='+str(manifest['networkReadTimeoutSeconds']) if manifest.get('networkReadTimeoutSeconds',30)!=30 else '') + '\n')
        for folder in ['mods', 'config', 'groovy', 'local', 'resources', 'resourcepacks']:
            for path in sorted((client / folder).rglob('*')):
                if path.is_file(): output.write(path, 'minecraft/' + path.relative_to(client).as_posix())
        output.write(client / 'options.txt', 'minecraft/options.txt')
    temporary.replace(export)
    manifest['prismImportSha256'] = sha(export)
    manifest['prismImportPrimed'] = bool(manifest.get('primed'))


def record_inputs(manifest):
    inputs = {}
    for side in ['server', 'client']:
        directory = Path(manifest[side]); paths = []
        for folder in ['config', 'groovy', 'local', 'resources', 'resourcepacks']:
            paths.extend(path for path in (directory / folder).rglob('*') if path.is_file())
        paths.extend(path for path in [directory / 'options.txt', directory / 'server.properties', directory / 'user_jvm_args.txt'] if path.is_file())
        inputs[side] = {path.relative_to(directory).as_posix(): sha(path) for path in sorted(paths)}
    return inputs


def record_generated_libraries(manifest):
    """Accept startup-extracted libraries only when their bytes match a retained mod's embedded JAR."""
    result = {}
    for side in ['server', 'client']:
        directory = Path(manifest[side]) / 'mods'
        nested = [path for path in directory.rglob('*.jar') if path.parent != directory]
        owners = {}
        wanted = {path.name for path in nested}
        for owner in sorted(directory.glob('*.jar')):
            with zipfile.ZipFile(owner) as archive:
                for entry in archive.namelist():
                    if Path(entry).name in wanted:
                        digest = hashlib.sha256(archive.read(entry)).hexdigest()
                        owners.setdefault((Path(entry).name, digest), []).append({'mod': owner.name, 'embeddedEntry': entry})
        records = {}
        for path in sorted(nested):
            digest = sha(path); source = owners.get((path.name, digest))
            if not source: raise RuntimeError('Unowned nested mod/library JAR; create and review a new lab: ' + str(path))
            records[path.relative_to(directory).as_posix()] = {'sha256': digest, 'owners': source,
                'reason': 'Required library extracted unchanged by a retained startup integration'}
        result[side] = records
    return result


def export_client(args):
    target, manifest = load_lab(args)
    asset = next(a for a in manifest['release']['assets'] if a['name'].endswith('.zip'))
    base_zip = fetch(asset['browser_download_url'], args.riven_workspace / '.local/downloads' / asset['name'],
                     asset['digest'].split(':')[1], 'sha256')
    manifest['generatedInputHashes'] = record_inputs(manifest)
    manifest['generatedLibraryHashes'] = record_generated_libraries(manifest)
    manifest['generatedInputsRecordedUtc'] = datetime.now(timezone.utc).isoformat()
    holder_note = 'Lab-only ObjectHolderRef mixin initializes the declaring class before registry field injection, preventing later static initializers from clearing injected values.'
    if needs_070_compat(manifest.get('runtimeVersion')) and holder_note not in manifest.get('compatibility', []):
        manifest.setdefault('compatibility', []).append(holder_note)
    package_client(target, manifest, base_zip)
    write_json(target / 'lab.json', manifest)
    print(manifest['prismImport'])


def load_lab(args):
    target = args.riven_workspace / '.local/cubic-lab' / args.name
    manifest = json.loads((target / 'lab.json').read_text())
    lab_workload.verify(manifest)
    witness=manifest.get('riven',{}).get('coastalWitnesses')
    if witness and sha(Path(manifest['server'])/witness['relativePath'])!=witness['sha256']:
        raise RuntimeError('Prepared Riven coastal witness changed; prepare a fresh lab revision')
    witness=manifest.get('riven',{}).get('biomeWitnesses')
    if witness and sha(Path(manifest['server'])/witness['relativePath'])!=witness['sha256']:
        raise RuntimeError('Prepared Riven biome witness changed; prepare a fresh lab revision')
    witness=manifest.get('riven',{}).get('decorationWitnesses')
    if witness and sha(Path(manifest['server'])/witness['relativePath'])!=witness['sha256']:
        raise RuntimeError('Prepared Riven decoration camera input changed; prepare a fresh lab revision')
    witness=manifest.get('riven',{}).get('caveWitnesses')
    if witness and sha(Path(manifest['server'])/witness['relativePath'])!=witness['sha256']:
        raise RuntimeError('Prepared Riven cave witness changed; prepare a fresh lab revision')
    if 'sharedScriptHashes' in manifest:
        for side in ['server', 'client']:
            if script_hashes(manifest[side]) != manifest['sharedScriptHashes']:
                raise RuntimeError('Prepared ' + side + ' registration scripts changed; prepare a fresh lab revision')
    for side, artifact in manifest.get('runtimeArtifacts', {}).items():
        if sha(Path(artifact['path'])) != artifact['sha256']:
            raise RuntimeError('Prepared ' + side + ' runtime changed; create a new reviewed lab revision')
    if 'modHashes' in manifest:
        for side in ['server', 'client']:
            actual = {path.name: sha(path) for path in Path(manifest[side]).joinpath('mods').glob('*.jar')}
            if actual != expected_mod_hashes(manifest, side): raise RuntimeError('Prepared ' + side + ' mod set changed; create a new lab revision before benchmarking')
    if 'generatedLibraryHashes' in manifest and record_generated_libraries(manifest) != manifest['generatedLibraryHashes']:
        raise RuntimeError('Generated mod library set changed; create a new lab revision before benchmarking')
    if getattr(args, 'command', None) in ['prime', 'run', 'storage-crash', 'server', 'client']:
        validate_java26_inputs(manifest)
        validate_view_distance_inputs(manifest)
        validate_jvm_inputs(manifest)
        validate_cube_experiments(manifest)
    return target, manifest


def argv(manifest, client, recording=None, jfr_settings=None):
    directory = Path(manifest['client'] if client else manifest['server'])
    temporary = directory / 'java-tmp'; temporary.mkdir(exist_ok=True)
    result = [manifest['java'], *manifest.get('jvmProfile', {}).get('arguments', ['-Xms1G', '-Xmx6G']),
              '-Djava.io.tmpdir=' + str(temporary), '-Dfile.encoding=UTF-8']
    result += ['-D' + name + '=' + value for name, value in manifest.get('labProperties', {}).items()]
    if manifest.get('networkReadTimeoutSeconds',30)!=30:result.append('-Dfml.readTimeout='+str(manifest['networkReadTimeoutSeconds']))
    if tuple(int(x) for x in manifest.get('runtimeVersion', '0.6.13').split('.')[:2]) >= (0, 7):
        result.append('-Dcubiclab.cleanroom070=true')
    if recording:
        repository = directory / 'jfr-repository'; repository.mkdir(exist_ok=True)
        result += ['-XX:FlightRecorderOptions=repository=' + str(repository),
                   '-XX:StartFlightRecording=filename=' + str(recording) + ',settings=' + str(jfr_settings or 'profile')
                   + ',dumponexit=true,maxsize=' + str(manifest.get('jfrMaxSizeMiB', 256)) + 'm']
    if not client:
        product_version = manifest.get('runtimeVersion', manifest['release']['tag_name'])
        modern_args = directory / 'libraries/com/cleanroommc/cleanroom' / product_version / ('win_args.txt' if os.name == 'nt' else 'unix_args.txt')
        if modern_args.exists():
            return result + ['-Djava.awt.headless=true', '@user_jvm_args.txt', '@' + str(modern_args), 'nogui']
        return result + ['-Djava.awt.headless=true', '-jar', 'cleanroom-' + manifest.get('runtimeVersion', manifest['release']['tag_name']) + '.jar', 'nogui']
    runtime = manifest['clientRuntime']
    classpath = os.pathsep.join([runtime['minecraft']] + [library['path'] for library in runtime['libraries']])
    player_uuid = str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + PLAYER).encode()).digest(), version=3))
    return result + ['--enable-native-access=ALL-UNNAMED', '-Djava.library.path=' + runtime['natives'], '-cp', classpath,
        runtime['mainClass'], '--tweakClass', 'net.minecraftforge.fml.common.launcher.FMLTweaker',
        '--username', PLAYER, '--uuid', player_uuid, '--accessToken', '0', '--userType', 'legacy', '--versionType', 'custom',
        '--version', 'Cleanroom-' + manifest['release']['tag_name'], '--gameDir', str(directory),
        '--assetsDir', runtime['assets'], '--assetIndex', runtime['assetIndex'], '--width', '1280', '--height', '720',
        '--server', '127.0.0.1', '--port', str(manifest['port'])]


def spawn(command, directory, log, messages, lifecycle_role=None, workload_guard=None):
    launched_utc = datetime.now(timezone.utc).isoformat()
    launched_monotonic_ns = time.monotonic_ns()
    with workload_guard.launch() if workload_guard else nullcontext():
        process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace',
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        if workload_guard: workload_guard.register(process)
    lifecycle = ProcessLifecycle(process, lifecycle_role, launched_monotonic_ns, launched_utc) if lifecycle_role else None
    process.lab_lifecycle = lifecycle
    def reader():
        with log.open('w', encoding='utf-8') as stream:
            for line in process.stdout:
                if lifecycle is not None: lifecycle.observe_line(line)
                stream.write(line); stream.flush(); messages.put(line)
        messages.put(None)
    threading.Thread(target=reader, daemon=True).start()
    return process


def write_process_receipt(evidence, process):
    lifecycle = getattr(process, 'lab_lifecycle', None)
    if lifecycle is not None:
        try:
            write_json(evidence / (lifecycle.role + '-process.json'),
                {'pid': process.pid, 'command': process.args, 'lifecycle': lifecycle.report()})
        except OSError as error:
            # Optional observation persistence must not prevent owned process cleanup.
            lifecycle.receipt_failure = str(error)


def capture_process_endpoint(evidence, process, endpoint):
    lifecycle = getattr(process, 'lab_lifecycle', None)
    if lifecycle is not None:
        lifecycle.capture(endpoint)
        write_process_receipt(evidence, process)


def retain_process_lifecycles(evidence, processes, result):
    records = {}
    for process in processes:
        lifecycle = getattr(process, 'lab_lifecycle', None)
        if lifecycle is not None:
            lifecycle.close()
            write_process_receipt(evidence, process)
            records[lifecycle.role] = lifecycle.report()
    result['processLifecycle'] = records


def await_line(messages, needle, processes, timeout, workload_guard=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if workload_guard: workload_guard.require_clean()
        try: line = messages.get(timeout=.5)
        except queue.Empty:
            if any(p.poll() is not None for p in processes): raise RuntimeError('Native process exited before ' + needle)
            continue
        if line is not None:
            plain = re.sub(r'\x1b\[[0-9;]*m', '', line)
            if needle in plain: return plain
    raise TimeoutError('Timed out waiting for ' + needle)


def send(server, command):
    server.stdin.write(command + '\n'); server.stdin.flush()


def prime(args):
    target, manifest = load_lab(args)
    if manifest.get('primed'):
        return
    server_dir = Path(manifest['server']); props = server_dir / 'server.properties'
    original = props.read_text()
    temporary = re.sub(r'^level-name=.*$', 'level-name=cubiclab-bootstrap', original, flags=re.M)
    evidence = target / 'runs/prime'
    evidence.mkdir(parents=True, exist_ok=False)
    processes = []; result = {'purpose': 'Generate dependency-owned first-use inputs and restart both native processes', 'passed': False}
    try:
        props.write_text(temporary, encoding='utf-8')
        server_messages, client_messages = queue.Queue(), queue.Queue()
        server = spawn(argv(manifest, False), server_dir, evidence / 'server.log', server_messages, lifecycle_role='server'); processes.append(server)
        write_process_receipt(evidence, server)
        await_line(server_messages, 'Done (', processes, 240)
        capture_process_endpoint(evidence, server, 'ready')
        client = spawn(argv(manifest, True), Path(manifest['client']), evidence / 'client.log', client_messages, lifecycle_role='client'); processes.append(client)
        write_process_receipt(evidence, client)
        await_line(server_messages, PLAYER + ' joined the game', processes, 240)
        result['initialScreen'] = await_line(client_messages, 'CUBICLAB_CLIENT_VIEW screen=', processes, 30).strip()
        capture_process_endpoint(evidence, client, 'ready')
        for process in processes: capture_process_endpoint(evidence, process, 'endBeforeQuit')
        send(server, 'cubiclab quit'); client.wait(timeout=45)
        send(server, 'save-all'); send(server, 'stop'); server.wait(timeout=90)
        if client.returncode != 0 or server.returncode != 0: raise RuntimeError('Priming processes did not exit cleanly')
        result['passed'] = True
        manifest['primed'] = True
        manifest['generatedLibraryHashes'] = record_generated_libraries(manifest)
        write_json(target / 'lab.json', manifest)
        print('First-use client/server priming completed; generated inputs retained.', flush=True)
    except Exception as error:
        result['failure'] = str(error)
        raise
    finally:
        for process in processes: capture_process_endpoint(evidence, process, 'endBeforeQuit')
        if processes and processes[0].poll() is None:
            try: send(processes[0], 'stop'); processes[0].wait(timeout=45)
            except (OSError, subprocess.TimeoutExpired): pass
        for process in reversed(processes):
            if process.poll() is None: process.terminate(); process.wait(timeout=15)
        props.write_text(original, encoding='utf-8')
        result['processExitCodes'] = [process.returncode for process in processes]
        retain_process_lifecycles(evidence, processes, result)
        write_json(evidence / 'result.json', result)
    export_client(args)


def selected_scenarios(args, manifest):
    workload = manifest.get('benchmarkWorkload', {}).get('value')
    scenarios = args.scenarios.split(',') if args.scenarios else (workload['scenarios'].get(args.phase, []) if workload else (
        ['idle','generate','lighting-snapshot','resident','save','horizontal','vertical'] if args.phase == 'write' else ['idle','load-sync','load-async','resident']))
    if not scenarios or any(not lab_workload.scenario_valid(n) for n in scenarios): raise ValueError('No valid scenarios selected for this phase')
    if any(n in {'storage-fault-matrix','storage-content-write','storage-content-read','storage-two-worlds'} for n in scenarios) and manifest.get('labProperties',{}).get('cubiclab.storageDiagnostics')!='true':
        raise ValueError('Storage fault/content fixtures require a fresh --storage-diagnostics preparation')
    if 'route' in scenarios and (not workload or 'route' not in workload): raise ValueError('route requires a prepared workload')
    return scenarios


def run(args):
    from lab_storage_evidence import lifecycle_names, retain_lifecycle
    target, prepared = load_lab(args)
    scenarios = selected_scenarios(args, prepared)
    guard = JavaWorkloadGuard()
    guard.observe()
    preflight = target / 'runs' / ('java-workload-preflight-' + uuid.uuid4().hex + '.json')
    write_json(preflight, guard.report())
    guard.require_clean()
    prime(args)
    target, manifest = load_lab(args)
    server_dir, client_dir = Path(manifest['server']), Path(manifest['client'])
    lifecycle_before = lifecycle_names(server_dir)
    phase = args.phase
    if phase == 'write' and (server_dir / 'riven-dev/level.dat').exists(): raise RuntimeError('Write requires a fresh world')
    if phase == 'read' and not (server_dir / 'riven-dev/level.dat').exists(): raise RuntimeError('Read requires a saved fixture')
    evidence = target / 'runs' / phase
    evidence.mkdir(parents=True, exist_ok=False)
    server_messages, client_messages = queue.Queue(), queue.Queue()
    processes = []; result = {'schema': 1, 'phase': phase, 'passed': False, 'scenarios': [], 'jfr': args.jfr,
        'inputHashesBeforeLaunch': record_inputs(manifest)}
    result['scenarioSequence'] = scenarios
    result['javaWorkloadPreflight'] = {'path':str(preflight), 'sha256':sha(preflight)}
    if manifest.get('benchmarkWorkload'):
        result['benchmarkWorkload'] = {k: manifest['benchmarkWorkload'][k] for k in ['schema','id','seed','sha256']}
    result['preLaunchInputSnapshots'] = retain_prelaunch_inputs(manifest, result['inputHashesBeforeLaunch'], evidence)
    server_jfr_settings = None
    if getattr(args, 'server_jfr_settings', None):
        server_jfr_settings = evidence / 'server-settings.jfc'
        shutil.copy2(args.server_jfr_settings, server_jfr_settings)
        result['serverJfrSettings'] = {'source': str(args.server_jfr_settings.resolve()),
            'retained': str(server_jfr_settings), 'sha256': sha(server_jfr_settings),
            'scope': 'Diagnostic method tracing can change timings; not performance acceptance'}
    client_jfr_settings = None
    if getattr(args, 'client_jfr_settings', None):
        client_jfr_settings = evidence / 'client-settings.jfc'
        shutil.copy2(args.client_jfr_settings, client_jfr_settings)
        result['clientJfrSettings'] = {'source': str(args.client_jfr_settings.resolve()),
            'retained': str(client_jfr_settings), 'sha256': sha(client_jfr_settings),
            'scope': 'Diagnostic method tracing can change timings; not performance acceptance'}
    try:
        guard.start()
        server_command = argv(manifest, False, evidence / 'server.jfr' if args.jfr else None, server_jfr_settings)
        server = spawn(server_command, server_dir, evidence / 'server.log', server_messages, lifecycle_role='server', workload_guard=guard); processes.append(server)
        write_process_receipt(evidence, server)
        await_line(server_messages, 'Done (', processes, 240, workload_guard=guard)
        capture_process_endpoint(evidence, server, 'ready')
        client_command = argv(manifest, True, evidence / 'client.jfr' if args.jfr else None, client_jfr_settings)
        client = spawn(client_command, client_dir, evidence / 'client.log', client_messages, lifecycle_role='client', workload_guard=guard); processes.append(client)
        write_process_receipt(evidence, client)
        await_line(server_messages, PLAYER + ' joined the game', processes, 240, workload_guard=guard)
        await_line(client_messages, 'CUBICLAB_CLIENT_VIEW screen=none', processes, 45, workload_guard=guard)
        capture_process_endpoint(evidence, client, 'ready')
        print('Real native client joined the loopback server.', flush=True)
        for scenario in scenarios:
            start_request = {'scenario': scenario, 'requestedUtc': datetime.now(timezone.utc).isoformat(),
                             'timeoutSeconds': args.scenario_start_timeout, 'acknowledged': False}
            result.setdefault('scenarioStartRequests', []).append(start_request)
            request_started = time.monotonic()
            try:
                send(server, 'cubiclab run ' + scenario)
                started = await_line(server_messages, 'CUBICLAB_STARTED', processes,
                                     args.scenario_start_timeout, workload_guard=guard)
                if 'scenario=' + scenario not in started.split():
                    raise RuntimeError('Unexpected scenario acknowledgement: ' + started.strip())
                start_request.update(acknowledged=True, receipt=started.strip(),
                                     acknowledgedUtc=datetime.now(timezone.utc).isoformat())
            finally:
                start_request['waitSeconds'] = time.monotonic() - request_started
            run_id = re.search(r'runId=([0-9a-f-]{36})', started)[1]
            start_request['runId'] = run_id
            view_timeout = int(manifest.get('labProperties', {}).get('cubiclab.fullViewTimeoutSeconds', '840'))
            ended = await_line(server_messages, 'CUBICLAB_SERVER_RESULT runId=' + run_id, processes,
                               manifest['benchmarkWorkload']['value'].get('timeoutSeconds',840)+90 if manifest.get('benchmarkWorkload') else
                               view_timeout + 90 if scenario == 'full-view' else
                               int(manifest.get('labProperties',{}).get('cubiclab.streamTicks','240')) * .15 + 90 if scenario in ('horizontal','vertical') else
                               210, workload_guard=guard)
            if ' PASS' not in ended: raise RuntimeError('Scenario failed: ' + ended.strip())
            client_wait_started=time.monotonic()
            try:
                await_line(server_messages, 'CUBICLAB_CLIENT_RESULT runId=' + run_id, processes, args.client_report_timeout, workload_guard=guard)
            except TimeoutError:
                diagnostic_tool=Path(manifest['java']).with_name('jcmd.exe' if os.name=='nt' else 'jcmd')
                for name,arguments in [('client-timeout-threads.txt',['Thread.print']),('client-timeout-jfr.txt',['JFR.dump','filename='+str(evidence/'client-timeout.jfr')])]:
                    try:
                        with (evidence/name).open('w',encoding='utf-8') as diagnostic_log:
                            subprocess.run([str(diagnostic_tool),str(client.pid),*arguments],stdout=diagnostic_log,stderr=subprocess.STDOUT,timeout=20,check=False)
                    except (OSError,subprocess.TimeoutExpired):pass
                raise
            server_data = json.loads((server_dir / 'cubiclab-results' / run_id / 'server.json').read_text())
            client_data = json.loads((client_dir / 'cubiclab-results' / run_id / 'client.json').read_text())
            validate_view_distance_capture(manifest, server_data, client_data)
            validate_jvm_capture(server_data, client_data, manifest)
            from lab_storage_evidence import validate_complete_ticks
            validate_complete_ticks(manifest,server_data)
            lab_workload.validate_capture(manifest, server_data, client_data)
            if 'seed' in manifest and server_data.get('worldSeed') != manifest['seed']:
                raise RuntimeError('Native world seed differs from preparation')
            if not client_data['frameIntervals']['count']: raise RuntimeError('No real rendered frames captured')
            if client_data['framesWithGui']: raise RuntimeError('A GUI covered the world during measurement; preserve this run and resolve first-use setup')
            if client_data.get('framesWithOverlay',0) or client_data.get('overlayProbeFailure'):
                raise RuntimeError('A known title/arrival overlay covered measurement or could not be observed; retain this failed capture')
            if scenario in ('horizontal', 'vertical') and not client_data['packets']['cubeCount']:
                raise RuntimeError('Streaming test received no cubes')
            row = {'scenario': scenario, 'runId': run_id, 'server': server_data, 'client': client_data,
                   'scenarioStartRequest': dict(start_request),
                   'clientReportTimeoutSeconds':args.client_report_timeout,'clientReportWaitSeconds':time.monotonic()-client_wait_started}
            result['scenarios'].append(row)
            # Retain raw chronology from both JVMs, without moving timed writes into the capture.
            for side, directory in [('server', server_dir), ('client', client_dir)]:
                source = directory / 'cubiclab-results' / run_id
                for path in [*source.glob('*.csv'), *source.glob('*-route-camera.json')]:
                    destination = evidence / 'raw' / run_id / side / path.name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(path, destination)
            print(f'{scenario}: PASS; {"complete server tick" if "completeServerTicks" in server_data else "Forge-event tick"} p95={server_data.get("completeServerTicks",server_data["serverTicks"])["p95Ms"]:.2f} ms, '
                  f'frame p95={client_data["frameIntervals"]["p95Ms"]:.2f} ms, cubes={client_data["packets"]["cubeCount"]}', flush=True)
        for process in processes: capture_process_endpoint(evidence, process, 'endBeforeQuit')
        send(server, 'tp ' + PLAYER + ' 0 80 0'); time.sleep(2)
        send(server, 'cubiclab quit'); client.wait(timeout=45)
        send(server, 'save-all'); send(server, 'stop'); server.wait(timeout=90)
        result['clientExitCode'] = client.returncode; result['serverExitCode'] = server.returncode
        result['passed'] = client.returncode == 0 and server.returncode == 0
    except Exception as error:
        result['failure'] = str(error)
        raise
    finally:
        try: guard.close()
        except Exception as error: guard.issue('observer-close-failed', str(error))
        result['hostWorkloadGuard'] = guard.report()
        if not result['hostWorkloadGuard']['passed']:
            result['passed'] = False
            result.setdefault('failure', 'Java workload guard excluded the native timing run')
        for process in processes: capture_process_endpoint(evidence, process, 'endBeforeQuit')
        if processes and processes[0].poll() is None:
            try:
                send(processes[0], 'cubiclab cancel'); time.sleep(.15)
                send(processes[0], 'cubiclab quit'); send(processes[0], 'save-all'); send(processes[0], 'stop')
                processes[0].wait(timeout=45)
            except (OSError, subprocess.TimeoutExpired): pass
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
        retain_process_lifecycles(evidence, processes, result)
        retain_incomplete_scenarios(result, server_dir, client_dir, evidence)
        result['storageLifecycleFiles'] = retain_lifecycle(server_dir, lifecycle_before, evidence)
        write_json(evidence / 'result.json', result)
    print(evidence / 'result.json')
    return 0 if result['passed'] else 1


def retain_incomplete_scenarios(result, server_dir, client_dir, evidence):
    """Keep failed/partial reports without adding them to validated scenarios."""
    completed_ids = {row['runId'] for row in result['scenarios']}
    incomplete = []
    for request in result.get('scenarioStartRequests', []):
        run_id = request.get('runId')
        if not run_id or run_id in completed_ids:
            continue
        row = {'scenario': request['scenario'], 'runId': run_id,
               'validationStatus': 'incomplete-or-failed', 'readErrors': []}
        for side, directory in [('server', server_dir), ('client', client_dir)]:
            source = directory / 'cubiclab-results' / run_id
            try:
                path = source / (side + '.json')
                if path.is_file():
                    row[side] = json.loads(path.read_text(encoding='utf-8'))
                for path in [*source.glob('*.json'), *source.glob('*.csv')]:
                    destination = evidence / 'raw' / run_id / side / path.name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(path, destination)
            except (OSError, ValueError) as error:
                row['readErrors'].append(side + ': ' + str(error))
        incomplete.append(row)
    result['incompleteScenarios'] = incomplete


def survey(args):
    """Resume an explicitly bounded native pregeneration job using the reviewed launcher."""
    operation = getattr(args, 'native_operation', 'pregenerate')
    if operation != 'pregenerate': raise ValueError('Unsupported native bulk operation')
    terrain = operation == 'pregenerate'
    prefix = 'CUBICLAB_PREGEN_' if terrain else 'CUBICLAB_SURVEY_'
    prime(args)
    target, manifest = load_lab(args)
    evidence = target / 'runs' / (operation + '-' + args.survey_id + '-' + uuid.uuid4().hex[:8])
    evidence.mkdir(parents=True, exist_ok=False)
    messages = queue.Queue(); processes = []
    result = {'passed': False, 'inputHashesBeforeLaunch': record_inputs(manifest),
              'scope': ('Native LIGHT/save/unload job; independent saved coverage and client inspection remain separate.' if terrain else 'Native generated and saved cubes; population/lighting and settled gameplay costs are separate.')}
    if terrain: result['preLaunchInputSnapshots'] = retain_prelaunch_inputs(manifest, result['inputHashesBeforeLaunch'], evidence)
    try:
        command = argv(manifest, False, evidence / 'server.jfr' if args.jfr else None)
        server = spawn(command, Path(manifest['server']), evidence / 'server.log', messages); processes.append(server)
        write_json(evidence / 'server-process.json', {'pid': server.pid, 'command': command})
        await_line(messages, 'Done (', processes, 300)
        send(server, f'cubiclab {operation} {args.survey_id} {args.survey_origin_x} {args.survey_origin_z} {args.survey_width} {args.survey_height} {args.survey_max_columns}')
        await_line(messages, prefix + 'STARTED', processes, 30)
        started = time.monotonic(); last_progress = started
        while True:
            try: line = messages.get(timeout=1)
            except queue.Empty:
                if server.poll() is not None: raise RuntimeError('Native survey server exited')
                if time.monotonic()-last_progress > 900: raise TimeoutError('No durable survey progress for 15 minutes')
                continue
            if line is None: continue
            plain = re.sub(r'\x1b\[[0-9;]*m', '', line)
            if prefix + 'PROGRESS' in plain:
                last_progress=time.monotonic(); print(plain.strip(),flush=True)
            if prefix + 'RESULT' in plain:
                result['receipt']=plain.strip()
                if ' PASS ' not in plain: raise RuntimeError(plain.strip())
                break
        result['seconds']=time.monotonic()-started
        if terrain:
            result['inputHashesAfterWork'] = record_inputs(manifest)
            for key in ('mods', 'controllerSources', 'generationInputsSha256'):
                if result['inputHashesBeforeLaunch'][key] != result['inputHashesAfterWork'][key]:
                    raise RuntimeError('Pregeneration inputs changed during native work: ' + key)
        send(server,'save-all');send(server,'stop');server.wait(timeout=180)
        result['serverExitCode']=server.returncode;result['passed']=server.returncode==0
        result['surveyDirectory']=str(Path(manifest['server'])/('cubiclab-pregeneration' if terrain else 'cubiclab-surveys')/args.survey_id)
        if terrain:
            checkpoint = json.loads((Path(result['surveyDirectory'])/'checkpoint.json').read_text())
            result['mapComplete'] = checkpoint['complete']
            result['committedColumns'] = checkpoint['committedColumns']
    except Exception as error:
        result['failure']=str(error);raise
    finally:
        if processes and processes[0].poll() is None:
            try:
                send(processes[0],'cubiclab cancel');send(processes[0],'stop');processes[0].wait(timeout=180)
            except (OSError,subprocess.TimeoutExpired):pass
        for process in processes:
            if process.poll() is None: process.terminate();process.wait(timeout=20)
        write_json(evidence/'result.json',result)
    print(evidence/'result.json',flush=True)
    return 0 if result['passed'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prime', 'export', 'run', 'storage-crash', 'server', 'client'])
    parser.add_argument('--crash-id', help='New bounded identity for an isolated native process-crash fixture.')
    parser.add_argument('--crash-boundary', choices=['payloadForce','headerForce','overflow-rename','after-barrier'])
    parser.add_argument('--riven-workspace', type=Path, default=FORK.parents[1])
    parser.add_argument('--name', required=True)
    parser.add_argument('--phase', choices=['write', 'read'], default='write')
    parser.add_argument('--scenarios', help='Comma-separated scenario override; prerequisites still apply')
    parser.add_argument('--jfr', action='store_true')
    parser.add_argument('--server-jfr-settings', type=Path,
                        help='Opt-in server diagnostic JFC for run --jfr; retained and hashed.')
    parser.add_argument('--client-jfr-settings', type=Path,
                        help='Opt-in client diagnostic JFC for run --jfr; retained and hashed.')
    parser.add_argument('--client-report-timeout',type=int,default=30,choices=range(30,181),metavar='30..180',help='Bounded client report deadline; timeout retains owned-client thread/JFR diagnostics. Does not relax frame, GUI or view-distance checks.')
    parser.add_argument('--scenario-start-timeout',type=int,default=10,choices=range(10,181),metavar='10..180',help='Bounded command acknowledgement wait; default 10 seconds. Retains request/wait receipts, including failure. Does not change the native job deadline; startup latency remains separate evidence.')
    parser.add_argument('--survey-id',default='pregeneration',help='Stable journal name; unchanged bounds and settings permit restart and resume.')
    parser.add_argument('--survey-origin-x',type=int,default=-625,help='Minimum chunk X, inclusive; default -10000 blocks.')
    parser.add_argument('--survey-origin-z',type=int,default=-625,help='Minimum chunk Z, inclusive.')
    parser.add_argument('--survey-width',type=int,default=1250,help='Chunk columns; 1250 gives 20000 blocks.')
    parser.add_argument('--survey-height',type=int,default=1250)
    parser.add_argument('--survey-max-columns',type=int,default=1562500,help='Stop cleanly after this many additional columns; resume the same journal later.')
    args = parser.parse_args(); args.riven_workspace = args.riven_workspace.resolve()
    for field in ['server_jfr_settings', 'client_jfr_settings']:
        selected = getattr(args, field)
        if selected:
            selected = selected.resolve()
            setattr(args, field, selected)
            if args.command != 'run' or not args.jfr:
                parser.error('--' + field.replace('_', '-') + ' requires run --jfr')
            if not selected.is_file() or any(c in str(selected) for c in ',\r\n'):
                parser.error('JFC must be an existing file with no comma/newline in its path')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', args.name): parser.error('Invalid lab name')
    if args.scenarios and any(not lab_workload.scenario_valid(s) for s in args.scenarios.split(',')):
        parser.error('Unknown scenario in --scenarios')
    if args.command == 'prime':
        with native_measurement_lease(args.riven_workspace,args.name): prime(args)
        return
    if args.command == 'export': export_client(args); return
    if args.command == 'storage-crash':
        from lab_storage_crash import run as storage_crash
        with native_measurement_lease(args.riven_workspace,args.name):raise SystemExit(storage_crash(args))
    if args.command == 'run':
        with native_measurement_lease(args.riven_workspace,args.name):
            raise SystemExit(run(args))
    _, manifest = load_lab(args)
    client = args.command == 'client'
    raise SystemExit(subprocess.call(argv(manifest, client), cwd=manifest['client' if client else 'server']))


if __name__ == '__main__': main()
