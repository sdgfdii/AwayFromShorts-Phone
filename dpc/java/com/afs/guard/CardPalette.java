package com.afs.guard;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
/** Single palette shared by check-in, memory, management and history. */
final class CardPalette {
    static final int GOAL=0,TABOO=1,MEMORY=2,QUIZ=3,LOVE=4,SUMMARY=5;
    static final int[] COLORS={0xFFFFFFFF,0xFF15171C,0xFFF6DE7B,0xFF80B8F5,0xFFF5B6D0,0xFFE85B61};
    static int ink(int type){return type==TABOO?0xFFF5F5F7:0xFF20232A;}
    static void textColors(View view,int type){if(view instanceof TextView)((TextView)view).setTextColor(ink(type));if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)textColors(((ViewGroup)view).getChildAt(i),type);}
    static void apply(View view,int type,boolean done){Ui ui=new Ui(view.getContext());view.setBackground(StudyCardSurface.background(ui,COLORS[type],done?ui.green():type==GOAL?0xFFBEC2CA:COLORS[type]));textColors(view,type);}
}
