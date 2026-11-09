/*
 * Copyright (c) 2026 Orthrus Research.
 * SPDX-License-Identifier: LGPL-3.0-only
 *
 * Cubature runtime integration for Distant Horizons 3.3.3.
 * See NOTICE.md and META-INF/licenses/distanthorizons/ for license texts.
 */
package io.github.opencubicchunks.cubicchunks.core.compat.distanthorizons;

import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The property part of Distant Horizons' block-state serial string: {@code {name:value}} per property, names in
 * order. DH 3.3.3 writes each value's {@code toString}; a modded value with none of its own prints an identity hash,
 * which never matches in another JVM, so the client and every later start fell back to the block's default state.
 * This writes the value's name from its property only where its {@code toString} is that identity hash, and keeps
 * the {@code toString} everywhere else, so every string that was already stable stays byte for byte the same (an
 * enum may print something other than its name, e.g. one shared by several values).
 */
public final class DistantPropertyNames {
    private DistantPropertyNames() {}

    public static String serialize(IBlockState state) {
        List<IProperty<?>> properties = new ArrayList<>(state.getPropertyKeys());
        properties.sort(Comparator.comparing(IProperty::getName));
        StringBuilder out = new StringBuilder();
        for (IProperty<?> property : properties) {
            out.append('{').append(property.getName()).append(':').append(name(state, property)).append('}');
        }
        return out.toString();
    }

    /**
     * The value's {@code toString}, or its property's name for it where its class inherits Object's toString (class
     * name and hash code, which need not survive a restart: GregTech's StoneType hashes in a field with only an
     * identity hash). A null from either falls back to the other, Object's form warning once per property and value
     * class since it will not match after a restart; if both are null the value has no stable form, and this throws
     * rather than writing a "null" that would silently match every other unnamed value.
     */
    private static <T extends Comparable<T>> String name(IBlockState state, IProperty<T> property) {
        T value = state.getValue(property);
        String printed = value.toString();
        if (printed != null && !INHERITS_TO_STRING.get(value.getClass())) {
            return printed;
        }
        String named = property.getName(value);
        if (named != null) {
            return named;
        }
        if (printed != null) {
            if (UNNAMED.add(property.getName() + ' ' + value.getClass().getName())) {
                CubicChunks.LOGGER.warn("Property {} of {} has a value of {} with only Object's toString and no "
                                + "name; Distant Horizons will not match its state across restarts.", property.getName(),
                        state.getBlock().getRegistryName(), value.getClass().getName());
            }
            return printed;
        }
        throw new IllegalStateException("Property " + property.getName() + " of " + state.getBlock().getRegistryName()
                + " has a value of " + value.getClass().getName() + " with neither a toString nor a name");
    }

    /** Property name and value class of each unnamed value already warned about, so each warns once. */
    private static final Set<String> UNNAMED = ConcurrentHashMap.newKeySet();

    /** Whether the value's class inherits Object.toString, by the declaring class of its public toString. */
    private static final ClassValue<Boolean> INHERITS_TO_STRING = new ClassValue<Boolean>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("toString").getDeclaringClass() == Object.class;
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }
    };
}
