package com.afs.guard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.text.Editable;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.ReplacementSpan;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import java.util.List;

/** Native inline checkboxes over Markdown. Text, selection, IME and import/export stay native. */
public final class MemoChecklistInput extends EditText {
    private final Ui ui;
    private boolean binding;
    private String before="";
    private int insertedAt,insertedCount,removedCount;
    private Runnable beforeAction,afterAction;
    private int pressed=-1;
    private float downX,downY;
    private static final int ACTION_TOGGLE=0x01021001;
    public MemoChecklistInput(Context context){
        super(context);ui=new Ui(context);
        addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){
                if(!binding){before=s.toString();insertedAt=start;removedCount=count;insertedCount=after;}
            }
            public void onTextChanged(CharSequence s,int start,int old,int count){}
            public void afterTextChanged(Editable text){
                if(binding)return;
                if(removedCount==0&&(insertedCount==1||insertedCount==2)&&insertedAt+insertedCount<=text.length()){
                    String inserted=text.subSequence(insertedAt,insertedAt+insertedCount).toString();
                    MemoChecklist.Change change=MemoChecklist.enter(before,insertedAt,inserted);
                    if(change!=null&&change.body.length()<=MemoStore.MAX_BODY){
                        binding=true;
                        // Replace only around the insertion so composing spans elsewhere survive.
                        replaceDifference(text,change.body);
                        setSelection(Math.min(change.cursor,text.length()));binding=false;
                    }
                }
                decorate();
            }
        });
    }
    public void actions(Runnable before,Runnable after){beforeAction=before;afterAction=after;}
    public void checklist(){apply(MemoChecklist.convert(getText().toString(),getSelectionStart(),getSelectionEnd()));}
    public void toggle(int offset){
        int a=getSelectionStart(),b=getSelectionEnd();
        MemoChecklist.Change change=MemoChecklist.toggle(getText().toString(),offset);
        beginBatchEdit();try{
            apply(new MemoChecklist.Change(change.body,Math.max(0,a)));
            if(a>=0&&b>=0)setSelection(Math.min(a,length()),Math.min(b,length()));
        }finally{endBatchEdit();}
    }
    public void apply(MemoChecklist.Change change){
        if(change.body.equals(getText().toString()))return;
        if(change.body.length()>MemoStore.MAX_BODY){android.widget.Toast.makeText(getContext(),"正文已达到 2 万字上限",0).show();return;}
        if(beforeAction!=null)beforeAction.run();
        binding=true;replaceDifference(getText(),change.body);binding=false;
        setSelection(Math.min(change.cursor,length()));decorate();if(afterAction!=null)afterAction.run();
    }
    private void replaceDifference(Editable text,String body){
        String old=text.toString();int start=0,end=old.length(),newEnd=body.length();
        while(start<end&&start<newEnd&&old.charAt(start)==body.charAt(start))start++;
        while(end>start&&newEnd>start&&old.charAt(end-1)==body.charAt(newEnd-1)){end--;newEnd--;}
        text.replace(start,end,body.substring(start,newEnd));
    }
    private static final class Muted extends ForegroundColorSpan {Muted(int color){super(color);}}
    private static final class Indent extends LeadingMarginSpan.Standard {Indent(int width){super(0,width);}}
    public final class Box extends ReplacementSpan {
        final boolean checked;
        Box(boolean checked){this.checked=checked;}
        @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt fm){return ui.dp(32);}
        @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int y,int bottom,Paint original){
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);float radius=ui.dp(8),cx=x+ui.dp(11),cy=y+(original.ascent()+original.descent())/2;
            paint.setStrokeWidth(ui.dp(1.5f));paint.setColor(checked?ui.accent():ui.text3());
            paint.setStyle(checked?Paint.Style.FILL:Paint.Style.STROKE);canvas.drawCircle(cx,cy,radius,paint);
            if(checked){paint.setColor(ui.onAccent());paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
                Path path=new Path();path.moveTo(cx-radius*.48f,cy);path.lineTo(cx-radius*.12f,cy+radius*.35f);path.lineTo(cx+radius*.53f,cy-radius*.37f);canvas.drawPath(path,paint);}
        }
    }
    public void decorate(){
        Editable text=getText();if(text==null)return;
        for(Box span:text.getSpans(0,text.length(),Box.class))text.removeSpan(span);
        for(Muted span:text.getSpans(0,text.length(),Muted.class))text.removeSpan(span);
        for(Indent span:text.getSpans(0,text.length(),Indent.class))text.removeSpan(span);
        for(MemoChecklist.Item item:MemoChecklist.items(text.toString())){
            text.setSpan(new Box(item.checked),item.marker,item.content,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int paragraphEnd=item.end<text.length()&&text.charAt(item.end)=='\r'?item.end+1:item.end;
            if(paragraphEnd<text.length()&&text.charAt(paragraphEnd)=='\n')paragraphEnd++;
            int indent=Math.round(getPaint().measureText(text,item.start,item.marker));
            text.setSpan(new Indent(ui.dp(32)+indent),item.start,paragraphEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if(item.checked&&item.content<item.end)text.setSpan(new Muted(ui.text3()),item.content,item.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        invalidate();
        MemoTextFormat.decorate(text,ui.text(),ui.text3());
    }
    private int checkboxAt(float eventX,float eventY){
        Layout layout=getLayout();if(layout==null)return -1;
        float x=eventX-getTotalPaddingLeft()+getScrollX(),y=eventY-getTotalPaddingTop()+getScrollY();
        if(y<0||y>layout.getHeight())return -1;
        int line=layout.getLineForVertical((int)y);
        List<MemoChecklist.Item> items=MemoChecklist.items(getText().toString());
        for(MemoChecklist.Item item:items){
            if(layout.getLineForOffset(item.marker)!=line)continue;
            float left=layout.getPrimaryHorizontal(item.marker),right=layout.getPrimaryHorizontal(item.content);
            if(x>=Math.min(left,right)-ui.dp(6)&&x<=Math.max(left,right)+ui.dp(3))return item.marker;
        }
        return -1;
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            pressed=checkboxAt(event.getX(),event.getY());downX=event.getX();downY=event.getY();
            if(pressed>=0)return true;
        }else if(pressed>=0){
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.hypot(event.getX()-downX,event.getY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop()){
                pressed=-1;MotionEvent down=MotionEvent.obtain(event);down.setAction(MotionEvent.ACTION_DOWN);super.onTouchEvent(down);down.recycle();return super.onTouchEvent(event);
            }
            if(event.getActionMasked()==MotionEvent.ACTION_UP){int item=pressed;pressed=-1;toggle(item);performClick();return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)pressed=-1;
            return true;
        }
        return super.onTouchEvent(event);
    }
    @Override public boolean performClick(){return super.performClick();}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);MemoChecklist.Item item=MemoChecklist.at(getText().toString(),getSelectionStart());
        if(item!=null)info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_TOGGLE,item.checked?"恢复当前待办":"完成当前待办"));
    }
    @Override public boolean performAccessibilityAction(int action,Bundle args){
        if(action==ACTION_TOGGLE&&MemoChecklist.at(getText().toString(),getSelectionStart())!=null){toggle(getSelectionStart());return true;}
        return super.performAccessibilityAction(action,args);
    }
}
