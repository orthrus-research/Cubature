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
- Captures with Riven's lab, from 2026-10-30:
  - a base run, and a second one repeating it;
  - one with DH's health check ignored;
  - one with DH on the client only and no distant generation.
- Preview survey: one `cubiclab preview-survey` run on the route.
- Workstation: i9-13900K, 32 logical CPUs.
- Runtime: Java 26, ZGC with 8 GiB per JVM.
- DH: 8 workers.
- Route: the land workload, a 60 s spectator flight at 16 blocks/s.
- Native view distance: 12 chunks, so 192 blocks and 15,625 view cubes.
- Repeats: the base run twice. The ignored-health and native-only runs once
  each.

### Native view

| Measure | Previews off (native only) | Base (two runs) | Health check ignored |
|---|---|---|---|
| Full view delivered after join | 23.8 s | 28.7 s, 28.8 s | 66.8 s |
| Server tick median during that | 29 ms | 31 ms, 30 ms | 47 ms |
| Server tick during flight, median / p95 | 5.7 / 33 ms | 18 / 47 ms, 20 / 50 ms | |
| View cubes missing at the client during flight (mean) | 257 | 423, 406 | 436 |

- **Previews cost the native view.** Even while DH paused them for most of
  the run, previews added 21% to the join (28.8 s against 23.8 s). In flight
  they tripled the median tick and left 60% more view cubes missing. With the
  pause off, join took 2.8 times as long.
- **The native floor is itself slow at join.** 23.8 s for 15,625 view cubes
  with no previews at all. In the JFR run, native workers spent 38 s blocked
  on Riven's regional builds (see "Where the headroom goes" below).
- **Flight keeps up.** The client trails the leading edge by about one cube
  slice with previews on, and less without them.
- **No native priority.** Nothing in Cubature puts native work ahead of
  previews. DH's health pause is the only brake, and it is one by accident.

### When LOD data arrives (base run, client side)

| Section detail | Cell | Arrivals | Distance to section at arrival, p10 / p50 / p90 | Wanted to arrival, p50 / p90 |
|---|---|---|---|---|
| 6 | 1 block | 219 | 55 / 176 / 303 | 9.6 s / 23 s |
| 7 | 2 | 152 | 304 / 464 / 1,146 | 25 s / 67 s |
| 8 | 4 | 133 | 590 / 1,007 / 1,809 | 53 s / 107 s |
| 9 | 8 | 79 | 1,104 / 1,616 / 2,128 | 77 s / 118 s |

Distances are in blocks. The repeat run agrees for detail 7 to 9 (medians
of 25, 51 and 74 s). For detail 6 its median wait was 5.9 s, and 46% of
arrivals came inside 192 blocks.

- **Full detail arrives too late.** 46 to 57% of full-detail tiles arrived
  after their section was already within 192 blocks, where native cubes take
  over. That is wasted sampling, and the most expensive kind, with exact
  trees.
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

Every tile has 4,096 columns, whatever its level. The survey below explains
the 16-block figure: horizon tiles are the first to touch new regions, and
they wait for regional builds.

### Full resolution against the atlas (preview survey)

**Method:** a lab job, `cubiclab preview-survey`, ran on a fresh server under
JFR.
- It ran Riven's real preview sampler over the atlas window below at 1, 2, 4,
  8, 16, 32 and 64-block cells.
- It sampled as Cubature samples DH tiles: one column at each cell's centre,
  one sampler per 64 x 64-cell tile, coarse cells first.
- Its records: each cell's top, ground and water tops, the kind of the top
  run, and each tile's CPU time.

**What the atlas gets right:** ground tops match `TerrainMap` in 99.82% of
columns, and water tops in all of them. The atlas is a faithful height map
for terrain and water.

**What appears only at full resolution:**
- **Trees.** Leaves are the top run in 8.0% of columns, 9 blocks above ground
  at the median (p95 14, max 21). Plants and snow layers top another 11.9%,
  at one block.
- **Cave entrances.** These are `CaveMap`'s sinkholes and rift chasms, which
  the atlas does not sample. There are 10 openings over 0.13% of columns, 107
  to 195 blocks deep. The two largest cover 1,620 and 2,308 columns, about 40
  to 48 blocks across.
- **Boulders.** 0.05% of columns, 1 to 7 blocks high.

