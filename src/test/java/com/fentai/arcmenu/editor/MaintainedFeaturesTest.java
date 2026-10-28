package com.fentai.arcmenu.editor;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** A maintained build must contain the actual Vision implementation and its installed hooks. */
final class MaintainedFeaturesTest {
    @Test void everyBuildProvidesVisionScenesAndEditorPreviews() throws Exception {
        var scene = read("VisionSceneClient");
        var supported = scene.methods.stream().filter(method -> method.name.equals("supported")).findFirst().orElseThrow();
        assertEquals(Opcodes.ICONST_1, firstOpcode(supported));
        var preview = scene.methods.stream().filter(method -> method.name.equals("receivePreview")).findFirst().orElseThrow();
        boolean storesPreview = false;
        for (var instruction : preview.instructions)
            if (instruction instanceof FieldInsnNode field && field.name.equals("preview")
                    && field.getOpcode() == Opcodes.PUTSTATIC) storesPreview = true;
        assertTrue(storesPreview, "receiving a preview must store its scene");
        assertCalls(scene, "extractScene", "extractEntity");
        assertCalls(scene, "extractScene", "apply");
        assertCalls(scene, "clear", "clear");
        assertCalls(read("VisionCursorClient"), "hello", "send");
        assertCalls(read("VisionTextClient"), "character", "charTyped");
        assertCalls(read("VisionTextClient"), "key", "keyPressed");
    }

    @Test void packagedHooksIncludeTheSameUserFeatures() throws Exception {
        try (var jar = new JarFile(System.getProperty("arcmenu.runtimeJar"));
             var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("arcmenu-editor.mixins.json")), StandardCharsets.UTF_8)) {
            var mixins = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("client")
                    .asList().stream().map(value -> value.getAsString()).toList();
            for (String hook : List.of("VisionMouseMixin", "VisionKeyboardMixin", "VisionHudMixin", "VisionTextKeyboardMixin",
                    "VisionGraphicsAccessor", "VisionGuiRendererMixin", "VisionEditorScreenMixin", "VisionWorkspacePreviewMixin", "VisionItemLightingMixin")) {
                assertTrue(mixins.contains(hook), hook);
                assertNotNull(jar.getJarEntry("com/fentai/arcmenu/editor/mixin/" + hook + ".class"), hook);
            }
        }
    }

    private static int firstOpcode(MethodNode method) {
        for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) return instruction.getOpcode();
        return -1;
    }

    private static void assertCalls(ClassNode owner, String methodName, String calledName) {
        var method = owner.methods.stream().filter(candidate -> candidate.name.equals(methodName)).findFirst().orElseThrow();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals(calledName)) return;
        fail(owner.name + "." + methodName + " must call " + calledName);
    }

    private ClassNode read(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/com/fentai/arcmenu/editor/" + name + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
