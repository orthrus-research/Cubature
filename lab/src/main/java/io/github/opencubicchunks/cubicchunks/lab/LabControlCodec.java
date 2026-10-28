package io.github.opencubicchunks.cubicchunks.lab;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Lab control JSON only; full reports stay intact in local files and on receipt. */
final class LabControlCodec {
    static final int MAX_WIRE_BYTES = 16383; // Forge UTF8String allows at most a two-byte length varint.
    static final int MAX_JSON_BYTES = 131072;
    static final String PREFIX = "cubiclab-gz1:";

    private LabControlCodec() { }

    static String encode(String json) {
        if (json.length() > MAX_JSON_BYTES) throw new IllegalArgumentException("Lab JSON exceeds 128 KiB");
        byte[] plain = json.getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_JSON_BYTES) throw new IllegalArgumentException("Lab JSON exceeds 128 KiB UTF-8");
        if (plain.length <= MAX_WIRE_BYTES) return json;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(plain); }
            String wire = PREFIX + Base64.getEncoder().encodeToString(output.toByteArray());
            if (wire.length() > MAX_WIRE_BYTES) throw new IllegalArgumentException("Compressed lab JSON exceeds control packet limit");
            return wire;
        } catch (IOException error) { throw new IllegalArgumentException("Cannot encode lab JSON", error); }
    }

    static String decode(String wire) {
        if (wire.length() > MAX_WIRE_BYTES || wire.getBytes(StandardCharsets.UTF_8).length > MAX_WIRE_BYTES)
            throw new IllegalArgumentException("Lab control packet exceeds UTF-8 limit");
        if (!wire.startsWith(PREFIX)) return wire;
        byte[] compressed = Base64.getDecoder().decode(wire.substring(PREFIX.length()));
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = gzip.read(buffer, 0, Math.min(buffer.length, MAX_JSON_BYTES - output.size() + 1))) != -1) {
                if (count > MAX_JSON_BYTES - output.size()) throw new IllegalArgumentException("Inflated lab JSON exceeds 128 KiB");
                output.write(buffer, 0, count);
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString();
        } catch (IOException error) { throw new IllegalArgumentException("Invalid compressed lab JSON", error); }
    }
}
