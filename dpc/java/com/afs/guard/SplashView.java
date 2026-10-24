package com.afs.guard;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * 进入 App 的加载动画（冷启动那一层，盖在首页上面）。
 *
 * 图形的语义是三层反向旋转，叠起来才是"还在算"的观感：
 *   外弧  顺时针，弧长随进度长出来；
 *   内弧  逆时针，转得比外圈快、弧更短 —— 速度和长度都错开，两层才不像是同一根线；
 *   中心  一个方块自转 45°，方角转到正位时刚好卡在外弧的圆心。
 *
 * 尺寸和配色全部走 {@link Ui}，深浅两套主题都能直接用。
 *
 * 时长按"最短可用"来定：进度 1200ms 拉满就交棒，淡出 320ms 和首页入场重叠。
 * 再长就变成"等人"了 —— 这个 App 打开本来就很快，动画只是把这段空窗填上。
 */
final class SplashView extends View {

    interface Done {
        void onDone();
    }

    private static final int SPIN_MS = 1100;   // 旋转一整圈
    private static final int LOAD_MS = 1200;    // 进度走满

    private final Ui ui;
    private final android.text.TextPaint p = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF outer = new RectF();
    private final RectF inner = new RectF();

    private ValueAnimator spin;
    private ValueAnimator load;
    private float phase;      // 0..1 循环，驱动旋转
    private float progress;   // 0..1 加载进度
    private Done done;
    private boolean fired;

    SplashView(Context c) {
        super(c);
        ui = new Ui(c);
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    void play(Done cb) {
        done = cb;
        android.util.Log.i(Guard.TAG, "splash: play");

        spin = ValueAnimator.ofFloat(0f, 1f);
        spin.setDuration(SPIN_MS);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                phase = (Float) a.getAnimatedValue();
                invalidate();
            }
        });

        load = ValueAnimator.ofFloat(0f, 1f);
        load.setDuration(LOAD_MS);
        load.setInterpolator(new DecelerateInterpolator(2.2f));
        load.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        load.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                fire();
            }
        });

        // 开场：从 0.86 放大淡入，图形不是"砰"地贴上来
        setScaleX(0.86f);
        setScaleY(0.86f);
        setAlpha(0f);
        animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .setDuration(260).start();

        spin.start();
        load.start();
    }

    private void fire() {
        if (fired) {
            return;
        }
        fired = true;
        invalidate();          // 先把满圈的最后一帧画出来，再交棒
        android.util.Log.i(Guard.TAG, "splash: done -> handOver");
        if (done != null) {
            done.onDone();
        }
    }

    void stop() {
        done = null;
        animate().cancel();
        if (spin != null) {
            spin.cancel();
            spin = null;
        }
        if (load != null) {
            load.cancel();
            load = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        c.drawColor(ui.bg());

        float cx = w / 2f;
        float cy = h / 2f - dp(26);   // 整体略上移，下面留给品牌字
        float R = dp(40);

        // 外圈轨道：极淡的一圈底线，弧看起来才像在轨道上跑而不是浮着
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.2f));
        p.setColor(ui.dark ? 0x22FFFFFF : 0x1A000000);
        p.setAlpha(255);
        c.drawCircle(cx, cy, R, p);

        // 外弧：顺时针，弧长随进度从 46° 长到 278°
        outer.set(cx - R, cy - R, cx + R, cy + R);
        p.setStrokeWidth(dp(3.2f));
        p.setColor(Ui.BLUE);
        c.drawArc(outer, phase * 360f - 90f, 46f + 232f * progress, false, p);

        // 内弧：反向，快 1.6 倍，弧更短
        float r2 = R - dp(13);
        inner.set(cx - r2, cy - r2, cx + r2, cy + r2);
        p.setStrokeWidth(dp(2.4f));
        p.setColor(Ui.TEAL);
        c.drawArc(inner, -phase * 560f, 58f, false, p);

        // 中心方块：跟外弧同向自转，起点 45° —— 转正时正好是个正方
        float s = dp(9);
        c.save();
        c.rotate(phase * 360f + 45f, cx, cy);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.BLUE);
        c.drawRoundRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2,
                dp(2.5f), dp(2.5f), p);
        c.restore();

        // 品牌字：不跟着转（跟着转就糊了），随进度从 35% 亮到满
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(dp(11));
        p.setLetterSpacing(0.32f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(ui.text3());
        p.setAlpha((int) (255 * (0.35f + 0.65f * progress)));
        c.drawText("AWAYFROMSHORTS", cx, cy + R + dp(46), p);
        p.setAlpha(255);   // 复位，免得下一帧别的绘制继承这个透明度
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density + 0.5f;
    }
}
