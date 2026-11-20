# Cubature modifications and source

Cubature is an independent fork of OpenCubicChunks/CubicChunks, beginning at
upstream revision `53847757c7998f3541fccc8c9b5d23dd23e4077d`. It is developed
alongside Riven and does not imply endorsement by the upstream authors.
The public name is Cubature; compatibility IDs, API packages and world
formats retain their existing names.

On November 9, 2026, always-on exclusive server-tick phase counters, a bounded
history and minute summaries were added; the lab records their vectors, cube
installation costs and no-storage counts without changing delivery admission.
The lab's complete-tick wrapper encloses the core phase clock, so the final phase vector is
published before the job's independent duration is recorded and its report is frozen.

On November 10, 2026, the server-tick phases also separate a cubic world's block ticking (column
weather and light checks and random ticks of tickable cubes) and the player cube map's own
bookkeeping, both inside Cubature's own methods. Phases for queued packet handlers with the network
tick and for Forge's server and world tick events are charged only by the lab, which wraps those
vanilla call sites; the mod itself adds no mixin to them, since other mods may overwrite or move
them. The provider's per-cube tick is gone: every tick it walked every tickable cube watcher (and
every loaded cube on the client) to drain a tile-entity creation queue that nothing fills, since
tile entities queue on their column, and set a flag nothing reads. Random ticks run in the world's
own block-tick pass and are unchanged.
Also on November 10, 2026, the public API gained `ICubicRandomTickListener`, registered on a server
world with `ICubicWorldServer.addRandomTickListener`. The cubic random-tick pass asks each listener
whether to give a cube its draws, then tells it every drawn position and state, and skips a block's
random tick when any listener declines. The cubes, their order and the drawn positions stay the
owner's; with no listener the pass runs as before, and listener time is its own nested phase.
The selectable `MixinWorldServer_UpdateBlocks`, which redirected `randomTickSpeed` to 0 inside
`WorldServer.updateBlocks` for cubic worlds, is removed: cubic worlds cancel that method at its head,
so the redirect never acted, and as a `@Redirect` it would have collided with another mod's redirect
of the same call in non-cubic worlds.
Also on November 10, 2026, upstream code that nothing in Cubature, its lab, Riven or OrthLib calls
was removed. From the API: the cube population and ore, decoration and structure-initialization
events, which nothing posted; `CubicPopulatorList` and `WorldGenEntitySpawner`; the cubic structure
framework (`ICubicStructureGenerator`, `ICubicFeatureGenerator`, `CubicFeatureGenerator`,
`CubicFeatureData`); and `CubeGeneratorsRegistry.register` with `generateWorld`, whose populators
nothing ran. The vanilla-compatibility populator list and the asynchronous loading callbacks stay.
From the core: the field-based block-state containers and their state classes, the old sky-light
update queue and selector, the light block-access interface, a newer server height map draft, the
global generator configuration, the debug tracer, the mapping table, an abstract server message
handler, a watcher player entry, the generic `WatchersSortingList` with its test, the column's
tick-storage cache, a BetterPortals accessor on the column watcher, debug dumps, and setters and
overloads without callers. The structure-start Mixin and `ICubicFeatureStart` are removed as well:
nothing marked a structure start cubic, so every saved start carried a `ChunkY` of 0 that nothing
read. New saves omit the tag; vanilla ignores it in existing ones.
Also on November 10, 2026, upstream's ForgeGradle build was removed: the root and API Gradle
scripts, properties and wrappers, and the two `buildSrc` submodules pointing at CubicGradle. The
Unimined project in `cleanroom/` compiles the same sources and is the only build.
On November 11, 2026, that project moved from Unimined to CleanroomGradle so it compiles against
Cleanroom 0.7.14-alpha, which publishes no Unimined userdev. Sources and artifact names are unchanged.
Also on November 10, 2026, entity chunk-loading tickets keep their entity's cube. Forge saves the
column its entity is stored in; upstream's `entityCubeY` came from a ticket field nothing set,
was written only when the ticket also forced cubes, and was dropped on load, so a cubic world
reloaded every entity ticket's cube Y 0 and then looked for the entity there. The ticket now saves the
entity's `chunkCoordY` in a cubic world whether or not it forces cubes, and loading restores it.
A ticket saved before this keeps the cube Y it stored, as before.
Also on November 10, 2026, compatibility code for mods and platforms outside Cubature's runtime
(official Cleanroom with Distant Horizons, and Demonica with its bundled Celeritas) was removed:
Forge+Bukkit hybrid mixins and the hybrid check, OptiFine's render mixins, interfaces and version
detection, BetterFps' fast-beacon mixin, the WorldEdit class transformer, the per-mod world-height
faking and fake chunk-load events for other mods' generators, ForgeEssentials' world class and
Geyser (Bedrock) client detection. The vanilla-sided mixins that were the hybrid check's
alternative now load with the core configuration under descriptive names. A cubic world reports its
own height, and the client and server cap view distance at 32 chunks. Unmodded Java clients keep
their default of no horizontal slices; the slice option now applies to them when enabled.
On November 11, 2026, support for clients without Cubature was removed: the `allowVanillaClients`
option and its horizontal-slice settings, `VanillaNetworkHandler`, the vanilla column and block
packets in delivery, and the 33 packet mixins that shifted positions for such clients. The option
was off by default and the connection check already refused those clients, so no delivery path
changes for a client that has Cubature.
Also on November 11, 2026, the height fixes for vanilla feature generators that only a biome
decorator or chunk generator calls were removed (lakes, dungeons, ice spikes and paths, boulders,
desert wells, dead bush, tall grass, flowers, double plants, shrub, swamp tree and the taiga pine),
with four mixins on such generators that had never held code. The fixes for generators a player
can start at any height stay: trees grown from saplings and the huge mushroom.
Also on November 11, 2026, `config/cubicchunks_mixin_config.json` and its four choices were removed.
Each choice is now fixed at what had been its default: cube arrays in the chunk cache, the
height-aware collision check and the vertical render distance stay; the path-navigator
optimisation, the fast collision check and the vanilla chunk-cache biome lookup, which only applied
when a choice was changed, are gone. An existing file is ignored.
Also on November 11, 2026, the fixes for a cubic End were removed: the dragon's vanilla top-block
lookup and the End gateway's exit search. The End is excluded from cubic worlds by default.
Also on November 11, 2026, the Mixin injectors were rewritten for CleanMix and MixinExtras:
redirects became `@WrapOperation`, constant modifications became `@ModifyExpressionValue`, and
array accesses and comparisons with zero are targeted through MixinExtras Expressions. Mixin
configurations are registered by a CleanMix connector. Two behaviours changed with the rewrite,
both upstream slips: in a cubic world a lily pad could stay on any block, and the huge mushroom
skipped its obstruction check below Y 0. Both now behave as their height checks intend.
Also on November 10, 2026, initial sky light and surface tracking return early for a cube without
storage that lies above every staged column top in a sky-lit world: its 4,096 sky checks
changed nothing, and the column's dirtying, staging removal and surface notifications still run.
A missing cube that a generator classifies EMPTY is installed as an all-air cube on the server
thread without a worker task or primer, marked modified as a generated cube is; a worker task
already running for it is retired, and its late result is dropped.
The client's Distant Horizons native capture loop counts, per pass, its visits, debounced and
already-updating columns, copies, submissions and exit reason, in its existing periodic log.
Cubes to generate, send and add players to are ordered visible-first: the 5 x 5 x 5 cubes around
each player, then cubes inside their column's surface band (or with no known band), then cubes
between a player's level and the band, then the rest, each class by distance. A hidden cube that
has waited 200 ticks may move ahead of the surface class, one per queue every 20 ticks. Bands
come from the generator's optional classification; without them the order is distance order.
Regional preparation in a world players respawn in ranks regions around its spawn point while no
player is present, so the time before the first join prepares what that join needs.
An explicit flush or close writes each region's whole backlog as one storage batch, so a region
file takes one data force and one header force per flush instead of two per 64-entry batch; the
background writer keeps its bounded batches.
Storage compression reuses one deflater per thread, writing the same gzip bytes as
CompressedStreamTools, and compresses a batch of more than 64 entries on up to four shared
workers.
A cube generated this session is not saved while it holds no blocks, biomes, entities, tile
entities, capabilities or pending neighbour light checks and its generator reports it
`regenerable` (the new optional `ICubeClassification.regenerable`, asked when saving is
considered); reloading it generates it again. Once saved, it saves like any other cube.
Chunk-mesh builders start their layer buffers at 512 KiB (solid), 128 KiB (cutout, cutout mipped)
and 256 KiB (translucent) instead of vanilla's 8 MiB, 512 KiB and 1 MiB; buffers still grow on
demand. Vanilla keeps about ten builders per processor, so this returns most of 0.8 GiB of direct
memory on 8 processors and 1.8 GiB on 32.

