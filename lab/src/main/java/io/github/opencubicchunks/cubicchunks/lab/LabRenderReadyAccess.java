package io.github.opencubicchunks.cubicchunks.lab;

/** Implemented only by the opt-in baseline-renderer Mixin. Invoked on the GL owner. */
public interface LabRenderReadyAccess {
    int[] cubiclab$renderDemand();
    void cubiclab$visitOutstanding(java.util.function.Consumer<int[]> visitor);
}
