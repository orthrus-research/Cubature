# Contributing to Cubature

Pull requests are welcome. The organization's
[contributing guide](https://github.com/orthrus-research/.github/blob/main/CONTRIBUTING.md),
[code of conduct](https://github.com/orthrus-research/.github/blob/main/CODE_OF_CONDUCT.md) and
[AI usage terms](https://github.com/orthrus-research/.github/blob/main/AI.md) apply. This page adds
what is specific to Cubature. Report Cubature problems here, not to upstream CubicChunks.

## Compatibility is a contract

- The `cubicchunks` and `cubicchunkscore` mod IDs, the `io.github.opencubicchunks` packages,
  command and config identifiers, registry IDs and the save format stay as they are. Renaming any
  of them needs an explicit migration and its own discussion.
- Upstream authorship, copyright headers and license notices stay intact. Files under the
  Forge-derived asynchronous I/O package keep LGPL-2.1; record changes to them in
  [MODIFICATIONS.md](MODIFICATIONS.md).
- Distant Horizons is used as the official, unmodified release. Its behavior changes only through
  Mixins or ASM in Cubature, with the source and license of anything adapted recorded.
- Worker threads may change when a cube is ready, never what it contains.

## Checks

Build with the tests from `cleanroom/` (JDK 25; Cleanroom is the only target):

```sh
./gradlew build
```

`tools/build_cubature.py` runs the same tests and also builds the measurement lab against the
exact artifact. The Python tools have their own tests
(`python -m pip install -r tools/requirements.txt`, then
`python -B -m unittest discover -s tools -p "test_*.py"`). Changes that affect scheduling, storage, lighting or delivery also need a native
check in the lab ([lab/README.md](lab/README.md)) or, within Riven's workspace, `riven.ps1 full`.

## Commits and documentation

- One reviewed change per commit. The message says what was run and what it showed.
- Describe a change in the document that owns its area (`docs/`), and add it to
  [MODIFICATIONS.md](MODIFICATIONS.md) when it changes behavior.
- Run output and evidence stay out of the repository. Editor and assistant configuration stays
  local (see `.gitignore`).
