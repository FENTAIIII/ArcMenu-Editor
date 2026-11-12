package com.fentai.arcmenu.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Optional channel: existing menu editors keep their original wire protocol. */
public final class EffectProtocol
{
    public static final String CHANNEL = "arcmenu:effect_editor";
    public static final int VERSION = 4;
    private static final int MAGIC = 0x41524546;
    // Packet type ids on the wire; encode and decode must agree.
    private static final int TYPE_HELLO = 1;
    private static final int TYPE_EDIT = 2;
    private static final int TYPE_EXIT = 3;
    private static final int TYPE_SNAPSHOT = 64;
    private static final int TYPE_CLOSED = 65;
    private static final int TYPE_ERROR = 66;
    private static final int TYPE_ACK = 67;
    private static final int TYPE_VIEWPORT = 68;
    private static final int TYPE_SELECTION = 69;
    private static final int TYPE_TRANSPORT = 70;
    private static final int TYPE_SCENE_FRAME = 71;
    private static final int TYPE_PLAYBACK = 72;

    private EffectProtocol()
    {}

    public sealed interface Packet permits Hello, Edit, Snapshot, Closed, Error, Ack, Viewport, Selection, Transport,
            SceneFrame, Playback, Exit
    {}

    public record Hello(int version) implements Packet
    {}

    /** Runtime playback only; zero releases the client's shortcut capture. */
    public record Playback(long sessionId) implements Packet
    {}

    public record Exit(long sessionId) implements Packet
    {}

    public record Edit(long revision, String action, String target, String property, int frame, int end, String value,
            String interpolation, double x1, double y1, double x2, double y2, long requestId) implements Packet
    {}

    public record Key(int frame, String value, String interpolation, double x1, double y1, double x2, double y2)
    {}

    public record Track(String target, String property, boolean enabled, List<Key> keys)
    {
        public Track
        {
            keys = List.copyOf(keys);
        }
    }

    public record Layer(String target, int start, int end, boolean enabled, boolean solo)
    {}

    public record Cue(String id, int frame, int end, String kind, String source, double volume, double pitch,
            boolean enabled)
    {}

    public record Marker(int frame, String label)
    {}

    public record Snapshot(long revision, String id, double fps, int duration, int workStart, int workEnd, boolean loop,
            boolean dirty, boolean playing, boolean autoKey, double playhead, List<Track> tracks, List<Layer> layers,
            List<Cue> cues, List<Marker> markers, List<String> assets) implements Packet
    {
        public Snapshot
        {
            tracks = List.copyOf(tracks);
            layers = List.copyOf(layers);
            cues = List.copyOf(cues);
            markers = List.copyOf(markers);
            assets = List.copyOf(assets);
        }
    }

    public record Closed() implements Packet
    {}

    public record Error(long revision, String message) implements Packet
    {}

    public record Ack(long requestId) implements Packet
    {}

    /** Sent after scene snapshots for operations that create independent layers. */
    public record Selection(long requestId, List<String> ids) implements Packet
    {
        public Selection
        {
            ids = List.copyOf(ids);
        }
    }

    /** Playback state for an already received timeline revision. */
    public record Transport(long revision, String id, boolean dirty, boolean playing, boolean autoKey, double playhead)
            implements Packet
    {}

    public record PropertyValue(String key, String value)
    {}

    public record NodeFrame(String id, double x, double y, double width, double height, double rotationZ,
            boolean visible, List<PropertyValue> properties)
    {
        public NodeFrame
        {
            properties = List.copyOf(properties);
        }
    }

    /** Sampled frontend changes; never replaces an authoritative scene snapshot. */
    public record SceneFrame(long revision, String menuId, List<NodeFrame> nodes) implements Packet
    {
        public SceneFrame
        {
            nodes = List.copyOf(nodes);
        }
    }

    /** Actual camera-to-plane distance in blocks; right/up offsets are logical canvas pixels. */
    public record Viewport(double pixelsPerBlock, double distance, double offsetX, double offsetY) implements Packet
    {
        public Viewport
        {
            if (!Double.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0 || !Double.isFinite(distance) || distance <= 0
                    || !Double.isFinite(offsetX) || !Double.isFinite(offsetY))
            {
                throw new IllegalArgumentException("Invalid Effect viewport geometry");
            }
        }
    }

