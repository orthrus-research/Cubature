"""Compare two dumps of cube light checksums (`-Dcubicchunks.lightChecksumDump=<directory>`).

Each side writes `<side>-<dimension>.tsv` as it closes its world. Two dumps are compared over the
cubes both hold whose 26 neighbors both also hold: a cube at the edge of what a side holds may still
be waiting for light from beyond it, so it says nothing about determinism.

  python tools/compare_light_checksums.py run-a/server-0.tsv run-b/server-0.tsv
  python tools/compare_light_checksums.py run-a/server-0.tsv run-a/client-0.tsv --list 20
  python tools/compare_light_checksums.py run-a/server-0.tsv run-a/client-0.tsv \
      --light-after run-a/server-lightafter-0.tsv

`--light-after` reads the server's count of cubes whose light changed after they were sent to a
player, and says how many of the differing cubes it holds: a client that is never sent such a
change keeps the light it first got.
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


def read_light_after(path: Path) -> dict[Position, int]:
    """The server's cubes whose light changed after they were sent, with the ticks it changed in."""
    with open(path, newline="", encoding="utf-8") as stream:
        return {
            (int(row["x"]), int(row["y"]), int(row["z"])): int(row["ticks"])
            for row in csv.DictReader(stream, delimiter="\t")
        }


def edge_distance(position: Position, cubes: dict[Position, dict]) -> int:
    """How many cubes the position lies inside the box around everything held."""
    xs = [p[0] for p in cubes]
    ys = [p[1] for p in cubes]
    zs = [p[2] for p in cubes]
    x, y, z = position
    return min(x - min(xs), max(xs) - x, y - min(ys), max(ys) - y, z - min(zs), max(zs) - z)


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
        "byEdgeDistance": dict(sorted(Counter(edge_distance(p, a) for p in differing).items())),
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("a", type=Path)
    parser.add_argument("b", type=Path)
    parser.add_argument("--list", type=int, default=10, help="differing cubes to print")
    parser.add_argument("--light-after", type=Path, help="the server's server-lightafter-<dim>.tsv")
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
    print(f"by cubes inside the edge of what is held: {result['byEdgeDistance']}")
    if args.light_after:
        after = read_light_after(args.light_after)
        relit = sum(1 for p in result["differing"] if p in after)
        print(
            f"light changed after a send in {len(after)} cubes; {relit} of the {len(result['differing'])} differing"
            " cubes are among them"
        )
    for position in result["differing"][: args.list]:
        print("  " + " ".join(map(str, position)))
    return 1 if result["differing"] else 0


if __name__ == "__main__":
    sys.exit(main())
