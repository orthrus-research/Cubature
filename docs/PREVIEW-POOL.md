# Cubature preview pool (design draft)

Status: draft on branch `preview-pool-design`, not implemented. When it is,
the behavior described here moves into [DISTANT-HORIZONS.md](DISTANT-HORIZONS.md)
and this file goes.

Cubature samples Distant Horizons surface previews on DH's World Gen
executor today. This design moves that work to a Cubature-owned pool whose
concurrency follows the server's tick time and the machine's processor count.
DH keeps choosing which tile comes next, persistence, propagation and delivery.
It also lists the Riven sampler changes that make every tile cheaper, since
that gain does not depend on core count.

## Constraints

- Riven `docs/DECISIONS.md`, adopted 2026-10-30:
  - **8-core baseline that scales up.** Sizes derive from the processor count:
    8 cores is the median player, and the design must keep improving up to
    24 cores.
  - **Own the DH pipeline.** Cubature owns DH 3.3.3's generation, scheduling
    and delivery, through its own code where DH's API allows it and Mixins
    otherwise.
- **Timing, never content.** The pool changes when a tile is sampled, never
  what it holds. `ICubicTerrainPreview` samplers are task-confined and pure;
  Riven's determinism rule (request order, threads, caches and eviction must
  not change results) still holds.
- **DH stays unmodified.** The DH JAR stays byte-for-byte. Each new DH Mixin
  carries LGPL-3.0-only terms, is a required match bound to 3.3.3, and is
  recorded in `NOTICE.md`, as `MixinDistantHorizonsQueueBackoff` is. The pool
  and governor themselves are Cubature code against DH's public API.
- **No emulation.** Sizes come from the formula below. Measurements run on
  the workstation as it is.

## What the measurements show

Riven captures from 2026-10-30, kept as local evidence in Riven `.local/`:
`lod-reports/ab3-land-base`, `ab3-land-health` and `ab2-land-jfr-trace`.

**Setup:**
- Workstation: i9-13900K, 32 logical CPUs.
- Runtime: Java 26, ZGC with 8 GiB, separate client and server JVMs.
- DH: 8 shared workers per JVM.
- Route: the land workload `cube-flight-land.json`, a 60 s spectator flight
  at 16 blocks/s over land.
- Repeats: one run per condition. Two earlier baselines agreed within 1%.

Each generated tile's slot, with DH's health check on (531 tiles in 126.5 s):

| Part of the slot | Total | Per tile |
|---|---|---|
| Waiting for a DH thread | 672 s (65%) | p50 205 ms, p90 4.6 s |
| Riven sampling | 374 s (35%) | p50 365 ms, p90 2.0 s |
| Handing back to DH | 0.03 s | 0.05 ms |
| DH update after the slot | 6.7 s | p50 11 ms |

**Why previews wait:** three DH rules starve them.
- **The health pause.** DH's World Gen executor runs only while the server is
  "healthy": the average of the last 100 ticks is under 25 ms and their 99th
  percentile under 50 ms. It was paused in 58% of samples, and DH counted the
  server unhealthy in 64% of checks. The median tick was only 15 ms (p90
  41 ms), so the 99th-percentile rule did the pausing: the mean p99 was 57 ms,
  the mean average 22 ms.
- **The shared picker.** All six DH executors share one pool of
  `numberOfThreads`, served by priority, then by least total runtime. World
  Gen does by far the most work, so every other executor's queued task goes
  first.
- **The in-flight cap.** DH starts no more generation than `numberOfThreads`
  plus one.

**With the health check ignored** (804 tiles in 160 s):
- World Gen never paused.
- Generation then queued behind the Update Propagator (21 tasks queued, 2.4
  running on average).
- The server tick median rose from 15 to 35 ms, and the p90 from 41 to 58 ms.

Removing DH's pause without governing CPU harms the tick, and an 8-core
machine has less slack than this one.

**Sampling cost** (JFR, diagnostic mode, so the figures carry profiler
overhead): the preview is 42.6% of server CPU samples.

Within the sampler, by share of its samples:

| Where the time goes | Share |
|---|---|
| `TerrainColumn.preview` | 64% |
| Of which tree replay | 50% |
| `TerrainMap.sample` directly | 31% |
| `NativeColumns.build` on the stack | 40.1% |
| ...through `TreeSites.surface` | 29.7% |
| ...through `GroundCover.waterBeside` | 10.4% |
| `StrictMath` self time (all functions) | 19.3% |
| ...hypot, cos and sin | 14.5% |

- **Allocation:** the server allocates 3.2 GB/s, 54% of it in the preview. The
  largest sampled types are `TerrainBlock[]`, `int[]` and `double[]`.
- **Allocation stalls:** ZGC stalled each preview thread for 30 to 54 ms over
  the run.
- **Colliding hash keys:** tree-binned `HashMap` lookups come from
  `CaveMap.family`, DH's `BlockStateWrapper.fromBlockState` and
  `OreMap.patch`.

