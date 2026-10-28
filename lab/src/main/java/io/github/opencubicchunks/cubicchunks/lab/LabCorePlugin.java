package io.github.opencubicchunks.cubicchunks.lab;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;

/** Optional instrumentation lives in this development JAR, outside the production fork. */
@IFMLLoadingPlugin.Name("CubicChunks lab")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions({"io.github.opencubicchunks.cubicchunks.lab.LabCorePlugin", "io.github.opencubicchunks.cubicchunks.lab.LabCompatibilityTransformer"})
public final class LabCorePlugin implements IFMLLoadingPlugin {
    public LabCorePlugin() {
        MixinBootstrap.init(); Mixins.addConfiguration("cubiclab.mixins.json");
        if (Boolean.getBoolean("cubiclab.storageDiagnostics")) Mixins.addConfiguration("cubiclab.storage.mixins.json");
        if (Boolean.getBoolean("cubiclab.cleanroom070")) Mixins.addConfiguration("cubiclab.compat070.mixins.json");
        boolean renderDiagnostics = Boolean.getBoolean("cubiclab.renderDiagnostics");
        boolean delivery = Boolean.getBoolean("cubiclab.deliveryTrace");
        if (delivery) Mixins.addConfiguration("cubiclab.delivery.mixins.json");
        boolean renderReady = Boolean.getBoolean("cubiclab.fullViewRenderReady") || delivery;
        if (renderDiagnostics || renderReady) Mixins.addConfiguration("cubiclab.render-task.mixins.json");
        if (renderReady) Mixins.addConfiguration("cubiclab.render-ready.mixins.json");
        if (renderDiagnostics || "orphan".equals(System.getProperty("cubiclab.uploadStrategy"))) {
            Mixins.addConfiguration("cubiclab.upload.mixins.json");
        }
        if (renderDiagnostics) {
            Mixins.addConfiguration("cubiclab.render.mixins.json");
            if (getClass().getClassLoader().getResource("com/cleanroommc/client/sdl/Pump.class") != null) {
                Mixins.addConfiguration("cubiclab.render-sdl.mixins.json");
            }
        }
    }
    public String[] getASMTransformerClass() { return new String[] {"io.github.opencubicchunks.cubicchunks.lab.LabCompatibilityTransformer"}; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String, Object> data) { }
    public String getAccessTransformerClass() { return null; }
}