    public static byte[] encode(Packet packet)
    {
        return encode(packet, VERSION);
    }

    public static byte[] encode(Packet packet, int version)
    {
        if (version < 1 || version > VERSION || (version == 1 && packet instanceof Selection)
                || (version < 3 && (packet instanceof Transport || packet instanceof SceneFrame))
                || (version < 4 && (packet instanceof Playback || packet instanceof Exit)))
        {
            throw new IllegalArgumentException("Unsupported Effect protocol version");
        }
        try
        {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(version);
            if (packet instanceof Hello hello)
            {
                out.writeByte(TYPE_HELLO);
                out.writeInt(hello.version());
            }
            else if (packet instanceof Exit exit)
            {
                out.writeByte(TYPE_EXIT);
                out.writeLong(exit.sessionId());
            }
            else if (packet instanceof Playback playback)
            {
                out.writeByte(TYPE_PLAYBACK);
                out.writeLong(playback.sessionId());
            }
            else if (packet instanceof Edit edit)
            {
                out.writeByte(TYPE_EDIT);
                out.writeLong(edit.revision());
                string(out, edit.action());
                string(out, edit.target());
                string(out, edit.property());
                out.writeInt(edit.frame());
                out.writeInt(edit.end());
                string(out, edit.value());
                string(out, edit.interpolation());
                out.writeDouble(edit.x1());
                out.writeDouble(edit.y1());
                out.writeDouble(edit.x2());
                out.writeDouble(edit.y2());
                out.writeLong(edit.requestId());
            }
            else if (packet instanceof Snapshot snapshot)
            {
                writeSnapshot(out, snapshot);
            }
            else if (packet instanceof Closed)
            {
                out.writeByte(TYPE_CLOSED);
            }
            else if (packet instanceof Error error)
            {
                out.writeByte(TYPE_ERROR);
                out.writeLong(error.revision());
                string(out, error.message());
            }
            else if (packet instanceof Ack ack)
            {
                out.writeByte(TYPE_ACK);
                out.writeLong(ack.requestId());
            }
            else if (packet instanceof Viewport viewport)
            {
                out.writeByte(TYPE_VIEWPORT);
                out.writeDouble(viewport.pixelsPerBlock());
                out.writeDouble(viewport.distance());
                out.writeDouble(viewport.offsetX());
                out.writeDouble(viewport.offsetY());
            }
            else if (packet instanceof Selection selection)
            {
                out.writeByte(TYPE_SELECTION);
                out.writeLong(selection.requestId());
                count(out, selection.ids().size());
                for (String id : selection.ids())
                {
                    string(out, id);
                }
            }
            else if (packet instanceof Transport transport)
            {
                out.writeByte(TYPE_TRANSPORT);
                out.writeLong(transport.revision());
                string(out, transport.id());
                out.writeBoolean(transport.dirty());
                out.writeBoolean(transport.playing());
                out.writeBoolean(transport.autoKey());
                out.writeDouble(transport.playhead());
            }
            else if (packet instanceof SceneFrame sceneFrame)
            {
                if (sceneFrame.nodes().size() > 10_000)
                {
                    throw new IllegalArgumentException("Too many Effect frame nodes");
                }
                out.writeByte(TYPE_SCENE_FRAME);
                out.writeLong(sceneFrame.revision());
                string(out, sceneFrame.menuId());
                count(out, sceneFrame.nodes().size());
                for (var node : sceneFrame.nodes())
                {
                    if (node.properties().size() > 256)
                    {
                        throw new IllegalArgumentException("Too many Effect frame properties");
                    }
                    string(out, node.id());
                    out.writeDouble(node.x());
                    out.writeDouble(node.y());
                    out.writeDouble(node.width());
                    out.writeDouble(node.height());
                    out.writeDouble(node.rotationZ());
                    out.writeBoolean(node.visible());
                    count(out, node.properties().size());
                    for (var property : node.properties())
                    {
                        string(out, property.key());
                        string(out, property.value());
                    }
                }
            }
            else
            {
                throw new IllegalArgumentException("Unknown Effect packet");
            }
            out.flush();
            var result = bytes.toByteArray();
            if (result.length > EditorProtocol.MAX_PACKET_BYTES)
            {
                throw new IllegalArgumentException("Effect packet is too large");
            }
            return result;
        }
        catch (IOException impossible)
        {
            throw new IllegalStateException(impossible);
        }
    }

