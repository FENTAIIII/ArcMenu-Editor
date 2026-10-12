#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(std140) uniform EditorViewport {
    vec4 ViewportRect;
    vec4 SourceRect;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 uv = (texCoord - ViewportRect.xy) / ViewportRect.zw;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) {
        discard;
    }
    fragColor = texture(InSampler, SourceRect.xy + uv * SourceRect.zw);
}
