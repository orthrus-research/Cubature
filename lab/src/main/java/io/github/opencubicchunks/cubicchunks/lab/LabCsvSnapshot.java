package io.github.opencubicchunks.cubicchunks.lab;
import java.io.IOException;
import java.nio.file.Path;
/** A JSON map which also preserves bounded raw observations after the capture ends. */
interface LabCsvSnapshot { void exportCsv(Path path) throws IOException; }