The lab fetcher publishes verified dependencies through short, unique temporary basenames beside
their destinations, so cached and downloaded libraries fit legacy Windows path limits.

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
ForkJoin build pool (`generatorBuildThreads`, by default a quarter of the processors, 1 to 4,
holding at most twice that while tasks block) and offers it to its generator for the generator's
own parallel work, then withdraws it when the world closes, so that work stays inside the worker
budget instead of the JVM's common pool.
The world type can request that same pool before constructing its biome provider through
`WorldBuildPool.request`; the cube provider then adopts it. A failed construction can release an
unadopted pool through `WorldBuildPool.abandon`. Cubature closes requested pools on failed or skipped
world initialization, world unload and server stop, and rejects requests from closed worlds or a
retired server. Adoption and withdrawal happen once, including when a generator callback fails.
Abandonment retires only unadopted pools; after adoption, a failed early request leaves the live
generator's pool in place until world/provider shutdown or server stop.
World types can also request a host-owned directory for discardable derived data before their biome
provider exists. The save-level path is a sibling named `<save>.cubature-cache`; it does not depend on
provider initialization and resolving it opens or creates no cache files. Clients and worlds without
a usable save location receive no directory. Consumers namespace files below the shared root by
kind and generation inputs and release their opened resources with the world's lifecycle.
An optional `ICubeClassification` contract exposes cached cube classes and inclusive surface bands
in cube-Y units. Queries read only immutable data already held by a generator and return UNKNOWN
or no band when required data is missing. EMPTY certifies that generation into a fresh primer
produces only Minecraft air and no 3D biomes, including decorations rooted in neighbouring columns.
Saved data, population and normal cube lifecycle work remain authoritative.
The same day the Forge 14.23.5.2860 / Java 8 fallback build was retired: Cubature builds for
Cleanroom only, as Java 25 bytecode tested on Java 26. `ICubeGenerator` also gained
`preparationRegionBlocks` and `prepareRegion`: each world prepares its generator's regional plans
ahead of the players on the build pool (`RegionalPreparation`), within
`regionalPreparationReachBlocks`, nearest by time to reach first. Regions within the native view
run up to the pool's size; those beyond run under `PreparationGovernor`, which reads CPU
contention rather than tick time alone. Ordinary preparation waits while more than one face of the
native view waits to generate or be sent, and the generator declines when its caches lack room.
The same day `processPlayer`'s own `setPlayerLocation` calls began running through
`DeferredArrivals.correct`, so they never replace a held teleport. A held player keeps moving, and
vanilla puts a move it rejects for colliding back where the player stood before it, after
`processPlayer` has already moved the player to where the client claimed; read as a teleport
elsewhere, that dropped the hold, and the player never arrived.
Held teleports now register their destination region ahead of ordinary preparation, without a reach
or governor limit, within the same bounded build pool. The hold ends when the destination is ready,
replaced, forced or abandoned; elapsed time produces a watchdog report instead of synchronous
generation. A translatable action-bar message reports preparation at most once a second. The optional
`IRetainedColumnPreparation` capability lets generation workers retain immutable column data through
server-thread installation: held arrivals own shared, capacity-bounded leases whose successes cannot
be evicted and whose failures remain diagnosable until cancellation or explicit retry. Other concurrent
generators keep their existing readiness path; worlds without generation workers still arrive at once.
Departures and world shutdown release held requests and leases, including late worker results. The
lab distinguishes a genuinely lost teleport from a destination that is still held for preparation.
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

