package io.github.opencubicchunks.cubicchunks.core.server;

import io.github.opencubicchunks.cubicchunks.api.world.ICubeProviderServer.Requirement;
import io.github.opencubicchunks.cubicchunks.api.world.CubeDataEvent;
import io.github.opencubicchunks.cubicchunks.api.world.CubeEvent;
import io.github.opencubicchunks.cubicchunks.api.world.ICube;
import io.github.opencubicchunks.cubicchunks.api.world.IColumn;
import io.github.opencubicchunks.cubicchunks.core.server.chunkio.async.forge.AsyncWorldIOExecutor;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import net.minecraft.block.Block;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Isolated native contracts; explicit flush and event reentrancy make this a correctness workload. */
public final class LabGenerationProbe implements AutoCloseable {
    private static final int STEAL_X = 640, STEAL_Y = 5, STEAL_Z = 640;
    private final CubeProviderServer provider;
    private Chunk outer;
    private int nestedEvents, checks, callbacks;
    private boolean armed;
    private boolean stealArmed, stealValidated;
    private int stealParentEvents, stealCubeEvents, stealCubeInitializations, survivorCallbacks, cancelledCallbacks, reentrantCallbacks;
    private long savedFingerprint;
    private Thread owner;
    private Cube synchronousCube, asynchronousCube, reentrantCube;
    private Consumer<Cube> survivorSubscription;

    public LabGenerationProbe(CubeProviderServer provider) { this.provider = provider; }

    public void prepare() throws IOException {
        owner = Thread.currentThread();
        for (int x = 600; x <= 601; x++) {
            Cube cube = provider.getCubeNow(x, 5, 600, Requirement.GENERATE);
            require(cube != null && cube.getX() == x, "setup generation failed");
            provider.getCubeIO().saveCube(cube);
            provider.getCubeIO().saveColumn((Chunk) cube.getColumn());
        }
        Cube saved = provider.getCubeNow(STEAL_X, STEAL_Y, STEAL_Z, Requirement.LIGHT);
        checkCompleteCube(saved);
        savedFingerprint = fingerprint(saved);
        provider.getCubeIO().saveCube(saved);
        provider.getCubeIO().saveColumn((Chunk) saved.getColumn());
        provider.getCubeIO().flush();
        for (Iterator<Cube> it = provider.cubesIterator(); it.hasNext();) {
            Cube cube = it.next();
            if (fixtureColumn(cube.getX(), cube.getZ())) {
                require(provider.tryUnloadCube(cube), "setup cube unexpectedly retained");
                it.remove();
            }
        }
        for (Iterator<Chunk> it = provider.columnsIterator(); it.hasNext();) {
            Chunk column = it.next();
            if (fixtureColumn(column.x, column.z)) {
                require(provider.tryUnloadColumn(column), "setup column unexpectedly retained");
                it.remove();
            }
        }
        provider.getCubeIO().flush();
        require(provider.getLoadedColumn(STEAL_X, STEAL_Z) == null
                && provider.getLoadedCube(STEAL_X, STEAL_Y, STEAL_Z) == null, "steal cohort was not unloaded");
        require(provider.getCubeIO().columnExists(STEAL_X, STEAL_Z)
                && provider.getCubeIO().cubeExists(STEAL_X, STEAL_Y, STEAL_Z), "steal cohort was not persisted");
        armed = true;
        MinecraftForge.EVENT_BUS.register(this);
        Chunk restored = provider.getColumn(600, 600, Requirement.LOAD);
        require(restored != null && restored == provider.getLoadedColumn(600, 600), "outer column not published");
        require(nestedEvents == 2 && outer != null, "nested disk events not observed");
        armed = false;
        provider.asyncGetCube(602, 5, 600, Requirement.LOAD, cube -> {
            require(cube == null, "absent load returned a cube");
            require(provider.getLoadedColumn(602, 600) == null, "load-only request generated its parent column");
            callbacks++;
        });
        provider.asyncGetCube(603, 5, 600, Requirement.GENERATE, cube -> {
            require(cube != null && cube.getX() == 603 && cube.getY() == 5 && cube.getZ() == 600,
                    "explicit async generation was lost");
            callbacks++;
        });
        stealArmed = true;
        Consumer<Cube> cancelled = cube -> {
            cancelledCallbacks++;
            throw new IllegalStateException("cancelled original subscription was delivered");
        };
        survivorSubscription = cube -> {
            require(Thread.currentThread() == owner, "cube callback ran outside the owner thread");
            checkCompleteCube(cube);
            require(fingerprint(cube) == savedFingerprint, "survivor cube differs from persisted blocks");
            asynchronousCube = cube;
            survivorCallbacks++;
            callbacks++;
        };
        provider.asyncGetCube(STEAL_X, STEAL_Y, STEAL_Z, Requirement.LOAD, cancelled);
        provider.asyncGetCube(STEAL_X, STEAL_Y, STEAL_Z, Requirement.LOAD, survivorSubscription);
        require(!AsyncWorldIOExecutor.canDropColumn(provider.world, STEAL_X, STEAL_Z), "waiting cube did not pin its parent");
        AsyncWorldIOExecutor.dropQueuedCubeLoad(provider.world, STEAL_X, STEAL_Y, STEAL_Z, cancelled);
        require(!AsyncWorldIOExecutor.canDropColumn(provider.world, STEAL_X, STEAL_Z), "cancelling one subscription released its survivor's pin");
    }

