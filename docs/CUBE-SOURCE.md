# Cube source

Status: sections 1 to 8 are built. The interfaces are in the public API module, package
`api.client` (`CubeSource`, `ICubeSource`, `ICubeSourceListener`, `ICubeSnapshot`,
`IClientBuildWork`, `IClientPreviewWork`) and, for the terrain preview, `api.worldgen`; the
implementation is `core.client.source`. What is still design is listed under "Not built" in
section 8. Quadrature's near and distant terrain read the source.

A cube source is what Cubature supplies to a client-side renderer that builds its geometry
from cubes instead of from the game's 16-section columns. Cubature owns this contract. A
renderer reads only the interfaces described here, never Cubature's internal classes, and
Cubature calls a renderer only through the listener it is given.

Today a renderer reaches cubes through hooks written for it specifically (the Celeritas
integration in `core/compat/celeritas`). This contract states the same needs once, for any
renderer.

## Parties

| Party | Owns |
|---|---|
| Cubature | Cubes on the client: storage, light, block entities, arrival and removal, the work executor |
| A renderer | Everything made from cubes: meshes, visibility data, GPU memory, drawing |

## Terms

- **Cube**: 16 by 16 by 16 blocks at a cube position.
- **Neighborhood**: a cube and the 26 cubes around it.
- **Storage-less cube**: a loaded cube with no block storage. It is all air, and its sky
  light is implicit: full above its column's highest opaque block, none at or below it
  (`LightingRules.emptySkyLight`).
- **Client thread**: the game's client thread, which also owns the graphics context.

## 1. World

The source exists for a client world that is cubic. It gives:

- the world's height range in cube coordinates, lowest inclusive and highest exclusive;
- whether the dimension has sky light;
- an identity that changes when the client world is replaced (dimension change,
  disconnect). Every snapshot and event belongs to one identity and is void after it.

## 2. Snapshot

A snapshot is an immutable copy of a neighborhood, taken on the client thread.

**Contents**, for each of the 27 cubes:

| Data | Notes |
|---|---|
| Presence | Loaded, or absent |
| Block states | 4,096 per cube; none for a storage-less cube |
| Block light and sky light | Per block. For a storage-less cube the snapshot answers sky light by the implicit rule, from the column tops it captured |
| Biomes | As the cube stores them, for tinting |
| Block entities | The cube's block entity map as it stood. The entities themselves are not copied |
| Version | The version, block version and light version of each cube (section 3) |

Block states are held as the game's state objects, not as numeric ids, so a snapshot has no
limit on how many states a game defines. The limit a cubic world has is its save format:
cube saves keep 20 bits of a state's id, so `ICubeSource.maxBlockStates()` answers
`MAX_BLOCK_STATES`, 1,048,576. A state whose id is that or more would lose its high bits when
a cube is saved; Cubature warns in the log at server start when any id reaches it. Cube
packets carry ids as the game's block state container writes them and, for small changes,
as variable-length integers of up to 28 bits, so neither narrows the limit.

**Light at a block.** `getCombinedLight` answers as the game's chunk cache does: a block
that takes its neighbors' brightness (a slab, stairs) is given the brightest of them.
`getLightFor(type, pos)` answers the light stored at the block itself, 0 to 15. Outside the
neighborhood and in a cube the snapshot does not hold, both read no block light and the
sky light of point 5.

**The center alone.** `snapshotCenter(cubeX, cubeY, cubeZ)` copies only the cube itself:
every neighbor is absent (`present` false) and reads as air with no block light and full
sky light, whether or not it is loaded. It serves a reader that needs nothing beyond the
cube, such as a mesher of interior faces; one that draws the cube's outer faces wants the
full snapshot.

**Guarantees:**

1. **Immutable.** Nothing readable through a snapshot changes after it is returned.
   Block entities are the exception: the snapshot holds the live objects, as the game's
   own chunk cache does, and a model that reads one off the client thread sees whatever
   that object then holds.
2. **Consistent.** All 27 cubes are captured between two client ticks, so a snapshot
   never shows half of an edit.
3. **Any thread.** A snapshot may be read from any thread, for as long as it is held.
4. **Block access.** A snapshot presents the game's block access interface
   (`IBlockAccess`): block state, block entity, combined light, biome, air test and side
   solidity, for any block in the neighborhood. Existing block models and block color
   handlers run against it unchanged.
5. **Absent neighbors read as empty.** An absent cube reads as air with implicit sky
   light. When it arrives, the cubes around it receive a change event (section 3), so
   faces drawn against the gap are rebuilt. The game's own render cache answers full sky
   light for a cube that is not loaded, so the two differ for a block that takes its
   brightness from a neighbor in such a cube, until that cube arrives.
6. **Empty is cheap.** A snapshot whose center cube is storage-less says so without being
   read block by block.
7. **Edge cubes need nothing more.** A snapshot may be taken of any cube, whatever of its
   neighborhood is loaded, so a renderer meshing the edge of what the client holds takes
   an ordinary snapshot: `present(dx, dy, dz)` says which of the 27 cubes it holds, and
   the rest read as point 5 says. No separate query of cube states is offered; a
   renderer that wants only presence and versions reads `isLoaded` and `version`.

