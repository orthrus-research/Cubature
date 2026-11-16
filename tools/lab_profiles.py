"""The lab's reviewed instance profiles: the JVM, cube experiments and view distance that owned
preparations configure, and their checks before launch and in each capture."""
from pathlib import Path
import re

# The baseline view distance, in cubes: the server's horizontal and vertical distances and the
# client's render distance. A run may raise any of them by exception; none goes below it.
VIEW_DISTANCE = 12
VIEW_DISTANCE_KEYS = ('horizontal', 'vertical', 'clientRender')
# Above these the game clamps the value: the server's view distances to 32, Cubature's render distance to 64.
VIEW_DISTANCE_LIMITS = {'horizontal': 32, 'vertical': 32, 'clientRender': 64}
# Named profiles below the baseline, chosen only when an instance is prepared. Their values are exact:
# an instance prepared with one is never raised, and its runs are never compared with baseline runs.
BASELINE_VIEW_PROFILE = 'baseline'
REDUCED_VIEW_PROFILES = {'near-8': {'horizontal': 8, 'vertical': 8, 'clientRender': 8}}
# Cubature's CubicChunksConfig.DeliveryMode, in stage order.
DELIVERY_MODES = ('BASELINE', 'MAINTENANCE', 'SAVE', 'PREFETCH')
JVM_ARGUMENTS = ['-Xms1G', '-Xmx8G', '-XX:+UseZGC']


JVM_VARIANTS = ['zgc', 'zgc-fixed', 'zgc-pretouch', 'g1', 'g1-fixed', 'g1-pretouch', 'g1-diag',
                'g1-p50', 'g1-egc', 'g1-ship8', 'g1-ship12', 'g1-ship8-nmt']
G1_LOG = '-Xlog:gc*,gc+heap=debug:file=gc.log:time,uptime,level,tags'
# G1 shipping candidates, each with the GC log: the shipping flags on the 8 GiB floor and on 12 GiB.
SHIPPING_G1 = ['-XX:G1HeapRegionSize=4m', '-XX:MaxGCPauseMillis=50', '-XX:+ExplicitGCInvokesConcurrent']
G1_EXPERIMENTS = {
    'g1-p50': ['-XX:MaxGCPauseMillis=50'],
    'g1-egc': ['-XX:+ExplicitGCInvokesConcurrent'],
    'g1-ship8': SHIPPING_G1,
    'g1-ship12': SHIPPING_G1,
    # The shipping profile with native memory tracking, which costs a little CPU; captures then
    # record each process's NMT summary after every scenario.
    'g1-ship8-nmt': SHIPPING_G1 + ['-XX:NativeMemoryTracking=summary'],
}
HEAP_MIB = {'g1-ship12': 12288}
# Environment variable that selects a variant for a newly prepared standalone instance.
VARIANT_ENVIRONMENT = 'CUBATURE_LAB_JVM'
# Newly prepared instances measure what ships: G1 with the shipping flags on the 8 GiB floor.
DEFAULT_VARIANT = 'g1-ship8'


def jvm_profile(variant=DEFAULT_VARIANT):
    if variant not in JVM_VARIANTS: raise ValueError('Unknown JVM experiment')
    maximum = HEAP_MIB.get(variant, 8192)
    initial = 1024 if '-' not in variant else maximum
    collector = 'G1' if variant.startswith('g1') else 'ZGC'
    arguments = [f'-Xms{initial}M', f'-Xmx{maximum // 1024}G', '-XX:+UseG1GC' if collector == 'G1' else '-XX:+UseZGC']
    # Keep the established default manifest/argfile representation stable.
    if variant == 'zgc': arguments = list(JVM_ARGUMENTS)
    if variant.endswith('-pretouch'): arguments.append('-XX:+AlwaysPreTouch')
    # G1 with a GC log beside each process: heap after every collection, for memory budgets.
    if variant == 'g1-diag' or variant in G1_EXPERIMENTS: arguments.append(G1_LOG)
    arguments.extend(G1_EXPERIMENTS.get(variant, []))
    return {'initialHeapMiB': initial, 'maxHeapMiB': maximum, 'collector': collector, 'arguments': arguments}


def tracks_native_memory(manifest):
    return any(a.startswith('-XX:NativeMemoryTracking=') for a in manifest.get('jvmProfile', {}).get('arguments', []))


def configure_jvm(server, profile=None):
    # The installer argfile is expanded after command-line flags and can override them.
    (server / 'user_jvm_args.txt').write_text(
        '# Owned lab profile: same heap and collector on server and client.\n'
        + '\n'.join((profile or jvm_profile())['arguments']) + '\n', encoding='utf-8')


