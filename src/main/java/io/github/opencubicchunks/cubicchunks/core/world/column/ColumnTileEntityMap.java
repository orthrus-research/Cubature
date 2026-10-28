/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.core.world.column;

import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import java.util.Map.Entry;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class ColumnTileEntityMap implements Map<BlockPos, TileEntity> {

    private final IColumn column;

    public ColumnTileEntityMap(IColumn column) {
        this.column = column;
    }

    @Override public int size() {
        int size = 0;
        for (ICube cube : column.getLoadedCubes()) size += cube.getTileEntityMap().size();
        return size;
    }

    @Override public boolean isEmpty() {
        for (ICube cube : column.getLoadedCubes()) if (!cube.getTileEntityMap().isEmpty()) return false;
        return true;
    }

    @Override public boolean containsKey(Object o) {
        return o instanceof BlockPos && tileEntities((BlockPos) o).containsKey(o);
    }

    @Override public boolean containsValue(Object o) {
        if (!(o instanceof TileEntity)) {
            return false;
        }
        BlockPos pos = ((TileEntity) o).getPos();
        int y = Coords.blockToCube(pos.getY());
        ICube cube = column.getLoadedCube(y);
        return cube != null && cube.getTileEntityMap().containsValue(o);
    }

    @Nullable
    @Override public TileEntity get(Object o) {
        return o instanceof BlockPos ? tileEntities((BlockPos) o).get(o) : null;
    }

    private Map<BlockPos, TileEntity> tileEntities(BlockPos pos) {
        // when something other than CHECK is passed into Chunk.getTileEntity, then if the current TE is null
        // it will try to create a new one. To do that it will get a block which will load the cube
        // with the already existing TE. But the "create new TE" code will continue not knowing the TE just got loaded
        // and will replace the newly loaded one
        // so load the cube here to avoid problems. Other places use getCube() for consistency
        return column.getCube(Coords.blockToCube(pos.getY())).getTileEntityMap();
    }

    @Override public TileEntity put(BlockPos blockPos, TileEntity tileEntity) {
        return tileEntities(blockPos).put(blockPos, tileEntity);
    }

    @Nullable
    @Override public TileEntity remove(Object o) {
        if (!(o instanceof BlockPos)) {
            return null;
        }
        BlockPos pos = (BlockPos) o;
        int y = Coords.blockToCube(pos.getY());
        ICube cube = column.getLoadedCube(y);
        return cube == null ? null : cube.getTileEntityMap().remove(pos);
    }

    @Override public void putAll(Map<? extends BlockPos, ? extends TileEntity> map) {
        map.forEach(this::put);
    }

    @Override public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override public Set<BlockPos> keySet() {
        return new AbstractSet<BlockPos>() {
            @Override public int size() {
                return ColumnTileEntityMap.this.size();
            }

            @Override public boolean isEmpty() {
                return ColumnTileEntityMap.this.isEmpty();
            }

            @Override public boolean contains(Object o) {
                return ColumnTileEntityMap.this.containsKey(o);
            }

            @Nonnull @Override public Iterator<BlockPos> iterator() {
                return new CubeIterator<BlockPos>() {
                    @Override public BlockPos next() { return nextEntry().getKey(); }
                };
            }

            @Override public boolean remove(Object o) {
                return ColumnTileEntityMap.this.remove(o) != null;
            }

            @Override public void clear() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override public Collection<TileEntity> values() {
        return new AbstractCollection<TileEntity>() {

            @Override public int size() {
                return ColumnTileEntityMap.this.size();
            }

            @Override public boolean isEmpty() {
                return ColumnTileEntityMap.this.isEmpty();

            }

            @Override public boolean contains(Object o) {
                return ColumnTileEntityMap.this.containsValue(o);
            }

            @Override public Iterator<TileEntity> iterator() {
                return new CubeIterator<TileEntity>() {
                    @Override public TileEntity next() { return nextEntry().getValue(); }
                };
            }

            @Override public boolean add(TileEntity tileEntity) {
                return ColumnTileEntityMap.this.put(tileEntity.getPos(), tileEntity) == null;
            }

            @Override public boolean remove(Object o) {
                if (!(o instanceof TileEntity)) {
                    return false;
                }
                TileEntity te = (TileEntity) o;
                return ColumnTileEntityMap.this.remove(te.getPos(), te);
            }

            @Override public void clear() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override public Set<Entry<BlockPos, TileEntity>> entrySet() {
        return new AbstractSet<Entry<BlockPos, TileEntity>>() {
            @Override public int size() {
                return ColumnTileEntityMap.this.size();
            }

            @Override public boolean isEmpty() {
                return ColumnTileEntityMap.this.isEmpty();
            }

            @Override public boolean contains(Object o) {
                if (!(o instanceof Entry)) return false;
                Entry<?, ?> entry = (Entry<?, ?>) o;
                Object key = entry.getKey();
                return key instanceof BlockPos && tileEntities((BlockPos) key).entrySet().contains(o);
            }

            @Nonnull @Override public Iterator<Entry<BlockPos, TileEntity>> iterator() {
                return new CubeIterator<Entry<BlockPos, TileEntity>>() {
                    @Override public Entry<BlockPos, TileEntity> next() { return nextEntry(); }
                };
            }

            @Override public boolean remove(Object o) {
                if (!(o instanceof Entry)) return false;
                Entry<?, ?> entry = (Entry<?, ?>) o;
                if (!(entry.getKey() instanceof BlockPos)) return false;
                ICube cube = column.getLoadedCube(Coords.blockToCube(((BlockPos) entry.getKey()).getY()));
                return cube != null && cube.getTileEntityMap().entrySet().remove(o);
            }

            @Override public void clear() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private abstract class CubeIterator<T> implements Iterator<T> {
        private final Iterator<? extends ICube> cubes = column.getLoadedCubes().iterator();
        private Iterator<Entry<BlockPos, TileEntity>> current = Collections.emptyIterator();
        private Entry<BlockPos, TileEntity> next;

        @Override public final boolean hasNext() {
            if (next != null) return true;
            while (!current.hasNext() && cubes.hasNext()) {
                Map<BlockPos, TileEntity> entries = cubes.next().getTileEntityMap();
                if (!entries.isEmpty()) current = entries.entrySet().iterator();
            }
            if (!current.hasNext()) return false;
            next = current.next();
            return true;
        }

        protected final Entry<BlockPos, TileEntity> nextEntry() {
            if (!hasNext()) throw new NoSuchElementException();
            Entry<BlockPos, TileEntity> result = next;
            next = null;
            return result;
        }
    }
}
