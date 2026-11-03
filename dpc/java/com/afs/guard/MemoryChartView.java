package com.afs.guard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * 统计曲线：柱 + 可选折线叠加，一套画法覆盖「每日复习量 + 当日记住率」「到期预测」「留存曲线」。
 *
 * 只用 Canvas 画，不引第三方库：整个应用零依赖，图表的量级也撑不起一个库。
 * 主题色一律取自 {@link Ui}，深色/浅色自动跟着走；柱和线都从 0 起算，
 * 不截断 y 轴 —— 截断会把 92% 和 96% 画成天壤之别，那是骗人的画法。
 */
final class MemoryChartView extends View {
    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private double[] bars = new double[0];
    private double[] line = null;       // 0..1，画成百分比折线
    private String[] labels = new String[0];
    private String unit = "";

    MemoryChartView(Context c) {
        super(c);
        ui = new Ui(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** @param line 可为 null；给了就按 0–1 的比例画成一条折线（右侧不标数值，靠点本身看趋势）。 */
    void render(double[] bars, double[] line, String[] labels, String unit) {
        this.bars = bars == null ? new double[0] : bars;
        this.line = line;
        this.labels = labels == null ? new String[0] : labels;
        this.unit = unit == null ? "" : unit;
        setContentDescription(describe());
        invalidate();
    }

    private String describe() {
        StringBuilder s = new StringBuilder();
        double max = 0;
        for (double v : bars) max = Math.max(max, v);
        s.append(T.s("图表，共 ")).append(bars.length).append(T.s(" 项，最高 ")).append(fmt(max)).append(unit);
        if (line != null) s.append(T.s("，含趋势线"));
        return s.toString();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = getSuggestedMinimumHeight();
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, Math.max(h, (int) dp(132)));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float labelH = dp(18), top = dp(14), left = dp(2), right = w - dp(2);
        float plotBottom = h - labelH, plotTop = top;
        float plotH = Math.max(1, plotBottom - plotTop);
        int n = bars.length;
        if (n == 0) return;

        double max = 0;
        for (double v : bars) max = Math.max(max, v);
        if (max <= 0) max = 1;

        // 三条基准线：0 / 50% / 100%，只画线不标刻度，读数靠柱顶文字和左侧标注。
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(ui.line());
        for (int i = 0; i <= 2; i++) {
            float y = plotBottom - plotH * i / 2f;
            canvas.drawLine(left, y, right, y, paint);
        }
        // 标尺放在绘图区上方的留白里：画在区内会被最高的那根柱子整个盖住。
        paint.setTextSize(dp(10));
        paint.setColor(ui.text3());
        paint.setStyle(Paint.Style.FILL);
        canvas.drawText(fmt(max) + unit, left + dp(2), Math.max(dp(11), plotTop - dp(4)), paint);

        float slot = (right - left) / n;
        float barW = Math.min(slot * 0.56f, dp(16));
        paint.setColor(ui.accent());
        for (int i = 0; i < n; i++) {
            float bh = (float) (plotH * Math.max(0, bars[i]) / max);
            if (bars[i] > 0) bh = Math.max(bh, dp(2));
            float cx = left + slot * i + slot / 2f;
            rect.set(cx - barW / 2f, plotBottom - bh, cx + barW / 2f, plotBottom);
            canvas.drawRoundRect(rect, dp(3), dp(3), paint);
        }

        if (line != null) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ui.red());
            float px = Float.NaN, py = Float.NaN;
            for (int i = 0; i < Math.min(line.length, n); i++) {
                if (line[i] < 0) { px = Float.NaN; continue; }   // 负值 = 这格没数据，断开线
                float cx = left + slot * i + slot / 2f;
                float cy = plotBottom - (float) (plotH * Math.min(1, Math.max(0, line[i])));
                if (!Float.isNaN(px)) canvas.drawLine(px, py, cx, cy, paint);
                px = cx; py = cy;
            }
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < Math.min(line.length, n); i++) {
                if (line[i] < 0) continue;
                float cx = left + slot * i + slot / 2f;
                float cy = plotBottom - (float) (plotH * Math.min(1, Math.max(0, line[i])));
                canvas.drawCircle(cx, cy, dp(2.4f), paint);
            }
        }

        // x 轴标签：最多 7 个，宁少不多 —— 14 天全挤上去等于没标签。
        // 末尾那个必须画（那是「今天」），所以先给它留好位置，前面会撞上去的一律让掉。
        paint.setColor(ui.text3());
        paint.setTextSize(dp(10));
        int step = Math.max(1, (int) Math.ceil(n / 7.0));
        int lastIdx = n - 1;
        String lastText = lastIdx < labels.length && labels[lastIdx] != null ? labels[lastIdx] : "";
        float lastW = paint.measureText(lastText);
        float lastX = Math.max(left, Math.min(right - lastW, left + slot * lastIdx + slot / 2f - lastW / 2f));
        for (int i = 0; i < lastIdx; i++) {
            if (i % step != 0) continue;
            String label = i < labels.length && labels[i] != null ? labels[i] : "";
            float tw = paint.measureText(label);
            float cx = left + slot * i + slot / 2f;
            float x = Math.max(left, Math.min(right - tw, cx - tw / 2f));
            if (x + tw + dp(6) > lastX) continue;          // 会和「今天」撞
            canvas.drawText(label, x, h - dp(4), paint);
        }
        if (!lastText.isEmpty()) canvas.drawText(lastText, lastX, h - dp(4), paint);
    }

    private static String fmt(double v) {
        if (v >= 100) return String.format(Locale.CHINA, "%.0f", v);
        if (v == Math.floor(v)) return String.format(Locale.CHINA, "%.0f", v);
        return String.format(Locale.CHINA, "%.1f", v);
    }

    private float dp(float value) { return ui.dp(value); }
}
