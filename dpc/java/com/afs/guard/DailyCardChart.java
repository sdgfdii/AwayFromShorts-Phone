package com.afs.guard;
import android.content.Context;
import android.graphics.*;
import android.view.View;
final class DailyCardChart extends View {
    static final String[] NAMES={"目标","禁忌","记忆","刷题","热爱","总结"};
    static final int[] COLORS={0xFF36A66C,0xFF17191F,0xFF478FE8,0xFFE49C39,0xFFAD71D9,0xFF2AADA3};
    private final int[] counts;private final String date;private final Ui ui;private final Paint paint=new Paint(3);
    DailyCardChart(Context c,String date,int[] counts){super(c);ui=new Ui(c);this.date=date;this.counts=counts.clone();StringBuilder desc=new StringBuilder(date);for(int i=0;i<counts.length;i++)desc.append("，").append(NAMES[i]).append(counts[i]);setContentDescription(desc);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float cx=getWidth()/2f,r=Math.min(getWidth()*.37f,ui.dp(39)),cy=ui.dp(68);int total=0;for(int count:counts)total+=count;
        paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(ui.dp(12));paint.setColor(ui.text2());canvas.drawText(date.substring(5).replace('-','/'),cx,ui.dp(19),paint);
        RectF circle=new RectF(cx-r,cy-r,cx+r,cy+r);float start=-90;
        if(total==0){paint.setColor(ui.surface2());canvas.drawOval(circle,paint);}else for(int i=0;i<counts.length;i++){if(counts[i]==0)continue;float sweep=360f*counts[i]/total;paint.setColor(COLORS[i]);canvas.drawArc(circle,start,sweep,true,paint);start+=sweep;}
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(ui.dp(1));paint.setColor(ui.line());canvas.drawOval(circle,paint);paint.setStyle(Paint.Style.FILL);
        paint.setColor(ui.text());paint.setTextSize(ui.dp(12));canvas.drawText(total==0?"暂无记录":total+" 项",cx,ui.dp(127),paint);
    }
}
