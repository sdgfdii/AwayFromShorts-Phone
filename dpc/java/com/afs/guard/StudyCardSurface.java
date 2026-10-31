package com.afs.guard;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;

/**
 * 记忆卡卡面：和打卡页的卡片同一套卡面规则 —— 同样的 22dp 圆角、1dp 描边、20/24 内边距，
 * 不投影、不扫光；只有类型底色不同（这些底色由 {@link CardPalette#apply} 统一给）。
 */
final class StudyCardSurface extends LinearLayout {
    private final Ui ui;
    StudyCardSurface(Context context){
        super(context);ui=new Ui(context);setOrientation(VERTICAL);
        setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(24));
        setBackground(background(ui,ui.surface(),ui.line()));
        setClipToOutline(true);setMinimumHeight(ui.dp(380));
    }
    static GradientDrawable background(Ui ui,int color,int stroke){GradientDrawable value=ui.round(color,22);value.setStroke(ui.dp(1),stroke);return value;}
}