**Cost.** A cube is copied once per version, and that copy serves every snapshot that
includes the cube until its version moves. A cube sits in up to 27 neighborhoods, so it is
copied once where a per-snapshot copy would copy it 27 times. Cube storage is unchanged.
`cubeCopies()` and `cubeCopyNanos()` count the copies made and the client thread's time making
them, and the client log gives both for each world as it goes (`Client cube source: ... cube
copies made`). The Celeritas integration copies blocks and block entities per section, and that
is the cost to beat.

**Release.** A renderer releases a snapshot when its build finishes or is cancelled.
Holding one must not keep a cube loaded.

## 3. Versions and events

Each loaded cube has a version, its generation: a number that increases whenever anything
a snapshot would capture changes, and with every arrival of its data. A snapshot records
the version of each cube it holds, so a renderer can discard a build whose center cube has
since changed. Versions come from one counter for the whole client world, so a cube that
leaves and arrives again never repeats a version it had before. A version may move when
nothing a snapshot shows differs (a render update the game asks for
without a change); it never stays still
when something does.

```java
// api.client.ICubeSource
long version(int cubeX, int cubeY, int cubeZ);
long blockVersion(int cubeX, int cubeY, int cubeZ);
long lightVersion(int cubeX, int cubeY, int cubeZ);
```

The version is split in two for a renderer that keeps light apart from geometry.
`blockVersion` moves when the cube's blocks or biomes change or its data arrives;
`lightVersion` moves when its block light or sky light changes or its data arrives. Each
is at most the version, and the version moves whenever either does, so a renderer that
reads only `version` loses nothing. A mesh whose `blockVersion` is unmoved needs only its
light redone. A block edit moves both, since light may follow it, and so does a render
update the game asks for over a range, which does not say what changed. All three are -1
for a cube that is not loaded. The defaults answer `version` for both halves.

Cubature delivers events to one listener, on the client thread, after the data they
describe is readable:

| Event | When |
|---|---|
| World attached, world detached | The client world appears or is replaced |
| Cube loaded | A cube's data arrived, including a full resend of a cube already held |
| Cube unloaded | A cube left the client |
| Cube changed | Blocks, light or biomes in a loaded cube changed; carries the kinds that changed and, where known, the bounds within the cube |

Events are delivered once per client tick, at its end.

**Guarantees:**

1. Events arrive in the order the changes were applied.
2. A change in a cube's outermost layer of blocks, or its arrival or removal, also raises
   a change for each loaded neighbor that touches it. Such a change carries the kind
   `NEIGHBOR` and does not move the neighbor's version, since nothing in it changed.
3. Cubature translates every route by which a cube changes into these events: whole-cube
   packets (`PacketCubes`), small block-change packets (`PacketCubeBlockChange`), the
   game's own block and light notifications, and its own lighting. A renderer listens to
   nothing else. When the server changes the light of a cube it has already sent and no
   block changed with it (the light of the cube's neighbors arriving, first light ending
   around it), it sends the cube's whole light again (`PacketCubeLight`, 4,109 bytes with
   sky light), at most once every 10 ticks a cube, and the client raises a change of kind
   `LIGHT` over the whole cube, which moves its light version only. The server records,
   block by block, which blocks' light it wrote since the cube was sent, and sends nothing
   when none was. With the server option `deliverOnlyChangedLight` (default off) it sends
   only those blocks' light as it then stands (`PacketCubeLightChanges`, 3 bytes a block,
   the whole light from 1,365 blocks up), and the client's change covers the box of the
   blocks it was sent: about a fifth of the bytes, but the client keeps sky light it
   darkened on its own, which the whole light corrects (Verification). The server option
   `deliverLightChanges` (default on) turns both off.
4. Events may be coalesced within a tick: several changes to one cube may arrive as one
   event with their kinds and bounds combined, and a change to a cube whose arrival is
   queued in the same tick is covered by the arrival.
5. A cube that arrives and leaves within one tick is reported as both, in order; by the
   time the arrival is delivered the cube is already gone.
6. **The bounds of a change are a contract.** They always contain every block that
   changed; they may contain more, never less. A change to one block carries that block.
   Changes merged within a tick carry the box around all of them. A `NEIGHBOR` change
   carries the whole cube (0 to 15 on every axis), whatever part of the boundary was
   touched. A render update the game asks for over a range carries the part of the range
   inside each cube; one over more than 32,768 cubes is taken to mean the whole world, and
   every loaded cube receives a change of blocks and light over the whole cube. An arrival
   covers the whole cube and carries no bounds; a change merged into an arrival queued in
   the same tick raises nothing further. A renderer may rebuild exactly the bounds it is
   given.
7. **An unload is final when it is delivered.** `cubeUnloaded` arrives at the end of the
   client tick in which the unload was applied, and by then the cube is gone: `isLoaded`
   is false and a snapshot reads it as absent. There is no retiring state between the two.
   A column that leaves takes every cube still held in it, each with its own
   `cubeUnloaded`. The game's own renderer clears its mesh of the cube within its usual
   rebuild latency, at any distance from the player. A renderer of its own needs no
   retiring state either: it owns its meshes, drops the cube's on `cubeUnloaded`, and no
   later event or read brings the cube back unless it arrives again (`cubeLoaded`). One
   that wants to keep drawing a departing cube for a while, to fade it out, keeps that
   mesh on its own side.

## 4. Queries

- The set of loaded cube positions, for a renderer that starts or restarts while a world
  is already loaded.
- Whether a given cube is loaded, and its current version, block version and light
  version.
