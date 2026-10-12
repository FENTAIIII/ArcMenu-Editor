package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.WorkspaceMarkup;
import com.fentai.arcmenu.editor.WorkspaceState;
import com.fentai.arcmenu.editor.aui.internal.element.Body;
import com.fentai.arcmenu.editor.aui.internal.element.AbstractText;
import com.fentai.arcmenu.editor.aui.internal.init.Document;
import com.fentai.arcmenu.editor.aui.internal.init.Element;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuiActionsTest {
    @Test void checkboxKeepsItsToggleActionWhileTheProjectNameSelectsItsRow() {
        AuiElements.register();var state=new WorkspaceState();
        state.update(JsonParser.parseString("{\"projects\":[{\"type\":\"effect\",\"id\":\"a\",\"path\":\"a\"}],\"packs\":[]}").getAsJsonObject());
        var document=new Document("checkbox-action-test",false);var body=document.body=new Body(document);
        body.setInnerHTML(WorkspaceMarkup.projects(state.rows(""),List.of()));
        Element row=document.getElementById("resource_effect:a");
        Element checkbox=document.getElementById("checkbox_effect:a");
        assertNotNull(checkbox);assertEquals("BUTTON",checkbox.tagName);assertEquals("checkbox",checkbox.getAttribute("role"));
        assertEquals("false",checkbox.getAttribute("aria-checked"));assertEquals("toggle_project",AuiActions.target(checkbox).getAttribute("data-action"));
        assertEquals("select_project",AuiActions.target(row.querySelector("strong")).getAttribute("data-action"));
        checkbox.setInnerHTML(WorkspaceMarkup.icon("check"));
        assertSame(checkbox,AuiActions.target(checkbox.querySelector("path")));
    }

    @Test void disabledCheckboxDoesNotFallThroughToTheEnclosingRowAction() {
        var document=new Document("disabled-action-test",false);var body=document.body=new Body(document);
        var row=body.appendChild(new Element(document,"DIV"));row.setAttribute("data-action","select_project");
        var checkbox=row.appendChild(new Element(document,"BUTTON"));checkbox.setAttribute("data-action","toggle_project");checkbox.setAttribute("disabled","");
        var caption=checkbox.appendChild(new Element(document,"SPAN"));
        assertNull(AuiActions.target(caption));assertSame(row,AuiActions.target(row));
    }

    @Test void existingLongDescriptionsRemainEditableAndTitlesFollowTheMarketLimit() {
        AuiElements.register();var document=new Document("metadata-input-limits",false);var body=document.body=new Body(document);
        body.setInnerHTML(WorkspaceMarkup.field("field_description","Description","","","textarea",true)
                +WorkspaceMarkup.field("field_title","Title","","","text",true));
        var description=(AbstractText)document.getElementById("field_description");
        description.setValue("x".repeat(1024));description.setSelectionRange(1024,1024);description.insertText(" more");
        assertEquals(1029,description.getValue().length());assertTrue(description.getValue().endsWith(" more"));
        var title=(AbstractText)document.getElementById("field_title");title.insertText("x".repeat(129));
        assertEquals(128,title.getValue().length());
    }
}
