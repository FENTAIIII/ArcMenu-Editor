package com.fentai.arcmenu.editor;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/** Optimizes captured PNG previews without changing their dimensions or alpha. */
public final class PreviewPngOptimizer {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final double MAX_RGB_RMS = 4.0;
    private static final int MAX_CHANNEL_ERROR = 12;

    private PreviewPngOptimizer() {}

    public static byte[] optimize(byte[] png) throws IOException {
        byte[] best = stripMetadata(png);
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
        if (source == null) throw new IOException("Preview is not a readable PNG");
        int width = source.getWidth(), height = source.getHeight();
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        boolean opaque = true;
        Map<Integer, Integer> exact = new LinkedHashMap<>();
        for (int pixel : pixels) {
            opaque &= pixel >>> 24 == 255;
            if (exact.size() <= 256) exact.putIfAbsent(pixel, exact.size());
        }
        BufferedImage lossless = new BufferedImage(width, height,
                opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        lossless.setRGB(0, 0, width, height, pixels, 0, width);
        best = smaller(best, encode(lossless));
        if (exact.size() <= 256) {
            int[] palette = exact.keySet().stream().mapToInt(Integer::intValue).toArray();
            return smaller(best, encode(indexed(width, height, pixels, palette, exact)));
        }
        BufferedImage adaptive = adaptivePalette(width, height, pixels);
        return adaptive == null ? best : smaller(best, encode(adaptive));
    }

    private static byte[] smaller(byte[] current, byte[] candidate) {
        return candidate.length < current.length ? candidate : current;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameter = writer.getDefaultWriteParam();
            if (parameter.canWriteCompressed()) {
                parameter.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameter.setCompressionQuality(0.0f);
            }
            writer.write(null, new IIOImage(image, null, null), parameter);
            output.flush();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static BufferedImage indexed(int width, int height, int[] pixels, int[] palette,
                                         Map<Integer, Integer> indices) {
        int depth = palette.length <= 2 ? 1 : palette.length <= 4 ? 2 : palette.length <= 16 ? 4 : 8;
        byte[] red = new byte[palette.length], green = new byte[palette.length];
        byte[] blue = new byte[palette.length], alpha = new byte[palette.length];
        for (int i = 0; i < palette.length; i++) {
            red[i] = (byte) (palette[i] >>> 16);
            green[i] = (byte) (palette[i] >>> 8);
            blue[i] = (byte) palette[i];
            alpha[i] = (byte) (palette[i] >>> 24);
        }
        IndexColorModel model = new IndexColorModel(depth, palette.length, red, green, blue, alpha);
        WritableRaster raster = model.createCompatibleWritableRaster(width, height);
        for (int i = 0; i < pixels.length; i++) raster.setSample(i % width, i / width, 0, indices.get(pixels[i]));
        return new BufferedImage(model, raster, false, null);
    }

    private static BufferedImage adaptivePalette(int width, int height, int[] pixels) {
        Map<Integer, Bin> histogram = new HashMap<>();
        for (int pixel : pixels) {
            int key = (pixel >>> 24 << 15) | ((pixel >>> 19 & 31) << 10)
                    | ((pixel >>> 11 & 31) << 5) | (pixel >>> 3 & 31);
            histogram.computeIfAbsent(key, ignored -> new Bin(pixel >>> 24)).add(pixel);
        }
        Map<Integer, List<Bin>> alphas = new HashMap<>();
        for (Bin bin : histogram.values()) alphas.computeIfAbsent(bin.alpha, ignored -> new ArrayList<>()).add(bin);
        List<Box> boxes = new ArrayList<>();
        alphas.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> boxes.add(new Box(entry.getValue())));
        while (boxes.size() < 256) {
            int split = -1;
            double score = -1;
            for (int i = 0; i < boxes.size(); i++) {
                Box box = boxes.get(i);
                if (box.bins.size() > 1 && box.score > score) { split = i; score = box.score; }
            }
            if (split < 0) break;
            Box[] halves = boxes.remove(split).split();
            boxes.add(halves[0]); boxes.add(halves[1]);
        }
        if (boxes.size() > 256) return null;
        int[] palette = new int[boxes.size()];
        Map<Integer, List<Integer>> byAlpha = new HashMap<>();
        for (int i = 0; i < boxes.size(); i++) {
            palette[i] = boxes.get(i).color();
            byAlpha.computeIfAbsent(palette[i] >>> 24, ignored -> new ArrayList<>()).add(i);
        }
        Map<Integer, Integer> indices = new HashMap<>();
        long squaredError = 0;
        for (int pixel : pixels) {
            Integer index = indices.get(pixel);
            if (index == null) {
                int best = -1, error = Integer.MAX_VALUE;
                for (int candidate : byAlpha.get(pixel >>> 24)) {
                    int distance = distance(pixel, palette[candidate]);
                    if (distance < error) { best = candidate; error = distance; }
                }
                index = best;
                indices.put(pixel, index);
            }
            int color = palette[index];
            for (int shift : new int[]{16, 8, 0}) {
                int difference = (pixel >>> shift & 255) - (color >>> shift & 255);
                if (Math.abs(difference) > MAX_CHANNEL_ERROR) return null;
                squaredError += (long) difference * difference;
            }
        }
        if ((double) squaredError / (pixels.length * 3L) > MAX_RGB_RMS * MAX_RGB_RMS) return null;
        return indexed(width, height, pixels, palette, indices);
    }

    private static int distance(int first, int second) {
        int red = (first >>> 16 & 255) - (second >>> 16 & 255);
        int green = (first >>> 8 & 255) - (second >>> 8 & 255);
        int blue = (first & 255) - (second & 255);
        return red * red + green * green + blue * blue;
    }

    private static byte[] stripMetadata(byte[] png) throws IOException {
        if (png == null || png.length < 33 || !Arrays.equals(SIGNATURE, Arrays.copyOf(png, 8)))
            throw new IOException("Preview is not a PNG");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(png.length);
        bytes.write(SIGNATURE);
        int offset = 8;
        boolean ended = false;
        while (offset < png.length) {
            if (png.length - offset < 12) throw new IOException("PNG chunk is truncated");
            long length = (long) (png[offset] & 255) << 24 | (long) (png[offset + 1] & 255) << 16
                    | (long) (png[offset + 2] & 255) << 8 | png[offset + 3] & 255;
            if (length > png.length - offset - 12) throw new IOException("PNG chunk is truncated");
            String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals("IHDR") || type.equals("PLTE") || type.equals("tRNS") || type.equals("IDAT") || type.equals("IEND"))
                bytes.write(png, offset, (int) length + 12);
            offset += (int) length + 12;
            if (type.equals("IEND")) { ended = true; break; }
        }
        if (!ended) throw new IOException("PNG has no end chunk");
        return bytes.toByteArray();
    }

