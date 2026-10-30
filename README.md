<p align="center"><img src=".github/assets/banner.svg" alt="Cubature by Orthrus Research" width="100%"></p>

<p align="center">
<a href="#status"><img alt="Status: research preview" src="https://img.shields.io/badge/status-research%20preview-000000?style=flat-square&labelColor=000000"></a>
<a href="NOTICE.md"><img alt="License: MIT and LGPL-2.1" src="https://img.shields.io/badge/license-MIT%20%2F%20LGPL--2.1-000000?style=flat-square&labelColor=000000"></a>
<a href="https://discord.gg/qCVqhXtSzR"><img alt="Discord" src="https://img.shields.io/badge/discord-join-000000?style=flat-square&labelColor=000000"></a>
</p>

Cubature is a maintained fork of [CubicChunks](https://github.com/OpenCubicChunks/CubicChunks) for
Minecraft 1.12.2, developed by Orthrus Research alongside
[Riven](https://github.com/orthrus-research/Riven). It owns the infrastructure of cubic worlds:
scheduling, lighting, cube lifetime, storage and delivery to clients, with optional integration
for the official [Distant Horizons](https://modrinth.com/mod/distanthorizons) release. Riven
supplies terrain and biome content.

Cubature is not an official OpenCubicChunks release and implies no endorsement by its authors.

## Status

Research preview: public for study and testing, with no releases yet; they will be published on
GitHub Releases. Existing CubicChunks identities and save formats are kept (see below), so worlds
load as CubicChunks worlds, but no release yet promises compatibility guarantees of its own.

## Changes from CubicChunks

The fork began at upstream commit `53847757c7998f3541fccc8c9b5d23dd23e4077d`.
[MODIFICATIONS.md](MODIFICATIONS.md) records its changes; in summary:

- Cleanroom support and modern Java (development on JDK 25; the tested runtime is Java 26). The
  Forge 14.23.5.2860 / Java 8 fallback was retired on October 30, 2026.
- Asynchronous load completion with clear ownership; persistence, storage-recovery, lighting and
  scheduled-tick fixes.
- A delivery scheduler: bounded worker threads generate cubes and prepare columns off the server
  thread for generators that declare concurrent generation, and the server thread installs them
  within a per-tick budget.
- Optional Distant Horizons integration through Mixins. The Distant Horizons JAR stays the
  unmodified upstream release, and none of its code is bundled.
- Shader support: packs under Demonica receive the camera's depth below the surface, and an opt-in
  option (off by default) adapts Complementary's cave fog at load time without changing its files.

## For players and pack makers

Cubature replaces CubicChunks: install one or the other, never both. It keeps the `cubicchunks`
and `cubicchunkscore` mod IDs, the `io.github.opencubicchunks` packages, command and config
identifiers, and the CubicChunks save format, so addons built for CubicChunks find what they
expect. The tested runtime is official Cleanroom (the version Riven pins) on Java 26; Riven's
[runtime profile](https://github.com/orthrus-research/Riven/blob/main/docs/RUNTIME.md) lists the
exact files.

## For addon and mod developers

The public API is `CubicChunksAPI/`, unchanged in its packages. A world generator that is a pure
function of seed and coordinates can declare concurrent generation
(`concurrentGenerationThreads`, `concurrentGenerationQueue`, `concurrentGenerationTickMillis`) to
have cubes generated off the server thread. It can also take each world's build pool through
`ICubeGenerator.useBuildPool` (`generatorBuildThreads`) for its own parallel work, so that work
stays inside the worker budget instead of the JVM's common pool. The `deliveryMode` config option selects how cubes
are unloaded, saved and prefetched, and [docs/DISTANT-HORIZONS.md](docs/DISTANT-HORIZONS.md)
describes the Distant Horizons adapter.

With concurrent generation active, `NetHandlerPlayServer.setPlayerLocation` into a column that is
neither loaded nor saved is held, for at most 600 ticks, while the workers prepare that column. The
player stays where they are meanwhile, so code that reads the player's position right after the call
still sees the old one. A later teleport that moves the player replaces a held one; a teleport to
where the player already is, as vanilla sends to re-send an unconfirmed teleport or correct a
rejected move, does not. `PlayerCubeMap.arrivals().teleportNow` moves a player at once, generating
the column on the server thread if it must.

## Building from source

The supported build is the Unimined project in `cleanroom/`, which needs JDK 25. It compiles against the official Distant Horizons 3.3.3 and Demonica 0.9.0 JARs,
which are never bundled. Inside Riven's workspace it reads them from Riven's `dev/pins`; in a
standalone clone, fetch them first (each is checked against its SHA-256):

```sh
python tools/fetch_build_inputs.py
cd cleanroom
./gradlew build
```

The build writes `Cubature-*-cleanroom.jar` with a sources JAR and the component license texts. The root Gradle build is upstream's legacy ForgeGradle setup, not
the supported build. `tools/build_cubature.py` runs the tests and builds the optional measurement
lab (`lab/`) against the exact artifact; see [docs/cleanroom-port.md](docs/cleanroom-port.md) and
the [lab guide](lab/README.md). [CONTRIBUTING.md](CONTRIBUTING.md) lists the checks a change must
pass.

## Documentation

| Document | Contents |
| --- | --- |
| [MODIFICATIONS.md](MODIFICATIONS.md) | Fork changes and the source and distribution requirements |
| [docs/cleanroom-port.md](docs/cleanroom-port.md) | Cleanroom and Java support |
| [docs/DISTANT-HORIZONS.md](docs/DISTANT-HORIZONS.md) | The optional Distant Horizons adapter |
| [lab/README.md](lab/README.md) | The optional in-game measurement lab |

## Community

Questions and discussion: [Discord](https://discord.gg/qCVqhXtSzR). Bugs and requests for
Cubature: this repository's issues, not upstream's. Security and private matters:
admin@orthrus.cc. Pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).

Orthrus Research develops with AI assistance (Claude Code and Codex). Every change meets the same
verification gates whatever its origin. See
[AI usage](https://github.com/orthrus-research/.github/blob/main/AI.md).

## License

Cubature keeps CubicChunks' terms: MIT, with the Forge-derived asynchronous I/O package under
LGPL-2.1. [NOTICE.md](NOTICE.md) gives the full statements and [MODIFICATIONS.md](MODIFICATIONS.md)
the modified files. Original authorship and copyright headers are intact. Binary and sources JARs
include every component license and notice. Riven's NRR license does not apply to Cubature.

## Upstream

CubicChunks extends Minecraft's world height and depth to the limits of a 32-bit integer. It is the
work of OpenCubicChunks and its contributors, and Cubature builds on all of it. The upstream
project, its downloads and its community are theirs:

- [OpenCubicChunks/CubicChunks](https://github.com/OpenCubicChunks/CubicChunks) (1.12.2 and earlier)
  and [CubicChunks2](https://github.com/OpenCubicChunks/CubicChunks2) (later versions)
- [CubicChunks on CurseForge](https://www.curseforge.com/minecraft/mc-mods/opencubicchunks)
- The [Cubic Chunks Discord](https://discord.gg/kMfWg9m)

Cubature's public history begins with its own first commit, so upstream's authors are named here.
CubicChunks began from Cuchaz's Tall Worlds mod and Robinton's first implementation. Its
contributors up to the fork point, by number of commits: Barteks2x (Bartosz Skrzypczak), Razaekel,
Foghrye4, jeff, Cuchaz, Cyclonit, DaPorkchop_, xcube, Malte Schütze, Niko, Andrew Athalye,
SirSavary, Babbaj, Thorin Stonehelm, MineCrak, Shad0wlife, Tom Martin, NotStirred, Blueberry,
Boreeas, Creeam, CursedFlames, Santiago Castro, Technoturnovers, ThisTestUser, notoriousgtw and
sirati97. The full history is in the
[upstream repository](https://github.com/OpenCubicChunks/CubicChunks/commits/53847757c7998f3541fccc8c9b5d23dd23e4077d).

The upstream logo is not used for Cubature.

---

<sub>Thanks to [Niko-sk2x](https://github.com/Niko-sk2x) for her stewardship of and continued work
on Cubic Chunks, and to [NotStirred](https://github.com/NotStirred),
[CursedFlames](https://github.com/CursedFlames), thatsalamander and DaPorkchop_ for their work on
it.</sub>
