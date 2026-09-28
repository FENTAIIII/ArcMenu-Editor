package com.fentai.arcmenu.editor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

final class ViewportShaderTest {
    @Test
    void viewportPipelineInitializesBeforeRendererStartup() {
        assertDoesNotThrow(EditorWorldCompositor::initialize);
    }

    @Test
    void viewportShaderCompilesToSpirv() throws Exception {
        String source;
        try (var stream = getClass().getResourceAsStream("/assets/arcmenu_editor/shaders/core/viewport_blit.fsh")) {
            assertNotNull(stream);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        assertNotEquals(0, compiler);
        assertNotEquals(0, options);
        try {
            // Minecraft upgrades GLSL and assigns bindings during backend compilation.
            shaderc_compile_options_set_forced_version_profile(options, 450, shaderc_profile_core);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            shaderc_compile_options_set_auto_map_locations(options, true);
            long result = shaderc_compile_into_spv(compiler, source, shaderc_fragment_shader,
                    "viewport_blit.fsh", "main", options);
            assertNotEquals(0, result);
            try {
                assertEquals(shaderc_compilation_status_success, shaderc_result_get_compilation_status(result),
                        shaderc_result_get_error_message(result));
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}
