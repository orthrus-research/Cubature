# Cubature lab

An optional measurement mod and native test harness for Cubature. It runs a real Minecraft 1.12.2
dedicated server and client on Cubature, drives fixed workloads (generation, save and reload,
storage, full-view delivery) and records tick, frame and delivery timings, optionally with JFR.
The lab is a development tool: it is never installed with Cubature or shipped in a pack.

The full-view render-ready gate, the delivery trace's render events and the render diagnostics
read the game's render chunks, which no longer run in cubic worlds under Quadrature, the renderer
a Cubature client needs there. A client without Quadrature starts only with
`-Dcubature.clientWithoutRenderer=true`, and then does not draw cubic worlds correctly.

## Building

Build Cubature first (`../cleanroom`), then the lab against that artifact, from this directory. Like
`cleanroom/`, the lab reads the set for its platform (see the [README](../README.md#building-from-source)):

```sh
../cleanroom/gradlew build
```

`tools/build_cubature.py` builds both and runs the tests in one step.

## Running

Workbench builds the instances: `project.toml`'s `[launch.lab]` is Cubature and this lab's observer
on the standalone world (`[launch.standalone]`), and its drivers run `tools/cubic_lab.py` in a
Workbench driver session, which holds the GPU lease for the whole run:

```sh
wb play cubature:lab --driver run
```

The driver starts the server and client from the instance's receipt (`tools/lab_session.py`) on a
fresh copy of the instance, and keeps its record (`lab.json`), the copy and every result (`runs/`) in
the session folder, which the report and profile tools take as their `--lab-directory`. Further
options, such as a workload and its scenarios, go in `LAB_DRIVER_ARGS`
(`--workload <file> --scenarios full-view,idle`). Riven's measurement and pregeneration drivers
(`tools/measure_pipeline.py`, `tools/pregenerate_map.py`) use the same runner on Riven's launch (see
Riven's [observability guide](https://github.com/orthrus-research/Riven/blob/main/docs/OBSERVABILITY.md)).

The Python tools and their tests need the packages in `tools/requirements.txt`:

```sh
python -m pip install -r tools/requirements.txt
python -B -m unittest discover -s tools -p "test_*.py"
```

### View distance

Every instance is built at the baseline view distance: 12 cubes for the server's horizontal
and vertical view distances and 12 chunks for the client's render distance, so runs compare. A
session may raise any of the three above the baseline, freely, and nothing goes below it:
`wb play --view horizontal=16` (also `vertical` and `client_render`, the views of
`[launch.standalone]`), and a tour may ask for raised distances itself. The lab's record of a
session's instance (`lab.json`, from `tools/lab_session.py`) takes the distances its files hold
into `viewDistance`, and each capture compares the measured distances with that record. The game clamps the server's distances above 32 and Cubature's render distance above 64,
so those are the highest a run may ask for.

Below the baseline only a named reduced profile is allowed (`near-8`: all three distances 8),
which instances prepared before Workbench's sessions recorded as `viewProfile`, with a label such
as `reduced near-8: horizontal 8, vertical 8, clientRender 8 (baseline 12)`; its values are
enforced exactly. A session's record names no profile, so it is a baseline one, and a session
below the baseline is refused before its JVMs start.
Runs of different profiles, or of a baseline and a raised run, are not compared:
`lab_profiles.require_same_view_profile` refuses them, and the full-view comparison accepts
only baseline runs.

| Path | Contents |
| --- | --- |
| `src/` | The lab mod: scenarios, probes and their Mixins |
| `benchmarks/` | Workload definitions and an example campaign |
| `jfr/` | JFR settings for generation, reload and tail-latency recordings |
| `profiles/` | Client renderer profiles; the runtime the lab launches is the set's platform (`runtime = "cleanroom"` in `[launch.lab]`) |

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

## One-off tours

`tours/` holds tour definitions for `wb play riven --tour <file>`, kept for a single question
each and not part of any suite.

`tours/stale-mesh-walk-2485331.json` walks the player 540 blocks east in steps of 9 blocks, 5
seconds at each, then steps back west twice, facing west over the edge the cubes left from (the tour client captures a view only after the player has moved more than 8
blocks and held still 100 ticks), so cubes leave at the trailing edge one column at a time
instead of all at once as a long teleport makes them. It asks for a client render distance of
20 against the server's 12, so that the cubes 13 to 20 cubes away leave the server while still
inside the client's render distance. Play it with `--param seed=2485331 --jvm
client:-Driven.captureTicks=100` (Riven's launch carries no Distant Horizons).

A tour file is a list of views, or an object with `views` and the view distances the tour
asks for: `{"viewDistance": {"horizontal": 12, "vertical": 12, "clientRender": 20}, "views":
[...]}`, any of the three left out staying at the baseline. Options given to `play` win over the
tour's.