## Celeritas and Demonica

From October 25 to November 19, 2026, Cubature carried optional client support for Celeritas as
bundled by Demonica 0.9.0 (its section manager given the world's height and fed sections from
cubes) and for Demonica's Iris pipeline (the `bedrockLevel` and `cubicSurfaceDepth` shader
uniforms and the opt-in `complementaryCaveFog` rewrite). On November 19, 2026 it was removed with
the `selectable.celeritas` and `selectable.demonica` Mixin configurations, `core/compat/celeritas`,
`core/compat/demonica`, the client option `complementaryCaveFog` and the build's Demonica input:
Demonica is no longer a target. The vanilla terrain-path mixins now always apply, and a client
with Celeritas or Demonica installed is not supported.

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

On October 30, 2026, `MixinDistantHorizonsNativeFirst` made DH 3.3.3's
`WorldGenerationQueue.isGeneratorBusy` report the generator busy while
Cubature's preview generator holds new tiles: while more than one face of the
world's native view waits to generate or be sent (`CubeDemand.nativeBehind`,
the rule regional preparation already used), no preview tile starts. Tiles
running finish; DH's selection, generation and delivery are unchanged. It is a
required match, carries LGPL-3.0-only terms, and its source is in the sources
JAR. The same day Cubature's own `PreviewPool` (`previewThreads`, by default a
quarter of the processors, 1 to 8) began sampling cubic preview tiles in place
of DH's world generation executor, so DH's server health pause no longer stops
them. Its governor sets how many run from the server's ticks (the p95 tick at
or under 45 ms, never fewer than one tile), and the same Mixin admits at most
its permits plus one tile. `previewThreads=0` keeps DH's executor.

