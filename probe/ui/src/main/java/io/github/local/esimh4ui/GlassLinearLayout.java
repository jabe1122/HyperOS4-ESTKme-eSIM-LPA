package io.github.local.esimh4ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

/**
 * LinearLayout with an optional glass background, modelled on SukiSU-Nova's {@code BlurredBar}
 * and floating bottom bar.
 *
 * <ul>
 *   <li>Frosted: the page underneath is blurred (radius 25) under a translucent tint.</li>
 *   <li>Progressive: the frosted layer fades out from the strong edge (screen edge) toward the
 *       content, like the HyperOS 4 bars.</li>
 *   <li>Liquid: a light 4dp blur with vibrancy, refraction along the rim (with a little
 *       chromatic dispersion), and a specular rim light (floating bar only).</li>
 * </ul>
 *
 * The page ({@code source}) is redrawn into a 1/4-scale bitmap whenever any view scrolls or the
 * layout changes, so the bars follow the content without redrawing on idle frames.
 */
public class GlassLinearLayout extends LinearLayout {
    public enum Fade {
        NONE,
        /** Strongest at the top edge (top bar). */
        TOP,
        /** Strongest at the bottom edge (bottom bar). */
        BOTTOM
    }

    private static final int CAPTURE_SCALE = 4;
    /** Liquid glass refracts the page, so it needs a sharper capture than frosted glass. */
    private static final int LIQUID_CAPTURE_SCALE = 2;

    /**
     * Liquid glass lens, modelled on Kyant's backdrop library (used by SukiSU and iOS-like
     * glass): the page is refracted along the shape's surface normal near the rim, with a
     * little chromatic dispersion, then tinted, and lit by a specular rim and a soft inner glow.
     */
    private static final String LENS_AGSL =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float height;\n"
            + "uniform float amount;\n"
            + "uniform float dispersion;\n"
            + "uniform half4 tint;\n"
            + "uniform float rimLight;\n"
            + "uniform float rimWidth;\n"
            + "float sdRoundRect(float2 p, float2 b, float r) {\n"
            + "  float2 q = abs(p) - b + float2(r);\n"
            + "  return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;\n"
            + "}\n"
            + "float2 sdNormal(float2 p, float2 b, float r) {\n"
            + "  float2 q = abs(p) - b + float2(r);\n"
            + "  float2 g;\n"
            + "  if (q.x > 0.0 && q.y > 0.0) { g = normalize(q); }\n"
            + "  else if (q.x > q.y) { g = float2(1.0, 0.0); }\n"
            + "  else { g = float2(0.0, 1.0); }\n"
            + "  float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);\n"
            + "  return g * s;\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "  float2 hs = size * 0.5;\n"
            + "  float2 p = coord - hs;\n"
            + "  float r = min(radius, min(hs.x, hs.y));\n"
            + "  float d = max(-sdRoundRect(p, hs, r), 0.0);\n"
            + "  float2 n = sdNormal(p, hs, r);\n"
            + "  // Circle profile: flat in the middle, bending steeply toward the rim.\n"
            + "  float x = clamp(1.0 - d / height, 0.0, 1.0);\n"
            + "  float bend = 1.0 - sqrt(1.0 - x * x);\n"
            + "  float2 off = -n * bend * amount;\n"
            + "  float2 lo = float2(0.5);\n"
            + "  float2 hi = size - float2(0.5);\n"
            + "  half4 col = content.eval(clamp(coord + off, lo, hi));\n"
            + "  if (dispersion > 0.0) {\n"
            + "    col.r = content.eval(clamp(coord + off * (1.0 + dispersion), lo, hi)).r;\n"
            + "    col.b = content.eval(clamp(coord + off * (1.0 - dispersion), lo, hi)).b;\n"
            + "  }\n"
            + "  col.rgb = mix(col.rgb, tint.rgb, tint.a);\n"
            + "  // Inner glow: the glass edge catches a bit more light than its center.\n"
            + "  col.rgb += half3(bend * rimLight * 0.25);\n"
            + "  // Specular rim, brightest where the normal faces the light (top-left),\n"
            + "  // with a weaker reflection on the opposite side.\n"
            + "  float rim = 1.0 - smoothstep(0.0, rimWidth, d);\n"
            + "  float facing = dot(n, normalize(float2(-1.0, -1.6)));\n"
            + "  float spec = max(facing, 0.0) + 0.45 * max(-facing, 0.0);\n"
            + "  col.rgb += half3(rim * rimLight * (0.25 + 0.75 * spec));\n"
            + "  col.a = 1.0;\n"
            + "  return col;\n"
            + "}\n";

