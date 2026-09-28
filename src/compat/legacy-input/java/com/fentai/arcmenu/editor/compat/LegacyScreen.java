package com.fentai.arcmenu.editor.compat;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bridges pre-1.21.9 callbacks while retaining vanilla widget input handling. */
public abstract class LegacyScreen extends Screen {
    protected LegacyScreen(Component title) { super(title); }

    public boolean isInGameUi() { return true; }

    @Override
    public final boolean mouseClicked(double x, double y, int button) {
        return mouseClicked(new MouseButtonEvent(x, y, button), false);
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(event.x(), event.y(), event.button());
    }

    @Override
    public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return mouseDragged(new MouseButtonEvent(x, y, button), dx, dy);
    }

    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return super.mouseDragged(event.x(), event.y(), event.button(), dx, dy);
    }

    @Override
    public final boolean mouseReleased(double x, double y, int button) {
        return mouseReleased(new MouseButtonEvent(x, y, button));
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(event.x(), event.y(), event.button());
    }

    @Override
    public final boolean keyPressed(int key, int scancode, int modifiers) {
        return keyPressed(new KeyEvent(key, scancode, modifiers));
    }

    public boolean keyPressed(KeyEvent event) {
        return super.keyPressed(event.key(), event.scancode(), event.modifiers());
    }
}
