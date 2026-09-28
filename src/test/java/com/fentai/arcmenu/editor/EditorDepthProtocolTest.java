package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class EditorDepthProtocolTest {
    @Test void releaseAndHandshakeUseTheSameVersion() {
        assertEquals("1.0.0", ArcMenuEditorClient.CLIENT_VERSION);
        assertTrue(EditorProtocol.supportsClient(ArcMenuEditorClient.CLIENT_VERSION));
        assertFalse(EditorProtocol.supportsClient("0.1.0-M3.3"));
        assertEquals(9, EditorProtocol.VERSION);
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
}
