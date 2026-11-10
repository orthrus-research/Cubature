"""Account a lab process's committed memory: the JVM's own view (NMT summary, jcmd System.map)
joined with Windows' (Sysinternals VMMap), so every committed byte has an owner class.

  python tools/lab_memory.py <evidence directory>   account each *-native-memory.txt there

The NMT summary gives what the JVM allocated with malloc; System.map tags each range the JVM
reserved (JAVAHEAP, CODE, GC ...) and marks write-combined (GPU-visible) pages; VMMap splits the
process into C heaps, other private data, thread stacks, images, mapped files and sections. C heap
beyond the JVM's malloc, and private data the JVM did not reserve, belong to native libraries and
drivers loaded beside it.
"""
import csv
import io
import json
import re
import sys
from pathlib import Path

MIB = 1024 * 1024
MAP_ROW = re.compile(r'^0x([0-9a-f]+)-0x([0-9a-f]+)\s+(\d+)\s+(\S+)\s+([cr])-(pvt|img|map)\s+(\S+)\s*(.*)$')


def nmt_summary(text):
    """Committed MiB in total, by malloc, and by category, from `VM.native_memory summary scale=MB`."""
    total = re.search(r'Total: reserved=(\d+)MB, committed=(\d+)MB', text)
    malloc = re.search(r'^\s*malloc: (\d+)MB', text, flags=re.M)
    categories = {m[1].strip(): int(m[3]) for m in re.finditer(r'-\s+([A-Za-z ]+?) \(reserved=(\d+)MB, committed=(\d+)MB', text)}
    if not total: raise ValueError('Not an NMT summary')
    return {'committedMiB': int(total[2]), 'mallocMiB': int(malloc[1]) if malloc else 0, 'categoriesMiB': categories}


def system_map(text):
    """Committed bytes from `jcmd System.map`: private by JVM tag, images and mapped views by file."""
    tags, images, mapped = {}, {}, {}
    untagged = write_combined = rows = 0
    for line in text.splitlines():
        m = MAP_ROW.match(line.strip())
        if not m: continue
        rows += 1
        if m[5] != 'c': continue
        size, protection, kind, info = int(m[3]), m[4], m[6], m[8].strip()
        if 'W' in protection: write_combined += size
        if kind == 'pvt':
            if info: tags[info.split()[0]] = tags.get(info.split()[0], 0) + size
            else: untagged += size
        else:
            target = images if kind == 'img' else mapped
            name = info or '(anonymous section)'
            target[name] = target.get(name, 0) + size
    if not rows: raise ValueError('Not a System.map listing')
    return {'taggedPrivate': tags, 'untaggedPrivate': untagged, 'images': images, 'mapped': mapped,
            'writeCombined': write_combined}


def vmmap_summary(text):
    """VMMap's per-type summary (Size, Committed, Private, Total WS, Private WS ...), in bytes."""
    summary, header = {}, None
    for row in csv.reader(io.StringIO(text)):
        row = [cell.strip() for cell in row]
        if not row or not row[0]: continue
        if row[0] == 'Type': header = row; continue
        if row[0] == 'Address': break
        if header and len(row) > 2:
            summary[row[0]] = {column: int(value.replace(',', '')) * 1024
                               for column, value in zip(header[1:], row[1:]) if column and value and value.replace(',', '').isdigit()}
    if 'Total' not in summary: raise ValueError('Not a VMMap CSV')
    return summary


def account(nmt, mapped, vmmap):
    """Owner classes of the process's private commit, in MiB, with the residual against VMMap's total."""
    private = lambda kind: vmmap.get(kind, {}).get('Private', 0)
    committed = lambda kind: vmmap.get(kind, {}).get('Committed', 0)
    tags = mapped['taggedPrivate']
    java_heap = tags.get('JAVAHEAP', 0)
    jvm_reserved = sum(size for tag, size in tags.items() if tag != 'STACK')
    jvm_malloc = nmt['mallocMiB'] * MIB
    owners = {
        'Java heap': java_heap,
        'JVM reserved, not heap (GC, class space, code, metaspace, CDS)': jvm_reserved - java_heap,
        'JVM malloc (NMT; direct buffers, internal)': jvm_malloc,
        'Native malloc beyond the JVM (C heaps: libraries, drivers)': private('Heap') - jvm_malloc,
        'Native VirtualAlloc the JVM did not reserve (drivers, libraries)': private('Private Data') - jvm_reserved,
        'Thread stacks': private('Stack'),
        'Image private pages (writable module data)': private('Image'),
        'Page tables': committed('Page Table'),
    }
    total = private('Total')
    result = {name: round(size / MIB) for name, size in owners.items()}
    result['Accounted'] = round(sum(owners.values()) / MIB)
    result['VMMap private total'] = round(total / MIB)
    return {
        'privateMiB': result,
        'workingSetMiB': {kind: round(values.get('Total WS', 0) / MIB) for kind, values in vmmap.items() if values.get('Total WS')},
        'sharedMiB': {'Mapped files': round(committed('Mapped File') / MIB), 'Shareable sections': round(committed('Shareable') / MIB)},
        'writeCombinedMiB': round(mapped['writeCombined'] / MIB),
        'largestImagesMiB': top(mapped['images']),
        'largestMappedMiB': top(mapped['mapped']),
    }


def top(sizes, count=8):
    return {Path(name).name if '\\' in name or '/' in name else name: round(size / MIB, 1)
            for name, size in sorted(sizes.items(), key=lambda item: -item[1])[:count]}


def account_files(nmt_path, map_path, vmmap_path):
    return account(nmt_summary(Path(nmt_path).read_text(encoding='utf-8', errors='replace')),
                   system_map(Path(map_path).read_text(encoding='utf-8', errors='replace')),
                   vmmap_summary(Path(vmmap_path).read_text(encoding='utf-8-sig', errors='replace')))


def main(directory):
    for nmt in sorted(Path(directory).glob('*-native-memory.txt')):
        stem = nmt.name[:-len('-native-memory.txt')]
        system, vmmap = nmt.with_name(stem + '-system-map.txt'), nmt.with_name(stem + '-vmmap.csv')
        if not system.is_file() or not vmmap.is_file(): continue
        result = account_files(nmt, system, vmmap)
        print('== ' + stem)
        for name, mib in result['privateMiB'].items(): print(f'  {mib:>8,} MiB  {name}')
        print(f"  shared: {result['sharedMiB']}; write-combined {result['writeCombinedMiB']} MiB")
        print(f"  images: {result['largestImagesMiB']}")


if __name__ == '__main__':
    main(sys.argv[1])
