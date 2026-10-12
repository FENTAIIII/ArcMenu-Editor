package com.fentai.arcmenu.editor.aui;

import com.mojang.blaze3d.platform.InputConstants;
import com.fentai.arcmenu.editor.aui.internal.element.Body;
import com.fentai.arcmenu.editor.aui.internal.init.Document;
import com.fentai.arcmenu.editor.aui.internal.init.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.atomic.AtomicInteger;

class AuiKeyCodesTest {
    @Test void nativeSdlSpaceCannotActivateARememberedCloseButton() {
        assertEquals(44,InputConstants.KEY_SPACE);
        var document=new Document("sdl-space-close-test",false);document.body=new Body(document);
        var close=document.body.appendChild(new Element(document,"BUTTON"));close.setAttribute("data-action","close");
        var clicks=new AtomicInteger();close.addEventListener("click",event->clicks.incrementAndGet());
        assertTrue(new AuiButtonKeys().route(AuiKeyCodes.translate(InputConstants.KEY_SPACE),close,close::click));
        assertEquals(0,clicks.get());
    }

    @Test void sdlDigitsAreTranslatedDespiteZeroFollowingNineInTheScancodeTable() {
        assertEquals(48,AuiKeyCodes.translate(InputConstants.KEY_0));
        for(int i=0;i<9;i++)assertEquals(49+i,AuiKeyCodes.translate(InputConstants.KEY_1+i));
        assertEquals(320,AuiKeyCodes.translate(InputConstants.KEY_NUMPAD0));
        for(int i=0;i<9;i++)assertEquals(321+i,AuiKeyCodes.translate(InputConstants.KEY_NUMPAD1+i));
    }

    @Test void sdlShortcutsSpacePunctuationAndNavigationKeepTheirAuiMeanings() {
        assertEquals(65,AuiKeyCodes.translate(InputConstants.KEY_A));
        assertEquals(67,AuiKeyCodes.translate(InputConstants.KEY_C));
        assertEquals(86,AuiKeyCodes.translate(InputConstants.KEY_V));
        assertEquals(88,AuiKeyCodes.translate(InputConstants.KEY_X));
        assertEquals(32,AuiKeyCodes.translate(InputConstants.KEY_SPACE));
        assertEquals(61,AuiKeyCodes.translate(InputConstants.KEY_EQUALS));
        assertEquals(91,AuiKeyCodes.translate(InputConstants.KEY_LBRACKET));
        assertEquals(257,AuiKeyCodes.translate(InputConstants.KEY_NUMPADENTER));
        assertEquals(261,AuiKeyCodes.translate(InputConstants.KEY_DELETE));
        assertEquals(258,AuiKeyCodes.translate(InputConstants.KEY_TAB));
        assertEquals(341,AuiKeyCodes.translate(InputConstants.KEY_LCONTROL));
        assertEquals(345,AuiKeyCodes.translate(InputConstants.KEY_RCONTROL));
        assertEquals(290,AuiKeyCodes.translate(InputConstants.KEY_F1));
        assertEquals(313,AuiKeyCodes.translate(InputConstants.KEY_F24));
        assertEquals(-1,AuiKeyCodes.translate(0));
    }
}
