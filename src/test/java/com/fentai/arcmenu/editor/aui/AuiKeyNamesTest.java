package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.element.Body;
import com.fentai.arcmenu.editor.aui.internal.event.KeyEvent;
import com.fentai.arcmenu.editor.aui.internal.init.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuiKeyNamesTest {
    @Test void actualVendorShortcutEventsNeedNoNativeGlfwWindowOrLibrary() {
        var document=new Document("logical-shortcuts",false);var body=document.body=new Body(document);
        for(int code:new int[]{65,67,86,88,90}) {
            var event=new KeyEvent(body,"keydown",code,0,2,false,KeyEvent.Source.SCREEN_EVENT);
            assertTrue(event.controlKey);assertEquals(Character.toString((char)(code+32)),event.key);
            assertEquals("Key"+(char)code,event.code);
        }
    }

    @Test void printableDigitsSymbolsAndFunctionKeysHaveStableLogicalNames() {
        assertEquals("0",AuiKeyNames.name(48,0));assertEquals("9",AuiKeyNames.name(57,0));
        assertEquals(" ",AuiKeyNames.name(32,0));assertEquals("=",AuiKeyNames.name(61,0));
        assertEquals("F1",AuiKeyNames.name(290,0));assertEquals("F24",AuiKeyNames.name(313,0));
        assertEquals("8",AuiKeyNames.name(328,0));assertEquals("Enter",AuiKeyNames.name(335,0));
        assertEquals("Unidentified",AuiKeyNames.name(-1,42));
    }
}
