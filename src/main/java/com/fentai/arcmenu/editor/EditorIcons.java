package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Icons sourced from the MIT-licensed bbs-mod atlas; see META-INF/LICENSE_bbs-mod.txt. */
final class EditorIcons {
    private static final Identifier ATLAS = Identifier.fromNamespaceAndPath(
            "arcmenu_editor", "textures/gui/editor_icons.png");

    static final Icon SAVE = icon(48, 0);
    static final Icon ADD = icon(64, 0);
    static final Icon DUPLICATE = icon(80, 0);
    static final Icon REMOVE = icon(96, 0);
    static final Icon LOCKED = icon(160, 0);
    static final Icon UNLOCKED = icon(176, 0);
    static final Icon COPY = icon(192, 0);
    static final Icon PASTE = icon(208, 0);
    static final Icon CUT = icon(224, 0);
    static final Icon FOLDER = icon(48, 16);
    static final Icon IMAGE = icon(64, 16);
    static final Icon GIF = icon(64, 16);
    static final Icon EDIT = icon(80, 16);
    static final Icon MATERIAL = icon(96, 16);
    static final Icon CLOSE = icon(112, 16);
    static final Icon BLOCK = icon(240, 16);
    static final Icon VISIBLE = icon(16, 32);
    static final Icon INVISIBLE = icon(32, 32);
    static final Icon MOVE_TO = icon(224, 32);
    static final Icon LINE = icon(192, 48);
    static final Icon REDO = icon(208, 48);
    static final Icon UNDO = icon(224, 48);
    static final Icon PROPERTIES = icon(32, 64);
    static final Icon FONT = icon(48, 64);
    static final Icon TRASH = icon(240, 64);
    static final Icon OUTLINE = icon(16, 96);
    static final Icon LIST = icon(144, 96);
    static final Icon SQUARE = icon(80, 144);
    static final Icon CURSOR = icon(32, 240);
    static final Icon CHEVRON_RIGHT = new Icon(152, 16, 6, 16);
    static final Icon CHEVRON_LEFT = new Icon(146, 16, 6, 16);

    private EditorIcons() {}

    private static Icon icon(int u, int v) {
        return new Icon(u, v, 16, 16);
    }

    static void draw(GuiGraphicsExtractor graphics, Icon icon, int x, int y) {
        draw(graphics, icon, x, y, 0xFFFFFFFF);
    }

    static void draw(GuiGraphicsExtractor graphics, Icon icon, int x, int y, int color) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, icon.u(), icon.v(),
                icon.width(), icon.height(), icon.width(), icon.height(), 256, 256, color);
        if (icon == GIF) {
            graphics.fill(x+10,y+9,x+11,y+15,color);
            graphics.fill(x+11,y+10,x+12,y+14,color);
            graphics.fill(x+12,y+11,x+13,y+13,color);
        }
    }

    record Icon(int u, int v, int width, int height) {}
}