- Which of a cube and its 26 neighbors are loaded, as a mask, without a snapshot:
  `loadedNeighbors(cubeX, cubeY, cubeZ)`. Bit `(dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)` is
  the cube at that offset, the order of `ICubeSnapshot.present`; `NEIGHBOR_CENTER` is the
  cube itself, `NEIGHBOR_FACES` the six that share a face, `NEIGHBOR_ALL` all 26. The
  count is `Integer.bitCount(mask & NEIGHBOR_ALL)`. It costs 27 cube look-ups. With the
  `NEIGHBOR` events of section 3 a renderer can tell when a cube's last missing face
  neighbor arrived.
- The light at one block, without a snapshot: `light(blockX, blockY, blockZ)`, packed as
  `sky << 4 | block`, as stored, or -1 when the block's cube is not loaded. A cube without
  block storage answers its implicit sky light.
- A diagnostic checksum of a loaded cube's light, `lightChecksum(cubeX, cubeY, cubeZ)`:
  a hash of the block light and sky light of its 4,096 blocks as the cube answers them,
  never negative, -1 for a cube that is not loaded. The server computes the same hash
  for its copy of a cube, so the two can be compared (see Verification). It reads every
  block; it is not for every frame. The default answers -1.

## 5. Work executor

A renderer need not start threads. Cubature provides a client-side executor with:

- a bounded number of workers, stated by Cubature and readable by the renderer;
- task priority, so near and in-view cubes build first;
- cancellation of a queued or running task;
- no use of the common pool.

Tasks read snapshots and write only memory the renderer owns.

Two pools are built on one budget: `previewWork()` for sampling the terrain preview
(section 8, Sampling threads) and `buildWork()` for work on cube snapshots, such as
background meshing.

```java
// api.client.ICubeSource
IClientBuildWork buildWork();
// api.client.IClientBuildWork
<R> Task<R> submit(long priority, int cubeX, int cubeY, int cubeZ, boolean centerOnly, int staleOn,
        Job<R> job);
void reprioritize(Task<?> task, long priority);
void reprioritizeAll(ToLongFunction<Task<?>> priority);
Task<?> poll();
int threads(), running(), queued(), admitted(), finished(), liveThreads();
// Job<R>: R run(ICubeSnapshot snapshot, Task<R> task)
```

**How a task moves.** A task names a cube and a priority, lowest first, equal priorities in
order of arrival. It waits on the client thread in a heap and holds no snapshot while it
waits. Each client tick and each frame, and on every `submit` and `poll`, the pool admits
waiting tasks while fewer are admitted than the permits allow plus a lookahead: it takes
each one's snapshot then (`snapshotCenter` when `centerOnly`), on the client thread, and
hands it to a thread in that order. A cube that is not loaded when its turn comes makes
its task `STALE` without a snapshot. The job runs with the snapshot and returns a result;
the snapshot is released when the job ends, whether it returned, threw, was cancelled,
went stale or its world went. The renderer takes ended tasks back with `poll()`, which
never waits: `DONE` with `result()`, or `FAILED` with `failure()`. `snapshotVersion()`
gives the center's version in the snapshot, to keep with the mesh.

**Priority as the camera moves.** `reprioritize` moves one waiting task in O(log n);
`reprioritizeAll` gives every waiting task a new priority from a function and rebuilds the
heap in O(n), the call for a camera that turned or moved. Admitted tasks keep their place.
A renderer might use the squared distance from the camera, doubled for a cube outside the
view frustum, and call `reprioritizeAll` when the camera has moved a cube or turned
sharply. Because waiting tasks hold no snapshot, a task that waits a long time builds
from the cube as it is when it runs, not as it was when it was handed in.

