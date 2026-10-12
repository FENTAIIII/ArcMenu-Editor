package com.fentai.arcmenu.editor.mcp;

import com.fentai.arcmenu.editor.EditorI18n;
import com.fentai.arcmenu.editor.ArcMenuEditorClient;
import com.fentai.arcmenu.editor.EditorScreen;
import com.fentai.arcmenu.editor.EffectEditorScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class McpScreen extends Screen {
    private final Screen parent;
    public McpScreen(Screen parent) { super(EditorI18n.component("arcmenu_editor.mcp.title")); this.parent = parent; }
    @Override protected void init() {
        int x = width / 2 - 150, y = height / 2 - 10;
        addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.mcp.copy"), button -> minecraft.keyboardHandler.setClipboard(EditorMcp.connectionJson())).bounds(x, y, 300, 20).build());
        addRenderableWidget(Button.builder(EditorI18n.component(EditorMcp.running() ? "arcmenu_editor.mcp.stop" : "arcmenu_editor.mcp.start"), button -> { EditorMcp.enabled(!EditorMcp.running()); rebuildWidgets(); }).bounds(x, y + 26, 300, 20).build());
        addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.mcp.back"), button -> onClose()).bounds(x, y + 52, 300, 20).build());
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractBackground(GuiGraphicsExtractor g, int x, int y, float partial) {}
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float partial) {
        g.fill(0, 0, width, height, 0xFF10131A);
        g.centeredText(font, title, width / 2, height / 2 - 82, 0xFFE8ECF4);
        g.centeredText(font, EditorMcp.endpoint(), width / 2, height / 2 - 60, 0xFF79C0FF);
        g.centeredText(font, EditorI18n.text(EditorMcp.running() ? "arcmenu_editor.mcp.running" : "arcmenu_editor.mcp.off"), width / 2, height / 2 - 43, 0xFFE8ECF4);
        g.centeredText(font, EditorI18n.text("arcmenu_editor.mcp.hint"), width / 2, height / 2 - 27, 0xFFAABCD4);
        super.extractRenderState(g, x, y, partial);
        if (!EditorMcp.status().isEmpty()) g.centeredText(font, font.plainSubstrByWidth(EditorMcp.status(), width - 20), width / 2, height - 20, 0xFFFF8E8E);
    }
    @Override public void onClose() {
        Screen next = parent;
        if (ArcMenuEditorClient.EFFECT.active() && parent instanceof EditorScreen) next = new EffectEditorScreen(ArcMenuEditorClient.state());
        else if (!ArcMenuEditorClient.EFFECT.active() && parent instanceof EffectEditorScreen) next = new EditorScreen(ArcMenuEditorClient.state());
        minecraft.setScreen(next);
    }
}
