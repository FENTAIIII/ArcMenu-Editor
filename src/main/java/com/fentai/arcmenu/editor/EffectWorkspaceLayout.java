package com.fentai.arcmenu.editor;

/** Independent Effect panel geometry; ordinary menu-editor layout preferences are untouched. */
final class EffectWorkspaceLayout {
    int left = -1, right = -1, bottom = -1;
    record Layout(EditorLayout.Rect project, EditorLayout.Rect composition, EditorLayout.Rect viewport,
                  EditorLayout.Rect preview, EditorLayout.Rect properties, EditorLayout.Rect timeline) {}
    Layout calculate(int width, int height) {
        int top = 40, gap = 3;
        int l = Math.max(90, Math.min(width / 3, left < 0 ? width * 18 / 100 : left));
        int r = Math.max(110, Math.min(width / 3, right < 0 ? width * 18 / 100 : right));
        int b = Math.max(110, Math.min(height - top - 100, bottom < 0 ? height * 40 / 100 : bottom));
        int lower = height - b;
        var project = new EditorLayout.Rect(gap, top, l - gap, lower - top - gap);
        var composition = new EditorLayout.Rect(l + gap, top, width - l - r - gap * 2, lower - top - gap);
        int previewH = Math.max(88, height * 25 / 100);
        var preview = new EditorLayout.Rect(width - r + gap, top, r - gap * 2, previewH);
        var properties = new EditorLayout.Rect(width - r + gap, top + previewH + gap, r - gap * 2, height - top - previewH - gap * 2);
        var timeline = new EditorLayout.Rect(gap, lower, width - r - gap * 2, b - gap);
        var host = new EditorLayout.Rect(composition.x() + 10, composition.y() + 32, Math.max(16, composition.width() - 20), Math.max(9, composition.height() - 53));
        int unit = Math.max(1, Math.min(host.width() / 16, host.height() / 9)); int w = unit * 16, h = unit * 9;
        var viewport = new EditorLayout.Rect(host.x() + (host.width() - w) / 2, host.y() + (host.height() - h) / 2, w, h);
        return new Layout(project, composition, viewport, preview, properties, timeline);
    }
}