def validate_jvm_inputs(manifest):
    profile = manifest.get('jvmProfile')
    if profile not in [jvm_profile(variant) for variant in JVM_VARIANTS]:
        raise RuntimeError('Native testing requires a reviewed equal-heap JVM profile; prepare a fresh instance')
    path = Path(manifest['server']) / 'user_jvm_args.txt'
    arguments = [line.strip() for line in path.read_text().splitlines()
                 if line.strip() and not line.lstrip().startswith('#')]
    if arguments != profile['arguments']:
        raise RuntimeError('Prepared server JVM arguments changed; prepare a fresh instance')


def validate_jvm_capture(server, client, manifest=None):
    profile = (manifest or {}).get('jvmProfile', jvm_profile())
    collector = profile['collector']
    for side, capture in [('server', server), ('client', client)]:
        runtime = capture.get('runtime', {})
        if runtime.get('maxHeapBytes') != profile['maxHeapMiB'] * 1024 * 1024:
            raise RuntimeError('Effective ' + side + ' heap differs from ' + str(profile['maxHeapMiB']) + ' MiB')
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


def view_distance_profile(horizontal=None, vertical=None, client_render=None):
    """The view distances of a run: the baseline, or any of them raised by exception."""
    profile = {'horizontal': VIEW_DISTANCE if horizontal is None else horizontal,
               'vertical': VIEW_DISTANCE if vertical is None else vertical,
               'clientRender': VIEW_DISTANCE if client_render is None else client_render}
    check_view_distance_profile(profile)
    return profile


def view_profile_name(manifest):
    """The instance's named view profile: 'baseline', the default for every instance that names none."""
    return manifest.get('viewProfile', BASELINE_VIEW_PROFILE)


def reduced_view_profile(name):
    if name not in REDUCED_VIEW_PROFILES:
        raise RuntimeError('No reduced view profile named ' + repr(name) + '; known: '
                           + ', '.join(sorted(REDUCED_VIEW_PROFILES)))
    return dict(REDUCED_VIEW_PROFILES[name])


def check_view_distance_profile(profile, view_profile=BASELINE_VIEW_PROFILE):
    """Refuses a profile that is not one: a missing or extra key, a value that is not a whole number,
    one below the baseline, or one above what the game would clamp it to. A named reduced profile
    instead allows exactly its own values."""
    if view_profile != BASELINE_VIEW_PROFILE:
        if profile != reduced_view_profile(view_profile):
            raise RuntimeError(f'The reduced view profile {view_profile} is exactly '
                               f'{reduced_view_profile(view_profile)}; this instance records {profile!r}')
        return
    if not isinstance(profile, dict) or set(profile) != set(VIEW_DISTANCE_KEYS):
        raise RuntimeError('This lab has no view-distance profile; preserve this older lab and prepare a new instance')
    for key in VIEW_DISTANCE_KEYS:
        value = profile[key]
        if type(value) is not int:
            raise RuntimeError(f'View distance {key} is not a whole number: {value!r}')
        if value < VIEW_DISTANCE:
            raise RuntimeError(f'View distance {key} {value} is below the baseline {VIEW_DISTANCE}; '
                               'preserve this older lab and prepare a new instance')
        if value > VIEW_DISTANCE_LIMITS[key]:
            raise RuntimeError(f'View distance {key} {value} is above what the game allows, '
                               f'{VIEW_DISTANCE_LIMITS[key]}')


def is_baseline_view_distance(profile):
    return profile == view_distance_profile()


def view_distance_label(profile, view_profile=BASELINE_VIEW_PROFILE):
    """How a run's view distances read in its records: 'baseline', what was raised, or the reduced profile."""
    if view_profile != BASELINE_VIEW_PROFILE:
        return (f'reduced {view_profile}: ' + ', '.join(f'{key} {profile[key]}' for key in VIEW_DISTANCE_KEYS)
                + f' (baseline {VIEW_DISTANCE})')
    if is_baseline_view_distance(profile):
        return 'baseline'
    raised = [f'{key} {profile[key]}' for key in VIEW_DISTANCE_KEYS if profile[key] != VIEW_DISTANCE]
    return 'raised: ' + ', '.join(raised) + f' (baseline {VIEW_DISTANCE})'


def _set_vertical(config, vertical):
    text = config.read_text()
    setting = f'    I:verticalCubeLoadDistance={vertical}'
    if re.search(r'^\s*I:verticalCubeLoadDistance=.*$', text, flags=re.M):
        text = re.sub(r'^\s*I:verticalCubeLoadDistance=.*$', setting, text, flags=re.M)
    else:
        text, count = re.subn(r'general\s*\{', 'general {\n' + setting, text, count=1)
        if count != 1: raise RuntimeError('Prepared CubicChunks config has no general category')
    config.write_text(text, encoding='utf-8')


