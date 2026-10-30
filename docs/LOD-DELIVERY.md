# Distant terrain delivery (design draft)

Status: draft on branch `preview-pool-design`, not implemented. When it is
implemented, the behavior moves into [DISTANT-HORIZONS.md](DISTANT-HORIZONS.md)
and this file goes.

## Goals, in order

1. **Native view first.** Serve the finished native cubes inside the player's
   render distance quickly.
2. **Accurate heights at medium distance.** They must arrive early, even with
   simplified detail.
3. **No large shape changes on approach.** When an area refines from coarse to
   full detail as the player gets close, its shape must not jump.

The first draft of this file aimed at preview throughput.
[Changes from the first draft](#changes-from-the-first-draft) lists what
moved, and why.

## Constraints

- **8-core baseline that scales up**, and **own the DH pipeline**: both from
  Riven `docs/DECISIONS.md`, 2026-10-30.
  - Sizes derive from the processor count.
  - Cubature changes DH 3.3.3 through its own code where the API allows it,
    and LGPL-3.0-only Mixins otherwise. Each Mixin is a required match bound
    to 3.3.3 and recorded in `NOTICE.md`.
  - The DH JAR stays unmodified.
- **Timing, never content.** Scheduling and merging change when data
  appears and how DH reduces it. Riven's blocks stay a pure function of seed
  and definitions.
- **No emulation.** Sizes come from formulas; measurements run on the
  workstation as it is.

## Evidence

**Setup:**
- Captures: Riven `ab3-land-base` and `ab3-land-health` (DH's health check
  ignored), from 2026-10-30.
- Workstation: i9-13900K, 32 logical CPUs.
- Runtime: Java 26, ZGC with 8 GiB per JVM.
- DH: 8 workers.
- Route: the land workload, a 60 s spectator flight at 16 blocks/s.
- Native view distance: 12 chunks, so 192 blocks and 15,625 view cubes.
- Repeats: one run per condition.
- Local evidence: Riven `.local/` (lod-reports, cubic-standalone,
  atlas/pop-route-full).

### Native view

| Measure | Base | Health check ignored |
|---|---|---|
| Full view delivered after join | 28.7 s | 66.8 s |
| Server tick median during that | 31 ms | 47 ms |
| View cubes missing at the client during flight (mean) | 423 | 436 |

- **Join is the weak point.** Preview work more than doubled the time to
  deliver the full native view at join.
- **Flight keeps up.** In both runs the client trails the leading edge by
  about one cube slice.
- **No native priority.** Nothing in Cubature puts native work ahead of
  previews. DH's health pause is the only brake, and it is one by accident.

### When LOD data arrives (base run, client side)

| Section detail | Cell | Arrivals | Distance to section at arrival, p10 / p50 / p90 | Wanted to arrival, p50 / p90 |
|---|---|---|---|---|
| 6 | 1 block | 219 | 55 / 176 / 303 | 9.6 s / 23 s |
| 7 | 2 | 152 | 304 / 464 / 1,146 | 25 s / 67 s |
| 8 | 4 | 133 | 590 / 1,007 / 1,809 | 53 s / 107 s |
| 9 | 8 | 79 | 1,104 / 1,616 / 2,128 | 77 s / 118 s |

Distances are in blocks.

- **Full detail arrives too late.** 57% of full-detail tiles arrived after
  their section was already within 192 blocks, where native cubes take over.
  That is wasted sampling, and the most expensive kind, with exact trees.
- **Medium distance arrives last.** The order is sound: DH's client sends the
  nearest waiting request whenever one of its 40 slots frees, and the server
  starts the nearest task. The waits come from throughput.
- **Throughput falls short.** Production was 4.4 tiles/s. The detail bands
  DH wanted on this route reach about 350 blocks for detail 6, 900 for detail
  7, 1,800 for 8, 2,500 for 9, and the 4,096-block radius for 10. Flying at
  16 blocks/s through them needs roughly 4 tiles/s. That rough estimate falls
  to about 3 tiles/s if tiles inside the native view are skipped. On top of
  that came the ~1,000 sections DH wanted at join.
- **Health check ignored:** detail 6, 7 and 8 arrived after medians of
  3.2 s, 7.2 s and 24 s.

### Cost per column (base run, server)

| Cell | 1 | 2 | 4 | 8 | 16 |
|---|---|---|---|---|---|
| Tile sampling, p50 (ms) | 322 | 451 | 330 | 413 | 1,920 |
| Per column (µs) | 79 | 110 | 80 | 101 | 469 |

Every tile has 4,096 columns, whatever its level.

**Fine cells:** two lookups resolve a whole native chunk column (all 256
columns, with caves and ores) to answer a one-column question:
- `TreeSites.surface`, 29.7% of sampler time in JFR, for exact trees below
  4-block cells;
- `GroundCover.waterBeside`, 10.4%, for canes at cells of 1 or 2 blocks.

**16-block cells:** neither lookup runs, so the cost there has another cause.
A 1,024-block tile touches many more region plans and drainage networks than
a fine one. That is the likely cause, but it is not yet profiled.

### Shape differentials (offline, terrain only)

**Method:**
- An atlas window of 2,048 x 2,048 blocks at one block per pixel on the route
  (seed 8675309, centre 4144, -25008), using terrain and water tops.
- Trees and caves are not in it.
- "Error" is the gap between a cell's value and each column's own top.
- "Shift" is how far a parent cell moves when finer data reaches it through
  DH's merge.

Figures are p95, in blocks:

| Cell | Centre sample: error | DH max merge: error | Cell mean: error | Shift today, full-detail children | Shift today, preview children | Shift with the nearest-mean merge, full / preview children |
|---|---|---|---|---|---|---|
| 4 | 2 | 3 | 1.6 | 3 | 2 | 2 / 1 |
| 8 | 3 | 7 | 3.1 | 6 | 3 | 2 / 2 |
| 16 | 6 | 13 | 5.8 | 11 | 6 | 4 / 3 |
| 32 | 11 | 23 | 10.1 | 22 | 12 | 7 / 6 |
| 64 | 19 | 39 | 17.0 | 36 | 21 | 11 / 11 |

- **Medium-distance heights are accurate when present.** A centre sample is
  within 3 blocks (p95) at 8-block cells and 6 at 16. The problem at medium
  distance is late arrival, then distortion by DH's merge.
- **DH's merge is the main shape distortion.** It builds a coarse column from
  four finer ones slice by slice, and any non-air child wins. So a parent
  built from finer data takes the highest block in its footprint:
  - A parent first drawn from Riven's centre sample rises when finer data
    reaches it.
  - Areas with native data at full detail look inflated next to unvisited
    ones.
  - Trees, which are 2 to 20+ blocks tall in `riven-trees.json`, make it
    worse. One tree lifts its whole cell, while Riven's coarse canopy rule
    draws crowns only where stand cover reaches half (`coverThreshold` 0.5).
- **A nearest-mean merge cuts the shift about threefold.** It keeps the child
  column whose top is nearest the four tops' mean. Its error is close to the
  centre sample's. It also matches Riven's majority rule for canopy: a cell
  shows crowns where most of it is under crowns.

## Plan

### 1. Native view first (Cubature)

- **Native debt.** Cubature's `PlayerCubeMap` already keeps `cubesToGenerate`
  and `cubesToSendToClients`. Their sizes are the native debt.
- **The gate.** Previews start only while the debt is below one face of the
  view, `(2 x view distance + 1)^2` cubes (625 at distance 12). At join and
  teleport, previews therefore wait for the native view, while steady flight
  stays under the gate.
- **Covered sections.** Waiting preview tasks for sections fully covered by
  sent native columns are cancelled, through `removeRetrievalRequestIf` on
  DH's world generation queue. Cubature's native snapshots supply DH there.
- **Measure first.** Capture full-view time and flight coverage with DH
  generation off on the same route. That gives the native-only floor.

### 2. Owned pool and governor (Cubature)

DH's World Gen executor can only be on or off, and its health pause stopped
previews in 58% of samples. Cubic previews therefore move to a Cubature pool,
one per server, in place of DH's executor.

**Admission:**
- DH still chooses which tile comes next.
- Mixins on `WorldGenerationQueue.isGeneratorBusy` and
  `GeneratedFullDataSourceProvider.getMaxRetrievalQueueCount` admit at most
  the pool's permits plus one tile, so DH's choice stays current.
- Completion runs DH's update on the pool thread: 11 ms against 365 ms of
  sampling per tile.

**Sizes** (`P` is the number of logical CPUs):

| P | Native workers `clamp(P/4, 1, 4)` | Preview ceiling `clamp(P/4, 1, 8)` | DH `numberOfThreads` `clamp(P/4, 2, 8)` |
|---|---|---|---|
| 8 | 2 | 2 | 2 |
| 16 | 4 | 4 | 4 |
| 24 | 4 | 6 | 6 |
| 32 | 4 | 8 | 8 |

**Governor:** once a second, from the median and p90 of the last 20 server
ticks.
- **Halve permits** when the median is 30 ms or more, or the p90 50 ms or
  more.
- **Add one permit** when the median is under 20 ms, the p90 under 45 ms, and
  tiles are waiting.
- **Zero permits** while step 1's gate is closed; otherwise at least one.
- **Fast response:** a worker above the permit count parks between rows of
  its tile, so the governor acts within about 30 ms.

`previewThreads = 0` keeps DH's executor, for A/B runs and as a fallback.

### 3. One coarse shape (Cubature, with a Riven check)

**The merge patch:** an LGPL Mixin on `FullDataSourceV2`'s 2 x 2 merge.
- **Where it applies:** where all four children are generated previews
  (generation step `SURFACE`).
- **What it keeps:** the child column whose top is nearest the four tops'
  mean, with its runs and light.
- **Elsewhere:** where any child carries native data, DH's rule stays, so thin
  built structures do not vanish at distance. Whether natural terrain in
  visited areas should switch too is an open question.

**The generator** keeps its centre sample. Up to 16-block cells, its error is
within a block of the cell mean's. A 4 x 4 grid would cut the shift at 32
and 64 blocks further (p95 5 and 8 instead of 7 and 11). Add it only if the
shape metric asks for it.

**The shape metric** is a Riven check that runs in CI.
- It runs the real preview sampler, trees included, at every footprint over a
  fixed window.
- It merges children as the patched merge does.
- It reports the error and shift by cell size.
- Its first run sets the tree thresholds; the terrain-only table above is the
  floor.

**Canopy:** compare Riven's stand-canopy cells against merged exact-tree
cells in that metric. Adjust `coverThreshold` or the canopy band where they
disagree.

### 4. Cheaper columns (Riven)

Each change keeps preview and native output identical. The parity references
check that, and the golden atlas digests must not change.

1. **One-column queries.** Replace the whole-chunk lookups in
   `TreeSites.surface` and `GroundCover.waterBeside`, which take 40.1% of
   sampler time. Cave carving and ore bodies are already computed per column.
   Confirm that every step is (unstable blocks, for one) before relying on
   it.
2. **Scratch reuse.** Move the per-column arrays of `TerrainColumn.preview` and
   `TreeSites.canopy` into the task-confined sampler.
3. **Hash keys.** Fix the colliding keys in `CaveMap.family` and `OreMap.patch`.
   Cubature caches `BlockStateWrapper` per block state in the preview writer.
4. **Coarse tiles.** Profile the 16-block-cell cost (469 µs per column) by
   tile, then make region plans and drainage reusable across a coarse tile
   if they are the cause.
5. **Later.** `StrictMath` takes 19.3% of sampler time. Replacing it changes
   terrain, so it needs a new terrain version and its own decision.

**Budget for the 8-core baseline:** two preview workers must supply about
3 tiles/s for 16 blocks/s flight once step 1 skips covered tiles. That is
0.6 s or less per tile. Today, at 1- to 8-block cells, the p50 is 0.32 to
0.45 s and the p90 0.5 to 2.9 s.

### 5. Refine before the player arrives (Cubature)

A Mixin on `WorldGenerationQueue.tryStartNextWorldGenTask` changes DH's
selection key.
- **Order by time to reach.** Today DH divides distance by detail level.
  Instead, take the distance from the section's edge to the native view's
  edge and divide by the player's speed toward the section. Sections ahead
  come first and those behind last. A player standing still keeps DH's
  order.
- **Skip what native will cover.** A tile whose section will be inside the
  native view before the tile could finish is skipped, so the step 1 rule
  applies ahead of arrival.
- **Limits:** DH's client still sends its nearest 40, so this reorders what
  the server holds.

### 6. DH's own pool and integrated servers

- **Fresh instances:** set DH's `numberOfThreads` from `P`.
- **Integrated servers:** add the client's frame time as a second governor
  input for one-JVM play.

## Acceptance

Land route on the workstation, traced, two runs per condition:

| Goal | Today | Target |
|---|---|---|
| Native full view at join, previews on, relative to previews off | (to measure) | within 10% |
| View cubes missing at the client during flight (mean) | 423 | 423 or fewer |
| Full-detail tiles arriving inside the native view | 57% | under 10% |
| Wanted to arrival, p50, detail 6 / 7 / 8 | 9.6 / 25 / 53 s | 3 / 7 / 20 s or less |
| Shape metric, terrain shift p95 at 16-block cells | 11 blocks | 4 or less |
| Shape metric, with trees | (first run) | set from the first run |
| Server tick during flight | median 15 ms, p90 41 ms | median 20 ms or less, p90 45 ms or less |

## Phases

1. **Measure:** the native-only floor and the shape metric with trees.
2. **Native first, pool and governor:** steps 1 and 2.
3. **One coarse shape:** step 3, the merge patch and canopy check, gated by the
   shape metric.
4. **Cheaper columns:** step 4, one change at a time.
5. **Arrival ordering:** step 5.
6. **DH pool sizing and integrated servers:** step 6.

## Decisions this needs

- **Preview pools.** Extend the 2026-10-23 approval of bounded Cubature worker
  pools for pure cube generation and storage writes to preview sampling.
- **DH's merge rule.** Changing it alters how DH reduces generated data
  everywhere. It needs its own entry, and a second one if it later covers
  native data.
- **DISTANT-HORIZONS.md.** It says the adapter creates no worker pool and that
  DH owns admission. Cubature would own how many previews run, in what order,
  and how generated data merges. DH keeps its request limits, propagation,
  persistence and delivery.

## Open questions

- **Trees.** Only terrain is measured offline. The shape metric's first run
  answers how much trees add.
- **Native data merge.** Should the nearest-mean merge also apply to native
  data? Today that data stays inflated next to unvisited areas, but switching
  could thin built structures at distance.
- **The 16-worker failure.** With 16 DH workers the full-view teleport was lost
  in both attempts, and the cause was never diagnosed.
- **Integrated servers.** No one-JVM run has been measured yet.
- **Fast flight.** DH pauses generation while the camera moves fast; the
  owned pool does not.
- **DH's request limit.** `generationRequestRateLimit` is 40; does it bind once
  tiles are cheaper?
- **Biome locks.** `SurfaceTranscription` takes a biome's lock for each
  surface routine; it may limit scaling toward 24 cores.

## Changes from the first draft

The first draft (commit `ac8b1a74`, as `PREVIEW-POOL.md`) moved previews to
an owned pool and judged success by tiles per second. Critique:

- **Wrong objective.** Its acceptance had no native-delivery, arrival-distance
  or shape measure. More tiles per second can still arrive behind the player,
  inside the native view or in the wrong shape.
- **It left DH's merge and ordering alone.** They are the main sources of shape
  shifts and of tiles arriving inside the native view.
- **Native first was too weak.** A floor of one preview permit still competes at
  join, which is where native delivery is slowest (28.7 s, or 66.8 s with
  previews running).
- **Threads alone cannot meet the 8-core baseline.** Two preview workers have
  a quarter of the workstation's capacity. Demand must fall instead: skip
  tiles the native view will cover, and make columns cheaper.
- **It missed coarse-cell cost.** Its Riven work targeted fine-detail trees
  only. Coarse cells cost 469 µs per column, for a reason not yet profiled.

The pool, governor, sizing and DH admission patches carry over as step 2,
with the native gate as the governor's first input.