## Design

### Where previews run

`CubicTerrainPreviewGenerator.generateLod` submits to `CubicPreviewPool`
instead of DH's executor:
- **The pool:** one per server, shared by every cubic level's preview
  generator, because the CPU budget is per machine.
- **Threads:** daemons named `Cubature Preview #n` at `NORM_PRIORITY - 1`, as
  in `ConcurrentGeneration`. Priority is a hint only: HotSpot honors it on
  Windows and ignores it on Linux by default, so the governor, not priority,
  protects the tick.
- **Completion:** the task completes DH's future on the pool thread, and DH's
  update after the slot runs there as it does today on DH's thread. That
  update is about 3% of sampling time (11 ms against 365 ms per tile), so
  handing it to another executor would add a queue for no gain.

Cubic previews then never enter DH's World Gen executor, so its gates (camera
speed, LOD-builder backlog, server health) and the shared picker no longer
apply to them. The governor takes their place. `limitIfServerUnhealthy` stays
on in fresh instances, because it still guards non-cubic levels.

`previewThreads = 0` keeps today's path on DH's executor, for A/B runs and as
a fallback.

### Admission: DH chooses what, Cubature chooses how many

DH's `WorldGenerationQueue` starts tasks one at a time:
- **Which:** the closest waiting task first, coarse detail first (distance
  divided by detail).
- **When it stops:** once more than `numberOfThreads` tasks are in flight.
- **When it resumes:** after every completion, and every 20 ms from
  `LodRequestModule`'s own loop through `startAndSetTargetPos`.

Two Mixins replace DH's thread count with the pool's capacity:

- **`WorldGenerationQueue.isGeneratorBusy`:** for a cubic preview generator,
  DH counts as busy once its in-flight tasks reach the pool's permits plus one.
  The pool thus never holds more than one waiting tile, so DH's nearest-first
  choice stays current as the player moves, and a new permit is used within
  about 20 ms.
- **`GeneratedFullDataSourceProvider.getMaxRetrievalQueueCount`:** DH sets
  this waiting-request cap to 20 x `numberOfThreads`. It becomes 20 x the
  pool's ceiling, so a smaller DH pool does not shrink the preview backlog.

DH keeps its request rate limits, propagation, persistence and delivery.

### Sizing from the processor count

`P` is `Runtime.availableProcessors()`, the logical CPU count. Each pool's
size follows from it:

- **Native generation:** `G = clamp(P/4, 1, 4)`, as today.
- **Preview ceiling:** `W = clamp(P/4, 1, 8)`.
- **DH's `numberOfThreads`:** `D = clamp(P/4, 2, 8)`. Generation has left this
  pool, which now runs IO, compression, propagation, LOD building and render
  loading. Fresh instances set `D` instead of a fixed 8.

| P | G | W | D |
|---|---|---|---|
| 8 | 2 | 2 | 2 |
| 16 | 4 | 4 | 4 |
| 24 | 4 | 6 | 6 |
| 32 (workstation) | 4 | 8 | 8 |

- **At the baseline:** native generation and previews together get at most
  half the logical CPUs up to 16, leaving the rest for the server thread, the
  client render thread, GC and DH's own pool.
- **On the workstation:** `D` stays at today's 8, so the comparison changes
  only where previews run.
- **Above the cap:** `W` stops at 8, where measurement on the workstation
  starts. It rises only if the governor holds the tick there.

### Governor

The governor reads the server's own tick times (`MinecraftServer.tickTimeArray`,
which DH's health check also reads) once per tick on the server thread. Once a
second it takes the median and 90th percentile of the last 20 ticks. These
statistics are robust to the single long ticks that made DH's 99th-percentile
rule pause so often. It then sets the permit count:

- **Decrease:** if the median is 30 ms or more, or the p90 50 ms (a whole tick)
  or more, permits are halved, with a floor of 1.
- **Increase:** if the median is under 20 ms, the p90 under 45 ms, and DH has
  at least as many tiles waiting as there are permits, add one permit, up to
  `W`.
- **Otherwise:** hold.
- **Native first:** while `ConcurrentGeneration` is saturated, or a teleport
  arrival is waiting in `DeferredArrivals`, permits cap at the floor. The view
  the player stands in comes before the horizon.
- **DH's backpressure:** when DH has more than 1,000 chunk-to-LOD tasks
  queued, which is its own `chunkBuilderOverloaded` limit, permits also cap at
  the floor.

The floor is 1, so previews never stop. DH stopped them for 58% of samples.

The thresholds derive from Minecraft's 50 ms tick, not from core counts. They
are starting values to tune against the trace: the base run (median 15 ms, p90
41 ms) would gain permits, and the health-ignored run (35 ms, 58 ms) would
lose them.

**Response time:** a tile is not preemptible, and its p90 is 2.0 s. The sampler
loop already checks for closing once per row, so it also checks the permits
there. A worker above the current permit count parks between rows until a
permit frees. That makes the response about one row (a 64-column row of a
2-second tile takes about 30 ms) plus the one-second window.

