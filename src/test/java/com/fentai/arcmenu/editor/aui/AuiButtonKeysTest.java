package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.element.AbstractText;
import com.fentai.arcmenu.editor.aui.internal.element.Body;
import com.fentai.arcmenu.editor.aui.internal.init.Document;
import com.fentai.arcmenu.editor.aui.internal.init.Element;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AuiButtonKeysTest {
    @Test void spaceDoesNotClickARememberedCloseButtonOrAnyPointerFocusedAction() {
        var document=document();var close=button(document,"close");var clicks=new AtomicInteger();
        close.addEventListener("click",event->clicks.incrementAndGet());
        var keys=new AuiButtonKeys();
        assertTrue(keys.route(32,close,close::click));
        assertEquals(0,clicks.get());
        keys.tabFocused(close);keys.reset();
        assertTrue(keys.route(32,close,close::click));
        assertEquals(0,clicks.get());
    }

    @Test void deliberateTabFocusKeepsSpaceButtonAndCheckboxActivation() {
        var document=document();var refresh=button(document,"refresh");var checkbox=button(document,"toggle_project");
        checkbox.setAttribute("role","checkbox");var clicks=new AtomicInteger();
        checkbox.addEventListener("click",event->clicks.incrementAndGet());
        var keys=new AuiButtonKeys();keys.tabFocused(checkbox);
        assertTrue(keys.route(32,refresh,refresh::click));
        assertEquals(0,clicks.get());
        assertTrue(keys.route(32,checkbox,checkbox::click));
        assertEquals(1,clicks.get());
    }

    @Test void enterStillActivatesPointerFocusedButtonsButNeverDisabledControls() {
        var document=document();var close=button(document,"close");var clicks=new AtomicInteger();
        close.addEventListener("click",event->clicks.incrementAndGet());var keys=new AuiButtonKeys();
        assertTrue(keys.route(257,close,close::click));
        assertTrue(keys.route(335,close,close::click));
        assertEquals(2,clicks.get());
        close.setAttribute("aria-disabled","true");keys.tabFocused(close);
        assertTrue(keys.route(32,close,close::click));
        assertTrue(keys.route(257,close,close::click));
        assertEquals(2,clicks.get());
    }

    @Test void spaceIsLeftToCharacterInputInTitlesAndMultilineDescriptions() {
        var document=document();document.body.setInnerHTML("<input id='title'/><textarea id='description'></textarea>");
        var keys=new AuiButtonKeys();var clicks=new AtomicInteger();
        for(String id:new String[]{"title","description"}) {
            var field=(AbstractText)document.getElementById(id);
            assertFalse(keys.route(32,field,clicks::incrementAndGet));
            field.insertText("two words");assertEquals("two words",field.getValue());
        }
        assertEquals(0,clicks.get());
        assertTrue(keys.route(32,null,clicks::incrementAndGet));
        assertEquals(0,clicks.get());
    }

    private static Document document() {
        AuiElements.register();var document=new Document("button-keys-test",false);
        document.body=new Body(document);return document;
    }

    private static Element button(Document document,String action) {
        var element=document.body.appendChild(new Element(document,"BUTTON"));
        element.setAttribute("data-action",action);return element;
    }
}