### Shape with trees (preview survey)

Figures are p95, in blocks. "Jump" is how far a cell moves when it refines to
its children's level.

| Cell | Today vs blocks | Terrain only | Today's jump | Exact centre trees vs blocks / jump | DH max merge of full detail | Nearest-mean merge of full detail |
|---|---|---|---|---|---|---|
| 2 | 2 | 1 | 2 | 2 / 2 | 2 | 1 |
| 4 | 11 | 2 | 11 | 3 / 2 | 7 | 3 |
| 8 | 11 | 3 | 3 | 6 / 6 | 12 | 6 |
| 16 | 11 | 6 | 6 | 9 / 9 | 16 | 8 |
| 32 | 13 | 11 | 10 | 13 / 12 | 25 | 12 |
| 64 | 20 | 19 | 18 | 20 / 18 | 40 | 19 |

- **Trees dominate coarse error.** At 4- to 16-block cells, coarse error is
  p95 11 with trees, against 2 to 6 for terrain alone.
- **The largest jump is 4 to 2 blocks, p95 11.** At 4-block cells and up
  (`coarseFootprintBlocks` = 4), Riven draws a stand canopy; below, it draws
  exact trees. Exact trees at the cell's centre column would cut that jump
  to 2.
- **The stand canopy overdraws at 4 to 8 blocks.**
  - At 4-block cells it draws canopy on 61% of cells only 1 to 25% under
    crowns, and on 2.4% of cells with none.
  - From 16 blocks it follows the majority: canopy on 87 to 99% of cells more
    than a quarter covered, and under 7% of cells below that.
- **A merge change alone is not enough at 4 to 8 blocks.** Today's canopy cells
  differ from a nearest-mean merge of full detail by p95 10 and 9 blocks. With
  exact centre trees the difference is 2 and 4.

### Where the headroom goes (survey profile and land JFR)

Survey cost per column, in µs (p50, with p90 where tiles vary):

| Cell | 64 (first tile, cold) | 32 | 16 | 8 | 4 | 2 | 1 |
|---|---|---|---|---|---|---|---|
| µs per column | 2,502 | 130 | 48 | 71 | 65 | 103 / 633 | 73 / 210 |

**Regional builds, not coarse columns.**
- Once an area's regional plans exist, coarse columns cost 48 to 130 µs.
- The first tile into the area took 11.8 s, of which only 2.6 s was CPU on
  its own thread. It waited for its continent's drainage network.

**Hydrology blocks on the shared ForkJoin pool.**
- **How it parks.** `Drainage.build` scans a continent with parallel streams.
  Each scanned point's `TerrainMap.intent` asks `Hydro.receivingWater`, which
  needs the 2,048-block `Hydro.plan` the point lies in. One task builds that
  plan while the others park. In the land run, pool threads spent 972 s
  parked in `Hydro.plan`, and the pool grew to 63 threads to replace them.
- **Outside the budget.** The pool's parallelism is P − 1: 31 on the
  workstation, 15 on an 8-core machine with SMT. Cubature's worker budget does
  not cover it.
- **Ticks during bursts.** In the land run the pool held 9% of server CPU
  samples, in bursts:

  | Common-pool work in that second | Mean tick | Seconds with a tick of 50 ms or more |
  |---|---|---|
  | A burst (100+ samples) | 98 ms | 90% |
  | None | 20 ms | 17% |

  That is the tick that pauses DH's generation.
- **Blocked workers.** Regional builds also block the workers that need them:

  | Threads | Blocked in total | Longest single wait |
  |---|---|---|
  | Cubature native generation | 38 s | 3.3 s |
  | DH World Gen | 56 s | 2.5 s |
  | Server thread | 1 s | 537 ms (in `Drainage.prepare`) |

  DH World Gen's waits were mostly cold-terrain eligibility (23 s), drainage
  (16 s) and hydrology plans (9 s).

**Tree sites at full detail.**
- **Whole chunk columns.** `NativeColumns.build` is on the stack in 39% of
  samples at 1-block cells and 70% at 2-block cells, mostly through
  `TreeSites.surface`. That builds a whole native chunk column (256 columns,
  with caves at 12 to 23% and ore bodies at 11 to 19%) to read one column's
  surface. `GroundCover.waterBeside` does the same for canes.
