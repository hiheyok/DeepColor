#version 330
#extension GL_ARB_separate_shader_objects : require

// Drawn with only alpha writes enabled: makes the main target opaque again, for when it has to be
// shown without the final pass after world_alpha.fsh cleared its alpha. See HdrExpandPass.
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(1.0);
}
