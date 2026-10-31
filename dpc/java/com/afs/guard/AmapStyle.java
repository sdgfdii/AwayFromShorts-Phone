package com.afs.guard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Independently drawn visual language observed in Amap 17.02; no map SDK or brand assets. */
final class AmapStyle {
    private AmapStyle(){}
    static Drawable page(Ui ui){return new Page(ui);}
    private static final class Page extends Drawable {
        private final Ui ui;private final Paint p=new Paint();private LinearGradient gradient;private int width,height;
        Page(Ui ui){this.ui=ui;}
        @Override public void draw(Canvas c){
            c.drawColor(ui.bg());
            if(width!=getBounds().width()||height!=getBounds().height()||gradient==null){width=getBounds().width();height=getBounds().height();gradient=new LinearGradient(0,0,0,ui.dp(220),ui.dark?0xFF173047:0xFFDFF3FF,ui.bg(),Shader.TileMode.CLAMP);}
            p.setShader(gradient);c.drawRect(getBounds(),p);
        }
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.OPAQUE;}
    }
    static final class Icon extends View {
        private final String kind;private final int color;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context c,String kind,int color){super(c);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){
            float size=Math.min(getWidth(),getHeight());c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/28,size/28);
            glyph(c,p,kind,color,false);c.restore();
        }
    }
    static void glyph(Canvas c,Paint p,String kind,int color,boolean selected){
        p.setShader(null);p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.25f);p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeCap(Paint.Cap.ROUND);
        int inside=(((color>>16)&255)*.2126+((color>>8)&255)*.7152+(color&255)*.0722)>180?0xFF101418:0xFFFFFFFF;
        if(T.s("守卫").equals(kind)){
            Path q=new Path();q.moveTo(14,3);q.lineTo(24,7);q.lineTo(23,16);q.quadTo(21,23,14,26);q.quadTo(7,23,5,16);q.lineTo(4,7);q.close();
            if(selected)p.setStyle(Paint.Style.FILL);c.drawPath(q,p);p.setStyle(Paint.Style.STROKE);if(selected)p.setColor(inside);c.drawLine(9,14,13,18,p);c.drawLine(13,18,20,10,p);
        }else if(T.s("记忆").equals(kind)){
            c.drawRoundRect(4,3,21,24,3,3,p);c.drawLine(8,3,8,24,p);if(selected){p.setStyle(Paint.Style.FILL);c.drawRoundRect(9,4,20,23,2,2,p);p.setStyle(Paint.Style.STROKE);p.setColor(inside);}c.drawLine(12,9,17,9,p);c.drawLine(12,14,17,14,p);
        }else if(T.s("目标管理").equals(kind)||T.s("目标列表").equals(kind)||T.s("学习目标").equals(kind)){
            c.drawCircle(13,15,10,p);c.drawCircle(13,15,5,p);c.drawLine(13,15,24,4,p);c.drawLine(19,4,24,4,p);c.drawLine(24,4,24,9,p);
        }else if(T.s("功能").equals(kind)){
            for(int i=0;i<4;i++){float x=4+i%2*12,y=4+i/2*12;c.drawRoundRect(x,y,x+8,y+8,2,2,p);}
        }else if(T.s("刷题").equals(kind)){
            c.drawRoundRect(5,3,23,25,3,3,p);if(selected){p.setStyle(Paint.Style.FILL);c.drawRoundRect(5,3,23,25,3,3,p);p.setStyle(Paint.Style.STROKE);p.setColor(inside);}c.drawLine(9,9,18,9,p);c.drawLine(9,14,15,14,p);c.drawLine(11,20,14,22,p);c.drawLine(14,22,20,17,p);
        }else if(T.s("冻结").equals(kind)){
            for(int i=0;i<6;i++){c.save();c.rotate(i*60,14,14);c.drawLine(14,3,14,14,p);c.drawLine(10,6,14,9,p);c.drawLine(18,6,14,9,p);c.restore();}
        }else if(T.s("打卡历史").equals(kind)||T.s("历史").equals(kind)){
            c.drawCircle(14,14,10,p);c.drawLine(14,8,14,14,p);c.drawLine(14,14,20,17,p);
        }else if(T.s("备忘录").equals(kind)){
            // 便签：纸 + 右上折角 + 两条横线，和底栏的线条语言一致
            Path note=new Path();note.moveTo(5,3);note.lineTo(18,3);note.lineTo(24,9);note.lineTo(24,25);note.lineTo(5,25);note.close();c.drawPath(note,p);
            c.drawLine(18,3,18,9,p);c.drawLine(18,9,24,9,p);c.drawLine(9,14,20,14,p);c.drawLine(9,19,17,19,p);
        }else if(T.s("工具箱").equals(kind)){
            // 工具箱：提手 + 箱体 + 箱盖线 + 锁扣
            c.drawRoundRect(9,4,19,10,2,2,p);
            c.drawRoundRect(3,9,25,25,2,2,p);
            c.drawLine(3,15,25,15,p);
            c.drawRoundRect(12,15,16,19,1,1,p);
        }else if(T.s("屏幕统计").equals(kind)||T.s("统计").equals(kind)){
            c.drawLine(4,24,24,24,p);c.drawLine(7,19,7,13,p);c.drawLine(14,19,14,5,p);c.drawLine(21,19,21,10,p);
        }else if(T.s("设置").equals(kind)){
            // 齿轮：8 个梯形齿 + 中心孔。几何和 classic 皮肤的 GearIcon 同一套，只是网格是 28。
            Path g=new Path();float cx=14,cy=14,ro=11.2f,rb=7.9f;
            android.graphics.RectF outer=new android.graphics.RectF(cx-ro,cy-ro,cx+ro,cy+ro);
            android.graphics.RectF root=new android.graphics.RectF(cx-rb,cy-rb,cx+rb,cy+rb);
            for(int i=0;i<8;i++){float base=i*45f;g.arcTo(outer,base-11f,22f,i==0);g.arcTo(root,base+11f,23f,false);}
            g.close();g.addCircle(cx,cy,3.2f,Path.Direction.CW);g.setFillType(Path.FillType.EVEN_ODD);
            p.setStyle(Paint.Style.FILL);c.drawPath(g,p);
        }else if(T.s("主题").equals(kind)||T.s("更多设置").equals(kind)){
            c.drawCircle(14,14,6,p);for(int i=0;i<8;i++){c.save();c.rotate(i*45,14,14);c.drawLine(14,3,14,5,p);c.restore();}
        }else{
            c.drawRoundRect(4,5,24,25,4,4,p);c.drawLine(9,3,9,8,p);c.drawLine(19,3,19,8,p);c.drawLine(4,11,24,11,p);
            if(selected){p.setStyle(Paint.Style.FILL);c.drawRoundRect(5,12,23,24,2,2,p);p.setStyle(Paint.Style.STROKE);p.setColor(inside);}c.drawLine(9,18,13,21,p);c.drawLine(13,21,20,15,p);
        }
    }
}
