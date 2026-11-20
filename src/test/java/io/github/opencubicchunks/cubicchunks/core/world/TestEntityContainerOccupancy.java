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
package io.github.opencubicchunks.cubicchunks.core.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import net.minecraft.entity.Entity;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A container tells its column +1 only as it goes from holding no entity to holding one and -1 only as it goes back,
 * so the column's count of cubes holding entities, kept from those notices, is always the true count.
 */
public class TestEntityContainerOccupancy {

    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    private static final class Plain extends Entity {
        Plain() {
            super(null);
        }

        @Override protected void entityInit() {
        }

        @Override protected void readEntityFromNBT(NBTTagCompound compound) {
        }

        @Override protected void writeEntityToNBT(NBTTagCompound compound) {
        }
    }

    @Test public void noticesFollowEmptiness() {
        Random random = new Random(8675309);
        EntityContainer[] containers = new EntityContainer[8];
        int[] count = {0};
        for (int i = 0; i < containers.length; i++) {
            containers[i] = new EntityContainer();
            containers[i].setOccupancy(change -> count[0] += change);
        }
        List<List<Entity>> held = new ArrayList<>();
        for (int i = 0; i < containers.length; i++) {
            held.add(new ArrayList<>());
        }
        for (int step = 0; step < 20000; step++) {
            int i = random.nextInt(containers.length);
            List<Entity> in = held.get(i);
            if (in.isEmpty() || random.nextInt(3) != 0) {
                Entity entity = new Plain();
                containers[i].addEntity(entity);
                in.add(entity);
            } else if (random.nextInt(10) == 0) {
                // Removing what the container does not hold changes nothing.
                assertFalse(containers[i].remove(new Plain()));
            } else {
                containers[i].remove(in.remove(random.nextInt(in.size())));
            }
            int truth = 0;
            for (EntityContainer container : containers) {
                if (container.size() > 0) {
                    truth++;
                }
            }
            assertEquals("step " + step, truth, count[0]);
        }
    }

    @Test public void aContainerOutsideAColumnTellsNoOne() {
        int[] count = {0};
        EntityContainer container = new EntityContainer();
        container.setOccupancy(change -> count[0] += change);
        container.setOccupancy(null);
        container.addEntity(new Plain());
        assertEquals(0, count[0]);
    }
}
