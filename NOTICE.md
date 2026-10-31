# Credits

Cubature is an independently maintained fork of CubicChunks. Fork changes
copyright (c) 2026 Orthrus Research. Existing component terms below remain
in force; Riven's NRR license does not replace them. See `MODIFICATIONS.md`
for modification dates, scope and corresponding-source instructions.

The separately installed Distant Horizons dependency retains its own
licenses. Its notice and license texts are included under
`META-INF/licenses/distanthorizons/` in supported builds. No DH implementation
is currently bundled into Cubature.

## Distant Horizons queue retry patch

`MixinDistantHorizonsQueueBackoff.java`, copyright (c) 2026 Orthrus Research,
is licensed under LGPL-3.0-only. This source-level runtime integration targets
DH 3.3.3's `ChunkUpdateQueueManager` and retains its queue and dispatch code.
The LGPL-3.0 and accompanying GPL-3.0 texts are packaged under
`META-INF/licenses/distanthorizons/`. This explicit exception takes precedence
over the MIT default below; Riven's NRR license does not cover it.

## Distant Horizons native-first admission patch

`MixinDistantHorizonsNativeFirst.java`, copyright (c) 2026 Orthrus Research,
is licensed under LGPL-3.0-only. This source-level runtime integration targets
DH 3.3.3's `WorldGenerationQueue`: it reports the generator busy while
Cubature's native view is behind or while Cubature's preview pool admits no
more tiles, and retains DH's queue, selection and generation code. The LGPL-3.0 and accompanying GPL-3.0 texts are packaged under
`META-INF/licenses/distanthorizons/`. This explicit exception takes precedence
over the MIT default below; Riven's NRR license does not cover it.

## Async chunk loading
The asynchronous chunk loading executor code in `io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge` is based on parts of
[MinecraftForge](https://github.com/MinecraftForge/MinecraftForge) and licensed under the
[GNU Lesser General Public License v2.1](http://www.gnu.org/licenses/lgpl-2.1.html):
  - [Original license text](https://github.com/MinecraftForge/MinecraftForge/blob/279380b4f1aa02a4df5e2b323c11ca6ab1531431/LICENSE-new.txt)
  - [Original sources](https://github.com/MinecraftForge/MinecraftForge/tree/279380b4f1aa02a4df5e2b323c11ca6ab1531431/src/main/java/net/minecraftforge/common/chunkio)

The full license is packaged as `META-INF/licenses/forge/LGPL-2.1.txt`.
Modifications and the additional helpers in this package retain those terms.

## io.github.opencubicchunks.cubicchunks.core.lighting.phosphor package

MIT License

Copyright (c) 2018 @PhiPro95 and @Mathe172
Copyright (c) 2021 @jellysquid_
Copyright (c) 2021 OpenCubicChunks
Copyright (c) 2021 contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Everything else

MIT License

Copyright (c) 2021 OpenCubicChunks
Copyright (c) 2021 contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
