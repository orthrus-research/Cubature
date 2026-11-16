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

### View distance

Every instance is prepared at the baseline view distance: 12 cubes for the server's horizontal
and vertical view distances and 12 chunks for the client's render distance, so runs compare. A
run may raise any of the three above the baseline, freely, and nothing goes below it. Riven's
`tools/runtime.py play` takes `--view-distance`, `--vertical-view-distance` and
`--client-render-distance`, and a tour may ask for raised distances itself (below). Before the
launch the instance is set to the run's distances, the baseline for a run that asks for nothing,
and the instance's `standalone.json` records them in `viewDistance` with `viewDistanceLabel`:
`baseline`, or what was raised, such as `raised: clientRender 20 (baseline 12)`. The checks
before launch and in each capture compare the files and the measured distances with that
record. The game clamps the server's distances above 32 and Cubature's render distance above 64,
so those are the highest a run may ask for.

Below the baseline only a named reduced profile is allowed, chosen when the instance is
prepared (`--view-profile near-8`: all three distances 8) and fixed for its life. The manifest
records it as `viewProfile`, with a label such as `reduced near-8: horizontal 8, vertical 8,
clientRender 8 (baseline 12)`; its values are enforced exactly, a run may not raise or change
them, and a play asked to is refused. An instance that names no profile is a baseline one.
Runs of different profiles, or of a baseline and a raised run, are not compared:
`lab_profiles.require_same_view_profile` refuses them, and the full-view comparison accepts
only baseline runs.

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

## One-off tours

`tours/` holds tour definitions for Riven's `tools/runtime.py play --tour`, kept for a single
question each and not part of any suite.

`tours/stale-mesh-walk-2485331.json` walks the player 540 blocks east in steps of 9 blocks, 5
seconds at each, then steps back west twice, facing west over the edge the cubes left from (the tour client captures a view only after the player has moved more than 8
blocks and held still 100 ticks), so cubes leave at the trailing edge one column at a time
instead of all at once as a long teleport makes them. It looks for the case in which the
game's renderer kept drawing a cube that had left: a render chunk still in the client's grid
whose cube the server unloaded, its outer neighbor already gone. Prepare the instance with
`--seed 2485331 --no-distant-horizons --capture-ticks 100` and play the tour with
`-Dcubicchunks.cubeSourceCheck=true`; 40 ticks after each removal the check looks at the cube's
render chunk and counts the departed cubes still drawn. The game rebuilds a render chunk marked
for an update only once it is in view, so a chunk still marked when checked has not had its
chance yet: it is checked again every 40 ticks, counted as having waited on a rebuild, and
counted as still drawn only once it is no longer marked and its mesh is not empty. The look
back brings the departed edge into view, so its chunks are rebuilt or found stale. `-Dcubicchunks.noUnloadedMeshFix=true`
puts back the game's wait, to compare.

A tour file is a list of views, or an object with `views` and the view distances the tour
asks for: `{"viewDistance": {"horizontal": 12, "vertical": 12, "clientRender": 20}, "views":
[...]}`, any of the three left out staying at the baseline. Options given to `play` win over the
tour's.

Measured 2026-11-16, with the fix and without, at the baseline distances: 61 views, 36,875
arrivals and 21,250 departures, every departed cube already outside the render grid, so
neither run reached the case. At equal view distances it cannot arise: the grid spans the
client's render distance around the player's chunk and the server's box its view distance
around the player's cube. The tour therefore asks for a client render distance of 20 against
the server's 12, so the cubes 13 to 20 cubes away leave the server while still in the grid.

Measured 2026-11-16 at client render 20 against server 12, before the look back and before
the check waited on rebuilds: 61 views each, 21,250 departures, all inside the grid, and 3,840
still drawn with the fix and without, the same cubes. The walk faced east, away from the
departed edge, so none of those chunks had been in view to be rebuilt.

Measured again 2026-11-16 with the look back and the check waiting on rebuilds, same
distances, 63 views each: 21,875 departures, 125 back, 16,250 outside the grid by the time
they were checked, and 4,400 checked after the game had had its chance to rebuild them (all
21,875 waited on a rebuild first, none still waiting at the end). Without the fix 1,916 of
those 4,400 were still drawn; with it, none.
