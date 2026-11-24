"""A Workbench driver session as the lab's tools see it: the instance, its command lines and the session folder.

Workbench builds the instance from a launch file, takes the GPU lease and quiet, and runs the launch's declared driver
(`wb play --driver <name>`, lab.run kind=driver) with the session's variables:

- WB_INSTANCE: the instance's receipt, instance.json (wb.lab.instance/1): every file by SHA-256 and the server's and
  client's command lines, with paths under {instance}, {cache} (the runtime cache) and {jdk:N};
- WB_SESSION: the session folder, which holds session.json (wb.lab.session/1): the JDK the session checked the
  instance against, the session's port and its other inputs;
- WB_LAUNCH: the launch file.

A driver starts its own JVMs from the receipt's command lines, with the roots and {port} filled in, and keeps what it
measures in the session folder. Nothing here assembles an instance and nothing takes a lease: Workbench verified the
instance against its receipt and holds the lease for the whole session. A measurement works on a fresh copy of the
instance in the session folder (copy), so the named instance keeps no measurement's changes; a resumable job, such as
a pregeneration, works on the instance itself.
"""
from __future__ import annotations

import json
import os
import re
import shutil
from pathlib import Path

from lab_files import sha, write_json
from lab_process import PLAYER

INSTANCE_SCHEMA = 'wb.lab.instance/1'
SESSION_SCHEMA = 'wb.lab.session/1'
RECEIPT = 'instance.json'
SESSION = 'session.json'
ENV = {'instance': 'WB_INSTANCE', 'session': 'WB_SESSION', 'launch': 'WB_LAUNCH'}
# Where a runtime cache may be when Workbench does not name it (WB_RUNTIME_CACHE): under the lab home's out root or
# Workbench's state folder. A candidate is taken only when it holds the receipt's asset index by its SHA-256.
CACHE_ENV = 'WB_RUNTIME_CACHE'
CACHE_CANDIDATES = (('WB_LAB_HOME', 'out/cache/runtime'), ('WB_STATE_DIR', 'cache/runtime'))
# What the game only reads, shared by hard link with the instance a copy is made from (as Workbench's own instances
# share them with their template); everything else is copied.
LINKED = ('server/libraries', 'server/mods', 'client/mods', 'client/natives')
# Never copied: what a game left behind in the named instance.
LEFT_BEHIND = ('logs', 'crash-reports', 'screenshots', 'java-tmp', 'cubiclab-results', 'jfr-repository')
SIDES = ('server', 'client')
PLACEHOLDER = re.compile(r'\{([a-z]+(?::[0-9]+)?)\}')


class SessionError(RuntimeError):
    pass


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def runtime_cache(receipt, environ=os.environ):
    """The runtime cache the receipt's {cache} paths are under: WB_RUNTIME_CACHE, else a candidate that holds the
    receipt's asset index by its SHA-256."""
    index = receipt['runtime']['asset_index']['path']
    digest = receipt['files'][index]['sha256']
    relative = index[len('{cache}/'):]
    named = environ.get(CACHE_ENV)
    candidates = [Path(named)] if named else [Path(environ[name]) / below for name, below in CACHE_CANDIDATES
                                                if environ.get(name)]
    for root in candidates:
        path = root / relative
        if path.is_file() and sha(path) == digest:
            return root
    if named:
        raise SessionError(f'{CACHE_ENV} names {named}, which does not hold the receipt\'s asset index {relative}')
    raise SessionError('no runtime cache holds the receipt\'s asset index ' + relative + '; name the cache in '
                       + CACHE_ENV)


def expand(argv, roots):
    """A receipt's command line with its roots ({instance}, {cache}, {jdk:N}) and {port} filled in."""
    def one(match):
        name = match.group(1)
        if name not in roots:
            raise SessionError('the receipt names {' + name + '}, which this session does not fill')
        return str(roots[name]).rstrip('/\\')
    return [PLACEHOLDER.sub(one, str(token)) for token in argv]


def level_name(properties):
    found = re.findall(r'^level-name=(.*)$', Path(properties).read_text(encoding='utf-8'), re.M)
    return found[-1].strip() if found else 'world'


def copy_instance(source, target):
    """A copy of the instance ``source`` in ``target``: the game's read-only files hard-linked, everything else
    copied, without the world and what earlier games left behind. Returns ``target``."""
    source, target = Path(source), Path(target)
    if target.exists():
        raise SessionError('the copy\'s folder exists already: ' + str(target))
    skip = {('server', level_name(source / 'server/server.properties'))}
    for side in SIDES:
        skip.update((side, name) for name in LEFT_BEHIND)
    for directory, folders, files in os.walk(source):
        here = Path(directory)
        relative = here.relative_to(source)
        folders[:] = [f for f in folders if tuple((relative / f).parts) not in skip]
        (target / relative).mkdir(parents=True, exist_ok=True)
        for name in files:
            path, made = here / name, target / relative / name
            key = (relative / name).as_posix()
            if name.endswith('.jar') or any(key.startswith(prefix + '/') for prefix in LINKED):
                try:
                    os.link(path, made)
                    continue
                except OSError:
                    pass  # another volume: a copy
            shutil.copy2(path, made)
    return target


