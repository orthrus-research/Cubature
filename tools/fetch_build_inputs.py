#!/usr/bin/env python3
"""Download the compile-only JARs the Cleanroom build and the lab read, verified by SHA-256.

cleanroom/build-inputs.json pins each by name, URL and hash; both Gradle builds check the files
against it and refuse to compile without them. Neither JAR is bundled into Cubature; both remain
their authors' unmodified releases.
"""
from lab_files import sha
import argparse
import json
from pathlib import Path
import urllib.request

FORK = Path(__file__).resolve().parents[1]
PINS = FORK / 'cleanroom/build-inputs.json'


def inputs():
    return json.loads(PINS.read_text(encoding='utf-8'))['inputs']


def fetch(name, url, expected, target):
    path = target / name
    if path.is_file() and sha(path) == expected:
        print('present  ' + name)
        return
    partial = path.with_name(name + '.part')
    request = urllib.request.Request(url, headers={'User-Agent': 'orthrus-research/cubature-build'})
    with urllib.request.urlopen(request, timeout=120) as response, partial.open('wb') as out:
        while chunk := response.read(1 << 20):
            out.write(chunk)
    actual = sha(partial)
    if actual != expected:
        partial.unlink()
        raise SystemExit(f'{name}: SHA-256 {actual} does not match the pinned {expected}')
    partial.replace(path)
    print('fetched  ' + name)


def fetch_all(target=FORK / 'cleanroom/build-inputs'):
    target.mkdir(parents=True, exist_ok=True)
    for pin in inputs():
        fetch(pin['file'], pin['url'], pin['sha256'], target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', type=Path, default=FORK / 'cleanroom/build-inputs')
    fetch_all(parser.parse_args().target)


if __name__ == '__main__':
    main()
