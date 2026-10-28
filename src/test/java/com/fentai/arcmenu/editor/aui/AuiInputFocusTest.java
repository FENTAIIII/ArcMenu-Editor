package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.element.Body;
import com.fentai.arcmenu.editor.aui.internal.element.TextArea;
import com.fentai.arcmenu.editor.aui.internal.init.Document;
import com.fentai.arcmenu.editor.aui.internal.init.Element;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AuiInputFocusTest {
    @Test void theWorkspaceSearchFieldAndItsIconFocusTheSingleEditableSearchInput() throws Exception {
        var root=body();
        try(var resource=getClass().getResourceAsStream("/assets/arcmenu_editor/workspace/workspace.html")) {
            assertNotNull(resource);
            String html=new String(resource.readAllBytes(),StandardCharsets.UTF_8);
            root.setInnerHTML(html.substring(html.indexOf("<body>")+6,html.indexOf("</body>")));
        }
        var input=root.document.getElementById("search");
        var field=input.parentElement;
        assertEquals(1,root.querySelectorAll("input[type=search]").size());
        assertSame(input,AuiInputFocus.control(field));
        assertSame(input,AuiInputFocus.control(field.querySelector("svg")));
        var clear=root.document.getElementById("search_clear");
        clear.removeAttribute("class");
        assertSame(clear,AuiInputFocus.control(clear.querySelector("svg")));
        assertTrue(AuiInputFocus.editable(input));
    }

    private static Body body() {
        AuiElements.register();var document=new Document("input-focus-test",false);
        return document.body=new Body(document);
    }
    private static Element append(Element parent,String tag) {
        return parent.appendChild(Element.init(new Element(parent.document,tag)));
    }

    @Test void clickingLabelTextOrRequiredMarkerResolvesItsTextInput() {
        var root=body();var input=append(root,"INPUT");input.setAttribute("id","name");
        var label=append(root,"LABEL");label.setAttribute("for","name");
        var marker=append(label,"SPAN");marker.setTextContent("*");
        assertSame(input,AuiInputFocus.control(label));assertSame(input,AuiInputFocus.control(marker));
        assertTrue(AuiInputFocus.editable(input));
    }

    @Test void labelNestedInsideAnotherActionKeepsFocusOnItsEditableControl() {
        var root=body();var row=append(root,"DIV");row.setAttribute("data-action","select_project");
        var label=append(row,"LABEL");var field=append(label,"TEXTAREA");
        assertSame(field,AuiInputFocus.control(label));assertSame(field,AuiInputFocus.control(field));
        assertTrue(AuiInputFocus.editable(field));
    }

    @Test void readonlyFieldsCanReceiveFocusButNeverEnableNativeTextEntry() {
        var root=body();var field=append(root,"INPUT");field.setAttribute("readonly","");
        assertSame(field,AuiInputFocus.control(field));assertFalse(AuiInputFocus.editable(field));
    }

    @Test void disabledHiddenAndNonTextControlsNeverEnableNativeTextEntry() {
        var root=body();var field=append(root,"INPUT");field.setAttribute("disabled","");
        assertNull(AuiInputFocus.control(field));assertFalse(AuiInputFocus.editable(field));
        field.removeAttribute("disabled");field.setAttribute("hidden","");
        assertNull(AuiInputFocus.control(field));assertFalse(AuiInputFocus.editable(field));
        field.removeAttribute("hidden");field.setAttribute("type","checkbox");
        assertSame(field,AuiInputFocus.control(field));assertFalse(AuiInputFocus.editable(field));
        assertFalse(AuiInputFocus.editable(append(root,"SELECT")));
    }

    @Test void labelsDoNotFocusAnUnavailableAssociatedControl() {
        var root=body();var label=append(root,"LABEL");label.setAttribute("for","missing");
        assertNull(AuiInputFocus.control(label));
        var input=append(root,"INPUT");input.setAttribute("id","missing");input.setAttribute("disabled","");
        assertNull(AuiInputFocus.control(label));
    }

    @Test void repaintingAnUnrelatedFragmentKeepsTheEditableNodeAndSelection() {
        var root=body();var field=(TextArea)append(root,"TEXTAREA");var review=append(root,"DIV");
        field.setValue("First line\nSecond line");field.focus();field.setSelectionRange(6,10);
        review.setInnerHTML("<span>New preview</span>");
        assertSame(field,root.document.getFocusedElement());assertEquals("First line\nSecond line",field.getValue());
        assertEquals(6,field.getSelectionStart());assertEquals(10,field.getSelectionEnd());
    }

    @Test void buttonDescendantsFocusTheButtonAndDoNotMasqueradeAsTextEntry() {
        var root=body();var button=append(root,"BUTTON");var caption=append(button,"SPAN");
        assertSame(button,AuiInputFocus.control(caption));assertFalse(AuiInputFocus.editable(button));
        assertNull(AuiInputFocus.control(append(root,"DIV")));
    }
}
