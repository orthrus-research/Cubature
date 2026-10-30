# Cleanroom and Java 25 development

This fork targets Minecraft 1.12.2 on Cleanroom 0.6.13-alpha / JDK 25, run on Java 25 or 26,
with the upstream CubicChunks API and source tree, including the earlier asynchronous
completion ownership fix. The Forge 14.23.5.2860 / Java 8 fallback was retired on
October 30, 2026, so the source may use any Java 25 API.

The isolated `cleanroom/` project uses the Cleanroom Unimined conventions.
The repository's original ForgeGradle project is preserved. Build with a
JDK 25 launcher:

```sh
cd cleanroom
./gradlew build
```

Use `gradlew.bat` on Windows. The remapped runtime JAR is under
`cleanroom/build/cleanroom/libs`. The
`*-dev.jar` files are development outputs, not native runtime artifacts.

The port does not bundle Mixin into Cleanroom. Minecraft mods belong in remapped mod configurations; ordinary
Java libraries remain separate. Native world mutation, lighting, event
publication and saving keep their existing thread ownership.

The bundled RegionLib is the hash-verified maintainer publication
`0.78.0-20210527.114505-2`; its matching source revision and license are
recorded under `cleanroom/licenses/regionlib`. The old flow-noise embed
has no uses in the mod or API and is omitted. Addons that implicitly relied
on that bundled library have not been qualified.

The port supplies MCP nullability metadata for compilation only. It does
not bundle that shim, Minecraft classes, or Mixin. Soft-interface bridges
preserve the public CubicChunks names while vanilla methods are remapped;
the overloaded pathfinding injection now identifies its exact target.

## Java 25 watchdog

The vanilla compatibility generator's watchdog previously called
`Thread.stop()`, which throws on Java 25. It now records one diagnostic per
active nested generation operation and keeps at most 32 recent stack
samples. Reports remain available to Minecraft's crash-report callable.
The watchdog does not stop or
interrupt the generation thread. Fatal recovery from a truly stuck
generator requires the server's watchdog or external supervision.

The Riven cube generator does not use this vanilla compatibility watchdog.
Its state transitions have focused tests; native timeout recovery is not
qualified by Riven's generation tests.

## Build validation

The source-built remapped artifact passes the focused async-completion,
hash-distribution and watchdog tests on Java 25 and 26. Build checks
also verify Java 25 bytecode, the six mixin configurations and
generated refmaps, public API naming, library checksums and packaging.
The original LaunchWrapper-based upstream test suite is not part of this
new test task and has not passed here.

A later persistence pass added region-file and heightmap-format regressions,
canceled-load ownership and callback identity coverage, and native
generation/save/restart checks.

## Cube lookup hash

The old queued-cube hash multiplied Y by Z. At Z=0, every Y for a fixed X
and world had the same hash; at Y=0, every Z had the same hash. Mixing each
coordinate independently removes these predictable collision planes in
the async cube task map. Equality and saved-world keys are unchanged.

The synthetic diagnostic
measures hash and bucket distributions for fixed coordinate sets. It does
not measure lookup latency, native generation, disk I/O, server throughput
or frame rate.

## Missing map mixin

An upstream mixin configuration lists `common.MixinItemMap`, whose source is absent from
CubicChunks. The entry is kept, so loading logs a missing-mixin warning; held-map behavior is
unqualified.

## Native validation

The port was first validated natively on Cleanroom 0.6.13-alpha with Temurin 25.0.3, using
Riven as the generator:

| Check | Cleanroom / Java 25 |
| --- | --- |
| Source build and focused regressions | Pass |
| Native generation and restart | Pass |
| Writes at -128, -1, 256, 383; reject -129 and 384 | Pass |
| Block light at -120 and 272 | Source 15, neighbor 14 retained |
| Furnace inventories at -120 and 272 | Three diamonds each retained |
| Independent saved-file fixture inspection | Pass; 4,638 saved cubes |
| Shutdown | Exit 0, no shutdown exception |

The saved-cube count describes those test worlds and is not a coverage, memory or performance
guarantee. Current native checks run through the [lab](../lab/README.md) and Riven's smoke
capture (`riven.ps1 full`).

Still unqualified: client launch/rendering, network play, OptiFine and
Bukkit hybrid integrations, the full upstream LaunchWrapper test suite,
long-running load/unload and concurrent native I/O stress, complete addon
compatibility, terrain/content/progression parity and native performance.
Optional OptiFine/Bukkit/Celeritas and wildcard-selector remap warnings remain
visible. The build compiles against the DH JAR and the Celeritas JAR inside
Demonica, which `tools/fetch_build_inputs.py` fetches (a Riven checkout
supplies them from `dev/pins`). The prior synchronous load-event recursion limitation remains.
The port keeps native world mutation on its existing owner thread; it
does not introduce concurrent chunk generation or virtual-thread I/O.
