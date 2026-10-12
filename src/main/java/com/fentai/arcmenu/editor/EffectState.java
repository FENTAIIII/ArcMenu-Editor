package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Serializes edits against authoritative revisions, including rapid keyframe drags and pasted keys. */
public final class EffectState {
    private EffectProtocol.Snapshot snapshot;
    private EffectProtocol.Viewport viewport;
    private record Pending(EffectProtocol.Edit edit, boolean scrub, boolean immediate) {}
    private static final long SCRUB_INTERVAL = 50_000_000L;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private long nextRequest = 1;
    private long inFlight;
    private long receivedAt;
    private EffectProtocol.Edit inFlightEdit;
    private BiConsumer<String, Integer> localControl = (action, frame) -> {};
    private int sceneEdits;
    private int beforeScene;
    private boolean scrubSent;
    private long lastScrubSent;
    private Consumer<EffectProtocol.Packet> transport = ignored -> {};
    public void transport(Consumer<EffectProtocol.Packet> value) { transport = value; }
    public void localControl(BiConsumer<String, Integer> value) { localControl = value; }
    public void update(EffectProtocol.Snapshot value) {
        if (snapshot != null && !snapshot.id().equals(value.id())) { resetQueue(); }
        snapshot = value; receivedAt = System.nanoTime();
    }
    /** A clock update cannot introduce timeline data from an unseen revision. */
    public boolean update(EffectProtocol.Transport value) {
        if (snapshot == null || snapshot.revision() != value.revision() || !snapshot.id().equals(value.id())) return false;
        var current = snapshot;
        snapshot = new EffectProtocol.Snapshot(current.revision(), current.id(), current.fps(), current.duration(),
                current.workStart(), current.workEnd(), current.loop(), value.dirty(), value.playing(), value.autoKey(), value.playhead(),
                current.tracks(), current.layers(), current.cues(), current.markers(), current.assets());
        receivedAt = System.nanoTime();
        return true;
    }
    public EffectProtocol.Snapshot snapshot() { return snapshot; }
    public EffectProtocol.Viewport viewport() { return viewport; }
    public boolean update(EffectProtocol.Viewport value) {
        if (value.equals(viewport)) return false;
        viewport = value;
        return true;
    }
    public boolean active() { return snapshot != null; }
    public boolean busy() { return sceneEdits!=0 || inFlight != 0 || !pending.isEmpty(); }
    public boolean sceneBusy(){return sceneEdits!=0;}
    public boolean beforeScenePending() { return beforeScene > 0; }
    public void beginSceneEdit() { beginSceneEdit(System.nanoTime()); }
    void beginSceneEdit(long now) {
        if (!active()) return;
        if (sceneEdits == 0 && pending.stream().anyMatch(Pending::scrub)) {
            beforeScene = pending.size();
            promoteScrubs();
        }
        sceneEdits++;
        flush(now);
    }
    public void endSceneEdit() { endSceneEdit(System.nanoTime()); }
    void endSceneEdit(long now) { if (sceneEdits > 0) sceneEdits--; flush(now); }
    public long inFlightRequest() { return inFlight; }
    public boolean controlPending() { return isControl(inFlightEdit) || pending.stream().anyMatch(value -> isControl(value.edit())); }
    private static boolean isControl(EffectProtocol.Edit edit) { return edit != null && (edit.action().equals("seek") || edit.action().equals("play") || edit.action().equals("stop")); }
    public double playhead() {
        if (snapshot == null) return 0;
        return playhead(System.nanoTime());
    }
    double playhead(long now) {
        if (snapshot == null) return 0;
        if (!snapshot.playing()) return snapshot.playhead();
        double frame = snapshot.playhead() + Math.max(0, now - receivedAt) / 1_000_000_000.0 * snapshot.fps();
        int span = snapshot.workEnd() - snapshot.workStart();
        if (snapshot.loop() && span > 0 && frame >= snapshot.workEnd()) return snapshot.workStart() + (frame - snapshot.workStart()) % span;
        return Math.min(snapshot.workEnd(), frame);
    }
    private void resetQueue() { pending.clear(); inFlight = 0; inFlightEdit = null; sceneEdits = 0; beforeScene = 0; scrubSent = false; }
    public void clear() { snapshot = null; viewport = null; resetQueue(); }
    public void acknowledge(long id) { acknowledge(id, System.nanoTime()); }
    void acknowledge(long id, long now) { if (inFlight == id) { inFlight = 0; inFlightEdit = null; flush(now); } }
    public void reject() { pending.clear(); inFlight = 0; inFlightEdit = null; beforeScene = 0; scrubSent = false; }
    public void submit(String action, String target, String property, int frame, int end, String value,
                       String interpolation, double x1, double y1, double x2, double y2) {
        if (snapshot == null) return;
        promoteScrubs();
        enqueue(new EffectProtocol.Edit(0, action, target, property, frame, end, value, interpolation, x1, y1, x2, y2, 0), false, true);
        if (action.equals("seek") || action.equals("play") || action.equals("stop")) localControl.accept(action, frame);
        flush();
    }
    public void action(String action) { submit(action, "", "", 0, 0, "", "linear", .333333333, 0, .666666667, 1); }
    public void seek(int frame) { submit("seek", "", "", frame, 0, "", "linear", .333333333, 0, .666666667, 1); }
    public void scrub(int frame) { scrub(frame, System.nanoTime()); }
    void scrub(int frame, long now) { queueScrub(frame, false, now); }
    public void finishScrub(int frame) { finishScrub(frame, System.nanoTime()); }
    void finishScrub(int frame, long now) { queueScrub(frame, true, now); }
    private void queueScrub(int frame, boolean finalSample, long now) {
        if (snapshot == null) return;
        localControl.accept("seek", frame);
        if (pending.isEmpty() && inFlightEdit != null && inFlightEdit.action().equals("seek") && inFlightEdit.frame() == frame) return;
        if (finalSample && sceneEdits == 0 && inFlight == 0 && pending.isEmpty() && !snapshot.playing() && snapshot.playhead() == frame) return;
        enqueue(new EffectProtocol.Edit(0, "seek", "", "", frame, 0, "", "linear", .333333333, 0, .666666667, 1, 0), true, finalSample);
        flush(now);
    }
    private void enqueue(EffectProtocol.Edit edit, boolean scrub, boolean immediate) {
        // Never merge a later scrub across the boundary before a pending scene edit.
        if (edit.action().equals("seek") && !pending.isEmpty() && pending.getLast().edit().action().equals("seek")
                && !(sceneEdits != 0 && pending.size() <= beforeScene)) pending.removeLast();
        if (pending.size() >= 4096) throw new IllegalArgumentException("Too many pending Effect operations");
        pending.addLast(new Pending(edit, scrub, immediate));
    }
    private void promoteScrubs() {
        if (pending.stream().noneMatch(value -> value.scrub() && !value.immediate())) return;
        var promoted = new ArrayDeque<Pending>();
        for (var value : pending) promoted.addLast(value.scrub() ? new Pending(value.edit(), true, true) : value);
        pending.clear(); pending.addAll(promoted);
    }
    public void key(String target, String property, EffectProtocol.Key key, int frame) {
        submit("key", target, property, frame, 0, key.value(), key.interpolation(), key.x1(), key.y1(), key.x2(), key.y2());
    }
    public void tick() { tick(System.nanoTime()); }
    void tick(long now) { flush(now); }
    private void flush() { flush(System.nanoTime()); }
    private void flush(long now) {
        if (snapshot == null || (sceneEdits != 0 && beforeScene == 0) || inFlight != 0 || pending.isEmpty()) return;
        var next = pending.getFirst();
        if (next.scrub() && !next.immediate() && scrubSent && now - lastScrubSent < SCRUB_INTERVAL) return;
        pending.removeFirst(); if (beforeScene > 0) beforeScene--;
        var p = next.edit(); inFlight = nextRequest++; inFlightEdit = p;
        if (next.scrub()) { lastScrubSent = now; scrubSent = true; }
        transport.accept(new EffectProtocol.Edit(snapshot.revision(), p.action(), p.target(), p.property(), p.frame(), p.end(), p.value(), p.interpolation(), p.x1(), p.y1(), p.x2(), p.y2(), inFlight));
    }
}
