// The HyperOS background effect of the About page: four soft color points that drift over a
// noise field. The shader, the presets and the animation are adapted from the Miuix example app
// (compose-miuix-ui/miuix, example/shared/.../component/effect, OS3 phone presets).
// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.local.esimh4probe

import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private class BgEffectPreset(
    val points: FloatArray,
    val colors1: FloatArray,
    val colors2: FloatArray,
    val colors3: FloatArray,
    val colorInterpPeriod: Float,
    val lightOffset: Float,
    val saturateOffset: Float,
    val pointOffset: Float,
)

private val PHONE_LIGHT = BgEffectPreset(
    points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
    colors1 = floatArrayOf(1.0f, 0.9f, 0.94f, 1.0f, 1.0f, 0.84f, 0.89f, 1.0f, 0.97f, 0.73f, 0.82f, 1.0f, 0.64f, 0.65f, 0.98f, 1.0f),
    colors2 = floatArrayOf(0.58f, 0.74f, 1.0f, 1.0f, 1.0f, 0.9f, 0.93f, 1.0f, 0.74f, 0.76f, 1.0f, 1.0f, 0.97f, 0.77f, 0.84f, 1.0f),
    colors3 = floatArrayOf(0.98f, 0.86f, 0.9f, 1.0f, 0.6f, 0.73f, 0.98f, 1.0f, 0.92f, 0.93f, 1.0f, 1.0f, 0.56f, 0.69f, 1.0f, 1.0f),
    colorInterpPeriod = 5.0f,
    lightOffset = 0.1f,
    saturateOffset = 0.2f,
    pointOffset = 0.2f,
)

private val PHONE_DARK = BgEffectPreset(
    points = floatArrayOf(0.8f, 0.2f, 1.0f, 0.8f, 0.9f, 1.0f, 0.2f, 0.9f, 1.0f, 0.2f, 0.2f, 1.0f),
    colors1 = floatArrayOf(0.2f, 0.06f, 0.88f, 0.4f, 0.3f, 0.14f, 0.55f, 0.5f, 0.0f, 0.64f, 0.96f, 0.5f, 0.11f, 0.16f, 0.83f, 0.4f),
    colors2 = floatArrayOf(0.07f, 0.15f, 0.79f, 0.5f, 0.62f, 0.21f, 0.67f, 0.5f, 0.06f, 0.25f, 0.84f, 0.5f, 0.0f, 0.2f, 0.78f, 0.5f),
    colors3 = floatArrayOf(0.58f, 0.3f, 0.74f, 0.4f, 0.27f, 0.18f, 0.6f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f, 0.12f, 0.16f, 0.7f, 0.6f),
    colorInterpPeriod = 8.0f,
    lightOffset = 0.0f,
    saturateOffset = 0.17f,
    pointOffset = 0.4f,
)

/** Session-only controls for the About page's hidden texture panel. */
internal class TextureSettings {
    // Independent from the application's bar style.
    var version by androidx.compose.runtime.mutableIntStateOf(3)
    var enabled by androidx.compose.runtime.mutableStateOf(true)
    var animated by androidx.compose.runtime.mutableStateOf(true)
    var fullScreen by androidx.compose.runtime.mutableStateOf(false)
    var noise by mutableFloatStateOf(1f)
    var brightness by mutableFloatStateOf(0f)
    var saturation by mutableFloatStateOf(1f)

    fun reset() {
        version = 3
        enabled = true
        animated = true
        fullScreen = false
        noise = 1f
        brightness = 0f
        saturation = 1f
    }
}

/** Holds the shader and sets only the uniforms that changed since the last frame. */
private class BgEffectPainter(preset: BgEffectPreset) {
    val shader = RuntimeShader(BG_EFFECT_FRAG).apply {
        setFloatUniform("uTranslateY", 0f)
        setFloatUniform("uNoiseScale", 1.5f)
        setFloatUniform("uPointRadiusMulti", 1f)
        setFloatUniform("uAlphaMulti", 1f)
        setFloatUniform("uPoints", preset.points)
        setFloatUniform("uLightOffset", preset.lightOffset)
        setFloatUniform("uSaturateOffset", preset.saturateOffset)
    }
    val brush = ShaderBrush(shader)

