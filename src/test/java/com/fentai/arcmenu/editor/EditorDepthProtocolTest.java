package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.net.FrameAssembler;
import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EditorProtocol.NodeSnapshot;
import com.fentai.arcmenu.protocol.EditorProtocol.PropertySnapshot;
import com.fentai.arcmenu.protocol.EditorProtocol.SetPropertyPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.SetQuickActionPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.SnapshotPacket;
import com.fentai.arcmenu.protocol.EditorProtocol.StringLimitException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditorDepthProtocolTest
{
    @Test
    void releaseAndHandshakeUseTheSameVersion()
    {
        assertEquals("3.4.1", ArcMenuEditorClient.CLIENT_VERSION);
        assertTrue(EditorProtocol.supportsClient(ArcMenuEditorClient.CLIENT_VERSION));
        assertTrue(EditorProtocol.supportsClient("3.1.0"));
        assertTrue(EditorProtocol.supportsClient("3.0.6"));
        assertTrue(EditorProtocol.supportsClient("1.2.1"));
        assertTrue(EditorProtocol.supportsClient("1.0.0"));
        assertTrue(EditorProtocol.supportsClient("1.0.1"));
        assertFalse(EditorProtocol.supportsClient("0.1.0-M3.3"));
        assertEquals(12, EditorProtocol.VERSION);
    }

    @Test
    void depthPropertyAndEditorSnapshotRoundTrip()
    {
        var property = new SetPropertyPacket(3, EditorProtocol.TAB_FRONTEND, "block", "scale.z", "7.5");
        assertEquals(property, EditorProtocol.decode(EditorProtocol.encode(property)));
        var node = new NodeSnapshot("block", "", EditorProtocol.KIND_BLOCK, 0, 0, 32, 32, 0, true, false,
                List.of(new PropertySnapshot("scale.z", EditorProtocol.PROPERTY_NUMBER, "7.5")));
        var snapshot = new SnapshotPacket(4, "models", 320, 180, false, true, "26.1.2", List.of(node), List.of());
        assertEquals(snapshot, EditorProtocol.decode(EditorProtocol.encode(snapshot)));
        var state = new EditorState();
        state.update(snapshot);
        assertEquals(snapshot, state.snapshot());
    }

    @Test
    void quickActionSwitchAndFlagRoundTrip()
    {
        var toggle = new SetQuickActionPacket(6, true);
        assertEquals(toggle, EditorProtocol.decode(EditorProtocol.encode(toggle)));
        var snapshot = new SnapshotPacket(6, EditorProtocol.DIALOG_PREFIX + "main", 320, 180, false, true, "26.3",
                List.of(), List.of(), List.of(), List.of(), false, true);
        assertEquals(snapshot, EditorProtocol.decode(EditorProtocol.encode(snapshot)));
        var state = new EditorState();
        state.update(snapshot);
        assertTrue(state.snapshot().quickAction());
    }

    @Test
    void frozenTextIgnoresLanguagePlaceholders()
    {
        assertTrue(EditorProtocol.dialogTextFreezes("Coins: %vault_eco_balance%"));
        assertTrue(EditorProtocol.dialogTextFreezes("{data:level}"));
        assertFalse(EditorProtocol.dialogTextFreezes("%arcmenu_lang_menu.title%"));
        assertFalse(EditorProtocol.dialogTextFreezes("Shop"));
    }

    @Test
    void largeChinesePropertyUsesFramedRequestsWithPreciseByteLimit()
    {
        var value = "中".repeat(EditorProtocol.MAX_STRING_BYTES / 3) + "a";
        var packet = new SetPropertyPacket(3, EditorProtocol.TAB_BACKEND, "submit", "actions", value);
        var frames = EditorProtocol.frame(1, EditorProtocol.encode(packet));
        assertTrue(frames.size() > 1);
        var assembler = new FrameAssembler();
        byte[] result = null;
        for (byte[] frame : frames)
        {
            result = assembler.accept(frame);
        }
        assertEquals(packet, EditorProtocol.decode(result));
        var failure = assertThrows(StringLimitException.class, () -> EditorProtocol
                .encode(new SetPropertyPacket(3, EditorProtocol.TAB_BACKEND, "submit", "actions", value + "a")));
        assertEquals("backend.submit.actions", failure.field());
        assertEquals(EditorProtocol.MAX_STRING_BYTES + 1, failure.byteCount());
    }
}