def configure_view_distance(server, profile=None, view_profile=BASELINE_VIEW_PROFILE):
    """Applies a run's server distances (the baseline unless given) to a preparation directory."""
    profile = view_distance_profile() if profile is None else profile
    check_view_distance_profile(profile, view_profile)
    properties = server / 'server.properties'
    text = re.sub(r'^view-distance=.*\n?', '', properties.read_text(), flags=re.M)
    properties.write_text(text + f'view-distance={profile["horizontal"]}\n', encoding='utf-8')
    _set_vertical(server / 'config/cubicchunks.cfg', profile['vertical'])


def client_options(profile=None):
    profile = view_distance_profile() if profile is None else profile
    check_view_distance_profile(profile)
    return (f'renderDistance:{profile["clientRender"]}\nmaxFps:120\nenableVsync:false\nfancyGraphics:false\n'
            'renderClouds:false\nfullscreen:false\npauseOnLostFocus:false\nshowSubtitles:false\ntutorialStep:none\n')


def apply_view_distance(manifest, profile):
    """Sets a prepared instance's view distances for the next run, and records them in its manifest:
    the server's view-distance and vertical distance, the client's render distance, and the client's
    vertical distance, which follows the server's. Returns whether anything changed. An instance
    prepared with a reduced profile keeps it: asking it for anything else is refused."""
    name = view_profile_name(manifest)
    if name != BASELINE_VIEW_PROFILE and profile != reduced_view_profile(name):
        raise RuntimeError(f'This instance was prepared with the reduced view profile {name}; its distances '
                           'are fixed, so a run may not set them')
    check_view_distance_profile(profile, name)
    if manifest.get('viewDistance') == profile:
        return False
    _write_view_distance(manifest, profile, name)
    manifest['viewDistance'] = dict(profile)
    manifest['viewDistanceLabel'] = view_distance_label(profile, name)
    return True


def choose_view_profile(manifest, name):
    """At preparation: gives a fresh instance a named view profile, writing its distances and
    recording it as viewProfile. The baseline is every instance's already, so naming it changes
    nothing."""
    if name in (None, BASELINE_VIEW_PROFILE):
        return
    profile = reduced_view_profile(name)
    _write_view_distance(manifest, profile, name)
    manifest['viewProfile'] = name
    manifest['viewDistance'] = profile
    manifest['viewDistanceLabel'] = view_distance_label(profile, name)


def require_same_view_profile(*manifests):
    """Refuses to compare runs whose view profiles or distances differ."""
    seen = {(view_profile_name(m), repr(m.get('viewDistance'))) for m in manifests}
    if len(seen) > 1:
        raise RuntimeError('Runs of different view profiles are not compared: '
                           + '; '.join(f'{name} {distances}' for name, distances in sorted(seen)))


def _write_view_distance(manifest, profile, view_profile):
    server, client = Path(manifest['server']), Path(manifest['client'])
    configure_view_distance(server, profile, view_profile)
    _set_vertical(client / 'config/cubicchunks.cfg', profile['vertical'])
    options = client / 'options.txt'
    text = options.read_text()
    text, count = re.subn(r'^renderDistance:\d+[ \t]*$', f'renderDistance:{profile["clientRender"]}', text, flags=re.M)
    if count != 1:
        raise RuntimeError('Client options have no single renderDistance in ' + str(options))
    options.write_text(text, encoding='utf-8')


def validate_view_distance_inputs(manifest):
    profile = manifest.get('viewDistance')
    check_view_distance_profile(profile, view_profile_name(manifest))
    server, client = Path(manifest['server']), Path(manifest['client'])
    checks = [(server / 'server.properties', r'^view-distance=(\d+)\s*$', profile['horizontal']),
              (client / 'options.txt', r'^renderDistance:(\d+)\s*$', profile['clientRender'])]
    checks += [(directory / 'config/cubicchunks.cfg', r'^\s*I:verticalCubeLoadDistance=(\d+)\s*$',
                profile['vertical']) for directory in [server, client]]
    for path, pattern, expected in checks:
        values = re.findall(pattern, path.read_text(), flags=re.M)
        if values != [str(expected)]:
            raise RuntimeError('Test view distance changed or is ambiguous in ' + str(path) + '; prepare a new revision')


def validate_view_distance_capture(manifest, server, client):
    expected = manifest.get('viewDistance')
    check_view_distance_profile(expected, view_profile_name(manifest))
    observed = {'horizontal': server.get('horizontalViewDistance'), 'vertical': server.get('verticalViewDistance'),
                'clientRender': client.get('renderDistance')}
    if observed != expected or client.get('verticalViewDistance') != expected['vertical']:
        raise RuntimeError('Measured view distances differ from the run\'s ' + str(expected) + ': ' + str(observed)
                           + '; client vertical=' + str(client.get('verticalViewDistance')))
