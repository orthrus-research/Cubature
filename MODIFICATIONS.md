# Cubature modifications and source

Cubature is an independent fork of OpenCubicChunks/CubicChunks, beginning at
upstream revision `53847757c7998f3541fccc8c9b5d23dd23e4077d`. It is developed
alongside Riven and does not imply endorsement by the upstream authors.
The public name is Cubature; compatibility IDs, API packages and world
formats retain their existing names.

Fork changes by Orthrus Research, October 12-19, 2026, include Cleanroom
and Java compatibility, asynchronous loading ownership, persistence and
lighting fixes, delivery pacing, and optional Distant Horizons integration.
On October 20, 2026, an optional packet cube-count ceiling was added for
delivery experiments; the default remains 1024 complete cubes per packet.
On October 23, 2026, a delivery scheduler was added: bounded worker threads generate cubes and
prepare columns off the server thread for generators that declare concurrent generation
(`concurrentGenerationThreads`, `concurrentGenerationQueue`, `concurrentGenerationTickMillis`),
a `deliveryMode` option selects the opt-in delivery stages, the storage writer lock is fair,
and `distantHorizonsNativeFeed` can limit DH rebuilds to edited columns.
On October 29, 2026, `deliveryMode` became the only delivery input: it is read when the server
starts and shared by every dimension, the `incrementalMaintenance` option and the
`cubature.deliveryExperiment` system property were removed, and `maintenanceUnloadsPerTick`
(now 64 by default) applies in every incremental stage. The same day the periodic autosave became
a named provider call reached only from vanilla's periodic save, so `saveChunks` always saves at
once; only watcher and ahead-preparation requests schedule generation on the workers, and other
requests never see work in flight as absent; and `PacketCubes` sorts a copy of its batch and is
told by its sender whether to compute an exact size. The provider and lighting no longer name
`PlayerCubeMap`: they read the world's player chunk map as `CubeDemand` (the cubes to tick, and
whether delivery still holds a cube) and `SurfaceHeightListener` (column heights changed), which the
map implements; `TestLayerBoundary` keeps it so. A teleport that leaves the player where they are
(vanilla re-sending an unconfirmed teleport, or correcting a rejected move) no longer cancels a held
teleport; before, one arriving during a hold dropped it and left the player where they stood.
On October 30, 2026, `ICubeGenerator` gained a default `useBuildPool`: each world opens a
ForkJoin build pool (`generatorBuildThreads`, by default a quarter of the processors, 1 to 4)
and offers it to its generator for the generator's own parallel work, then withdraws it when the
world closes, so that work stays inside the worker budget instead of the JVM's common pool.
The Cubature name and license packaging were adopted on October 19, 2026;
that change added no terrain or scheduling algorithm.

## Forge-derived asynchronous I/O

The package `io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge`
retains the LGPL-2.1 terms identified in [NOTICE.md](NOTICE.md), including
the following covered files and their fork changes:

| File | Last implementation change | Change area |
| --- | --- | --- |
| `AsyncColumnIOProvider.java` | 2026-10-18 | Column loading and completion ownership |
| `AsyncCubeIOProvider.java` | 2026-10-18 | Cube loading and completion ownership |
| `AsyncIOProvider.java` | 2026-10-18 | Task state and callback lifecycle |
| `AsyncTaskCompletion.java` | 2026-10-18 | Completion publication and cleanup; added in this fork |
| `AsyncWorldIOExecutor.java` | 2026-10-18 | Scheduling, cancellation and world shutdown |
| `QueuedColumn.java` | 2026-10-14 | Column task keys |
| `QueuedCube.java` | 2026-10-14 | Cube task keys and coordinate hashing |

Modification notices were added to these files on October 19, 2026. The fork also adds
`AsyncCompletionQueue`, `AsyncTaskExecution`, `AsyncTaskQuiescence`,
`AsyncTaskRetirement` and `WeakWorldClosures` in the same package; its
LGPL-2.1 notice applies to these helpers as well. No NRR grant replaces it.

The full LGPL-2.1 text is in `cleanroom/licenses/forge/LGPL-2.1.txt`.
Original copyright and license notices remain in the covered source.

## Distant Horizons

The optional adapter targets official DH 3.3.3 for Minecraft 1.12.2/Forge.
Cubature changes selected DH classes at runtime while leaving the separate
upstream JAR unchanged on disk. The current build does not bundle DH's
implementation. Its source identities, license texts and dependency notice
are under `cleanroom/licenses/distanthorizons/`.

Copied or adapted DH implementation, including a future port of historical
patches, must retain its applicable terms and undergo a combination-license
review. Runtime patching is not an exemption. Do not apply this fork's MIT
default or Riven's NRR license indiscriminately to DH-derived code.

