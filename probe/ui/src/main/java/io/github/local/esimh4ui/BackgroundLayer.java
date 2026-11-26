package io.github.local.esimh4ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Custom app background: center-cropped image with optional blur and a dim overlay. */
public final class BackgroundLayer extends FrameLayout {
    private final ImageView image;
    private final View dim;
    private final int dimBase;

    public BackgroundLayer(Context ctx, UiKit.Palette p, Bitmap bitmap) {
        super(ctx);
        setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        image = new ImageView(ctx);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(bitmap);
        addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dim = new View(ctx);
        addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Dim toward the page color, so text keeps its contrast in both light and dark themes.
        dimBase = p.pageBg | 0xFF000000;
        setBlur(ThemeConfig.bgBlur);
        setDim(ThemeConfig.bgDim);
    }

    /** Returns the layer for the stored background, or null when none is set. */
    public static BackgroundLayer createIfSet(Context ctx, UiKit.Palette p) {
        if (!p.hasBackground) return null;
        Bitmap bmp = ThemeConfig.loadBackground(ctx);
        return bmp != null ? new BackgroundLayer(ctx, p, bmp) : null;
    }

    public void setBlur(int radiusDp) {
        if (radiusDp <= 0) {
            image.setRenderEffect(null);
        } else {
            float r = UiKit.dpf(getContext(), radiusDp);
            image.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
        }
    }

    public void setDim(int percent) {
        int a = Math.round(Math.max(0, Math.min(90, percent)) / 100f * 255f);
        dim.setBackgroundColor(UiKit.withAlpha(dimBase, a));
    }
}
