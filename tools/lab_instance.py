"""A prepared lab instance: runtime inputs fetched against their hashes, the client prepared and
packaged, its inputs recorded, and the instance loaded and checked, then primed by a first launch."""
from concurrent.futures import ThreadPoolExecutor
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
import urllib.request
import uuid
import zipfile
from lab_files import sha, write_json
from lab_process import (PLAYER, argv, await_line, capture_process_endpoint, retain_process_lifecycles, send, spawn,
                         write_process_receipt)
from lab_profiles import client_options, validate_cube_experiments, validate_jvm_inputs, validate_view_distance_inputs
import lab_workload

FORK = Path(__file__).resolve().parents[1]


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
