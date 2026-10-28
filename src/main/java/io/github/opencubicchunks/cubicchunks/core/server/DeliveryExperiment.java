package io.github.opencubicchunks.cubicchunks.core.server;

/** Delivery mode from CubicChunksConfig.deliveryMode; the cubature.deliveryExperiment JVM property overrides it. */
public final class DeliveryExperiment {
    private static final String STAGE = System.getProperty("cubature.deliveryExperiment",
            io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig.deliveryMode.name().toLowerCase(java.util.Locale.ROOT));
    static {
        if (!STAGE.equals("baseline") && !STAGE.equals("maintenance") && !STAGE.equals("save") && !STAGE.equals("prefetch"))
            throw new IllegalArgumentException("Unknown Cubature delivery experiment: " + STAGE);
    }
    private DeliveryExperiment() {}
    public static boolean maintenance() { return !STAGE.equals("baseline"); }
    public static boolean saving() { return STAGE.equals("save") || STAGE.equals("prefetch"); }
    public static boolean prefetch() { return STAGE.equals("prefetch"); }
}
