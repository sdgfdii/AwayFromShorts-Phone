package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared colors and components for the Swiss layout grid.
 * Every skin uses 20dp page margins, 16dp panel insets and semantic text roles.
 * ThemeStore applies the chosen font and SwissTypography supplies size, leading and weight.
 */
public final class Ui {

    public static final String SP = "afs_ui";
    public static final String K_THEME = "theme";     // dark | light | auto

    /* ---------- 深色（默认） ---------- */
    public static final int D_BG = 0xFF0B0C10;
    public static final int D_SURFACE = 0xFF16171C;
    public static final int D_SURFACE2 = 0xFF1E2027;
    public static final int D_LINE = 0xFF262932;
    public static final int D_TEXT = 0xFFF2F3F5;
    public static final int D_TEXT2 = 0xFF9096A1;
    public static final int D_TEXT3 = 0xFF6B707A;

    /* ---------- 浅色 ---------- */
    public static final int L_BG = 0xFFF3F4F6;
    public static final int L_SURFACE = 0xFFFFFFFF;
    public static final int L_SURFACE2 = 0xFFEDEFF2;
    public static final int L_LINE = 0xFFE3E6EB;
    public static final int L_TEXT = 0xFF14161A;
    public static final int L_TEXT2 = 0xFF5F6570;
    public static final int L_TEXT3 = 0xFF8B9199;

    /* ---------- 强调色（两套主题共用） ---------- */
    public static final int BLUE = 0xFF2F6FED;      // 主色：Voyage 那种蓝
    public static final int BLUE_DIM = 0xFF1F4FB0;
    public static final int TEAL = 0xFF12A594;
    public static final int GREEN = 0xFF2FA36B;
    public static final int RUST = 0xFFB4573F;
    public static final int PURPLE = 0xFF7A57D1;
    public static final int RED = 0xFFFF5A6E;
    public static final int AMBER = 0xFFD4971E;

    /* 图标格底色（圆形/圆角方块里的那层） */
    public static final int TILE_BLUE = 0xFF2A5FBF;
    public static final int TILE_TEAL = 0xFF14706A;
    public static final int TILE_GREEN = 0xFF22694A;
    public static final int TILE_RUST = 0xFF9C4B37;
    public static final int TILE_PURPLE = 0xFF5F45A8;

    private final Context ctx;
    public final boolean dark;
    public final AppearancePalette palette;
    public final boolean amap;
    /** 涟漪风格：浅底、大标题、圆角白卡、胶囊控件。 */
    public final boolean ripple;

    public Ui(Context c) {
        this.ctx = c;
        this.dark = isDark(c);
        this.palette = ThemeStore.palette(c,dark);
        AppearanceConfig config=ThemeStore.load(c);
        this.amap = config.amap();
        this.ripple = config.ripple();
    }

    /** 给没有 Ui 实例的地方（排版、导航）快速判断是否涟漪风格。 */
    public static boolean rippleMode(Context c) {
        return ThemeStore.load(c).ripple();
    }

    /* ==================== 主题 ==================== */

    /** 当前是否深色。auto 跟随系统（夜里深、白天浅）。 */
    public static boolean isDark(Context c) {
        return ThemeStore.dark(c);
    }

    public static void setTheme(Context c, String theme) {
        AppearanceConfig a=ThemeStore.load(c);a.mode=theme;ThemeStore.save(c,a);
    }

    /** 深/浅互切（首页右上角那个按钮用）。 */
    public static void toggle(Context c) {
        setTheme(c, isDark(c) ? "light" : "dark");
    }

