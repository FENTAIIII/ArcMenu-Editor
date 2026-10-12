package com.fentai.arcmenu.editor;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceStateTest {
    private WorkspaceState state(){var state=new WorkspaceState();state.update(JsonParser.parseString("""
            {"projects":[{"type":"menu","id":"a","path":"a.yml"},{"type":"menu","id":"b","path":"folder/b.yml"},{"type":"menu","id":"c","path":"folder/c.yml"},{"type":"effect","id":"a","path":"folder/a/scene.yml"}],"packs":[],"marketUrl":"https://example.org","marketCertificateSha256":"abc"}
            """).getAsJsonObject());return state;}
    @Test void foldersFollowRelativeDirectoriesAndCollapseOnlyTheirOwnType(){var state=state();assertEquals(4,state.rows("").stream().filter(r->!r.folder()).count());state.toggleFolder("menu/folder");assertEquals(List.of("effect:a","menu:a"),state.rows("").stream().filter(r->!r.folder()).map(WorkspaceState.Row::key).toList());assertEquals(1,state.rows("folder/c").stream().filter(r->!r.folder()).count());}
    @Test void shiftRangeIgnoresFolderRowsAndControlTogglesAcrossTypes(){var state=state();var rows=state.rows("");var b=state.projects().stream().filter(p->p.id().equals("b")).findFirst().orElseThrow();var c=state.projects().stream().filter(p->p.id().equals("c")).findFirst().orElseThrow();state.select(b,false,false,rows);state.select(c,false,true,rows);assertEquals(List.of("b","c"),state.selection().stream().map(WorkspaceState.Project::id).toList());state.select(b,true,false,rows);assertEquals(List.of("c"),state.selection().stream().map(WorkspaceState.Project::id).toList());assertEquals("menu",state.references().get(0).getAsJsonObject().get("type").getAsString());}
    @Test void catalogueRefreshRetainsExistingSelectionsWithoutConfusingSameIds(){var state=state();state.selectAll(state.rows(""));state.update(JsonParser.parseString("{\"projects\":[{\"type\":\"effect\",\"id\":\"a\"}],\"packs\":[]}").getAsJsonObject());assertEquals(1,state.selection().size());assertEquals("effect",state.selection().getFirst().type());state.clear();assertTrue(state.selection().isEmpty());assertEquals("",state.marketUrl());assertEquals("",state.marketCertificateSha256());}
    @Test void pinAndServerPlaneSettingsRemainExact(){var state=state();assertEquals("abc",state.marketCertificateSha256());var p=state.projects().getFirst();assertEquals(100,p.pixelsPerBlock());assertEquals(2,p.distance());assertThrows(UnsupportedOperationException.class,()->state.projects().clear());}
    @Test void preparedArchivesRemainSeparateFromProjectsAndInstalledPackages(){
        var state=state();state.update(JsonParser.parseString("""
            {"projects":[],"packs":[{"id":"installed","title":"Installed","version":"1"}],"localPacks":[{"filename":"bundle-1.arcpack","id":"bundle","version":"1","title":"Bundle","description":"Local package","projects":[{"type":"effect","id":"removed-source","path":"effects/removed-source"}],"prepared":true,"previews":["effect-removed-source.png"],"cover":"effect-removed-source.png","size":421}]}
            """).getAsJsonObject());
        assertEquals(1,state.localPacks().size());assertEquals("installed",state.packs().getFirst().id());var pack=state.localPacks().getFirst();assertTrue(pack.prepared());assertEquals("effect-removed-source.png",pack.cover());assertEquals("removed-source",pack.captureProjects().getFirst().id());assertTrue(state.projects().isEmpty());state.clear();assertTrue(state.localPacks().isEmpty());
    }
    @Test void templateFoldersAndMetadataAreListedWithoutMixingOtherTypeFoldersIntoTheFilter(){
        var state=state();state.update(JsonParser.parseString("""
            {"projects":[{"type":"menu","id":"shared","path":"addon/shared.yml"},{"type":"effect","id":"shared","path":"shared/scene.yml"},{"type":"template","id":"shared","path":"arcpack-starter/groups/shared.yml","rootId":"card","nodeCount":4},{"type":"template","id":"bare","path":"bare.yml"}]}
            """).getAsJsonObject());
        var template=state.projects().stream().filter(p->p.type().equals("template")&&p.id().equals("shared")).findFirst().orElseThrow();
        assertEquals("card",template.rootId());assertEquals(4,template.nodeCount());
        assertTrue(template.previewOnly());assertFalse(template.configurable());assertEquals("preview-open",template.openAction());
        var rows=state.rows("","template");
        assertEquals(List.of("template","template/arcpack-starter","template/arcpack-starter/groups"),rows.stream().filter(WorkspaceState.Row::folder).map(WorkspaceState.Row::key).toList());
        assertTrue(rows.stream().filter(row->!row.folder()).allMatch(row->row.project().type().equals("template")));
        assertEquals(1,state.rows("arcpack-starter","template").stream().filter(row->!row.folder()).count());
        state.toggleFolder("template/arcpack-starter");
        assertEquals(List.of("bare"),state.rows("","template").stream().filter(row->!row.folder()).map(row->row.project().id()).toList());
        assertEquals(1,state.rows("","menu").stream().filter(row->!row.folder()).count());
        var bare=state.projects().stream().filter(p->p.id().equals("bare")).findFirst().orElseThrow();
        assertEquals("",bare.rootId());assertEquals(0,bare.nodeCount());
    }
    @Test void mixedSelectionAndTemplateOnlyPackageCaptureKeepEveryTypeAndExactIdentity(){
        var state=new WorkspaceState();state.update(JsonParser.parseString("""
            {"projects":[{"type":"menu","id":"same"},{"type":"effect","id":"same"},{"type":"template","id":"same","path":"groups/same.yml"}],"localPacks":[{"filename":"templates.arcpack","id":"templates","projects":[{"type":"template","id":"same","path":"templates/groups/same.yml"}]}]}
            """).getAsJsonObject());
        state.selectAll(state.rows(""));
        assertEquals(List.of("effect:same","menu:same","template:same"),state.selection().stream().map(WorkspaceState.Project::key).toList());
        assertEquals(3,state.references().size());
        var capture=state.localPacks().getFirst().captureProjects();
        assertEquals(1,capture.size());assertEquals("template",capture.getFirst().type());
        assertEquals("same",capture.getFirst().id());assertEquals("templates/groups/same.yml",capture.getFirst().path());
        assertEquals(capture.getFirst().reference(),state.references().get(2));
    }
    @Test void communityCapabilitiesHideEffectsAndResetForNextServer() {
        var state=new WorkspaceState();assertTrue(state.effectsSupported());
        state.update(JsonParser.parseString("{\"capabilities\":{\"effects\":false,\"shaders\":false,\"gifs\":true}}").getAsJsonObject());
        assertFalse(state.effectsSupported());
        state.updateCapabilities(JsonParser.parseString("{\"marketUrl\":\"https://example.com\"}").getAsJsonObject());
        assertFalse(state.effectsSupported());state.clear();assertTrue(state.effectsSupported());
    }
    @Test void marketplaceCapabilityBlocksCommunityGuestsWithoutChangingOlderPaidServers() {
        var state=new WorkspaceState();assertTrue(state.marketAllowed());
        state.updateCapabilities(JsonParser.parseString("{\"capabilities\":{\"market\":false,\"effects\":false}}").getAsJsonObject());
        assertFalse(state.marketAllowed());
        state.updateCapabilities(JsonParser.parseString("{\"marketUrl\":\"https://example.com\"}").getAsJsonObject());
        assertFalse(state.marketAllowed());state.clear();assertTrue(state.marketAllowed());
    }
}