    @SubscribeEvent public void onLoad(ChunkDataEvent.Load event) {
        Chunk column = event.getChunk();
        if (column.getWorld() != provider.world) return;
        if (stealArmed && column.x == STEAL_X && column.z == STEAL_Z) {
            stealArmed = false;
            stealParentEvents++;
            require(Thread.currentThread() == owner, "column publication ran outside the owner thread");
            require(provider.getLoadedColumn(STEAL_X, STEAL_Z) == column, "steal event cannot resolve its parent column");
            // This event precedes provider callbacks which resolve the queued cube's dependency.
            // The synchronous request must claim a task not yet present in its executor queue.
            synchronousCube = provider.getCubeNow(STEAL_X, STEAL_Y, STEAL_Z, Requirement.LOAD);
            checkCompleteCube(synchronousCube);
            require(synchronousCube == asynchronousCube && survivorCallbacks == 1,
                    "synchronous steal did not notify its surviving asynchronous consumer exactly once");
            require(synchronousCube == reentrantCube && reentrantCallbacks == 1,
                    "native-event reentry did not preserve exact cube/callback identity");
            require(provider.getLoadedCube(STEAL_X, STEAL_Y, STEAL_Z) == synchronousCube,
                    "synchronous steal did not register its cube");
            int restoredEntries = 0;
            for (ICube loaded : ((IColumn) column).getLoadedCubes()) {
                if (loaded.getY() == STEAL_Y) {
                    require(loaded == synchronousCube, "parent column retained a different restored cube");
                    restoredEntries++;
                }
            }
            require(restoredEntries == 1 && synchronousCube.isCubeLoaded(),
                    "parent publication did not retain exactly one initialized restored cube");
            require(fingerprint(synchronousCube) == savedFingerprint, "stolen cube differs from persisted blocks");
            require(AsyncWorldIOExecutor.canDropColumn(provider.world, STEAL_X, STEAL_Z), "completed steal retained its IO parent pin");
            stealValidated = true;
        }
        if (!armed || column.z != 600 || column.x < 600 || column.x > 601) return;
        nestedEvents++;
        require(provider.getLoadedColumn(column.x, column.z) == column, "event cannot resolve its own column");
        require(provider.getLoadedColumn(-999, 777) == null, "unrelated coordinates alias the loading column");
        if (column.x == 600) {
            outer = column;
            Chunk inner = provider.getColumn(601, 600, Requirement.LOAD);
            require(inner != null && inner.x == 601 && inner.z == 600, "nested load returned the outer column");
            require(provider.getLoadedColumn(600, 600) == outer, "nested load erased its parent's context");
        } else {
            require(provider.getLoadedColumn(600, 600) == outer, "nested event cannot resolve its parent");
        }
    }

