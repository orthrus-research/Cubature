package io.github.opencubicchunks.cubicchunks.lab;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

public class TestLabControlCodec {
    private static String repeated(char value, int length) {
        char[] text = new char[length]; Arrays.fill(text, value); return new String(text);
    }
    private static void roundTrip(String json) {
        ByteBuf buf = Unpooled.buffer();
        try {
            Network.Message sent = new Network.Message(); sent.json = json; sent.toBytes(buf);
            assertTrue(buf.readableBytes() <= LabControlCodec.MAX_WIRE_BYTES + 2);
            Network.Message received = new Network.Message(); received.fromBytes(buf);
            assertEquals(json, received.json); assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }
    private static void rejects(Runnable action) {
        try { action.run(); fail("Invalid control message accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static String compressed(byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(bytes); }
        return LabControlCodec.PREFIX + Base64.getEncoder().encodeToString(out.toByteArray());
    }
    @Test public void retainsLegacyPlainControlMessages() {
        String json = "{\"op\":\"ready\",\"scenario\":\"full-view\"}";
        assertSame(json, LabControlCodec.encode(json)); roundTrip(json);
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufUtils.writeUTF8String(buf, json);
            Network.Message message = new Network.Message(); message.fromBytes(buf); assertEquals(json, message.json);
        } finally { buf.release(); }
    }
    @Test public void respectsTheActualForgeTwoByteLengthBoundary() {
        String boundary = repeated('a', 16383);
        assertSame(boundary, LabControlCodec.encode(boundary)); roundTrip(boundary);
        String larger = repeated('a', 16384);
        assertTrue(LabControlCodec.encode(larger).startsWith(LabControlCodec.PREFIX)); roundTrip(larger);
    }
    @Test public void countsUtf8BytesRatherThanJavaCharacters() {
        String boundary = repeated('\u6c34', 5461);
        assertEquals(16383, boundary.getBytes(StandardCharsets.UTF_8).length);
        assertSame(boundary, LabControlCodec.encode(boundary)); roundTrip(boundary);
        roundTrip(boundary + "\u6c34\ud83e\uddf1");
        assertTrue(LabControlCodec.encode(boundary + "\u6c34").startsWith(LabControlCodec.PREFIX));
    }
    @Test public void preservesEveryFieldInLargeDiagnosticReports() {
        JsonObject data = new JsonObject(); data.addProperty("op", "report");
        data.addProperty("diagnostics", repeated('x', 22000)); data.addProperty("tail", "\u03bcs \ud83e\uddf1");
        roundTrip(data.toString());
    }
    @Test public void enforcesTheDecodedByteLimitAtBothEnds() {
        roundTrip(repeated('a', LabControlCodec.MAX_JSON_BYTES));
        rejects(() -> LabControlCodec.encode(repeated('a', LabControlCodec.MAX_JSON_BYTES + 1)));
        rejects(() -> LabControlCodec.encode(repeated('\u6c34', LabControlCodec.MAX_JSON_BYTES / 3 + 1)));
    }
    @Test public void rejectsInflationPastTheLimitAndInvalidUtf8() throws Exception {
        String bomb = compressed(repeated('a', LabControlCodec.MAX_JSON_BYTES + 1).getBytes(StandardCharsets.UTF_8));
        rejects(() -> LabControlCodec.decode(bomb));
        String invalidUtf8 = compressed(new byte[]{(byte) 0xc3, 0x28});
        rejects(() -> LabControlCodec.decode(invalidUtf8));
    }
    @Test public void rejectsMalformedTruncatedAndCorruptCompressedReports() throws Exception {
        rejects(() -> LabControlCodec.decode(LabControlCodec.PREFIX + "!"));
        rejects(() -> LabControlCodec.decode(LabControlCodec.PREFIX));
        String valid = compressed(repeated('a', 20000).getBytes(StandardCharsets.UTF_8));
        byte[] bytes = Base64.getDecoder().decode(valid.substring(LabControlCodec.PREFIX.length()));
        String truncated = LabControlCodec.PREFIX + Base64.getEncoder().encodeToString(Arrays.copyOf(bytes, bytes.length - 1));
        rejects(() -> LabControlCodec.decode(truncated));
        bytes[bytes.length - 8] ^= 1;
        String corrupt = LabControlCodec.PREFIX + Base64.getEncoder().encodeToString(bytes);
        rejects(() -> LabControlCodec.decode(corrupt));
    }
    @Test public void rejectsOversizedWireAndIncompressibleMessages() {
        rejects(() -> LabControlCodec.decode(repeated('a', LabControlCodec.MAX_WIRE_BYTES + 1)));
        rejects(() -> LabControlCodec.decode(repeated('\u6c34', 5462)));
        byte[] random = new byte[24000]; new Random(73).nextBytes(random);
        String json = Base64.getEncoder().encodeToString(random);
        rejects(() -> LabControlCodec.encode(json));
    }
}
