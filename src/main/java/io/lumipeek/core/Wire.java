package io.lumipeek.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import static io.lumipeek.core.Failure.Code.WORK_FAILED;

/** Bounded, versioned binary IPC. No serialization, logging, files or network. */
final class Wire {
    static final int MAGIC = 0x4c504b31, MAX_REQUEST = 256 * 1024, MAX_REPLY = 8 * 1024 * 1024;
    static final int GRANT = 1, DISCOVER = 2, ANALYZE = 3;
    private Wire() {}
    @FunctionalInterface interface Writer { void write(DataOutputStream out) throws IOException; }
    static byte[] write(Writer writer, int max) throws Failure {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            writer.write(out); out.flush();
            if (bytes.size() > max) throw new Failure(WORK_FAILED);
            return bytes.toByteArray();
        } catch (IOException ex) { throw new Failure(WORK_FAILED); }
    }
    static DataInputStream input(byte[] data, int max) throws Failure {
        if (data.length > max) throw new Failure(WORK_FAILED);
        return new DataInputStream(new ByteArrayInputStream(data));
    }
    static void string(DataOutputStream out, String value) throws IOException {
        if (value.length() > 30000) throw new IOException();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 120_000) throw new IOException();
        out.writeInt(bytes.length); out.write(bytes);
    }
    static String string(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 120_000 || size > in.available()) throw new IOException();
        byte[] bytes = in.readNBytes(size);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw new IOException();
        return value;
    }
    static byte[] bytes(DataInputStream in, int max) throws IOException {
        int size = in.readInt();
        if (size < 1 || size > max || size > in.available()) throw new IOException();
        return in.readNBytes(size);
    }
    static void end(DataInputStream in) throws IOException { if (in.available() != 0) throw new IOException(); }
}
