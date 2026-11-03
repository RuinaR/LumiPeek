package io.lumipeek.core;

import java.io.*;

/** Already decoded in the isolated process. Host UI must use RGB, never decode PNG again. */
public final class PreparedPhoto implements AutoCloseable {
    private final int width, height;
    private final byte[] rgb, png;
    private boolean closed;
    private PreparedPhoto(int width, int height, byte[] rgb, byte[] png) {
        this.width = width; this.height = height; this.rgb = rgb; this.png = png;
    }
    static PreparedPhoto read(DataInputStream in) throws IOException {
        int w = in.readInt(), h = in.readInt();
        if (w < 1 || h < 1 || w > 1024 || h > 1024) throw new IOException();
        byte[] rgb = Wire.bytes(in, 1024 * 1024 * 3), png = Wire.bytes(in, 4 * 1024 * 1024);
        if (rgb.length != (long) w * h * 3 || png.length < 8
                || png[0] != (byte) 137 || png[1] != 80 || png[2] != 78 || png[3] != 71
                || png[4] != 13 || png[5] != 10 || png[6] != 26 || png[7] != 10) throw new IOException();
        return new PreparedPhoto(w, h, rgb, png);
    }
    public int width() { return width; }
    public int height() { return height; }
    public synchronized byte[] copyRgb() { requireOpen(); return rgb.clone(); }
    public synchronized byte[] copyPng() { requireOpen(); return png.clone(); }
    private void requireOpen() { if (closed) throw new IllegalStateException("PHOTO_CLOSED"); }
    @Override public synchronized void close() {
        closed = true; java.util.Arrays.fill(rgb, (byte) 0); java.util.Arrays.fill(png, (byte) 0);
    }
}
