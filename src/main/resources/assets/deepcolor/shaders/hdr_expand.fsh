#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

#ifdef VULKAN
layout(push_constant) uniform DeepColorExpand {
    float boost;
    float exponent;
    int mapping;
    float gamma;
};
#else
uniform float boost;
uniform float exponent;
uniform int mapping;
uniform float gamma;
#endif

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// Shared with dither.fsh. gamma is a power-law exponent, or 0 for the piecewise sRGB curve. Both
// extend past 1.0, so values brighter than SDR white survive storage in the float main target.
vec3 decodeCurve(vec3 c) {
    if (gamma > 0.0) {
        return pow(c, vec3(gamma));
    }
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

vec3 encodeCurve(vec3 l) {
    if (gamma > 0.0) {
        return pow(l, vec3(1.0 / gamma));
    }
    return mix(l * 12.92, 1.055 * pow(l, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), l));
}

// Runs on the finished world, before the interface is drawn, so menus and text keep their own
// brightness. Values are linear light where 1.0 is the interface white.
void main() {
    vec4 source = texture(InSampler, texCoord);
    vec3 color = mix(source.rgb, vec3(0.0), notEqual(source.rgb, source.rgb));
    vec3 light = decodeCurve(max(color, 0.0));

    if (mapping == 0) {
        // Highlight expansion: scale by luminance so hues stay put. White reaches the full boost. The
        // exponent grows with the boost, so a gray at half brightness never gains more than 15%.
        float luminance = dot(light, vec3(0.2126, 0.7152, 0.0722));
        light *= 1.0 + boost * pow(clamp(luminance, 0.0, 1.0), exponent);
    } else {
        // Linear stretch: the whole world image scaled up evenly.
        light *= 1.0 + boost;
    }
    fragColor = vec4(encodeCurve(light), source.a);
}
