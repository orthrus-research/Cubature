package io.github.opencubicchunks.cubicchunks.core.server.delivery;

/** Soft limits checked between indivisible full packets; at least one packet can make progress. */
final class CubeSendBudget {
    private long started, nanos, bytes, byteLimit;
    private int packets;

    void reset(long now, long nanos, long byteLimit) {
        this.started = now;
        this.nanos = nanos;
        this.byteLimit = byteLimit;
        this.bytes = 0;
        this.packets = 0;
    }

    boolean canSend(long now) {
        return packets == 0 || ((nanos <= 0 || now - started < nanos) && (byteLimit <= 0 || bytes < byteLimit));
    }

    void sent(long bodyBytes) {
        if (bodyBytes < 0) throw new IllegalArgumentException("Negative cube packet size");
        bytes += bodyBytes;
        packets++;
    }

    long bytes() { return bytes; }

    /** Whether a byte limit applies this tick, so packets must report their exact size. */
    boolean limitsBytes() { return byteLimit > 0; }
}