    protected final UiKit.Palette palette;
    private boolean glass;
    private int tintColor;
    private int fallbackColor;
    private float blurRadiusDp = 25f;
    private Fade fade = Fade.NONE;
    private boolean liquid;
    private boolean pillShape;
    private float glassAlpha = 1f;

    private View source;
    private Bitmap captureBitmap;
    private Canvas captureCanvas;
    private RenderNode blurNode;
    private int effectW = -1;
    private int effectH = -1;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint();
    private final int[] srcLoc = new int[2];
    private final int[] selfLoc = new int[2];
    private boolean captureFailed = false;
    private boolean needCapture = true;
    /** True when the lens shader is in use; it then applies the tint itself. */
    private boolean lensActive = false;

    private final ViewTreeObserver.OnPreDrawListener preDrawListener = this::onPreDraw;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::captureNow;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::captureNow;

    public GlassLinearLayout(Context ctx, UiKit.Palette p) {
        super(ctx);
        this.palette = p;
        setWillNotDraw(false);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    /**
     * Turns the glass background on or off.
     *
     * @param tint     translucent color drawn over the blurred page
     * @param fallback opaque color used if the page cannot be captured
     */
    public void setGlass(boolean enabled, int tint, int fallback) {
        this.glass = enabled;
        this.tintColor = tint;
        this.fallbackColor = fallback;
        if (enabled) setBackground(null);
        effectW = -1;
        invalidate();
    }

    public void setBlurRadius(float dp) {
        this.blurRadiusDp = dp;
        effectW = -1;
        invalidate();
    }

    public void setFade(Fade fade) {
        this.fade = fade;
        invalidate();
    }

    /** Liquid glass; {@code pill} rounds the lens to a capsule. */
    public void setLiquid(boolean liquid, boolean pill) {
        this.liquid = liquid;
        this.pillShape = pill;
        effectW = -1;
        invalidate();
    }

    /** 0 hides the glass (fully transparent bar), 1 shows it. Used to blur only after scrolling. */
    public void setGlassAlpha(float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        if (a == glassAlpha) return;
        boolean wasHidden = glassAlpha <= 0.01f;
        glassAlpha = a;
        if (wasHidden && a > 0.01f) needCapture = true;
        invalidate();
    }

    public boolean isGlass() {
        return glass && !captureFailed;
    }

    /** Sets the view whose content is blurred behind this layout. */
    public void setBlurSource(View source) {
        this.source = source;
        needCapture = true;
    }

    /** Called when software capture fails; the default switches to an opaque background. */
    protected void onGlassFailed(int fallback) {
        setBackground(new ColorDrawable(fallback));
    }

    private boolean onPreDraw() {
        if (needCapture) captureNow();
        return true;
    }

    private void captureNow() {
        if (!glass || source == null || captureFailed || glassAlpha <= 0.01f) return;
        capture();
        needCapture = captureBitmap == null;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewTreeObserver vto = getViewTreeObserver();
        vto.addOnPreDrawListener(preDrawListener);
        vto.addOnScrollChangedListener(scrollListener);
        vto.addOnGlobalLayoutListener(layoutListener);
        needCapture = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        ViewTreeObserver vto = getViewTreeObserver();
        vto.removeOnPreDrawListener(preDrawListener);
        vto.removeOnScrollChangedListener(scrollListener);
        vto.removeOnGlobalLayoutListener(layoutListener);
        if (captureBitmap != null) {
            captureBitmap.recycle();
            captureBitmap = null;
        }
        super.onDetachedFromWindow();
    }

    private void capture() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int scale = liquid ? LIQUID_CAPTURE_SCALE : CAPTURE_SCALE;
        int bw = Math.max(1, w / scale);
        int bh = Math.max(1, h / scale);
        if (captureBitmap == null || captureBitmap.getWidth() != bw || captureBitmap.getHeight() != bh) {
            if (captureBitmap != null) captureBitmap.recycle();
            captureBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            captureCanvas = new Canvas(captureBitmap);
        }
        captureBitmap.eraseColor(palette.pageBg | 0xFF000000);
        source.getLocationInWindow(srcLoc);
        getLocationInWindow(selfLoc);
        int save = captureCanvas.save();
        try {
            captureCanvas.scale(bw / (float) w, bh / (float) h);
            captureCanvas.translate(srcLoc[0] - selfLoc[0], srcLoc[1] - selfLoc[1]);
            source.draw(captureCanvas);
        } catch (Throwable t) {
            // Some content cannot be drawn in software; fall back to a solid background.
            captureFailed = true;
            onGlassFailed(fallbackColor);
        } finally {
            captureCanvas.restoreToCount(save);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (isGlass() && captureBitmap != null && glassAlpha > 0.01f) {
            int w = getWidth();
            int h = getHeight();
            int layer = canvas.saveLayerAlpha(0, 0, w, h, Math.round(255 * glassAlpha));
            boolean tintInShader = false;
            float sx = w / (float) captureBitmap.getWidth();
            float sy = h / (float) captureBitmap.getHeight();
            if (canvas.isHardwareAccelerated()) {
                ensureEffect(w, h);
                blurNode.setPosition(0, 0, w, h);
                RecordingCanvas rc = blurNode.beginRecording(w, h);
                rc.scale(sx, sy);
                rc.drawBitmap(captureBitmap, 0f, 0f, bitmapPaint);
                blurNode.endRecording();
                canvas.drawRenderNode(blurNode);
                tintInShader = lensActive;
            } else {
                int save = canvas.save();
                canvas.scale(sx, sy);
                canvas.drawBitmap(captureBitmap, 0f, 0f, bitmapPaint);
                canvas.restoreToCount(save);
            }
            if (!tintInShader) canvas.drawColor(tintColor);
            if (fade != Fade.NONE) {
                // Keep the glass solid near the strong edge and fade it out toward the content.
                float y0 = fade == Fade.TOP ? 0f : h;
                float y1 = fade == Fade.TOP ? h : 0f;
                maskPaint.setShader(new LinearGradient(0f, y0, 0f, y1,
                        new int[]{0xFF000000, 0xFF000000, 0x00000000},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, maskPaint);
            }
            canvas.restoreToCount(layer);
        }
        onDrawBehindChildren(canvas);
        super.dispatchDraw(canvas);
    }

    /** Draws between the glass and the children, for example a selection indicator. */
    protected void onDrawBehindChildren(Canvas canvas) {}

    private void ensureEffect(int w, int h) {
        if (blurNode == null) blurNode = new RenderNode("Glass");
        if (w == effectW && h == effectH) return;
        effectW = w;
        effectH = h;
        Context ctx = getContext();
        float r = UiKit.dpf(ctx, liquid ? 4f : blurRadiusDp);
        RenderEffect blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
        if (!liquid) {
            blurNode.setRenderEffect(blur);
            return;
        }
        // Vibrancy: the glass makes what is behind it a bit more saturated, like iOS / backdrop.
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.5f);
        RenderEffect vivid = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm), blur);
        RenderEffect effect = vivid;
        lensActive = false;
        try {
            RuntimeShader lens = new RuntimeShader(LENS_AGSL);
            float height = Math.min(UiKit.dpf(ctx, 24f), Math.min(w, h) / 2f);
            lens.setFloatUniform("size", w, h);
            lens.setFloatUniform("radius", pillShape ? h / 2f : 0f);
            lens.setFloatUniform("height", height);
            lens.setFloatUniform("amount", UiKit.dpf(ctx, 28f));
            lens.setFloatUniform("dispersion", 0.18f);
            lens.setFloatUniform("tint",
                    Color.red(tintColor) / 255f, Color.green(tintColor) / 255f,
                    Color.blue(tintColor) / 255f, Color.alpha(tintColor) / 255f);
            lens.setFloatUniform("rimLight", palette.isNight ? 0.28f : 0.6f);
            lens.setFloatUniform("rimWidth", UiKit.dpf(ctx, 1.25f));
            effect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), vivid);
            lensActive = true;
        } catch (Throwable t) {
            // Shader not supported on this device: keep blur + saturation only.
        }
        blurNode.setRenderEffect(effect);
    }
}