**Stale work.** A task is always stale once its cube leaves. Its `staleOn` mask adds the
changes that make it stale after its snapshot was taken: `STALE_ON_BLOCKS` (the center's
blocks or biomes, its block version), `STALE_ON_LIGHT` (the center's light version),
`STALE_ON_NEIGHBORS` (the version of any of the 26 neighbors, their arrival and departure
included); `STALE_ON_CENTER` is the first two. Each pump of a tick, a frame or a `poll`
compares the admitted tasks with the cubes as they stand: a stale running job sees
`cancelled()` and should return, and a stale task never comes back from `poll`. A `submit`
only admits, so a renderer that hands in thousands of tasks in a frame does not check every
held task each time. `snapshotsTaken()` and `snapshotNanos()` count the pool's snapshots and
the client thread's time taking them, copies included; the stop line gives both for the world. The renderer, which hears the change as an event
(section 3), hands in a new task. A renderer that keeps light apart from geometry hands in
its geometry task with `STALE_ON_BLOCKS` only. `cancel()` ends a task at any stage; a
cancelled task never comes back either. When the world changes every task is `DROPPED`,
its snapshot released, and the threads stop; the client log says so in one line (`Client build
pool stopped:` with the threads told to stop, the tasks dropped, waiting and admitted, and the
jobs interrupted), and `liveThreads()` falls to 0. A second later the log counts them
(`Client build pool threads 1000 ms after its stop: 0 still alive`), so a thread left behind
is measured, not assumed; while any is alive it counts again at 2, 5 and 10 seconds. A pool that never ran a job started no threads and logs neither.

**Bounds.** `clientBuildThreads` threads (default a quarter of the processors, 1 to 4),
daemons just above the lowest priority, started with the first job and stopped when the
world goes. They share their permits with the preview pool: one governor (section 8,
Sampling threads) sets how many jobs of the two pools together may run, from the frame
times and, in single player, the server's ticks, and `running()` reads it. A job that runs
long calls `checkpoint()` between parts, where it waits if the permits were lowered.

The pool takes snapshots only when it is pumped, about once a frame, so it takes them ahead of
its threads: the lookahead is as many jobs as the running permits finish between two frames,
from a moving average of a job's time on its thread and of the time between frames, at least
one a thread and at most 16 a thread, and one a thread until both are measured. At most the
permits plus the lookahead hold snapshots at once (`admitted()`), so memory stays bounded
however many tasks wait; `finished()` counts results not yet taken back, which hold no
snapshot. Measured with Quadrature's terrain on 2026-11-18, 4 threads at 120 frames a second:
with one a thread the pool built about 950 meshes a second while 20,000 waited, its threads idle
for most of each frame; with the measured lookahead, up to 3,330.

## 6. Settled

### The held box

```java
// api.client.ICubeSource
int[] heldBox();                 // {centerX, centerY, centerZ, horizontal, vertical}, or null
boolean inHeldBox(int cubeX, int cubeY, int cubeZ);
// api.client.ICubeSourceListener
void heldBoxChanged();
```

The server keeps a box of cubes sent to each player: every cube within its horizontal view
distance of the player's cube along X and Z and within its vertical view distance along Y,
each inclusive. It states that box to the client (`PacketHeldBox`, 14 bytes) when the player
joins a cubic world, when the center moves to another cube and when either view distance
changes, and the source answers it with `heldBox()`, a new array each call. The distances
are the ones the server applies, which cap what the client's options ask for. The center
is the player's cube as the server last moved it, and can trail the player by a tick.

Every cube in the box is one the server will send; the client may also hold cubes outside
it, sent ahead of the player or not yet unloaded. `heldBoxChanged` arrives at the end of
the tick, after that tick's cube events; a box already stated when the world attaches
raises no event. With no world attached, or from a server that does not state the box,
`heldBox()` is null and `inHeldBox` false.

The message is new in this build. A client and server of the same Cubature version but
built before it cannot tell they differ (the version check compares mod versions), and a
client without it is disconnected when the server sends it, as with the terrain preview's
message (section 8): run both sides from this build or later.

### The settled signal

```java
// api.client.ICubeSource
int quietTicks();
boolean heldBoxSent();
boolean settled(int quietTicks);
// api.client.ICubeSourceListener
void heldBoxSent();
```

The signal has two parts, and `settled(n)` is both.

**Every cube of the box has arrived.** The server walks each player's held box after it
sends cubes each tick, from a cursor that only moves past cubes already sent to that
player, at most 4,096 a tick. When the whole box is sent it says so once
(`PacketHeldBoxSent`, naming the box). That message follows the cubes' own on the same
channel, so the client has applied every cube of the box when it reads it. The source
answers `heldBoxSent()` true only while the box the message names is the box the client
holds; a new box (the player moves to another cube, a view distance changes) makes it false
until the server reports the new one, and the listener hears `heldBoxSent()` each time it
turns true. A box of 25 cubes a side is 15,625 cubes, walked in four ticks once its cubes
are sent; while one waits, the walk costs one look-up a tick.

**Nothing has moved since.** `quietTicks()` counts the client ticks in a row, ending with the
last, in which no cube arrived, left or changed (blocks, light or biomes, a neighbor's
included), the held box did not change, and the client's light engine had no work left
queued at the tick's end. Client relighting raises change events (section 3), so a tick in
which the client relit anything is not quiet either.

`settled(n)` is `heldBoxSent() && quietTicks() >= n`; from a server that states no box it is
the quiet ticks alone. For a frame capture, `settled(20)`, one second, is the intended use:
wait for it, then capture. A player that moves keeps it false; so does any world in which
something changes every tick (flowing water, a working machine), which is why the quiet
count is a parameter.

**What settled does not say:**

- that the client's light equals the server's at that moment. The server sends a cube's
  light again when it changes without a block, at most every 10 ticks a cube (section 3),
  so light the server changed in the last half second may not have arrived; measured, a
  handful of cubes near the box's edge, none deeper than four cubes (Verification);
- anything of cubes outside the box, which the client may hold too;
- that a renderer has built what arrived: that is the renderer's own queue;
- anything of entities, block entities' contents, or Distant Horizons' level of detail;
- that it will stay settled: the next change ends it.

## 7. Threads

| Operation | Thread |
|---|---|
| Take a snapshot, release a snapshot, queries, attach or detach the listener | Client thread |
| Read a snapshot | Any |
| Receive an event | Client thread |
| Run a build task | A thread of `buildWork()` |
| Submit, reprioritize, cancel or poll a build task | Client thread |
| Read `terrainPreview()` | Client thread |
| Create and run a terrain preview sampler | Any; a preview is shared, a sampler confined to one thread at a time (`ICubicTerrainPreview`) |

## 8. Terrain preview

A generator that implements `ICubicTerrainPreview` describes any column of its world as
vertical runs of block states, from the seed alone, with no cube generated. A renderer that
draws terrain beyond the loaded cubes samples it on the client, so the server does no
preview work for that client. The source gives the attached world's preview, or null:

```java
// api.client.ICubeSource
@Nullable ICubicTerrainPreview terrainPreview();
```

`ICubeSourceListener.terrainPreviewChanged()` is raised when it becomes available or is
withdrawn. A preview already present when the world attaches raises no event; read it
then. Samplers are task-confined and may run on any thread.

### What the server sends at join

After `PacketCubicWorldData`, to a player joining a cubic dimension whose generator
implements `IClientTerrainPreviewOffer`, one message (`PacketClientPreview`). Cubature owns
the envelope; the generator owns the payload (`ClientPreviewOffer`).

| Field | Owner | Content |
|---|---|---|
| Dimension | Cubature | The dimension the preview is for |
| Factory | Generator | The name its mod registered a factory under |
| Identity | Generator | A hash that fixes everything the preview depends on |
| Seed | Generator | 8 bytes |
| Generator options | Generator | The string the world was created with |
| Recorded definitions | Generator | Opaque bytes, at most 1 MiB |

A server may keep its seed to itself: with `sendSeedForClientPreview` off (default on) the
message carries the dimension and the fact that the seed is withheld, and nothing else. The
identity is left out too, because it is a hash of the seed and would let a guessed seed be
checked. Such a client builds no preview and `terrainPreview()` stays null.

The capability bit in `PacketCubicWorldData` keeps its meaning: this server answers Distant
Horizons preview requests.

### Building the preview on a client

A generator mod registers a factory once, at mod construction:

```java
// api.worldgen
TerrainPreviewFactories.register("mymod", inputs -> ...);

public interface ICubicTerrainPreviewFactory {
    @Nullable ICubicTerrainPreview create(ClientPreviewInputs inputs);
}
```

`ClientPreviewInputs` carries the dimension, the fields of the offer, a `ForkJoinPool` for
in-build parallelism and a derived-data directory. The pool is the preview's own build pool
("The build pool" below); the directory may be null and today always is (see "Not built").

Rules:

1. **Identity first.** The factory computes its own identity from the seed, the options,
   its packaged definitions and the recorded definitions it was sent, and returns null
   unless it equals the server's. A client with a different build of the generator, or one
   that resolves a recorded block differently, draws nothing the server would not generate.
2. **No world.** The factory receives no `World`. A preview must not read or write a live
   world, a save directory or the game directory.
3. **Threads.** The factory runs off the client thread, on a thread Cubature starts for
   that one build; the generator starts none. Regional builds a sampler triggers run on
   the sampling thread.
4. **Lifetime.** The preview belongs to one client world (section 1). When that world is
   replaced Cubature takes the preview back without waiting: a column being sampled
   finishes, and every later column, and every later sampler, throws
   `TerrainPreviewWithdrawnException`. A caller drops the work in hand.
5. **Memory.** The preview's retained heap is the client's cost. Bounded memos, as on the
   server.

### Single player

A client of its own integrated server borrows that server's generator: `terrainPreview()`
returns it (behind the same withdrawal guard) and no factory runs, so the regional data
native generation already built is shared, not built twice.

- Before the server closes a world (the server-stopping event, and a world's unload ahead
  of every other unload handler) Cubature takes the loan back and waits for the columns
  being sampled. No client thread is inside a generator whose stores are closing, and a
  world that has begun to close lends nothing.
- A client leaving the world gives the loan back without waiting; the server's own
  withdrawal still waits.
- The client's sampling competes with native generation for the same regional builds. A
  renderer budgets it; the governor that reads the client frame time and the server tick
  belongs to the work executor.
- A world opened to LAN leaves the host on the shared generator; guests build their own.

### The build pool

A factory that builds a client preview gets `ClientPreviewInputs.buildPool()`: the preview's
own `ForkJoinPool` of 1 or 2 daemon threads just above the lowest priority (half the preview
pool's threads, at most 2), made with each build of a preview and shut down with it: when it is
withdrawn or replaced, when the world goes, and when the factory declines, fails or finishes for
a world that is no longer the client's. The preview is revoked first and its pool shut down
after, so no sampler starts a build on a pool that is going; work queued there is dropped and
work running is interrupted, and the client log says so in one line (`Client preview build pool
stopped:`). It is never the preview pool's threads and takes no permit of the shared budget, so
a sampling job that waits on a build split over it waits on other threads, which a join helps
rather than blocks. Its running threads count to the governor as work that ran. A preview
borrowed from the integrated server keeps that server's own build pool.

### Not built

- **A client derived-data directory**, with a size cap and eviction, keyed by preview
  identity: `ClientPreviewInputs.derivedDataDirectory()` is null, so regional data stays in
  memory and is rebuilt each session.
- **Sending the generator's regional store.** Measured with Riven on 2026-11-15, a client
  builds cold in 5 to 7 s to its first tile in an 8 km world and 22 to 35 s in a 32 km one,
  and receiving the store shortens only that start (to about 5 s), not the total. So a pull
  transfer (request, bounded chunks under a bandwidth cap, checksum, kept across sessions
  by identity) is an optimisation for fast links, not a requirement.
- **Server tiles as the fallback of last resort**, for a withheld seed or a declining
  factory: the server pool samples and sends tiles, as it does for Distant Horizons today.

### Sampling threads

A renderer samples the preview on Cubature's threads, not its own:

```java
// api.client.ICubeSource
@Nullable IClientPreviewWork previewWork();

// api.client.IClientPreviewWork
int QUEUED = 0, RUNNING = 1, DONE = 2, FAILED = 3, CANCELLED = 4, WITHDRAWN = 5;
int threads();
int running();
Task submit(long priority, Job job);
@Nullable Task poll();
interface Job { void run(ICubicTerrainPreview.Sampler sampler, Task task) throws Exception; }
interface Task { Job job(); int state(); void cancel(); boolean cancelled();
                 @Nullable Throwable failure(); void checkpoint() throws InterruptedException; }
```

- **Jobs.** `submit` takes a job and a priority; the lowest priority runs first, and jobs of
  one priority run in the order they arrived. A job runs on a pool thread with a sampler the
  pool keeps for that thread, so the rule that a sampler belongs to one task at a time is
  the pool's to keep: a job never makes or shares one. The job keeps its own result.
- **Results.** `poll`, on the client thread, returns the next task whose job has ended,
  `DONE` or `FAILED` (with what it threw), or null. It never waits; a renderer takes a few
  a frame.
- **Cancelling.** A cancelled task that was waiting never runs. One that is running sees
  `cancelled()` and should return between its rows. Neither comes back from `poll`, nor
  does a task cancelled after its job ended but before it was taken.
- **A preview that goes or changes.** Tasks belong to the preview attached when they were
  handed in. When it is withdrawn or replaced, waiting tasks become `WITHDRAWN` without
  running, running ones are interrupted and end `WITHDRAWN` (a job that meets
  `TerrainPreviewWithdrawnException` ends the same way), finished ones not yet taken are
  dropped, and the pool's threads stop. A new preview gets new threads with the first job
  handed in under it. No thread outlives the preview it sampled; with no preview attached a
  task is `WITHDRAWN` at once.
- **Threads.** `clientPreviewThreads` (default -1: a quarter of the processors, 1 to 4),
  daemons one step above the lowest priority, never the common pool.
- **Governor.** As on the server, the threads are a ceiling and permits say how many jobs
  run at once: half the threads to start, never fewer than one. Once a second the governor
  reads the last 120 frame times (the time between render ticks) and, in single player, the
  integrated server's latest ticks. A dedicated server's ticks are never read: they are in
  another process, so on a remote server only the frames decide.
  - It halves the permits while jobs ran and the frames' p95 is at or over
    `clientPreviewFrameBudgetMs` (default 25), or the integrated server's tick p95 over 20
    ticks is at or over the server pool's own 45 ms.
  - It adds one when there was demand, two seconds have passed since the last change, the
    frames' p95 is under four fifths of the budget and the tick p95 over 40 ticks under 40 ms.
    Demand is any of: a job waited for a permit, work was handed in and not yet started (in
    either pool), or a job started with every permit taken. The last matters for a renderer
    that hands in no more than `running()` allows: it never makes a job wait, but keeps the
    pool full, and that alone now raises it.
  - A long job calls `Task.checkpoint()` between rows, where it waits if the permits have
    fallen below the jobs running. A client whose frames are longer than the budget whatever
    the pool does stays at one job.
  - Each change of the permits is logged in one line (`Client work permits 1 -> 2:`) with what
    decided it (for a raise, the demand: work queued, a pool that ran full, a job that waited), the frame p95, the server tick p95 or `remote server`, and whether a job
    waited, ran, was queued and whether the pool ran full.
  - With no world attached it does nothing: no cut, no raise, no line. What the pools flagged
    and the frames drawn meanwhile are dropped, so a new world's first second is judged on
    its own.
- **Shared.** The permits are one budget for this pool and the build pool (section 5): the
  ceiling is the larger of the two pools' threads, a job of either takes a permit, and the
  governor reads both pools' waits and runs.
- **Stopping.** When the preview is withdrawn or replaced the pool stops its threads and the
  client log says so in one line (`Client preview pool stopped:` with the threads told to stop,
  the waiting tasks withdrawn and the running ones interrupted). `liveThreads()` counts the
  pool's threads still alive, 0 once they have stopped; -1 from a pool that does not track
  them. A second after the stop line the log counts them (`Client preview pool threads 1000
  ms after its stop: 0 still alive`), and the preview's own build pool does the same
  (`Client preview build pool threads …`). The count is polled from the client tick, which
  runs with or without a world, so the client thread never waits on a join.

Not run in a game: the tests cover order, cancelling, withdrawal, thread lifetime, permits
and the governor's arithmetic, with stand-in previews.

### Surface heights

A renderer that must know where a column's ground is (for example to tell which columns its
own meshes cover) asks for a whole chunk column at once, on the client thread:

```java
// api.client.ICubeSource
int NO_SURFACE = Integer.MIN_VALUE;
int surfaceHeights(int columnX, int columnZ, int[] ground, int[] liquid, boolean[] fromLoaded);
int surfaceHeights(int columnX, int columnZ, int[] ground, int[] liquid, boolean[] fromLoaded,
        boolean allowPreview);
long surfaceVersion(int columnX, int columnZ);
```

`surfaceHeights` fills 256 entries of each array, at index `localZ << 4 | localX`, and
returns how many block columns were answered from loaded cubes.

- **Ground** is the highest block that blocks movement and is not liquid, not a log
  (`BlockLog`) and not plant matter: the materials leaves, plants, vine, cactus and gourd,
  the set the Distant Horizons integration files as leaves. Planks, glass and ice are ground.
  Under a tree the ground is the soil; at an overhang it is the top of the overhang.
- **Liquid** is the highest liquid block above that ground, or `NO_SURFACE` when there is
  none.
- A height is the Y of the block's top face: the block's Y plus one, as a preview run gives
  its top.
- **From loaded cubes** where they hold the column's surface: the search goes down from the
  column's height index (the highest block that dims light, which the server keeps for the
  whole column) through leaves, logs, plants, liquid and air to the first ground, at most 256
  blocks. `fromLoaded` is true only then. If a cube on the way is not loaded, or no ground
  is found, the loaded cubes do not say where the ground is, and both heights are the
  preview's with `fromLoaded` false. Ground that lets all light through and stands above
  everything that dims it (a glass roof over open air) is looked for first: the column's
  loaded cubes that hold blocks are searched from the highest down to the height index, at
  most 256 blocks above it, and cubes that hold nothing are passed over whole. A roof in a
  cube that is not loaded is not seen.
