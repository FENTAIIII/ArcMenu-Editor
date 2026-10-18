package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class VisionPreviewProtocolTest {
    @Test void editorPreviewHasAnIndependentSceneAndClosePacketWithoutCursorState() {
        var scene = new VisionProtocol.Scene(52, 12, 64, -9, 45, 10, 70, List.of(17, 18, 21));
        for (boolean active : new boolean[]{true, false}) {
            var packet = new VisionProtocol.PreviewScene(active, scene);
            var decoded = VisionProtocol.decode(VisionProtocol.encode(packet));
            assertInstanceOf(VisionProtocol.PreviewScene.class, decoded);
            assertEquals(packet, decoded);
        }
    }
}
