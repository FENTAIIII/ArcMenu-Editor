package com.fentai.arcmenu.editor;

import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;

/** Uses Minecraft's active client language and standard resource-pack fallback. */
public final class EditorI18n {
    private static Language lastLanguage;
    private static long languageVersion;

    private EditorI18n() {}

    /** Changes when Minecraft replaces its language after a selection or resource reload. */
    public static synchronized long languageVersion() {
        Language current = Language.getInstance();
        if (current != lastLanguage) {
            lastLanguage = current;
            languageVersion++;
        }
        return languageVersion;
    }

    public static String text(String key, Object... arguments) {
        return Component.translatable(key, arguments).getString();
    }

    /** Keeps unknown extension property names intact while translating standard labels. */
    public static String property(String key) {
        String translation = "arcmenu_editor.property." + key.replace('.', '_').replace('-', '_');
        return Language.getInstance().has(translation) ? text(translation) : key;
    }

    /** Optional inspector help follows the same language and resource-pack overrides as labels. */
    public static String propertyHint(String key) {
        String translation = "arcmenu_editor.property." + key.replace('.', '_').replace('-', '_') + "_hint";
        return Language.getInstance().has(translation) ? text(translation) : "";
    }

    public static Component component(String key, Object... arguments) {
        return Component.translatable(key, arguments);
    }
}