On October 31, 2026, `MixinDistantHorizonsArrivalTree`,
`MixinDistantHorizonsArrivalRequests` and `MixinDistantHorizonsServeInOrder`
gave a cubic world Cubature's arrival plan (`distantHorizonsArrivalPlan`, on by
default). On the client, DH 3.3.3's quad tree hands its missing sections to the
request queue in the plan's order (by when the player reaches them, coarse
detail first) and leaves out sections the native view covers or will cover
before their data could arrive; the request queue sends the plan's choice,
cancels unsent requests the plan now holds back (DH's tree keeps them missing)
and keeps in flight only what the server answers in about 3 s; and the tree's
queueing thread ends after half a second without queueing, so DH's next update
finds the sections wanted then. On the server, `WorldGenerationQueue` starts
the waiting tile whose request arrived first. All are required matches, carry
LGPL-3.0-only terms, and their sources are in the sources JAR. DH's request
limits, network protocol, propagation, persistence and rendering are unchanged.

On November 1, 2026, `MixinDistantHorizonsNoWorldMessages` made DH 3.3.3's
1.12.2 plugin channel leave a received message undecoded while no DH world is
loaded. A dedicated server closes DH's world at `FMLServerStoppingEvent`,
before it disconnects its players, and full-data requests arriving then failed
DH's world assertion on the Netty thread and cut the connection. DH's handler
already skips a wrapper without a message. It is a required match, carries
LGPL-3.0-only terms, and its source is in the sources JAR.

On November 5, 2026, `MixinDistantHorizonsPropertyNames` made DH 3.3.3's
`BlockStateWrapper` serialize a property value by its name from its property
where its class inherits Object's `toString` (class name and hash code), so
modded values without one (GregTech's ore `stone_type`, whose hash code mixes
in an identity hash) are stable across JVMs and starts. Every other value keeps
its `toString`, so strings that were already stable stay byte for byte the
same. DH 3.3.3 matches a stored string against this same method's output for
each of the block's states when it deserializes. It is a required match,
carries LGPL-3.0-only terms, and its source is in the sources JAR.

On November 5, 2026, the background storage writer began gathering saves for
`storageGatherMillis` (250 ms by default; 0 writes at once) before a round
writes them, so each region's two durability forces cover a fuller batch; saves
made during a round wait for the next window. Explicit flushes, close and
tick-driven writes do not wait. A crash or killed process loses up to one
window of saves plus the round in progress; a clean shutdown writes them.

On November 14, 2026, the public API gained a cube source for client-side
renderers (`api.client`, described in `docs/CUBE-SOURCE.md`): events for cubes
arriving, leaving and changing, a version per cube, and immutable snapshots of
a cube with its 26 neighbors. It is additive; the vanilla, Celeritas, Demonica
and Distant Horizons paths do not use it. Each client cube carries a version
number and a reference to its latest snapshot copy.

On November 15, 2026, a client gained the generator's terrain preview through
the cube source (`ICubeSource.terrainPreview`, `docs/CUBE-SOURCE.md` section
8). A server whose generator offers it sends each joining player one more
message with the seed, the generator options, the world's recorded definitions
and an identity hash; the new server option `sendSeedForClientPreview` (default
on) withholds all of them. The generator's registered factory builds the
preview on the client; a client of its own integrated server borrows that
server's generator, which the server takes back before it closes the world. It
is additive: the vanilla, Celeritas, Demonica and Distant Horizons paths do not
use it, and a server whose generator offers nothing sends nothing.

Also on November 15, 2026, the cube source gained further additive defaults,
all read on the client thread (`docs/CUBE-SOURCE.md`): `surfaceHeights` can
leave the preview unsampled, `cubeContent` says whether a cube is loaded and
holds any block, and `blockVersion` and `lightVersion` split a cube's version
so that a change of light alone can be told from a change of blocks. Each
client cube carries the two halves beside its version. The bounds that cube
change events carry are now a stated contract.

Also on November 15, 2026, two client unload paths changed. A vanilla render
chunk more than 24 blocks from the player whose own cube has left is now built
(to nothing) instead of waiting for its neighbors, which had left the mesh of
the departed cube drawn. A column unload on the client first unloads every cube
the client still holds in it, as a cube unload would, instead of leaving those
cubes in the client's cube map.

Also on November 15, 2026, the cube source gained `lightChecksum`, a diagnostic
hash of a loaded cube's light (default -1), and Cubature gained two diagnostic
switches, off by default: `cubicchunks.lightChecksumDump` writes each side's
checksums to files, and `cubicchunks.noClientBoundaryRelight` skips the client's
relight of a cube's boundaries on arrival.

Also on November 15, 2026, the cube source gained `previewWork`, a pool of
Cubature's threads on which a renderer samples the client terrain preview
instead of starting threads of its own (default null; nothing uses it yet). Its
threads exist only while a preview is attached, run below normal priority, and
take fewer jobs at once while frames or the integrated server's ticks run long.
Two client options size and govern it: `clientPreviewThreads` and
`clientPreviewFrameBudgetMs`.

On November 16, 2026, the cube source gained more reads, all additive defaults
on the client thread except the snapshot's, which any thread may make:
`loadedNeighbors` (a mask of which of a cube and its 26 neighbors are loaded),
`light` (one block's light, packed), `snapshotCenter` (a snapshot of the cube
alone), `maxBlockStates`, and on snapshots `getLightFor` (light as stored) and
`blockVersion` and `lightVersion` per offset. Each snapshot copy carries the two
version halves. At server start Cubature now warns in the log when a block state
id reaches 1,048,576, the most that cube saves keep.

Also on November 16, 2026, the server states to each player the box of cubes it
keeps sent (a new client message, PacketHeldBox: the player's cube and the
horizontal and vertical view distances it applies), when the player joins, when
the player moves to another cube and when the distances change. The cube source
answers it with `heldBox` and `inHeldBox`, and its listener hears
`heldBoxChanged`. A client built before this message is disconnected when it
receives it; both sides need this build or later.

Also on November 16, 2026, the cube source gained a settled signal for frame
captures (`quietTicks`, `heldBoxSent`, `settled`, and the listener's
`heldBoxSent`). The server walks each player's held box after sending cubes, at
most 4,096 look-ups a player a tick while the box is not all sent, and says once
per box that every cube of it has been sent (another new client message,
PacketHeldBoxSent). The client counts the ticks in which nothing arrived, left or
changed and its light engine had no work queued.

Also on November 16, 2026, the cube source gained `buildWork`, a pool of
Cubature's threads for work on cube snapshots such as background meshing (a new
client option, `clientBuildThreads`). Waiting tasks sit in a priority queue on
the client thread that can be reordered cheaply, take their snapshot only when a
thread is about to be free, go stale when their cube leaves or changes as their
mask says, and release their snapshot whatever way they end. The pool shares its
running jobs with the preview pool under the preview pool's governor.

