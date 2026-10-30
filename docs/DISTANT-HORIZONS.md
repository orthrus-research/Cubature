# Distant Horizons integration

The optional client/server integration targets the unmodified Distant Horizons 3.3.3
Minecraft 1.12.2 Forge artifact. The reviewed binary has SHA-256
`48c3ace5c35c471893d3dc78de79e3dde229df0179a59352307a2e1c8f60e613`.
Its embedded source identity is DH commit `575517f261dfd04e302a37719f1459e9b5633d9f`
and core submodule `6c1be680011194b2cf04ef842ace40c50683b09c`.
Sources are available from the [DH repository](https://gitlab.com/distant-horizons-team/distant-horizons).
No DH implementation or binary is currently copied into Cubature's distributable.

Cubature supplies the runtime compatibility and optimization patches through
Mixins or ASM. "Unmodified upstream" describes the separately installed JAR
on disk; the adapter intentionally transforms selected classes at runtime.
Each patch must preserve applicable source licenses and notices. Moving a
copied or adapted implementation into a Mixin does not remove its license.
See [modification and source notes](../MODIFICATIONS.md).

`MixinDistantHorizonsQueueBackoff` targets the three failed-dispatch requeue
sites in DH 3.3.3: debounce, unavailable executor and executor rejection.
After upstream has put the item back, it requests a 2 ms park only when
there is no pre-update work waiting. This prevents a nonempty but blocked
queue from repeatedly polling and reinserting the same item at full CPU.
It retains upstream priorities, the 250 ms debounce, successful dispatch,
queue ownership and interrupt-driven shutdown. OS scheduling can extend the
requested park; a new arrival during it is handled on the next iteration.
All three sites are required Mixin matches, so another DH version requires
fresh review. This patch carries LGPL-3.0-only terms as recorded in `NOTICE.md`.

Riven's `tools/measure_pipeline.py` runs a reproducible fixed-view
comparison of two Cubature and Riven builds. Its optional
lab observer records per-worker CPU and allocation counters, complete native
view delivery and frame intervals. Qualify those scopes separately from DH
coverage, presentation latency, water rendering and long traversal.

DH's 1.12.2 receive hook observes vanilla chunk packets. Cubic packets bypass it,
and the vanilla DH reader assumes sixteen section-array entries and Y 0..255.
CC supplies three adaptations:

- After cube decoding and block/height/light packet updates, a client hook marks
  the affected column dirty. It coalesces notifications for three client ticks,
  starts at most two snapshots per tick and stops starting more after 2 ms.
  An individual snapshot is not preemptible. No new worldgen workers are created.
- `CubicChunkSnapshot` copies received blocks, biome IDs and native light arrays
  on the client owner. DH workers read that payload, including copied height
  maps shared by snapshot copies. Missing sections defer capture. A surface and
  air above it must be received for every position in the column.
- Optional DH level mixins supply CC's real bounds. A lighting adapter lets
  these snapshots carry CC's block lighting through DH's existing update queue.
  Ordinary DH chunks and noncubic worlds keep their normal paths.

The visited-only lane needs DH only on the client. The automatic distant lane
installs the same unmodified DH artifact on both sides. Without DH, the common
notification facade has no installed listener.

## Automatic surface previews

`ICubicTerrainPreview` is an optional generator capability, independent of DH.
It creates task-confined samplers which emit a biome and contiguous block runs
for a complete vertical column. The sampler must use the native generator's
content rules and may not access or load a live world. Riven shares its block
resolver between normal cube filling and this sampler; its terrain, temperature,
plate and erosion maps remain unchanged.

CC binds a DH `API_DATA_SOURCES` generator for the server level. DH supplies its
executor, request detail and pooled 64-column-wide data source. CC samples the
native column at each requested cell's integer center, writes relative heights
using the real world minimum, and returns the data through DH's normal future.
At coarse levels this is a surface approximation. It creates no gameplay cubes,
terrain cache, private worker pool or population work. DH owns admission, rate
limits, propagation, persistence, server delivery and rendering. Pooled sources
are retained only until their generation future completes; closing cancels work.

Minecraft 1.12.2's DH startup forces `INTERNAL_SERVER` and hides its built-in
rough-generation plans. CC keeps that global fallback intact. Its world packet
adds an optional capability byte, absent on older servers. Only a capable cubic
level uses an effective `SURFACE_ONLY` plan, accepts coarse requests and bypasses
the internal-server block-detail cap. Other levels keep their original DH path;
the global file setting stays `CHUNKS_ONLY`. Disabling generation still wins.
DH's pooled-source setter requires DTO detail zero; the data source position
carries the actual cell scale. API validation remains enabled.

Cube loads, successful block edits and vanilla update attempts coalesce into
owner-thread snapshots on the server as well as the client. Vanilla DH readers
are suppressed for cubic columns. Actual block and light snapshots enter DH's
normal update queue and supersede seed previews, preserving player changes.
The preview bridge accepts opaque or zero-opacity non-emitting block runs and
vanilla stationary source water. DH run light is sampled above the top face,
before that run attenuates the sky. Water splits into at most five lit layers
and one dark remainder using its vanilla opacity of three. This models a
surface column without underwater air or emitters; underwater air, flowing
fluids, other translucent states and emitters fail explicitly. Native snapshots
use actual light and supersede seed previews. Riven owns water geometry; this
optional CC adapter owns the encoding. DH owns generation and delivery queues.

The source build compiles against the official DH JAR as a remapped compile-only
input, fetched by `tools/fetch_build_inputs.py` or read from a Riven checkout's
`dev/pins`. It is not bundled or made a runtime dependency.

## Current scope

The visited-only lane and the automatic lane are qualified separately. The
automatic lane uses a fresh stationary Riven overworld, a 256-chunk
(4,096-block) DH radius and normal native distances 12, and covers measured
coverage, saved geometry agreement, edits and removals, restart and images.
Earlier failed candidates are not accepted configurations.

Only generators implementing the preview capability supply unvisited terrain.
Unsupported cubic generators are blocked from DH's unsafe vanilla chunk-generation
fallback; they can still contribute native snapshots where available. Nether/End
background generation is not qualified by the Riven overworld run.

The supported finite world span is at most 4,095 blocks because DH uses twelve
bits for relative vertical coordinates. The current native profile is
[-128,384). A snapshot scans its contiguous received vertical slice and encodes
positions relative to the world's minimum. It neither fills unknown depths nor
merges disjoint historical depth slices. Full underground browsing, unbounded
vertical worlds, 3D biome inputs, shaders and broad dynamic-light workloads are
not qualified by this surface integration.

Native qualification uses Java 26.0.2.1, formal Cleanroom 0.7.9-alpha, 8 GiB/ZGC
per JVM and actual server horizontal/vertical and client horizontal/vertical
distances 12. Cleanroom is the only build target. No isolated test suite or performance improvement is claimed.
