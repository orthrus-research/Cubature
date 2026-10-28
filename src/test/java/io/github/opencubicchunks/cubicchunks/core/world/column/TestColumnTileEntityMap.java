package io.github.opencubicchunks.cubicchunks.core.world.column;

import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.Assert.*;

public class TestColumnTileEntityMap {
    @BeforeClass public static void bootstrapReturnTypesForJava8Proxies() {
        // Java 8 proxy initialization also initializes referenced return types such as Biome.
        Bootstrap.register();
    }
    public static class Fixture {
        public final Map<Integer, ICube> cubes = new LinkedHashMap<>();
        public final Map<Integer, Map<BlockPos, TileEntity>> maps = new LinkedHashMap<>();
        public int loads;
        public final IColumn column = (IColumn) Proxy.newProxyInstance(IColumn.class.getClassLoader(), new Class<?>[] {IColumn.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getLoadedCubes": return cubes.values();
                case "getLoadedCube": return cubes.get((Integer) args[0]);
                case "getCube": loads++; return cube((Integer) args[0]);
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
        public final ColumnTileEntityMap view = new ColumnTileEntityMap(column);
        public ICube cube(int y) {
            return cubes.computeIfAbsent(y, key -> {
                Map<BlockPos, TileEntity> entries = new LinkedHashMap<>(); maps.put(key, entries);
                return (ICube) Proxy.newProxyInstance(ICube.class.getClassLoader(), new Class<?>[] {ICube.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getTileEntityMap")) return entries;
                    throw new UnsupportedOperationException(method.getName());
                });
            });
        }
        public TileEntity tile(int y) {
            TileEntity tile = new TileEntity() {}; tile.setPos(new BlockPos(8, y, 8));
            cube(Coords.blockToCube(y)); maps.get(Coords.blockToCube(y)).put(tile.getPos(), tile);
            return tile;
        }
    }

    @Test public void allViewsSkipEmptyCubesAndKeepBackingOrder() {
        Fixture f = new Fixture(); f.cube(-20); TileEntity low = f.tile(-120); f.cube(0); TileEntity high = f.tile(376); f.cube(30);
        assertEquals(2, f.view.size()); assertFalse(f.view.isEmpty());
        assertEquals(Arrays.asList(low.getPos(), high.getPos()), new ArrayList<>(f.view.keySet()));
        assertEquals(Arrays.asList(low, high), new ArrayList<>(f.view.values()));
        Map<BlockPos, TileEntity> expected = new LinkedHashMap<>(); expected.put(low.getPos(), low); expected.put(high.getPos(), high);
        assertEquals(expected.entrySet(), new HashSet<>(f.view.entrySet()));
    }

    @Test public void iteratorsHandleRepeatedHasNextDirectNextAndExhaustion() {
        Fixture f = new Fixture(); TileEntity tile = f.tile(-120); f.cube(1);
        for (Collection<?> view : Arrays.asList(f.view.keySet(), f.view.values(), f.view.entrySet())) {
            Iterator<?> it = view.iterator(); assertTrue(it.hasNext()); assertTrue(it.hasNext());
            assertNotNull(it.next()); assertFalse(it.hasNext()); assertFalse(it.hasNext());
            try { it.next(); fail(); } catch (NoSuchElementException expected) { }
            try { it.remove(); fail(); } catch (UnsupportedOperationException expected) { }
        }
        assertSame(tile, f.view.values().iterator().next());
        Fixture empty = new Fixture(); empty.cube(0);
        assertTrue(empty.view.isEmpty()); assertEquals(0, empty.view.size());
        assertFalse(empty.view.entrySet().iterator().hasNext());
    }

    @Test public void entryMembershipAndRemovalRequireBothKeyAndValue() {
        Fixture f = new Fixture(); TileEntity tile = f.tile(376), other = new TileEntity() {};
        Map.Entry<BlockPos, TileEntity> matching = new AbstractMap.SimpleImmutableEntry<>(tile.getPos(), tile);
        Map.Entry<BlockPos, TileEntity> wrong = new AbstractMap.SimpleImmutableEntry<>(tile.getPos(), other);
        assertTrue(f.view.entrySet().contains(matching)); assertFalse(f.view.entrySet().contains(wrong));
        assertFalse(f.view.entrySet().contains(tile.getPos())); assertFalse(f.view.entrySet().remove(tile.getPos()));
        assertFalse(f.view.entrySet().remove(wrong)); assertTrue(f.view.entrySet().remove(matching));
        assertTrue(f.view.isEmpty()); assertFalse(f.view.entrySet().contains(matching));
    }

    @Test public void viewsRemainLiveAndEntriesCanReplaceValues() {
        Fixture f = new Fixture(); Set<BlockPos> keys = f.view.keySet(); Collection<TileEntity> values = f.view.values();
        TileEntity tile = f.tile(-120); assertTrue(keys.contains(tile.getPos())); assertTrue(values.contains(tile));
        TileEntity replacement = new TileEntity() {}; replacement.setPos(tile.getPos());
        assertSame(tile, f.view.entrySet().iterator().next().setValue(replacement));
        assertSame(replacement, f.view.get(tile.getPos())); assertTrue(values.remove(replacement)); assertTrue(keys.isEmpty());
        assertTrue(values.add(tile)); assertTrue(keys.remove(tile.getPos())); assertTrue(values.isEmpty());
    }

    @Test public void lookupLoadsTheCubeButAbsentValueMembershipDoesNot() {
        Fixture f = new Fixture(); BlockPos absent = new BlockPos(8, -120, 8);
        assertNull(f.view.get(absent)); assertEquals(1, f.loads); assertTrue(f.cubes.containsKey(-8));
        TileEntity elsewhere = new TileEntity() {}; elsewhere.setPos(new BlockPos(8, 376, 8));
        assertFalse(f.view.containsValue(elsewhere)); assertEquals(1, f.loads); assertFalse(f.cubes.containsKey(23));
        assertFalse(f.view.entrySet().remove(new AbstractMap.SimpleImmutableEntry<>(elsewhere.getPos(), elsewhere)));
        assertEquals(1, f.loads); assertFalse(f.cubes.containsKey(23));
    }

    @Test public void clearRemainsUnsupportedOnMapAndAllViews() {
        Fixture f = new Fixture(); f.tile(8);
        for (Runnable clear : Arrays.<Runnable>asList(f.view::clear, f.view.keySet()::clear, f.view.values()::clear, f.view.entrySet()::clear)) {
            try { clear.run(); fail(); } catch (UnsupportedOperationException expected) { }
        }
        assertEquals(1, f.view.size());
    }
}
