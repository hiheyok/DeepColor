#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
uniform sampler2D NoiseSampler;

#ifdef VULKAN
layout(push_constant) uniform DeepColorParams {
    float ditherLsb;
    float outputLsb;
    float strength;
    float noiseOffset;
    int mode;
    int pattern;
    int encoding;
    float gamma;
    float uiScale;
    float peakScale;
    int hdrActive;
    float floorScale;
    float gamutStretch;
    int gamutTarget;
    float unitNits;
};
#else
uniform float ditherLsb;
uniform float outputLsb;
uniform float strength;
uniform float noiseOffset;
uniform int mode;
uniform int pattern;
uniform int encoding;
uniform float gamma;
uniform float uiScale;
uniform float peakScale;
uniform int hdrActive;
uniform float floorScale;
uniform float gamutStretch;
uniform int gamutTarget;
uniform float unitNits;
#endif

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const float LSB_8 = 1.0 / 255.0;
const float GRADIENT_MAX = 32.0 / 255.0;
const float BLACK_EDGE = 0.004;
const int BLUE_NOISE_SIZE = 64;

// Interleaved gradient noise (Jimenez 2014). Cheaper than blue noise but more visible.
float gradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

// Void-and-cluster rank, stored as high byte in red and low byte in green. See BlueNoise.java.
float blueNoise(vec2 pixel) {
    ivec2 cell = ivec2(mod(pixel, float(BLUE_NOISE_SIZE)));
    vec2 bytes = floor(texelFetch(NoiseSampler, cell, 0).rg * 255.0 + 0.5);
    return (bytes.r * 256.0 + bytes.g + 0.5) / float(BLUE_NOISE_SIZE * BLUE_NOISE_SIZE);
}

// noiseOffset steps by the golden ratio each frame. That walks every pixel through its range evenly,
// so a few frames average out to the true level. Offsetting the position instead would make some
// frame pairs nearly repeat.
float uniformNoise(vec2 pixel) {
    float u = pattern == 0 ? blueNoise(pixel) : gradientNoise(pixel);
    return fract(u + noiseOffset);
}

// Remaps a uniform value in [0, 1) to a triangular distribution in (-1, 1). Unlike adding two noise
// samples this keeps the spatial character of the source noise.
float triangular(float uniformValue) {
    float centered = uniformValue * 2.0 - 1.0;
    return sign(centered) * (1.0 - sqrt(max(0.0, 1.0 - abs(centered))));
}

vec3 uniformNoise3(vec2 pixel) {
    // Separate offsets decorrelate the channels, so the grain stays neutral instead of colored.
    return vec3(
        uniformNoise(pixel),
        uniformNoise(pixel + vec2(23.0, 41.0)),
        uniformNoise(pixel + vec2(47.0, 11.0))
    );
}

// Adds noise in units of the given step, keeping the mean of the rounded result equal to the input.
// Triangular noise spans two steps, so near either end of the range it would be clipped: a pure 0
// would come out as a lifted, grainy floor, which is very visible on an OLED. So the noise never
// reaches past the ends, at any strength:
// - In the last step it is uniform and at most one step wide. 0 and 1 stay exact, and anything in
//   between still averages to its true level: a quarter step above black lights a quarter of the
//   pixels. (Fading the triangular noise out instead rounded everything below a quarter step to
//   black and pulled the rest of the step too dark.)
// - Above that the triangular noise is limited to the distance to the end, which only matters for
//   strengths above 1.
vec3 dither(vec3 color, float stepSize, float amount, vec2 pixel) {
    vec3 u = uniformNoise3(pixel);
    vec3 triangularNoise = vec3(triangular(u.r), triangular(u.g), triangular(u.b));
    vec3 headroom = min(color, 1.0 - color) / stepSize;
    vec3 lastStep = (u - 0.5) * min(amount, 1.0);
    vec3 inside = triangularNoise * min(vec3(amount), headroom);
    return color + mix(lastStep, inside, step(1.0, headroom)) * stepSize;
}

