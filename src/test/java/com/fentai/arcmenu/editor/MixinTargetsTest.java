package com.fentai.arcmenu.editor;

import com.google.gson.JsonParser;
import com.llamalad7.mixinextras.wrapper.factory.FactoryRedirectWrapperMixinTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Checks actual target bytecode, without starting Minecraft or loading its classes. */
final class MixinTargetsTest {
    @Test
    void visionSceneStaysBelowVanillaMessagesAndCursorStaysAbove() throws Exception {
        var cursor = read("com/fentai/arcmenu/editor/VisionCursorClient");
        assertTrue(cursor.methods.stream().anyMatch(method -> method.name.equals("controlling")),
                "every maintained version must provide Vision input");
        var render = cursor.methods.stream().filter(method -> method.name.equals("render")).findFirst().orElseThrow();
        assertFalse(calls(render).contains("com/fentai/arcmenu/editor/VisionSceneClient.extract"),
                "the post-HUD cursor pass must not cover chat or titles with the menu scene");
        var hudStream = getClass().getResourceAsStream("/com/fentai/arcmenu/editor/mixin/HudMixin.class");
        if (hudStream != null) {
            var hud = new ClassNode();
            try (hudStream) { new ClassReader(hudStream).accept(hud, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); }
            MethodNode background = null;
            MethodNode foreground = null;
            for (var method : hud.methods) {
                var calls = calls(method);
                if (calls.contains("com/fentai/arcmenu/editor/VisionSceneClient.extract")) background = method;
                if (calls.contains("com/fentai/arcmenu/editor/VisionCursorClient.render")) foreground = method;
            }
            assertNotNull(background);
            assertNotNull(foreground);
            assertInjectsAt(background, "extractRenderState", "HEAD");
            assertInjectsAt(foreground, "extractRenderState", "RETURN");
            var backgroundCalls = calls(background);
            int editor = backgroundCalls.indexOf("com/fentai/arcmenu/editor/EditorScreen.isEditor");
            assertTrue(editor >= 0 && editor < backgroundCalls.indexOf("com/fentai/arcmenu/editor/VisionSceneClient.extract"),
                    "editor previews retain their separate viewport rendering policy");
        } else {
            var renderer = read("com/fentai/arcmenu/editor/mixin/GameRendererMixin");
            var handler = renderer.methods.stream().filter(method -> calls(method)
                    .contains("com/fentai/arcmenu/editor/VisionSceneClient.extract")).findFirst().orElseThrow();
            var calls = calls(handler);
            int scene = calls.indexOf("com/fentai/arcmenu/editor/VisionSceneClient.extract");
            int messages = -1;
            for (int i = 0; i < calls.size(); i++) {
                if (calls.get(i).equals("net/minecraft/client/gui/Gui.extractRenderState")
                        || calls.get(i).equals("net/minecraft/client/gui/Gui.render")) messages = i;
            }
            int pointer = calls.indexOf("com/fentai/arcmenu/editor/VisionCursorClient.render");
            assertTrue(scene < messages && messages < pointer, "scene, vanilla HUD, cursor must be extracted in that order");
        }
        var scene = read("com/fentai/arcmenu/editor/VisionSceneClient");
        for (var method : scene.methods) {
            var calls = calls(method);
            int picture = -1;
            int nextStratum = -1;
            for (int i = 0; i < calls.size(); i++) {
                if (calls.get(i).endsWith(".addPicturesInPictureState") || calls.get(i).endsWith(".submitPicturesInPictureState")) picture = i;
                if (calls.get(i).endsWith(".nextStratum")) nextStratum = i;
            }
            if (picture >= 0) assertTrue(picture < nextStratum,
                    "vanilla messages must begin above the extracted menu scene");
        }
    }

