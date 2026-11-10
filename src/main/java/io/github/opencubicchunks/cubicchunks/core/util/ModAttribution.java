/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
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
package io.github.opencubicchunks.cubicchunks.core.util;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraftforge.fml.common.Loader;

/** Names the mods whose classes appear in a stack trace, for worldgen hang reports. */
public final class ModAttribution {

    private static final Map<String, String> packageToModId = getPackageToModId();

    private ModAttribution() {
    }

    private static Map<String, String> getPackageToModId() {
        return Collections.unmodifiableMap(Loader.instance().getActiveModList().stream()
                .flatMap(mod -> mod.getOwnedPackages().stream().map(pkg -> new AbstractMap.SimpleEntry<>(pkg, mod.getModId())))
                .collect(Collectors
                        .toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue, (a, b) -> a.equals(b) ? a : a + " or " + b)));
    }

    private static String getPackageName(Class<?> clazz) {
        String canonicalName = clazz.getCanonicalName();
        int dot = canonicalName.lastIndexOf('.'); //cannot occur anywhere in the class name itself
        return dot < 0
                ? "" //default package
                : canonicalName.substring(0, dot);
    }

    public static Set<String> getModsForStacktrace(StackTraceElement[] stacktrace) {
        Set<String> mods = new HashSet<>();
        for (StackTraceElement traceElement : stacktrace) {
            try {
                Class<?> cl = Class.forName(traceElement.getClassName());
                if (cl != null) {
                    String modid = packageToModId.get(getPackageName(cl));
                    if (modid != null && !modid.equals("minecraft") && !modid.equals("forge") && !modid.equals("cubicchunks")) {
                        mods.add(modid);
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        return mods;
    }

    public static String getModForStacktraceElement(StackTraceElement traceElement) {
        try {
            Class<?> cl = Class.forName(traceElement.getClassName());
            if (cl != null) {
                String modid = packageToModId.get(getPackageName(cl));
                if (modid != null) {
                    return modid;
                }
            }
        } catch (ClassNotFoundException ignored) {
        }
        return "(no mod)";
    }
}
