package com.afs.guard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * 冻结特效：全部应用都在屏蔽时盖在首页上的那层薄霜。
 *
 * 之前"全屏蔽"只是把页面变黑 —— 信息传到了，但什么感觉都没有；
 * 加这层是想让"现在被冻住"有一点身体感知：边缘一圈冷光在呼吸，
 * 几粒霜屑慢慢往下飘。全都做得很淡（最高 ~15% 透明度），
 * 因为底下还有字要读，喧宾夺主就本末倒置了。
 *
 * 省电：只在可见时跑动画，onVisibilityChanged / onWindowVisibilityChanged
 * 里都会把时钟拨正，从后台回来不会"咻"地补帧。
 */
public class FrostView extends View {

    private static final int N = 42;

    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flake = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] fx = new float[N];
    private final float[] fy = new float[N];
    private final float[] fr = new float[N];   // 霜粒半径（dp）
    private final float[] fs = new float[N];   // 下落速度（屏高比例 / 秒）
    private final float[] fp = new float[N];   // 横摆相位
    private final float[] fa = new float[N];   // 各自的透明度
    private final long t0 = System.currentTimeMillis();
    private long last = t0;
    private final Random rnd = new Random(20261023L);
    private int lastEdgeAlpha = -1;

    public FrostView(Context c) {
        super(c);
        flake.setColor(0xFFD8E8F6);
        for (int i = 0; i < N; i++) {
            fx[i] = rnd.nextFloat();
            fy[i] = rnd.nextFloat();
            fr[i] = 0.8f + rnd.nextFloat() * 2.2f;
            fs[i] = 0.008f + rnd.nextFloat() * 0.014f;
            fp[i] = rnd.nextFloat() * (float) Math.PI * 2f;
            fa[i] = 0.05f + rnd.nextFloat() * 0.13f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        float dt = 0;
        last = now;

        // 边缘冷光：中心透明 → 四角一层淡冰蓝，随呼吸明暗
        float breath = 0.55f;
        int a = (int) (0x14 + 0x0e * breath);
        if (a != lastEdgeAlpha) {
            lastEdgeAlpha = a;
            edge.setShader(new RadialGradient(w / 2f, h / 2f,
                    (float) Math.max(w, h) * 0.72f,
                    0x00000000, (a << 24) | 0xBFD9EE, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, edge);

        float dp = getResources().getDisplayMetrics().density;
        float flakeBoost = 0.7f + 0.3f * breath;
        for (int i = 0; i < N; i++) {
            fy[i] += fs[i] * dt;
            if (fy[i] > 1.05f) {
                fy[i] = -0.05f;
                fx[i] = rnd.nextFloat();
            }
            float x = (fx[i] + 0.02f * (float) Math.sin(fp[i])) * w;
            float y = fy[i] * h;
            flake.setAlpha((int) (fa[i] * 255f * flakeBoost));
            canvas.drawCircle(x, y, fr[i] * dp * 0.5f, flake);
        }
    }

    @Override
    protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        last = System.currentTimeMillis();
        if (vis == VISIBLE) {
            invalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int vis) {
        super.onWindowVisibilityChanged(vis);
        last = System.currentTimeMillis();
        if (vis == VISIBLE) {
            invalidate();
        }
    }
}