Also on November 16, 2026, the server sends a cube's light again when it changes
the light of a cube a player already has and no block changed with it (a new
client message, PacketCubeLight: the cube's block light and sky light, 4,109
bytes), at most every 10 ticks a cube, and the client raises a light change for
it. Before, the client kept the light it was first sent: measured, 405 cubes
near the edge of what was loaded differed from the server's, against 19 with the
change. The new server option `deliverLightChanges` (default on) turns it off.
Every cube records whether its light was written since the server's last tick.

Also on November 16, 2026, the server records block by block which blocks' light
it wrote in a cube already sent, and sends nothing when none was. The new server
option `deliverOnlyChangedLight` (default off) sends only those blocks' light (a
new client message, PacketCubeLightChanges, 3 bytes a block). The diagnostic
light dumps gained periodic server dumps (`cubicchunks.lightChecksumEvery`),
per-block light for listed cubes (`cubicchunks.lightDetail`) and each cube's
pending boundary checks. A lab switch, `cubicchunks.noUnloadedMeshFix`, puts back
the game's wait in the render worker, and `lab/tours` holds a one-off walking tour.

Also on November 16, 2026, the lab's view distances became a baseline instead of a fixed
value: instances are prepared at 12, a run may raise the server's view distances (up to 32)
or the client's render distance (up to 64) by exception, and the run's distances are recorded
with a label that marks a raised run. Lower than 12 is still refused.

