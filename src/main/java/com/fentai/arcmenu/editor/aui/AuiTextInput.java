package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.init.Element;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;

/** GLFW clients already deliver characters; SDL clients supply a native adapter. */
final class AuiTextInput implements GuiEventListener,AutoCloseable {
    private boolean focused;

    void synchronize(Element element,double[] bounds) {}
    void render(GuiGraphicsExtractor graphics) {}
    boolean enabled(){return false;}
    @Override public void setFocused(boolean value){focused=value;}
    @Override public boolean isFocused(){return focused;}
    @Override public void close(){focused=false;}
}
