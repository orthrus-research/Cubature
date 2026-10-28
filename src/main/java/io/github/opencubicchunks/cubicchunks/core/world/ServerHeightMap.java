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
package io.github.opencubicchunks.cubicchunks.core.world;

import io.github.opencubicchunks.cubicchunks.api.util.Coords;
import io.github.opencubicchunks.cubicchunks.api.world.IHeightMap;
import io.github.opencubicchunks.cubicchunks.core.CubicChunks;
import io.github.opencubicchunks.cubicchunks.core.world.cube.Cube;
import mcp.MethodsReturnNonnullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ServerHeightMap implements IHeightMap {

    /**
     * Special value to indicate the absence of a segment in the segments arrays.
     * Since all integer values might represent a valid segment, an unlikely value to occur has been chosen. It
     * logically represents a 1-block segment at the very top of the world.
     */
    private static final int NONE_SEGMENT = 0x7fffffff;

    /**
     * Array containing the y-coordinates of the lowest segment in each block column. The value {@link Coords#NO_HEIGHT}
     * is used if a given block column does not contain any segments.
     */
    @Nonnull private final int[] ymin;

    /**
     * Array containing the y-coordinate of the highest segment in each block column. The value {@link Coords#NO_HEIGHT}
     * is used if a given block column does not contain any segments.
     */
    @Nonnull private final HeightMap ymax;

    /**
     * Array containing an array of segments for each x/z position in a column.
     */
    @Nonnull private final int[][] segments;

    private int heightMapLowest;

    public ServerHeightMap(int[] heightmap) {
        this.ymin = new int[Cube.SIZE * Cube.SIZE];
        this.ymax = new HeightMap(heightmap);

        this.segments = new int[Cube.SIZE * Cube.SIZE][];

        // init to empty
        for (int i = 0; i < Cube.SIZE * Cube.SIZE; i++) {
            this.ymin[i] = Coords.NO_HEIGHT;
            this.ymax.set(i, Coords.NO_HEIGHT);
        }

        this.heightMapLowest = Coords.NO_HEIGHT;
    }


    private static int getOpacity(int segmentIndex) {
        return (segmentIndex + 1) % 2;
    }

    private static int getLastSegmentIndex(int[] segments) {
        for (int i = segments.length - 1; i >= 0; i--) {
            if (segments[i] != NONE_SEGMENT) {
                return i;
            }
        }
        throw new Error("Invalid segments state");
    }

    /**
     * Checks if the number of segments in a given block-column is correct.
     * The number of segments must always be odd.
     *
     * @param xzIndex The block-column's index.
     * @return True if the number of segments is correct.
     */
    private boolean parityCheck(int xzIndex) {
        return getLastSegmentIndex(segments[xzIndex]) % 2 == 0;
    }

    // Interface: IHeightMap ----------------------------------------------------------------------------------------

    @Override
    public void onOpacityChange(int localX, int blockY, int localZ, int opacity) {
        if (blockY > CubicChunks.MAX_SUPPORTED_BLOCK_Y || blockY < CubicChunks.MIN_SUPPORTED_BLOCK_Y) {
            return;
        }
        int xzIndex = getIndex(localX, localZ);

        // try to stay in no-segments mode as long as we can, this is the simple case
        boolean isOpaque = opacity != 0;
        if (this.segments[xzIndex] == null) {
            this.setNoSegments(xzIndex, blockY, isOpaque);
        } else {
            this.setOpacityWithSegments(xzIndex, blockY, isOpaque);
        }

        this.heightMapLowest = Coords.NO_HEIGHT;
    }

    @Override
    public int getTopBlockY(int localX, int localZ) {
        return this.ymax.get(getIndex(localX, localZ));
    }

    @Override
    public int getTopBlockYBelow(int localX, int localZ, int blockY) {
        int i = getIndex(localX, localZ);
        if (blockY > ymax.get(i)) return ymax.get(i);
        if (blockY <= ymin[i]) return Coords.NO_HEIGHT;
        int[] starts = segments[i];
        if (starts == null) return blockY - 1;
        int segment = findSegment(starts, getLastSegmentIndex(starts) + 1, blockY);
        if (segment < 0) return Coords.NO_HEIGHT;
        if ((segment & 1) != 0) return starts[segment] - 1;
        return segment == 0 || blockY != starts[segment] ? blockY - 1 : starts[segment - 1] - 1;
    }

    @Override
    public int getLowestTopBlockY() {
        if (this.heightMapLowest == Coords.NO_HEIGHT) {
            this.heightMapLowest = Integer.MAX_VALUE;
            for (int i = 0; i < Cube.SIZE * Cube.SIZE; i++) {
                if (this.ymax.get(i) < this.heightMapLowest) {
                    this.heightMapLowest = this.ymax.get(i);
                }
            }
            if (this.heightMapLowest == Coords.NO_HEIGHT) {
                this.heightMapLowest--; // don't recalculate this on every call
            }
        }
        return this.heightMapLowest;
    }


    // Helper ----------------------------------------------------------------------------------------------------------

    private void setNoSegments(int i, int y, boolean opaque) {
        int min = ymin[i], max = ymax.get(i);
        assert (min == Coords.NO_HEIGHT) == (max == Coords.NO_HEIGHT);
        if (opaque) {
            if (min == Coords.NO_HEIGHT) {
                ymin[i] = y;
                ymax.set(i, y);
            } else if (y < min) {
                if (y + 1 != min) segments[i] = new int[] {y, y + 1, min};
                ymin[i] = y;
            } else if (y > max) {
                if (y != max + 1) segments[i] = new int[] {min, max + 1, y};
                ymax.set(i, y);
            }
        } else if (y >= min && y <= max) {
            if (min == max) {
                ymin[i] = Coords.NO_HEIGHT;
                ymax.set(i, Coords.NO_HEIGHT);
            } else if (y == min) ymin[i]++;
            else if (y == max) ymax.decrement(i);
            else segments[i] = new int[] {min, y, y + 1};
        }
    }

    /** Index of the last segment start at or below y, or -1 below the first start. */
    private static int findSegment(int[] starts, int count, int y) {
        int low = 0, high = count - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (starts[mid] < y) low = mid + 1;
            else if (starts[mid] > y) high = mid - 1;
            else return mid;
        }
        return high;
    }

    /** Each changed block inserts, moves or cancels the two boundaries around [y, y + 1). */
    private void setOpacityWithSegments(int i, int y, boolean opaque) {
        int[] starts = segments[i];
        int count = getLastSegmentIndex(starts) + 1, min = ymin[i], max = ymax.get(i);
        if (count == 1 && min == max && y == min && !opaque) {
            segments[i] = null;
            ymin[i] = Coords.NO_HEIGHT;
            ymax.set(i, Coords.NO_HEIGHT);
            return;
        }
        if (y < min) {
            if (!opaque) return;
            if (y == min - 1) starts[0] = y;
            else insertSegments(i, 0, y, y + 1, count);
            ymin[i] = y;
        } else if (y > max) {
            if (!opaque) return;
            if (y != max + 1) insertSegments(i, count, max + 1, y, count);
            ymax.set(i, y);
        } else {
            int at = findSegment(starts, count, y);
            if (((at & 1) == 0) == opaque) return;
            int bottom = starts[at], top = at + 1 == count ? max : starts[at + 1] - 1;
            if (bottom == top) {
                if (at == count - 1) ymax.set(i, starts[--at] - 1);
                else if (at == 0) ymin[i] = starts[2];
                if (count == 3) segments[i] = null;
                else {
                    System.arraycopy(starts, at + 2, starts, at, count - at - 2);
                    starts[count - 2] = starts[count - 1] = NONE_SEGMENT;
                }
            } else if (y == bottom) {
                starts[at]++;
                if (at == 0) ymin[i]++;
            } else if (y == top) {
                if (at == count - 1) ymax.decrement(i);
                else starts[at + 1]--;
            } else insertSegments(i, at + 1, y, y + 1, count);
        }
        assert segments[i] == null || parityCheck(i);
    }

    private void insertSegments(int i, int at, int first, int second, int count) {
        int[] old = segments[i], target = old;
        if (old.length < count + 2) {
            target = new int[count + 2];
            System.arraycopy(old, 0, target, 0, at);
        }
        System.arraycopy(old, at, target, at + 2, count - at);
        target[at] = first;
        target[at + 1] = second;
        segments[i] = target;
    }

    private static int getIndex(int localX, int localZ) {
        return (localZ << 4) | localX;
    }

    // Serialization / NBT ---------------------------------------------------------------------------------------------

    public byte[] getData() {
        try {
            // Each block column stores two heights, a segment count, and only its used segment entries.
            int serializedSize = this.segments.length * (2 * Integer.BYTES + Short.BYTES);
            for (int[] columnSegments : this.segments) {
                if (columnSegments != null && columnSegments.length != 0) {
                    serializedSize += (getLastSegmentIndex(columnSegments) + 1) * Integer.BYTES;
                }
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream(serializedSize);
            DataOutputStream out = new DataOutputStream(buf);
            writeData(out);
            out.close();
            return buf.toByteArray();
        } catch (IOException ex) {
            throw new Error(ex);
        }
    }

    public void readData(byte[] data) {
        ByteBuffer input = ByteBuffer.wrap(data);
        this.heightMapLowest = Coords.NO_HEIGHT;
        for (int i = 0; i < this.segments.length; i++) {
            this.ymin[i] = input.getInt();
            this.ymax.set(i, input.getInt());
            int count = input.getShort() & 0xffff;
            if (count == 0) {
                if (this.segments[i] != null) this.segments[i] = null;
                continue;
            }
            int[] starts = new int[count];
            for (int j = 0; j < count; j++) starts[j] = input.getInt();
            this.segments[i] = starts;
            assert parityCheck(i) : "The number of segments was wrong!";
        }
    }

    private void writeData(DataOutputStream out) throws IOException {
        for (int i = 0; i < this.segments.length; i++) {
            out.writeInt(this.ymin[i]);
            out.writeInt(this.ymax.get(i));
            int[] segments = this.segments[i];
            if (segments == null || segments.length == 0) {
                out.writeShort(0);
            } else {
                int lastSegmentIndex = getLastSegmentIndex(segments);
                out.writeShort(lastSegmentIndex + 1);
                for (int j = 0; j <= lastSegmentIndex; j++) {
                    out.writeInt(segments[j]);
                }
            }
        }
    }


    // Debug -----------------------------------------------------------------------------------------------------------

    public String dump(int localX, int localZ) {
        int i = getIndex(localX, localZ);

        StringBuilder buf = new StringBuilder();
        buf.append("range=[");
        buf.append(this.ymin[i]);
        buf.append(",");
        buf.append(this.ymax.get(i));
        buf.append("], segments(p,o)=");

        if (this.segments[i] != null) {
            for (int pos : this.segments[i]) {
                int opacity = getOpacity(i);
                buf.append("(");
                buf.append(pos);
                buf.append(",");
                buf.append(opacity);
                buf.append(")");
            }
        }
        return buf.toString();
    }

}