- **From the preview** otherwise, sampled at one-block cells: the highest ground run and the
  highest liquid run above it. With no preview the ground is `NO_SURFACE`. A sample costs
  what the generator's preview costs for a column (about 0.1 ms in Riven), on the client
  thread, so a chunk column answered wholly from the preview costs about 256 of them the
  first time. Answers are kept for the last 2,048 chunk columns and dropped when the preview
  changes.
- **Without sampling.** With `allowPreview` false the preview is not sampled, so the call
  costs only the search of loaded cubes. A block column they cannot answer takes an answer
  the preview gave in an earlier call, if one is kept, and `NO_SURFACE` otherwise;
  `fromLoaded` is false either way. The five-argument form is the same call with true.

`surfaceVersion` changes whenever what the loaded cubes say could have changed: a cube of
the column loaded or unloaded, a block in one changed, or the server corrected the column's
height index. A caller that holds the number can skip a column whose number has not moved.
It does not cover the preview's answers; `terrainPreviewChanged()` does. It is one entry in
a map per chunk column touched since the world attached, about 16 bytes each, cleared when
the world detaches.

All three have defaults (`NO_SURFACE` everywhere, 0 and -1), so a source that does not
track surfaces still answers.

### What a cube holds

```java
// api.client.ICubeSource
int NOT_LOADED = 0, EMPTY = 1, HAS_BLOCKS = 2;
int cubeContent(int cubeX, int cubeY, int cubeZ);
```