## Celeritas

On October 25, 2026, optional support was added for Celeritas as bundled by Demonica 0.9.0
(Celeritas d94ec1e5). Celeritas replaces `RenderGlobal`'s terrain path, so the vanilla
vertical-render-distance mixins stand aside when it is present. In cubic worlds the
`client.celeritas` mixins instead give its section manager the world's full height, and they
clone section blocks and tile entities from cubes. A cube without storage is all air with implicit,
height-based sky light; Celeritas read it as its own empty section, which has full sky light at
any depth, so cave walls beside such a cube were lit as daylight. Since October 27, 2026 it reads a
section holding the cube's implicit sky light instead, unless every block of it is open to the sky.
Its entity gatherer reads the world's entity list, because a cubic column's own entity lists stay empty, and its renderer's build height starts at
the world's floor, so entity culling covers entities below Y 0. The client cube cache reports each column to
Celeritas's chunk tracker, and a ready column has render sections only for loaded cubes: each cube
adds its section once its blocks arrive and removes it after it unloads, applied where Celeritas
applies its own column events, since a section change during its asynchronous graph search throws. These files contain no
Celeritas code; they are MIT like the rest of the fork. The build compiles against the Celeritas
JAR inside the Demonica pin and bundles none of it. When Demonica's Iris pipeline is present,
`client.demonica.MixinDemonicaWorldInfo` replaces the `bedrockLevel` shader uniform. Demonica
reports 0, the vanilla floor; in a cubic world the uniform now carries the world's minimum height,
so packs stop fading sky and fog below Y-6. Since October 28, 2026 the same mixin adds a uniform,
`cubicSurfaceDepth`: how far the camera's eye is below the top of its column's highest
light-blocking block, from the height map the server sends, 0 at or above it. With the client
option `complementaryCaveFog` (off by default), `client.demonica.MixinDemonicaIncludeGraph`
rewrites one line of Complementary r5.9.3's `caveFactor.glsl` as Demonica reads it, so its cave
fog follows that depth instead of the depth below Y61.9, and caves above sea level darken like
those below. Other text is left unchanged. Complementary's license grants no change to its code,
so the option stays off unless its authors permit it.

## Biomes by height

On October 26, 2026, `CubePrimer` began storing one biome per 4x4x4 section, as cubes do;
upstream stored 2x16x2-block columns, so every section of a generated cube took the biome last
set at its X and Z. Two vanilla column-biome lookups were moved off Y 0 for cubic worlds, where a
generator may give each cube below the surface its own 3D biome:
`fixes.client.MixinEntityRenderer_RainBiome` decides a column's rain or snow from the biome at
its precipitation height, and `fixes.common.MixinEntitySnowman_MeltBiome` melts a snow golem by
the biome where it stands. Vanilla worlds are unchanged.

## Client lighting after small edits

On October 28, 2026, `PacketCubeBlockChange` began applying a cube's changed blocks before taking
the server's column heights. Vanilla's `Chunk.setBlockState` relights a column when a block lands at
or above the column's top, judged by the height the client holds. With the server's heights already
in place the client never relit the column. Storage created for a cube that had none also started
with the sky light of the new heights, so the edited columns stayed unlit above the edit: sky light
0 over a placed slab where the server held 15, and a dark band on smooth-lit faces beside it. The
client now applies the blocks against its own heights, then takes the server's heights and relights
any column whose height still differs. Edits of 64 or more blocks in a cube resend the whole cube,
which already relit from the old heights.

## Source and distribution

On October 19, 2026, `MixinDistantHorizonsQueueBackoff` added a requested 2 ms
park after DH 3.3.3 requeues an update because debounce or executor availability
prevents dispatch, provided its pre-update queue has no work. The three retry
sites are required injection matches. Successful dispatch, queue contents,
250 ms debounce, priorities and cancellation remain upstream-owned. The park
preserves the interrupt flag; OS scheduling can extend the requested delay.
The patch file carries LGPL-3.0-only terms and its matching source is included
in the sources JAR. No DH class is replaced on disk.

Both supported builds include `NOTICE.md`, this file, the upstream MIT text,
the full LGPL-2.1 text and component notices in binary and matching sources
JARs. The sources JAR includes the mod and API Java sources. It is not by
itself the complete buildable repository or a source archive for separately
installed DH, Minecraft, Cleanroom or every external library.

Build with the supported `cleanroom/` project as described in
`docs/cleanroom-port.md`. This repository is the complete modified source of
each build; the upstream base commit alone is not.

Before distributing a binary, provide its matching covered source and all
required build inputs and patches, with component notices. Keep the LGPL
replacement/relinking and reverse-engineering permissions intact, and review
the actual distribution's combination terms. Do not use an NRR notice as a
substitute for these obligations.