- **Nested builds.** The crowding check builds the earlier layers' nearby sites
  first, and 22% of full-detail samples sit inside such a nested build.
- **No unbounded recursion.** The deepest Riven stacks are about 60 frames, and
  the other repeated frames in the profile are overloads calling each other.

**Coarse cells build boulders they never draw.** `GroundCover.preview` asks
`Boulders.at` at every footprint, but `TerrainColumn.preview` leaves boulders
out from 4-block cells. This costs 63% of samples at 32-block cells, 25% at
16 and 11% at 8.

**Hash maps degraded to trees.** `HashMap$TreeNode.getTreeNode` takes 9.2% of
self time at 2-block cells and 5.3% at 1-block cells, through `CaveMap.family`
and `OreMap.patch`.

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
  centre sample's. With trees, its error against the blocks is p95 3 to 19
  blocks at 4- to 64-block cells, against 7 to 40 for DH's merge (survey
  table above).

## Plan

### 1. Native view first (Cubature)

- **Native debt.** Cubature's `PlayerCubeMap` already keeps `cubesToGenerate`
  and `cubesToSendToClients`. Their sizes are the native debt.
- **The gate** (done, `MixinDistantHorizonsNativeFirst`). Previews start only
  while the debt is below one face of the view, `(2 x view distance + 1)^2`
  cubes (625 at distance 12); regional preparation shares the rule
  (`CubeDemand.nativeBehind`). At join and teleport, previews therefore wait
  for the native view, while steady flight stays under the gate. Land route,
  two runs each, after Riven's flood-cell and climate-lattice memos: the first
  preview tile finished 29 and 34 s after full view began, against 11 and 12 s
  without the gate; full view took 25.5 and 28.1 s, against 28.6 and 31.1 s
  without it and 29.8 and 28.4 s with previews off. Those memos had already
  closed most of the join gap, so on the workstation the gate mostly guarantees
  the order; flight is unchanged (median tick 22 to 27 ms with previews, 9 to
  10 ms without), which step 3 addresses.
- **Covered sections.** Waiting preview tasks for sections fully covered by
  sent native columns are cancelled, through `removeRetrievalRequestIf` on
  DH's world generation queue. Cubature's native snapshots supply DH there.
- **The native-only floor** is measured: 23.8 s at join, with a 5.7 ms median
  tick in flight (`riven_observe.py capture --dh-visited-only`). The gate's
  target is to stay within 10% of it.

### 2. Riven headroom (Riven)

Each change keeps content identical: preview and native output agree (the
parity references), and the golden atlas digests do not change. The first two
items free the native view as much as the preview.

1. **Hydrology off the shared pool, without parking.**
   - **Plans before the scan** (done in Riven). Before `Drainage.build` scans
     a continent, Riven builds the `Hydro` plans under its land, one task per
     plan, so no scan task waits on another's plan. Riven's
     `RegionalCacheProbe` (12 dispersed players, cold pass) measured
     common-pool waits on plans falling from 305 s to 12 s at 8 km and from
     438 s to 6 s at 32 km. At 8 km the common pool kept its 31 threads
     instead of growing to 53.
   - **A plan memo sized to the world** (done in Riven). The plan memo grows
     with the layout scale squared: 512 plans at 8 km and 8,192 at 32 km,
     where 12 dispersed players read 726. At 32 km, plans rebuilt on
     repeated demand fell from 89 to 0.
   - **Single builds publish before release** (done in Riven). A miss that
     raced a finishing build no longer builds the key again.
   - **A bounded pool** (done). Each world offers its generator a build pool
     through `ICubeGenerator.useBuildPool` (`generatorBuildThreads`, a quarter
     of the processors, 1 to 4, at most twice that while tasks block), and
     Riven runs every in-build loop there, never the JVM's common pool.
     Running on the calling worker alone was measured and rejected: 32 km
     network builds took 13.1 s on average (41 s at worst) against 4.7 s.
     - **Offline,** with 12 players: common-pool waits fell to zero at both
       sizes, and network builds averaged 1.15 s at 8 km and 4.8 s at 32 km.
     - **On the land route:**
       - full view 30.4 s against 28.7 and 28.8 s;
       - tick p95 62.5 ms in the full view and 45.7 ms in flight,
         against 70 to 71 and 47 to 50 ms;
       - flight tick median 22.8 ms against 18 to 20 ms;
       - DH paused generation in 51% of samples against 57%.
     - **Trade-off:** tails improved, while first-touch work slowed with 4
       threads instead of 31. Step 2 item 2 is what recovers that time.
   - **Target:** no common-pool bursts, and no 50 ms ticks in the seconds that
     had them.
