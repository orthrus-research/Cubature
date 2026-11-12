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
Dependency downloads and cached copies use checksum-verified content and unique temporary files
beside their destinations. Temporary basenames stay short even when a library's filename is long,
so preparation also works within legacy Windows path limits.

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
and lambdas by exact name (one, at the quad tree's fallback call), read fields by reflection and
attach completion callbacks, and change no result. `merge-same` rows count the columns a
network answer or generated tile replaces that DH last built from finer data, or fills;
`merge-restore` the replaced ones built from below again; `merge-stamp` downward merges that stamp
DOWN_SAMPLED over kept columns (`LabLodMerge`); `fallback` rows count parents drawn in place of children that are not all ready,
and the renderable children they hide (`LabLodFallback`). Every injection is required, so another DH version needs a fresh review. Without a workload
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

The lab's complete-tick wrapper has priority 1200, above the core phase clock's 1100. CleanMix
applies lower priorities first; MixinExtras wraps the preceding stage, so the lab clock encloses
the core clock. The core publishes every phase vector, including the final tick, before the lab
records its independent duration and freezes the job. Both series must have equal counts and
chronological boundaries; matching the phase sum to its own clock alone does not prove coverage.

The lab also records the first wall-clock minute after the dedicated server's actual `Done`
message, independently of when a client joins or a scenario starts. Only ticks whose start lies
inside that minute count; a tick crossing its end keeps its entire duration. Clean shutdown writes
`cubiclab-startup/<epoch>/startup.json` and its phase-vector CSV in the server directory. The
report is valid only when exactly one ready message was observed synchronously on the server
thread, the minute completed, and no samples were dropped. Shutdown exports the retained data;
the observer does no file work inside a measured tick.
