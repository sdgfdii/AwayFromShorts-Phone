package com.afs.guard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 齿轮图标。
 *
 * <p>为什么不直接写「⚙」：那个字符在 ColorOS 上会掉进 emoji 字体，被渲染成一张彩色小图，
 * 颜色由系统说了不算（深色主题下还会发灰发脏），同一行里和别的线性图标完全不是一个语言。
 * 这里用 Path 画：8 个梯形齿 + 中心孔，尺寸按 24×24 的网格设计，缩到 20dp 依然干净。
 */
final class GearIcon extends View {

    private static final int TEETH = 8;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    GearIcon(Context c, int color) {
        super(c);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setColor(int value) {
        if (paint.getColor() == value) return;
        paint.setColor(value);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;

        // 全部尺寸按 24×24 的设计网格算，再按实际边长缩放
        float unit = size / 24f;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float outer = 10.4f * unit;     // 齿顶圆
        float root = 7.3f * unit;       // 齿根圆
        float hole = 3.0f * unit;       // 中心孔

        RectF toothArc = new RectF(cx - outer, cy - outer, cx + outer, cy + outer);
        RectF gapArc = new RectF(cx - root, cy - root, cx + root, cy + root);

        float step = 360f / TEETH;
        float half = 11f;               // 单个齿占 ±11°，剩下的是齿间缺口

        path.reset();
        for (int i = 0; i < TEETH; i++) {
            float base = i * step;
            // arcTo 会自动把上一段终点和这一段起点连成直线，正好是齿的侧壁
            path.arcTo(toothArc, base - half, half * 2f, i == 0);
            path.arcTo(gapArc, base + half, step - half * 2f, false);
        }
        path.close();
        // 中心孔：同一个 Path 加个圆，EVEN_ODD 自然挖空
        path.addCircle(cx, cy, hole, Path.Direction.CW);
        path.setFillType(Path.FillType.EVEN_ODD);
        canvas.drawPath(path, paint);
    }
}
