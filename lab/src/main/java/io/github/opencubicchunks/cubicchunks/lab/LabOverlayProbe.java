package io.github.opencubicchunks.cubicchunks.lab;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** Read-only observation of the vanilla title overlay; never edits its fields. */
final class LabOverlayProbe {
    private Field vanillaTitle;
    boolean active;
    String failure;
    LabOverlayProbe(){
        try{vanillaTitle=ReflectionHelper.findField(GuiIngame.class,"titlesTimer","field_175195_w");}
        catch(RuntimeException error){failure="Vanilla title visibility unavailable: "+error;}
    }
    void observe(Minecraft mc){
        if(failure!=null)return;
        try{active=vanillaTitle.getInt(mc.ingameGUI)>0;}
        catch(ReflectiveOperationException|RuntimeException error){failure="Overlay visibility read failed: "+error;}
    }
}