// Rounds to levels of stepSize and writes them as output codes that land back on the same level
// under both rounding and truncation. That matters when dithering for 8 bits on a 10-bit surface:
// the desktop compositor converts to 8 bits, and must not lose or shift what the dither did.
vec3 toLevels(vec3 color, float stepSize) {
    vec3 levels = floor(clamp(color, 0.0, 1.0) / stepSize + 0.5);
    vec3 codes = ceil(levels * (stepSize / outputLsb) - 0.001);
    return clamp(codes * outputLsb, 0.0, 1.0);
}

// Same curve as hdr_expand.fsh, extended past 1.0 for values brighter than SDR white.
vec3 decodeCurve(vec3 c) {
    if (gamma > 0.0) {
        return pow(c, vec3(gamma));
    }
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

vec3 srgbDecode(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// Shadow floor. OLED panels in HDR mode clip very dark levels to black, so a dark gradient ends in a
// hard edge. This lifts only the darkest values above the floor: 0 stays 0, a value of a quarter of
// the floor already reaches 88% of it, and anything far above the floor gains just the floor itself.
// The curve is smooth, so the gradient into black stays a gradient.
float toe(float value) {
    if (floorScale <= 0.0) {
        return value;
    }
    return value + floorScale * (1.0 - exp(-value / (0.25 * floorScale)));
}

// Applied to luminance and scaled back onto the color, so dark colors keep their hue.
vec3 toeColor(vec3 light) {
    float luminance = dot(light, LUMA);
    return luminance > 0.0 ? light * (toe(luminance) / luminance) : light;
}

// Reads the game's sRGB colors as if they were in a wider gamut and converts them to scRGB (Rec.709
// primaries). The result can go negative or above 1 per channel, which scRGB allows, so saturated
// colors use the panel's wide gamut, like a monitor's native-gamut SDR mode does. Every row sums to
// 1, so grays and white are unchanged. Derived from the published primaries, all with a D65 white.
// GLSL matrices are column-major: each group of three below is one column.
const mat3 P3_TO_709 = mat3(
    1.2249402, -0.0420570, -0.0196376,
    -0.2249402, 1.0420570, -0.0786360,
    0.0, 0.0, 1.0982736
);
const mat3 ADOBE_TO_709 = mat3(
    1.3983557, 0.0, 0.0,
    -0.3983557, 1.0, -0.0429290,
    0.0, 0.0, 1.0429290
);
const mat3 BT2020_TO_709 = mat3(
    1.6604910, -0.1245505, -0.0181508,
    -0.5876411, 1.1328999, -0.1005789,
    -0.0728499, -0.0083494, 1.1187297
);

vec3 stretchGamut(vec3 light) {
    if (gamutStretch <= 0.0) {
        return light;
    }
    mat3 toRec709 = gamutTarget == 1 ? ADOBE_TO_709 : gamutTarget == 2 ? BT2020_TO_709 : P3_TO_709;
    return mix(light, toRec709 * light, gamutStretch);
}

// scRGB is linear light where 1.0 means 80 nits (unitNits; on macOS EDR 1.0 is SDR white, counted as
// 203 nits). With HDR on, SDR white goes to the chosen
// interface brightness and the shadow curve is applied here, so Windows' own sRGB decoding of SDR
// apps (the washed-out look) never happens. With Windows HDR off the compositor maps scRGB 0..1
// back to SDR, so the plain sRGB decode reproduces the normal image exactly.
vec3 encodeScrgb(vec3 c) {
    if (hdrActive == 1) {
        return min(toeColor(stretchGamut(decodeCurve(max(c, 0.0))) * uiScale), vec3(peakScale));
    }
    return srgbDecode(clamp(c, 0.0, 1.0));
}

// Near-black test pattern for HDR, in absolute nits, to find where the panel stops showing detail.
// Strip 0: continuous log ramp from 0.0001 to 1 nit, with true black at the far left.
// Strip 1: the same ramp through the shadow floor.
// Strip 2: patches at fixed levels, with black gaps between them.
// Strip 3: the game's own darkest shades (RGB 0 to 32) at the current settings.
const float PATCH_NITS[8] = float[8](0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2);

// Grey step chart: the same eight 8-bit shades in SDR and in HDR, so that photos of both can be
// compared patch by patch. Black gaps separate the patches. HDR applies the current curve settings
// but not the world brightness expansion, so it compares exactly the SDR-to-HDR mapping.
const float STEP_CODES[8] = float[8](2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 255.0);

vec3 stepChart() {
    float cell = texCoord.x * 8.0;
    if (fract(cell) < 0.1 || texCoord.y < 0.3 || texCoord.y > 0.7) {
        return vec3(0.0);
    }
    return vec3(STEP_CODES[int(min(cell, 7.0))] / 255.0);
}

vec3 hdrPattern(int strip) {
    float x = texCoord.x;
    if (strip <= 1) {
        if (x < 0.05) {
            return vec3(0.0);
        }
        float nits = pow(10.0, mix(-4.0, 0.0, (x - 0.05) / 0.95));
        float value = nits / unitNits;
        return vec3(strip == 1 ? toe(value) : value);
    }
    if (strip == 2) {
        float cell = x * 8.0;
        if (fract(cell) < 0.12) {
            return vec3(0.0);
        }
        return vec3(PATCH_NITS[int(min(cell, 7.0))] / unitNits);
    }
    return encodeScrgb(vec3(x * GRADIENT_MAX));
}

// Debug gradient, generated here because anything drawn through the GUI has 8-bit vertex colors.
// It goes through the same final quantization as the real image, so every strip is genuine: the
// 8-bit strips are exactly what an 8-bit pipeline would show, also on a 10-bit output.
vec3 gradient(int strip, vec2 pixel) {
    // The ramp starts with a sliver of true black, which must stay black with every setting.
    vec3 ramp = vec3(texCoord.x < BLACK_EDGE ? 0.0 : texCoord.x * GRADIENT_MAX);
    if (strip == 0) {
        return toLevels(ramp, LSB_8);
    }
    if (strip == 1) {
        return toLevels(dither(ramp, LSB_8, 1.0, pixel), LSB_8);
    }
    if (strip == 2) {
        return toLevels(ramp, outputLsb);
    }
    return toLevels(dither(ramp, ditherLsb, 1.0, pixel), ditherLsb);
}

void main() {
    vec3 color = texture(InSampler, texCoord).rgb;
    // Float targets are not clamped and can hold NaN. Clamping NaN is undefined, so remove it first.
    color = mix(color, vec3(0.0), notEqual(color, color));
    vec2 pixel = floor(gl_FragCoord.xy);

    if (encoding == 1) {
        // Float output: nothing to quantize or dither. Values above 1.0 are expanded highlights.
        color = max(color, 0.0);
        if (mode == 6) {
            fragColor = vec4(encodeScrgb(stepChart() + min(color, 1.0)), 1.0);
            return;
        }
        if (mode != 0) {
            int strip = mode == 1 ? 3 - int(clamp(texCoord.y, 0.0, 0.9999) * 4.0) : mode - 2;
            if (hdrActive == 1) {
                // The labels are drawn as white on black, so they add at interface brightness.
                fragColor = vec4(hdrPattern(strip) + min(color, 1.0) * uiScale, 1.0);
                return;
            }
            color = gradient(strip, pixel) + min(color, 1.0);
        }
        fragColor = vec4(encodeScrgb(color), 1.0);
        return;
    }

    color = clamp(color, 0.0, 1.0);
    if (mode == 6) {
        // Exact codes: no dither, so every patch is one flat level.
        fragColor = vec4(toLevels(clamp(stepChart() + color, 0.0, 1.0), LSB_8), 1.0);
        return;
    }
    if (mode == 0) {
        fragColor = vec4(toLevels(dither(color, ditherLsb, strength, pixel), ditherLsb), 1.0);
    } else {
        // Mode 1 stacks all four strips, top to bottom. Modes 2 to 5 show one strip full screen.
        int strip = mode == 1 ? 3 - int(clamp(texCoord.y, 0.0, 0.9999) * 4.0) : mode - 2;
        // The screen draws black with white labels, so adding keeps the labels readable.
        fragColor = vec4(clamp(gradient(strip, pixel) + color, 0.0, 1.0), 1.0);
    }
}
