package com.afs.guard;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Original artwork with real, accessible buttons placed in image coordinates. */
final class RestReminderCard extends FrameLayout {
    final ImageView artwork;
    private final float[][] targets;

    /**
     * @param confirm true = 图上摆「知道了」热区；false = 一个热区都不摆。
     *                现在调用方恒传 false：弹窗固定 15 秒自动消失，期间关不掉。
     */
    RestReminderCard(Context context, Bitmap bitmap, int slide, String description, boolean confirm, Runnable close) {
        super(context);
        artwork = new ImageView(context);
        artwork.setImageBitmap(bitmap);
        artwork.setScaleType(ImageView.ScaleType.FIT_XY);
        artwork.setContentDescription(description);
        addView(artwork, new LayoutParams(-1, -1));
        if (!confirm) {
            // 自动消失模式：一个热区都不放。图里画的「知道了」只是插画，不需要点。
            targets = new float[0][];
            return;
        }
        switch (slide) {
            case 0: targets = new float[][]{{.11f,.71f,.89f,.92f}}; break;
            case 1: case 3: targets = new float[][]{{.67f,.61f,.94f,.74f}}; break;
            case 2: targets = new float[][]{
                {.09f,.22f,.26f,.26f},{.41f,.22f,.58f,.26f},{.75f,.20f,.92f,.25f},
                {.09f,.55f,.26f,.59f},{.41f,.55f,.58f,.59f},{.75f,.54f,.92f,.58f},
                {.09f,.86f,.26f,.90f},{.40f,.86f,.59f,.91f},{.74f,.86f,.92f,.90f}}; break;
            case 4: targets = new float[][]{{.19f,.54f,.75f,.79f}}; break;
            case 5: targets = new float[][]{{.72f,.88f,.98f,.98f}}; break;
            default: targets = new float[][]{{.06f,.66f,.94f,.89f}};
        }
        for (float[] target : targets) {
            Button button = new Button(context);
            button.setTag(target);
            button.setContentDescription(T.s("知道了"));
            button.setMinWidth(0);
            button.setMinHeight(0);
            button.setMinimumWidth(0);
            button.setMinimumHeight(0);
            button.setPadding(0, 0, 0, 0);
            button.setStateListAnimator(null);
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(100);
            android.graphics.drawable.Drawable background = null;
            if (slide == 5) {
                GradientDrawable comic = new GradientDrawable();
                comic.setColor(0xfffff6dd);
                comic.setStroke(dp(2), Color.BLACK);
                comic.setCornerRadius(dp(12));
                background = comic;
                button.setText(T.s("知道了"));
                button.setTextSize(16);
                button.setTextColor(Color.BLACK);
                button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            }
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), background, mask));
            button.setOnClickListener(v -> close.run());
            addView(button, new LayoutParams(1, 1));
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left, height = bottom - top;
        artwork.layout(0, 0, width, height);
        for (int i = 1; i < getChildCount(); i++) {
            View button = getChildAt(i);
            float[] rect = (float[]) button.getTag();
            int w = Math.min(width, Math.max(dp(48), Math.round((rect[2] - rect[0]) * width)));
            int h = Math.min(height, Math.max(dp(48), Math.round((rect[3] - rect[1]) * height)));
            int x = Math.max(0, Math.min(width - w, Math.round((rect[0] + rect[2]) * width / 2 - w / 2f)));
            int y = Math.max(0, Math.min(height - h, Math.round((rect[1] + rect[3]) * height / 2 - h / 2f)));
            button.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            button.layout(x, y, x + w, y + h);
        }
    }
}
