package com.fentai.arcmenu.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Server-authorized visual predictions. Clicks and all business actions remain server-owned. */
public final class VisionVisuals {
    public static final int MAX_WORDS = 8192;
    public static final int MAX_TARGETS = 512;
    public static final int MAX_REGIONS = 253;

    private VisionVisuals() {}

    public record Vector(double x, double y, double z) {
        public Vector {
            require(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z), "Invalid Vision vector");
        }
        public double dot(Vector other) { return x * other.x + y * other.y + z * other.z; }
        public Vector cross(Vector other) { return new Vector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x); }
        public Vector subtract(Vector other) { return new Vector(x - other.x, y - other.y, z - other.z); }
        public Vector add(Vector other) { return new Vector(x + other.x, y + other.y, z + other.z); }
        public Vector scale(double amount) { return new Vector(x * amount, y * amount, z * amount); }
    }
    public record Point(double x, double y) {}

    /** Unit axes and logical pixels per world block, matching the authoritative menu plane. */
    public record Plane(Vector origin, Vector right, Vector up, double pixelsPerBlock) {
        public Plane {
            require(origin != null && right != null && up != null && Double.isFinite(pixelsPerBlock) && pixelsPerBlock > 0,
                    "Invalid Vision plane");
            require(Math.abs(right.dot(right) - 1) < .001 && Math.abs(up.dot(up) - 1) < .001 && Math.abs(right.dot(up)) < .001,
                    "Vision plane axes must be orthonormal");
        }
        public Point project(Vector eye, Vector direction) {
            Vector normal = right.cross(up);
            double denominator = direction.dot(normal);
            if (denominator >= -1e-9) return null;
            double distance = origin.subtract(eye).dot(normal) / denominator;
            if (!Double.isFinite(distance) || distance < 0) return null;
            Vector local = eye.add(direction.scale(distance)).subtract(origin);
            return new Point(local.dot(right) * pixelsPerBlock, local.dot(up) * pixelsPerBlock);
        }
    }

    public record Region(double x, double y, double width, double height) {
        public Region {
            require(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(width) && Double.isFinite(height)
                    && width >= 0 && height >= 0, "Invalid Vision region");
        }
        public boolean contains(Point point) {
            return point != null && Math.abs(point.x - x) <= width / 2 && Math.abs(point.y - y) <= height / 2;
        }
    }
    public static int hit(List<Region> regions, Point point) {
        for (int i = 0; i < regions.size(); i++) if (regions.get(i).contains(point)) return i;
        return -1;
    }

    public record HoverTarget(int entityId, int owner, boolean text) {
        public HoverTarget { require(entityId >= 0 && owner >= 0 && owner < 128, "Invalid Vision hover target"); }
    }
    /** Words are the same 24-bit table used by Mouse; an empty table restores server visuals. */
    public record Hover(long sessionId, Plane plane, List<Integer> words, List<HoverTarget> targets) implements VisionProtocol.Packet {
        public Hover {
            words = List.copyOf(words);
            targets = List.copyOf(targets);
            require(sessionId > 0 && plane != null && words.size() <= MAX_WORDS && targets.size() <= MAX_TARGETS,
                    "Invalid Vision hover state");
            if (words.isEmpty()) require(targets.isEmpty(), "Inactive hover has targets");
            else {
                var table = new VisionHoverTable(words);
                var ids = new HashSet<Integer>();
                for (var target : targets) require(target.owner < table.owners() && ids.add(target.entityId), "Invalid Vision hover ownership");
            }
        }
    }

    /** Affine native Display transform at canvas (0,0), plus translation per canvas unit. Empty matrix detaches. */
    public record Follow(long sessionId, int entityId, Plane plane, List<Float> matrix, Vector stepX, Vector stepY,
                         List<Region> regions, int targetRegion) implements VisionProtocol.Packet {
        public Follow {
            matrix = List.copyOf(matrix);
            regions = List.copyOf(regions);
            require(sessionId > 0 && entityId >= 0, "Invalid Vision follow identity");
            if (matrix.isEmpty()) require(regions.isEmpty() && targetRegion == -1, "Inactive follow has regions");
            else {
                require(matrix.size() == 16 && matrix.stream().allMatch(Float::isFinite) && plane != null && stepX != null && stepY != null,
                        "Invalid Vision follow transform");
                require(regions.size() <= MAX_REGIONS && targetRegion >= -1 && targetRegion < regions.size(), "Invalid Vision follow regions");
                require(matrix.get(3) == 0 && matrix.get(7) == 0 && matrix.get(11) == 0 && matrix.get(15) == 1,
                        "Vision follow transform must be affine");
            }
        }
        public static Follow detach(long sessionId, int entityId) { return new Follow(sessionId, entityId, null, List.of(), null, null, List.of(), -1); }
    }

    static void writeHover(DataOutputStream out, Hover hover) throws IOException {
        out.writeLong(hover.sessionId);
        writePlane(out, hover.plane);
        out.writeShort(hover.words.size());
        for (int word : hover.words) { out.writeByte(word >>> 16); out.writeShort(word); }
        out.writeShort(hover.targets.size());
        for (var target : hover.targets) { out.writeInt(target.entityId); out.writeByte(target.owner); out.writeBoolean(target.text); }
    }
    static Hover readHover(DataInputStream in) throws IOException {
        long session = in.readLong();
        Plane plane = readPlane(in);
        int size = count(in, MAX_WORDS);
        var words = new ArrayList<Integer>(size);
        for (int i = 0; i < size; i++) words.add((in.readUnsignedByte() << 16) | in.readUnsignedShort());
        size = count(in, MAX_TARGETS);
        var targets = new ArrayList<HoverTarget>(size);
        for (int i = 0; i < size; i++) targets.add(new HoverTarget(in.readInt(), in.readUnsignedByte(), in.readBoolean()));
        return new Hover(session, plane, words, targets);
    }
    static void writeFollow(DataOutputStream out, Follow follow) throws IOException {
        out.writeLong(follow.sessionId);
        out.writeInt(follow.entityId);
        out.writeBoolean(!follow.matrix.isEmpty());
        if (follow.matrix.isEmpty()) return;
        writePlane(out, follow.plane);
        for (float value : follow.matrix) out.writeFloat(value);
        writeVector(out, follow.stepX);
        writeVector(out, follow.stepY);
        out.writeShort(follow.regions.size());
        out.writeShort(follow.targetRegion);
        for (var region : follow.regions) {
            out.writeDouble(region.x); out.writeDouble(region.y); out.writeDouble(region.width); out.writeDouble(region.height);
        }
    }
    static Follow readFollow(DataInputStream in) throws IOException {
        long session = in.readLong();
        int entity = in.readInt();
        if (!in.readBoolean()) return Follow.detach(session, entity);
        Plane plane = readPlane(in);
        var matrix = new ArrayList<Float>(16);
        for (int i = 0; i < 16; i++) matrix.add(in.readFloat());
        Vector x = readVector(in), y = readVector(in);
        int size = count(in, MAX_REGIONS), target = in.readShort();
        var regions = new ArrayList<Region>(size);
        for (int i = 0; i < size; i++) regions.add(new Region(in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()));
        return new Follow(session, entity, plane, matrix, x, y, regions, target);
    }
    private static void writePlane(DataOutputStream out, Plane plane) throws IOException {
        writeVector(out, plane.origin); writeVector(out, plane.right); writeVector(out, plane.up); out.writeDouble(plane.pixelsPerBlock);
    }
    private static Plane readPlane(DataInputStream in) throws IOException { return new Plane(readVector(in), readVector(in), readVector(in), in.readDouble()); }
    private static void writeVector(DataOutputStream out, Vector value) throws IOException { out.writeDouble(value.x); out.writeDouble(value.y); out.writeDouble(value.z); }
    private static Vector readVector(DataInputStream in) throws IOException { return new Vector(in.readDouble(), in.readDouble(), in.readDouble()); }
    private static int count(DataInputStream in, int maximum) throws IOException {
        int count = in.readUnsignedShort();
        require(count <= maximum, "Vision visual collection exceeds its limit");
        return count;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
