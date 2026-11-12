package com.fentai.arcmenu.editor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

/** Live modifier-key state, for handlers whose input event does not carry modifiers. */
final class KeyboardModifiers
{
    private KeyboardModifiers()
    {}

    static boolean shiftDown(Minecraft minecraft)
    {
        return InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_RSHIFT);
    }

    static boolean controlDown(Minecraft minecraft)
    {
        return InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_LCONTROL)
                || InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_RCONTROL);
    }

    static boolean altDown(Minecraft minecraft)
    {
        return InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_LALT)
                || InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_RALT);
    }
}
