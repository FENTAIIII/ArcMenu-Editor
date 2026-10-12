package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable pointer mapping and atomic keyframe payloads for a complete gesture. */
final class EffectKeyframeGestures {
    record Ref(String target, String property, EffectProtocol.Key key) {}
    record Mapping(double left, double width, double firstFrame, double frameRange,
                   double top, double bottom, double minimum, double maximum, int duration) {
        double frame(double x) { return firstFrame + (x - left) / Math.max(1, width) * frameRange; }
        int boundedFrame(double x) { return Math.max(0, Math.min(duration, (int) Math.round(frame(x)))); }
        double value(double y) { return minimum + (bottom - y) / Math.max(1, bottom - top) * (maximum - minimum); }
        int delta(double pointer, double origin) { return (int) Math.round((pointer - origin) / Math.max(1, width) * frameRange); }
    }
    static int boundedDelta(Collection<Ref> keys, int delta, int duration) {
        int first = keys.stream().mapToInt(ref -> ref.key.frame()).min().orElse(0);
        int last = keys.stream().mapToInt(ref -> ref.key.frame()).max().orElse(0);
        return Math.max(-first, Math.min(duration - last, delta));
    }
    static JsonObject references(Collection<Ref> keys) {
        JsonArray array = new JsonArray();
        for (var ref : keys) array.add(reference(ref));
        JsonObject payload = new JsonObject(); payload.add("keys", array); return payload;
    }
    static JsonObject moved(Collection<Ref> keys, int delta, String valueTarget, String valueProperty, double valueDelta) {
        JsonArray array = new JsonArray();
        for (var ref : keys) {
            var entry = reference(ref); entry.addProperty("newFrame", ref.key.frame() + delta);
            if (valueDelta != 0 && ref.target.equals(valueTarget) && ref.property.equals(valueProperty))
                entry.addProperty("value", Double.toString(Double.parseDouble(ref.key.value()) + valueDelta));
            array.add(entry);
        }
        JsonObject payload = new JsonObject(); payload.add("keys", array); return payload;
    }
    static JsonObject pasted(Collection<Ref> keys, int playhead, int duration) {
        int first = keys.stream().mapToInt(ref -> ref.key.frame()).min().orElse(0);
        int last = keys.stream().mapToInt(ref -> ref.key.frame()).max().orElse(0);
        int start = Math.max(0, Math.min(duration - (last - first), playhead));
        JsonArray array = new JsonArray();
        for (var ref : keys) {
            var entry = reference(ref); var key = ref.key;
            entry.addProperty("frame", start + key.frame() - first); entry.addProperty("value", key.value());
            entry.addProperty("interpolation", key.interpolation()); entry.addProperty("x1", key.x1());
            entry.addProperty("y1", key.y1()); entry.addProperty("x2", key.x2()); entry.addProperty("y2", key.y2()); array.add(entry);
        }
        JsonObject payload = new JsonObject(); payload.add("keys", array); return payload;
    }
    static JsonObject reversed(Collection<Ref> keys) {
        int first = keys.stream().mapToInt(ref -> ref.key.frame()).min().orElse(0);
        int last = keys.stream().mapToInt(ref -> ref.key.frame()).max().orElse(0);
        var groups = new LinkedHashMap<String, List<Ref>>();
        for (var ref : keys) groups.computeIfAbsent(ref.target + "\u0000" + ref.property, ignored -> new ArrayList<>()).add(ref);
        JsonArray array = new JsonArray();
        for (var group : groups.values()) {
            group.sort(Comparator.comparingInt(ref -> ref.key.frame()));
            for (int index = 0; index < group.size(); index++) {
                var ref = group.get(index); var entry = reference(ref); entry.addProperty("newFrame", first + last - ref.key.frame());
                var curve = group.get(index == 0 ? group.size() - 1 : index - 1).key;
                if (index == 0) { addCurve(entry, curve); array.add(entry); continue; }
                String mode = switch (curve.interpolation()) { case "ease-in" -> "ease-out"; case "ease-out" -> "ease-in"; default -> curve.interpolation(); };
                entry.addProperty("interpolation", mode); entry.addProperty("x1", 1 - curve.x2()); entry.addProperty("y1", 1 - curve.y2());
                entry.addProperty("x2", 1 - curve.x1()); entry.addProperty("y2", 1 - curve.y1()); array.add(entry);
            }
        }
        JsonObject payload = new JsonObject(); payload.add("keys", array); return payload;
    }
    static JsonObject eased(Collection<Ref> selected, Collection<EffectProtocol.Track> tracks, String side) {
        var changed = new LinkedHashMap<String, Ref>();
        for (var ref : selected) {
            var track = tracks.stream().filter(candidate -> candidate.target().equals(ref.target) && candidate.property().equals(ref.property)).findFirst().orElse(null);
            if (track == null) continue;
            for (int index = 0; index < track.keys().size(); index++) if (track.keys().get(index).frame() == ref.key.frame()) {
                if (!side.equals("ease-in") && index + 1 < track.keys().size()) changeEase(changed, track, index, true);
                if (!side.equals("ease-out") && index > 0) changeEase(changed, track, index - 1, false);
            }
        }
        JsonArray array = new JsonArray();
        for (var ref : changed.values()) {
            var entry = reference(ref); entry.addProperty("newFrame", ref.key.frame()); addCurve(entry, ref.key); array.add(entry);
        }
        JsonObject payload = new JsonObject(); payload.add("keys", array); return payload;
    }
    static JsonObject toggled(Collection<Ref> properties, int frame) {
        JsonArray array = new JsonArray();
        for (var ref : properties) {
            var entry = reference(ref); entry.addProperty("frame", frame); entry.addProperty("value", ref.key.value());
            addCurve(entry, new EffectProtocol.Key(frame, ref.key.value(), "linear", 1.0 / 3, 0, 2.0 / 3, 1)); array.add(entry);
        }
        JsonObject payload = new JsonObject(); payload.add("keys", array); return payload;
    }
    private static void changeEase(Map<String, Ref> changed, EffectProtocol.Track track, int index, boolean outgoing) {
        var original = track.keys().get(index); String id = track.target() + "\u0000" + track.property() + "\u0000" + original.frame();
        var stored = changed.get(id); var curve = stored == null ? segmentBezier(track, index) : stored.key;
        var edited = new EffectProtocol.Key(original.frame(), original.value(), "bezier", outgoing ? 1.0 / 3 : curve.x1(), outgoing ? 0 : curve.y1(), outgoing ? curve.x2() : 2.0 / 3, outgoing ? curve.y2() : 1);
        changed.put(id, new Ref(track.target(), track.property(), edited));
    }
    private static EffectProtocol.Key segmentBezier(EffectProtocol.Track track, int index) {
        var key = EffectCurvePreview.explicitBezier(track.keys().get(index));
        if (key.interpolation().equals("bezier")) return key;
        double y1 = 1.0 / 3, y2 = 2.0 / 3;
        if (key.interpolation().equals("auto-bezier") && EffectCurvePreview.numeric(track) && index + 1 < track.keys().size()) {
            var keys = track.keys(); var next = keys.get(index + 1); double change = Double.parseDouble(next.value()) - Double.parseDouble(key.value());
            if (Math.abs(change) > 1e-12) {
                var before = keys.get(Math.max(0, index - 1)); var after = keys.get(Math.min(keys.size() - 1, index + 2)); double span = next.frame() - key.frame();
                y1 = (Double.parseDouble(next.value()) - Double.parseDouble(before.value())) / (next.frame() - before.frame()) * span / change / 3;
                y2 = 1 - (Double.parseDouble(after.value()) - Double.parseDouble(key.value())) / (after.frame() - key.frame()) * span / change / 3;
            }
        }
        return new EffectProtocol.Key(key.frame(), key.value(), "bezier", 1.0 / 3, y1, 2.0 / 3, y2);
    }
    private static void addCurve(JsonObject entry, EffectProtocol.Key key) {
        entry.addProperty("interpolation", key.interpolation()); entry.addProperty("x1", key.x1()); entry.addProperty("y1", key.y1()); entry.addProperty("x2", key.x2()); entry.addProperty("y2", key.y2());
    }
    static boolean inside(double x, double y, double ax, double ay, double bx, double by) {
        return x >= Math.min(ax, bx) && x <= Math.max(ax, bx) && y >= Math.min(ay, by) && y <= Math.max(ay, by);
    }
    private static JsonObject reference(Ref ref) {
        JsonObject entry = new JsonObject(); entry.addProperty("target", ref.target);
        entry.addProperty("property", ref.property); entry.addProperty("frame", ref.key.frame()); return entry;
    }
    private EffectKeyframeGestures() {}
}
