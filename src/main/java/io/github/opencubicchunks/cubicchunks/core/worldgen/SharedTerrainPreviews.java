/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
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
package io.github.opencubicchunks.cubicchunks.core.worldgen;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

/**
 * The previews a client has borrowed from its own integrated server's generators
 * (docs/CUBE-SOURCE.md, section 8). The client shares a generator, so its regional memos are the
 * ones native generation already paid for; the server takes every loan back before it closes the
 * world, so no client thread samples a generator whose stores are closing, and lends none of a
 * world it has begun to close.
 *
 * <p>Keyed by the server world's identity; this class knows neither side's classes, so a
 * dedicated server loads it and finds it empty.
 */
public final class SharedTerrainPreviews {

    private static final Map<Object, List<WithdrawablePreview>> LOANS = new IdentityHashMap<>();
    /** Worlds that have begun to close. Worlds do not override equality, so this is by identity. */
    private static final Set<Object> CLOSING = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean stopping;

    private SharedTerrainPreviews() {
    }

    /** A server is about to start: loans may be made again. */
    public static void serverStarting() {
        synchronized (LOANS) {
            stopping = false;
        }
    }

    /** Lends a server world's generator preview to the client, or null once the world is closing. */
    @Nullable
    public static WithdrawablePreview lend(Object serverWorld, ICubicTerrainPreview generator) {
        synchronized (LOANS) {
            if (stopping || CLOSING.contains(serverWorld)) {
                return null;
            }
            WithdrawablePreview loan = new WithdrawablePreview(generator);
            LOANS.computeIfAbsent(serverWorld, world -> new ArrayList<>()).add(loan);
            return loan;
        }
    }

    /**
     * Ends one loan the client no longer uses, without waiting: columns being sampled finish, and
     * the server's own withdrawal still waits for them.
     */
    public static void giveBack(WithdrawablePreview loan) {
        loan.revoke();
    }

    /**
     * Takes back every loan of a server world's generator, waiting for columns in flight. Called
     * on the server thread before the world closes.
     */
    public static void withdraw(Object serverWorld) {
        List<WithdrawablePreview> loans;
        synchronized (LOANS) {
            CLOSING.add(serverWorld);
            loans = LOANS.remove(serverWorld);
        }
        if (loans != null) {
            for (WithdrawablePreview loan : loans) {
                loan.withdraw();
            }
        }
    }

    /** Takes back every loan of every world: the server is stopping. */
    public static void withdrawAll() {
        List<WithdrawablePreview> loans = new ArrayList<>();
        synchronized (LOANS) {
            stopping = true;
            for (List<WithdrawablePreview> of : LOANS.values()) {
                loans.addAll(of);
            }
            LOANS.clear();
        }
        for (WithdrawablePreview loan : loans) {
            loan.withdraw();
        }
    }
}
