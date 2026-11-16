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
package io.github.opencubicchunks.cubicchunks.core.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestLightDue {

    @Test
    public void aCubeNeverSentIsDueAtOnce() {
        assertTrue(CubeWatcher.lightDue(0, CubeWatcher.NEVER_SENT, 10));
        assertTrue(CubeWatcher.lightDue(123_456_789L, CubeWatcher.NEVER_SENT, 10));
    }

    @Test
    public void sendsAreSpaced() {
        assertFalse(CubeWatcher.lightDue(105, 100, 10));
        assertFalse(CubeWatcher.lightDue(109, 100, 10));
        assertTrue(CubeWatcher.lightDue(110, 100, 10));
    }

    @Test
    public void aClockThatWentBackDoesNotStallSends() {
        assertTrue(CubeWatcher.lightDue(50, 100, 10));
    }
}
