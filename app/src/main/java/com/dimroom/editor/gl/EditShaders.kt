package com.dimroom.editor.gl

/**
 * The GLSL ES 2.0 program that renders one edited photo.
 *
 * The whole edit stack is a single fragment shader so a slider drag costs exactly one full-screen
 * pass — no intermediate framebuffers, no per-adjustment ping-pong. The same source is used by the
 * on-screen preview ([PhotoRenderer]) and by the full-resolution exporter ([OffscreenRenderer]),
 * which is what guarantees "what you see is what you export".
 *
 * Stage order deliberately mirrors Lightroom's own pipeline:
 * white balance -> exposure -> tone (contrast/highlights/shadows/whites/blacks) -> dehaze ->
 * clarity -> HSL -> vibrance/saturation -> vignette -> grain.
 */
object EditShaders {

    /**
     * Geometry (crop, 90° rotation, straighten, flips) is applied to texture coordinates here, and
     * the quad itself is positioned by [uPosMatrix] which carries letterboxing plus user zoom/pan.
     */
    val VERTEX_SHADER = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;

        uniform mat4 uPosMatrix;
        uniform mat3 uTexMatrix;

        varying vec2 vTexCoord;

        void main() {
            gl_Position = uPosMatrix * vec4(aPosition, 0.0, 1.0);
            vTexCoord = (uTexMatrix * vec3(aTexCoord, 1.0)).xy;
        }
    """.trimIndent()

    val FRAGMENT_SHADER = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif

        varying vec2 vTexCoord;

        uniform sampler2D uTexture;
        uniform vec2 uTexelSize;

        // Light
        uniform float uExposure;    // stops
        uniform float uContrast;    // -1..1
        uniform float uHighlights;  // -1..1
        uniform float uShadows;     // -1..1
        uniform float uWhites;      // -1..1
        uniform float uBlacks;      // -1..1

        // Color
        uniform float uTemperature; // -1..1
        uniform float uTint;        // -1..1
        uniform float uVibrance;    // -1..1
        uniform float uSaturation;  // -1..1

        // HSL: 8 bands x (hue, saturation, luminance)
        uniform vec3 uHsl[8];
        uniform float uHslCenters[8];
        uniform float uHslActive;   // 1.0 when any band is non-neutral

        // Effects
        uniform float uClarity;     // -1..1
        uniform float uDehaze;      // -1..1
        uniform float uVignette;    // -1..1
        uniform float uGrain;       // 0..1
        uniform float uGrainSeed;

        // Compare
        uniform float uSplit;       // divider x in device pixels; <= 0 disables split view
        uniform float uShowOriginal;// 1.0 renders the untouched photo everywhere

        const vec3 LUMA = vec3(0.2125, 0.7154, 0.0721);

        float luminance(vec3 c) { return dot(c, LUMA); }

        vec3 rgb2hsv(vec3 c) {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }

        vec3 hsv2rgb(vec3 c) {
            vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
            vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
            return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
        }

        // Smooth roll-off used by the tone controls so corrections fade out instead of clipping.
        float toneMask(float lum, float center, float width) {
            return exp(-((lum - center) * (lum - center)) / (2.0 * width * width));
        }

        vec3 applyWhiteBalance(vec3 color) {
            // Cheap Kelvin-like shift: push red against blue for temperature, green against
            // magenta for tint. Values are scaled to stay well short of channel clipping.
            float t = uTemperature * 0.32;
            float g = uTint * 0.24;
            color.r *= 1.0 + t;
            color.b *= 1.0 - t;
            color.g *= 1.0 + g;
            color.r *= 1.0 - g * 0.5;
            color.b *= 1.0 - g * 0.5;
            return color;
        }

        vec3 applyTone(vec3 color) {
            color *= pow(2.0, uExposure);

            // Contrast about mid-grey.
            float c = 1.0 + uContrast * 0.85;
            color = (color - 0.5) * c + 0.5;

            float lum = luminance(clamp(color, 0.0, 4.0));

            // Highlights / shadows: region-weighted gains.
            float highlightMask = toneMask(lum, 0.85, 0.35);
            float shadowMask = toneMask(lum, 0.15, 0.32);
            color += color * (uHighlights * 0.6 * highlightMask);
            color += (uShadows * 0.55 * shadowMask) * (1.0 - color * 0.5);

            // Whites / blacks: endpoint stretch.
            float whiteMask = smoothstep(0.5, 1.0, lum);
            float blackMask = 1.0 - smoothstep(0.0, 0.5, lum);
            color += uWhites * 0.35 * whiteMask;
            color += uBlacks * 0.35 * blackMask;

            return color;
        }

        // Dark-channel dehaze: estimate transmission per pixel and invert the haze model
        // I = J * t + A * (1 - t) with the airlight A assumed white.
        vec3 applyDehaze(vec3 color) {
            if (abs(uDehaze) < 0.001) return color;

            if (uDehaze > 0.0) {
                float darkChannel = min(min(color.r, color.g), color.b);
                float t = clamp(1.0 - uDehaze * 0.8 * darkChannel, 0.2, 1.0);
                vec3 dehazed = (color - (1.0 - t)) / t;
                // Clearing haze reveals colour, so let saturation come up with it.
                float lum = luminance(dehazed);
                dehazed = mix(vec3(lum), dehazed, 1.0 + uDehaze * 0.3);
                return mix(color, dehazed, uDehaze);
            }

            // Negative dehaze lays haze back on: lift the blacks toward a flat, pale wash.
            float lum = luminance(color);
            return mix(color, vec3(mix(lum, 0.78, 0.4)), -uDehaze * 0.55);
        }

        // Unsharp mask on luminance only: 8 taps approximate a low-pass, and the difference
        // between the pixel and that blur is local contrast.
        vec3 applyClarity(vec3 color, vec2 uv) {
            if (abs(uClarity) < 0.001) return color;
            vec2 r = uTexelSize * 3.0;
            vec3 blur = vec3(0.0);
            blur += texture2D(uTexture, uv + vec2(-r.x, -r.y)).rgb;
            blur += texture2D(uTexture, uv + vec2( 0.0, -r.y)).rgb;
            blur += texture2D(uTexture, uv + vec2( r.x, -r.y)).rgb;
            blur += texture2D(uTexture, uv + vec2(-r.x,  0.0)).rgb;
            blur += texture2D(uTexture, uv + vec2( r.x,  0.0)).rgb;
            blur += texture2D(uTexture, uv + vec2(-r.x,  r.y)).rgb;
            blur += texture2D(uTexture, uv + vec2( 0.0,  r.y)).rgb;
            blur += texture2D(uTexture, uv + vec2( r.x,  r.y)).rgb;
            blur /= 8.0;

            float detail = luminance(color) - luminance(blur);
            // Protect the extremes so clarity does not halo against blown skies.
            float protect = 1.0 - smoothstep(0.75, 1.0, luminance(color));
            return color + detail * uClarity * 1.5 * protect;
        }

        vec3 applyHsl(vec3 color) {
            // Skip the HSV round trip entirely when every band is neutral.
            if (uHslActive < 0.5) return color;

            vec3 hsv = rgb2hsv(clamp(color, 0.0, 1.0));
            float hueDegrees = hsv.x * 360.0;

            float hueShift = 0.0;
            float satShift = 0.0;
            float lumShift = 0.0;
            float totalWeight = 0.0;

            for (int i = 0; i < 8; i++) {
                float center = uHslCenters[i];
                float delta = abs(hueDegrees - center);
                delta = min(delta, 360.0 - delta);
                // 45 degrees of influence per band, feathered so neighbours blend.
                float weight = 1.0 - smoothstep(0.0, 45.0, delta);
                hueShift += uHsl[i].x * weight;
                satShift += uHsl[i].y * weight;
                lumShift += uHsl[i].z * weight;
                totalWeight += weight;
            }

            if (totalWeight < 0.001) return color;

            hsv.x = fract(hsv.x + hueShift * (30.0 / 360.0));
            hsv.y = clamp(hsv.y * (1.0 + satShift), 0.0, 1.0);
            vec3 shifted = hsv2rgb(hsv);
            shifted *= (1.0 + lumShift * 0.6);
            return mix(color, shifted, clamp(totalWeight, 0.0, 1.0));
        }

        vec3 applySaturation(vec3 color) {
            float lum = luminance(color);

            // Vibrance protects already-saturated pixels (and skin tones) from over-cooking.
            if (abs(uVibrance) > 0.001) {
                float maxC = max(max(color.r, color.g), color.b);
                float minC = min(min(color.r, color.g), color.b);
                float sat = maxC - minC;
                float protectSkin = 1.0 - smoothstep(0.0, 0.35, max(0.0, color.r - color.b) * 0.6);
                float amount = uVibrance * (1.0 - sat) * mix(1.0, protectSkin, 0.5);
                color = mix(vec3(lum), color, 1.0 + amount * 1.4);
            }

            color = mix(vec3(lum), color, 1.0 + uSaturation);
            return color;
        }

        vec3 applyVignette(vec3 color, vec2 uv) {
            if (abs(uVignette) < 0.001) return color;
            vec2 centered = uv - 0.5;
            float d = length(centered) * 1.4142;
            float falloff = smoothstep(0.35, 1.0, d);
            return color * (1.0 + uVignette * falloff * 0.9);
        }

        vec3 applyGrain(vec3 color, vec2 uv) {
            if (uGrain < 0.001) return color;
            float n = fract(sin(dot(uv * 1024.0 + uGrainSeed, vec2(12.9898, 78.233))) * 43758.5453);
            // Grain is most visible in midtones, as it is on film.
            float lum = luminance(color);
            float midtone = 1.0 - abs(lum - 0.5) * 1.6;
            return color + (n - 0.5) * uGrain * 0.22 * clamp(midtone, 0.15, 1.0);
        }

        void main() {
            vec2 uv = vTexCoord;
            vec3 original = texture2D(uTexture, uv).rgb;

            bool showOriginal = uShowOriginal > 0.5 || (uSplit > 0.0 && gl_FragCoord.x < uSplit);
            if (showOriginal) {
                gl_FragColor = vec4(clamp(original, 0.0, 1.0), 1.0);
                return;
            }

            vec3 color = original;
            color = applyWhiteBalance(color);
            color = applyTone(color);
            color = applyDehaze(color);
            color = applyClarity(color, uv);
            color = clamp(color, 0.0, 1.0);
            color = applyHsl(color);
            color = applySaturation(color);
            color = applyVignette(color, uv);
            color = applyGrain(color, uv);

            gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """.trimIndent()
}
