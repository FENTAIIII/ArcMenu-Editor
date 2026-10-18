package com.fentai.arcmenu.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorLayoutTest {
    @Test
    void releaseDefaultsMatchTheValidatedFullScreenLayout() {
        EditorLayout layout = new EditorLayout();
        assertEquals(92, layout.toolsWidth());
        assertEquals(240, layout.inspectorWidth());
        assertEquals(120, layout.templatesHeight());
        assertEquals(1.0, layout.virtualScreenScale(), 1e-12);
        assertEquals(0.0, layout.virtualScreenOffsetX(), 1e-12);
        assertEquals(0.0, layout.virtualScreenOffsetY(), 1e-12);
    }

    @Test
    void gameOpeningRemainsExact16By9AcrossDockSizes() {
        EditorLayout layout = new EditorLayout();
        assertExactAndContained(layout.calculate(1920, 1080));

        layout.toolsWidth(143);
        layout.inspectorWidth(511);
        layout.templatesHeight(317);
        assertExactAndContained(layout.calculate(1537, 911));
    }

    @Test
    void projectPreviewUsesTheSameCenteredSixteenByNineAperture() {
        assertEquals(new EditorLayout.Rect(5, 3, 416, 234),
                EditorLayout.fit16By9(new EditorLayout.Rect(0, 0, 427, 241)));
        assertEquals(new EditorLayout.Rect(0, 0, 1280, 720),
                EditorLayout.fit16By9(new EditorLayout.Rect(0, 0, 1281, 721)));
    }

    @Test
    void virtualScreenUsesUniformScaleAndIndependentOffsets() {
        EditorLayout editor = new EditorLayout();
        EditorLayout.Layout layout = editor.calculate(1920, 1080);
        editor.virtualScreenScale(0.427);
        editor.virtualScreenOffsetX(3.25);
        editor.virtualScreenOffsetY(-2.5);

        EditorLayout.ScreenRect screen = editor.virtualScreen(layout);
        assertEquals(screen.width() * 9.0, screen.height() * 16.0, 1e-9);
        assertEquals(layout.viewport().width() * 0.427, screen.width(), 1e-9);
        assertEquals(layout.viewport().x() + layout.viewport().width() / 2.0 + 3.25,
                screen.x() + screen.width() / 2.0, 1e-9);
        assertEquals(layout.viewport().y() + layout.viewport().height() / 2.0 + 2.5,
                screen.y() + screen.height() / 2.0, 1e-9);
    }

    @Test
    void virtualScreenCalibrationPersists(@TempDir Path directory) {
        Path file = directory.resolve("arcmenu-editor.properties");
        EditorLayout saved = new EditorLayout();
        saved.virtualScreenScale(0.43125);
        saved.virtualScreenOffsetX(-1.75);
        saved.virtualScreenOffsetY(2.125);
        saved.save(file);

        EditorLayout loaded = new EditorLayout();
        loaded.load(file);
        assertEquals(0.43125, loaded.virtualScreenScale(), 1e-12);
        assertEquals(-1.75, loaded.virtualScreenOffsetX(), 1e-12);
        assertEquals(2.125, loaded.virtualScreenOffsetY(), 1e-12);
    }

    private static void assertExactAndContained(EditorLayout.Layout layout) {
        EditorLayout.Rect view = layout.viewport();
        EditorLayout.Rect host = layout.viewportHost();
        assertEquals(0, view.width() % 16);
        assertEquals(0, view.height() % 9);
        assertEquals(view.width() * 9, view.height() * 16);
        assertTrue(view.x() >= host.x() && view.y() >= host.y());
        assertTrue(view.right() <= host.right() && view.bottom() <= host.bottom());
    }
}
