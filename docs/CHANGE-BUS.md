# Change bus

Status: milestone 1 is built: the feed, the scopes, the per-tick batches, the edit version saved with each cube,
and the public API in package `api.change`. Later milestones are design: the generation baseline, the far-view
stream that sends distant players the edits that differ from the terrain preview, and the interest and policy
registries.

The change bus is the server's one stream of block changes in cubic worlds. Every block change that is not
generation feeds it, and anything that wants to know what changed subscribes to it: distant terrain, maps, claims,
caches. A writer needs no new API to be seen, and a reader needs no hook of its own.

## The feed

| Source | Where | Notes |
|---|---|---|
| `Chunk.setBlockState` (vanilla, WorldEdit for Forge, structures, machines) | `MixinChunk_Cubes`, beside the cube's `markDirty()` | Once for each block that really changes; an unchanged write returns before it |
| Writers that change cube storage directly | `ICube.markEdited(mask, cause)` | The same journal |

Writers that go around both are not seen. A writer through `Chunk.getBlockStorageArray()` already misses cubic
columns, which keep their storage in cubes, so it is broken on Cubature either way.

## Causes

A thread's writes carry the innermost cause pushed with `ChangeScope.push(cause)`, or `cubicchunks:unknown`.

| Cause | Pushed by | Journaled |
|---|---|---|
| `cubicchunks:generation` | Cubature around `cubeGen.populate` and `GameRegistry.generateWorld` in `CubeProviderServer.populateCube`; mods around their own generated content | Never |
| `cubicchunks:natural` | Cubature around the cubic random-tick pass (`MixinWorldServer.updateBlocksCubicChunks`: random ticks, ice and snow) | Only with `changeCountNaturalTicks` |
| `cubicchunks:unknown` | Nobody: the default | Yes |
| a mod's own | The mod, around its writes | Yes |

For `changeSettlingTicks` (200 by default) after a cube is populated, and again after it is first fully
populated, its writes are not journaled: springs, falling sand and mod fluids keep writing for a while after
population. Both starts matter: a cube's own population places springs that flow before its neighbors are
populated, and players are sent a cube only once it is fully populated. The window is not saved, so a cube loaded
from disk is never settling. Milestone 2 turns these writes into refreshes of the cube's baseline.

## Journal and dispatch

- The first write to a cube after a drain puts it on its world's queue. Later writes in the same tick only OR a
  64-bit mask of the 4x4x4 sub-blocks written (`CubeChange.subBlockIndex`) and add their cause if it is new, so a
  paste of millions of blocks makes one change per cube.
- At the end of each server tick (`tickCubicWorld`), the queue is drained into one `CubeChange` per cube. Each
  takes the next version of the save's counter, and the cube keeps it with the causes.
- The changes go to each subscriber whose `ChangeFilter` passes some of them, on the server thread. A listener may
  take `CubeChangeBatch.snapshot(pos)`, a copy of the cube's blocks safe to read from any thread, during its call
  only. An exception in one listener is logged and does not reach the others.

## Versions and saving

- The counter is `ChangeVersions`, in the save's shared data (`data/cubicchunks_change_versions.dat`), one for every
  dimension. Loading moves it on by 2^20, so a version handed out after the last save and lost in a crash is not
  handed out again.
- A cube saves its latest change as `editVersion` and `editCauses`, next to `fullyPopulated`. Older builds ignore
  both tags. A cube with no change since the bus existed has no tags and version 0.
- The bus keeps no history. A subscriber that needs history keeps its own.

## Public API (`api.change`, version `ChangeApi.CHANGE_API_VERSION`, 1.0)

| Type | Use |
|---|---|
| `ChangeApi.bus()` | The bus, installed in Cubature's pre-initialization |
| `IWorldChangeBus.subscribe(filter, listener)` | Receive batches; close the returned subscription to stop |
| `ChangeFilter` | `edits()`, `everything()`, `causes(...)`, `.inDimension(dim)` |
| `CubeChangeBatch`, `CubeChange` | One tick's changes in one world: position, version, sub-block mask, causes |
| `ICubeBlockCopy` | A cube's blocks copied on the server thread |
| `ChangeScope`, `ChangeCauses` | Name the cause of a run of writes |
| `ICube.markEdited`, `ICube.getEditVersion` | Report a direct write; read a cube's version |

## Not built yet

- Tile-entity changes (`TileEntity.markDirty` for listed classes), and a `player` cause around player block
  placement and breaking. Today those writes carry `unknown`.
- The generation baseline, the far-view stream, interest domains and far-view policies (milestones 2 to 4 of the
  spec).
- A measurement of the feed's cost under a bulk paste. The hot path is a thread-local read, two compares, an OR and
  a branch; the queue is touched once per cube per tick.
