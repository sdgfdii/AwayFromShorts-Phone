package com.afs.guard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small native outline glyphs; independent of the user's chosen text font. */
final class ThemeGlyphView extends View {
    private final String glyph;
    private final int color;
    private final float stroke;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    ThemeGlyphView(Context context,String glyph,int color){super(context);this.glyph=glyph;this.color=color;stroke=ThemeStore.load(context).amap()?1.25f:1.65f;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    private void line(Canvas c,float x,float y,float xx,float yy){c.drawLine(x,y,xx,yy,p);}
    @Override protected void onDraw(Canvas c){
        float size=Math.min(getWidth(),getHeight());c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/28,size/28);
        p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(stroke);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        if(T.s("返回").equals(glyph)){line(c,20,14,7,14);line(c,7,14,13,8);line(c,7,14,13,20);}
        else if(T.s("界面风格").equals(glyph)){c.drawRoundRect(3,4,25,24,3,3,p);line(c,3,10,25,10);line(c,11,10,11,24);}
        else if(T.s("搜索").equals(glyph)){c.drawCircle(12,12,6,p);line(c,16.5f,16.5f,22,22);}
        else if(T.s("主题模式").equals(glyph)){c.drawCircle(14,14,5,p);for(int i=0;i<8;i++){c.save();c.rotate(i*45,14,14);line(c,14,3,14,6);c.restore();}}
        else if(T.s("纯黑模式").equals(glyph)){c.drawRoundRect(5,5,23,20,2,2,p);line(c,10,24,18,24);line(c,14,20,14,24);p.setStyle(Paint.Style.FILL);c.drawRect(8,8,13,17,p);}
        else if(T.s("主题色彩").equals(glyph)){Path q=new Path();q.moveTo(15,4);q.cubicTo(1,2,1,23,12,24);q.cubicTo(20,26,15,18,22,17);q.cubicTo(29,14,24,5,15,4);c.drawPath(q,p);p.setStyle(Paint.Style.FILL);c.drawCircle(9,10,1.6f,p);c.drawCircle(14,7,1.6f,p);c.drawCircle(19,10,1.6f,p);c.drawCircle(8,16,1.6f,p);}
        else if(T.s("底栏").equals(glyph)){Path q=new Path();q.moveTo(4,9);q.lineTo(14,3);q.lineTo(24,9);q.lineTo(14,15);q.close();c.drawPath(q,p);line(c,4,14,14,20);line(c,14,20,24,14);line(c,4,19,14,25);line(c,14,25,24,19);}
        else if(T.s("选项卡动画").equals(glyph)){line(c,3,8,10,8);line(c,5,14,12,14);line(c,3,20,10,20);c.drawCircle(20,14,5,p);}
        else if(T.s("液态玻璃").equals(glyph)){Path q=new Path();q.moveTo(14,3);q.cubicTo(12,8,6,12,6,17);q.cubicTo(6,27,22,27,22,17);q.cubicTo(22,12,16,7,14,3);q.close();c.drawPath(q,p);Path shine=new Path();shine.moveTo(10,15);shine.quadTo(8,20,13,21);c.drawPath(shine,p);}
        else if(T.s("字体").equals(glyph)){line(c,5,23,14,4);line(c,14,4,23,23);line(c,9,16,19,16);}
        else if(T.s("文本缩放").equals(glyph)){line(c,3,5,17,5);line(c,10,5,10,24);line(c,18,12,26,12);line(c,22,12,22,24);}
        else if(T.s("重置").equals(glyph)){c.drawArc(new RectF(6,5,23,22),220,290,false,p);line(c,5,5,5,12);line(c,5,12,12,12);}
        else if(T.s("删除").equals(glyph)){line(c,4,7,24,7);line(c,10,7,10,4);line(c,10,4,18,4);line(c,18,4,18,7);line(c,7,7,8.5f,24);line(c,21,7,19.5f,24);line(c,8.5f,24,19.5f,24);line(c,11,11,11.5f,20);line(c,17,11,16.5f,20);}
        c.restore();
    }
}