2. **Regional plans before a worker needs them** (done). Riven's
   "Preparation within bounds" (2026-10-30) allows it and sets its limits.
   - **How it runs.** Each world's `RegionalPreparation` asks the generator
     for regions ahead of the players on the build pool, ranked by time to
     reach.
     - Regions within the native view run up to the pool's size; those
       beyond run under a contention governor, which reads CPU load and the
       server thread's wait rather than tick time.
     - Nothing starts while more than one face of the native view waits to
       generate or be sent.
   - **What Riven prepares** for each 2,048-block region: its continents'
     drainage networks, its hydrology plan, and its sediment and rock
     decisions.
   - **Its bounds.** Riven declines past a quarter of a set-associative
     memo, half the drainage memo, or 10 MB x layout squared of networks.
     Networks were made compact first: a whole 32 km world's take 138 MB.
   - **Offline, 8 km, 12 players:** preparing the world took 13.5 s. The
     cold pass then built 4 networks instead of 42 and waited 0 s on them
     instead of 39 s.
   - **On the land route:**
     - full view unchanged at 30.4 s (40.2 s without the native hold);
     - preview sampling p90 1,441 ms against 1,821 ms;
     - flight tick median 25.0 ms against 22.8 ms.
3. **One-column surface queries.** `TreeSites.surface` and
   `GroundCover.waterBeside` take 30% and 10% of full-detail time. The survey
   shows the native surface equals the `TerrainMap` top except at cave
   openings. So a query needs that top and one column's carving, without ores
   or the other 255 columns. The survey verifies it over 4.2 million columns.
4. **The crowding cascade.** Measure what is left after item 3. Crowding needs
   the earlier layers' valid trunks, which today means their full site
   builds.
5. **No boulder lookups at coarse cells.** Skip `Boulders.at` in
   `GroundCover.preview` from 4-block cells, where boulders are not drawn.
6. **Hash keys and scratch.** Fix the colliding keys in `CaveMap.family` and
   `OreMap.patch`. Move the per-column arrays of `TerrainColumn.preview` and
   `TreeSites.canopy` into the task-confined sampler. On Cubature's side,
   cache `BlockStateWrapper` per block state in the preview writer.
7. **Later.** `StrictMath` takes 10 to 20% of sampler time. Replacing it
   changes terrain, so it needs a new terrain version and its own decision.

**Budget for the 8-core baseline:** two preview workers must supply about
3 tiles/s for 16 blocks/s flight once step 1 skips covered tiles. That is
0.6 s or less per tile. In the survey, the p90 per tile is 0.86 s at 1-block
cells and 2.6 s at 2-block cells, so items 3 and 4 decide whether the
baseline holds.

### 3. Owned pool and governor (Cubature)

Done (`PreviewPool`, `PreviewGovernor`, `LivePermits`; Riven
`docs/DECISIONS.md`, 2026-10-30, "Bounded pools for preview sampling"). DH's
World Gen executor can only be on or off, and its health pause stopped
previews in 46 to 58% of samples. Cubic previews therefore run on a Cubature
pool, one per server, in place of DH's executor: DH hands the generator its
executor, and the generator samples on the pool instead, so no Mixin is
needed for that.

**What previews cost the tick** (land route, after Riven's flood-cell and
climate-lattice memos):
- **Flight tick by DH workers**, p50 / p95: previews off 9.5 / 36 ms; 1 worker
  14.3 / 39.5; 2: 16.7 / 40.9; 4: 22.1 / 42.6; 8: 22 to 27 / 45 to 47. About
  4 ms per running tile, plus about 5 ms for DH being there at all.
- **Not cores, not waits.** A JFR of the flight with 8 workers: the server
  thread ran 42% more on-CPU samples on the same native work and never
  waited. GC ran 24 s of the 62 against 3.9 s with previews off, and preview
  threads allocated 72 GB of the 131 GB (Riven's `TerrainColumn.preview`
  25 GB, `NativeColumns.build` 24 GB). About a third of the extra time is DH's
  native feed on the server thread (`CubicChunkSnapshot.capture`), the rest
  the same work running slower. Neither the machine's CPU load nor the server
  thread's waits, which regional preparation's governor reads, show this; the
  ticks do.

