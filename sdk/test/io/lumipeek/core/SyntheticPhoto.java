package io.lumipeek.core;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/** Test fixture only; production images are created by the isolated worker. */
public final class SyntheticPhoto {
    private SyntheticPhoto() {}
    public static PreparedPhoto create() throws Exception {
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB), "png", png);
        byte[] clean = png.toByteArray();
        byte[] frame = Wire.write(out -> {
            out.writeInt(2); out.writeInt(2); out.writeInt(12); out.write(new byte[12]);
            out.writeInt(clean.length); out.write(clean);
        },1024);
        return PreparedPhoto.read(Wire.input(frame,1024));
    }
}
