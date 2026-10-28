#!/usr/bin/env python3
"""Export the queue-key synthetic hash comparison as PNG, SVG, CSV and JSON.

Chart contract: compare eight measured rows (four workloads, two formulas),
with matching Java 25 and Java 8 captures. Left: grouped horizontal bars for
distinct hashes / keys, zero-based percentage scale. Right: paired points for
the largest equal-hash group on an explicitly logarithmic key-count scale.
Use orange/open marks for upstream and blue/filled marks for coordinate mix.
Static 13 x 7.4 inch exports support source review, not native speed claims.
"""

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from matplotlib.ticker import FuncFormatter
import numpy as np


ROOT = Path(__file__).resolve().parents[2]
WORKLOADS = (
    ("vertical_z_zero", "Vertical line at z = 0", "x = 8; y = -8192..8191; z = 0"),
    ("horizontal_y_zero", "Horizontal line at y = 0", "x = 8; y = 0; z = -8192..8191"),
    ("origin_plane", "128 x 128 plane at y = 0", "x, z = -64..63; y = 0"),
    ("volume_32", "32 x 32 x 32 volume", "x, y, z = -16..15"),
)
COLORS = {"upstream": "#C77C2B", "coordinate_mix": "#2878B5"}
LABELS = {"upstream": "Upstream formula", "coordinate_mix": "Independent coordinate mix"}


