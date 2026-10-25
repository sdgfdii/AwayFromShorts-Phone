package com.afs.guard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.util.Calendar;

/** A local-day 00–24 rail with frozen intervals and a live time cursor. */
final class FreezeTimelineView extends View {
    private final Ui look;
    private final Rule rule;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[][] segments;
    private int cachedDay = -1;
    FreezeTimelineView(Context context, Rule rule) {
        super(context); this.look = new Ui(context); this.rule = rule;
        setContentDescription("24小时冻结时间条，" + rule.freezeSummary());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    private float dp(float v) { return look.dp(v); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Calendar now = Calendar.getInstance();
        int day = now.get(Calendar.YEAR) * 400 + now.get(Calendar.DAY_OF_YEAR);
        if (day != cachedDay) { segments = FreezeSlotStore.frozenSegments(rule, now); cachedDay = day; }
        float left = dp(3), right = getWidth() - dp(3), width = right - left;
        float top = dp(25), bottom = dp(39);
        paint.setColor(look.surface2());
        RectF rail = new RectF(left, top, right, bottom);
        canvas.drawRoundRect(rail, dp(7), dp(7), paint);
        canvas.save();
        android.graphics.Path clip = new android.graphics.Path(); clip.addRoundRect(rail, dp(7), dp(7), android.graphics.Path.Direction.CW); canvas.clipPath(clip);
        paint.setColor(look.accent());
        for (int[] segment : segments) canvas.drawRect(left + width * segment[0] / 1440f, top,
                left + width * segment[1] / 1440f, bottom, paint);
        canvas.restore();
        float minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        float cursor = left + width * minute / 1440f;
        paint.setColor(look.text()); paint.setStrokeWidth(dp(1.5f));
        canvas.drawLine(cursor, top - dp(3), cursor, bottom + dp(4), paint);
        canvas.drawCircle(cursor, top - dp(3), dp(2.5f), paint);
        paint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
        paint.setTextAlign(Paint.Align.CENTER);
        String current = "现在 " + Rule.hm(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        float half = paint.measureText(current) / 2f;
        canvas.drawText(current, Math.max(left + half, Math.min(right - half, cursor)), dp(14), paint);
        paint.setColor(look.text3());
        for (int hour = 0; hour <= 24; hour += 6) {
            float x = left + width * hour / 24f;
            paint.setTextAlign(hour == 0 ? Paint.Align.LEFT : hour == 24 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(String.format(java.util.Locale.US, "%02d", hour), x, dp(57), paint);
        }
    }
}
