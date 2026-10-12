package com.fentai.arcmenu.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Optional channel: existing menu editors keep their original wire protocol. */
public final class EffectProtocol {
    public static final String CHANNEL = "arcmenu:effect_editor";
    public static final int VERSION = 3;
    private static final int MAGIC = 0x41524546;
    private EffectProtocol() {}
    public sealed interface Packet permits Hello, Edit, Snapshot, Closed, Error, Ack, Viewport, Selection, Transport, SceneFrame {}
    public record Hello(int version) implements Packet {}
    public record Edit(long revision, String action, String target, String property, int frame, int end,
                       String value, String interpolation, double x1, double y1, double x2, double y2, long requestId) implements Packet {}
    public record Key(int frame, String value, String interpolation, double x1, double y1, double x2, double y2) {}
    public record Track(String target, String property, boolean enabled, List<Key> keys) {
        public Track { keys = List.copyOf(keys); }
    }
    public record Layer(String target, int start, int end, boolean enabled, boolean solo) {}
    public record Cue(String id, int frame, int end, String kind, String source, double volume, double pitch, boolean enabled) {}
    public record Marker(int frame, String label) {}
    public record Snapshot(long revision, String id, double fps, int duration, int workStart, int workEnd,
                           boolean loop, boolean dirty, boolean playing, boolean autoKey, double playhead,
                           List<Track> tracks, List<Layer> layers, List<Cue> cues, List<Marker> markers, List<String> assets) implements Packet {
        public Snapshot { tracks = List.copyOf(tracks); layers = List.copyOf(layers); cues = List.copyOf(cues); markers = List.copyOf(markers); assets = List.copyOf(assets); }
    }
    public record Closed() implements Packet {}
    public record Error(long revision, String message) implements Packet {}
    public record Ack(long requestId) implements Packet {}
    /** Sent after scene snapshots for operations that create independent layers. */
    public record Selection(long requestId, List<String> ids) implements Packet {
        public Selection { ids = List.copyOf(ids); }
    }
    /** Playback state for an already received timeline revision. */
    public record Transport(long revision, String id, boolean dirty, boolean playing, boolean autoKey, double playhead) implements Packet {}
    public record PropertyValue(String key, String value) {}
    public record NodeFrame(String id, double x, double y, double width, double height, double rotationZ,
                            boolean visible, List<PropertyValue> properties) {
        public NodeFrame { properties = List.copyOf(properties); }
    }
    /** Sampled frontend changes; never replaces an authoritative scene snapshot. */
    public record SceneFrame(long revision, String menuId, List<NodeFrame> nodes) implements Packet {
        public SceneFrame { nodes = List.copyOf(nodes); }
    }
    /** Actual camera-to-plane distance in blocks; right/up offsets are logical canvas pixels. */
    public record Viewport(double pixelsPerBlock, double distance, double offsetX, double offsetY) implements Packet {
        public Viewport {
            if (!Double.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0 || !Double.isFinite(distance) || distance <= 0 ||
                    !Double.isFinite(offsetX) || !Double.isFinite(offsetY)) throw new IllegalArgumentException("Invalid Effect viewport geometry");
        }
    }
    public static byte[] encode(Packet packet) {
        return encode(packet, VERSION);
    }
    public static byte[] encode(Packet packet, int version) {
        if (version < 1 || version > VERSION || (version == 1 && packet instanceof Selection)
                || (version < 3 && (packet instanceof Transport || packet instanceof SceneFrame))) throw new IllegalArgumentException("Unsupported Effect protocol version");
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeByte(version);
            if (packet instanceof Hello p) { out.writeByte(1); out.writeInt(p.version()); }
            else if (packet instanceof Edit p) {
                out.writeByte(2); out.writeLong(p.revision()); string(out, p.action()); string(out, p.target()); string(out, p.property());
                out.writeInt(p.frame()); out.writeInt(p.end()); string(out, p.value()); string(out, p.interpolation());
                out.writeDouble(p.x1()); out.writeDouble(p.y1()); out.writeDouble(p.x2()); out.writeDouble(p.y2());
                out.writeLong(p.requestId());
            } else if (packet instanceof Snapshot p) {
                out.writeByte(64); out.writeLong(p.revision()); string(out, p.id()); out.writeDouble(p.fps());
                out.writeInt(p.duration()); out.writeInt(p.workStart()); out.writeInt(p.workEnd()); out.writeBoolean(p.loop());
                out.writeBoolean(p.dirty()); out.writeBoolean(p.playing()); out.writeBoolean(p.autoKey()); out.writeDouble(p.playhead());
                count(out, p.tracks().size());
                for (var t : p.tracks()) {
                    string(out, t.target()); string(out, t.property()); out.writeBoolean(t.enabled()); count(out, t.keys().size());
                    for (var k : t.keys()) { out.writeInt(k.frame()); string(out, k.value()); string(out, k.interpolation()); out.writeDouble(k.x1()); out.writeDouble(k.y1()); out.writeDouble(k.x2()); out.writeDouble(k.y2()); }
                }
                count(out, p.layers().size()); for (var l : p.layers()) { string(out, l.target()); out.writeInt(l.start()); out.writeInt(l.end()); out.writeBoolean(l.enabled()); out.writeBoolean(l.solo()); }
                count(out, p.cues().size()); for (var c : p.cues()) { string(out, c.id()); out.writeInt(c.frame()); out.writeInt(c.end()); string(out, c.kind()); string(out, c.source()); out.writeDouble(c.volume()); out.writeDouble(c.pitch()); out.writeBoolean(c.enabled()); }
                count(out, p.markers().size()); for (var m : p.markers()) { out.writeInt(m.frame()); string(out, m.label()); }
                count(out, p.assets().size()); for (var a : p.assets()) string(out, a);
            } else if (packet instanceof Closed) out.writeByte(65);
            else if (packet instanceof Error p) { out.writeByte(66); out.writeLong(p.revision()); string(out, p.message()); }
            else if (packet instanceof Ack p) { out.writeByte(67); out.writeLong(p.requestId()); }
            else if (packet instanceof Viewport p) { out.writeByte(68); out.writeDouble(p.pixelsPerBlock()); out.writeDouble(p.distance()); out.writeDouble(p.offsetX()); out.writeDouble(p.offsetY()); }
            else if (packet instanceof Selection p) { out.writeByte(69); out.writeLong(p.requestId()); count(out, p.ids().size()); for (String id : p.ids()) string(out, id); }
            else if (packet instanceof Transport p) {
                out.writeByte(70); out.writeLong(p.revision()); string(out, p.id());
                out.writeBoolean(p.dirty()); out.writeBoolean(p.playing()); out.writeBoolean(p.autoKey()); out.writeDouble(p.playhead());
            }
            else if (packet instanceof SceneFrame p) {
                if (p.nodes().size() > 10_000) throw new IllegalArgumentException("Too many Effect frame nodes");
                out.writeByte(71); out.writeLong(p.revision()); string(out, p.menuId()); count(out, p.nodes().size());
                for (var node : p.nodes()) {
                    if (node.properties().size() > 256) throw new IllegalArgumentException("Too many Effect frame properties");
                    string(out, node.id()); out.writeDouble(node.x()); out.writeDouble(node.y());
                    out.writeDouble(node.width()); out.writeDouble(node.height()); out.writeDouble(node.rotationZ());
                    out.writeBoolean(node.visible()); count(out, node.properties().size());
                    for (var property : node.properties()) { string(out, property.key()); string(out, property.value()); }
                }
            }
            else throw new IllegalArgumentException("Unknown Effect packet");
            out.flush(); var result = bytes.toByteArray();
            if (result.length > EditorProtocol.MAX_PACKET_BYTES) throw new IllegalArgumentException("Effect packet is too large");
            return result;
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public static Packet decode(byte[] bytes) {
        if (bytes.length > EditorProtocol.MAX_PACKET_BYTES) throw new IllegalArgumentException("Effect packet is too large");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("Effect protocol version mismatch");
            int version = in.readUnsignedByte();
            if (version < 1 || version > VERSION) throw new IllegalArgumentException("Effect protocol version mismatch");
            Packet result = switch (in.readUnsignedByte()) {
                case 1 -> new Hello(in.readInt());
                case 2 -> new Edit(in.readLong(), string(in), string(in), string(in), in.readInt(), in.readInt(), string(in), string(in), finite(in), finite(in), finite(in), finite(in), in.readLong());
                case 64 -> snapshot(in);
                case 65 -> new Closed();
                case 66 -> new Error(in.readLong(), string(in));
                case 67 -> new Ack(in.readLong());
                case 68 -> new Viewport(finite(in), finite(in), finite(in), finite(in));
                case 69 -> selection(in);
                case 70 -> {
                    if (version < 3) throw new IllegalArgumentException("Unsupported Effect transport version");
                    yield new Transport(in.readLong(), string(in), in.readBoolean(), in.readBoolean(), in.readBoolean(), finite(in));
                }
                case 71 -> {
                    if (version < 3) throw new IllegalArgumentException("Unsupported Effect frame version");
                    yield sceneFrame(in);
                }
                default -> throw new IllegalArgumentException("Unknown Effect packet type");
            };
            if (in.available() != 0) throw new IllegalArgumentException("Trailing Effect packet data");
            return result;
        } catch (IOException error) { throw new IllegalArgumentException("Truncated Effect packet", error); }
    }
    private static Snapshot snapshot(DataInputStream in) throws IOException {
        long revision = in.readLong(); String id = string(in); double fps = finite(in);
        int duration = in.readInt(), start = in.readInt(), end = in.readInt();
        boolean loop = in.readBoolean(), dirty = in.readBoolean(), playing = in.readBoolean(), auto = in.readBoolean(); double head = finite(in);
        var tracks = new ArrayList<Track>();
        for (int i = count(in, 2048); i > 0; i--) {
            String target = string(in), property = string(in); boolean enabled = in.readBoolean(); var keys = new ArrayList<Key>();
            for (int j = count(in, 10000); j > 0; j--) keys.add(new Key(in.readInt(), string(in), string(in), finite(in), finite(in), finite(in), finite(in)));
            tracks.add(new Track(target, property, enabled, keys));
        }
        var layers = new ArrayList<Layer>(); for (int i = count(in, 2048); i > 0; i--) layers.add(new Layer(string(in), in.readInt(), in.readInt(), in.readBoolean(), in.readBoolean()));
        var cues = new ArrayList<Cue>(); for (int i = count(in, 4096); i > 0; i--) cues.add(new Cue(string(in), in.readInt(), in.readInt(), string(in), string(in), finite(in), finite(in), in.readBoolean()));
        var markers = new ArrayList<Marker>(); for (int i = count(in, 4096); i > 0; i--) markers.add(new Marker(in.readInt(), string(in)));
        var assets = new ArrayList<String>(); for (int i = count(in, 4096); i > 0; i--) assets.add(string(in));
        return new Snapshot(revision, id, fps, duration, start, end, loop, dirty, playing, auto, head, tracks, layers, cues, markers, assets);
    }
    private static Selection selection(DataInputStream in) throws IOException {
        long requestId = in.readLong(); var ids = new ArrayList<String>();
        for (int i = count(in, 512); i > 0; i--) ids.add(string(in));
        return new Selection(requestId, ids);
    }
    private static SceneFrame sceneFrame(DataInputStream in) throws IOException {
        long revision = in.readLong(); String menuId = string(in); var nodes = new ArrayList<NodeFrame>();
        for (int i = count(in, 10_000); i > 0; i--) {
            String id = string(in); double x = finite(in), y = finite(in), width = finite(in), height = finite(in), rotation = finite(in);
            boolean visible = in.readBoolean(); var properties = new ArrayList<PropertyValue>();
            for (int j = count(in, 256); j > 0; j--) properties.add(new PropertyValue(string(in), string(in)));
            nodes.add(new NodeFrame(id, x, y, width, height, rotation, visible, properties));
        }
        return new SceneFrame(revision, menuId, nodes);
    }
    private static double finite(DataInputStream in) throws IOException { double value = in.readDouble(); if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite Effect value"); return value; }
    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > EditorProtocol.MAX_STRING_BYTES) throw new IllegalArgumentException("Effect string is too long");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in) throws IOException {
        int length = in.readInt(); if (length < 0 || length > EditorProtocol.MAX_STRING_BYTES || length > in.available()) throw new IllegalArgumentException("Invalid Effect string length");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
    private static void count(DataOutputStream out, int count) throws IOException { out.writeInt(count); }
    private static int count(DataInputStream in, int max) throws IOException { int count = in.readInt(); if (count < 0 || count > max || count > in.available()) throw new IllegalArgumentException("Invalid Effect list size"); return count; }
}
