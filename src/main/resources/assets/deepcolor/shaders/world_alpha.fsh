#version 330
#extension GL_ARB_separate_shader_objects : require

// Drawn with only alpha writes enabled. Alpha 0 marks every pixel as world; the interface drawn
// afterwards raises alpha wherever it covers the world, so the final pass knows how much of each
// pixel is world. See HdrExpandPass.
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(0.0);
}
