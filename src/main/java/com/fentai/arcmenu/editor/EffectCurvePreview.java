package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;
import java.util.List;

/** Numeric curve sampling shared by the value and velocity graphs. */
final class EffectCurvePreview {
    static boolean numeric(EffectProtocol.Track track) {
        if (track.keys().isEmpty() || track.property().equals("color")) return false;
        try { for (var key : track.keys()) if (!Double.isFinite(Double.parseDouble(key.value()))) return false; return true; }
        catch (NumberFormatException ignored) { return false; }
    }
    static double value(EffectProtocol.Track track, double frame) {
        List<EffectProtocol.Key> keys = track.keys();
        if (keys.isEmpty()) return 0;
        if (frame <= keys.getFirst().frame()) return number(keys.getFirst());
        if (frame >= keys.getLast().frame()) return number(keys.getLast());
        int low = 0, high = keys.size() - 1;
        while (high - low > 1) { int mid = (low + high) / 2; if (keys.get(mid).frame() <= frame) low = mid; else high = mid; }
        var a = keys.get(low); var b = keys.get(high);
        double t = (frame - a.frame()) / (b.frame() - a.frame()), start = number(a), end = number(b);
        if (a.interpolation().equals("auto-bezier")) {
            var before = keys.get(Math.max(0, low - 1)); var after = keys.get(Math.min(keys.size() - 1, high + 1));
            double span = b.frame() - a.frame(), m0 = (end - number(before)) / (b.frame() - before.frame()) * span;
            double m1 = (number(after) - start) / (after.frame() - a.frame()) * span, t2 = t * t, t3 = t2 * t;
            return (2 * t3 - 3 * t2 + 1) * start + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * end + (t3 - t2) * m1;
        }
        double mix = switch (a.interpolation()) {
            case "hold" -> 0;
            case "bezier" -> bezier(t, a.x1(), a.y1(), a.x2(), a.y2());
            case "ease-in" -> bezier(t, .42, 0, 1, 1);
            case "ease-out" -> bezier(t, 0, 0, .58, 1);
            case "ease-in-out" -> bezier(t, .42, 0, .58, 1);
            default -> t;
        };
        return start + (end - start) * mix;
    }
    static double speed(EffectProtocol.Track track, double frame, double fps) {
        var keys = track.keys();
        if (keys.isEmpty()) return 0;
        double first = keys.getFirst().frame(), last = keys.getLast().frame();
        if (frame < first || frame > last || first == last) return 0;
        int index = 0; while (index + 1 < keys.size() - 1 && keys.get(index + 1).frame() <= frame) index++;
        if (keys.get(index).interpolation().equals("hold")) return 0;
        double left = Math.max(keys.get(index).frame(), frame - .001), right = Math.min(keys.get(index + 1).frame(), frame + .001);
        return (value(track, right) - value(track, left)) / Math.max(.000001, right - left) * fps;
    }
    static EffectProtocol.Key explicitBezier(EffectProtocol.Key key) {
        return switch (key.interpolation()) {
            case "ease-in" -> new EffectProtocol.Key(key.frame(), key.value(), "bezier", .42, 0, 1, 1);
            case "ease-out" -> new EffectProtocol.Key(key.frame(), key.value(), "bezier", 0, 0, .58, 1);
            case "ease-in-out" -> new EffectProtocol.Key(key.frame(), key.value(), "bezier", .42, 0, .58, 1);
            default -> key;
        };
    }
    private static double number(EffectProtocol.Key key) { return Double.parseDouble(key.value()); }
    private static double bezier(double time, double x1, double y1, double x2, double y2) {
        double low = 0, high = 1;
        for (int i = 0; i < 40; i++) { double middle = (low + high) / 2; if (cubic(middle, x1, x2) < time) low = middle; else high = middle; }
        return cubic((low + high) / 2, y1, y2);
    }
    private static double cubic(double t, double a, double b) { double u = 1 - t; return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t; }
    private EffectCurvePreview() {}
}
