package com.fentai.arcmenu.editor;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Checks actual target bytecode, without starting Minecraft or loading its classes. */
final class MixinTargetsTest {
    @Test
    void everyRequiredInjectionMatchesTheSelectedMinecraftVersion() throws Exception {
        var stream = getClass().getResourceAsStream("/arcmenu-editor.mixins.json");
        assertNotNull(stream);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            String prefix = config.get("package").getAsString().replace('.', '/') + "/";
            for (var entry : config.getAsJsonArray("client")) {
                var mixin = read(prefix + entry.getAsString());
                var annotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                        .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
                var targets = (List<?>) value(annotation, "value");
                for (var targetType : targets) {
                    var target = read(((Type) targetType).getInternalName());
                    for (var handler : mixin.methods) {
                        for (var injection : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                            if (!injection.desc.endsWith("/Inject;") && !injection.desc.endsWith("/Redirect;")) continue;
                            var methods = (List<?>) value(injection, "method");
                            Object atValue = value(injection, "at");
                            var ats = atValue instanceof List<?> list ? list : List.of(atValue);
                            for (var methodName : methods) {
                                var candidates = target.methods.stream()
                                        .filter(m -> m.name.equals(methodName) || (m.name + m.desc).equals(methodName)).toList();
                                String context = mixin.name + "." + handler.name + " -> " + target.name + "." + methodName;
                                assertFalse(candidates.isEmpty(), context + " missing method");
                                for (var atObject : ats) {
                                    var at = (AnnotationNode) atObject;
                                    if (!"INVOKE".equals(value(at, "value"))) continue;
                                    String invocation = (String) value(at, "target");
                                    long matches = candidates.stream().flatMap(m -> {
                                        var calls = new ArrayList<String>();
                                        for (var instruction : m.instructions) {
                                            if (instruction instanceof MethodInsnNode call) {
                                                calls.add("L" + call.owner + ";" + call.name + call.desc);
                                            }
                                        }
                                        return calls.stream();
                                    }).filter(invocation::equals).count();
                                    assertTrue(matches > 0, context + " missing invocation " + invocation);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var result = new ArrayList<AnnotationNode>();
        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);
        return result;
    }

    private ClassNode read(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/" + name + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
