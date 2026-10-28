package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Developer-owned, client-only geometry calibration shared by every node of one kind. */
final class ElementSelectionCalibration {
    static final Calibration IDENTITY = new Calibration(0.0, 0.0, 1.0, 1.0);
    static final byte[] KINDS = {
            EditorProtocol.KIND_GROUP,
            EditorProtocol.KIND_RECTANGLE,
            EditorProtocol.KIND_FRAME,
            EditorProtocol.KIND_LINE,
            EditorProtocol.KIND_TEXT,
            EditorProtocol.KIND_IMAGE,
            EditorProtocol.KIND_GIF,
            EditorProtocol.KIND_ITEM,
            EditorProtocol.KIND_BLOCK,
            EditorProtocol.KIND_REGION
    };

    private final Map<Byte, Calibration> values = new LinkedHashMap<>();

    ElementSelectionCalibration() {
        resetAll();
    }

    Calibration get(byte kind) {
        return values.getOrDefault(kind, IDENTITY);
    }

    /**
     * The rectangle calibration compensates the legacy sizing, which trims the top of a
     * rectangle. A menu with exact rectangle sizing shows its authored geometry directly.
     */
    Calibration forNode(byte kind, boolean exactRectangles) {
        return exactRectangles && kind == EditorProtocol.KIND_RECTANGLE ? IDENTITY : get(kind);
    }

    void set(byte kind, double offsetRatioX, double offsetRatioY, double scaleX, double scaleY) {
        double normalizedScaleX = scale(scaleX);
        double normalizedScaleY = scale(scaleY);
        double normalizedRatioX = ratio(offsetRatioX);
        double normalizedRatioY = ratio(offsetRatioY);
        // The editor resizes from the bottom-right handle. At these two exact
        // ratios that handle collapses onto the element centre and has no
        // invertible size coordinate, so fall back to no offset on that axis.
        if (Math.abs(normalizedScaleX + 2.0 * normalizedRatioX) < 0.000001) normalizedRatioX = 0.0;
        if (Math.abs(normalizedScaleY - 2.0 * normalizedRatioY) < 0.000001) normalizedRatioY = 0.0;
        values.put(kind, new Calibration(normalizedRatioX, normalizedRatioY, normalizedScaleX, normalizedScaleY));
    }

    void reset(byte kind) {
        values.put(kind, defaultFor(kind));
    }

    void resetAll() {
        values.clear();
        for (byte kind : KINDS) values.put(kind, defaultFor(kind));
    }

    /** Calibrated defaults captured from the 26.1.2 release-validation client. */
    static Calibration defaultFor(byte kind) {
        return switch (kind) {
            case EditorProtocol.KIND_RECTANGLE -> new Calibration(0.0, -0.03, 1.0, 0.9);
            case EditorProtocol.KIND_LINE -> new Calibration(0.0, 0.0, 1.02, 0.1);
            case EditorProtocol.KIND_TEXT -> new Calibration(0.0, 0.5, 1.3, 1.3);
            case EditorProtocol.KIND_IMAGE, EditorProtocol.KIND_GIF -> new Calibration(0.12, 0.25, 1.0, 1.0);
            case EditorProtocol.KIND_REGION -> new Calibration(0.0, -0.04, 1.01, 0.92);
            default -> IDENTITY;
        };
    }

    void load(Path file) {
        resetAll();
        if (!Files.isRegularFile(file)) return;
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
            for (byte kind : KINDS) {
                String prefix = key(kind) + ".";
                Calibration fallback = get(kind);
                set(kind,
                        number(properties, prefix + "offset-ratio-x", fallback.offsetRatioX),
                        number(properties, prefix + "offset-ratio-y", fallback.offsetRatioY),
                        number(properties, prefix + "scale-x", fallback.scaleX),
                        number(properties, prefix + "scale-y", fallback.scaleY));
            }
        } catch (IOException ignored) {
            // Local calibration failure cannot prevent a server document opening.
        }
    }

    void save(Path file) {
        Properties properties = new Properties();
        for (byte kind : KINDS) {
            String prefix = key(kind) + ".";
            Calibration value = get(kind);
            properties.setProperty(prefix + "offset-ratio-x", Double.toString(value.offsetRatioX));
            properties.setProperty(prefix + "offset-ratio-y", Double.toString(value.offsetRatioY));
            properties.setProperty(prefix + "scale-x", Double.toString(value.scaleX));
            properties.setProperty(prefix + "scale-y", Double.toString(value.scaleY));
        }
        try {
            Files.createDirectories(file.getParent());
            try (var writer = Files.newBufferedWriter(file)) {
                properties.store(writer,
                        "ArcMenu Editor developer selection calibration by element kind (size-relative offset ratio, size multiplier)");
            }
        } catch (IOException ignored) {
            // Persistence is optional and cannot make the editor unusable.
        }
    }

    static String key(byte kind) {
        return switch (kind) {
            case EditorProtocol.KIND_GROUP -> "group";
            case EditorProtocol.KIND_RECTANGLE -> "rectangle";
            case EditorProtocol.KIND_FRAME -> "frame";
            case EditorProtocol.KIND_LINE -> "line";
            case EditorProtocol.KIND_TEXT -> "text";
            case EditorProtocol.KIND_IMAGE -> "image";
            case EditorProtocol.KIND_GIF -> "gif";
            case EditorProtocol.KIND_ITEM -> "item";
            case EditorProtocol.KIND_BLOCK -> "block";
            case EditorProtocol.KIND_REGION -> "interaction-region";
            default -> "kind-" + kind;
        };
    }

    private static double number(Properties properties, String key, double fallback) {
        try {
            return Double.parseDouble(properties.getProperty(key, Double.toString(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double ratio(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.abs(value) < 0.0000005 ? 0.0 : Math.max(-100.0, Math.min(100.0, value));
    }

    private static double scale(double value) {
        if (!Double.isFinite(value)) return 1.0;
        return Math.max(0.01, Math.min(100.0, value));
    }

    record Calibration(double offsetRatioX, double offsetRatioY, double scaleX, double scaleY) {
        double proxyX(double serverX, double serverWidth) { return serverX + serverWidth * offsetRatioX; }
        double proxyY(double serverY, double serverHeight) { return serverY + serverHeight * offsetRatioY; }
        double proxyWidth(double serverWidth) { return serverWidth * scaleX; }
        double proxyHeight(double serverHeight) { return serverHeight * scaleY; }

        /** Inverts the variable-width calibrated position of the right resize handle. */
        double serverRightX(double nodeX, double proxyRightX) {
            return nodeX + (proxyRightX - nodeX) / (scaleX + 2.0 * offsetRatioX);
        }

        /** Inverts the variable-height calibrated position of the bottom resize handle. */
        double serverBottomY(double nodeY, double proxyBottomY) {
            return nodeY + (proxyBottomY - nodeY) / (scaleY - 2.0 * offsetRatioY);
        }
    }
}
