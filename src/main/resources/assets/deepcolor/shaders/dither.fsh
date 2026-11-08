#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
uniform sampler2D NoiseSampler;
uniform sampler2D WorldSampler;
uniform sampler2D VignetteSampler;

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
    float expandBoost;
    float expandExponent;
    int expandMode;
    int additiveOverlay;
    float vignetteRed;
    float vignetteGreen;
    float vignetteBlue;
    float shadowDither;
    float shadowContrast;
    float shadowContrastRange;
    int floorDither;
    float calibrationPanel;
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
uniform float expandBoost;
uniform float expandExponent;
uniform int expandMode;
uniform int additiveOverlay;
uniform float vignetteRed;
uniform float vignetteGreen;
uniform float vignetteBlue;
uniform float shadowDither;
uniform float shadowContrast;
uniform float shadowContrastRange;
uniform int floorDither;
uniform float calibrationPanel;
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

// The shadow curve: a power law, or the piecewise sRGB curve when gamma is 0. Extended past 1.0 for
// values brighter than SDR white.
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

// HDR brightness expansion of the world, in the same encoding as the main target.
// Highlights (expandMode 1): scaled by luminance so hues stay put; white reaches the full boost, and
// the exponent grows with the boost so a gray at half brightness never gains more than 15%.
// Linear (expandMode 2): the whole world scaled up evenly.
vec3 expandWorld(vec3 world) {
    vec3 light = decodeCurve(max(world, 0.0));
    if (expandMode == 1) {
        float luminance = dot(light, LUMA);
        light *= 1.0 + expandBoost * pow(clamp(luminance, 0.0, 1.0), expandExponent);
    } else {
        light *= 1.0 + expandBoost;
    }
    return encodeCurve(light);
}

// The main target holds the plain SDR image with the interface over it, and its alpha says how much
// the interface covers each pixel (HdrExpandPass set it to 0 for the world). Normal blending gives
// current = a * ui + (1 - a) * v * k * world, where v is the vanilla vignette and k any other
// darkening that left alpha alone. The expanded image is the same with world replaced by its
// expansion, so the difference (1 - a) * v * k * (expanded - world) is added.
//
// v is known exactly: the vignette multiplies the screen by 1 - tint * vignette.png, and its tint
// for this frame is recorded when it is drawn (0 when it is not). It is divided out, not guessed,
// so light added on top of it (the nausea overlay) cannot make the expansion ignore it.
//
// k is measured from the pixel: current / ((1 - a) * v * world). Where every channel gives the same
// ratio, that is the world darkened evenly and the ratio is k. Light added on top (colored
// interface, nausea) changes the channels unevenly and can only raise each channel's ratio above
// k, so the smallest ratio is used: exact without other darkening (it clamps to 1), and never
// brighter than the real image.
//
// Alpha alone is not proof, since a mod overlay may draw without writing alpha. Where alpha says
// pure world, an uneven pixel, or one darkened by more than about half, is not trusted as world: a
// dark tooltip over a bright sky is left alone instead of glowing. That check fades out as alpha
// rises, so fading text and anti-aliased edges change smoothly instead of popping.
vec3 vignetteKeeps() {
    vec3 tint = vec3(vignetteRed, vignetteGreen, vignetteBlue);
    if (all(equal(tint, vec3(0.0)))) {
        return vec3(1.0);
    }
    return clamp(1.0 - tint * texture(VignetteSampler, texCoord).rgb, 0.0, 1.0);
}