On November 16, 2026, the column message released the buffers that wrap its
bytes when it is written on the server and read on the client; before, both were
left to the garbage collector, and Netty's leak detector could report them.

Also on November 16, 2026, the client preview pool and the client build pool log
one line when they stop their threads, and both report their live threads
through a new default, `liveThreads()`.

Also on November 16, 2026, the governor of the client pools counts work queued
and a pool running full as demand, not only a job that waited for a permit, so
a renderer that hands in no more jobs than may run still lets the pool grow, and
it logs each change of the permits with what decided it.

Also on November 16, 2026, `ICubicTerrainPreview` states its threading contract:
a preview is shared across threads, a sampler is confined to one thread at a time
and may serve many tasks there, and what either keeps between calls is a pure
function of the preview's inputs and the coordinates. The interface is unchanged.

Also on November 16, 2026, the lab gained named reduced view profiles below the
baseline (`near-8`), chosen when an instance is prepared and fixed for its life;
such an instance is never raised, and its runs are not compared with others.

Also on November 16, 2026, a client that builds a terrain preview gives the
factory a build pool of its own (`ClientPreviewInputs.buildPool()`, before null):
a small ForkJoinPool of 1 or 2 low-priority daemon threads, shut down with the
preview, separate from the sampling pool's threads and permits.

Also on November 16, 2026, the governor of the client pools does nothing while
no world is attached, and drops what the pools flagged meanwhile; and a second
after each client pool's stop line the log counts the pool's threads still
alive. The lab's cube source check counts a departed cube's mesh as still drawn
only once the game has had its chance to rebuild the chunk, and the stale-mesh
tour ends looking back over the edge the cubes left from.

