package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.core.asm.mixin.ICubicWorldInternal;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.ICubeIO;
import io.github.opencubicchunks.cubicchunks.core.util.ticket.ITicket;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.chunk.NibbleArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owner-thread save-order regression. Autosave cases wait for ordinary provider ticks without a forced-save rescue. */
public final class LabLightingSaveProbe implements AutoCloseable {
    private enum Mode { CLEAN_FIRST, DIRTY_LATER, AUTOSAVE, DIRECT }

    private final CubeProviderServer provider;
    private final ICubeIO io;
    private final Cube first;
    private final Cube later;
    private final BlockPos pos;
    private final IBlockState original;
    private final ITicket hold = () -> false;
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final List<Integer> pendingByTick = new ArrayList<>();
    private final List<Integer> discoveryByTick = new ArrayList<>();
    private int caseIndex;
    private boolean waiting;
    private boolean closed;

    public LabLightingSaveProbe(CubeProviderServer provider) {
        this.provider = provider;
        this.io = provider.getCubeIO();
        Iterator<Cube> cubes = provider.cubesIterator();
        require(cubes.hasNext(), "Lighting save fixture requires loaded cubes");
        first = cubes.next();
        require(cubes.hasNext(), "Lighting save fixture requires a later cube");
        later = cubes.next();
        pos = new BlockPos(first.getX() * 16 + 8, first.getY() * 16 + 8, first.getZ() * 16 + 8);
        original = first.getBlockState(pos);
        require(!original.getBlock().hasTileEntity(original), "Refusing to perturb a tile entity");
        first.getTickets().add(hold);
        later.getTickets().add(hold);
    }

    public boolean tick() throws IOException {
        require(!closed, "Lighting save fixture is closed");
        EnumSkyBlock light = caseIndex < Mode.values().length ? EnumSkyBlock.BLOCK : EnumSkyBlock.SKY;
        Mode mode = Mode.values()[caseIndex % Mode.values().length];
        int expected = light == EnumSkyBlock.BLOCK ? 15 : 0;
        if (waiting) {
            pendingByTick.add(provider.getPendingAutosaveCount());
            discoveryByTick.add(provider.getPendingAutosaveDiscoveryCount());
            require(pendingByTick.size() <= 1200, "Paced autosave did not complete within 1200 provider ticks");
            if (first.needsSaving() || provider.getPendingAutosaveCount() != 0
                    || provider.getPendingAutosaveDiscoveryCount() != 0) {
                return false;
            }
            // No full save here: only snapshots issued by the provider's real continuation can satisfy this check.
            verify(light, mode, expected);
            waiting = false;
        } else {
            first.setBlockState(pos, Blocks.GLOWSTONE.getDefaultState());
            queue(EnumSkyBlock.BLOCK);
            queue(EnumSkyBlock.SKY);
            drain();
            provider.saveChunks(true);
            io.flush();
            int stale = 15 - expected;
            first.setLightFor(light, pos, stale);
            io.saveCube(first);
            io.flush();
            require(savedLight(light) == stale, "Fixture did not establish stale saved " + light);
            require(!first.needsSaving(), "Fixture target must be clean before queuing light");
            queue(light);
            require(!first.needsSaving(), "Queuing light dirtied the target before the scan");
            if (mode == Mode.DIRTY_LATER) {
                later.markDirty();
            }
            if (mode == Mode.AUTOSAVE) {
                pendingByTick.clear();
                discoveryByTick.clear();
                provider.beginPeriodicAutosave();
                require(first.needsSaving(), "Autosave entry did not complete pending " + light);
                require(provider.getPendingAutosaveCount() + provider.getPendingAutosaveDiscoveryCount() > 0,
                        "Autosave did not schedule light-only work");
                waiting = true;
                return false;
            }
            if (mode == Mode.DIRECT) {
                io.saveCube(first);
            } else {
                provider.saveChunks(true);
            }
            verify(light, mode, expected);
        }
        caseIndex++;
        return caseIndex == Mode.values().length * 2;
    }

    private void verify(EnumSkyBlock light, Mode mode, int expected) throws IOException {
        io.flush();
        int saved = savedLight(light);
        require(saved == expected, mode + " left stale saved " + light + ": " + saved + ", expected " + expected);
        require(!first.needsSaving(), mode + " left repaired light unsaved");
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("light", light.name());
        check.put("mode", mode.name());
        check.put("expected", expected);
        check.put("saved", saved);
        if (mode == Mode.AUTOSAVE) {
            check.put("pendingByTick", new ArrayList<>(pendingByTick));
            check.put("discoveryByTick", new ArrayList<>(discoveryByTick));
            check.put("forcedSaveRescue", false);
        }
        checks.add(check);
    }

    private void queue(EnumSkyBlock light) {
        ((ICubicWorldInternal) first.getWorld()).getLightingManager().checkLightFor(light, pos);
    }

    private void drain() {
        ((ICubicWorldInternal) first.getWorld()).getLightingManager().processUpdates();
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", 2);
        result.put("cube", Arrays.asList(first.getX(), first.getY(), first.getZ()));
        result.put("block", Arrays.asList(pos.getX(), pos.getY(), pos.getZ()));
        result.put("incrementalMaintenance", provider.usesIncrementalMaintenance());
        result.put("checks", checks);
        result.put("passed", checks.size() == Mode.values().length * 2);
        result.put("scope", "Queued light at save entry, actual snapshots and storage readback; autosave uses real ticks. Not concurrent save-listener mutation or latency qualification.");
        return result;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            first.setBlockState(pos, original);
            queue(EnumSkyBlock.BLOCK);
            queue(EnumSkyBlock.SKY);
            drain();
            first.markDirty();
            provider.saveChunks(true);
            io.flush();
        } finally {
            first.getTickets().remove(hold);
            later.getTickets().remove(hold);
        }
    }

    private int savedLight(EnumSkyBlock light) throws IOException {
        NBTTagCompound nbt = io.loadCubeNbt(first.getColumn(), first.getY()).getNbt();
        require(nbt != null, "Fixture cube missing from storage");
        byte[] values = nbt.getCompoundTag("Level").getTagList("Sections", 10)
                .getCompoundTagAt(0).getByteArray(light == EnumSkyBlock.BLOCK ? "BlockLight" : "SkyLight");
        require(values.length == 2048, "Fixture light array missing");
        return new NibbleArray(values).get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
