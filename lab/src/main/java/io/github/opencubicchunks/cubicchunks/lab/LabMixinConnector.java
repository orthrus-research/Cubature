package io.github.opencubicchunks.cubicchunks.lab;

import com.cleanroommc.cleanmix.ConditionalMixin;
import org.spongepowered.asm.mixin.connect.IMixinConnector;

/**
 * Registers the lab's Mixin configurations; CleanMix calls it from the JAR's {@code MixinConnector} attribute.
 * Optional instrumentation lives in this development JAR, outside the production fork, and each trace is
 * selected by the system property its harness passes.
 */
public final class LabMixinConnector implements IMixinConnector {

    @Override
    public void connect() {
        boolean renderDiagnostics = Boolean.getBoolean("cubiclab.renderDiagnostics");
        boolean delivery = Boolean.getBoolean("cubiclab.deliveryTrace");
        boolean renderReady = Boolean.getBoolean("cubiclab.fullViewRenderReady") || delivery;
        boolean orphanUploads = "orphan".equals(System.getProperty("cubiclab.uploadStrategy"));
        // Checked as a resource so no client class loads this early.
        boolean sdl = getClass().getClassLoader().getResource("com/cleanroommc/client/sdl/Pump.class") != null;

        ConditionalMixin.of("cubiclab.mixins.json").register();
        ConditionalMixin.of(Boolean.getBoolean("cubiclab.storageDiagnostics"), "cubiclab.storage.mixins.json").register();
        ConditionalMixin.of(delivery, "cubiclab.delivery.mixins.json").register();
        ConditionalMixin.of(!System.getProperty("cubiclab.lodTrace", "").isEmpty(), "cubiclab.lod.mixins.json").register();
        ConditionalMixin.of(renderDiagnostics || renderReady, "cubiclab.render-task.mixins.json").register();
        ConditionalMixin.of(renderReady, "cubiclab.render-ready.mixins.json").register();
        ConditionalMixin.of(renderDiagnostics || orphanUploads, "cubiclab.upload.mixins.json").register();
        ConditionalMixin.of(renderDiagnostics, "cubiclab.render.mixins.json").register();
        ConditionalMixin.of(renderDiagnostics && sdl, "cubiclab.render-sdl.mixins.json").register();
    }
}