### Completion, cancellation and failure

- **Closing:** DH closes the generator when it stops world generation. The pool
  then shuts down its workers, and the per-row check throws `CancellationException`
  as it does today.
- **Stale requests:** DH drops waiting requests from its own map, never from
  inside the pool. The pool holds at most one waiting tile, so a stale tile
  costs at most one sample.
- **Failures:** they propagate through the future, as today.

### Cheaper samples (Riven)

Riven owns these changes. Each keeps preview and native output identical,
checked by the parity references, with the golden atlas digests unchanged.

1. **One-column queries.** `TreeSites.surface` and `GroundCover.waterBeside`
   each need one neighbor's surface height or one block. To get it, they
   resolve a whole native chunk column: all 256 columns, with caves and ores.
   That is 40.1% of sampler time. Cave carving and ore bodies are computed
   per column in `NativeColumns.build`, and ores only replace rock, so a
   one-column query of the same steps should give the same answer for a
   fraction of the work. First check that every step is per column
   (unstable blocks, for one); the parity references decide.
2. **Scratch reuse.** `TerrainColumn.preview` allocates per column:
   - run bottoms and blocks;
   - a 256-entry kept-band column;
   - a tree recorder and canopy.

   `TreeSites.canopy` allocates its column arrays per call. These move into
   the task-confined sampler, which overwrites them for each column.
3. **Hash keys.** The colliding keys in `CaveMap.family` and `OreMap.patch`
   need better mixing. On Cubature's side, the preview writer calls
   `BlockStateWrapper.fromBlockState` for every run. It caches the wrapper per
   block state for the sampler's lifetime.
4. **Later, content-changing.** `StrictMath` takes 19.3% of sampler time.
   Faster replacements change terrain, so they need a terrain version, a
   golden atlas update and their own decision.

### Observability

The lab trace adds two kinds of row:
- **`preview-pool` rows, four times a second:** permits, running, waiting,
  and tiles DH holds in flight.
- **A `governor` row per decision:** window median and p90, permits before and
  after, and the rule that fired.

`riven_observe.py lod` then reports the slot's first part as "waiting for a
permit", with permits over time beside the tick percentiles.

## Acceptance

All runs on the workstation's land route, traced, two runs per condition.
- **Throughput:** at least 5.0 tiles per second, which beats the best DH
  configuration measured (health ignored, 804 tiles in 160 s).
- **Server tick:** median 20 ms or less and p90 45 ms or less over the route
  (baseline 15 ms and 41 ms).
- **Previews never stop:** the pool always holds at least one permit, and DH's
  World Gen executor carries no cubic preview work.
- **Correctness:** every lifecycle scenario passes and no teleport is lost.
  Saved geometry still agrees, under the current DH scope.
- **No regressions:** the stationary horizon drain is no slower than with DH's
  8 workers (29 s, Riven `docs/RUNTIME.md`).

The Riven changes are measured the same way, plus:
- per-tile sampling time by detail level;
- the allocation rate (JFR, compared only between runs that both had it on);
- unchanged atlas digests.

## Phases

1. **Pool, admission and governor** (Cubature, with `previewThreads` and its
   `0` fallback), plus the trace rows. Measure against `ab3-land-base` and
   `ab3-land-health`.
2. **Riven:** one-column queries, then scratch reuse and hash keys, one change
   at a time.
3. **DH's `numberOfThreads`** from `P` in fresh instances.
4. **Integrated server:** the client's frame time as a second governor input.

## Decisions this needs

- **Preview pools:** extend the 2026-10-23 approval of bounded Cubature
  worker pools for pure cube generation and storage writes to preview
  sampling.
- **DISTANT-HORIZONS.md:** it currently says the adapter creates no private
  worker pool and that DH owns admission. Cubature would own how many previews
  run; DH keeps choosing which, plus rate limits, propagation, persistence and
  delivery.

## Open questions

- **The 16-worker failure.** With 16 DH workers, the full-view teleport was lost
  in both attempts, and the cause was never diagnosed. Repeat it with the pool
  at its ceiling.
- **Integrated servers.** In one JVM, previews compete with rendering, which
  the server tick does not show. The lab records frame intervals, but no
  integrated run has been measured.
- **Fast flight.** DH pauses its generation while the camera moves fast; the
  pool does not. Check whether previews during fast flight delay the client's
  LOD building.
- **DH's request limit.** Fresh instances allow 40 generation requests,
  `generationRequestRateLimit`. The trace's `srv-request` rows will show
  whether that binds once the pool is faster.
- **Biome locks.** `SurfaceTranscription` runs each biome's surface routine
  under that biome's lock. JFR logged about 0.5 s of waits over its 20 ms
  threshold, mostly on Biomes O' Plenty Highland and Crag, from 4 native and
  8 DH threads. Same-biome sampling serializes there, so it may cap scaling
  toward 24 cores.
