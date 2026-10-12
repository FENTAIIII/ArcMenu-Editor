package com.fentai.arcmenu.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditorLanguageResourcesTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?([sdf])");
    private static final Pattern TRANSLATION_KEY = Pattern.compile("\"(arcmenu_editor\\.[A-Za-z0-9_.-]+)\"");

    @Test
    void bundledClientLanguagesHaveIdenticalNonemptyKeys() {
        JsonObject chinese = resource("/assets/arcmenu_editor/lang/zh_cn.json");
        JsonObject english = resource("/assets/arcmenu_editor/lang/en_us.json");
        assertEquals(chinese.keySet(), english.keySet());
        assertTrue(chinese.size() >= 100);
        for (String key : chinese.keySet()) {
            assertTrue(chinese.get(key).getAsString().length() > 0, key);
            assertTrue(english.get(key).getAsString().length() > 0, key);
            assertEquals(placeholders(english.get(key).getAsString()), placeholders(chinese.get(key).getAsString()), key);
        }
    }

    @Test
    void everyLiteralEditorKeyHasABundledEnglishFallback() throws Exception {
        JsonObject english = resource("/assets/arcmenu_editor/lang/en_us.json");
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/compat"))) {
            if (!Files.isDirectory(root)) continue;
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    var matcher = TRANSLATION_KEY.matcher(Files.readString(file));
                    while (matcher.find()) {
                        String key = matcher.group(1);
                        if (!key.endsWith(".")) assertTrue(english.has(key), file + ": " + key);
                    }
                }
            }
        }
    }

    @Test
    void embeddedBrowserDefaultsAndEveryEffectChoiceHaveTranslations() {
        JsonObject english = resource("/assets/arcmenu_editor/lang/en_us.json");
        for (String suffix : List.of("text", "cut", "copy", "paste", "select_all", "button"))
            assertTrue(english.has("arcmenu_editor.aui." + suffix), suffix);
        for (String choice : List.of("linear", "hold", "bezier", "auto-bezier", "ease-in", "ease-out", "ease-in-out", "value", "speed", "sound", "nbs", "action", "command", "console", "true", "false"))
            assertTrue(english.has("arcmenu_editor.effect.choice." + choice), choice);
        for (String key : List.of("file_picker.apricityui.empty", "devtools.apricityui.copy_selector", "tooltip.apricityui.devtools.close"))
            assertTrue(english.has(key), key);
    }

    private JsonObject resource(String path) {
        var stream = getClass().getResourceAsStream(path);
        assertNotNull(stream, path);
        return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private List<String> placeholders(String value) {
        var matcher = PLACEHOLDER.matcher(value);
        var arguments = new ArrayList<String>();
        int next = 1;
        while (matcher.find()) arguments.add((matcher.group(1) == null ? Integer.toString(next++) : matcher.group(1)) + ":" + matcher.group(2));
        arguments.sort(String::compareTo);
        return arguments;
    }
}
