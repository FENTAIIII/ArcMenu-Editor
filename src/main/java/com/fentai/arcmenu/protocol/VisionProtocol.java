package com.fentai.arcmenu.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Small runtime cursor channel shared by Paper and the existing ArcMenu Editor client. */
public final class VisionProtocol {
    public static final String CHANNEL = "arcmenu:vision";
    public static final int VERSION = 6;
    public static final String MIN_EDITOR_VERSION = "3.0.6";
    public static final int MAX_PACKET_BYTES = 32767;
    public static final int MAX_SCENE_ENTITIES = 4096;
    public static final float MENU_VERTICAL_FOV = 70.0f;
    public static final byte MOVE = 0;
    public static final byte LEFT = 1;
    public static final byte RIGHT = 2;
    public static final byte MIDDLE = 3;
    public static final byte CLOSE = 4;
    public static final byte JUMP = 5;
    public static final byte LEAVE = 6;
    public static final byte TEXT_UPDATE = 0;
    public static final byte TEXT_FINISH = 1;
    public static final int MAX_TEXT_LENGTH = 50;
    public static final int MAX_TEXT_TITLE_LENGTH = 1024;
    private static final int MAGIC = 0x41525631;

    private VisionProtocol() {}

    public sealed interface Packet permits Hello, State, Input, Scene, TextState, TextInput, PreviewScene,
            VisionVisuals.Hover, VisionVisuals.Follow {}
    public record Hello(int version) implements Packet {}
    /** Size and projection use logical canvas units; their ratio is the cursor's world angular size. */
    public record State(long sessionId, boolean active, boolean choosing, double cursorSize,
                        double canvasProjection, double sensitivityX, double sensitivityY) implements Packet {
        public State {
            if (sessionId < 0 || (active && sessionId == 0) || !positive(cursorSize) || !positive(canvasProjection)
                    || !positive(sensitivityX) || !positive(sensitivityY)) {
                throw new IllegalArgumentException("Invalid Vision state");
            }
        }
    }
    /** Clicks include the current ray, so a click never races a separately queued hover update. */
    public record Input(long sessionId, float yaw, float pitch, byte action, boolean shift) implements Packet {
        public Input {
            if (sessionId <= 0 || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                    || pitch < -90 || pitch > 90 || action < MOVE || action > LEAVE) {
                throw new IllegalArgumentException("Invalid Vision input");
            }
        }
    }

    /** Finishing an editor preserves its draft; only the existing server submit region can commit it. */
    public record TextInput(long sessionId, long editId, byte action, String value) implements Packet {
        public TextInput {
            if (sessionId <= 0 || editId <= 0 || action < TEXT_UPDATE || action > TEXT_FINISH
                    || !validText(value, MAX_TEXT_LENGTH)) {
                throw new IllegalArgumentException("Invalid Vision text input");
            }
        }
    }

    /** The nonce separates repeated edits of the same region within one menu session. */
    public record TextState(long sessionId, long editId, boolean active, String title, String value,
                            int maxLength) implements Packet {
        public TextState {
            if (sessionId <= 0 || editId <= 0 || maxLength < 1 || maxLength > MAX_TEXT_LENGTH
                    || !validText(title, MAX_TEXT_TITLE_LENGTH) || !validText(value, maxLength)) {
                throw new IllegalArgumentException("Invalid Vision text state");
            }
        }
    }

    /** Native Display updates remain authoritative; this only selects an isolated client render scene. */
    public record Scene(long sessionId, double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
                        float verticalFov, List<Integer> entityIds) implements Packet {
        public Scene {
            entityIds = List.copyOf(entityIds);
            if (sessionId <= 0 || !Double.isFinite(eyeX) || !Double.isFinite(eyeY) || !Double.isFinite(eyeZ)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch < -90 || pitch > 90
                    || !Float.isFinite(verticalFov) || verticalFov <= 0 || verticalFov >= 180
                    || entityIds.size() > MAX_SCENE_ENTITIES || entityIds.stream().anyMatch(id -> id < 0)
                    || new HashSet<>(entityIds).size() != entityIds.size()) {
                throw new IllegalArgumentException("Invalid Vision scene");
            }
        }
    }

    /** An editor viewport scene has its own lifetime and never captures the runtime mouse. */
    public record PreviewScene(boolean active, Scene scene) implements Packet {
        public PreviewScene {
            if (scene == null) {
                throw new IllegalArgumentException("Missing Vision preview scene");
            }
        }
    }

