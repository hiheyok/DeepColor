#version 330
#extension GL_ARB_separate_shader_objects : require

// Undoes the HDR brightness expansion for screenshots, which are 8-bit SDR images.
// InSampler is the main target as it is now: the expanded world with the interface drawn over it.
// WorldSampler is the world as it was before the expansion.
//
// Much of the interface darkens the world evenly: the vignette multiplies the whole screen, and
// menu and chat backgrounds blend black over it. There the main target is the expanded world times
// one factor, the same in every channel, and the SDR image would have been the original world times
// that factor. Anything else (text, icons, colored overlays) is interface and is kept as it is.
uniform sampler2D InSampler;
uniform sampler2D WorldSampler;

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

// Same as hdr_expand.fsh, which writes what is compared here.
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

vec3 expand(vec3 color) {
    color = mix(color, vec3(0.0), notEqual(color, color));
    vec3 light = decodeCurve(max(color, 0.0));
    if (mapping == 0) {
        float luminance = dot(light, vec3(0.2126, 0.7152, 0.0722));
        light *= 1.0 + boost * pow(clamp(luminance, 0.0, 1.0), exponent);
    } else {
        light *= 1.0 + boost;
    }
    return encodeCurve(light);
}

void main() {
    vec4 current = texture(InSampler, texCoord);
    vec4 world = texture(WorldSampler, texCoord);
    vec3 expanded = expand(world.rgb);
    // Channels too dark to give a reliable ratio are left out of the comparison.
    vec3 usable = step(vec3(0.002), expanded);
    if (dot(usable, vec3(1.0)) < 0.5) {
        // The world is black here, where the expansion changes nothing.
        fragColor = current;
        return;
    }
    vec3 ratio = current.rgb / max(expanded, vec3(0.002));
    float factor = dot(ratio * usable, vec3(1.0)) / dot(usable, vec3(1.0));
    vec3 spread = abs(ratio - factor) * usable;
    // Both sides went through 16-bit float storage, which keeps about 3 decimal digits, and darkened
    // channels can round in different directions. Black channels must stay black.
    bool evenlyScaled = max(spread.r, max(spread.g, spread.b)) <= 0.004 + 0.004 * factor
        && factor <= 1.004
        && all(lessThanEqual(current.rgb * (1.0 - usable), vec3(0.002)));
    fragColor = evenlyScaled ? vec4(world.rgb * min(factor, 1.0), current.a) : current;
}
