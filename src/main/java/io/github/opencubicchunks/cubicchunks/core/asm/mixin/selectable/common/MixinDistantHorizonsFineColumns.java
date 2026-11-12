/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.asm.mixin.selectable.common;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.FineColumnGuard;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DH's same-detail merge reads each input column's generation step to decide whether it replaces the stored column.
 * Where FineColumnGuard keeps the stored column, the input's step reads as EMPTY, which DH's rule never applies, so
 * the column is skipped as DH skips an empty input. Only the input's step is changed; the stored step and the
 * compression mode read in the same loop pass through.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2", remap = false)
public abstract class MixinDistantHorizonsFineColumns {
    @Shadow(remap = false) @Final public ByteArrayList columnGenerationSteps;
    @Shadow(remap = false) @Final public LongArrayList[] dataPoints;
    @Shadow(remap = false) @Final private long pos;

    @WrapOperation(method = "updateFromSameDetailLevel", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/bytes/ByteArrayList;getByte(I)B"), require = 3, allow = 3, remap = false)
    private byte cubicchunks$keepFineColumns(ByteArrayList steps, int index, Operation<Byte> original,
            @Local(argsOnly = true) FullDataSourceV2 input) {
        byte step = original.call(steps, index);
        if (!CubicChunksConfig.distantHorizonsKeepFineColumns || steps != input.columnGenerationSteps) return step;
        LongArrayList stored = dataPoints[index];
        boolean keep = FineColumnGuard.keeps(FineColumnGuard.answer(input.applyToChildren, input.applyToParent),
                DhSectionPos.getDetailLevel(pos), columnGenerationSteps.getByte(index), stored != null && !stored.isEmpty(), step);
        return keep ? 0 : step;
    }
}
