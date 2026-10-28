package net.minecraft.block.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraftforge.common.property.ExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;

/**
 * Independently authored lab compatibility class for the Forge-added builder
 * missing from the published 0.7.0 universal JAR and its binary patch entries.
 * It delegates state construction to the runtime's existing container types.
 */
public final class BlockStateContainer$Builder {
    private final Block block;
    private final List<IProperty<?>> listed = new ArrayList<>();
    private final List<IUnlistedProperty<?>> unlisted = new ArrayList<>();
    public BlockStateContainer$Builder(Block block) { this.block = block; }
    public BlockStateContainer$Builder add(IProperty<?>... properties) { Collections.addAll(listed, properties); return this; }
    public BlockStateContainer$Builder add(IUnlistedProperty<?>... properties) { Collections.addAll(unlisted, properties); return this; }
    public BlockStateContainer build() {
        IProperty<?>[] properties = listed.toArray(new IProperty<?>[0]);
        return unlisted.isEmpty() ? new BlockStateContainer(block, properties)
            : new ExtendedBlockState(block, properties, unlisted.toArray(new IUnlistedProperty<?>[0]));
    }
}