def read_rows(path):
    with path.open(newline="", encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    for row in rows:
        for key in row:
            if key not in ("workload", "implementation"):
                row[key] = int(row[key])
        if not (0 < row["distinct_hashes"] <= row["keys"]):
            raise ValueError("Invalid hash counts: " + str(row))
    expected = {(name, implementation) for name, _, _ in WORKLOADS for implementation in LABELS}
    if {(row["workload"], row["implementation"]) for row in rows} != expected or len(rows) != len(expected):
        raise ValueError("Expected exactly one row per workload and implementation")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "cleanroom/build/hash-diagnostic")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/measurements/hash-distribution")
    args = parser.parse_args()
    input_dir, output_dir = args.input.resolve(), args.output.resolve()
    java25_path = input_dir / "hash-distribution-java25.csv"
    java8_path = input_dir / "hash-distribution-java8.csv"
    rows = read_rows(java25_path)
    if rows != read_rows(java8_path):
        raise ValueError("Java 25 and Java 8 captures differ; the combined chart would be misleading")
    measured = json.loads((input_dir / "summary.json").read_text(encoding="utf-8"))
    if any(command["exit_code"] != 0 for run in measured["runs"] for command in run["commands"]):
        raise ValueError("The compile/test captures contain a failure")
    if measured["negative_control"]["commands"][-1]["exit_code"] == 0:
        raise ValueError("The original-expression negative control unexpectedly passed")
    by_key = {(row["workload"], row["implementation"]): row for row in rows}
    for name, _, _ in WORKLOADS:
        if by_key[name, "upstream"]["keys"] != by_key[name, "coordinate_mix"]["keys"]:
            raise ValueError("Mismatched workload sizes: " + name)
    output_dir.mkdir(parents=True, exist_ok=True)
    for path in (java25_path, java8_path):
        if path.resolve() != (output_dir / path.name).resolve():
            shutil.copyfile(path, output_dir / path.name)

    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "text.color": "#252B32",
                         "axes.labelcolor": "#252B32", "xtick.color": "#535B64", "ytick.color": "#252B32",
                         "svg.fonttype": "none", "axes.titleweight": "bold"})
    fig, (left, right) = plt.subplots(1, 2, figsize=(13, 7.4), sharey=True)
    fig.subplots_adjust(left=0.235, right=0.965, top=0.715, bottom=0.205, wspace=0.19)
    fig.suptitle("Cubic Chunks queue-key hash distributions", x=0.045, y=0.955, ha="left", fontsize=21, fontweight="bold")
    fig.text(0.045, 0.892, "Measured synthetic coordinates | Java 25 and Java 8 captures agree", fontsize=12)
    fig.text(0.045, 0.852, "Both formulas use the same coordinates and a world-hash contribution of zero.", color="#535B64", fontsize=10)
    fig.legend(handles=[Patch(facecolor="white", edgecolor=COLORS["upstream"], hatch="///", label=LABELS["upstream"]),
                        Patch(facecolor=COLORS["coordinate_mix"], edgecolor=COLORS["coordinate_mix"], label=LABELS["coordinate_mix"])],
               loc="upper left", bbox_to_anchor=(0.04, 0.822), ncol=2, frameon=False)
    ys = np.arange(len(WORKLOADS))
    for index, implementation in enumerate(LABELS):
        positions = ys + (-0.17 if index == 0 else 0.17)
        percentages = [100 * by_key[name, implementation]["distinct_hashes"] / by_key[name, implementation]["keys"]
                       for name, _, _ in WORKLOADS]
        left.barh(positions, percentages, height=0.29, edgecolor=COLORS[implementation], linewidth=1.1,
                  color="white" if index == 0 else COLORS[implementation], hatch="///" if index == 0 else None)
        for y, percent in zip(positions, percentages):
            label = "100%" if percent == 100 else (f"{percent:.3f}%" if percent < 0.01 else f"{percent:.2f}%")
            left.text(percent + 2, y, label, va="center", fontsize=9)
        sizes = [by_key[name, implementation]["largest_equal_hash_group"] for name, _, _ in WORKLOADS]
        right.scatter(sizes, positions, s=65, marker="D" if index == 0 else "o", linewidths=1.3,
                      facecolors="white" if index == 0 else COLORS[implementation], edgecolors=COLORS[implementation], zorder=3)
        for y, size in zip(positions, sizes):
            right.text(size * 1.4, y, f"{size:,}", va="center", fontsize=9)
    for y, (name, _, _) in zip(ys, WORKLOADS):
        old = by_key[name, "upstream"]["largest_equal_hash_group"]
        new = by_key[name, "coordinate_mix"]["largest_equal_hash_group"]
        right.plot([old, new], [y - 0.17, y + 0.17], color="#BCC2C9", linewidth=1, zorder=1)
    left.set_title("Distinct hashes / queued keys", loc="left", fontsize=11, pad=14)
    left.set_xlim(0, 117)
    left.set_xticks([0, 25, 50, 75, 100], ["0%", "25%", "50%", "75%", "100%"])
    left.set_xlabel("Higher retains more distinct key hashes", labelpad=12)
    left.set_yticks(ys, [label + "\n" + f"{by_key[name, 'upstream']['keys']:,} keys" for name, label, _ in WORKLOADS])
    left.invert_yaxis()
    right.set_title("Largest group with an identical hash", loc="left", fontsize=11, pad=14)
    right.set_xscale("log")
    right.set_xlim(0.7, 85000)
    right.set_xticks([1, 10, 100, 1000, 10000])
    right.xaxis.set_major_formatter(FuncFormatter(lambda value, _: f"{value:,.0f}"))
    right.set_xlabel("Keys per group (log scale) | lower is better", labelpad=12)
    for axis in (left, right):
        axis.set_axisbelow(True)
        axis.grid(axis="x", color="#E4E7EB", linewidth=0.7)
        axis.tick_params(axis="y", length=0, pad=10)
        axis.tick_params(axis="x", length=3, color="#B6BDC5")
        for spine in ("top", "right", "left"):
            axis.spines[spine].set_visible(False)
        axis.spines["bottom"].set_color("#B6BDC5")
    fig.text(0.045, 0.105, "The revised formula gives every key a distinct hash in these four samples.", fontsize=11, fontweight="bold")
    fig.text(0.045, 0.066, "Scope: synthetic hash distribution only. No native Minecraft runtime, elapsed-time speedup, or universal collision guarantee.",
             fontsize=9.2, color="#535B64")
    fig.text(0.045, 0.033, "Source: QueuedCubeHashDiagnostic using the production hashCoordinates method; raw CSV and source hashes accompany this export.",
             fontsize=8.7, color="#535B64")
    fig.savefig(output_dir / "hash-distribution.png", dpi=180, facecolor="white")
    fig.savefig(output_dir / "hash-distribution.svg", facecolor="white", metadata={"Date": None})
    svg_path = output_dir / "hash-distribution.svg"
    svg_path.write_text("\n".join(line.rstrip() for line in svg_path.read_text(encoding="utf-8").splitlines()) + "\n", encoding="utf-8")
    plt.close(fig)

    summary = {
        "generated_utc": datetime.now(timezone.utc).isoformat(),
        "scope": "Measured synthetic coordinate hash distribution; no native runtime or wall-clock speedup claim.",
        "captures_match": True,
        "world_hash_contribution": 0,
        "synthetic_bucket_count": 32768,
        "source_sha256": measured["source_sha256"],
        "plot_script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "csv_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in (java25_path, java8_path)},
        "runtimes": [{"profile": run["profile"], "java_version": run["java_version"].splitlines()[0],
                      "mapped_api_sha256": run["mapped_api_sha256"]} for run in measured["runs"]],
        "validation": {"compiled_release": 8, "compile_fixture": "MCP nullability annotation only",
                       "regression_tests": "passed on Java 25 and Java 8",
                       "original_expression_negative_control": "failed as expected"},
        "workloads": [{"id": name, "coordinates": coordinates, "keys": by_key[name, "upstream"]["keys"],
                       "upstream": by_key[name, "upstream"], "coordinate_mix": by_key[name, "coordinate_mix"]}
                      for name, _, coordinates in WORKLOADS],
    }
    (output_dir / "result.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print("Exported PNG, SVG, raw CSV captures and result.json to " + str(output_dir))


if __name__ == "__main__":
    main()
