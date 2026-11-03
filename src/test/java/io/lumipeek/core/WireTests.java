package io.lumipeek.core;

import java.io.*;
import java.nio.file.Path;

/** Adversarial worker responses must never drive unbounded host allocation/decoding. */
public final class WireTests {
    private static int passed;
    private WireTests() {}
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void rejected(Checked task) throws Exception {
        try { task.run(); throw new AssertionError("malformed worker response accepted"); }
        catch (IOException | Failure expected) { passed++; }
    }
    public static void main(String[] args) throws Exception {
        rejected(() -> Wire.input(new byte[Wire.MAX_REQUEST + 1], Wire.MAX_REQUEST));
        byte[] hugeString = {127, -1, -1, -1};
        rejected(() -> Wire.string(new DataInputStream(new ByteArrayInputStream(hugeString))));
        rejected(() -> Wire.string(new DataInputStream(new ByteArrayInputStream(new byte[]{0,0,0,1,(byte) 255}))));
        rejected(() -> PreparedPhoto.read(new DataInputStream(new ByteArrayInputStream(new byte[]{127,-1,-1,-1,0,0,0,1}))));
        byte[] invalidPixels = Wire.write(out -> {
            out.writeInt(1024); out.writeInt(1024); out.writeInt(1); out.writeByte(0);
            out.writeInt(8); out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        }, 100);
        rejected(() -> PreparedPhoto.read(new DataInputStream(new ByteArrayInputStream(invalidPixels))));
        rejected(() -> Wire.bytes(new DataInputStream(new ByteArrayInputStream(hugeString)), 1024));
        rejected(() -> Wire.end(new DataInputStream(new ByteArrayInputStream(new byte[]{1}))));
        rejected(() -> Wire.write(out -> out.write(new byte[5]), 4));
        Path root = Path.of("build").toAbsolutePath();
        rejected(() -> IsolatedPhotos.candidate(root, "../outside.png"));
        rejected(() -> IsolatedPhotos.candidate(root, "C:outside.png"));
        rejected(() -> IsolatedPhotos.candidate(root, ""));
        rejected(() -> Wire.write(out -> Wire.string(out, "x".repeat(30001)), Wire.MAX_REQUEST));
        System.out.println("PASS: bounded IPC " + passed + " assertions");
    }
}
