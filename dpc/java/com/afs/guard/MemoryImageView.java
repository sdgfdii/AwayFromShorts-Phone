package com.afs.guard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;

/** Image-fit coordinates are normalized, so masks survive screen size/orientation changes. */
public final class MemoryImageView extends View {
    private final Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private JSONArray masks;
    private final boolean editing;
    private boolean revealed, dragging;
    private float startX, startY, endX, endY;
    public MemoryImageView(Context c, String name, JSONArray areas, boolean edit) {
        super(c); editing = edit; masks = areas;
        bitmap = BitmapFactory.decodeFile(MemoryStore.media(c, name).getAbsolutePath());
        setContentDescription(edit ? "图片遮挡编辑器，拖动框选需要隐藏的标注" : "知识图片，显示答案后揭开标注");
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }
    public JSONArray masks() { return masks; }
    public void reveal() { revealed = true; invalidate(); }
    public void undo() {
        JSONArray keep = new JSONArray(); for (int i = 0; i < masks.length() - 1; i++) keep.put(masks.opt(i));
        masks = keep; invalidate();
    }
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null) { paint.setColor(0xFFFF7777); paint.setTextSize(36); c.drawText("图片无法读取，请重新选择", 8, 60, paint); return; }
        float scale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
        bounds.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        paint.setAlpha(255); c.drawBitmap(bitmap, null, bounds, paint);
        if (revealed) return;
        paint.setColor(editing ? 0xDD2F6FED : 0xFF2F6FED);
        for (int i = 0; i < masks.length(); i++) {
            JSONArray r = masks.optJSONArray(i); if (r == null) continue;
            float x = bounds.left + (float) r.optDouble(0) * bounds.width();
            float y = bounds.top + (float) r.optDouble(1) * bounds.height();
            c.drawRect(x, y, x + (float) r.optDouble(2) * bounds.width(), y + (float) r.optDouble(3) * bounds.height(), paint);
        }
        if (dragging) c.drawRect(Math.min(startX, endX), Math.min(startY, endY), Math.max(startX, endX), Math.max(startY, endY), paint);
    }
    public boolean onTouchEvent(MotionEvent e) {
        if (!editing || bitmap == null) return false;
        float x = Math.max(bounds.left, Math.min(bounds.right, e.getX()));
        float y = Math.max(bounds.top, Math.min(bounds.bottom, e.getY()));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!bounds.contains(e.getX(), e.getY())) return false;
                startX = endX = x; startY = endY = y; dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true); invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                endX = x; endY = y; invalidate(); return true;
            case MotionEvent.ACTION_UP:
                endX = x; endY = y;
                if (dragging && Math.abs(endX - startX) > 6 && Math.abs(endY - startY) > 6 && masks.length() < 100) {
                    JSONArray r = new JSONArray();
                    r.put((Object) Double.valueOf((Math.min(startX, endX) - bounds.left) / bounds.width()));
                    r.put((Object) Double.valueOf((Math.min(startY, endY) - bounds.top) / bounds.height()));
                    r.put((Object) Double.valueOf(Math.abs(endX - startX) / bounds.width()));
                    r.put((Object) Double.valueOf(Math.abs(endY - startY) / bounds.height())); masks.put(r);
                }
                dragging = false; getParent().requestDisallowInterceptTouchEvent(false); invalidate(); performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false; getParent().requestDisallowInterceptTouchEvent(false); invalidate(); return true;
            default: return true;
        }
    }
    public boolean performClick() { super.performClick(); return true; }
    protected void onDetachedFromWindow() { super.onDetachedFromWindow(); if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
}