vec3 withExpandedWorld(vec3 current, float alpha) {
    vec4 copy = texture(WorldSampler, texCoord);
    vec3 world = mix(max(copy.rgb, 0.0), vec3(0.0), notEqual(copy.rgb, copy.rgb));
    float coverage = clamp(alpha, 0.0, 1.0);
    vec3 vignette = vignetteKeeps();
    vec3 shareOfWorld = world * vignette * (1.0 - coverage);
    float worldLuminance = dot(shareOfWorld, LUMA);
    if (worldLuminance <= 1e-4) {
        return current;
    }
    float measured = clamp(dot(max(current, 0.0), LUMA) / worldLuminance, 0.0, 1.0);
    vec3 usable = step(vec3(0.002), shareOfWorld);
    vec3 ratio = max(current, 0.0) / max(shareOfWorld, vec3(0.002));
    vec3 spread = abs(ratio - measured) * usable;
    bool even = max(spread.r, max(spread.g, spread.b)) <= 0.03 + 0.03 * measured;
    vec3 usableRatio = mix(vec3(1.0), ratio, usable);
    float darkening = even ? measured : clamp(min(usableRatio.r, min(usableRatio.g, usableRatio.b)), 0.0, 1.0);
    // How much a pixel that alpha calls world must look like world to be expanded. Full strictness
    // at alpha 0, none from alpha 0.1, smoothly in between.
    // Nausea preserves coverage alpha, but adds colored light, so its pixels need not
    // look like evenly darkened world. Ordinary alpha-blended UI still masks expansion.
    float strictness = additiveOverlay == 1 ? 0.0 : 1.0 - smoothstep(0.0, 0.1, coverage);
    float looksLikeWorld = even ? smoothstep(0.35, 0.6, measured) : 0.0;
    float trust = mix(1.0, looksLikeWorld, strictness);
    // The copy's alpha is 0 for world. Behind a blurred menu it is where interface drawn before the
    // blur was smeared into the copy; that part is not world and is not expanded. It only weights
    // what is added, so the darkening measured above stays exact.
    float blurredWorld = 1.0 - smoothstep(0.0, 0.05, clamp(copy.a, 0.0, 1.0));
    return current + trust * blurredWorld * (1.0 - coverage) * darkening * vignette * (expandWorld(world) - world);
}

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

// Optional display compensation after HDR mapping and the shadow floor, before shadow noise.
// f(L) = L * (1 - s + s * smoothstep(0, range, L)). This is monotonic for 0 <= s < 1,
// keeps zero exact and rejoins the original curve with matching slope at the range boundary.
// Scaling linear luminance preserves hue, including negative wide-gamut components.
vec3 contrastHdrShadows(vec3 light) {
    if (hdrActive == 0 || shadowContrast <= 0.0) {
        return light;
    }
    float nits = dot(light, LUMA) * unitNits;
    if (nits <= 0.0 || nits >= shadowContrastRange) {
        return light;
    }
    float keep = 1.0 - shadowContrast * (1.0 - smoothstep(0.0, shadowContrastRange, nits));
    return light * keep;
}

// ST 2084 / ITU-R BT.2100 Table 4. These only size the noise; output remains linear scRGB.
float pqEncode(float nits) {
    float y = pow(nits / 10000.0, 2610.0 / 16384.0);
    return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), 2523.0 / 32.0);
}

float pqDecode(float code) {
    float y = pow(max(code, 0.0), 32.0 / 2523.0);
    return 10000.0 * pow(max(y - 0.8359375, 0.0) / (18.8515625 - 18.6875 * y), 16384.0 / 2610.0);
}

