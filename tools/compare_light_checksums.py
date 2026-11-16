"""Compare two dumps of cube light checksums (`-Dcubicchunks.lightChecksumDump=<directory>`).

Each side writes `<side>-<dimension>.tsv` as it closes its world. Two dumps are compared over the
cubes both hold whose 26 neighbors both also hold: a cube at the edge of what a side holds may still
be waiting for light from beyond it, so it says nothing about determinism.

  python tools/compare_light_checksums.py run-a/server-0.tsv run-b/server-0.tsv
  python tools/compare_light_checksums.py run-a/server-0.tsv run-a/client-0.tsv --list 20
"""

import argparse
import csv
import sys
from collections import Counter
from pathlib import Path

Position = tuple[int, int, int]


def read(path: Path) -> dict[Position, dict]:
    with open(path, newline="", encoding="utf-8") as stream:
        return {
            (int(row["x"]), int(row["y"]), int(row["z"])): {
                "checksum": row["checksum"],
                "blocks": row.get("blocks"),
                "storage": row["storage"] == "1",
                "firstLight": row["firstLight"] == "1",
            }
            for row in csv.DictReader(stream, delimiter="\t")
        }


def interior(cubes: dict[Position, dict]) -> set[Position]:
    """The cubes whose 26 neighbors are all held too."""
    near = [(dx, dy, dz) for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1) if dx or dy or dz]
    return {
        (x, y, z)
        for (x, y, z) in cubes
        if all((x + dx, y + dy, z + dz) in cubes for dx, dy, dz in near)
    }


def compare(a: dict[Position, dict], b: dict[Position, dict]) -> dict:
    shared = interior(a) & interior(b)
    differing = sorted(p for p in shared if a[p]["checksum"] != b[p]["checksum"])
    return {
        "heldA": len(a),
        "heldB": len(b),
        "compared": len(shared),
        "differing": differing,
        "differingWithStorage": sum(1 for p in differing if a[p]["storage"] or b[p]["storage"]),
        "blocksDiffer": sum(1 for p in shared if a[p]["blocks"] != b[p]["blocks"]),
        "differingSameBlocks": sum(1 for p in differing if a[p]["blocks"] == b[p]["blocks"]),
        "byCubeY": dict(sorted(Counter(p[1] for p in differing).items())),
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("a", type=Path)
    parser.add_argument("b", type=Path)
    parser.add_argument("--list", type=int, default=10, help="differing cubes to print")
    args = parser.parse_args(argv)
    result = compare(read(args.a), read(args.b))
    print(f"held: {result['heldA']} and {result['heldB']}; compared (interior to both): {result['compared']}")
    print(
        f"differing: {len(result['differing'])}"
        f" ({result['differingWithStorage']} with block storage); by cube Y: {result['byCubeY']}"
    )
    print(
        f"blocks differ in {result['blocksDiffer']} compared cubes; light differs over the same blocks in"
        f" {result['differingSameBlocks']}"
    )
    for position in result["differing"][: args.list]:
        print("  " + " ".join(map(str, position)))
    return 1 if result["differing"] else 0


if __name__ == "__main__":
    sys.exit(main())