Answers without a snapshot whether a cube is loaded and whether it holds anything. `EMPTY`
is given only for a loaded cube whose every block is air: the cube has no block storage, or
its storage's count of blocks that are not air is zero. The game keeps that count as blocks
are set, and Cubature recounts it when a cube arrives from the server, so a cube emptied by
edits reads `EMPTY` again and a cube with any block, a plant included, reads `HAS_BLOCKS`.
It costs one cube look-up. The default answers `NOT_LOADED`.

## 9. How the Celeritas integration maps onto this

| Today (`CeleritasCompat` and the `selectable.celeritas` Mixins) | In this contract |
|---|---|
| `section`: a loaded cube's block storage | Snapshot, block states and light |
| `EmptyCubeSection`: a stand-in for a storage-less cube, built from column tops | Snapshot, implicit sky light |
| `tileEntities`: the block entity map per section | Snapshot, block entities |
| `minSection`, `maxSection` | World, height range |
| `loadedCubeYs` | Queries, loaded set |
| `columnLoaded`, `cubeLoaded`, `cubeUnloaded` | Events |
| `applyCubeChanges`: deferring changes until the renderer applies its own events | Events arrive on the client thread at a defined point |
| The entity gatherer | Not in this contract: entities are drawn by the game |

## 10. What changes in Cubature

