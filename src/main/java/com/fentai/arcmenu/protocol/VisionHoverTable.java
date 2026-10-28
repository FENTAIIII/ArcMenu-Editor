package com.fentai.arcmenu.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Native playback of the same bounded color table consumed by the Mouse post effect. */
public final class VisionHoverTable {
    private final List<Integer> words;
    private final List<VisionVisuals.Region> regions;
    private final int[] active;
    private final double[] started;
    private int hovered = -1;

    public VisionHoverTable(List<Integer> words) {
        this.words = List.copyOf(words);
        require(words.size() >= 11 && words.size() <= VisionVisuals.MAX_WORDS);
        require(words.stream().allMatch(word -> word >= 0 && word <= 0xFFFFFF));
        require(w(0) == 0xA4C3E1 && w(1) == words.size() && w(2) != 0);
        require(owners() <= 128 && w(4) <= 254 && w(5) <= VisionVisuals.MAX_REGIONS);
        require(w(6) == 11 && w(7) == w(6) + owners() * 18 && w(8) == w(7) + w(4) * 20);
        require(w(9) == w(8) + w(5) * 2 && w(10) >= w(9) && words.size() == w(10) + w(5) * 4);
        for (int i = 0; i < w(4); i++) {
            int base = w(7) + i * 20, mode = w(base + 2), count = (mode >> 8) & 255;
            require(w(base) < owners() && w(base + 1) > 0 && (mode & 15) <= 3 && ((mode >> 4) & 15) <= 2);
            require(count >= 1 && count <= 8 && (mode >> 17) == 0);
            for (int k = 1; k < count; k++) require(w(base + 4 + k * 2) >= w(base + 2 + k * 2));
        }
        for (int i = w(8); i < w(9); i++) require((w(i) & 65535) + (w(i) >> 16) <= w(10) - w(9));
        for (int i = w(9); i < w(10); i++) require((w(i) & 255) < w(4) && (w(i) >> 9) == 0);
        var parsed = new ArrayList<VisionVisuals.Region>();
        for (int i = w(10); i < words.size(); i += 4)
            parsed.add(new VisionVisuals.Region(fixed(w(i)), fixed(w(i + 1)), fixed(w(i + 2)), fixed(w(i + 3))));
        regions = List.copyOf(parsed);
        active = new int[owners()];
        Arrays.fill(active, -1);
        started = new double[owners()];
    }

    public int owners() { return w(3); }
    public boolean choosing() { return hovered >= 0; }

    /** Tick time may be fractional; a new table starts with no hovered region or active tracks. */
    public void update(VisionVisuals.Point point, double tick) {
        int next = VisionVisuals.hit(regions, point);
        if (next == hovered) return;
        run(hovered, 1, tick);
        run(next, 0, tick);
        hovered = next;
    }

    private void run(int region, int sequence, double tick) {
        if (region < 0) return;
        int script = w(w(8) + region * 2 + sequence), first = w(9) + (script & 65535);
        for (int i = 0; i < script >> 16; i++) {
            int op = w(first + i), track = op & 255, owner = w(w(7) + track * 20);
            if ((op >> 8) != 0) { active[owner] = track; started[owner] = tick; }
            else if (active[owner] == track) active[owner] = -1;
        }
    }

    public int argb(int owner, double tick) {
        int baseOwner = w(6) + owner * 18;
        int alpha = (w(baseOwner + 1) & 255) << 24;
        if (active[owner] < 0) return alpha | w(baseOwner);
        int base = w(7) + active[owner] * 20, mode = w(base + 2);
        double progress = Math.max(0, tick - started[owner]) / w(base + 1);
        int loop = (mode >> 4) & 15;
        progress = loop == 0 ? Math.min(1, progress) : loop == 1 ? progress % 1 : 1 - Math.abs(progress % 2 - 1);
        progress = switch (mode & 15) {
            case 1 -> progress * progress;
            case 2 -> 1 - (1 - progress) * (1 - progress);
            case 3 -> progress < .5 ? 2 * progress * progress : 1 - Math.pow(-2 * progress + 2, 2) / 2;
            default -> progress;
        };
        int count = (mode >> 8) & 255, previous = base + 4;
        if (progress <= w(previous) / (double) 0xFFFFFF) return alpha | w(previous + 1);
        for (int i = 1; i < count; i++) {
            int next = base + 4 + i * 2;
            double end = w(next) / (double) 0xFFFFFF;
            if (progress < end) {
                if ((mode & 0x10000) != 0) return alpha | w(previous + 1);
                double start = w(previous) / (double) 0xFFFFFF;
                return alpha | mix(w(previous + 1), w(next + 1), (progress - start) / (end - start));
            }
            previous = next;
        }
        return alpha | w(previous + 1);
    }

    private static int mix(int a, int b, double amount) {
        int color = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int from = (a >> shift) & 255, to = (b >> shift) & 255;
            color |= (int) Math.round(from + (to - from) * amount) << shift;
        }
        return color;
    }
    private int w(int index) { return words.get(index); }
    private static double fixed(int word) { return (word - 0x800000) / 64.0; }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid Vision hover table"); }
}