    public static Packet decode(byte[] bytes)
    {
        if (bytes.length > EditorProtocol.MAX_PACKET_BYTES)
        {
            throw new IllegalArgumentException("Effect packet is too large");
        }
        try
        {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC)
            {
                throw new IllegalArgumentException("Effect protocol version mismatch");
            }
            int version = in.readUnsignedByte();
            if (version < 1 || version > VERSION)
            {
                throw new IllegalArgumentException("Effect protocol version mismatch");
            }
            Packet result = switch (in.readUnsignedByte())
            {
            case TYPE_HELLO -> new Hello(in.readInt());
            case TYPE_EDIT -> new Edit(in.readLong(), string(in), string(in), string(in), in.readInt(), in.readInt(),
                    string(in), string(in), finite(in), finite(in), finite(in), finite(in), in.readLong());
            case TYPE_EXIT ->
            {
                if (version < 4)
                {
                    throw new IllegalArgumentException("Unsupported Effect exit version");
                }
                yield new Exit(in.readLong());
            }
            case TYPE_PLAYBACK ->
            {
                if (version < 4)
                {
                    throw new IllegalArgumentException("Unsupported Effect playback version");
                }
                yield new Playback(in.readLong());
            }
            case TYPE_SNAPSHOT -> snapshot(in);
            case TYPE_CLOSED -> new Closed();
            case TYPE_ERROR -> new Error(in.readLong(), string(in));
            case TYPE_ACK -> new Ack(in.readLong());
            case TYPE_VIEWPORT -> new Viewport(finite(in), finite(in), finite(in), finite(in));
            case TYPE_SELECTION -> selection(in);
            case TYPE_TRANSPORT ->
            {
                if (version < 3)
                {
                    throw new IllegalArgumentException("Unsupported Effect transport version");
                }
                yield new Transport(in.readLong(), string(in), in.readBoolean(), in.readBoolean(), in.readBoolean(),
                        finite(in));
            }
            case TYPE_SCENE_FRAME ->
            {
                if (version < 3)
                {
                    throw new IllegalArgumentException("Unsupported Effect frame version");
                }
                yield sceneFrame(in);
            }
            default -> throw new IllegalArgumentException("Unknown Effect packet type");
            };
            if (in.available() != 0)
            {
                throw new IllegalArgumentException("Trailing Effect packet data");
            }
            return result;
        }
        catch (IOException error)
        {
            throw new IllegalArgumentException("Truncated Effect packet", error);
        }
    }

    private static Snapshot snapshot(DataInputStream in) throws IOException
    {
        long revision = in.readLong();
        String id = string(in);
        double fps = finite(in);
        int duration = in.readInt();
        int start = in.readInt();
        int end = in.readInt();
        boolean loop = in.readBoolean();
        boolean dirty = in.readBoolean();
        boolean playing = in.readBoolean();
        boolean auto = in.readBoolean();
        double head = finite(in);
        var tracks = new ArrayList<Track>();
        for (int i = count(in, 2048); i > 0; i--)
        {
            String target = string(in);
            String property = string(in);
            boolean enabled = in.readBoolean();
            var keys = new ArrayList<Key>();
            for (int j = count(in, 10000); j > 0; j--)
            {
                keys.add(new Key(in.readInt(), string(in), string(in), finite(in), finite(in), finite(in), finite(in)));
            }
            tracks.add(new Track(target, property, enabled, keys));
        }
        var layers = new ArrayList<Layer>();
        for (int i = count(in, 2048); i > 0; i--)
        {
            layers.add(new Layer(string(in), in.readInt(), in.readInt(), in.readBoolean(), in.readBoolean()));
        }
        var cues = new ArrayList<Cue>();
        for (int i = count(in, 4096); i > 0; i--)
        {
            cues.add(new Cue(string(in), in.readInt(), in.readInt(), string(in), string(in), finite(in), finite(in),
                    in.readBoolean()));
        }
        var markers = new ArrayList<Marker>();
        for (int i = count(in, 4096); i > 0; i--)
        {
            markers.add(new Marker(in.readInt(), string(in)));
        }
        var assets = new ArrayList<String>();
        for (int i = count(in, 4096); i > 0; i--)
        {
            assets.add(string(in));
        }
        return new Snapshot(revision, id, fps, duration, start, end, loop, dirty, playing, auto, head, tracks, layers,
                cues, markers, assets);
    }

    private static Selection selection(DataInputStream in) throws IOException
    {
        long requestId = in.readLong();
        var ids = new ArrayList<String>();
        for (int i = count(in, 512); i > 0; i--)
        {
            ids.add(string(in));
        }
        return new Selection(requestId, ids);
    }

    private static SceneFrame sceneFrame(DataInputStream in) throws IOException
    {
        long revision = in.readLong();
        String menuId = string(in);
        var nodes = new ArrayList<NodeFrame>();
        for (int i = count(in, 10_000); i > 0; i--)
        {
            String id = string(in);
            double x = finite(in);
            double y = finite(in);
            double width = finite(in);
            double height = finite(in);
            double rotation = finite(in);
            boolean visible = in.readBoolean();
            var properties = new ArrayList<PropertyValue>();
            for (int j = count(in, 256); j > 0; j--)
            {
                properties.add(new PropertyValue(string(in), string(in)));
            }
            nodes.add(new NodeFrame(id, x, y, width, height, rotation, visible, properties));
        }
        return new SceneFrame(revision, menuId, nodes);
    }

    private static double finite(DataInputStream in) throws IOException
    {
        double value = in.readDouble();
        if (!Double.isFinite(value))
        {
            throw new IllegalArgumentException("Non-finite Effect value");
        }
        return value;
    }

    private static void writeSnapshot(DataOutputStream out, Snapshot snapshot) throws IOException
    {
        out.writeByte(TYPE_SNAPSHOT);
        out.writeLong(snapshot.revision());
        string(out, snapshot.id());
        out.writeDouble(snapshot.fps());
        out.writeInt(snapshot.duration());
        out.writeInt(snapshot.workStart());
        out.writeInt(snapshot.workEnd());
        out.writeBoolean(snapshot.loop());
        out.writeBoolean(snapshot.dirty());
        out.writeBoolean(snapshot.playing());
        out.writeBoolean(snapshot.autoKey());
        out.writeDouble(snapshot.playhead());
        count(out, snapshot.tracks().size());
        for (var track : snapshot.tracks())
        {
            string(out, track.target());
            string(out, track.property());
            out.writeBoolean(track.enabled());
            count(out, track.keys().size());
            for (var keyframe : track.keys())
            {
                out.writeInt(keyframe.frame());
                string(out, keyframe.value());
                string(out, keyframe.interpolation());
                out.writeDouble(keyframe.x1());
                out.writeDouble(keyframe.y1());
                out.writeDouble(keyframe.x2());
                out.writeDouble(keyframe.y2());
            }
        }
        count(out, snapshot.layers().size());
        for (var layer : snapshot.layers())
        {
            string(out, layer.target());
            out.writeInt(layer.start());
            out.writeInt(layer.end());
            out.writeBoolean(layer.enabled());
            out.writeBoolean(layer.solo());
        }
        count(out, snapshot.cues().size());
        for (var cue : snapshot.cues())
        {
            string(out, cue.id());
            out.writeInt(cue.frame());
            out.writeInt(cue.end());
            string(out, cue.kind());
            string(out, cue.source());
            out.writeDouble(cue.volume());
            out.writeDouble(cue.pitch());
            out.writeBoolean(cue.enabled());
        }
        count(out, snapshot.markers().size());
        for (var marker : snapshot.markers())
        {
            out.writeInt(marker.frame());
            string(out, marker.label());
        }
        count(out, snapshot.assets().size());
        for (var asset : snapshot.assets())
        {
            string(out, asset);
        }
    }

    private static void string(DataOutputStream out, String value) throws IOException
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > EditorProtocol.MAX_STRING_BYTES)
        {
            throw new IllegalArgumentException("Effect string is too long");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String string(DataInputStream in) throws IOException
    {
        int length = in.readInt();
        if (length < 0 || length > EditorProtocol.MAX_STRING_BYTES || length > in.available())
        {
            throw new IllegalArgumentException("Invalid Effect string length");
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void count(DataOutputStream out, int count) throws IOException
    {
        out.writeInt(count);
    }

    private static int count(DataInputStream in, int max) throws IOException
    {
        int count = in.readInt();
        if (count < 0 || count > max || count > in.available())
        {
            throw new IllegalArgumentException("Invalid Effect list size");
        }
        return count;
    }
}
