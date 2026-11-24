package io.github.opencubicchunks.cubicchunks.lab;

import io.github.opencubicchunks.cubicchunks.api.client.CubeSource;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.io.File;
import java.util.Arrays;

/**
 * The tour client, for a Workbench tour session (lab.run kind=tour) of Cubature's lab launch: with
 * {@code -Dcubiclab.tour=true} it takes a screenshot of each view the server moves the player to (when, is
 * {@link LabTourViews}) and prints {@link #CAPTURED} with the file's name, which the launch's tour rules wait for. Off
 * by default, when nothing of it is registered, so the lab's own scenarios and their measurements are unchanged.
 * <p>
 * The screenshot is the world as drawn, taken after the world and before the hand and the HUD
 * ({@code RenderWorldLastEvent}), into the game folder's {@code screenshots/}, which lab.run files by view. Optional
 * settings: {@code cubiclab.tourStillTicks} (default 120), {@code cubiclab.tourQuietTicks} (20, the quiet ticks the
 * cube source must report), {@code cubiclab.tourRenderFrames} (10) and {@code cubiclab.tourLimitTicks} (1200).
 */
public final class LabTourClient {
    public static final String PROPERTY = "cubiclab.tour";
    public static final String CAPTURED = "CUBICLAB_TOUR_CAPTURED";
    static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

    private final LabTourViews views = new LabTourViews(Integer.getInteger("cubiclab.tourStillTicks", 120),
            Integer.getInteger("cubiclab.tourLimitTicks", 1200), Integer.getInteger("cubiclab.tourRenderFrames", 10));
    private final int quietTicks = Math.max(1, Integer.getInteger("cubiclab.tourQuietTicks", 20));
    private LabTourViews.View due;
    private int[] dueBox;
    private int dueQuiet;

    LabTourClient() {
        System.out.println("CUBICLAB_TOUR_CLIENT stillTicks=" + views.stillTicks + " quietTicks=" + quietTicks
                + " renderFrames=" + views.renderFrames + " limitTicks=" + views.limitTicks);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase != TickEvent.Phase.END || mc.player == null || mc.world == null || due != null) return;
        ICubeSource source = CubeSource.get();
        boolean settled = source != null && source.attached() && source.settled(quietTicks);
        LabTourViews.View view = views.tick(mc.player.posX, mc.player.posY, mc.player.posZ, settled);
        if (view == null) return;
        dueBox = source == null ? null : source.heldBox();
        dueQuiet = source == null ? 0 : source.quietTicks();
        due = view;
    }

    @SubscribeEvent
    public void frame(TickEvent.RenderTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase == TickEvent.Phase.END && mc.world != null && mc.renderGlobal != null)
            views.frame(mc.renderGlobal.hasNoChunkUpdates());
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        LabTourViews.View view = due;
        if (view == null) return;
        due = null;
        Minecraft mc = Minecraft.getMinecraft();
        String name = String.format("cubiclab-view-%02d-%d.png", view.index, System.currentTimeMillis());
        String result = ScreenShotHelper.saveScreenshot(mc.gameDir, name, mc.displayWidth, mc.displayHeight,
                mc.getFramebuffer()).getUnformattedText();
        String detail = " view=" + view.index + " file=" + name + " settled=" + view.settled + " stillTicks="
                + view.stillTicks + " quietTicks=" + dueQuiet + " idleFrames=" + view.idleFrames + " heldBox="
                + (dueBox == null ? "none" : Arrays.toString(dueBox).replace(" ", "")) + " pose="
                + String.format("%.2f,%.2f,%.2f,%.1f,%.1f", mc.player.posX, mc.player.posY, mc.player.posZ,
                mc.player.rotationYaw, mc.player.rotationPitch);
        // No capture line without the file: lab.run then names the view it waited for.
        if (!new File(new File(mc.gameDir, "screenshots"), name).isFile()) {
            System.out.println("CUBICLAB_TOUR_FAILED" + detail + " result=" + result);
            return;
        }
        System.out.println(CAPTURED + detail);
    }
}