    private var width = Float.NaN
    private var height = Float.NaN
    private var stage = Float.NaN
    private var lastEffectHeight = Float.NaN
    private val colors = FloatArray(16)
    private val pointsAnim = FloatArray(8)

    /** [effectHeight]: the top part of the page the color points are laid out in. */
    fun update(w: Float, h: Float, effectHeight: Float, preset: BgEffectPreset, colorStage: Float, time: Float) {
        if (w != width || h != height || effectHeight != lastEffectHeight) {
            lastEffectHeight = effectHeight
            width = w
            height = h
            shader.setFloatUniform("uResolution", w, h)
            val ratio = effectHeight / h
            if (w <= h) {
                shader.setFloatUniform("uBound", 0f, 1f - ratio, 1f, ratio)
            } else {
                val aspect = w / h
                shader.setFloatUniform("uBound", 0f, 1f - ratio / 2f - aspect / 2f, 1f, aspect)
            }
        }
        if (colorStage != stage) {
            stage = colorStage
            val base = floor(colorStage).toInt()
            val fraction = colorStage - base
            val start = colorsAt(preset, base)
            val end = colorsAt(preset, base + 1)
            for (i in 0 until 16) colors[i] = start[i] + (end[i] - start[i]) * fraction
            shader.setFloatUniform("uColors", colors)
        }
        for (i in 0 until 4) {
            val x = preset.points[i * 3]
            val y = preset.points[i * 3 + 1]
            val animX = x + sin(time + y) * preset.pointOffset
            val animY = y + cos(time + animX) * preset.pointOffset
            pointsAnim[i * 2] = animX
            pointsAnim[i * 2 + 1] = animY
        }
        shader.setFloatUniform("uPointsAnim", pointsAnim)
        shader.setFloatUniform("uAnimTime", time)
    }

    fun applySettings(settings: TextureSettings) {
        shader.setFloatUniform("uTextureNoise", settings.noise)
        shader.setFloatUniform("uTextureBrightness", settings.brightness)
        shader.setFloatUniform("uTextureSaturation", settings.saturation)
    }

    private fun colorsAt(preset: BgEffectPreset, index: Int): FloatArray = when (index.mod(4)) {
        1 -> preset.colors1
        3 -> preset.colors3
        else -> preset.colors2
    }
}

/**
 * Draws [surface] with the animated effect over the top [effectFraction] of the box, then
 * [content]. [alpha] fades the effect (the About page fades it out while the header scrolls away).
 */
@Composable
internal fun BgEffectBackground(
    isDark: Boolean,
    surface: Color,
    modifier: Modifier = Modifier,
    effectFraction: Float = 0.8f,
    alpha: () -> Float = { 1f },
    settings: TextureSettings = remember { TextureSettings() },
    content: @Composable BoxScope.(iconBrush: (Size) -> Brush) -> Unit,
) {
    val preset = remember(isDark, settings.version) {
        val base = if (isDark) PHONE_DARK else PHONE_LIGHT
        when (settings.version) {
            2 -> BgEffectPreset(base.points, base.colors1, base.colors1, base.colors1,
                base.colorInterpPeriod, base.lightOffset, base.saturateOffset, 0.15f)
            else -> base
        }
    }
    val painter = remember(preset) { BgEffectPainter(preset) }
    val iconPainter = remember(preset) { BgEffectPainter(preset) }
    val colorStage = remember { Animatable(0f) }
    var time by remember { mutableFloatStateOf(0f) }

    // The colors move to the next set every few seconds.
    LaunchedEffect(preset, settings.animated) {
        if (!settings.animated) return@LaunchedEffect
        var target = floor(colorStage.value) + 1f
        while (isActive) {
            delay((preset.colorInterpPeriod * 500).toLong())
            colorStage.animateTo(target, spring(dampingRatio = 0.9f, stiffness = 35f))
            target += 1f
        }
    }
    // The points drift continuously; reading `time` only in the draw phase redraws, not recomposes.
    LaunchedEffect(settings.animated) {
        if (!settings.animated) return@LaunchedEffect
        val elapsed = time
        val origin = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { time = elapsed + (it - origin) / 1_000_000_000f }
        }
    }

    Box(
        modifier = modifier.drawBehind {
            drawRect(surface)
            val a = alpha()
            if (a > 0f && settings.enabled) {
                painter.update(size.width, size.height, size.height * if (settings.fullScreen) 1f else effectFraction, preset, colorStage.value, time)
                painter.applySettings(settings)
                drawRect(painter.brush, alpha = a)
            }
        },
        content = {
            content { size ->
                iconPainter.update(size.width, size.height, size.height, preset, colorStage.value, time)
                iconPainter.applySettings(settings)
                iconPainter.brush
            }
        },
    )
}