    private static final class Bin {
        final int alpha;
        long red, green, blue, count;
        Bin(int alpha) { this.alpha = alpha; }
        void add(int pixel) {
            red += pixel >>> 16 & 255; green += pixel >>> 8 & 255; blue += pixel & 255; count++;
        }
        int channel(int channel) { return (int) ((channel == 0 ? red : channel == 1 ? green : blue) / count); }
    }

    private static final class Box {
        final List<Bin> bins;
        final int axis;
        final long count;
        final double score;
        Box(List<Bin> bins) {
            this.bins = bins;
            int[] min = {255, 255, 255}, max = {0, 0, 0};
            long total = 0;
            for (Bin bin : bins) {
                total += bin.count;
                for (int channel = 0; channel < 3; channel++) {
                    int value = bin.channel(channel);
                    min[channel] = Math.min(min[channel], value); max[channel] = Math.max(max[channel], value);
                }
            }
            count = total;
            int chosen = 0;
            for (int channel = 1; channel < 3; channel++) if (max[channel] - min[channel] > max[chosen] - min[chosen]) chosen = channel;
            axis = chosen;
            int range = max[axis] - min[axis];
            score = (double) range * range * count;
        }
        Box[] split() {
            bins.sort(Comparator.comparingInt((Bin bin) -> bin.channel(axis))
                    .thenComparingInt(bin -> bin.channel((axis + 1) % 3)).thenComparingInt(bin -> bin.channel((axis + 2) % 3)));
            long cumulative = 0;
            int split = 1;
            for (int i = 0; i < bins.size() - 1; i++) {
                cumulative += bins.get(i).count; split = i + 1;
                if (cumulative >= (count + 1) / 2) break;
            }
            return new Box[]{new Box(new ArrayList<>(bins.subList(0, split))), new Box(new ArrayList<>(bins.subList(split, bins.size())))};
        }
        int color() {
            long red = 0, green = 0, blue = 0;
            for (Bin bin : bins) { red += bin.red; green += bin.green; blue += bin.blue; }
            return bins.get(0).alpha << 24 | (int) ((red + count / 2) / count) << 16
                    | (int) ((green + count / 2) / count) << 8 | (int) ((blue + count / 2) / count);
        }
    }
}
