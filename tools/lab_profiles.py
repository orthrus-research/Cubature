"""The lab's reviewed instance profiles: the JVM, cube experiments and view distance that owned
preparations configure, and their checks before launch and in each capture."""
from pathlib import Path
import re

VIEW_DISTANCE = 12
# Cubature's CubicChunksConfig.DeliveryMode, in stage order.
DELIVERY_MODES = ('BASELINE', 'MAINTENANCE', 'SAVE', 'PREFETCH')
JVM_ARGUMENTS = ['-Xms1G', '-Xmx8G', '-XX:+UseZGC']


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
    if args.delivery_mode not in DELIVERY_MODES:
        raise ValueError('Delivery mode must be one of ' + ', '.join(DELIVERY_MODES))
    values = {'deliveryMode': ('S', args.delivery_mode),
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
        actual = re.findall(r'^\s*[BIS]:' + re.escape(name) + r'=(.*?)\s*$', path.read_text(), flags=re.M)
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
