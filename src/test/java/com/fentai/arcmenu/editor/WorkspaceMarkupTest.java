package com.fentai.arcmenu.editor;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceMarkupTest {
    @Test void authenticationFailureShowsEscapedServiceReasonAndAnExplicitRetry() {
        String markup=WorkspaceMarkup.authentication("Sign-in failed", "Local projects remain available", "HTTP <502> & unavailable", false, "Retry");
        assertTrue(markup.contains("id='authentication_error'"));
        assertTrue(markup.contains("HTTP &lt;502&gt; &amp; unavailable"));
        assertTrue(markup.contains("data-action='login'"));
        assertFalse(markup.contains("modal"));
    }

    @Test void pendingAuthenticationShowsProgressWithoutOfferingAnotherAttempt() {
        String markup=WorkspaceMarkup.authentication("Checking session", "You can keep editing", "", true, "Retry");
        assertTrue(markup.contains("role='status'"));
        assertTrue(markup.contains("Checking session"));
        assertFalse(markup.contains("authentication_error"));
        assertFalse(markup.contains("data-action='login'"));
    }

    @Test void templateRowsOfferPreviewAndRenderTheDistinctTemplateIconAndType() {
        var template=new WorkspaceState.Project("template","card","arcpack-starter/card.yml",800,450,100,2,"","card-root",4);
        var row=new WorkspaceState.Row(template.key(),template.id(),2,template,false);
        String markup=WorkspaceMarkup.projects(List.of(row),List.of(template));
        assertTrue(markup.contains("row-resource-icon template"));
        assertTrue(markup.contains(WorkspaceMarkup.icon("template")));
        assertTrue(markup.contains(WorkspaceMarkup.text("type.template")));
        assertTrue(markup.contains("data-action='toggle_project' data-id='template:card'"));
        assertTrue(markup.contains("aria-checked='true'"));
        assertTrue(markup.contains("data-action='open_project' data-id='template:card'"));
        assertTrue(markup.contains(EditorI18n.text("arcmenu_editor.projects.preview")));
        assertFalse(markup.contains("data-action='configure'"));
    }

    @Test void templateDetailsDescribeTheGroupWithoutOfferingMenuGlobalConfiguration() {
        var template=new WorkspaceState.Project("template","card","arcpack-starter/card.yml",800,450,100,2,"","card<&>",4);
        String details=WorkspaceMarkup.projectDetails(template);
        assertTrue(details.contains("card&lt;&amp;&gt;"));
        assertTrue(details.contains(EditorI18n.text("arcmenu_editor.projects.template_nodes")));
        assertTrue(details.contains(EditorI18n.text("arcmenu_editor.projects.template_hint")));
        assertFalse(details.contains(EditorI18n.text("arcmenu_editor.projects.field.pixelsPerBlock")));
        var menu=new WorkspaceState.Project("menu","menu","menu.yml",800,450,100,2,"");
        assertTrue(WorkspaceMarkup.projectDetails(menu).contains(EditorI18n.text("arcmenu_editor.projects.field.pixelsPerBlock")));
        assertNotEquals(WorkspaceMarkup.icon("menu"),WorkspaceMarkup.icon("template"));
    }
}