class Session:
    """The driver session Workbench runs this process in (from_environment), on its instance or a copy of it."""

    def __init__(self, receipt_path, folder, launch_path=None, cache=None, instance=None):
        self.receipt_path = Path(receipt_path)
        self.folder = Path(folder)
        self.receipt = read_json(self.receipt_path)
        if self.receipt.get('schema') != INSTANCE_SCHEMA:
            raise SessionError(f'{self.receipt_path} is not an instance receipt ({INSTANCE_SCHEMA})')
        self.record = read_json(self.folder / SESSION)
        if self.record.get('schema') != SESSION_SCHEMA:
            raise SessionError(f'{self.folder / SESSION} is not a session record ({SESSION_SCHEMA})')
        if self.record['instance']['receipt_sha256'] != sha(self.receipt_path):
            raise SessionError('the session ran on another receipt than ' + str(self.receipt_path))
        self.launch_path = Path(launch_path) if launch_path else self.folder / 'launch.json'
        self.launch = read_json(self.launch_path)
        if sha(self.launch_path) != self.receipt['launch']['sha256']:
            raise SessionError('the launch file is not the one the instance was built from')
        self.instance = Path(instance) if instance else self.receipt_path.parent
        (major, _), = self.receipt['jdk'].items()
        self.cache = Path(cache) if cache else runtime_cache(self.receipt)
        self.roots = {'instance': self.instance, 'cache': self.cache, f'jdk:{major}': Path(self.record['jdk']['home']),
                      'port': self.record['inputs']['port']}
        if self.receipt['player']['name'] != PLAYER:
            raise SessionError(f'the instance\'s player is {self.receipt["player"]["name"]}; the lab drives {PLAYER}')

    @classmethod
    def from_environment(cls, environ=os.environ):
        missing = [name for name in (ENV['instance'], ENV['session']) if not environ.get(name)]
        if missing:
            raise SessionError('not in a driver session (no ' + ', '.join(missing) + '): run this as the launch\'s '
                               'driver, wb play --driver <name>')
        return cls(environ[ENV['instance']], environ[ENV['session']], environ.get(ENV['launch']) or None)

    @property
    def id(self):
        return self.record['id']

    @property
    def port(self):
        return int(self.roots['port'])

    def copy(self, name='instance'):
        """This session on a fresh copy of its instance, made in the session folder."""
        target = copy_instance(self.instance, self.folder / name)
        return Session(self.receipt_path, self.folder, self.launch_path, self.cache, instance=target)

    def command(self, side):
        """(argv, jvm_at, cwd) of ``side`` from the receipt, every root filled in."""
        entry = self.receipt[side]
        return expand(entry['command'], self.roots), int(entry['jvm_at']), Path(expand([entry['cwd']], self.roots)[0])

    def member(self, name):
        """The launch's one member of project ``name`` (its mod, as a rule) with its placed files."""
        found = [m for m in self.launch['members'] if m['name'] == name and m.get('artifact', 'mod') == 'mod']
        if len(found) != 1:
            raise SessionError(f'the launch names {len(found)} {name} mods')
        member = dict(found[0])
        placed = next((m['placed'] for m in self.receipt['members'] if m['member'] == member['member']), [])
        member['placed'] = [Path(p) for p in expand(placed, self.roots)]
        return member

    def mod_hashes(self):
        return {side: {p.name: sha(p) for p in sorted((self.instance / side / 'mods').glob('*.jar'))} for side in SIDES}

    def view_distance(self):
        """The view distances the instance's files hold, as the lab's profiles name them."""
        def one(path, pattern):
            values = re.findall(pattern, path.read_text(encoding='utf-8'), re.M)
            if len(values) != 1:
                raise SessionError(f'{path} holds {len(values)} values for {pattern}')
            return int(values[0])
        server, client = self.instance / 'server', self.instance / 'client'
        return {'horizontal': one(server / 'server.properties', r'^view-distance=(\d+)\s*$'),
                'vertical': one(server / 'config/cubicchunks.cfg', r'^\s*I:verticalCubeLoadDistance=(\d+)\s*$'),
                'clientRender': one(client / 'options.txt', r'^renderDistance:(\d+)\s*$')}

    def manifest(self):
        """The lab's record of the session's instance, as the lab's runner and checks read one: its folders, Java and
        JVM profile, port, seed and view distances, the receipt's command lines, the mods it holds and where it came
        from. A driver adds what it sets up (labProperties, a workload) before the runner starts."""
        commands = {}
        for side in SIDES:
            argv, at, cwd = self.command(side)
            commands[side] = {'command': argv, 'jvm_at': at, 'cwd': str(cwd)}
        seeds = re.findall(r'^level-seed=(-?\d+)\s*$',
                           (self.instance / 'server/server.properties').read_text(encoding='utf-8'), re.M)
        profile = self.receipt.get('jvm_profile') or {}
        launch = self.receipt['launch']
        manifest = {
            'schema': 'cubature.lab.session/1',
            'name': self.instance.name,
            'session': {'id': self.id, 'folder': str(self.folder)},
            'server': str(self.instance / 'server'),
            'client': str(self.instance / 'client'),
            'java': commands['server']['command'][0],
            'port': self.port,
            'jvmProfile': {key: profile[key] for key in ('initialHeapMiB', 'maxHeapMiB', 'collector', 'arguments')
                           if key in profile},
            'viewDistance': self.view_distance(),
            'commands': commands,
            'modHashes': self.mod_hashes(),
            'receipt': {'path': str(self.receipt_path), 'sha256': sha(self.receipt_path), 'key': self.receipt['key']},
            'launch': {key: launch.get(key) for key in ('sha256', 'project', 'launch', 'commit', 'set', 'members')},
            'jdk': dict(self.record['jdk']),
            'labProperties': {},
        }
        if len(seeds) == 1:
            manifest['seed'] = int(seeds[0])
        return manifest


def write_record(session, name, value):
    """A driver's own record of what it set up, kept in the session folder beside session.json."""
    write_json(session.folder / name, value)
    return session.folder / name