Also on November 16, 2026, a client pool whose threads are not all gone a second
after its stop is counted again at 2, 5 and 10 seconds, so a thread that ends
late is told from one that never ends.

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

On November 18, 2026, the client build pool takes snapshots ahead of its threads for as many
jobs as the running permits finish between two frames, measured from a moving average of a job's
time and of the time between frames, at least one and at most 16 a thread; before, it took one a
thread, so its threads stood idle between frames. A submission to the pool, and a poll, now only
admit waiting tasks (a poll still checks the task it hands back); the staleness check of the tasks
already held is left to the pumps of each tick and frame, and the pool counts the snapshots it holds
as it takes and releases them. The
cube source counts the cube copies its snapshots make and the pool the snapshots it takes, each
with the client thread's time, readable through the API and logged as the world goes.

Also on November 18, 2026, the client lets go of a cube's copy for snapshots once no snapshot has
asked for it in `clientKeptCopyTicks` ticks (default 200; 0 keeps every copy as before); before,
a copy stayed until its cube changed or left, which held about 640 MiB at a view distance of 20.

Also on November 18, 2026, newly prepared lab instances run ZGC on a fixed 8 GiB heap, server and
client (`zgc-fixed`, with `zgc-fixed-nmt` for native memory tracking), in place of the shipping G1
profile (`g1-ship8`, still a variant). With a renderer meshing on the client build pool at a view
distance of 20, the client's longest collection pause went from 179 ms to 0.07 ms and the server's
from 140 ms to 0.03 ms, with the same meshing speed and client CPU and no allocation stalls.

On November 19, 2026, first light finds a cube's block light sources by reading its storage once,
block by block, and asks a state for its light without the world when the state's block takes its
light from the state: the block's world-aware method is Forge's own, which asks the state, and the
state's is the game's, which asks the block. Before, it asked every block of the cube with the
world and its position. A block that overrides the world-aware method is still asked with the
world, and a cube without storage whose air gives no light is not scanned. The blocks found are
the same: with `-Dcubature.checkFirstLightSources=true` the scan also asks every block with the
world and logs each block where the two answers disagree, and over 86,016 cubes of a generating
cave tour none did. On that tour first light's server-thread cost fell from 69.0 to 45.3
microseconds a cube.

Also on November 19, 2026, a cube built from a generator's primer asks a block's material once for
each run of one state, not at every one of its 4,096 blocks, to leave blocks of air material as the
storage's air. Blocks are copied in the same order, so the storage, its palette and its bytes are
the same. With `-Dcubature.checkCubeConstruction=true` every block is also asked, and a block that
parts from its run is logged; over 114,688 cubes of a generating cave tour none did. On that tour
construction's server-thread cost fell from 87.3 to 72.0 microseconds a cube.

Also on November 19, 2026, first light skips its scan for block light sources when no state in the
cube's storage palette gives light from itself and air gives none: the game's linear and hash-map
palettes hold every state their storage has stored, and a block the palette does not name reads as
air. A registry palette, which holds every state there is, is never read, and its cube is scanned.
On a generating cave tour 73,149 of 86,016 cubes were skipped so; with
`-Dcubature.checkFirstLightSources=true` they are scanned anyway, and none held a block giving
light. On that tour first light's server-thread cost fell from 45.3 to 24.7 microseconds a cube.

Also on November 19, 2026, a cube built from a generator's primer writes each run of one state a run
at a time. The run's first block goes through the storage's own `set`, so a state enters the palette,
and the palette grows, where the per-block copy does it; the rest of the run is written as that
state's number straight into the container's bits, and the storage's counts of blocks and of
randomly ticking blocks are added once (accessor `IExtendedBlockStorage`). The blocks, the palette,
the bits and both counts are the per-block copy's, as the primer copy's tests hold, counts included.
With `-Dcubature.checkCubeConstruction=true` every cube is also copied a block at a time and the two
are compared. Over 110,592 cubes of a generating cave tour no copy parted. On that tour, on fresh
instances with the same client, construction's server-thread cost was 39.3 microseconds a cube
against 67.0 for the commit before; the other install phases were the same within a few percent.