    @SubscribeEvent public void onCubeLoad(CubeDataEvent.Load event) {
        if (event.getCube().getWorld() == provider.world && event.getCube().getX() == STEAL_X
                && event.getCube().getY() == STEAL_Y && event.getCube().getZ() == STEAL_Z) {
            require(Thread.currentThread() == owner, "cube restore ran outside the owner thread");
            require(++stealCubeEvents == 1, "steal cohort was restored more than once");
            Cube restoring = (Cube) event.getCube();
            require(!AsyncWorldIOExecutor.canDropColumn(provider.world, STEAL_X, STEAL_Z), "native publication lost its parent pin");
            // Enqueue before the synchronous reentry can register the cube in the provider.
            provider.asyncGetCube(STEAL_X, STEAL_Y, STEAL_Z, Requirement.LOAD, cube -> {
                require(Thread.currentThread() == owner && cube == restoring, "native-event async reentry changed cube/owner");
                checkCompleteCube(cube); reentrantCube = cube; reentrantCallbacks++;
            });
            require(reentrantCallbacks == 0, "native-event async callback bypassed its original publication");
            Cube same = provider.getCubeNow(STEAL_X, STEAL_Y, STEAL_Z, Requirement.LOAD);
            require(same == restoring, "native-event synchronous reentry decoded a second cube");
            require(reentrantCallbacks == 0, "native-event synchronous reentry dispatched callbacks twice or early");
            checkCompleteCube(same);
        }
    }

    @SubscribeEvent public void onCubeInitialized(CubeEvent.Load event) {
        if (event.getWorld() == provider.world && event.getCube().getX() == STEAL_X
                && event.getCube().getY() == STEAL_Y && event.getCube().getZ() == STEAL_Z)
            require(++stealCubeInitializations == 1, "reentrant restore initialized the cube more than once");
    }

    private static boolean fixtureColumn(int x, int z) {
        return z == 600 && x >= 600 && x <= 601 || x == STEAL_X && z == STEAL_Z;
    }

    private void checkCompleteCube(Cube cube) {
        require(cube != null && cube.getX() == STEAL_X && cube.getY() == STEAL_Y && cube.getZ() == STEAL_Z,
                "steal cohort returned absent or unrelated cube");
        require(cube.isFullyPopulated() && cube.isInitialLightingDone() && cube.isSurfaceTracked(),
                "steal cohort did not retain complete population/light/surface state");
    }

    private static long fingerprint(Cube cube) {
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            hash ^= Block.getStateId(cube.getBlockState(x, y, z));
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
        checks++;
    }

    public boolean complete() {
        if (callbacks != 3 || !stealValidated) return false;
        require(stealParentEvents == 1 && stealCubeEvents == 1 && stealCubeInitializations == 1
                && survivorCallbacks == 1 && cancelledCallbacks == 0 && reentrantCallbacks == 1,
                "steal/cancellation event or callback counts differ");
        return true;
    }
    public int operations() { return 6; }
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checks", checks); result.put("nestedLoadEvents", nestedEvents); result.put("asyncCallbacks", callbacks);
        result.put("stealParentEvents", stealParentEvents); result.put("stealCubeLoadEvents", stealCubeEvents);
        result.put("survivorCallbacks", survivorCallbacks); result.put("cancelledCallbacks", cancelledCallbacks);
        result.put("reentrantCallbacks", reentrantCallbacks); result.put("stealCubeInitializations", stealCubeInitializations);
        result.put("synchronousStealValidated", stealValidated);
        result.put("savedBlockFingerprint", Long.toUnsignedString(savedFingerprint));
        result.put("stealCube", java.util.Arrays.asList(STEAL_X, STEAL_Y, STEAL_Z));
        result.put("scope", "actual provider, nested saved-column events, absent LOAD, explicit GENERATE, "
                + "saved fully ready cube stolen synchronously during parent publication, native cube event reenters sync/async same-coordinate loads, "
                + "one initialization/event and one initialized parent-column entry with exact identity/callback/pin checks, "
                + "one original subscription cancelled; not a timing benchmark");
        return result;
    }
    @Override public void close() {
        armed = stealArmed = false;
        MinecraftForge.EVENT_BUS.unregister(this);
        if (survivorSubscription != null && survivorCallbacks == 0)
            AsyncWorldIOExecutor.dropQueuedCubeLoad(provider.world, STEAL_X, STEAL_Y, STEAL_Z, survivorSubscription);
    }
}
