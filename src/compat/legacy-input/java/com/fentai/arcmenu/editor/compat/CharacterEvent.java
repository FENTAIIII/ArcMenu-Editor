package com.fentai.arcmenu.editor.compat;

/** Character input bridge for Minecraft versions before 1.21.9. */
public record CharacterEvent(int codepoint, int modifiers) {}
