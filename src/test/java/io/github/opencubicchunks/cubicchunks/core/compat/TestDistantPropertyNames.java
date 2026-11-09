package io.github.opencubicchunks.cubicchunks.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Optional;
import io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons.DistantPropertyNames;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.properties.PropertyHelper;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.IStringSerializable;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** DH 3.3.3's block-state property strings, with each value that has only Object's toString written by its name. */
public class TestDistantPropertyNames {
    private static Block host;

    @BeforeClass public static void bootstrap() {
        Bootstrap.register();
        host = new Block(Material.ROCK) {
            @Override protected BlockStateContainer createBlockState() { return new BlockStateContainer(this, KIND); }
        };
    }

    @Test public void vanillaValuesKeepDistantHorizonsForm() {
        // DH writes each value's toString; none of these has only Object's, so DH's stored strings still match.
        IBlockState[] states = {Blocks.LOG.getDefaultState(), Blocks.STONE.getStateFromMeta(1),
                Blocks.LEAVES.getDefaultState(), Blocks.FARMLAND.getStateFromMeta(3), Blocks.STONE_SLAB.getStateFromMeta(8)};
        for (IBlockState state : states) assertEquals(state.toString(), distantHorizonsForm(state), DistantPropertyNames.serialize(state));
        assertEquals("{axis:y}{variant:oak}", DistantPropertyNames.serialize(Blocks.LOG.getDefaultState()));
    }

    @Test public void everyRegisteredStateKeepsDistantHorizonsForm() {
        // Only a value with Object's toString may change; every other string DH has stored stays byte for byte.
        int states = 0, skipped = 0;
        for (Block block : Block.REGISTRY) {
            for (IBlockState state : block.getBlockState().getValidStates()) {
                if (hasObjectToString(state)) {
                    skipped++;
                } else {
                    assertEquals(state.toString(), distantHorizonsForm(state), DistantPropertyNames.serialize(state));
                }
                states++;
            }
        }
        assertTrue("registered states: " + states, states > 1000);
        // Vanilla has no value with Object's toString, so the check above ran on every state.
        assertEquals("Object-toString states skipped", 0, skipped);
    }

    @Test public void aValueWithoutItsOwnToStringIsNamed() {
        IBlockState state = host.getDefaultState().withProperty(KIND, Kind.BASALT);
        assertTrue("DH's form carries Object's toString: " + distantHorizonsForm(state), distantHorizonsForm(state).contains("Kind@"));
        assertEquals("{kind:basalt}", DistantPropertyNames.serialize(state));
    }

    @Test public void aValueWithItsOwnToStringKeepsIt() {
        // Like an enum whose toString is shared by several values: not its name, but stable, so DH's data has it.
        Block shaped = new Block(Material.ROCK) {
            @Override protected BlockStateContainer createBlockState() { return new BlockStateContainer(this, SHAPE); }
        };
        IBlockState state = shaped.getDefaultState().withProperty(SHAPE, Shape.LINES_X);
        assertEquals("{shape:lines}", DistantPropertyNames.serialize(state));
    }

    /** Whether DH's form of any of the state's values is Object's toString (class name and hash). */
    private static boolean hasObjectToString(IBlockState state) {
        for (Comparable<?> value : state.getProperties().values()) {
            if (value.toString().equals(value.getClass().getName() + '@' + Integer.toHexString(value.hashCode()))) return true;
        }
        return false;
    }

    /** DH 3.3.3's serializeBlockStateProperties for 1.12.2. */
    private static String distantHorizonsForm(IBlockState state) {
        List<IProperty<?>> properties = new ArrayList<>(state.getPropertyKeys());
        properties.sort(Comparator.comparing(IProperty::getName));
        StringBuilder out = new StringBuilder();
        for (IProperty<?> property : properties) {
            out.append('{').append(property.getName()).append(':').append(state.getValue(property).toString()).append('}');
        }
        return out.toString();
    }

    /**
     * A modded value type with no toString of its own, like GregTech's StoneType: its hashCode is its own but mixes in
     * a field that has only an identity hash (StoneType's stoneMaterial), so Object.toString prints a different string
     * every start without being the value's identity hash.
     */
    static final class Kind implements Comparable<Kind> {
        static final Object MATERIAL = new Object();
        static final Kind GRANITE = new Kind("granite"), BASALT = new Kind("basalt");
        final String name;
        Kind(String name) { this.name = name; }
        @Override public int compareTo(Kind other) { return name.compareTo(other.name); }
        @Override public boolean equals(Object other) { return other instanceof Kind && ((Kind) other).name.equals(name); }
        @Override public int hashCode() { return 31 * name.hashCode() + MATERIAL.hashCode(); }
    }

    static final PropertyHelper<Kind> KIND = new PropertyHelper<Kind>("kind", Kind.class) {
        @Override public Collection<Kind> getAllowedValues() { return Arrays.asList(Kind.GRANITE, Kind.BASALT); }
        @Override public Optional<Kind> parseValue(String value) {
            for (Kind kind : getAllowedValues()) if (kind.name.equals(value)) return Optional.of(kind);
            return Optional.absent();
        }
        @Override public String getName(Kind value) { return value.name; }
    };

    /** A value type whose toString is not its name. */
    enum Shape implements IStringSerializable {
        LINES_X, LINES_Y;
        @Override public String getName() { return name().toLowerCase(Locale.ROOT); }
        @Override public String toString() { return "lines"; }
    }

    static final PropertyEnum<Shape> SHAPE = PropertyEnum.create("shape", Shape.class);
}