private const val BG_EFFECT_FRAG = """
    uniform vec2 uResolution;
    uniform float uAnimTime;
    uniform vec4 uBound;
    uniform float uTranslateY;
    uniform vec3 uPoints[4];
    uniform vec2 uPointsAnim[4];
    uniform vec4 uColors[4];
    uniform float uAlphaMulti;
    uniform float uNoiseScale;
    uniform float uPointRadiusMulti;
    uniform float uSaturateOffset;
    uniform float uLightOffset;
    uniform float uTextureNoise;
    uniform float uTextureBrightness;
    uniform float uTextureSaturation;

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

    float hash(vec2 p) {
        vec3 p3 = fract(vec3(p.xyx) * 0.13);
        p3 += dot(p3, p3.yzx + 3.333);
        return fract((p3.x + p3.y) * p3.z);
    }

    float perlin(vec2 x) {
        vec2 i = floor(x); vec2 f = fract(x);

        float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
        float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));

        vec2 u = f * f * (3.0 - 2.0 * f);
        return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
    }

    float gradientNoise(in vec2 uv) {
        return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
    }

    vec4 main(vec2 fragCoord){
        vec2 vUv = fragCoord/uResolution;
        vUv.y = 1.0-vUv.y;
        vec2 uv = vUv;
        uv -= vec2(0., uTranslateY);
        uv.xy -= uBound.xy;
        uv.xy /= uBound.zw;

        vec4 color = vec4(0.0);
        float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));

        for (int i = 0; i < 4; i++){
            vec4 pointColor = uColors[i];
            pointColor.rgb *= pointColor.a;
            vec2 point = uPointsAnim[i];
            float rad = uPoints[i].z * uPointRadiusMulti;

            float d = distance(uv, point);
            float pct = smoothstep(rad, 0., d);
            color.rgb = mix(color.rgb, pointColor.rgb, pct);
            color.a = mix(color.a, pointColor.a, pct);
        }

        float oppositeNoise = smoothstep(0., 1., noiseValue);
        color.rgb /= max(color.a, 0.0001);
        vec3 hsv = rgb2hsv(color.rgb);
        hsv.y = mix(hsv.y, 0.0, oppositeNoise * uSaturateOffset);
        hsv.y = clamp(hsv.y * uTextureSaturation, 0.0, 1.0);
        color.rgb = hsv2rgb(hsv);
        color.rgb += oppositeNoise * uLightOffset + uTextureBrightness;

        color.a = clamp(color.a, 0., 1.);
        color.a *= uAlphaMulti;

        color.rgb += uTextureNoise * ((10.0 / 255.0) * gradientNoise(fragCoord.xy) - (5.0 / 255.0));
        color.rgb = clamp(color.rgb, 0.0, 1.0);
        return vec4(color.rgb * color.a, color.a);
    }
"""
