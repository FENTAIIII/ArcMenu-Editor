#version 330

uniform sampler2D InSampler;

layout(std140) uniform EditorViewport {
    vec4 ViewportRect;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = (texCoord - ViewportRect.xy) / ViewportRect.zw;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) {
        discard;
    }
    fragColor = texture(InSampler, uv);
}
