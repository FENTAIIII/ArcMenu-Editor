package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import com.fentai.arcmenu.protocol.VisionProtocol.TextInput;
import com.fentai.arcmenu.protocol.VisionProtocol.TextState;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class VisionTextProtocolTest
{
    @Test
    void nativeTextAndDraftFinishRetainWhitespaceUnicodeAndTheCurrentEditorNonce()
    {
        var state = new TextState(12, 35, true, "Input", " 中文 😀 ", 50);
        assertEquals(state, VisionProtocol.decode(VisionProtocol.encode(state)));
        var finish = new TextInput(12, 35, VisionProtocol.TEXT_FINISH, " 中文 😀 ");
        assertEquals(finish, VisionProtocol.decode(VisionProtocol.encode(finish)));
    }

    @Test
    void theClientCannotSendTruncatedSupplementaryCharactersOrOverlengthText()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new TextInput(12, 35, VisionProtocol.TEXT_UPDATE, "a".repeat(51)));
        assertThrows(IllegalArgumentException.class, () -> new TextInput(12, 35, VisionProtocol.TEXT_UPDATE, "\uD83D"));
    }
}
