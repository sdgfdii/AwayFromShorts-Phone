package com.afs.guard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 1.6dp 线性图标：用 Canvas 画路径，不依赖系统字体里的符号字形。
 *
 * 之前界面上的 ⇄ ✎ ⌫ ▣ 这些是直接当文字画的：字形来自各系统自带的符号字体，
 * 粗细、基线、大小在同一行里都对不齐（Android 版本不同还会换字形）。
 * 这里把同一套图形用固定描边画出来，调用方只管给个字形记号，
 * 认得的就走图标，不认得的返回 false 让调用方继续用文字（比如「Tᴛ」「A」这类字母标记）。
 */
final class LineIcon extends View {
    /** 描边固定 1.6dp：不管图标画多大，线重都是同一个数，一排图标才不会有的粗有的细。 */
    private static final float STROKE_DP = 1.6f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final String glyph;
    private int color;

    LineIcon(Context context, String glyph, int color) {
        super(context);
        this.glyph = glyph == null ? "" : glyph;
        this.color = color;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void color(int value) {
        if (color == value) return;
        color = value;
        invalidate();
    }

    /** 这个字形有没有对应的矢量图标。 */
    static boolean known(String glyph) {
        if (glyph == null) return false;
        switch (glyph) {
            case "⇄": case "✎": case "⌫": case "▣": case "⧉": case "◉": case "⚙":
            case "!": case "◷": case "◔": case "◇": case "▤": case "↗": case "■":
            case "◎": case "▦": case "◌": case "✓": case "✕": case "＋": case "−":
            case "›": case "‹": case "●": case "🔊":
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;
        float size = Math.min(w, h);
        canvas.save();
        canvas.translate(getPaddingLeft() + (w - size) / 2f, getPaddingTop() + (h - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        paint.setColor(color);
        // 画布放大到 size/24，把「1.6dp 像素」换算回 24 格里的宽度
        float strokePx = STROKE_DP * getResources().getDisplayMetrics().density;
        paint.setStrokeWidth(strokePx * 24f / size);
        paint.setStyle(Paint.Style.STROKE);
        drawGlyph(canvas);
        canvas.restore();
    }

    private void drawGlyph(Canvas c) {
        path.reset();
        switch (glyph) {
            case "🔊":
                path.moveTo(3,9);path.lineTo(7,9);path.lineTo(12,5);path.lineTo(12,19);path.lineTo(7,15);path.lineTo(3,15);path.close();
                c.drawPath(path,paint);
                rect.set(10,7,19,17);c.drawArc(rect,-55,110,false,paint);
                rect.set(8,3,24,21);c.drawArc(rect,-55,110,false,paint);
                break;
            case "⇄":
                path.moveTo(4, 9); path.lineTo(17, 9);
                path.moveTo(14, 6); path.lineTo(17, 9); path.lineTo(14, 12);
                path.moveTo(20, 15); path.lineTo(7, 15);
                path.moveTo(10, 12); path.lineTo(7, 15); path.lineTo(10, 18);
                c.drawPath(path, paint);
                break;
            case "✎":
                path.moveTo(5, 19); path.lineTo(9, 18); path.lineTo(19, 8);
                path.lineTo(16, 5); path.lineTo(6, 15); path.close();
                path.moveTo(14, 7); path.lineTo(17, 10);
                c.drawPath(path, paint);
                break;
            case "⌫":
                path.moveTo(5, 8); path.lineTo(19, 8);
                path.moveTo(9, 8); path.lineTo(9, 5.5f); path.lineTo(15, 5.5f); path.lineTo(15, 8);
                path.moveTo(7.2f, 8); path.lineTo(8.2f, 19); path.lineTo(15.8f, 19); path.lineTo(16.8f, 8);
                path.moveTo(10.6f, 11.5f); path.lineTo(10.6f, 16);
                path.moveTo(13.4f, 11.5f); path.lineTo(13.4f, 16);
                c.drawPath(path, paint);
                break;
            case "!":
                path.moveTo(12, 4.5f); path.lineTo(20.5f, 19); path.lineTo(3.5f, 19); path.close();
                path.moveTo(12, 9.4f); path.lineTo(12, 14);
                path.moveTo(12, 16.6f); path.lineTo(12, 16.72f);
                c.drawPath(path, paint);
                break;
            case "⧉":
                rect.set(4, 5, 14, 15); c.drawRoundRect(rect, 1.6f, 1.6f, paint);
                rect.set(10, 9, 20, 19); c.drawRoundRect(rect, 1.6f, 1.6f, paint);
                break;
            case "▣":
                rect.set(4, 4, 20, 20); c.drawRoundRect(rect, 2.4f, 2.4f, paint);
                path.moveTo(4, 9); path.lineTo(20, 9);
                c.drawPath(path, paint);
                break;
            case "▤":
                rect.set(4, 5, 20, 19); c.drawRoundRect(rect, 2.2f, 2.2f, paint);
                path.moveTo(4, 10); path.lineTo(20, 10);
                path.moveTo(9, 10); path.lineTo(9, 19);
                c.drawPath(path, paint);
                break;
            case "◉":
                c.drawCircle(12, 12, 8.2f, paint);
                c.drawCircle(12, 12, 2.8f, paint);
                break;
            case "◎":
                c.drawCircle(12, 12, 8.2f, paint);
                c.drawCircle(12, 12, 4.4f, paint);
                break;
            case "●":
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 12, 4.6f, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case "⚙":
                c.drawCircle(12, 12, 3.1f, paint);
                c.drawCircle(12, 12, 7.4f, paint);
                for (int i = 0; i < 6; i++) {
                    double a = Math.PI / 3 * i;
                    float x1 = (float) (12 + Math.cos(a) * 7.4f), y1 = (float) (12 + Math.sin(a) * 7.4f);
                    float x2 = (float) (12 + Math.cos(a) * 9.2f), y2 = (float) (12 + Math.sin(a) * 9.2f);
                    path.moveTo(x1, y1); path.lineTo(x2, y2);
                }
                c.drawPath(path, paint);
                break;
            case "◷":
            case "◔":
                c.drawCircle(12, 12, 8.2f, paint);
                path.moveTo(12, 7.4f); path.lineTo(12, 12); path.lineTo(15.2f, 13.8f);
                c.drawPath(path, paint);
                break;
            case "◇":
                path.moveTo(12, 3.6f); path.lineTo(20.2f, 12); path.lineTo(12, 20.4f); path.lineTo(3.8f, 12); path.close();
                c.drawPath(path, paint);
                break;
            case "↗":
                path.moveTo(7, 17); path.lineTo(17, 7);
                path.moveTo(10.4f, 7); path.lineTo(17, 7); path.lineTo(17, 13.6f);
                c.drawPath(path, paint);
                break;
            case "■":
                paint.setStyle(Paint.Style.FILL);
                rect.set(7.5f, 7.5f, 16.5f, 16.5f); c.drawRoundRect(rect, 2f, 2f, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case "▦":
                rect.set(4, 4, 20, 20); c.drawRoundRect(rect, 2.4f, 2.4f, paint);
                path.moveTo(12, 4); path.lineTo(12, 20);
                path.moveTo(4, 12); path.lineTo(20, 12);
                c.drawPath(path, paint);
                break;
            case "◌":
                c.drawCircle(12, 12, 8.2f, paint);
                break;
            case "✓":
                path.moveTo(5, 12.6f); path.lineTo(9.6f, 17.2f); path.lineTo(19, 7.2f);
                c.drawPath(path, paint);
                break;
            case "✕":
                path.moveTo(6.4f, 6.4f); path.lineTo(17.6f, 17.6f);
                path.moveTo(17.6f, 6.4f); path.lineTo(6.4f, 17.6f);
                c.drawPath(path, paint);
                break;
            case "＋":
                path.moveTo(12, 5); path.lineTo(12, 19);
                path.moveTo(5, 12); path.lineTo(19, 12);
                c.drawPath(path, paint);
                break;
            case "−":
                path.moveTo(5, 12); path.lineTo(19, 12);
                c.drawPath(path, paint);
                break;
            case "›":
                path.moveTo(9.5f, 6.5f); path.lineTo(15, 12); path.lineTo(9.5f, 17.5f);
                c.drawPath(path, paint);
                break;
            case "‹":
                path.moveTo(14.5f, 6.5f); path.lineTo(9, 12); path.lineTo(14.5f, 17.5f);
                c.drawPath(path, paint);
                break;
            default:
                break;
        }
    }
}
