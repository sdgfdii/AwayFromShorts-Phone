package com.afs.guard;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.StateListDrawable;
import android.widget.TextView;

/** Immediate platform states; no animation, click delay, or business ownership. */
final class ActionFeedback {
    private ActionFeedback(){}
    static ColorStateList textColors(int normal){
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
                new int[]{(normal&0x00ffffff)|0x61000000,normal});
    }
    static void bind(TextView view,Ui ui,int fill,float radius,int text){
        // 涟漪风格的所有按钮都是胶囊，和参考稿的「回到桌面」一致。
        if(ui.ripple&&radius>=10)radius=99;
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},ui.round((fill&0x00ffffff)|0x66000000,radius));
        int lift=ui.dark?24:-18;
        int pressed=Color.argb(Color.alpha(fill),channel(Color.red(fill)+lift),channel(Color.green(fill)+lift),channel(Color.blue(fill)+lift));
        states.addState(new int[]{android.R.attr.state_pressed},ui.round(pressed,radius));
        states.addState(new int[]{android.R.attr.state_focused},ui.round(pressed,radius));
        states.addState(new int[]{},ui.round(fill,radius));
        view.setBackground(states);view.setTextColor(textColors(text));view.setFocusable(true);
    }
    private static int channel(int n){return Math.max(0,Math.min(255,n));}
}
