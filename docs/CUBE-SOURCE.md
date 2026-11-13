# Cube source (design draft)

Status: draft, not implemented. When it is implemented, the interfaces live in Cubature's
public API module and this file describes them as built.

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
| Version | See section 3 |

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
   faces drawn against the gap are rebuilt.
6. **Empty is cheap.** A snapshot whose center cube is storage-less says so without being
   read block by block.

**Cost.** Cubature may share unchanged cube data between snapshots instead of copying it.
The contract requires only the guarantees above. The Celeritas integration copies blocks
and block entities per section; that is the cost to beat.

**Release.** A renderer releases a snapshot when its build finishes or is cancelled.
Holding one must not keep a cube loaded.

## 3. Versions and events

Each loaded cube has a version: a number that increases whenever anything a snapshot
would capture changes. A snapshot records the version of each cube it holds, so a renderer
can discard a build whose center cube has since changed.

Cubature delivers events to one listener, on the client thread, after the data they
describe is readable:

| Event | When |
|---|---|
| World attached, world detached | The client world appears or is replaced |
| Cube loaded | A cube's data arrived, including a full resend of a cube already held |
| Cube unloaded | A cube left the client |
| Cube changed | Blocks, light or biomes in a loaded cube changed; carries the kinds that changed and, where known, the bounds within the cube |

**Guarantees:**

1. Events arrive in the order the changes were applied.
2. A change in a cube's outermost layer of blocks, or its arrival or removal, also raises
   a change for each loaded neighbor that touches it.
3. Cubature translates every route by which a cube changes into these events: whole-cube
   packets (`PacketCubes`), small block-change packets (`PacketCubeBlockChange`), the
   game's own block and light notifications, and its own lighting. A renderer listens to
   nothing else.
4. Events may be coalesced within a tick: several changes to one cube may arrive as one
   event with their kinds and bounds combined.

## 4. Queries

- The set of loaded cube positions, for a renderer that starts or restarts while a world
  is already loaded.
- Whether a given cube is loaded, and its current version.

## 5. Work executor

A renderer need not start threads. Cubature provides a client-side executor with:

- a bounded number of workers, stated by Cubature and readable by the renderer;
- task priority, so near and in-view cubes build first;
- cancellation of a queued or running task;
- no use of the common pool.

Tasks read snapshots and write only memory the renderer owns.

## 6. Settled

The source reports whether cube arrival for the current view is settled: every cube the
client expects within its view distance has arrived, and no light work that would raise a
change event is outstanding. Automated frame captures wait on this.

## 7. Threads

| Operation | Thread |
|---|---|
| Take a snapshot, release a snapshot, queries, attach or detach the listener | Client thread |
| Read a snapshot | Any |
| Receive an event | Client thread |
| Run a build task | Cubature's executor |

## 8. How the Celeritas integration maps onto this

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

## 9. What changes in Cubature

Adding the source is additive. The vanilla, Celeritas, Demonica and Distant Horizons
integrations are unaffected by it.

## Open points

Each has a proposed answer. None is settled until it is implemented and measured.

1. **Sharing against copying.** Whether cube data can be shared between snapshots without
   changing how cubes are stored, and what a copy costs if not.
   *Proposed:* copy first, as the Celeritas integration does, and measure. The guarantees
   in section 2 do not change if sharing is added later.
2. **Settled on the client.** The server pushes cubes and the client holds no list of
   what to expect.
   *Proposed:* define settled as quiet: no cube packet applied and no client light work
   outstanding for a stated number of consecutive ticks. A signal from the server that a
   player's view is fully sent would be exact and can replace it later.
3. **Biome data.** Whether the stored biomes are enough for the game's color blending at
   neighborhood edges.
   *Proposed:* the neighborhood is enough for the game's default blend radius; confirm
   against the game's biome color code.
4. **Block entities off-thread.** Guarantee 1 of section 2 inherits the game's hazard.
   *Proposed:* accept it, as the game does.
5. **First-run light.** Frame captures show ground under tree canopy darker on freshly
   generated terrain than on the same terrain after a save and reload. The cause is not
   established. Whatever it is, a light change must raise a change event; one that does
   not is a defect against guarantee 3 of section 3.