Adding the source is additive. The vanilla, Celeritas, Demonica and Distant Horizons
integrations are unaffected by it.

## Verification

`-Dcubicchunks.cubeSourceCheck=true` installs a listener that reads the source as a
renderer would and checks it against the live world: every cube that loads or changes is
snapshotted and compared with the game's own render cache, and 40 ticks later, if the
cube's version has not moved, its copy is compared with the cube again. A difference at
that point is a change that raised no event.

Measured 2026-11-14 on a forest tour of a Riven world (17 views, about six minutes):
144,350 arrivals, 128,725 removals and 969,934 change events, 835,689 of them neighbor
changes; 278,595 snapshots, none differing from the game's render cache where all 27
cubes were loaded and 15 differing beside a cube that was not (guarantee 5 of section 2);
133,798 cubes rechecked with their version unmoved, none changed. The tour changes no
blocks by hand, so the small block-change packet route is exercised only by what the
server does on its own.

The same switch also follows each removal: five ticks after a cube leaves, the vanilla
render chunk at its position, if the render grid still covers it, must hold no mesh, and
every report counts the cubes the client still holds in a column it no longer has.

**Light checksums.** `-Dcubicchunks.lightChecksumDump=<directory>` has each side write a
line per cube it holds (position, light checksum, block checksum, whether it has storage
and whether its first light is done): the server to `server-<dimension>.tsv` as it stops,
the client to `client-<dimension>.tsv` every 200 ticks and as its world goes.
`tools/compare_light_checksums.py` compares two dumps over the cubes interior to both.
`-Dcubicchunks.noClientBoundaryRelight=true` skips the client's relight of a cube's
boundaries on arrival, for bisecting.

Measured 2026-11-15 on fresh Riven worlds of one seed, the first two views of the forest
tour, eight runs (25 × 25 × 25 cubes held on each side, 12,167 interior):

