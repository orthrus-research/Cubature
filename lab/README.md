# Cubature lab

An optional measurement mod and native test harness for Cubature. It runs a real Minecraft 1.12.2
dedicated server and client on Cubature, drives fixed workloads (generation, save and reload,
storage, full-view delivery) and records tick, frame and delivery timings, optionally with JFR.
The lab is a development tool: it is never installed with Cubature or shipped in a pack.

## Building

Build Cubature first (`../cleanroom`), then the lab against that artifact, from this directory:

```sh
../cleanroom/gradlew build
```

`tools/build_cubature.py` builds both and runs the tests in one step.

## Running

`tools/cubic_standalone.py` prepares isolated client and server instances, and `tools/cubic_lab.py`
runs scenarios and bulk pregeneration in them. Riven prepares instances with its
`tools/runtime.py prepare` and drives captures through its `tools/riven_observe.py` (see Riven's
[observability guide](https://github.com/orthrus-research/Riven/blob/main/docs/OBSERVABILITY.md)).
The Python tools and their tests need the packages in `tools/requirements.txt`:

```sh
python -m pip install -r tools/requirements.txt
python -B -m unittest discover -s tools -p "test_*.py"
```

| Path | Contents |
| --- | --- |
| `src/` | The lab mod: scenarios, probes and their Mixins |
| `benchmarks/` | Workload definitions and an example campaign |
| `jfr/` | JFR settings for generation, reload and tail-latency recordings |
| `profiles/`, `runtime.properties` | Client renderer profiles and the pinned runtime the lab launches |

`-Dcubiclab.lodTrace=<directory>` (no spaces) streams Distant Horizons LOD stage events from JVM
start until exit to `lod-client.csv` or `lod-server.csv` there, with a `lod-<side>.json` summary
rewritten every second (`LabLodTrace`). Its Mixins (`MixinLod*`, `cubiclab.lod.mixins.json`) are
independently authored read-only observers: they inject at the head or return of DH 3.3.3 methods
and lambdas by exact name, read fields by reflection and attach completion callbacks, and change no
result. Every injection is required, so another DH version needs a fresh review. Without a workload
the rest of the lab stays idle, so a traced instance can be played normally.

`cubiclab preview-survey <id> <minX> <minZ> <size> <footprints> <threads>` samples the overworld
generator's `ICubicTerrainPreview` over a square window at each listed footprint (comma-separated
powers of two, in the order given) as Cubature's DH generator does: one column at each cell's integer
centre, told the cell width, one sampler per 64 x 64-cell tile (`LabPreviewSurvey`). It writes
`cubiclab-surveys/<id>/` in the server directory: per footprint, rasters of the top, ground and water
tops and the top run's kind, each tile's thread CPU time, and `summary.json`. It places nothing in the
world; thread names carry the footprint, so a JFR recording separates them.

Some probes live in Cubature's own packages (`core.server`, `core.server.chunkio`) so they can call
package-private members such as `CubeProviderServer.tryUnloadCube` and `cubesIterator`, as unit tests
in the same package do. They are tests: Cubature keeps those members package-private rather than
publishing them for the lab, and a change to them must keep the lab building
(`tools/build_cubature.py` builds both).

Results describe the measured machine and runtime; they are diagnostics, not performance claims.
