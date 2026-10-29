#!/usr/bin/env python3
"""Download the compile-only JARs the Cleanroom build reads, verified by SHA-256.

Inside Riven's workspace the build uses its dev/pins instead and this step is unnecessary.
Neither JAR is bundled into Cubature; both remain their authors' unmodified releases.
"""
from lab_files import sha
import argparse
from pathlib import Path
import urllib.request

FORK = Path(__file__).resolve().parents[1]
INPUTS = [
    ('DistantHorizons-3.3.3-1.12.2-forge.jar',
     'https://cdn.modrinth.com/data/uCdwusMi/versions/1PczaQGZ/DistantHorizons-3.3.3-1.12.2-forge.jar',
     '48c3ace5c35c471893d3dc78de79e3dde229df0179a59352307a2e1c8f60e613'),
    ('Demonica-0.9.0.jar',
     'https://github.com/ndellagrotte/Demonica/releases/download/v0.9.0/Demonica-0.9.0.jar',
     '241632b96fa241b670d7c5c29110d8e7c870bda9693d2967e680cf357e1a619d'),
]


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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', type=Path, default=FORK / 'cleanroom/build-inputs')
    args = parser.parse_args()
    args.target.mkdir(parents=True, exist_ok=True)
    for name, url, expected in INPUTS:
        fetch(name, url, expected, args.target)


if __name__ == '__main__':
    main()
