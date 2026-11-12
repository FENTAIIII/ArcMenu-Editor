package com.fentai.arcmenu.editor;

/** Independent Effect panel geometry; ordinary menu-editor layout preferences are untouched. */
final class EffectWorkspaceLayout
{
    int left = -1;
    int right = -1;
    int bottom = -1;

    record Layout(EditorLayout.Rect project, EditorLayout.Rect composition, EditorLayout.Rect viewport,
            EditorLayout.Rect preview, EditorLayout.Rect properties, EditorLayout.Rect timeline)
    {}

    Layout calculate(int width, int height)
    {
        int top = 40;
        int gap = 3;
        int leftWidth = Math.max(90, Math.min(width / 3, left < 0 ? width * 18 / 100 : left));
        int rightWidth = Math.max(110, Math.min(width / 3, right < 0 ? width * 18 / 100 : right));
        int bottomHeight = Math.max(110, Math.min(height - top - 100, bottom < 0 ? height * 40 / 100 : bottom));
        int lower = height - bottomHeight;
        var project = new EditorLayout.Rect(gap, top, leftWidth - gap, lower - top - gap);
        var composition = new EditorLayout.Rect(leftWidth + gap, top, width - leftWidth - rightWidth - gap * 2,
                lower - top - gap);
        int previewH = Math.max(88, height * 25 / 100);
        var preview = new EditorLayout.Rect(width - rightWidth + gap, top, rightWidth - gap * 2, previewH);
        var properties = new EditorLayout.Rect(width - rightWidth + gap, top + previewH + gap, rightWidth - gap * 2,
                height - top - previewH - gap * 2);
        var timeline = new EditorLayout.Rect(gap, lower, width - rightWidth - gap * 2, bottomHeight - gap);
        var host = new EditorLayout.Rect(composition.x() + 10, composition.y() + 32,
                Math.max(16, composition.width() - 20), Math.max(9, composition.height() - 53));
        int unit = Math.max(1, Math.min(host.width() / 16, host.height() / 9));
        int viewportWidth = unit * 16;
        int viewportHeight = unit * 9;
        var viewport = new EditorLayout.Rect(host.x() + (host.width() - viewportWidth) / 2,
                host.y() + (host.height() - viewportHeight) / 2, viewportWidth, viewportHeight);
        return new Layout(project, composition, viewport, preview, properties, timeline);
    }
}
