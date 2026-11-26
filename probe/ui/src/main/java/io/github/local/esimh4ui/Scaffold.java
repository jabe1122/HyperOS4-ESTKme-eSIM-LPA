package io.github.local.esimh4ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * Standard page frame shared by all screens.
 *
 * <pre>
 * root
 *  ├─ stage (blurred by the bars): background image + swipeable pages
 *  ├─ header (overlay): top bar + progress line
 *  └─ bottom bar (overlay, optional)
 * </pre>
 *
 * Miuix follows the SukiSU-Nova top bar: each page starts with a large title that scrolls with
 * the content; the bar itself is transparent at rest and, once the page scrolls, shows a small
 * centered title over a (progressive) blur. Material 3 keeps a solid small top app bar.
 */
public final class Scaffold {
    public final FrameLayout root;
    /** Background + pages; this is what the glass bars blur. */
    public final FrameLayout stage;
    public final BackgroundLayer background;
    public final GlassLinearLayout header;
    public final TextView titleView;
    public final TextView subtitleView;
    public final PagerHost pager;

    private final Activity activity;
    private final UiKit.Palette palette;
    private final boolean miuix;
    private final FrameLayout barRow;
    private final LinearLayout actions;
    private final ProgressLine progress;
    private final List<ScrollView> pages = new ArrayList<>();
    private final List<TextView> largeTitles = new ArrayList<>();
    private Toast toast;
    private BottomBar bottomBar;
    private Insets bars = Insets.NONE;
    private int headerHeight = 0;

    public Scaffold(Activity activity, UiKit.Palette p, String title, String subtitle, boolean showBack) {
        this.activity = activity;
        this.palette = p;
        this.miuix = UiKit.isMiuix();

        root = new FrameLayout(activity);
        root.setBackgroundColor(p.pageBg);

        stage = new FrameLayout(activity);
        stage.setBackgroundColor(p.pageBg);
        root.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        background = BackgroundLayer.createIfSet(activity, p);
        if (background != null) {
            stage.addView(background);
        }

        pager = new PagerHost(activity);
        stage.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pager.addOnPageChangeObserver(index -> updateCollapse());

        // Header overlay: pages scroll underneath it.
        header = new GlassLinearLayout(activity, p);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setClickable(true);
        if (miuix && ThemeConfig.barGlass) {
            int alpha = p.hasBackground ? Math.round(ThemeConfig.cardAlpha * 2.55f) : 0xDE; // 0.87 like SukiSU
            header.setGlass(true, UiKit.withAlpha(p.pageBg, alpha), p.pageBg | 0xFF000000);
            header.setBlurSource(stage);
            if (ThemeConfig.progressiveBlur) header.setFade(GlassLinearLayout.Fade.TOP);
            header.setGlassAlpha(0f); // transparent until the page scrolls
        } else {
            header.setBackgroundColor(p.pageBg | 0xFF000000);
        }
        root.addView(header, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            if (h != headerHeight) {
                headerHeight = h;
                header.post(this::applyInsets);
            }
        });

        // Bar row: [back] [title] [actions]. Miuix centers a small title; Material 3 left-aligns it.
        barRow = new FrameLayout(activity);
        barRow.setMinimumHeight(UiKit.dp(activity, miuix ? 56 : 64));
        header.addView(barRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout leftSide = new LinearLayout(activity);
        leftSide.setOrientation(LinearLayout.HORIZONTAL);
        leftSide.setGravity(Gravity.CENTER_VERTICAL);
        barRow.addView(leftSide, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL));

