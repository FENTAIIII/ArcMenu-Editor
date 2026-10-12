package com.fentai.arcmenu.editor;

import com.google.gson.JsonParser;
import com.fentai.arcmenu.editor.aui.internal.ui.UiTranslations;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Exercises the game's live language rather than selecting an editor-specific locale. */
@ResourceLock("minecraft-language")
final class EditorI18nTest {
    private final Language original = Language.getInstance();

    @AfterEach void restoreLanguage() {
        Language.inject(original);
        EditorI18n.languageVersion();
    }

    @Test void stringsAndExistingComponentsFollowTheActiveGameLanguage() {
        Language.inject(language("en_us", Map.of()));
        var title = EditorI18n.component("arcmenu_editor.projects.default_pack_title");
        assertEquals("Configuration pack", title.getString());
        assertEquals("Cut", EditorI18n.text("arcmenu_editor.aui.cut"));
        assertEquals("Frame must be an integer", EditorI18n.text("arcmenu_editor.effect.validation.integer", "Frame"));

        Language.inject(language("zh_cn", Map.of()));
        assertEquals("配置包", title.getString());
        assertEquals("剪切", EditorI18n.text("arcmenu_editor.aui.cut"));
        assertEquals("帧必须是整数", EditorI18n.text("arcmenu_editor.effect.validation.integer", "帧"));
    }

    @Test void unbundledGameLanguagesRetainEnglishFallbackAndResourceOverrides() {
        // Minecraft loads en_us before the selected locale and higher-priority packs.
        Language.inject(language("fr_fr", Map.of("arcmenu_editor.aui.copy", "Copier")));
        assertEquals("Copier", EditorI18n.text("arcmenu_editor.aui.copy"));
        assertEquals("Paste", EditorI18n.text("arcmenu_editor.aui.paste"));
        assertEquals("Configuration pack", EditorI18n.text("arcmenu_editor.projects.default_pack_title"));
        assertEquals("missing.translation", EditorI18n.text("missing.translation"));
    }

    @Test void languageVersionChangesOnReloadWithoutChangingTheSelectedLocale() {
        Language first = language("en_us", Map.of());
        Language.inject(first);
        long before = EditorI18n.languageVersion();
        assertEquals(before, EditorI18n.languageVersion());
        var copy = EditorI18n.component("arcmenu_editor.aui.copy");
        assertEquals("Copy", copy.getString());

        Language.inject(language("en_us", Map.of("arcmenu_editor.aui.copy", "Copy text")));
        assertTrue(EditorI18n.languageVersion() > before);
        assertEquals("Copy text", copy.getString());
        long after = EditorI18n.languageVersion();
        assertEquals(after, EditorI18n.languageVersion());
    }

    @Test void embeddedLibraryAccessibilityTextUsesTheGameLanguageWithoutASeparateMod() {
        Language.inject(language("zh_cn", Map.of()));
        assertEquals("没有匹配的文件", UiTranslations.translate("file_picker.apricityui.empty"));
        Language.inject(language("en_us", Map.of("file_picker.apricityui.empty", "No resources match")));
        assertEquals("No resources match", UiTranslations.translate("file_picker.apricityui.empty"));
    }

    @Test void propertyLabelsFollowTranslationsAndPreserveUnknownExtensionNames() {
        Language.inject(language("en_us", Map.of("arcmenu_editor.property.extension_alpha", "Extension opacity")));
        assertEquals("Extension opacity", EditorI18n.property("extension.alpha"));
        assertEquals("Extension opacity", EditorI18n.property("extension-alpha"));
        assertEquals("custom.plugin.value", EditorI18n.property("custom.plugin.value"));
        Language.inject(language("zh_cn", Map.of("arcmenu_editor.property.extension_alpha", "扩展透明度")));
        assertEquals("扩展透明度", EditorI18n.property("extension.alpha"));
    }

    @Test void hoverEventLabelsAndHelpFollowTheGameLanguageAndResourceOverrides() {
        Language.inject(language("en_us", Map.of()));
        assertEquals("On hover", EditorI18n.property("on-hover"));
        assertEquals("On leave", EditorI18n.property("on-leave"));
        assertTrue(EditorI18n.propertyHint("on-hover").contains("animation: track-id"));
        assertTrue(EditorI18n.propertyHint("on-leave").contains("\\n"));
        assertEquals("", EditorI18n.propertyHint("custom.plugin.value"));

        Language.inject(language("zh_cn", Map.of()));
        assertEquals("悬浮进入", EditorI18n.property("on-hover"));
        assertEquals("悬浮离开", EditorI18n.property("on-leave"));
        assertTrue(EditorI18n.propertyHint("on-hover").contains("触发一次"));

        Language.inject(language("fr_fr", Map.of("arcmenu_editor.property.on_hover", "Au survol",
                "arcmenu_editor.property.on_hover_hint", "Reactions au survol")));
        assertEquals("Au survol", EditorI18n.property("on-hover"));
        assertEquals("Reactions au survol", EditorI18n.propertyHint("on-hover"));
        assertEquals("On leave", EditorI18n.property("on-leave"));
    }

    @Test void effectChoicesAndMarketplaceReasonsChangeLanguageWithoutChangingProjectDataOrDiagnostics() {
        String diagnostic = "Dependency entries must contain at most 2048 UTF-16 code units";
        var failure = new MarketClient.RequestException(400, "invalid_manifest", diagnostic);
        String target = "menu.banner";
        String asset = "arcpack-aimv/audio/beat.ogg";

        Language.inject(language("zh_cn", Map.of()));
        assertEquals("贝塞尔", EffectDialog.choiceLabel("interpolation", "bezier"));
        assertEquals("ArcMenu 动作", EffectDialog.choiceLabel("kind", "action"));
        assertEquals("开启", EffectDialog.choiceLabel("enabled", "true"));
        assertEquals(target, EffectDialog.choiceLabel("target", target));
        assertEquals(asset, EffectDialog.choiceLabel("source", asset));
        assertEquals("每个依赖项最多包含 2048 个 UTF-16 单元。", MarketMessages.error(failure));
        assertEquals(diagnostic, failure.getMessage());

        Language.inject(language("en_us", Map.of()));
        assertEquals("Bezier", EffectDialog.choiceLabel("interpolation", "bezier"));
        assertEquals("ArcMenu action", EffectDialog.choiceLabel("kind", "action"));
        assertEquals("Enabled", EffectDialog.choiceLabel("enabled", "true"));
        assertEquals(target, EffectDialog.choiceLabel("target", target));
        assertEquals(asset, EffectDialog.choiceLabel("source", asset));
        assertEquals("Each dependency may contain at most 2048 UTF-16 units.", MarketMessages.error(failure));
        assertEquals(diagnostic, failure.getMessage());
        assertEquals("invalid_manifest", failure.code());
    }

    private Language language(String locale, Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>();
        load("en_us", values);
        if (!locale.equals("en_us")) load(locale, values);
        values.putAll(overrides);
        return new Language() {
            @Override public String getOrDefault(String key, String fallback) { return values.getOrDefault(key, fallback); }
            @Override public boolean has(String key) { return values.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
        };
    }

    private void load(String locale, Map<String, String> values) {
        var stream = getClass().getResourceAsStream("/assets/arcmenu_editor/lang/" + locale + ".json");
        if (stream == null) return;
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonParser.parseReader(reader).getAsJsonObject().entrySet().forEach(entry -> values.put(entry.getKey(), entry.getValue().getAsString()));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