    @Test
    void visionPreservesHudPreferencesAndOnlySuppressesTheCrosshair() throws Exception {
        var cursor = read("com/fentai/arcmenu/editor/VisionCursorClient");
        for (var method : cursor.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field) {
                    assertNotEquals("hideGui", field.name, "Vision must leave the player's F1 preference untouched");
                }
                if (instruction instanceof MethodInsnNode call) {
                    assertFalse(call.owner.endsWith("/gui/Hud") && call.name.equals("toggle"),
                            "Vision must not toggle the complete vanilla HUD");
                }
            }
        }
        boolean supported = cursor.methods.stream().anyMatch(method -> method.name.equals("controlling"));
        try (var jar = new JarFile(System.getProperty("arcmenu.runtimeJar"));
             var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("arcmenu-editor.mixins.json")),
                     StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            boolean registered = config.getAsJsonArray("client").asList().stream()
                    .anyMatch(entry -> entry.getAsString().equals("VisionHudMixin"));
            assertEquals(supported, registered, "only native Vision adapters need a crosshair hook");
        }
        if (!supported) return;
        var mixin = read("com/fentai/arcmenu/editor/mixin/VisionHudMixin");
        var handlers = mixin.methods.stream().filter(method -> method.name.startsWith("arcmenu$")).toList();
        assertEquals(1, handlers.size());
        var handler = handlers.getFirst();
        var injection = annotations(handler.visibleAnnotations, handler.invisibleAnnotations).stream()
                .filter(annotation -> annotation.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        var targetAnnotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                .filter(annotation -> annotation.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
        var gui = read(((Type) ((List<?>) value(targetAnnotation, "value")).getFirst()).getInternalName());
        String crosshair = gui.methods.stream().anyMatch(method -> method.name.equals("renderCrosshair"))
                ? "renderCrosshair" : "extractCrosshair";
        assertEquals(List.of(crosshair), value(injection, "method"),
                "chat, titles, action bars and other HUD messages must retain vanilla rendering");
        assertEquals(Boolean.TRUE, value(injection, "cancellable"));
        var at = (AnnotationNode) ((List<?>) value(injection, "at")).getFirst();
        assertEquals("HEAD", value(at, "value"));
        boolean checksSession = false;
        for (var instruction : handler.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(cursor.name)
                    && call.name.equals("active")) checksSession = true;
        }
        assertTrue(checksSession, "only an active Vision session hides the crosshair, including native chat or focus interruptions");
    }

    @Test
    void redirectsRemainReadableByOlderMixinExtras() throws Exception {
        try (var jar = new JarFile(System.getProperty("arcmenu.runtimeJar"));
             var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("arcmenu-editor.mixins.json")),
                     StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            String prefix = config.get("package").getAsString().replace('.', '/') + "/";
            for (var entry : config.getAsJsonArray("client")) {
                var mixin = new ClassNode();
                try (var stream = jar.getInputStream(jar.getJarEntry(prefix + entry.getAsString() + ".class"))) {
                    new ClassReader(stream).accept(mixin, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                for (var handler : mixin.methods) {
                    for (var annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                        if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")) {
                            assertInstanceOf(AnnotationNode.class, value(annotation, "at"),
                                    mixin.name + "." + handler.name
                                            + " must store a single @At for MixinExtras 0.5.4 and earlier");
                        }
                    }
                }
                new FactoryRedirectWrapperMixinTransformer().transform(null, mixin);
            }
        }
    }

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
                                if (injection.desc.endsWith("/Inject;")) {
                                    Type[] arguments = Type.getArgumentTypes(handler.desc);
                                    if (arguments.length > 1) {
                                        List<Type> captured = List.of(arguments).subList(0, arguments.length - 1);
                                        assertTrue(candidates.stream().anyMatch(candidate ->
                                                        captured.equals(List.of(Type.getArgumentTypes(candidate.desc)))),
                                                context + " must capture all target arguments before CallbackInfo");
                                    }
                                }
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

    private static List<String> calls(MethodNode method) {
        var result = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) result.add(call.owner + "." + call.name);
        }
        return result;
    }

    private static void assertInjectsAt(MethodNode method, String target, String point) {
        var injection = annotations(method.visibleAnnotations, method.invisibleAnnotations).stream()
                .filter(annotation -> annotation.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of(target), value(injection, "method"));
        var at = (AnnotationNode) ((List<?>) value(injection, "at")).getFirst();
        assertEquals(point, value(at, "value"));
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
