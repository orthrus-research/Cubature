package io.github.opencubicchunks.cubicchunks.api.world;

import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Takes part in a cubic world's random-tick pass, on the server thread. The pass visits each cube
 * players or tickets keep tickable once per world tick. Cubes whose blocks never tick randomly are
 * not visited. For each visited cube it draws {@code randomTickSpeed} positions, as vanilla draws
 * them for a chunk section. Listeners don't choose the cubes, the order or the positions; the owner
 * may change those. Register with {@link ICubicWorldServer#addRandomTickListener}.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public interface ICubicRandomTickListener {
    /**
     * Before the cube's draws. Every listener is asked; if any returns false the cube gets no draws
     * this tick.
     */
    boolean admitCube(World world, ICube cube);

    /**
     * For each drawn position of an admitted cube, whether its block ticks randomly or not. Every
     * listener sees the draw; if any returns false that block's random tick is skipped.
     */
    boolean onDraw(World world, ICube cube, BlockPos pos, IBlockState state);
}
