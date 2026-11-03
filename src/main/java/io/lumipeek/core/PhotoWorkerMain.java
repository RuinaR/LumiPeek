package io.lumipeek.core;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;

/** One request per short-lived worker JVM. Never start this in the host JVM. */
public final class PhotoWorkerMain {
    private PhotoWorkerMain() {}
    public static void main(String[] args) {
        try {
            WorkerBridge.initialize(Path.of(args[1]));
            ImageIO.setUseCache(false);
            byte[] request = WorkerBridge.request0(Long.parseUnsignedLong(args[0]));
            byte[] response;
            try { response = handle(request); }
            catch (Failure ex) { response = error(ex.code()); }
            catch (Exception ex) { response = error(Failure.Code.WORK_FAILED); }
            finally { Arrays.fill(request, (byte) 0); }
            System.out.write(response); System.out.flush(); Arrays.fill(response, (byte) 0);
            System.exit(0); // one-shot JVM: image/native helper threads must not prolong its lifetime
        } catch (Throwable ex) {
            // No stack traces, image paths, model data or crash dumps in diagnostics.
            System.exit(2);
        }
    }
    private static byte[] error(Failure.Code code) throws Failure {
        return Wire.write(out -> { out.writeInt(Wire.MAGIC); Wire.string(out, code.name()); }, Wire.MAX_REPLY);
    }
    private static byte[] handle(byte[] request) throws Exception {
        try (DataInputStream in = Wire.input(request, Wire.MAX_REQUEST)) {
            if (in.readInt() != Wire.MAGIC) throw new IOException();
            int operation = in.readInt();
            long ms = in.readLong();
            if (ms < 1 || ms > 60000) throw new IOException();
            String dll = Wire.string(in), root = Wire.string(in);
            WindowsFiles.initialize(Path.of(dll));
            if (operation == Wire.GRANT) {
                Wire.end(in);
                long[] id = WindowsFiles.identify(root, ms);
                return Wire.write(out -> {
                    out.writeInt(Wire.MAGIC); Wire.string(out, "OK");
                    for (long value : id) out.writeLong(value);
                }, Wire.MAX_REPLY);
            }
            long[] id = {in.readLong(), in.readLong(), in.readLong()};
            if (operation == Wire.DISCOVER) {
                boolean recursive = in.readBoolean(); int limit = in.readInt(); Wire.end(in);
                String[] paths = WindowsFiles.discover(root, id, recursive, limit, ms);
                return Wire.write(out -> {
                    out.writeInt(Wire.MAGIC); Wire.string(out, "OK"); out.writeInt(paths.length);
                    for (String path : paths) Wire.string(out, path);
                }, Wire.MAX_REPLY);
            }
            if (operation != Wire.ANALYZE) throw new IOException();
            String relative = Wire.string(in); Wire.end(in);
            String name = Path.of(relative).getFileName().toString();
            int dot = name.lastIndexOf('.'); if (dot < 0) throw new IOException();
            byte[] original = WindowsFiles.read(root, id, relative, ms);
            byte[] clean;
            try { clean = new PhotoDecoder().decode(original, name.substring(dot + 1), Duration.ofMillis(ms)); }
            finally { Arrays.fill(original, (byte) 0); }
            try {
                // Re-open only our own generated PNG, inside the contained JVM.
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(clean));
                try {
                    int w = image.getWidth(), h = image.getHeight();
                    if (w < 1 || h < 1 || w > 1024 || h > 1024) throw new IOException();
                    byte[] rgb = new byte[w * h * 3]; int index = 0;
                    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                        int color = image.getRGB(x, y);
                        rgb[index++] = (byte) (color >> 16); rgb[index++] = (byte) (color >> 8); rgb[index++] = (byte) color;
                    }
                    try {
                        return Wire.write(out -> {
                            out.writeInt(Wire.MAGIC); Wire.string(out, "OK"); out.writeInt(w); out.writeInt(h);
                            out.writeInt(rgb.length); out.write(rgb); out.writeInt(clean.length); out.write(clean);
                        }, Wire.MAX_REPLY);
                    } finally { Arrays.fill(rgb, (byte) 0); }
                } finally { if (image != null) image.flush(); }
            } finally { Arrays.fill(clean, (byte) 0); }
        }
    }
}
