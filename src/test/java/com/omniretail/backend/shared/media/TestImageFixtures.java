package com.omniretail.backend.shared.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Imagenes diminutas generadas localmente o codificadas como fixtures WebP publicas de 1 px. */
public final class TestImageFixtures {

    private static final String WEBP_VP8 =
            "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA";
    private static final String WEBP_VP8L =
            "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";
    private TestImageFixtures() {}

    public static byte[] png() {
        return image("png", 2, 2, true);
    }

    public static byte[] jpeg() {
        return image("jpeg", 2, 1, false);
    }

    public static byte[] jpeg(int width, int height) {
        return image("jpeg", width, height, false);
    }

    public static byte[] webpVp8() {
        return Base64.getDecoder().decode(WEBP_VP8);
    }

    public static byte[] webpVp8l() {
        return Base64.getDecoder().decode(WEBP_VP8L);
    }

    public static byte[] webpVp8xAlpha() {
        byte[] lossless = webpVp8l();
        byte[] result = new byte[lossless.length + 18];
        System.arraycopy(new byte[] {'R', 'I', 'F', 'F'}, 0, result, 0, 4);
        putLe32(result, 4, result.length - 8);
        System.arraycopy(new byte[] {'W', 'E', 'B', 'P', 'V', 'P', '8', 'X'}, 0, result, 8, 8);
        putLe32(result, 16, 10);
        result[20] = 0x10; // alpha; canvas 1 x 1 (width/height minus one remain zero)
        System.arraycopy(lossless, 12, result, 30, lossless.length - 12);
        return result;
    }

    public static byte[] webpVp8WithDimensions(int width, int height) {
        if (width <= 0 || width > 0x3fff || height <= 0 || height > 0x3fff) {
            throw new IllegalArgumentException("Dimensiones VP8 fuera de rango.");
        }
        byte[] result = webpVp8();
        int frame = findChunkData(result, "VP8 ");
        putLe16(result, frame + 6, width);
        putLe16(result, frame + 8, height);
        return result;
    }

    public static byte[] pngWithDimensions(int width, int height) {
        byte[] value = png();
        putBe32(value, 16, width);
        putBe32(value, 20, height);
        CRC32 crc = new CRC32();
        crc.update(value, 12, 17);
        putBe32(value, 29, (int) crc.getValue());
        return value;
    }

    public static byte[] jpegWithOrientation(int orientation) {
        byte[] jpeg = jpeg();
        byte[] exif = new byte[] {
            (byte) 0xff, (byte) 0xe1, 0x00, 0x22,
            'E', 'x', 'i', 'f', 0, 0,
            'I', 'I', 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
            (byte) orientation, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        };
        byte[] result = new byte[jpeg.length + exif.length];
        System.arraycopy(jpeg, 0, result, 0, 2);
        System.arraycopy(exif, 0, result, 2, exif.length);
        System.arraycopy(jpeg, 2, result, 2 + exif.length, jpeg.length - 2);
        return result;
    }

    public static byte[] append(byte[] value, byte... suffix) {
        byte[] result = new byte[value.length + suffix.length];
        System.arraycopy(value, 0, result, 0, value.length);
        System.arraycopy(suffix, 0, result, value.length, suffix.length);
        return result;
    }

    private static byte[] image(String format, int width, int height, boolean alpha) {
        try {
            BufferedImage image = new BufferedImage(
                    width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(alpha ? new Color(20, 120, 220, 128) : new Color(20, 120, 220));
            graphics.fillRect(0, 0, width, height);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, output)) throw new IllegalStateException("No ImageIO writer: " + format);
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void putBe32(byte[] value, int offset, int number) {
        value[offset] = (byte) (number >>> 24);
        value[offset + 1] = (byte) (number >>> 16);
        value[offset + 2] = (byte) (number >>> 8);
        value[offset + 3] = (byte) number;
    }

    private static void putLe32(byte[] value, int offset, int number) {
        value[offset] = (byte) number;
        value[offset + 1] = (byte) (number >>> 8);
        value[offset + 2] = (byte) (number >>> 16);
        value[offset + 3] = (byte) (number >>> 24);
    }

    private static void putLe16(byte[] value, int offset, int number) {
        value[offset] = (byte) number;
        value[offset + 1] = (byte) (number >>> 8);
    }

    private static int findChunkData(byte[] value, String chunk) {
        byte[] expected = chunk.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int index = 12; index + 8 <= value.length;) {
            boolean match = true;
            for (int part = 0; part < 4; part++) match &= value[index + part] == expected[part];
            int length = (value[index + 4] & 0xff)
                    | ((value[index + 5] & 0xff) << 8)
                    | ((value[index + 6] & 0xff) << 16)
                    | ((value[index + 7] & 0xff) << 24);
            if (match) return index + 8;
            index += 8 + length + (length & 1);
        }
        throw new IllegalArgumentException("Chunk WebP no encontrado: " + chunk);
    }
}
