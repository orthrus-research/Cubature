"""Freeze exact lab/CC source bytes without committing or including build/world output."""
import argparse
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def source_files(root):
    names = subprocess.check_output(['git', '-C', str(root), 'ls-files', '-co', '--exclude-standard', '-z'])
    result = []
    for raw in sorted(set(names.split(b'\0'))):
        if not raw:
            continue
        name = raw.decode('utf-8')
        path = Path(name)
        if any(part in {'build', '.gradle', '__pycache__', '.local'} for part in path.parts):
            continue
        if name.startswith(('src/', 'CubicChunksAPI/', 'cleanroom/', 'lab/', 'tools/')) and path.suffix in {
                '.java', '.py', '.gradle', '.properties', '.json', '.cfg', '.jfc', '.xml', '.txt'}:
            if (root / name).is_file():
                result.append(name)
    return result


def freeze(root, output):
    root = root.resolve()
    output.mkdir(parents=True, exist_ok=False)
    records = {}
    with zipfile.ZipFile(output / 'source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for name in source_files(root):
            data = (root / name).read_bytes()
            records[name] = {'sha256': digest(data), 'bytes': len(data)}
            archive.writestr(name, data)
    manifest = {'schema': 1, 'root': str(root), 'head': subprocess.check_output(
        ['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip(), 'files': records,
        'sourceArchiveSha256': digest((output / 'source.zip').read_bytes()),
        'scope': 'Exact selected source bytes including uncommitted files. Artifact identity needs a separately recorded build receipt.'}
    (output / 'source.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    return manifest


def verify(manifest, prefixes=None):
    root = Path(manifest['root'])
    selected = lambda name: not prefixes or name.startswith(tuple(prefixes))
    expected = {name: record for name, record in manifest['files'].items() if selected(name)}
    current = {name for name in source_files(root) if selected(name)}
    changed = sorted(set(expected) ^ current)
    changed += [name for name in sorted(set(expected) & current)
                if digest((root / name).read_bytes()) != expected[name]['sha256']]
    return sorted(set(changed))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = freeze(args.root, args.output)
    print(json.dumps({'head': result['head'], 'files': len(result['files']), 'output': str(args.output)}))
