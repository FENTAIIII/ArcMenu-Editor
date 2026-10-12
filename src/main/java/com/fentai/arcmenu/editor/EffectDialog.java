package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EffectProtocol;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.function.Consumer;

/** Keyframe values, interpolation/velocity handles, layer timing, media/action clips and work area. */
public final class EffectDialog extends Screen implements EffectWorkspaceChild {
    private final Screen parent;
    private final EditorState scene;
    private final EffectState state = ArcMenuEditorClient.EFFECT;
    private final String mode;
    private String target;
    private String property;
    private final int initialFrame;
    private final EffectProtocol.Key initialKey;
    private final EffectProtocol.Cue initialCue;
    private final Map<String, EditBox> fields = new LinkedHashMap<>();
    private final Map<String, String> choices = new HashMap<>();
    private final List<String> labels = new ArrayList<>();
    private int left, top, boxWidth, rowHeight;
    private EditorLayout.Rect graph;
    private int handle;
    private boolean speedGraph;
    private String error = "";
    private final EffectPopup popup = new EffectPopup();
    @Override public EffectEditorScreen workspace() { return parent instanceof EffectEditorScreen screen ? screen : null; }
    EffectDialog(Screen parent, EditorState scene, String mode, String target, String property, int frame, EffectProtocol.Key key, EffectProtocol.Cue cue) {
        super(EditorI18n.component("arcmenu_editor.effect." + mode));
        this.parent = parent; this.scene = scene; this.mode = mode; this.target = target; this.property = property;
        this.initialFrame = frame; this.initialKey = key; this.initialCue = cue;
    }
    @Override protected void init() {
        var preserved = new HashMap<String, String>(); fields.forEach((key, box) -> preserved.put(key, box.getValue()));
        fields.clear(); labels.clear(); clearWidgets();
        boxWidth = Math.min(500, width - 24); left = (width - boxWidth) / 2;
        int count = mode.equals("key") ? 10 : mode.equals("cue") ? 10 : mode.equals("layer") ? 5 : mode.equals("settings") ? 5 : 2;
        rowHeight = Math.max(17, Math.min(24, (height - (mode.equals("key") ? 158 : 95)) / Math.max(1, count)));
        int body = count * rowHeight + (mode.equals("key") ? 125 : 42);
        top = Math.max(8, (height - body) / 2);
        if (mode.equals("key")) {
            var nodes = scene.snapshot() == null ? List.<EditorProtocol.NodeSnapshot>of() : scene.snapshot().frontend();
            if (target.isEmpty() && !nodes.isEmpty()) target = nodes.getFirst().id();
            choose("target", target, nodes.stream().map(EditorProtocol.NodeSnapshot::id).toList(), value -> { target = value; property = ""; init(); });
            var node = scene.find(target);
            var properties = node == null ? List.<EditorProtocol.PropertySnapshot>of() : node.properties().stream().filter(p -> EffectPropertyRules.animated(node.kind(),p.key())).toList();
            if (property.isEmpty() && !properties.isEmpty()) property = properties.getFirst().key();
            choose("property", property, properties.stream().map(EditorProtocol.PropertySnapshot::key).toList(), value -> {
                property = value;
                var box = fields.get("value"); if (box != null) box.setValue(defaultValue());
            });
            field("frame", Integer.toString(initialFrame));
            field("value", initialKey == null ? defaultValue() : initialKey.value());
            choose("interpolation", initialKey == null ? "linear" : initialKey.interpolation(), List.of("linear", "hold", "bezier", "auto-bezier", "ease-in", "ease-out", "ease-in-out"), ignored -> {});
            field("x1", Double.toString(initialKey == null ? 1.0 / 3 : initialKey.x1()));
            field("y1", Double.toString(initialKey == null ? 0 : initialKey.y1()));
            field("x2", Double.toString(initialKey == null ? 2.0 / 3 : initialKey.x2()));
            field("y2", Double.toString(initialKey == null ? 1 : initialKey.y2()));
            choose("graph-mode", speedGraph ? "speed" : "value", List.of("value", "speed"), value -> speedGraph = value.equals("speed"));
            graph = new EditorLayout.Rect(left + 38, top + 26 + labels.size() * rowHeight, boxWidth - 76, 86);
        } else if (mode.equals("cue")) {
            field("id", initialCue == null ? "cue-" + initialFrame + "-" + state.snapshot().cues().size() : initialCue.id());
            field("frame", Integer.toString(initialFrame));
            choose("kind", initialCue == null ? "sound" : initialCue.kind(), List.of("sound", "nbs", "action", "command", "console"), ignored -> {});
            field("source", initialCue == null ? "minecraft:block.note_block.chime" : initialCue.source());
            field("end", Integer.toString(initialCue == null ? -1 : initialCue.end()));
            field("volume", Double.toString(initialCue == null ? 1 : initialCue.volume()));
            field("pitch", Double.toString(initialCue == null ? 1 : initialCue.pitch()));
            choose("enabled", initialCue == null || initialCue.enabled() ? "true" : "false", List.of("true", "false"), ignored -> {});
            choose("asset", "", state.snapshot().assets(), value -> { fields.get("source").setValue(value); choices.put("kind", value.endsWith(".nbs") ? "nbs" : "sound"); });
        } else if (mode.equals("layer")) {
            var layer = state.snapshot().layers().stream().filter(l -> l.target().equals(target)).findFirst().orElse(null);
            field("target", target); field("start", Integer.toString(layer == null ? 0 : layer.start())); field("end", Integer.toString(layer == null ? state.snapshot().duration() : layer.end()));
            choose("enabled", layer == null || layer.enabled() ? "true" : "false", List.of("true", "false"), ignored -> {});
            choose("solo", layer != null && layer.solo() ? "true" : "false", List.of("true", "false"), ignored -> {});
        } else if (mode.equals("settings")) {
            var p = state.snapshot(); field("fps", Double.toString(p.fps())); field("duration", Integer.toString(p.duration()));
            field("start", Integer.toString(p.workStart())); field("end", Integer.toString(p.workEnd()));
            choose("loop", Boolean.toString(p.loop()), List.of("true", "false"), ignored -> {});
        } else {
            field("frame", Integer.toString(initialFrame));
            field("label", state.snapshot().markers().stream().filter(m -> m.frame() == initialFrame).map(EffectProtocol.Marker::label).findFirst().orElse(""));
        }
        preserved.forEach((key, value) -> { var field = fields.get(key); if (field != null) field.setValue(value); });
        int bottom = graph == null ? top + 28 + labels.size() * rowHeight : graph.bottom() + 8;
        addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.effect.confirm"), ignored -> apply()).bounds(left + boxWidth - 164, bottom, 76, 20).build());
        addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.effect.cancel"), ignored -> onClose()).bounds(left + boxWidth - 82, bottom, 76, 20).build());
        if ((mode.equals("key") && initialKey != null) || (mode.equals("cue") && initialCue != null) || mode.equals("marker"))
            addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.effect.delete"), ignored -> delete()).bounds(left + 6, bottom, 76, 20).build());
        if (mode.equals("key")) addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.effect.easy-ease"), ignored -> {
            choices.put("interpolation", "bezier"); fields.get("x1").setValue("0.333333333"); fields.get("y1").setValue("0"); fields.get("x2").setValue("0.666666667"); fields.get("y2").setValue("1");
        }).bounds(left + 88, bottom, 76, 20).build());
    }
    private String defaultValue() {
        var node = scene.find(target);
        return node == null ? "0" : node.properties().stream().filter(p -> p.key().equals(property)).map(EditorProtocol.PropertySnapshot::value).findFirst().filter(v -> !v.isBlank()).orElse("1");
    }
    private void field(String key, String value) {
        int y = top + 26 + labels.size() * rowHeight; labels.add(key);
        var box = new EditBox(font, left + 114, y, boxWidth - 122, 16, EditorI18n.component("arcmenu_editor.effect.field." + key));
        box.setMaxLength(EditorProtocol.MAX_STRING_BYTES / 2); box.setValue(value);
        fields.put(key, box); addRenderableWidget(box);
    }
    private void choose(String key, String value, List<String> options, Consumer<String> changed) {
        int y = top + 26 + labels.size() * rowHeight; labels.add(key); choices.putIfAbsent(key, value);
        var button = Button.builder(Component.literal(choiceLabel(key, choices.getOrDefault(key, value))), clicked -> {
            if (options.isEmpty()) return;
            popup.open(clicked.getX(),clicked.getY()+16,options.stream().map(next -> new EffectPopup.Item(choiceLabel(key, next),"",()-> {
                choices.put(key,next);clicked.setMessage(Component.literal(choiceLabel(key, next)));changed.accept(next);
            })).toList());
        }).bounds(left + 114, y, boxWidth - 122, 16).build();
        addRenderableWidget(button);
    }
    static String choiceLabel(String key, String value) {
        return switch (key) {
            case "property" -> EditorI18n.property(value);
            case "interpolation", "graph-mode", "kind", "enabled", "solo", "loop" ->
                    EditorI18n.text("arcmenu_editor.effect.choice." + value);
            default -> value;
        };
    }
    private String text(String key) { return fields.get(key).getValue().trim(); }
    private int integer(String key) {
        try { return Integer.parseInt(text(key)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(EditorI18n.text("arcmenu_editor.effect.validation.integer", EditorI18n.text("arcmenu_editor.effect.field." + key))); }
    }
    private double number(String key) {
        try { double value = Double.parseDouble(text(key)); if (Double.isFinite(value)) return value; }
        catch (NumberFormatException ignored) { /* Show the same localized validation for incomplete numbers. */ }
        throw new IllegalArgumentException(EditorI18n.text("arcmenu_editor.effect.validation.number", EditorI18n.text("arcmenu_editor.effect.field." + key)));
    }
    private void apply() {
        try {
            switch (mode) {
                case "key" -> {
                    int frame = integer("frame");
                    state.submit(initialKey == null ? "key" : "replace-key", target, property, initialKey == null ? frame : initialKey.frame(), frame, fields.get("value").getValue(), choices.get("interpolation"), number("x1"), number("y1"), number("x2"), number("y2"));
                }
                case "cue" -> {
                    if (initialCue == null) state.submit("cue", text("id"), choices.get("kind"), integer("frame"), integer("end"), fields.get("source").getValue(), choices.get("enabled").equals("true") ? "linear" : "muted", number("volume"), number("pitch"), 0, 0);
                    else state.submit("replace-cue", initialCue.id(), choices.get("kind"), integer("frame"), integer("end"), fields.get("source").getValue(), text("id"), number("volume"), number("pitch"), choices.get("enabled").equals("true") ? 1 : 0, 0);
                }
                case "layer" -> state.submit("layer", text("target"), choices.get("solo"), integer("start"), integer("end"), choices.get("enabled"), "linear", 0, 0, 0, 0);
                case "settings" -> state.submit("settings", "", choices.get("loop"), integer("start"), integer("duration"), text("end"), "linear", number("fps"), 0, 0, 0);
                case "marker" -> state.submit("marker", "", "", integer("frame"), 0, fields.get("label").getValue(), "linear", 0, 0, 0, 0);
                default -> throw new IllegalArgumentException(EditorI18n.text("arcmenu_editor.effect.validation.form"));
            }
            onClose();
        } catch (IllegalArgumentException invalid) { error = invalid.getMessage(); }
    }
    private void delete() {
        String action = mode.equals("key") ? "delete-key" : mode.equals("cue") ? "delete-cue" : "delete-marker";
        state.submit(action, mode.equals("cue") ? initialCue.id() : target, property, initialFrame, 0, "", "linear", 0, 0, 0, 0); onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float partial) {}
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float partial) {
        g.fill(0, 0, width, height, 0xF0141922);
        g.text(font, title, left + 6, top + 7, 0xFFE8ECF4, false);
        for (int i = 0; i < labels.size(); i++) g.text(font, EditorI18n.text("arcmenu_editor.effect.field." + labels.get(i)), left + 6, top + 30 + i * rowHeight, 0xFFAABCD4, false);
        if (graph != null) drawGraph(g);
        super.extractRenderState(g, x, y, partial);
        popup.draw(g,font,width,height,x,y);
        if (!error.isBlank()) g.text(font, font.plainSubstrByWidth(error, width - 20), 10, height - 14, 0xFFFF8E8E, false);
    }
    private void drawGraph(GuiGraphicsExtractor g) {
        g.fill(graph.x(), graph.y(), graph.right(), graph.bottom(), 0xFF0D121B);
        for (int i = 0; i <= 4; i++) { int x = graph.x() + graph.width() * i / 4, y = graph.y() + graph.height() * i / 4; g.fill(x, graph.y(), x + 1, graph.bottom(), 0xFF263345); g.fill(graph.x(), y, graph.right(), y + 1, 0xFF263345); }
        try {
            double x1 = number("x1"), y1 = number("y1"), x2 = number("x2"), y2 = number("y2");
            String interpolation = choices.get("interpolation");
            if (interpolation.equals("ease-in")) { x1 = .42; y1 = 0; x2 = 1; y2 = 1; }
            if (interpolation.equals("ease-out")) { x1 = 0; y1 = 0; x2 = .58; y2 = 1; }
            if (interpolation.equals("ease-in-out")) { x1 = .42; y1 = 0; x2 = .58; y2 = 1; }
            int lastX = graph.x(), lastY = graphY(speedGraph ? 0 : 0);
            for (int i = 1; i <= 128; i++) {
                double t = i / 128.0, u = 1 - t;
                double px = 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t;
                double py = 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t;
                if (interpolation.equals("auto-bezier")) { px = t; py = automaticValue(t); }
                if (interpolation.equals("linear")) { px = t; py = t; }
                if (interpolation.equals("hold")) { px = t; py = i == 128 ? 1 : 0; }
                if (speedGraph) {
                    double dx = 3 * u * u * x1 + 6 * u * t * (x2 - x1) + 3 * t * t * (1 - x2);
                    double dy = 3 * u * u * y1 + 6 * u * t * (y2 - y1) + 3 * t * t * (1 - y2);
                    py = interpolation.equals("linear") ? .333333333 : interpolation.equals("hold") ? 0 : Math.max(-.5, Math.min(1.5, dy / Math.max(.000001, dx) / 3));
                    if (interpolation.equals("auto-bezier")) py = Math.max(-.5, Math.min(1.5, (automaticValue(Math.min(1, t + .0001)) - automaticValue(Math.max(0, t - .0001))) / (Math.min(1, t + .0001) - Math.max(0, t - .0001)) / 3));
                }
                int nx = graph.x() + (int) (Math.max(0, Math.min(1, px)) * graph.width()), ny = graphY(py);
                line(g, lastX, lastY, nx, ny, 0xFF79C0FF); lastX = nx; lastY = ny;
            }
            if (!speedGraph && interpolation.equals("bezier")) {
                int hx1 = graph.x() + (int) (Math.max(0, Math.min(1, x1)) * graph.width()), hy1 = graphY(y1), hx2 = graph.x() + (int) (Math.max(0, Math.min(1, x2)) * graph.width()), hy2 = graphY(y2);
                line(g, graph.x(), graphY(0), hx1, hy1, 0xFFB68C49); line(g, graph.right(), graphY(1), hx2, hy2, 0xFFB68C49);
                g.fill(hx1 - 3, hy1 - 3, hx1 + 4, hy1 + 4, 0xFFFFCF76); g.fill(hx2 - 3, hy2 - 3, hx2 + 4, hy2 + 4, 0xFFFFCF76);
            }
        } catch (IllegalArgumentException ignored) { /* Numeric fields may be incomplete while typing. */ }
    }
    private double automaticValue(double t) {
        var track = state.snapshot().tracks().stream().filter(candidate -> candidate.target().equals(target) && candidate.property().equals(property)).findFirst().orElse(null);
        if (track == null) return t;
        int frame = integer("frame");
        var keys = new ArrayList<>(track.keys());
        keys.removeIf(key -> key.frame() == frame);
        keys.add(new EffectProtocol.Key(frame, text("value"), "auto-bezier", 0, 0, 0, 0));
        keys.sort(Comparator.comparingInt(EffectProtocol.Key::frame));
        int index = 0; while (index < keys.size() && keys.get(index).frame() != frame) index++;
        if (index >= keys.size() - 1) return t;
        var leftKey = keys.get(index); var rightKey = keys.get(index + 1);
        var before = keys.get(Math.max(0, index - 1)); var after = keys.get(Math.min(keys.size() - 1, index + 2));
        double a = Double.parseDouble(leftKey.value()), b = Double.parseDouble(rightKey.value()), span = rightKey.frame() - leftKey.frame();
        if (Math.abs(b - a) < 1e-12) return 0;
        double m0 = (b - Double.parseDouble(before.value())) / (rightKey.frame() - before.frame()) * span;
        double m1 = (Double.parseDouble(after.value()) - a) / (after.frame() - leftKey.frame()) * span;
        double t2 = t * t, t3 = t2 * t;
        return ((2 * t3 - 3 * t2 + 1) * a + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * b + (t3 - t2) * m1 - a) / (b - a);
    }
    private int graphY(double value) { return Math.max(graph.y(), Math.min(graph.bottom(), graph.bottom() - 12 - (int) (Math.max(-2, Math.min(3, value)) * (graph.height() - 24)))); }
    private static void line(GuiGraphicsExtractor g, int x, int y, int tx, int ty, int color) {
        int steps = Math.max(Math.abs(tx - x), Math.abs(ty - y));
        for (int i = 0; i <= steps; i++) { double t = steps == 0 ? 0 : i / (double) steps; int px = (int) (x + (tx - x) * t), py = (int) (y + (ty - y) * t); g.fill(px, py, px + 1, py + 1, color); }
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if(popup.active())return popup.click(event.x(),event.y(),event.button()==GLFW.GLFW_MOUSE_BUTTON_LEFT);
        if (graph != null && graph.contains(event.x(), event.y()) && !speedGraph) {
            try {
                double distance1 = Math.hypot(event.x() - graph.x() - number("x1") * graph.width(), event.y() - graphY(number("y1")));
                double distance2 = Math.hypot(event.x() - graph.x() - number("x2") * graph.width(), event.y() - graphY(number("y2")));
                if (Math.min(distance1, distance2) < 10) { handle = distance1 < distance2 ? 1 : 2; choices.put("interpolation", "bezier"); return true; }
            } catch (IllegalArgumentException ignored) {}
        }
        return super.mouseClicked(event, doubleClick);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (handle != 0) {
            fields.get("x" + handle).setValue(String.format(Locale.ROOT, "%.4f", Math.max(0, Math.min(1, (event.x() - graph.x()) / graph.width()))));
            fields.get("y" + handle).setValue(String.format(Locale.ROOT, "%.4f", Math.max(-2, Math.min(3, (graph.bottom() - 12 - event.y()) / (graph.height() - 24)))));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) { if (handle != 0) { handle = 0; return true; } return super.mouseReleased(event); }
    @Override public boolean keyPressed(KeyEvent event) { if(popup.active())return popup.key(event.key()==GLFW.GLFW_KEY_DOWN?1:event.key()==GLFW.GLFW_KEY_UP?-1:0,event.isConfirmation(),event.isEscape()); if (event.isConfirmation()) { apply(); return true; } return super.keyPressed(event); }
    @Override public boolean mouseScrolled(double x,double y,double h,double v){return popup.wheel(v)||super.mouseScrolled(x,y,h,v);}
    @Override public void onClose() {
        Screen next = parent;
        if (ArcMenuEditorClient.EFFECT.active() && parent instanceof EditorScreen) next = new EffectEditorScreen(scene);
        else if (!ArcMenuEditorClient.EFFECT.active() && parent instanceof EffectEditorScreen) next = new EditorScreen(scene);
        minecraft.setScreen(next);
        if (next instanceof EditorScreen editor) editor.serverStateChanged();
        else if (next instanceof EffectEditorScreen editor) editor.serverStateChanged();
    }
}
