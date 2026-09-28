package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EditorAssetLibraryTest {
    @Test void imagesOpenTheMediaPanelUntilSelectIsClicked() {
        var library = new EditorAssetLibrary();
        assertFalse(library.imageMode());
        library.selectTool(EditorProtocol.KIND_IMAGE);
        library.selectTool(EditorProtocol.KIND_IMAGE);
        assertTrue(library.imageMode(), "Clicking Image again must not switch to templates");
        library.selectTool(EditorProtocol.KIND_RECTANGLE);
        assertTrue(library.imageMode());
        library.selectTool((byte) -1);
        assertFalse(library.imageMode());
    }

    @Test void foldersShowOnlyTheirImmediateFilesAndChildFolders() {
        var library = new EditorAssetLibrary();
        library.update(List.of(image("/ui/buttons/confirm.png"), image("/logo.png"), image("/ui/panel.png"), image("/icons/star.png")));
        assertEquals(List.of("", "icons", "ui", "ui/buttons"), library.folders());
        assertEquals(List.of("icons", "ui", "logo.png"), library.entries().stream().map(EditorAssetLibrary.Entry::name).toList());
        library.openFolder("ui");
        assertEquals(List.of("buttons", "panel.png"), library.entries().stream().map(EditorAssetLibrary.Entry::name).toList());
        library.openFolder("ui/buttons");
        assertEquals("confirm.png", library.entries().getFirst().name());
        assertEquals("/ui/buttons/confirm.png", library.entries().getFirst().image().path());
        library.up();
        assertEquals("ui", library.folder());
    }

    @Test void internalCursorAndTooltipAssetsDoNotBecomeUserFoldersOrImages() {
        var library = new EditorAssetLibrary();
        library.update(List.of(image("/mouse/mouse.png"), image("/mouse/choose.png"), image("/tooltip/default.png"),
                image("/ui/tooltips/border.png"), image("/local_cursor/diagnostic.png"), image("/tooltip.png"),
                image("/ui/.cache/tile.png"), image("/ui/mousepad.png"), image("/ui/tooltip-guide.png")));
        assertEquals(List.of("", "ui"), library.folders());
        library.openFolder("ui");
        assertEquals(List.of("mousepad.png", "tooltip-guide.png"), library.entries().stream().map(EditorAssetLibrary.Entry::name).toList());
        assertFalse(EditorAssetLibrary.isUserImage("\\MOUSE\\choose.PNG"));
    }

    @Test void snapshotsKeepTheCurrentFolderAndImageUnlessTheyWereRemoved() {
        var library = new EditorAssetLibrary();
        var available = List.of(image("/ui/icons/star.png"), image("/ui/panel.png"));
        library.update(available);
        library.openFolder("ui/icons");
        library.select("/ui/icons/star.png");
        library.update(available);
        assertEquals("ui/icons", library.folder());
        assertNotNull(library.selected());
        library.update(List.of(image("/ui/panel.png")));
        assertEquals("ui", library.folder());
        assertNull(library.selected());
    }

    @Test void oldImageSnapshotsMapDirectlyToExistingPackTexturesWithoutNewProtocolFields() {
        assertEquals("textures/images/ui/panel.png", EditorAssetLibrary.texturePath("/ui/panel.png"));
        assertEquals("textures/images/ui/panel.png", EditorAssetLibrary.texturePath("ui\\panel.png"));
    }

    @Test void previewsFitWideAndTallImagesWithoutStretching() {
        var box = new EditorLayout.Rect(10, 20, 100, 50);
        assertEquals(new EditorLayout.Rect(10, 32, 100, 25), EditorImagePreview.fit(400, 100, box));
        assertEquals(new EditorLayout.Rect(53, 20, 13, 50), EditorImagePreview.fit(100, 400, box));
        assertEquals(new EditorLayout.Rect(35, 20, 50, 50), EditorImagePreview.fit(100, 100, box));
    }

    private static EditorProtocol.ImageSnapshot image(String path) { return new EditorProtocol.ImageSnapshot(path, 128, 64); }
}
