package io.github.opencubicchunks.cubicchunks.core.server;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.Test;

import static org.junit.Assert.*;

/** Saved state-palette encoding contracts without native block registration/bootstrap. */
public class TestSavedCubeMarkerStateId {
    private static final int MARKER = (1 << 8) | (1 << 4) | 1;

    private static NBTTagCompound saved(int blockId, int metadata) {
        NBTTagCompound level = new NBTTagCompound(), section = new NBTTagCompound();
        NBTTagList sections = new NBTTagList(); sections.appendTag(section); level.setTag("Sections", sections);
        byte[] blocks = new byte[4096], data = new byte[2048];
        blocks[MARKER] = (byte) blockId;
        data[MARKER >>> 1] = (byte) (metadata << 4 | 3); // Adjacent low nibble is deliberately unrelated.
        section.setByteArray("Blocks", blocks); section.setByteArray("Data", data);
        for (int shift : new int[]{8, 12}) {
            int value = blockId >>> shift & 15;
            if (value != 0) {
                byte[] extra = new byte[2048]; extra[MARKER >>> 1] = (byte) (value << 4 | 2);
                section.setByteArray(shift == 8 ? "Add" : "Add2", extra);
            }
        }
        return level;
    }

    @Test public void goldAndDiamondMatchSavedPaletteIdsRatherThanLegacyBlockStateIds() {
        assertEquals(656, LabMaintenanceProbe.savedMarkerPaletteId(saved(41, 0)));
        assertEquals(912, LabMaintenanceProbe.savedMarkerPaletteId(saved(57, 0)));
        assertNotEquals(41, LabMaintenanceProbe.savedMarkerPaletteId(saved(41, 0)));
        assertNotEquals(57, LabMaintenanceProbe.savedMarkerPaletteId(saved(57, 0)));
    }

    @Test public void metadataUsesLowPaletteBitsAndTheMarkersHighNibble() {
        assertEquals((57 << 4) | 7, LabMaintenanceProbe.savedMarkerPaletteId(saved(57, 7)));
        assertNotEquals(57 | (7 << 12), LabMaintenanceProbe.savedMarkerPaletteId(saved(57, 7)));
    }

    @Test public void unsignedBlockBytesAndBothExtendedIdNibblesSurvive() {
        for (int blockId : new int[]{255, 256, 4095, 4096, 0x1241, 65535}) {
            for (int metadata : new int[]{0, 1, 15}) {
                assertEquals((blockId << 4) | metadata, LabMaintenanceProbe.savedMarkerPaletteId(saved(blockId, metadata)));
            }
        }
    }

    @Test public void missingOrMalformedSavedSectionsRemainFailures() {
        try { LabMaintenanceProbe.savedMarkerPaletteId(new NBTTagCompound()); fail("Missing section accepted"); }
        catch (IllegalStateException expected) { }
        for (String field : new String[]{"Blocks", "Data", "Add", "Add2"}) {
            NBTTagCompound level = saved(0x1241, 7);
            level.getTagList("Sections", 10).getCompoundTagAt(0).setByteArray(field, new byte[1]);
            try { LabMaintenanceProbe.savedMarkerPaletteId(level); fail("Malformed " + field + " accepted"); }
            catch (IllegalStateException expected) { }
        }
    }
}