    public static String themeName(Context c) {
        return ThemeStore.load(c).mode;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /* ==================== 颜色 ==================== */

    public int bg() {
        return palette.bg;
    }

    public int surface() {
        return palette.surface;
    }

    /** 比卡片再亮一档：卡片里再嵌一层（进度条底、内嵌行）时才用。 */
    public int surface2() {
        return palette.surface2;
    }

    public int line() {
        return palette.line;
    }

    public int text() {
        return palette.text;
    }

    public int text2() {
        return palette.text2;
    }

    public int text3() {
        return palette.text3;
    }

    /** 深色下用亮一点的蓝，浅色下用标准蓝，保证各自对比度。 */
    public int accent() {
        return palette.accent;
    }
    public int onAccent(){return palette.onAccent;}

    public int green() {
        return dark ? 0xFF3DDC84 : 0xFF12A150;
    }

    public int red() {
        return dark ? 0xFFFF7B72 : 0xFFD93A3A;
    }

    /* ==================== 尺寸 ==================== */

    public int dp(float v) {
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()) + 0.5f);
    }

    /* ==================== 构件 ==================== */

    public GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        float radius=amap&&radiusDp>=10&&radiusDp<90?Math.min(radiusDp,16):radiusDp;
        g.setCornerRadius(dp(radius));
        if(amap&&color==surface()&&radiusDp>=16)g.setStroke(Math.max(1,dp(.5f)),line());
        return g;
    }

    public GradientDrawable round(int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable g = round(color, radiusDp);
        if (strokeColor != 0) {
            g.setStroke(dp(strokeDp), strokeColor);
        }
        return g;
    }

    public void bg(View v, int color, float radiusDp) {
        v.setBackground(round(color, radiusDp));
    }

    /** 按钮圆角：涟漪风格下，所有按钮统一成胶囊。 */
    public float button(float baseDp) {
        return ripple ? 99f : baseDp;
    }

    /** 一张标准卡片：圆角 18、内边距 16、无描边。涟漪风格更圆一点。 */
    public LinearLayout card() {
        return card(ripple?22f:18f, ripple?18f:16f);
    }

    public LinearLayout card(float radiusDp, float padDp) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(surface(), radiusDp));
        int p = dp(padDp);
        box.setPadding(p, p, p, p);
        if(amap||ripple){box.setElevation(dp(1));box.setClipToOutline(true);}
        return box;
    }

    /** Amap's pale blue header fades into the neutral page; classic stays unchanged. */
    public void page(View view){if(amap)view.setBackground(AmapStyle.page(this));else view.setBackgroundColor(bg());}

    /** 卡片之间统一留 10dp，看起来才像一组。 */
    public LinearLayout.LayoutParams gap(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    /** 大标题（Voyage 的「晚上好」那一档）。 */
    public TextView title(String s) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(28);
        tv.setTextColor(text());
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLineSpacing(dp(4), 1f);
        return SwissTypography.role(tv,SwissTypography.Role.TITLE);
    }

    /** 次级说明行（日期、副标题）。 */
    public TextView sub(String s) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setTextColor(text2());
        return SwissTypography.role(tv,SwissTypography.Role.META);
    }

    /** 区块标题：彩色小方块 + 标题 + 右侧动作，一眼看出这是"一组"。 */
    public LinearLayout section(String glyph, int glyphBg, String name, String action,
                                View.OnClickListener onAction) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        if(amap){row.addView(new AmapStyle.Icon(ctx,name,text()),new LinearLayout.LayoutParams(dp(24),dp(24)));}
        else if(!ripple) {
        TextView icon = new TextView(ctx);
        icon.setText(glyph);
        icon.setTextSize(11);
        icon.setTextColor(0xFFFFFFFF);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(glyphBg, 7f));
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        }

        TextView t = new TextView(ctx);
        t.setText(name);
        t.setTextSize(ripple?14:18);
        t.setTextColor(ripple?text3():text());
        t.setTypeface(ripple?Typeface.DEFAULT:Typeface.DEFAULT_BOLD);
        SwissTypography.role(t,ripple?SwissTypography.Role.KEEP:SwissTypography.Role.SECTION);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = ripple ? 0 : dp(12);
        row.addView(t, tlp);

        if (action != null) {
            TextView a = new TextView(ctx);
            a.setText(action);
            a.setTextSize(12);
            a.setTextColor(text2());
            a.setPadding(dp(8), dp(6), 0, dp(6));
            if (onAction != null) {
                a.setOnClickListener(onAction);
            }
            row.addView(a);
        }
        return row;
    }

    /** 圆形图标（Voyage 工具格里那种彩色圆）。 */
    public TextView circle(String glyph, int color, float sizeDp, int glyphSize) {
        TextView tv = new TextView(ctx);
        tv.setText(glyph);
        tv.setTextSize(glyphSize);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(round(color, sizeDp / 2f));
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return tv;
    }

    /**
     * 图标格：上层彩色圆、下层两行字。Voyage 的工具格就是这个结构，
     * 比"一整块大按钮"更好扫读 —— 圆形本身就是可点的暗示。
     */
    public LinearLayout tile(String glyph, int circleColor, String label, String hint,
                             View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackground(round(surface(), 16f));
        int p = dp(12);
        box.setPadding(p, p, p, p);
        if (onClick != null) {
            box.setOnClickListener(onClick);
        }

        LinearLayout iconWrap = new LinearLayout(ctx);
        iconWrap.setGravity(Gravity.CENTER);
        if(amap)iconWrap.addView(new AmapStyle.Icon(ctx,label,text()),new LinearLayout.LayoutParams(dp(32),dp(32)));
        else if(ripple){
            TextView badge = new TextView(ctx);
            badge.setText(glyph);badge.setTextSize(17);badge.setTextColor(circleColor);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(round(soft(circleColor,.86f),14f));
            iconWrap.addView(badge,new LinearLayout.LayoutParams(dp(44),dp(44)));
        }
        else iconWrap.addView(circle(glyph, circleColor, 44f, 17));
        box.addView(iconWrap);

        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(text());
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(9);
        box.addView(t, tlp);

        if (hint != null) {
            TextView h = new TextView(ctx);
            h.setText(hint);
            h.setTextSize(10);
            h.setTextColor(text3());
            h.setGravity(Gravity.CENTER);
            h.setMaxLines(1);
            // 英文比中文长，超出宽度时静默裁掉会留下「Cards · rewards ·」这种断尾。
            // 显示省略号至少说明「还有内容」，不至于看起来像 bug。
            h.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(h);
        }
        return box;
    }

    /** 胶囊：标签/状态用，不抢按钮的位置。 */
    public TextView chip(String s, int fg, int fill) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(ripple?12:11);
        tv.setTextColor(fg);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(round(fill, 99f));
        tv.setPadding(dp(ripple?12:10), dp(ripple?6:5), dp(ripple?12:10), dp(ripple?6:5));
        return tv;
    }

    /** 把颜色朝页面底色拉近，做出参考稿里那种很淡的色块底。 */
    public int soft(int color, float amount) {
        int base = surface();
        float keep = 1 - Math.max(0, Math.min(1, amount));
        int r = Math.round(((color >> 16) & 255) * keep + ((base >> 16) & 255) * amount);
        int g = Math.round(((color >> 8) & 255) * keep + ((base >> 8) & 255) * amount);
        int b = Math.round((color & 255) * keep + (base & 255) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 右上角的设置按钮：圆角底 + 齿轮。
     *
     * 齿轮是画出来的（{@link GearIcon}），不用字体里的「⚙」—— 那个字符在 ColorOS 上
     * 会走 emoji 字体，颜色和大小都不受控。
     */
    public View settingsButton(int fg, int fill) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(round(fill, 12f));
        box.addView(new GearIcon(ctx, fg), new LinearLayout.LayoutParams(dp(19), dp(19)));
        box.setContentDescription(T.s("设置"));
        return box;
    }

    /** 圆角图标按钮（右上角主题切换那种）。 */
    public TextView iconButton(String glyph, int fg, int fill) {
        TextView tv = new TextView(ctx);
        ThemeStore.excludeFont(tv);
        tv.setText(glyph);
        tv.setTextSize(15);
        tv.setTextColor(fg);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(round(fill, 12f));
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        return tv;
    }

    /** 水平分隔线：只在同一张卡里拆行时才用，卡片之间留白足够，不用线。 */
    public View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(line());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f)));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }
}
