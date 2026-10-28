package io.github.opencubicchunks.cubicchunks.core.server.chunkio;

import java.io.IOException;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Backend-release ownership after a successful durability drain. The facade excludes readers and
 * publishers before entering this boundary. Failures retain state; they never reopen admission.
 *
 * <p>Close attempts are serialized. Observers never acquire the close monitor, so a backend callback
 * can inspect admission without blocking on its own release. This does not quiesce world readers,
 * capture dirty world state or provide the outer world's pre-retirement gate.
 */
final class StorageCloseState {
    enum State {
        OPEN,
        CLOSING,
        FAILED,
        CLOSED
    }

    @FunctionalInterface
    interface CloseAction {
        void close() throws IOException;
    }

    private final CloseAction closeBackend;
    private final Runnable stopWriter;
    private final boolean retryableBackend;
    private volatile State state = State.OPEN;
    @Nullable private volatile IOException failure;
    @Nullable private IOException backendFailure;
    private boolean backendAttempted;
    private boolean backendClosed;
    private boolean writerStopped;

    StorageCloseState(CloseAction closeBackend, Runnable stopWriter, boolean retryableBackend) {
        this.closeBackend = Objects.requireNonNull(closeBackend, "closeBackend");
        this.stopWriter = Objects.requireNonNull(stopWriter, "stopWriter");
        this.retryableBackend = retryableBackend;
    }

    State state() {
        return state;
    }

    @Nullable
    IOException failure() {
        return failure;
    }

    void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("Cube storage no longer admits I/O: " + state, failure);
        }
    }

    synchronized void close() throws IOException {
        if (state == State.CLOSED) {
            return;
        }
        if (state == State.CLOSING) {
            throw new IOException("Reentrant cube storage close");
        }
        state = State.CLOSING;
        IOException error = null;
        Error fatal = null;
        try {
            if (backendAttempted && !backendClosed && !retryableBackend) {
                error = new IOException("Backend close failed; this provider does not support a safe close retry",
                        backendFailure);
            } else if (!backendClosed) {
                backendAttempted = true;
                try {
                    closeBackend.close();
                    backendClosed = true;
                    backendFailure = null;
                } catch (IOException closeFailure) {
                    error = closeFailure;
                    backendFailure = error;
                } catch (RuntimeException closeFailure) {
                    error = new IOException("Cube storage backend close failed", closeFailure);
                    backendFailure = error;
                } catch (Error closeFailure) {
                    fatal = closeFailure;
                    error = new IOException("Cube storage backend close aborted", closeFailure);
                    backendFailure = error;
                }
            }
        } finally {
            // No admitted snapshots remain after the facade's barrier. Stop even if backend release
            // failed, but retain its error and ownership rather than replacing either with success.
            if (!writerStopped) {
                try {
                    stopWriter.run();
                    writerStopped = true;
                } catch (RuntimeException | Error stopFailure) {
                    IOException wrapped = new IOException("Cube storage writer shutdown failed", stopFailure);
                    if (error == null) {
                        error = wrapped;
                    } else {
                        error.addSuppressed(wrapped);
                    }
                    if (fatal != null) {
                        if (fatal != stopFailure) {
                            fatal.addSuppressed(stopFailure);
                        }
                    } else if (stopFailure instanceof Error) {
                        fatal = (Error) stopFailure;
                        if (backendFailure != null) {
                            fatal.addSuppressed(backendFailure);
                        }
                    }
                }
            }
            failure = error;
            state = backendClosed && writerStopped ? State.CLOSED : State.FAILED;
        }
        if (fatal != null) {
            throw fatal;
        }
        if (error != null) {
            throw error;
        }
    }
}
