package com.fentai.arcmenu.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceModalTest {
    private static final WorkspaceState.Project MENU = new WorkspaceState.Project("menu", "welcome", "addon/welcome.yml", 960, 540, 80, 2.5, "menu.welcome");
    private static final WorkspaceState.Project EFFECT = new WorkspaceState.Project("effect", "intro", "intro/scene.yml", 1280, 720, 120, 3, "effect.intro");

    private WorkspaceModal modal(String mode, List<WorkspaceState.Project> projects) {
        return new WorkspaceModal(mode, projects, null, new JsonObject());
    }

    @Test void createFormsUseVisibleDefaultsAndSubmitTheSelectedProjectType() {
        for (String mode : List.of("new_menu", "new_effect")) {
            var modal = modal(mode, List.of());
            assertEquals("", modal.values.get("id"));
            var parameters = modal.parameters(Map.of("id", "  new-project  ", "folder", "  addon/templates  "));
            assertEquals(mode.equals("new_menu") ? "menu" : "effect", parameters.get("type").getAsString());
            assertEquals("new-project", parameters.get("id").getAsString());
            assertEquals("addon/templates", parameters.get("folder").getAsString());
            assertEquals(800, parameters.get("width").getAsDouble());
            assertEquals(450, parameters.get("height").getAsDouble());
            assertEquals(mode.equals("new_menu") ? 42.7 : 100, parameters.get("pixelsPerBlock").getAsDouble());
            assertEquals(2, parameters.get("distance").getAsDouble());
        }
    }

    @Test void configurationPreservesTheSelectedIdentityAndPermissionWhileChangingGeometry() {
        var modal = modal("configure", List.of(MENU));
        assertEquals("960", modal.values.get("width"));
        assertEquals("2.5", modal.values.get("distance"));
        assertEquals("menu.welcome", modal.values.get("permission"));
        var parameters = modal.parameters(Map.of("width", "1920", "height", "1080", "pixelsPerBlock", "64", "distance", "1.75", "permission", "menu.updated", "id", "unrelated", "type", "effect"));
        assertEquals("welcome", parameters.get("id").getAsString());
        assertEquals("menu", parameters.get("type").getAsString());
        assertEquals(1920, parameters.get("width").getAsDouble());
        assertEquals(1080, parameters.get("height").getAsDouble());
        assertEquals(64, parameters.get("pixelsPerBlock").getAsDouble());
        assertEquals(1.75, parameters.get("distance").getAsDouble());
        assertEquals("menu.updated", parameters.get("permission").getAsString());
    }

    @Test void exportSnapshotsTheSelectionAndIncludesBothProjectTypesWithoutCeByDefault() {
        var selected = new ArrayList<>(List.of(MENU, EFFECT));
        var modal = modal("export", selected);
        selected.clear();
        assertEquals("menu-pack", modal.values.get("id"));
        var parameters = modal.parameters(Map.of("description", "  A complete menu and animation package.  "));
        assertEquals("1.0.0", parameters.get("version").getAsString());
        assertEquals(EditorI18n.text("arcmenu_editor.projects.default_pack_title"), parameters.get("title").getAsString());
        assertFalse(parameters.get("allowCe").getAsBoolean());
        assertTrue(parameters.get("confirm").getAsBoolean());
        assertEquals(2, parameters.getAsJsonArray("projects").size());
        assertEquals(MENU.reference(), parameters.getAsJsonArray("projects").get(0));
        assertEquals(EFFECT.reference(), parameters.getAsJsonArray("projects").get(1));
    }

    @Test void singleProjectExportUsesItsIdAndRequiresAnExplicitCeChoice() {
        var modal = modal("export", List.of(EFFECT));
        assertEquals("intro", modal.values.get("id"));
        assertEquals("intro", modal.values.get("title"));
        var parameters = modal.parameters(Map.of("description", "Preview with CE dependencies.", "allowCe", "true"));
        assertTrue(parameters.get("allowCe").getAsBoolean());
        assertThrows(IllegalArgumentException.class, () -> modal.parameters(Map.of("allowCe", "yes")));
    }

    @Test void requiredValuesAndInvalidGeometryDoNotProduceServerParameters() {
        assertThrows(IllegalArgumentException.class, () -> modal("new_menu", List.of()).parameters(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> modal("export", List.of(MENU)).parameters(Map.of("description", "  \n  ")));
        assertThrows(IllegalArgumentException.class, () -> modal("import", List.of()).parameters(Map.of("filename", "  ")));
        for (String field : List.of("width", "height", "pixelsPerBlock", "distance")) {
            for (String invalid : List.of("0", "-1", "NaN", "Infinity", "1e999", "bad")) {
                assertThrows(IllegalArgumentException.class, () -> modal("configure", List.of(MENU)).parameters(Map.of(field, invalid)), field + "=" + invalid);
            }
        }
    }

    @Test void publicationAndMetadataFormsRetainMultilineUnicodeValues() {
        var pack = new WorkspaceState.LocalPack("starter-1.0.0.arcpack", "starter", "1.0.0", "Starter", "Original description", new JsonObject(), new JsonArray(), new JsonArray(), 1024, "", new JsonArray(), "", false);
        var publish = new WorkspaceModal("publish", List.of(), pack, new JsonObject());
        assertEquals("Starter", publish.values.get("title"));
        String description = "First paragraph.\n\nUnicode preservation: 菜单 🎨";
        var parameters = publish.parameters(Map.of("title", "Updated title", "description", description));
        assertEquals(description, parameters.get("description").getAsString());
        assertEquals("Updated title", parameters.get("title").getAsString());
        var listing = new JsonObject();listing.addProperty("title", "Published title");listing.addProperty("description", description);
        var edit = new WorkspaceModal("edit_metadata", List.of(), null, listing);
        assertEquals(listing, edit.parameters(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> edit.parameters(Map.of("title", "")));
    }

    @Test void confirmationFormsUseExplicitServerFlagsAndTheExactSelection() {
        var deleted = modal("delete", List.of(MENU, EFFECT)).parameters(Map.of());
        assertTrue(deleted.get("confirm").getAsBoolean());
        assertEquals(MENU.reference(), deleted.getAsJsonArray("projects").get(0));
        assertEquals(EFFECT.reference(), deleted.getAsJsonArray("projects").get(1));
        assertTrue(modal("discard", List.of(MENU)).parameters(Map.of()).get("confirmDiscard").getAsBoolean());
        assertEquals("starter.arcpack", modal("import", List.of()).parameters(Map.of("filename", " starter.arcpack ")).get("filename").getAsString());
    }

    @Test void templateAndMixedPackagesExportAndDeleteTheExactReferencesWithoutGlobalConfiguration() {
        var template=new WorkspaceState.Project("template","card","groups/card.yml",800,450,100,2,"");
        var only=modal("export",List.of(template)).parameters(Map.of("description","Reusable card group"));
        assertEquals("card",only.get("id").getAsString());
        assertEquals(template.reference(),only.getAsJsonArray("projects").get(0));
        for(String operation:List.of("export","delete")) {
            var parameters=modal(operation,List.of(MENU,EFFECT,template)).parameters(Map.of("description","Menus, animations and templates"));
            assertEquals(3,parameters.getAsJsonArray("projects").size());
            assertEquals(MENU.reference(),parameters.getAsJsonArray("projects").get(0));
            assertEquals(EFFECT.reference(),parameters.getAsJsonArray("projects").get(1));
            assertEquals(template.reference(),parameters.getAsJsonArray("projects").get(2));
            assertTrue(parameters.get("confirm").getAsBoolean());
        }
        assertThrows(IllegalArgumentException.class,()->modal("configure",List.of(template)));
        assertTrue(MENU.configurable());assertTrue(EFFECT.configurable());
        assertEquals("open",MENU.openAction());assertEquals("open",EFFECT.openAction());
    }
}
