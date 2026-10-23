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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    // Shown first, in this order. Any other TV app you install later appears after them.
    private static final List<String> FAVORITES = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.stremio.one",
            "com.android.vending");

    // Never shown as a card (settings has its own gear button).
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

    private static final int BG = 0xFF07070A;
    private static final int CARD_BG = 0xFF17171C;
    private static final int ANIM_MS = 160;

    private GlowView glow;
    private TextView greeting, heroTitle, heroSub;
    private ImageView heroIcon;
    private LinearLayout row;
    private HorizontalScrollView rowScroll;
    private View gear;

    private String focusedPkg;
    private int accent = 0xFF7A1420;
    private ValueAnimator accentAnim;
    private final Map<String, Integer> accentCache = new HashMap<>();
    private final List<AppEntry> apps = new ArrayList<>();
    private String appsSignature = "";

    static final class AppEntry {
        String pkg; CharSequence label; Drawable icon; Drawable banner; Intent launch;
    }

    // ---------------------------------------------------------------- lifecycle

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
        greeting.setText(greetingText());
        reloadApps();
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back does nothing.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { openSettings(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        glow = new GlowView(this);
        glow.setAccent(accent);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(64), dp(36), dp(64), dp(28));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        // Top bar: greeting ............ gear  clock
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        greeting = text(18, 0xB3FFFFFF, false);
        top.addView(greeting, new LinearLayout.LayoutParams(0, -2, 1f));

        gear = buildGear();
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(44), dp(44));
        gp.rightMargin = dp(22);
        top.addView(gear, gp);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        top.addView(clock);
        content.addView(top);

        content.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));

        // Hero: the focused app, big
        heroIcon = new ImageView(this);
        heroIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(64), dp(64));
        ip.bottomMargin = dp(14);
        content.addView(heroIcon, ip);

        heroTitle = text(56, Color.WHITE, true);
        heroTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        heroTitle.setLetterSpacing(-0.02f);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(heroTitle);

        heroSub = text(20, 0xCCFFFFFF, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(6);
        content.addView(heroSub, sp);

        TextView hint = text(14, 0x80FFFFFF, false);
        hint.setText("Press OK to open");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = dp(14);
        content.addView(hint, hp);

        content.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(36)));

        // Row title + row of cards
        TextView rowTitle = text(17, 0xE6FFFFFF, true);
        rowTitle.setText("My apps");
        content.addView(rowTitle);

        rowScroll = new HorizontalScrollView(this);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setClipChildren(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setFillViewport(false);
        rowScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(4), dp(22), dp(40), dp(22));
        rowScroll.addView(row);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.leftMargin = -dp(4);
        content.addView(rowScroll, rp);

        return root;
    }

    private View buildGear() {
        FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x00FFFFFF);
        f.setBackground(bg);
        ImageView g = new ImageView(this);
        g.setImageResource(R.drawable.ic_gear);
        g.setColorFilter(0xCCFFFFFF);
        f.addView(g, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        f.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFFFFFFF : 0x00FFFFFF);
            g.setColorFilter(has ? 0xFF000000 : 0xCCFFFFFF);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(ANIM_MS).start();
            if (has) setHero(null);
        });
        f.setOnClickListener(v -> openSettings());
        return f;
    }

    // ---------------------------------------------------------------- apps

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
        if (sig.equals(appsSignature) && row.getChildCount() > 0) {
            restoreFocus();
            return;
        }
        appsSignature = sig;

        apps.clear();
        for (String p : order) {
            ResolveInfo ri = byPkg.get(p);
            AppEntry e = new AppEntry();
            e.pkg = p;
            e.label = ri.loadLabel(pm);
            e.icon = ri.loadIcon(pm);
            e.banner = ri.activityInfo.loadBanner(pm);
            if (e.banner == null) e.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            e.launch = pm.getLeanbackLaunchIntentForPackage(p);
            if (e.launch == null) e.launch = pm.getLaunchIntentForPackage(p);
            apps.add(e);
        }

        row.removeAllViews();
        for (int i = 0; i < apps.size(); i++) row.addView(buildCard(apps.get(i), i));
        if (row.getChildCount() > 0) {
            gear.setNextFocusDownId(row.getChildAt(0).getId());
        }
        restoreFocus();
    }

    private void restoreFocus() {
        View target = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (focusedPkg != null && focusedPkg.equals(c.getTag())) { target = c; break; }
        }
        if (target == null && row.getChildCount() > 0) target = row.getChildAt(0);
        if (target != null) {
            final View t = target;
            t.post(t::requestFocus);
        } else {
            setHero(null);
            gear.requestFocus();
        }
    }

    private View buildCard(AppEntry e, int index) {
        int w = dp(288), h = dp(162), r = dp(14);

        FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        card.setTag(e.pkg);
        card.setFocusable(true);
        card.setClickable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(r);
        bg.setColor(CARD_BG);
        card.setBackground(bg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        card.setElevation(dp(2));

        if (e.banner != null) {
            ImageView b = new ImageView(this);
            b.setImageDrawable(e.banner);
            b.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(b, new FrameLayout.LayoutParams(-1, -1));
        } else {
            int a = accentFor(e);
            bg.setColors(new int[]{darken(a, 0.55f), darken(a, 0.25f)});
            bg.setOrientation(GradientDrawable.Orientation.TL_BR);
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(e.icon);
            card.addView(ic, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
            TextView l = text(15, Color.WHITE, true);
            l.setText(e.label);
            l.setSingleLine(true);
            l.setEllipsize(TextUtils.TruncateAt.END);
            l.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            lp.bottomMargin = dp(12);
            lp.leftMargin = lp.rightMargin = dp(12);
            card.addView(l, lp);
        }

        // Focus ring
        View ring = new View(this);
        GradientDrawable rd = new GradientDrawable();
        rd.setCornerRadius(r);
        rd.setColor(Color.TRANSPARENT);
        rd.setStroke(dp(3), Color.WHITE);
        ring.setBackground(rd);
        ring.setAlpha(0f);
        card.addView(ring, new FrameLayout.LayoutParams(-1, -1));

        card.setAlpha(0.72f);
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.10f : 1f;
            v.animate().scaleX(s).scaleY(s).alpha(has ? 1f : 0.72f)
                    .translationZ(has ? dp(14) : 0)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(ANIM_MS).start();
            ring.animate().alpha(has ? 1f : 0f).setDuration(ANIM_MS).start();
            if (has) {
                focusedPkg = e.pkg;
                setHero(e);
            }
        });
        card.setOnClickListener(v -> launch(e));
        card.setNextFocusUpId(gear.getId() == View.NO_ID ? assignId(gear) : gear.getId());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        if (index > 0) lp.leftMargin = dp(26);
        card.setLayoutParams(lp);
        return card;
    }

    private int assignId(View v) {
        int id = View.generateViewId();
        v.setId(id);
        return id;
    }

    private void launch(AppEntry e) {
        if (e.launch == null) return;
        Intent i = new Intent(e.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (ActivityNotFoundException ignored) { }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) { }
    }

    // ---------------------------------------------------------------- hero + colour

    private void setHero(AppEntry e) {
        final CharSequence title = e == null ? "Settings" : e.label;
        final CharSequence sub = e == null ? "Wi-Fi, picture, sound and more"
                : (TAGLINES.containsKey(e.pkg) ? TAGLINES.get(e.pkg) : "Open app");
        final Drawable icon = e == null ? null : e.icon;
        final int target = e == null ? 0xFF3A4458 : accentFor(e);

        heroTitle.animate().cancel();
        heroTitle.animate().alpha(0f).translationX(-dp(8)).setDuration(90).withEndAction(() -> {
            heroTitle.setText(title);
            heroSub.setText(sub);
            if (icon != null) { heroIcon.setImageDrawable(icon); heroIcon.setVisibility(View.VISIBLE); }
            else heroIcon.setVisibility(View.INVISIBLE);
            heroTitle.setTranslationX(dp(8));
            heroTitle.animate().alpha(1f).translationX(0).setDuration(ANIM_MS).start();
            heroSub.setAlpha(0f);
            heroSub.animate().alpha(1f).setDuration(ANIM_MS + 60).start();
            heroIcon.setAlpha(0f);
            heroIcon.animate().alpha(1f).setDuration(ANIM_MS).start();
        }).start();

        animateAccent(target);
    }

    private void animateAccent(int to) {
        if (accentAnim != null) accentAnim.cancel();
        accentAnim = ValueAnimator.ofObject(new ArgbEvaluator(), accent, to);
        accentAnim.setDuration(420);
        accentAnim.setInterpolator(new DecelerateInterpolator());
        accentAnim.addUpdateListener(a -> {
            accent = (Integer) a.getAnimatedValue();
            glow.setAccent(accent);
        });
        accentAnim.start();
    }

    /** Picks a rich colour out of the app icon so each app tints the screen differently. */
    private int accentFor(AppEntry e) {
        Integer c = accentCache.get(e.pkg);
        if (c != null) return c;
        int result = 0xFF3A4458;
        try {
            Bitmap b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            Drawable d = e.icon.getConstantState() != null
                    ? e.icon.getConstantState().newDrawable().mutate() : e.icon;
            d.setBounds(0, 0, 32, 32);
            d.draw(cv);
            double x = 0, y = 0, wSum = 0;
            float[] hsv = new float[3];
            for (int i = 0; i < 32; i++) for (int j = 0; j < 32; j++) {
                int p = b.getPixel(i, j);
                if (Color.alpha(p) < 160) continue;
                Color.colorToHSV(p, hsv);
                double w = hsv[1] * hsv[1] * hsv[2];
                if (w < 0.05) continue;
                double rad = Math.toRadians(hsv[0]);
                x += Math.cos(rad) * w; y += Math.sin(rad) * w; wSum += w;
            }
            b.recycle();
            if (wSum > 1.5) {
                float hue = (float) Math.toDegrees(Math.atan2(y, x));
                if (hue < 0) hue += 360f;
                result = Color.HSVToColor(new float[]{hue, 0.85f, 0.72f});
            }
        } catch (Throwable ignored) { }
        accentCache.put(e.pkg, result);
        return result;
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    // ---------------------------------------------------------------- helpers

    private String greetingText() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 5) return "Good night";
        if (h < 12) return "Good morning";
        if (h < 18) return "Good afternoon";
        return "Good evening";
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Dark background with a soft coloured glow in the top-right, darkened on the left and bottom. */
    static final class GlowView extends View {
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint leftPaint = new Paint();
        private final Paint bottomPaint = new Paint();
        private int accent = 0xFF7A1420;

        GlowView(Context c) { super(c); }

        void setAccent(int a) { accent = a; rebuild(); invalidate(); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) { rebuild(); }

        private void rebuild() {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            int a = accent & 0x00FFFFFF;
            glowPaint.setShader(new RadialGradient(w * 0.82f, h * 0.12f, w * 0.72f,
                    new int[]{0x99000000 | a, 0x33000000 | a, 0x00000000 | a},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            glow2Paint.setShader(new RadialGradient(w * 0.98f, h * 0.85f, w * 0.40f,
                    new int[]{0x40000000 | a, 0x00000000 | a}, null, Shader.TileMode.CLAMP));
            leftPaint.setShader(new LinearGradient(0, 0, w * 0.62f, 0,
                    0xD907070A, 0x0007070A, Shader.TileMode.CLAMP));
            bottomPaint.setShader(new LinearGradient(0, h * 0.45f, 0, h,
                    0x0007070A, 0xF207070A, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(BG);
            int w = getWidth(), h = getHeight();
            c.drawRect(0, 0, w, h, glowPaint);
            c.drawRect(0, 0, w, h, glow2Paint);
            c.drawRect(0, 0, w, h, leftPaint);
            c.drawRect(0, 0, w, h, bottomPaint);
        }
    }
}