**Admission:**
- DH still chooses which tile comes next.
- `MixinDistantHorizonsNativeFirst` on `WorldGenerationQueue.isGeneratorBusy`
  admits at most the pool's permits plus one tile, so DH's choice stays
  current.
- `GeneratedFullDataSourceProvider.getMaxRetrievalQueueCount` stays DH's: 20 x
  `numberOfThreads` (160 at 8) bounds the waiting queue DH chooses from, far
  more than the permits need, so it is not patched.
- Completion runs DH's update on the pool thread: 11 ms against 365 ms of
  sampling per tile.

**Sizes** (`P` is the number of logical CPUs):

| P | Native workers `clamp(P/4, 1, 4)` | Preview ceiling `clamp(P/4, 1, 8)` | DH `numberOfThreads` `clamp(P/4, 2, 8)` |
|---|---|---|---|
| 8 | 2 | 2 | 2 |
| 16 | 4 | 4 | 4 |
| 24 | 4 | 6 | 6 |
| 32 | 4 | 8 | 8 |

The pool is `previewThreads` (by default the preview ceiling); native workers
and DH's `numberOfThreads` keep their settings until step 6.

**Governor:** the tick's tail within budget (chosen 2026-10-30): a tick has
50 ms, and previews may use what keeps the p95 tick at or under 45 ms. It
starts at half the ceiling and acts once a second.
- **Halve permits** when the p95 of the last 20 ticks is 45 ms or more and
  tiles ran in that second (a join's slow ticks, while the gate holds every
  tile, are the native view's own).
- **Add one permit** when tiles are waiting, two seconds have passed since
  the last change, and the p95 of the last 40 ticks is under 40 ms.
- **Zero permits** while step 1's gate is closed; otherwise at least one.
- **Fast response:** a tile above the permit count waits between its rows, so
  a cut acts within one row, about 5 ms.
- The server log carries a line a minute with the permits' range and mean.

**Result** (land route, 8 DH workers, the pool's 8 threads): the flight tick's
p95 was 44.0, 44.4 and 44.6 ms in three runs, against 47.0 and 45.2 with the
gate alone and 36.3 and 36.4 with previews off. The median stayed at 23.7 to
24.4 ms, since the target is the tail. The runs made 669 to 699 tiles, against
577 and 679, and full view took 26.6 to 28.4 s. In flight the permits averaged
2.1 to 3.3, between 1 and 8, with about 12 changes a minute; through the join
they hold at 4.

`previewThreads = 0` keeps DH's executor, for A/B runs and as a fallback.

### 4. One coarse shape (Cubature and Riven)

**The merge patch:** an LGPL Mixin on `FullDataSourceV2`'s 2 x 2 merge.
- **Where it applies:** where all four children are generated previews
  (generation step `SURFACE`).
- **What it keeps:** the child column whose top is nearest the four tops'
  mean, with its runs and light.
- **Elsewhere:** where any child carries native data, DH's rule stays, so thin
  built structures do not vanish at distance. Whether natural terrain in
  visited areas should switch too is an open question.

**The generator:**
- **Terrain** keeps its centre sample. Up to 16-block cells, its error is
  within a block of the cell mean's.
- **Exact trees** at the cell's centre column, instead of the stand canopy,
  up to 8-block cells at least. Raise `coarseFootprintBlocks` from 4 to 16,
  then measure the cost: exact trees need tree sites, which item 3 of step 2
  makes cheaper. Native blocks do not change, but it is a definition value,
  so its fingerprint changes and it needs fresh instances.
- **Above that cut-off,** the stand canopy stays, since it already follows the
  local majority from 16 blocks.

**The shape metric** is the preview survey: `cubiclab preview-survey` plus
`survey_compare.py`, moved into the lab and Riven's tools. It runs the real
sampler at every footprint, trees included, merges as the patched merge does,
and reports error, jump and shift by cell size. A 4 x 4 grid for terrain is
added only if the metric asks for it at 32 and 64 blocks.

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
| Native full view at join, previews off | 23.8 s | shorter, after step 2 items 1 and 2 |
| Native full view at join, previews on, relative to previews off | +21% (28.8 s) | within 10% |
| Server tick median during flight, previews on | 18 to 20 ms (5.7 ms off) | 10 ms or less |
| View cubes missing at the client during flight (mean) | 406 to 423 (257 off) | 300 or fewer |
| Full-detail tiles arriving inside the native view | 46 to 57% | under 10% |
| Wanted to arrival, p50, detail 6 / 7 / 8 | 6 to 10 / 25 / 51 to 53 s | 3 / 7 / 20 s or less |
| Coarse error with trees, p95 at 4 / 8 / 16-block cells | 11 / 11 / 11 blocks | 3 / 6 / 9 or less |
| Refinement jump from 4 to 2 blocks, p95 | 11 blocks | 3 or less |
| Parent shift when full detail arrives, terrain, p95 at 16-block cells | 11 blocks | 4 or less |
| Seconds with common-pool bursts (100+ samples) | 11 in the land run | none |
| Worker time blocked on regional builds | 38 s native, 56 s DH | under 5 s each |
| Server tick during flight | median 15 ms, p90 41 ms | median 20 ms or less, p90 45 ms or less |

## Phases

1. **Native first and hydrology:** step 1, and items 1 and 2 of step 2. Both
   take load off the native view.
2. **Owned pool and governor:** step 3.
3. **Cheaper columns:** items 3 to 6 of step 2, one change at a time, each
   checked by the survey.
4. **One coarse shape:** step 4, the merge patch and exact centre trees, gated
   by the survey.
5. **Arrival ordering:** step 5.
6. **DH pool sizing and integrated servers:** step 6.

## Decisions this needs

- **Preview pools.** Decided 2026-10-30 (Riven `docs/DECISIONS.md`, "Bounded
  pools for preview sampling").
- **DH's merge rule.** Changing it alters how DH reduces generated data
  everywhere. It needs its own entry, and a second one if it later covers
  native data.
- **Riven's parallelism.** Decided 2026-10-30 (Riven `docs/DECISIONS.md`,
  "Cubature supplies Riven's in-build executor").
- **Regional plans ahead of the player.** Decided 2026-10-30 (Riven
  `docs/DECISIONS.md`, "Preparation within bounds"), which supersedes the "no
  preparation coordinator" constraint.
- **DISTANT-HORIZONS.md.** Updated with step 3: Cubature owns when previews
  start and how many run; DH keeps the choice of tile, its request limits,
  propagation, persistence and delivery. Order (step 5) and the merge (step 4)
  follow with those steps.

## Open questions

- **Exact trees at coarse cells.** What do exact centre-column trees cost at
  4- to 16-block cells once surface queries are one column?
- **Cave entrances at distance.** Sinkholes and chasms 40 to 48 blocks wide
  appear only where a coarse centre sample falls inside one. Should coarse
  cells show an entrance that covers most of their footprint?
- **Native data merge.** Should the nearest-mean merge also apply to native
  data? Today that data stays inflated next to unvisited areas, but switching
  could thin built structures at distance.
- **The lost full-view teleport.** The full-view teleport sometimes never
  completes: in both attempts with 16 DH workers on 2026-10-29, and once with 8
  on 2026-10-30, after Riven's generation got faster; the 16 runs since, at 8
  and 16 workers, all passed. In each traced failure the client stayed at its
  join position and stopped building native sections about 11 s in, as full
  view began, while DH data kept arriving, and the server, which sent cubes
  throughout, never moved the player. Cubature logs a held teleport that ends
  without completing, and the lab's failure says where the server has the
  player and whether its teleport is still held; the next failure should show
  which side loses it.
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
  only. The coarse cost turned out to be cold regional builds.

The pool, governor, sizing and DH admission patches carry over as step 3,
with the native gate as the governor's first input.

**Second revision.** The preview survey measured trees and profiled the cost.
- **Trees dominate coarse error.** They, not terrain, set the coarse error, and
  the stand canopy's switch to exact trees is the largest jump.
- **Hydrology is the biggest headroom loss.** It blocks on the shared ForkJoin
  pool, which lines up with the 50 ms ticks, and it stalls native workers.
- **The plan changed to match.** It gained step 2, Riven headroom, and exact
  centre trees in step 4.
