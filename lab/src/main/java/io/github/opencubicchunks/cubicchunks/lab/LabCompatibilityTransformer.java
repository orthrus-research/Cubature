package io.github.opencubicchunks.cubicchunks.lab;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import net.minecraft.launchwrapper.IClassTransformer;

/** Supplies only our missing-class compatibility implementation; existing runtime classes win. */
public final class LabCompatibilityTransformer implements IClassTransformer {
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes != null || !"net.minecraft.block.state.BlockStateContainer$Builder".equals(transformedName)) return bytes;
        try (InputStream stream = LabCompatibilityTransformer.class.getResourceAsStream("/net/minecraft/block/state/BlockStateContainer$Builder.class")) {
            if (stream == null) return bytes; // Forge artifact carries no Cleanroom 0.7 compatibility class.
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(); byte[] block = new byte[4096];
            int count; while ((count = stream.read(block)) >= 0) buffer.write(block, 0, count);
            System.out.println("CUBICLAB_COMPAT supplied missing BlockStateContainer.Builder");
            return buffer.toByteArray();
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot load lab compatibility class", failure); }
    }
}