| Compared | Cubes whose light differs | Blocks differ |
|---|---|---|
| Server, two identical runs (three pairs) | 21, 26, 46 | none |
| Server, single-threaded generation, two runs | 46 | none |
| Client against its own server, each of six runs | 408 to 429 | none |
| The same, client boundary relight off (two runs) | 408, 409 | none |

Every difference lies within five cubes of the edge of what the side holds; none deeper.
Between servers they lie mostly within four cubes of the top face, under the lowest cubes
not yet loaded. Between client and server 275 cubes differ in all six runs, so that part
is systematic, not a matter of order. Single-threaded generation and turning the client's
boundary relight off change neither. The client's part had a cause: the server relit
cubes it had already sent as cubes beyond them arrived, and a change of light alone was not
sent (the server's world listener ignores `notifyLightSet`; only changed blocks were queued
for a player), so the client kept the light it was first sent.

Measured 2026-11-16, same seed and views, with the server counting the cubes whose light
changed after they were sent (`server-lightafter-<dimension>.tsv` in the dump):

| Run | Light changed after a send | Light sends | Client against its server |
|---|---|---|---|
| `deliverLightChanges` off | 2,441 cubes, 3,216 changes | none | 405 cubes differ; 398 of them had changed after a send |
| `deliverLightChanges` on | 2,449 cubes, 3,201 changes | 3,120, 12.8 MB in the run | 19 cubes differ, none deeper than four cubes inside the edge |

The 19 left lie near the top of the box. The client's dump is written every 200 ticks
and the server's as it stops, so the two can be up to ten seconds apart; light the server
changed in between, or within the 10 ticks a send waits, is not in the client's. Nothing
more than five cubes inside the edge has differed in any run. The cubes that differ between
two servers (21 to 46, by generation order) are untouched by this and remain open.

**Only the changed light.** Measured 2026-11-16, same seed and views, 1,200 ticks a view,
with `deliverOnlyChangedLight` on: 3,093 to 3,154 light sends of 2.4 MB in a run, against
12.8 MB for the whole light, and 36 to 66 cubes differing between a client and its server
in four runs, against 19 with the whole light. In the cubes that still differ every block
that differs is sky light, the client darker than the server by 1 to 8 and more, at blocks
the server never changed: the client darkens sky light on its own, and only the whole light
undid it. The option stays off until that is found.

**Between two servers.** Measured 2026-11-16 on two pairs of fresh worlds of one seed,
dumps every 600 ticks over 2,400 ticks at the views, then each world reloaded from its save
and visited again, then 64 blocks higher so that four layers of cubes load above the old
top:

- Blocks: identical in every comparison of every run (the block checksum hashes the state
  of all 4,096 blocks of each cube). Generation is deterministic.
- Light: 30 to 43 of 12,167 cubes differ. The count falls for the first 1,800 ticks and
  then holds; no differing cube has a boundary check pending; after the reload the same 30
  differ, and after the cubes above load, 32. It is not a transient of the loaded edge:
  the light is final, saved and never revisited.
- What differs: only sky light, almost only in air (13,132 of 13,434 differing blocks), in
  cubes 13 to 16 under the crowns of the tallest trees. In each cube one world holds sky
  light 0 where the other holds light that came in from the side (1 to 15): light that
  never spread there. Which world goes dark changes from cube to cube and run to run.
- Not the cause: concurrent generation (single-threaded generation differs as much), the
  client, storage made after first light (relighting those blocks changed nothing,
  measured), storage-less cubes (every differing cube has storage in both worlds).

## Open points

Each has a proposed answer. None is settled until it is implemented and measured.

Terrain preview (section 8): whether an identity should refuse or only warn when nothing
but biome colours differ; the store's granularity, whole world or per continent; whether a
dedicated server should bake its regional data at world creation; and whether the
seed-withheld mode, which costs the server what Distant Horizons costs it today, is worth
keeping.

1. **What a copy costs.** Copies are shared between snapshots per cube version (section
   2). The time and memory of one copy are not measured.
2. **Settled on the client.** Answered by section 6: the server states the held box and
   says when every cube of it is sent, and the client counts quiet ticks. Left open: light
   the server changes after it sent a cube (point 5).
3. **Biome data.** Whether the stored biomes are enough for the game's color blending at
   neighborhood edges.
   *Proposed:* the neighborhood is enough for the game's default blend radius; confirm
   against the game's biome color code.
4. **Block entities off-thread.** Guarantee 1 of section 2 inherits the game's hazard.
   *Proposed:* accept it, as the game does.
5. **First-run light.** Frame captures show ground under tree canopy darker on freshly
   generated terrain than on the same terrain after a save and reload. The cause is not
   established. Whatever it is, a light change must raise a change event; one that does
   not is a defect against guarantee 3 of section 3. Light checksums (Verification) find
   light identical between runs and between client and server more than five cubes inside
   what is loaded, and differing nearer the edge: by order between servers, and
   systematically between a client and its server. The client's part is answered: the
   server now sends light that changes after a cube was sent (section 3), and a client
   matches its server but for a few cubes near the edge (Verification). Left open: two
   servers differ in a few dozen cubes of air under tall crowns, where one never received
   sky light from the side; the difference is final and saved (Verification, Between two
   servers). It is a defect of light propagation that depends on the order cubes are lit
   in, and the pass that drops the light is not found. *Proposed next:* record, for the
   cubes that go dark, when each was first lit, the column tops it was lit against, and
   every later change of those tops and of its neighbors' arrival, and compare a dark run
   with a bright one.
