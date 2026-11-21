/*
 *  This file is part of Cubature, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2026 Orthrus Research
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.cubicchunks.core.client;

import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiErrorScreen;
import net.minecraftforge.fml.client.CustomModLoadingErrorDisplayException;
import net.minecraftforge.fml.common.Loader;

import java.util.List;

/**
 * Cubature's client has no renderer of its own for cubic worlds: the game's chunk system does not work in them, and a
 * renderer that takes it over draws the world, Quadrature (mod id {@code quadrature}). Without it the client stops
 * loading with a message saying so. Dedicated servers draw nothing and are not checked.
 * <p>
 * {@code -Dcubature.clientWithoutRenderer=true} skips the check, for runs that only time the server. The game's
 * renderer then draws only heights 0 to 255 of a cubic world, with the entities and block entities there, as in a world
 * that is not cubic ({@code MixinViewFrustum_VertViewDistance} keeps its grid that high).
 */
public final class RendererRequirement extends CustomModLoadingErrorDisplayException {

    private static final long serialVersionUID = 1L;

    /** The mod id of the renderer that takes over the game's chunk system in cubic worlds. */
    public static final String RENDERER_MOD_ID = "quadrature";
    public static final String SKIP_PROPERTY = "cubature.clientWithoutRenderer";

    static final String TITLE = "Cubature cannot start without Quadrature";
    static final String MESSAGE = "Cubature's client draws cubic worlds through Quadrature, which is not installed. "
            + "Install Quadrature, or start the game with -D" + SKIP_PROPERTY + "=true to go on without it: "
            + "only heights 0 to 255 of a cubic world are then drawn.";

    private RendererRequirement() {
        super(TITLE + ": " + MESSAGE, null);
    }

    /** Stops loading when Quadrature is not loaded, unless the check is skipped. Called from the client's preInit. */
    public static void check() {
        if (Loader.isModLoaded(RENDERER_MOD_ID)) {
            return;
        }
        if (Boolean.getBoolean(SKIP_PROPERTY)) {
            CubicChunks.LOGGER.warn("Quadrature is not installed and -D{}=true is set: only heights 0 to 255 of a cubic "
                    + "world are drawn.", SKIP_PROPERTY);
            return;
        }
        throw new RendererRequirement();
    }

    @Override
    public void initGui(GuiErrorScreen errorScreen, FontRenderer fontRenderer) {
    }

    @Override
    public void drawScreen(GuiErrorScreen errorScreen, FontRenderer fontRenderer, int mouseRelX, int mouseRelY,
            float tickTime) {
        int y = errorScreen.height / 2 - 40;
        errorScreen.drawCenteredString(fontRenderer, TITLE, errorScreen.width / 2, y, 0xFFFFFF);
        y += 2 * fontRenderer.FONT_HEIGHT;
        List<String> lines = fontRenderer.listFormattedStringToWidth(MESSAGE, Math.max(100, errorScreen.width - 40));
        for (String line : lines) {
            errorScreen.drawCenteredString(fontRenderer, line, errorScreen.width / 2, y, 0xEEEEEE);
            y += fontRenderer.FONT_HEIGHT + 2;
        }
    }
}