    public static byte[] encode(Packet packet) {
        try {
            var bytes = new ByteArrayOutputStream(64);
            var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            // Version 1 clients can read this probe and decline support without a decoder exception.
            out.writeByte(packet instanceof Hello ? 1 : VERSION);
            if (packet instanceof Hello value) {
                out.writeByte(1);
                out.writeInt(value.version());
            } else if (packet instanceof Input value) {
                out.writeByte(2);
                out.writeLong(value.sessionId());
                out.writeFloat(value.yaw());
                out.writeFloat(value.pitch());
                out.writeByte(value.action());
                out.writeBoolean(value.shift());
            } else if (packet instanceof TextInput value) {
                out.writeByte(3);
                out.writeLong(value.sessionId());
                out.writeLong(value.editId());
                out.writeByte(value.action());
                writeString(out, value.value());
            } else if (packet instanceof State value) {
                out.writeByte(64);
                out.writeLong(value.sessionId());
                out.writeBoolean(value.active());
                out.writeBoolean(value.choosing());
                out.writeDouble(value.cursorSize());
                out.writeDouble(value.canvasProjection());
                out.writeDouble(value.sensitivityX());
                out.writeDouble(value.sensitivityY());
            } else if (packet instanceof Scene value) {
                out.writeByte(65);
                writeScene(out, value);
            } else if (packet instanceof TextState value) {
                out.writeByte(66);
                out.writeLong(value.sessionId());
                out.writeLong(value.editId());
                out.writeBoolean(value.active());
                out.writeByte(value.maxLength());
                writeString(out, value.title());
                writeString(out, value.value());
            } else if (packet instanceof PreviewScene value) {
                out.writeByte(67);
                out.writeBoolean(value.active());
                writeScene(out, value.scene());
            } else if (packet instanceof VisionVisuals.Hover value) {
                out.writeByte(68);
                VisionVisuals.writeHover(out, value);
            } else if (packet instanceof VisionVisuals.Follow value) {
                out.writeByte(69);
                VisionVisuals.writeFollow(out, value);
            } else {
                throw new IllegalArgumentException("Unknown Vision packet");
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Packet decode(byte[] bytes) {
        if (bytes.length > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("Vision packet is too large");
        }
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("Vision protocol version mismatch");
            }
            int version = in.readUnsignedByte();
            int type = in.readUnsignedByte();
            if (version != VERSION && !(version == 1 && type == 1)) {
                throw new IllegalArgumentException("Vision protocol version mismatch");
            }
            Packet packet = switch (type) {
                case 1 -> new Hello(in.readInt());
                case 2 -> new Input(in.readLong(), in.readFloat(), in.readFloat(), in.readByte(), in.readBoolean());
                case 3 -> new TextInput(in.readLong(), in.readLong(), in.readByte(), readString(in, MAX_TEXT_LENGTH));
                case 64 -> new State(in.readLong(), in.readBoolean(), in.readBoolean(),
                        in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble());
                case 65 -> readScene(in);
                case 66 -> readTextState(in);
                case 67 -> new PreviewScene(in.readBoolean(), readScene(in));
                case 68 -> VisionVisuals.readHover(in);
                case 69 -> VisionVisuals.readFollow(in);
                default -> throw new IllegalArgumentException("Unknown Vision packet type");
            };
            if (in.available() != 0) {
                throw new IllegalArgumentException("Trailing Vision packet data");
            }
            return packet;
        } catch (IOException error) {
            throw new IllegalArgumentException("Truncated Vision packet", error);
        }
    }

    private static void writeScene(DataOutputStream out, Scene value) throws IOException {
        out.writeLong(value.sessionId());
        out.writeDouble(value.eyeX());
        out.writeDouble(value.eyeY());
        out.writeDouble(value.eyeZ());
        out.writeFloat(value.yaw());
        out.writeFloat(value.pitch());
        out.writeFloat(value.verticalFov());
        out.writeShort(value.entityIds().size());
        for (int entityId : value.entityIds()) {
            out.writeInt(entityId);
        }
    }

    private static TextState readTextState(DataInputStream in) throws IOException {
        long sessionId = in.readLong(), editId = in.readLong();
        boolean active = in.readBoolean();
        int maxLength = in.readUnsignedByte();
        return new TextState(sessionId, editId, active, readString(in, MAX_TEXT_TITLE_LENGTH),
                readString(in, MAX_TEXT_LENGTH), maxLength);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxLength) throws IOException {
        int length = in.readUnsignedShort();
        if (length > maxLength * 3 || length > in.available()) {
            throw new IllegalArgumentException("Invalid Vision text length");
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
            if (!validText(value, maxLength)) {
                throw new IllegalArgumentException("Invalid Vision text");
            }
            return value;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid Vision UTF-8 text", error);
        }
    }

    private static boolean validText(String value, int maxLength) {
        if (value == null || value.length() > maxLength) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(character)) {
                return false;
            }
        }
        return true;
    }

    private static Scene readScene(DataInputStream in) throws IOException {
        long sessionId = in.readLong();
        double eyeX = in.readDouble(), eyeY = in.readDouble(), eyeZ = in.readDouble();
        float yaw = in.readFloat(), pitch = in.readFloat(), verticalFov = in.readFloat();
        int count = in.readUnsignedShort();
        if (count > MAX_SCENE_ENTITIES || in.available() != count * Integer.BYTES) {
            throw new IllegalArgumentException("Invalid Vision scene entity count");
        }
        var entityIds = new ArrayList<Integer>(count);
        for (int index = 0; index < count; index++) {
            entityIds.add(in.readInt());
        }
        return new Scene(sessionId, eyeX, eyeY, eyeZ, yaw, pitch, verticalFov, entityIds);
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }
}