// Mask near-black panel/compositor banding with optional fine luminance grain. A fixed linear
// scRGB LSB is much too large here. Size the amplitude in nominal 10-bit PQ steps instead.
// Symmetric *linear-light* noise avoids the brightness bias of adding noise directly in PQ.
// One shared sample scales all channels, preserving hue and negative wide-gamut components.
vec3 ditherHdrShadows(vec3 light, vec2 pixel) {
    if (hdrActive == 0 || shadowDither <= 0.0) {
        return light;
    }
    float luminance = dot(light, LUMA);
    float nits = luminance * unitNits;
    if (nits <= 0.0 || nits >= 10.0) {
        return light;
    }
    // Below a measured panel floor, symmetric noise may never reach a visible code. Stochastic
    // pulses above that floor retain the intended linear-light mean without lighting true black.
    float pulse = min(floorScale * (1.0 + shadowDither), peakScale * luminance / max(max(light.r, max(light.g, light.b)), 1e-10));
    bool useFloor = floorDither == 1 && floorScale > 0.0 && pulse > floorScale;
    if (useFloor && luminance < pulse) {
        return uniformNoise(pixel) < luminance / pulse ? light * (pulse / luminance) : vec3(0.0);
    }
    float code = pqEncode(nits);
    float radius = shadowDither / 1023.0;
    float amplitude = min(nits - pqDecode(max(0.0, code - radius)), pqDecode(code + radius) - nits);
    // Never clip at black or the configured peak: clipping would bias the average brightness.
    float largest = max(light.r, max(light.g, light.b));
    float peakRoom = nits * max(0.0, peakScale / max(largest, 1e-10) - 1.0);
    amplitude = max(0.0, min(amplitude, min(0.95 * nits, peakRoom)));
    amplitude *= 1.0 - smoothstep(1.0, 10.0, nits);
    if (useFloor) {
        amplitude = min(amplitude, max(0.0, (luminance - floorScale) * unitNits));
        // Match the vanishing pulse variance at the upper end of the stochastic region.
        amplitude *= smoothstep(pulse, 2.0 * pulse, luminance);
    }
    return light * (1.0 + triangular(uniformNoise(pixel)) * amplitude / nits);
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
    float chartY = (texCoord.y - calibrationPanel) / (1.0 - calibrationPanel);
    if (fract(cell) < 0.1 || chartY < 0.3 || chartY > 0.7) {
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
    return encodeScrgb(vec3(x < BLACK_EDGE ? 0.0 : x * GRADIENT_MAX));
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
        // A plain 8-bit pipeline with standard dither, for comparison: always strength 1.
        return toLevels(dither(ramp, LSB_8, 1.0, pixel), LSB_8);
    }
    if (strip == 2) {
        return toLevels(ramp, outputLsb);
    }
    // The real dither at the configured depth and strength.
    return toLevels(dither(ramp, ditherLsb, strength, pixel), ditherLsb);
}

void main() {
    vec4 sampled = texture(InSampler, texCoord);
    vec3 color = sampled.rgb;
    // Float targets are not clamped and can hold NaN. Clamping NaN is undefined, so remove it first.
    color = mix(color, vec3(0.0), notEqual(color, color));
    if (expandMode != 0) {
        color = withExpandedWorld(color, sampled.a);
    }
    vec2 pixel = floor(gl_FragCoord.xy);

    if (mode != 0 && calibrationPanel > 0.0 && texCoord.y < calibrationPanel) {
        // The controls occupy the bottom of the input image. Do not draw a test ramp under them.
        fragColor = vec4(encoding == 1 ? encodeScrgb(max(color, 0.0)) : toLevels(clamp(color, 0.0, 1.0), outputLsb), 1.0);
        return;
    }
    float patternY = (texCoord.y - calibrationPanel) / (1.0 - calibrationPanel);
    if (encoding == 1) {
        // Float output stays unquantized. Optional shadow noise masks downstream banding.
        color = max(color, 0.0);
        if (mode == 6) {
            // Keep the grey reference chart exact.
            fragColor = vec4(encodeScrgb(stepChart() + min(color, 1.0)), 1.0);
            return;
        }
        if (mode != 0) {
            int strip = mode == 1 ? 3 - int(clamp(patternY, 0.0, 0.9999) * 4.0) : mode - 2;
            if (hdrActive == 1) {
                // The labels are drawn as white on black, so they add at interface brightness.
                // Keep reference ramps/patches exact; game shadows preview contrast and noise.
                vec3 light = hdrPattern(strip);
                fragColor = vec4((strip == 3 ? ditherHdrShadows(contrastHdrShadows(light), pixel) : light) + min(color, 1.0) * uiScale, 1.0);
                return;
            }
            color = gradient(strip, pixel) + min(color, 1.0);
        }
        fragColor = vec4(ditherHdrShadows(contrastHdrShadows(encodeScrgb(color)), pixel), 1.0);
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
        int strip = mode == 1 ? 3 - int(clamp(patternY, 0.0, 0.9999) * 4.0) : mode - 2;
        // The screen draws black with white labels, so adding keeps the labels readable.
        fragColor = vec4(clamp(gradient(strip, pixel) + color, 0.0, 1.0), 1.0);
    }
}
