package io.lumipeek.core;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Duration;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import static io.lumipeek.core.Failure.Code.*;

/** Built-in PNG/JPEG only; output is a fresh metadata-free, bounded PNG. */
public final class PhotoDecoder {
    public static final long MAX_PIXELS = 30_000_000;
    public byte[] decode(byte[] source, String extension, Duration budget) throws Failure {
        if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("decode budget");
        if (source.length == 0 || source.length > PhotoFolder.MAX_BYTES) throw new Failure(FILE_LIMIT);
        String format = switch (extension.toLowerCase(java.util.Locale.ROOT)) {
            case "png" -> "png";
            case "jpg", "jpeg" -> "jpeg";
            default -> throw new Failure(FORMAT_DENIED);
        };
        boolean png = source.length >= 8 && source[0] == (byte) 137 && source[1] == 80
                && source[2] == 78 && source[3] == 71 && source[4] == 13 && source[5] == 10
                && source[6] == 26 && source[7] == 10;
        boolean jpeg = source.length >= 3 && source[0] == (byte) 255
                && source[1] == (byte) 216 && source[2] == (byte) 255;
        if (!(format.equals("png") ? png : jpeg)) throw new Failure(FORMAT_DENIED);
        long start = System.nanoTime();
        // Select the JDK decoder explicitly; never invoke third-party registered readers.
        var readers = ImageIO.getImageReadersByFormatName(format);
        ImageReader reader = null;
        while (readers.hasNext()) {
            ImageReader candidate = readers.next();
            if (candidate.getClass().getModule().equals(ImageIO.class.getModule())) { reader = candidate; break; }
            candidate.dispose();
        }
        if (reader == null) throw new Failure(FORMAT_DENIED);
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            reader.setInput(input, true, true);
            int w = reader.getWidth(0), h = reader.getHeight(0);
            if (w < 1 || h < 1 || (long) w * h > MAX_PIXELS) throw new Failure(PIXEL_LIMIT);
            check(start, budget);
            var param = reader.getDefaultReadParam();
            int sample = Math.max(1, (int) Math.ceil(Math.max(w, h) / 1024.0));
            param.setSourceSubsampling(sample, sample, 0, 0);
            BufferedImage decoded = reader.read(0, param);
            check(start, budget);
            double scale = Math.min(1, 1024.0 / Math.max(decoded.getWidth(), decoded.getHeight()));
            BufferedImage clean = new BufferedImage(Math.max(1, (int) (decoded.getWidth() * scale)),
                    Math.max(1, (int) (decoded.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = clean.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, clean.getWidth(), clean.getHeight());
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(decoded, 0, 0, clean.getWidth(), clean.getHeight(), null);
            } finally { g.dispose(); decoded.flush(); }
            try (var output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(clean, "png", output)) throw new Failure(DECODE_FAILED);
                check(start, budget);
                return output.toByteArray();
            } finally { clean.flush(); }
        } catch (IOException | RuntimeException ex) { throw new Failure(DECODE_FAILED); }
        finally { reader.dispose(); }
    }
    private static void check(long start, Duration budget) throws Failure {
        if (Thread.currentThread().isInterrupted()) throw new Failure(CANCELLED);
        if (System.nanoTime() - start >= budget.toNanos()) throw new Failure(TIMEOUT);
    }
}
