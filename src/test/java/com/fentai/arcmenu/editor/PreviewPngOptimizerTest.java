package com.fentai.arcmenu.editor;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class PreviewPngOptimizerTest {
    @Test void preserves720pDimensionsAndExactFlatColorsIncludingTransparency() throws Exception {
        BufferedImage source = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
        int[] colors = {0xff14202e, 0xff62d1ff, 0xffffffff, 0xff000000, 0x80445566, 0x00445566};
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++)
            source.setRGB(x, y, colors[(x / 80 + y / 80) % colors.length]);
        byte[] input = png(source), optimized = PreviewPngOptimizer.optimize(input);
        BufferedImage decoded = read(optimized);
        assertEquals(1280, decoded.getWidth()); assertEquals(720, decoded.getHeight());
        assertEquals(3, optimized[25]);
        assertTrue(optimized.length < input.length);
        assertPixelsEqual(source, decoded);
    }

    @Test void usesOneBitPaletteForExactTwoColorArtwork() throws Exception {
        BufferedImage source = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 128; x++)
            source.setRGB(x, y, (x + y) % 2 == 0 ? 0xff121212 : 0xffededed);
        byte[] optimized = PreviewPngOptimizer.optimize(png(source));
        assertEquals(1, optimized[24]); assertEquals(3, optimized[25]);
        assertPixelsEqual(source, read(optimized));
    }

    @Test void stripsTextColorProfileAndOtherAncillaryMetadata() throws Exception {
        BufferedImage source = new BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(5, 5, 0x4088aacc);
        byte[] input = withChunk(png(source), "tEXt", "Description\0Preview metadata".getBytes(StandardCharsets.US_ASCII));
        input = withChunk(input, "gAMA", new byte[]{0, 0, (byte) 177, (byte) 143});
        byte[] optimized = PreviewPngOptimizer.optimize(input);
        for (String chunk : chunks(optimized)) assertTrue(List.of("IHDR", "PLTE", "tRNS", "IDAT", "IEND").contains(chunk), chunk);
        assertPixelsEqual(source, read(optimized));
        assertTrue(optimized.length <= input.length);
    }

    @Test void adaptsSmoothHighColorArtworkOnlyWithinMeasuredError() throws Exception {
        BufferedImage source = gradient(false);
        byte[] input = png(source), optimized = PreviewPngOptimizer.optimize(input);
        assertEquals(3, optimized[25]);
        assertTrue(optimized.length < input.length);
        assertBoundedError(source, read(optimized));
    }

    @Test void adaptivePalettePreservesEveryAlphaValue() throws Exception {
        BufferedImage source = gradient(true);
        byte[] optimized = PreviewPngOptimizer.optimize(png(source));
        assertEquals(3, optimized[25]);
        assertBoundedError(source, read(optimized));
    }

    @Test void retainsLosslessPixelsWhenHighColorNoiseWouldExceedQualityLimit() throws Exception {
        BufferedImage source = new BufferedImage(128, 96, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < 96; y++) for (int x = 0; x < 128; x++) source.setRGB(x, y, random.nextInt(1 << 24));
        byte[] input = png(source), optimized = PreviewPngOptimizer.optimize(input);
        assertEquals(2, optimized[25]);
        assertTrue(optimized.length <= input.length);
        assertPixelsEqual(source, read(optimized));
    }

    @Test void neverEnlargesAlreadySmallPngsAndRejectsUnreadableInput() throws Exception {
        for (int size : new int[]{1, 2, 4, 8}) {
            BufferedImage source = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            source.setRGB(0, 0, 0x80000000);
            byte[] input = png(source), optimized = PreviewPngOptimizer.optimize(input);
            assertTrue(optimized.length <= input.length);
            assertPixelsEqual(source, read(optimized));
        }
        assertThrows(IOException.class, () -> PreviewPngOptimizer.optimize(new byte[]{1, 2, 3}));
        assertThrows(IOException.class, () -> PreviewPngOptimizer.optimize(null));
    }

    private static BufferedImage gradient(boolean alpha) {
        BufferedImage image = new BufferedImage(256, 192, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            image.setRGB(x, y, (alpha && x >= 128 ? 127 : 255) << 24 | (80 + x % 80) << 16 | (80 + y % 80) << 8 | 120);
        return image;
    }

    private static byte[] png(BufferedImage source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", bytes));
        return bytes.toByteArray();
    }

    private static BufferedImage read(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static void assertPixelsEqual(BufferedImage source, BufferedImage decoded) {
        assertArrayEquals(source.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth()),
                decoded.getRGB(0, 0, decoded.getWidth(), decoded.getHeight(), null, 0, decoded.getWidth()));
    }

    private static void assertBoundedError(BufferedImage source, BufferedImage decoded) {
        long squaredError = 0;
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int first = source.getRGB(x, y), second = decoded.getRGB(x, y);
            assertEquals(first >>> 24, second >>> 24);
            for (int shift : new int[]{16, 8, 0}) {
                int difference = (first >>> shift & 255) - (second >>> shift & 255);
                assertTrue(Math.abs(difference) <= 12, "Palette error exceeded the per-channel limit");
                squaredError += (long) difference * difference;
            }
        }
        assertTrue((double) squaredError / (source.getWidth() * source.getHeight() * 3L) <= 16.0);
    }

    private static byte[] withChunk(byte[] png, String kind, byte[] body) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(png, 0, 33);
        DataOutputStream output = new DataOutputStream(result);
        output.writeInt(body.length); byte[] type = kind.getBytes(StandardCharsets.US_ASCII);
        output.write(type); output.write(body);
        CRC32 crc = new CRC32(); crc.update(type); crc.update(body); output.writeInt((int) crc.getValue());
        result.write(png, 33, png.length - 33);
        return result.toByteArray();
    }

    private static List<String> chunks(byte[] png) {
        List<String> result = new ArrayList<>();
        for (int offset = 8; offset < png.length;) {
            int length = (png[offset] & 255) << 24 | (png[offset + 1] & 255) << 16 | (png[offset + 2] & 255) << 8 | png[offset + 3] & 255;
            result.add(new String(png, offset + 4, 4, StandardCharsets.US_ASCII));
            offset += length + 12;
        }
        return result;
    }
}
