package com.afs.guard;

import android.content.Context;
import android.graphics.*;
import android.widget.TextView;

/** Visible vector icon, semantic TextView label retained for accessibility and automation. */
public final class MemoToolButton extends TextView {
    private final Ui ui;private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float iconDp=25;private Integer iconColor;
    public MemoToolButton(Context c,String label){super(c);ui=new Ui(c);setText(label);setGravity(android.view.Gravity.CENTER);setMinHeight(ui.dp(48));setContentDescription(label);
        setOnLongClickListener(v->{android.widget.Toast.makeText(c,getText(),0).show();return true;});setFocusable(true);
        android.util.TypedValue value=new android.util.TypedValue();if(c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,value,true))setBackgroundResource(value.resourceId);
    }
    private void line(Canvas c,float a,float b,float x,float y){c.drawLine(a,b,x,y,ink);}
    public void setIconSize(float dp){iconDp=dp;invalidate();}
    public void setIconColor(int color){iconColor=color;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        canvas.save();float size=ui.dp(iconDp);canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/28,size/28);
        ink.setColor(iconColor==null?ui.accent():iconColor);ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(ui.amap?1.25f:1.65f);ink.setStrokeCap(Paint.Cap.ROUND);ink.setStrokeJoin(Paint.Join.ROUND);
        String label=getText().toString();
        if(T.s("撤销").equals(label)||T.s("重做").equals(label)){if(T.s("重做").equals(label)){canvas.translate(28,0);canvas.scale(-1,1);}Path p=new Path();p.moveTo(5,11);p.cubicTo(18,3,26,12,22,22);canvas.drawPath(p,ink);line(canvas,5,11,5,4);line(canvas,5,11,12,11);}
        else if("Markdown".equals(label)){canvas.drawRoundRect(2,5,26,23,3,3,ink);line(canvas,6,19,6,10);line(canvas,6,10,10,15);line(canvas,10,15,14,10);line(canvas,14,10,14,19);line(canvas,21,10,21,19);line(canvas,18,16,21,19);line(canvas,21,19,24,16);}
        else if(T.s("标题").equals(label)||T.s("副标题").equals(label)||T.s("小标题").equals(label)){line(canvas,4,6,4,22);line(canvas,15,6,15,22);line(canvas,4,14,15,14);ink.setStyle(Paint.Style.FILL);ink.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));ink.setTextSize(11);canvas.drawText(T.s("标题").equals(label)?"1":T.s("副标题").equals(label)?"2":"3",18,22,ink);}
        else if(T.s("正文").equals(label)){line(canvas,4,6,24,6);line(canvas,14,6,14,23);line(canvas,9,23,19,23);}
        else if(T.s("注释").equals(label)){line(canvas,5,5,5,23);for(int i=0;i<3;i++)line(canvas,11,8+i*6,i==2?20:25,8+i*6);}
        else if(T.s("圆点符号").equals(label)||T.s("数字符号").equals(label)){for(int i=0;i<3;i++){float y=7+i*7;ink.setStyle(Paint.Style.STROKE);line(canvas,11,y,25,y);if(T.s("圆点符号").equals(label)){ink.setStyle(Paint.Style.FILL);canvas.drawCircle(5,y,1.6f,ink);}else{ink.setStyle(Paint.Style.FILL);ink.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));ink.setTextSize(8);canvas.drawText(String.valueOf(i+1),3,y+3,ink);}}}
        else if(T.s("待办事项").equals(label)){canvas.drawRoundRect(3,4,25,24,3,3,ink);line(canvas,7,14,12,19);line(canvas,12,19,21,9);}
        else if(T.s("增加缩进").equals(label)||T.s("减少缩进").equals(label)){for(int i=0;i<3;i++)line(canvas,13,7+i*7,25,7+i*7);boolean more=T.s("增加缩进").equals(label);float a=more?3:9,b=more?9:3;line(canvas,a,9,b,14);line(canvas,b,14,a,19);}
        else if(T.s("待办").equals(label)){canvas.drawCircle(14,14,10,ink);line(canvas,8,14,12,18);line(canvas,12,18,20,9);}
        else if(T.s("管理").equals(label)){for(int i=0;i<3;i++){float y=7+i*7;line(canvas,4,y,24,y);canvas.drawCircle(i%2==0?10:19,y,2.5f,ink);}}
        else if(T.s("预览").equals(label)){Path p=new Path();p.moveTo(2,14);p.cubicTo(8,3,20,3,26,14);p.cubicTo(20,25,8,25,2,14);canvas.drawPath(p,ink);canvas.drawCircle(14,14,4,ink);}
        else if(T.s("编辑").equals(label)){Path p=new Path();p.moveTo(5,19);p.lineTo(4,24);p.lineTo(9,23);p.lineTo(24,8);p.lineTo(20,4);p.close();canvas.drawPath(p,ink);line(canvas,17,7,21,11);}
        else if(T.s("完成").equals(label)){line(canvas,6,14,12,20);line(canvas,12,20,23,7);}
        else if(T.s("删除").equals(label)){line(canvas,5,7,23,7);line(canvas,10,7,10,4);line(canvas,10,4,18,4);line(canvas,18,4,18,7);line(canvas,8,7,9,24);line(canvas,20,7,19,24);line(canvas,9,24,19,24);line(canvas,12,11,12,20);line(canvas,16,11,16,20);}
        else if(T.s("置顶").equals(label)||T.s("取消置顶").equals(label)){Path p=new Path();p.moveTo(9,5);p.lineTo(19,5);p.lineTo(18,12);p.lineTo(22,17);p.lineTo(6,17);p.lineTo(10,12);p.close();canvas.drawPath(p,ink);line(canvas,14,17,14,25);if(T.s("取消置顶").equals(label))line(canvas,4,3,25,25);}
        else{ink.setStyle(Paint.Style.FILL);for(int i=0;i<3;i++)canvas.drawCircle(7+i*7,14,1.8f,ink);}
        canvas.restore();
    }
}
