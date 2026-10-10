package com.jarves.mh.ui.theme.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import com.jarves.mh.ui.OpenPerf

/**
 * The glass lens, written in AGSL (Android 13+). It runs after the backdrop blur, on the
 * blurred sample of one glass shape (or a merged group of up to four shapes):
 *
 * 1. Lens: within [lensHeight] of the edge, samples are pulled from further inside along the
 *    edge normal with a circular profile (flat in the middle, steep at the rim), so content
 *    under the edge bends like it would under a thick piece of glass.
 * 2. Dispersion: red and blue are bent slightly more and less than green (capped).
 * 3. Vibrancy: saturation boost of the sample, composited over the opaque canvas.
 * 4. Adaptive wash: the wash gets denser where the sample is bright (dark mode) or dark (light
 *    mode), keeping labels on the glass readable without a CPU read-back.
 * 5. Edge light: a soft inner highlight facing the light direction, and in light mode a faint
 *    shade on the far side, so the rim reads as thick glass even over a plain background.
 * 6. Press glow: a soft light spot at the touch point.
 * 7. Groups only: the shape mask and rim come from a smooth union of the member shapes, so
 *    nearby glass pieces blend into one and pull apart as they move.
 *
 * Positions are in the layer's pixel space. Rects are x, y, width, height.
 */
internal const val GlassShaderSource = """
uniform shader content;
uniform float4 rects[4];
uniform float radii[4];
uniform float count;
uniform float smoothK;
uniform float lensHeight;
uniform float refraction;
uniform float dispersion;
uniform float saturation;
uniform float4 canvas;
uniform float4 wash;
uniform float adaptive;
uniform float dark;
uniform float2 light;
uniform float edgeLight;
uniform float edgeShade;
uniform float3 glow;
uniform float glowRadius;
uniform float maskShape;
uniform float rimWidth;
uniform float rimStrength;

float sdBox(float2 p, float4 r, float rad) {
    float2 halfSize = r.zw * 0.5;
    float2 q = abs(p - (r.xy + halfSize)) - halfSize + rad;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
}

float smin(float a, float b, float k) {
    if (k <= 0.0) { return min(a, b); }
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * 0.25;
}

float sdf(float2 p) {
    float d = sdBox(p, rects[0], radii[0]);
    for (int i = 1; i < 4; i++) {
        if (float(i) < count) { d = smin(d, sdBox(p, rects[i], radii[i]), smoothK); }
    }
    return d;
}

float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

half4 main(float2 p) {
    float d = sdf(p);
    float inside = -d;
    // The normal only feeds the lens bend, the edge band and the rim. Pixels beyond all of them
    // use none of it, so they skip the four gradient samples.
    float reach = max(lensHeight, max(lensHeight, 1.0) * 0.7);
    if (maskShape > 0.5) { reach = max(reach, rimWidth); }
    float2 n = float2(0.0, 0.0);
    if (inside < reach) {
        float e = 1.0;
        float2 g = float2(sdf(p + float2(e, 0.0)) - sdf(p - float2(e, 0.0)), sdf(p + float2(0.0, e)) - sdf(p - float2(0.0, e)));
        float gl = length(g);
        n = gl > 0.0001 ? g / gl : float2(0.0, 0.0);
    }

    float t = lensHeight > 0.0 ? clamp(1.0 - inside / lensHeight, 0.0, 1.0) : 0.0;
    float bend = 1.0 - sqrt(max(1.0 - t * t, 0.0));
    float2 shift = -n * refraction * bend;

    float4 c = float4(content.eval(p + shift));
    if (dispersion > 0.0 && bend > 0.0) {
        float r = float4(content.eval(p + shift * (1.0 + dispersion))).r;
        float b = float4(content.eval(p + shift * (1.0 - dispersion))).b;
        c = float4(r, c.g, b, c.a);
    }
    float3 rgb = c.rgb + canvas.rgb * (1.0 - c.a);
    rgb = mix(float3(luma(rgb)), rgb, saturation);

    float l = luma(rgb);
    float washAlpha = clamp(wash.a + adaptive * (dark > 0.5 ? l : 1.0 - l), 0.0, 0.92);
    rgb = mix(rgb, wash.rgb, washAlpha);

    float facing = max(dot(n, light), 0.0);
    float away = max(dot(n, -light), 0.0);
    float band = 1.0 - smoothstep(0.0, max(lensHeight, 1.0) * 0.7, inside);
    rgb += edgeLight * band * (0.35 + 0.65 * facing + 0.3 * away);
    rgb = min(rgb, float3(1.0));
    rgb -= edgeShade * band * (0.4 + 0.6 * away);

    if (glow.z > 0.0) {
        float2 dp = p - glow.xy;
        float fall = exp(-dot(dp, dp) / (2.0 * glowRadius * glowRadius));
        rgb += glow.z * fall;
        rgb += glow.z * 0.6 * band * fall;
    }

    float alpha = 1.0;
    if (maskShape > 0.5) {
        alpha = clamp(0.5 - d, 0.0, 1.0);
        float rim = 1.0 - smoothstep(0.0, rimWidth, inside);
        rgb += rimStrength * rim * (0.25 + 0.75 * facing + 0.4 * away);
    }
    rgb = clamp(rgb, 0.0, 1.0);
    return half4(half3(rgb * alpha), half(alpha));
}
"""

/** Compiles the lens once per call site; null where AGSL is unavailable or failed to compile. */
internal object GlassShaders {
    @Volatile
    private var broken = false

    /** True if this device can run the lens tier at all. */
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !broken

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun create(): RuntimeShader? {
        if (broken) return null
        return try {
            val started = OpenPerf.nowMs()
            RuntimeShader(GlassShaderSource).also {
                OpenPerf.log("AGSL lens shader created in ${OpenPerf.nowMs() - started} ms")
            }
        } catch (t: Throwable) {
            // A driver or test environment without AGSL: fall back to blur-only glass for good.
            broken = true
            null
        }
    }
}
