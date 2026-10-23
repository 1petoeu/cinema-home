package sk.peto.cinema;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    // Shown first, in this order. Any other TV app installed later appears after them.
    private static final List<String> FAVORITES = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.stremio.one",
            "com.android.vending");

    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "sk.peto.cinema",
            "com.android.tv.settings",
            "com.droidlogic.tv.settings",
            "com.google.android.tvlauncher",
            "com.spocky.projengmenu",
            "me.efesser.flauncher"));

    private static final Map<String, String> TAGLINES = new HashMap<>();
    static {
        TAGLINES.put("com.google.android.youtube.tv", "Videos, music and live streams");
        TAGLINES.put("com.stremio.one", "Movies and series");
        TAGLINES.put("com.android.vending", "Get more apps");
    }

    private static final int BG = 0xFF0B0B0D;
    private static final int TILE_BG = 0xFF1C1C21;
    private static final int ANIM_MS = 120;

    private GlowView glow;
    private ImageView backdrop;
    private TextView heroTitle, heroSub, heroChip;
    private LinearLayout row;
    private View gear;

    private String focusedPkg;
    private int accent = 0xFF5A0F18;
    private ValueAnimator accentAnim;
    private final Map<String, Integer> accentCache = new HashMap<>();
    private final Map<String, Drawable> backdropCache = new HashMap<>();
    private String appsSignature = "";

    static final class AppEntry {
        String pkg; CharSequence label; Drawable icon; Drawable banner; Intent launch;
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadApps();
    }

    @Override
    public void onBackPressed() { /* home screen */ }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { openSettings(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------ layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // 1. coloured ambient glow
        glow = new GlowView(this);
        glow.setAccent(accent);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        // 2. billboard backdrop: the focused app's art, soft and large, on the right
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        backdrop.setColorFilter(0xFF8C8C8C, android.graphics.PorterDuff.Mode.MULTIPLY);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -1);
        bp.leftMargin = dp(300);
        bp.bottomMargin = dp(150);
        root.addView(backdrop, bp);

        // 3. shade: dark from the left and from the bottom so text and tiles stay readable
        root.addView(new ShadeView(this), new FrameLayout.LayoutParams(-1, -1));

        // 4. content
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(56), dp(28), dp(56), dp(26));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        gear = buildGear();
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(34), dp(34));
        gp.rightMargin = dp(16);
        top.addView(gear, gp);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextColor(0xE6FFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        clock.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        top.addView(clock);
        content.addView(top);

        content.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));

        heroTitle = text(40, Color.WHITE);
        heroTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        heroTitle.setLetterSpacing(-0.01f);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(heroTitle, new LinearLayout.LayoutParams(dp(560), -2));

        heroSub = text(15, 0xB3FFFFFF);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(8);
        content.addView(heroSub, sp);

        heroChip = text(13, 0xFF000000);
        heroChip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heroChip.setText("▶  Open");
        heroChip.setPadding(dp(14), dp(7), dp(16), dp(7));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setCornerRadius(dp(4));
        chipBg.setColor(Color.WHITE);
        heroChip.setBackground(chipBg);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = dp(16);
        content.addView(heroChip, cp);

        content.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(30)));

        TextView rowTitle = text(14, 0xD9FFFFFF);
        rowTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        rowTitle.setText("My apps");
        content.addView(rowTitle);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(2), dp(12), dp(40), dp(12));
        scroll.addView(row);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.leftMargin = -dp(2);
        content.addView(scroll, rp);

        return root;
    }

    private View buildGear() {
        FrameLayout f = new FrameLayout(this);
        f.setId(View.generateViewId());
        f.setFocusable(true);
        f.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x00FFFFFF);
        f.setBackground(bg);
        ImageView g = new ImageView(this);
        g.setImageResource(R.drawable.ic_gear);
        g.setColorFilter(0xB3FFFFFF);
        f.addView(g, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        f.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFFFFFFF : 0x00FFFFFF);
            g.setColorFilter(has ? 0xFF000000 : 0xB3FFFFFF);
            if (has) setHero(null);
        });
        f.setOnClickListener(v -> openSettings());
        return f;
    }

    // ------------------------------------------------------------ apps

    private void reloadApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(q, 0);

        Map<String, ResolveInfo> byPkg = new HashMap<>();
        for (ResolveInfo ri : found) {
            String p = ri.activityInfo.packageName;
            if (!HIDDEN.contains(p) && !byPkg.containsKey(p)) byPkg.put(p, ri);
        }
        List<String> order = new ArrayList<>();
        for (String f : FAVORITES) if (byPkg.containsKey(f)) order.add(f);
        List<String> rest = new ArrayList<>(byPkg.keySet());
        rest.removeAll(order);
        Collections.sort(rest, (a, b) -> String.valueOf(byPkg.get(a).loadLabel(pm))
                .compareToIgnoreCase(String.valueOf(byPkg.get(b).loadLabel(pm))));
        order.addAll(rest);

        String sig = TextUtils.join(",", order);
        if (sig.equals(appsSignature) && row.getChildCount() > 0) { restoreFocus(); return; }
        appsSignature = sig;
        backdropCache.clear();

        row.removeAllViews();
        for (int i = 0; i < order.size(); i++) {
            String p = order.get(i);
            ResolveInfo ri = byPkg.get(p);
            AppEntry e = new AppEntry();
            e.pkg = p;
            e.label = ri.loadLabel(pm);
            e.icon = ri.loadIcon(pm);
            e.banner = ri.activityInfo.loadBanner(pm);
            if (e.banner == null) e.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            e.launch = pm.getLeanbackLaunchIntentForPackage(p);
            if (e.launch == null) e.launch = pm.getLaunchIntentForPackage(p);
            row.addView(buildTile(e, i));
        }
        if (row.getChildCount() > 0) gear.setNextFocusDownId(row.getChildAt(0).getId());
        restoreFocus();
    }

    private void restoreFocus() {
        View target = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (focusedPkg != null && focusedPkg.equals(c.getTag())) { target = c; break; }
        }
        if (target == null && row.getChildCount() > 0) target = row.getChildAt(0);
        if (target != null) { final View t = target; t.post(t::requestFocus); }
        else { setHero(null); gear.requestFocus(); }
    }

    private View buildTile(AppEntry e, int index) {
        int w = dp(200), h = dp(112), r = dp(6);

        FrameLayout tile = new FrameLayout(this);
        tile.setId(View.generateViewId());
        tile.setTag(e.pkg);
        tile.setFocusable(true);
        tile.setClickable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(r);
        bg.setColor(TILE_BG);
        tile.setBackground(bg);
        tile.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        tile.setClipToOutline(true);

        if (e.banner != null) {
            ImageView b = new ImageView(this);
            b.setImageDrawable(e.banner);
            b.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(b, new FrameLayout.LayoutParams(-1, -1));
        } else {
            int a = accentFor(e);
            bg.setColors(new int[]{darken(a, 0.45f), darken(a, 0.2f)});
            bg.setOrientation(GradientDrawable.Orientation.TL_BR);
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(e.icon);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER);
            ip.bottomMargin = dp(12);
            tile.addView(ic, ip);
            TextView l = text(12, 0xE6FFFFFF);
            l.setText(e.label);
            l.setSingleLine(true);
            l.setEllipsize(TextUtils.TruncateAt.END);
            l.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            lp.bottomMargin = dp(9);
            lp.leftMargin = lp.rightMargin = dp(8);
            tile.addView(l, lp);
        }

        View ring = new View(this);
        GradientDrawable rd = new GradientDrawable();
        rd.setCornerRadius(r);
        rd.setColor(Color.TRANSPARENT);
        rd.setStroke(dp(2), Color.WHITE);
        ring.setBackground(rd);
        ring.setAlpha(0f);
        tile.addView(ring, new FrameLayout.LayoutParams(-1, -1));

        tile.setAlpha(0.8f);
        tile.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).alpha(has ? 1f : 0.8f)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(ANIM_MS).start();
            ring.animate().alpha(has ? 1f : 0f).setDuration(ANIM_MS).start();
            if (has) { focusedPkg = e.pkg; setHero(e); }
        });
        tile.setOnClickListener(v -> launch(e));
        tile.setNextFocusUpId(gear.getId());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        if (index > 0) lp.leftMargin = dp(12);
        tile.setLayoutParams(lp);
        return tile;
    }

    private void launch(AppEntry e) {
        if (e.launch == null) return;
        try { startActivity(new Intent(e.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException ignored) { }
    }

    private void openSettings() {
        try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException ignored) { }
    }

    // ------------------------------------------------------------ billboard

    private void setHero(AppEntry e) {
        final CharSequence title = e == null ? "Settings" : e.label;
        final CharSequence sub = e == null ? "Wi-Fi, picture, sound and more"
                : (TAGLINES.containsKey(e.pkg) ? TAGLINES.get(e.pkg) : "");
        final Drawable art = e == null ? null : backdropFor(e);

        heroTitle.setText(title);
        heroSub.setText(sub);
        heroSub.setVisibility(sub.length() == 0 ? View.GONE : View.VISIBLE);
        heroTitle.setAlpha(0.4f);
        heroTitle.animate().alpha(1f).setDuration(ANIM_MS).start();

        backdrop.animate().cancel();
        backdrop.animate().alpha(0f).setDuration(80).withEndAction(() -> {
            backdrop.setImageDrawable(art);
            if (art != null) backdrop.animate().alpha(0.55f).setDuration(260).start();
        }).start();

        animateAccent(e == null ? 0xFF2A303C : accentFor(e));
    }

    /**
     * Banner rendered into a tiny bitmap and stretched back up: free, GPU-cheap soft blur,
     * so the billboard looks like out-of-focus key art rather than a pixelated logo.
     */
    private Drawable backdropFor(AppEntry e) {
        if (backdropCache.containsKey(e.pkg)) return backdropCache.get(e.pkg);
        Drawable src = e.banner != null ? e.banner : null;
        Drawable out = null;
        if (src != null) {
            try {
                Bitmap small = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(small);
                Drawable d = src.getConstantState() != null ? src.getConstantState().newDrawable().mutate() : src;
                d.setBounds(0, 0, 48, 27);
                d.draw(c);
                Bitmap mid = Bitmap.createScaledBitmap(small, 192, 108, true);
                small.recycle();
                BitmapDrawable bd = new BitmapDrawable(getResources(), mid);
                bd.setFilterBitmap(true);
                out = bd;
            } catch (Throwable ignored) { }
        }
        backdropCache.put(e.pkg, out);
        return out;
    }

    private void animateAccent(int to) {
        if (accentAnim != null) accentAnim.cancel();
        accentAnim = ValueAnimator.ofObject(new ArgbEvaluator(), accent, to);
        accentAnim.setDuration(350);
        accentAnim.setInterpolator(new DecelerateInterpolator());
        accentAnim.addUpdateListener(a -> { accent = (Integer) a.getAnimatedValue(); glow.setAccent(accent); });
        accentAnim.start();
    }

    private int accentFor(AppEntry e) {
        Integer cached = accentCache.get(e.pkg);
        if (cached != null) return cached;
        int result = 0xFF2A303C;
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            Drawable d = e.icon.getConstantState() != null ? e.icon.getConstantState().newDrawable().mutate() : e.icon;
            d.setBounds(0, 0, 24, 24);
            d.draw(cv);
            double x = 0, y = 0, wSum = 0;
            float[] hsv = new float[3];
            for (int i = 0; i < 24; i++) for (int j = 0; j < 24; j++) {
                int p = b.getPixel(i, j);
                if (Color.alpha(p) < 160) continue;
                Color.colorToHSV(p, hsv);
                double w = hsv[1] * hsv[1] * hsv[2];
                if (w < 0.05) continue;
                double rad = Math.toRadians(hsv[0]);
                x += Math.cos(rad) * w; y += Math.sin(rad) * w; wSum += w;
            }
            b.recycle();
            if (wSum > 1.0) {
                float hue = (float) Math.toDegrees(Math.atan2(y, x));
                if (hue < 0) hue += 360f;
                result = Color.HSVToColor(new float[]{hue, 0.8f, 0.5f});
            }
        } catch (Throwable ignored) { }
        accentCache.put(e.pkg, result);
        return result;
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------ background layers

    /** Soft coloured light from the top-right, tinted by the focused app. */
    static final class GlowView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int accent;
        GlowView(Context c) { super(c); }
        void setAccent(int a) { accent = a; rebuild(); invalidate(); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { rebuild(); }
        private void rebuild() {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            int a = accent & 0x00FFFFFF;
            p.setShader(new RadialGradient(w * 0.85f, h * 0.05f, w * 0.75f,
                    new int[]{0xB0000000 | a, 0x40000000 | a, a},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas c) { c.drawRect(0, 0, getWidth(), getHeight(), p); }
    }

    /** Netflix-style fade: solid on the left, clear on the right; plus a fade into the tile row. */
    static final class ShadeView extends View {
        private final Paint left = new Paint(), bottom = new Paint();
        ShadeView(Context c) { super(c); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            left.setShader(new LinearGradient(0, 0, w * 0.7f, 0,
                    new int[]{BG, 0xCC000000 | (BG & 0xFFFFFF), BG & 0xFFFFFF},
                    new float[]{0.25f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            bottom.setShader(new LinearGradient(0, h * 0.42f, 0, h * 0.78f,
                    BG & 0xFFFFFF, BG, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), left);
            c.drawRect(0, 0, getWidth(), getHeight(), bottom);
        }
    }
}