        if (showBack) {
            ImageView back = UiKit.createIconButton(
                    activity, p, R.drawable.ic_arrow_back_24, activity.getString(R.string.ui_back), activity::finish);
            LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) back.getLayoutParams();
            blp.rightMargin = UiKit.dp(activity, 8);
            blp.leftMargin = -UiKit.dp(activity, 6);
            leftSide.addView(back);
        }

        titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(p.onSurface);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        subtitleView = new TextView(activity);
        if (miuix) {
            // Small collapsed title, centered; fades in once the large title scrolls away.
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            titleView.setGravity(Gravity.CENTER);
            titleView.setAlpha(0f);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            tlp.leftMargin = UiKit.dp(activity, 96);
            tlp.rightMargin = UiKit.dp(activity, 96);
            barRow.addView(titleView, tlp);
            subtitleView.setVisibility(View.GONE);
        } else {
            LinearLayout titleWrap = new LinearLayout(activity);
            titleWrap.setOrientation(LinearLayout.VERTICAL);
            titleWrap.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams twLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            twLp.rightMargin = UiKit.dp(activity, 10);
            leftSide.addView(titleWrap, twLp);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, showBack ? 20f : 19f);
            titleWrap.addView(titleView);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.topMargin = UiKit.dp(activity, 3);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            subtitleView.setTypeface(Typeface.MONOSPACE);
            subtitleView.setTextColor(p.onSecondaryContainer);
            subtitleView.setPadding(UiKit.dp(activity, 8), UiKit.dp(activity, 2), UiKit.dp(activity, 8), UiKit.dp(activity, 2));
            subtitleView.setBackground(UiKit.roundedRect(activity, p.secondaryContainer, 999f, 0, 0));
            titleWrap.addView(subtitleView, vlp);
            setSubtitle(subtitle);
        }

        actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        barRow.addView(actions, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL));

        progress = new ProgressLine(activity, p);
        progress.setVisibility(View.INVISIBLE);
        header.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(activity, 3)));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            bars = insets.getInsets(WindowInsets.Type.systemBars());
            applyInsets();
            return WindowInsets.CONSUMED;
        });
    }

    /** Sets the title shown in the bar (the collapsed title in Miuix). */
    public void setTitle(String title) {
        titleView.setText(title);
    }

    /** Material 3 only: small monospace pill under the title (version). Ignored by Miuix. */
    public void setSubtitle(String subtitle) {
        if (miuix) return;
        subtitleView.setText(subtitle);
        subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
    }

    public void addAction(View action) {
        if (actions.getChildCount() > 0) {
            ((LinearLayout.LayoutParams) action.getLayoutParams()).leftMargin = UiKit.dp(activity, 8);
        }
        actions.addView(action);
    }

    public void setActionsVisible(boolean visible) {
        UiKit.setAnimatedVisibility(actions, visible ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Adds a scrolling page. A stable {@code scrollId} lets Android restore the scroll position.
     * In Miuix, {@code largeTitle} is shown at the top of the page and collapses into the bar.
     * Pull-to-refresh is off until a listener is set on the returned page.
     */
    public RefreshScrollView addPage(View content, int scrollId, String largeTitle) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView big = null;
        if (miuix && largeTitle != null) {
            big = new TextView(activity);
            big.setText(largeTitle);
            big.setTextColor(palette.onSurface);
            big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
            big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            big.setSingleLine(true);
            big.setEllipsize(TextUtils.TruncateAt.END);
            big.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 4), UiKit.dp(activity, 12), UiKit.dp(activity, 14));
            column.addView(big, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RefreshScrollView scroll = new RefreshScrollView(activity);
        scroll.setId(scrollId);
        scroll.setIndicatorColor(palette.primary);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (pages.indexOf(scroll) == pager.getCurrentPage()) updateCollapse();
        });
        pager.addView(scroll, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pages.add(scroll);
        largeTitles.add(big);
        applyInsets();
        return scroll;
    }

    /** Miuix: fade in the small title and the bar blur as the current page scrolls. */
    private void updateCollapse() {
        if (!miuix || pages.isEmpty()) return;
        int index = Math.max(0, Math.min(pages.size() - 1, pager.getCurrentPage()));
        int y = pages.get(index).getScrollY();
        TextView big = largeTitles.get(index);
        float titleSpan = big != null && big.getHeight() > 0 ? big.getHeight() : UiKit.dp(activity, 48);
        float collapse = Math.max(0f, Math.min(1f, y / titleSpan));
        titleView.setAlpha(big == null ? 1f : Math.max(0f, (collapse - 0.5f) * 2f));
        if (big != null) big.setAlpha(1f - collapse);
        // Like SukiSU's BlurredBar: the blur shows once the page is scrolled.
        header.setGlassAlpha(Math.max(0f, Math.min(1f, y / (float) UiKit.dp(activity, 16))));
    }

    public void showPage(int index, boolean animate) {
        pager.setPage(index, animate);
    }

    public void setOnPageChangeListener(PagerHost.OnPageChangeListener l) {
        pager.setOnPageChangeListener(l);
    }

    public void setBottomBar(BottomBar bar) {
        this.bottomBar = bar;
        bar.setBlurSource(stage);
        // The selection slides with the finger while the pages are swiped.
        pager.setOnPageScrollListener(bar::setIndicatorPosition);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, bar.contentHeightPx(), Gravity.BOTTOM);
        root.addView(bar, lp);
        applyInsets();
    }

    /** Space taken at the bottom by the bottom bar (without the system navigation inset). */
    private int bottomBarSpace() {
        if (bottomBar == null) return 0;
        return bottomBar.contentHeightPx() + (bottomBar.isFloating() ? UiKit.dp(activity, 16) : 0);
    }

    private void applyInsets() {
        Context ctx = activity;
        int side = UiKit.dp(ctx, miuix ? 12 : 18);
        barRow.setPadding(side + bars.left, bars.top, side + bars.right, 0);

        int pageSide = UiKit.dp(ctx, miuix ? 12 : 16);
        int pageTop = headerHeight + UiKit.dp(ctx, miuix ? 0 : 4);
        int pageBottom = UiKit.dp(ctx, 28) + bars.bottom + bottomBarSpace();
        for (ScrollView page : pages) {
            if (page.getPaddingTop() != pageTop || page.getPaddingBottom() != pageBottom
                    || page.getPaddingLeft() != pageSide + bars.left) {
                page.setPadding(pageSide + bars.left, pageTop, pageSide + bars.right, pageBottom);
            }
        }

        if (bottomBar != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
            if (bottomBar.isFloating()) {
                // SukiSU: 28dp side margins; 8dp above the navigation bar (28dp without one).
                lp.height = bottomBar.contentHeightPx();
                lp.leftMargin = UiKit.dp(ctx, 28) + bars.left;
                lp.rightMargin = UiKit.dp(ctx, 28) + bars.right;
                lp.bottomMargin = bars.bottom > 0 ? bars.bottom + UiKit.dp(ctx, 8) : UiKit.dp(ctx, 28);
            } else {
                lp.height = bottomBar.contentHeightPx() + bars.bottom;
                bottomBar.setPadding(bars.left, 0, bars.right, bars.bottom);
            }
            bottomBar.setLayoutParams(lp);
        }

        updateCollapse();
    }

    public void setBusy(boolean busy) {
        if (busy) {
            UiKit.setAnimatedVisibility(progress, View.VISIBLE);
            progress.start();
        } else {
            progress.stop();
            UiKit.setAnimatedVisibility(progress, View.INVISIBLE);
        }
    }

    /** Shows {@code msg} as a short system toast, replacing any toast still on screen. */
    public void showToast(String msg) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (toast != null) toast.cancel();
        toast = Toast.makeText(activity.getApplicationContext(), msg, Toast.LENGTH_SHORT);
        toast.show();
    }

    /** Call from {@code onDestroy}. */
    public void release() {
        progress.stop();
    }

    /** Thin 3dp indeterminate progress line. */
    private static final class ProgressLine extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private ValueAnimator anim;
        private float phase = 0f;

        ProgressLine(Context ctx, UiKit.Palette p) {
            super(ctx);
            trackPaint.setColor(UiKit.isMiuix() ? p.surfaceHighest : p.secondaryContainer);
            barPaint.setColor(p.primary);
        }

        void start() {
            if (anim != null && anim.isRunning()) return;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1050L);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void stop() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, trackPaint);
            float segW = w * 0.36f;
            float left = -segW + (w + segW) * phase;
            rect.set(left, 0, left + segW, h);
            canvas.drawRoundRect(rect, r, r, barPaint);
        }
    }
}
