package com.fentai.arcmenu.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class EditorLayout {
    public static final int HEADER_HEIGHT = 24;
    public static final int MIN_VIEW_WIDTH = 320;
    public static final int MIN_VIEW_HEIGHT = 180;
    private int toolsWidth = 92;
    private int inspectorWidth = 240;
    private int templatesHeight = 120;
    private double virtualScreenScale = 1.0;
    private double virtualScreenOffsetX;
    private double virtualScreenOffsetY;

    public Layout calculate(int width, int height) {
        int maxInspector = Math.max(220, width - toolsWidth - MIN_VIEW_WIDTH - 12);
        inspectorWidth = clamp(inspectorWidth, 240, maxInspector);
        int maxTemplates = Math.max(120, height - HEADER_HEIGHT - MIN_VIEW_HEIGHT - 8);
        templatesHeight = clamp(templatesHeight, 120, maxTemplates);

        Rect tools = new Rect(0, HEADER_HEIGHT, toolsWidth, height - HEADER_HEIGHT);
        Rect inspector = new Rect(width - inspectorWidth, HEADER_HEIGHT, inspectorWidth, height - HEADER_HEIGHT);
        Rect templates = new Rect(toolsWidth, height - templatesHeight, width - toolsWidth - inspectorWidth, templatesHeight);
        Rect host = new Rect(toolsWidth, HEADER_HEIGHT, width - toolsWidth - inspectorWidth, height - HEADER_HEIGHT - templatesHeight);
        Rect viewport = fit16By9(host.inset(7));
        int managerHeight = Math.max(160, (height - HEADER_HEIGHT) * 46 / 100);
        Rect manager = new Rect(inspector.x, inspector.y, inspector.width, managerHeight);
        Rect properties = new Rect(inspector.x, inspector.y + managerHeight, inspector.width, inspector.height - managerHeight);
        return new Layout(new Rect(0, 0, width, HEADER_HEIGHT), tools, host, viewport, templates, manager, properties);
    }

    public int toolsWidth() { return toolsWidth; }
    public int inspectorWidth() { return inspectorWidth; }
    public int templatesHeight() { return templatesHeight; }
    public double virtualScreenScale() { return virtualScreenScale; }
    public double virtualScreenOffsetX() { return virtualScreenOffsetX; }
    public double virtualScreenOffsetY() { return virtualScreenOffsetY; }
    public void toolsWidth(int value) { toolsWidth = clamp(value, 92, 180); }
    public void inspectorWidth(int value) { inspectorWidth = clamp(value, 240, 520); }
    public void templatesHeight(int value) { templatesHeight = clamp(value, 120, 360); }
    public void virtualScreenScale(double value) { virtualScreenScale = clamp(value, 0.1, 4.0); }
    public void virtualScreenOffsetX(double value) { virtualScreenOffsetX = clamp(value, -4096.0, 4096.0); }
    public void virtualScreenOffsetY(double value) { virtualScreenOffsetY = clamp(value, -4096.0, 4096.0); }

    /** Calibrated 16:9 menu plane inside the full 16:9 game viewport. */
    public ScreenRect virtualScreen(Layout layout) {
        Rect viewport = layout.viewport();
        double width = viewport.width() * virtualScreenScale;
        double height = viewport.height() * virtualScreenScale;
        return new ScreenRect(
                viewport.x() + (viewport.width() - width) / 2.0 + virtualScreenOffsetX,
                viewport.y() + (viewport.height() - height) / 2.0 - virtualScreenOffsetY,
                width,
                height
        );
    }

    public void load(Path file) {
        if (!Files.isRegularFile(file)) return;
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            values.load(reader);
            toolsWidth(Integer.parseInt(values.getProperty("tools-width", Integer.toString(toolsWidth))));
            inspectorWidth(Integer.parseInt(values.getProperty("inspector-width", Integer.toString(inspectorWidth))));
            templatesHeight(Integer.parseInt(values.getProperty("templates-height", Integer.toString(templatesHeight))));
            virtualScreenScale(Double.parseDouble(values.getProperty("virtual-screen-scale", Double.toString(virtualScreenScale))));
            virtualScreenOffsetX(Double.parseDouble(values.getProperty("virtual-screen-offset-x", Double.toString(virtualScreenOffsetX))));
            virtualScreenOffsetY(Double.parseDouble(values.getProperty("virtual-screen-offset-y", Double.toString(virtualScreenOffsetY))));
        } catch (IOException | NumberFormatException ignored) {
            // A malformed local preference must never prevent opening a server document.
        }
    }

    public void save(Path file) {
        Properties values = new Properties();
        values.setProperty("tools-width", Integer.toString(toolsWidth));
        values.setProperty("inspector-width", Integer.toString(inspectorWidth));
        values.setProperty("templates-height", Integer.toString(templatesHeight));
        values.setProperty("virtual-screen-scale", Double.toString(virtualScreenScale));
        values.setProperty("virtual-screen-offset-x", Double.toString(virtualScreenOffsetX));
        values.setProperty("virtual-screen-offset-y", Double.toString(virtualScreenOffsetY));
        try {
            Files.createDirectories(file.getParent());
            try (var writer = Files.newBufferedWriter(file)) {
                values.store(writer, "ArcMenu Editor local layout and virtual-screen calibration");
            }
        } catch (IOException ignored) {
            // Layout persistence is optional and cannot make the editor unusable.
        }
    }

    public static Rect fit16By9(Rect host) {
        // The editor contract requires an exact 16:9 game opening. Integer GUI
        // coordinates can only guarantee that when both sides use one shared unit.
        int unit = Math.max(1, Math.min(host.width / 16, host.height / 9));
        int width = unit * 16;
        int height = unit * 9;
        return new Rect(host.x + (host.width - width) / 2, host.y + (host.height - height) / 2, width, height);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(value, max));
    }

    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double px, double py) { return px >= x && px < right() && py >= y && py < bottom(); }
        public Rect inset(int amount) {
            return new Rect(x + amount, y + amount, Math.max(1, width - amount * 2), Math.max(1, height - amount * 2));
        }
    }

    public record ScreenRect(double x, double y, double width, double height) {
        public double right() { return x + width; }
        public double bottom() { return y + height; }
        public boolean contains(double px, double py) { return px >= x && px < right() && py >= y && py < bottom(); }
    }

    public record Layout(Rect header, Rect tools, Rect viewportHost, Rect viewport,
                         Rect templates, Rect manager, Rect properties) {}
}
