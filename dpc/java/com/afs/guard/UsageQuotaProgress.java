package com.afs.guard;

import android.content.Context;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.ProgressBar;

/** Read-only quota gauge; all enforcement and counting stay in their existing stores. */
final class UsageQuotaProgress extends ProgressBar {
    private final Ui ui;
    private final GradientDrawable fill;
    UsageQuotaProgress(Context c){
        super(c,null,android.R.attr.progressBarStyleHorizontal);ui=new Ui(c);setMax(1000);
        fill=ui.round(ui.accent(),6);
        LayerDrawable layers=new LayerDrawable(new android.graphics.drawable.Drawable[]{ui.round(ui.surface2(),6),new ClipDrawable(fill,Gravity.LEFT,ClipDrawable.HORIZONTAL)});
        layers.setId(0,android.R.id.background);layers.setId(1,android.R.id.progress);setProgressDrawable(layers);
        setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.red()));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    void bind(long used,long limit,boolean readable,boolean enabled){
        boolean known=limit>0&&(readable||!enabled);
        setIndeterminate(!known&&enabled);
        int progress=known?(int)Math.min(1000,Math.max(0,used)*1000.0/limit):0;
        if(!isIndeterminate())setProgress(progress);
        // 暂不计时的条目用中性灰，别让"还没开始算"看起来像"已经用爆"。
        int color=!enabled||!known?ui.text3():used>=limit?ui.red():ui.accent();
        fill.setColor(color);setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        if(!known)setIndeterminateTintList(android.content.res.ColorStateList.valueOf(color));
        setAlpha(enabled?(!known?.55f:1f):.5f);
        setContentDescription(!enabled?T.s("使用限额已暂停"):!known?T.s("未授权或用量暂不可读，暂不计时"):String.format(java.util.Locale.CHINA,T.s("已用 %.0f%%，剩余 %.1f 分钟"),Math.min(100,Math.max(0,used)*100.0/limit),Math.max(0,limit-used)/60000.0));
    }
}
