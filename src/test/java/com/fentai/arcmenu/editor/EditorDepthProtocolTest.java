package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.editor.net.FrameAssembler;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class EditorDepthProtocolTest {
    @Test void releaseAndHandshakeUseTheSameVersion() {
        assertEquals("3.4.1", ArcMenuEditorClient.CLIENT_VERSION);
        assertTrue(EditorProtocol.supportsClient(ArcMenuEditorClient.CLIENT_VERSION));
        assertTrue(EditorProtocol.supportsClient("3.1.0"));
        assertTrue(EditorProtocol.supportsClient("3.0.6"));
        assertTrue(EditorProtocol.supportsClient("1.2.1"));
        assertTrue(EditorProtocol.supportsClient("1.0.0"));
        assertTrue(EditorProtocol.supportsClient("1.0.1"));
        assertFalse(EditorProtocol.supportsClient("0.1.0-M3.3"));
        assertEquals(11, EditorProtocol.VERSION);
    }

    @Test void depthPropertyAndEditorSnapshotRoundTrip() {
        var property = new EditorProtocol.SetPropertyPacket(3, EditorProtocol.TAB_FRONTEND, "block", "scale.z", "7.5");
        assertEquals(property, EditorProtocol.decode(EditorProtocol.encode(property)));
        var node = new EditorProtocol.NodeSnapshot("block", "", EditorProtocol.KIND_BLOCK,
                0, 0, 32, 32, 0, true, false,
                List.of(new EditorProtocol.PropertySnapshot("scale.z", EditorProtocol.PROPERTY_NUMBER, "7.5")));
        var snapshot = new EditorProtocol.SnapshotPacket(4, "models", 320, 180, false, true, "26.1.2", List.of(node), List.of());
        assertEquals(snapshot, EditorProtocol.decode(EditorProtocol.encode(snapshot)));
        var state = new EditorState();
        state.update(snapshot);
        assertEquals(snapshot, state.snapshot());
    }

    @Test void largeChinesePropertyUsesFramedRequestsWithPreciseByteLimit() {
        var value = "中".repeat(EditorProtocol.MAX_STRING_BYTES / 3) + "a";
        var packet = new EditorProtocol.SetPropertyPacket(3, EditorProtocol.TAB_BACKEND, "submit", "actions", value);
        var frames = EditorProtocol.frame(1, EditorProtocol.encode(packet));
        assertTrue(frames.size() > 1);
        var assembler = new FrameAssembler();
        byte[] result = null;
        for (byte[] frame : frames) result = assembler.accept(frame);
        assertEquals(packet, EditorProtocol.decode(result));
        var failure = assertThrows(EditorProtocol.StringLimitException.class, () -> EditorProtocol.encode(
                new EditorProtocol.SetPropertyPacket(3, EditorProtocol.TAB_BACKEND, "submit", "actions", value + "a")));
        assertEquals("backend.submit.actions", failure.field());
        assertEquals(EditorProtocol.MAX_STRING_BYTES + 1, failure.byteCount());
    }
}
