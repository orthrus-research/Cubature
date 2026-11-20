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
exact artifact. It records the build in the workspace's `.local/builds/cubature-latest.json` and
`cubature-<branch>.json`, and publishes each artifact to `.local/builds/cubature/` under the commit
it was built from, so builds from several worktrees do not replace one another. The Python tools have their own tests
(`python -m pip install -r tools/requirements.txt`, then
`python -B -m unittest discover -s tools -p "test_*.py"`). Changes that affect scheduling, storage, lighting or delivery also need a native
check in the lab ([lab/README.md](lab/README.md)) or, within Riven's workspace, `riven.ps1 full`.

Before a push, `python tools/ci.py [commit]` checks the commit (HEAD by default) in a worktree of
its own, so nothing uncommitted takes part: the compile-only inputs, the build with its focused
regressions on Java 26, the lab's build and checks, and every tool test in a virtual environment
made from `tools/requirements.txt`. Gradle runs on the JDK 25 in `JAVA_HOME` (or `--java25`), and
`--java26` names the JDK for the regressions when Gradle does not find one. A pass is recorded under
the commit's tree in the main checkout's `.local/ci/passes.json`; the pre-push hook that
`python tools/push_gate.py install` writes refuses a pushed tip whose tree has no pass.

## Commits and documentation

- One reviewed change per commit. The message says what was run and what it showed.
- Describe a change in the document that owns its area (`docs/`), and add it to
  [MODIFICATIONS.md](MODIFICATIONS.md) when it changes behavior.
- Run output and evidence stay out of the repository. Editor and assistant configuration stays
  local (see `.gitignore`).
