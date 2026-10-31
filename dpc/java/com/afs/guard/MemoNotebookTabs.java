package com.afs.guard;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Scrollable notebook tabs with a single animated selection capsule. */
final class MemoNotebookTabs extends HorizontalScrollView {
    private final Ui ui;
    private final FrameLayout track;
    private final LinearLayout labels;
    private final Capsule capsule;
    private final Runnable motionChanged=()->{if(!MotionPolicy.enabled(getContext()))position(false);};
    private final Consumer<String> choose;
    private final List<String> ids=new ArrayList<>();
    private String signature="",selected="*";
    private ValueAnimator animation;
    private boolean touching;
    MemoNotebookTabs(Context context,Consumer<String> choose){
        super(context);this.choose=choose;ui=new Ui(context);setFillViewport(true);setHorizontalScrollBarEnabled(false);
        setBackground(ui.round(ui.surface(),20));setClipToOutline(true);
        track=new FrameLayout(context);addView(track,new LayoutParams(-2,ui.dp(48)));
        capsule=new Capsule(context);
        track.addView(capsule,new FrameLayout.LayoutParams(-1,ui.dp(40)));
        labels=new LinearLayout(context);labels.setGravity(Gravity.CENTER_VERTICAL);labels.setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4));
        track.addView(labels,new FrameLayout.LayoutParams(-2,-1));
        addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol)position(false);});
    }
    void bind(MemoLibrary.Model model,String active){
        StringBuilder key=new StringBuilder();for(MemoLibrary.Book book:model.notebooks)key.append(book.id).append(':').append(book.name).append('\n');
        boolean rebuild=!signature.equals(key.toString())||ids.isEmpty();
        if(rebuild){signature=key.toString();labels.removeAllViews();ids.clear();addTab("*",T.s("全部"));addTab("",T.s("默认笔记本"));for(MemoLibrary.Book book:model.notebooks)addTab(book.id,book.name);}
        boolean changed=!selected.equals(active);selected=active;
        for(int i=0;i<ids.size();i++){TextView label=(TextView)labels.getChildAt(i);boolean current=ids.get(i).equals(active);label.setSelected(current);label.setTextColor(current?ui.accent():ui.text2());label.setTypeface(null,current?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);}
        if(rebuild)post(()->position(false));else if(changed)position(true);
    }
    private void addTab(String id,String name){
        TextView label=new TextView(getContext());label.setText(name);label.setTextSize(14);label.setGravity(Gravity.CENTER);label.setSingleLine();
        label.setMaxWidth(ui.dp(200));label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setPadding(ui.dp(18),0,ui.dp(18),0);
        label.setContentDescription(T.s("笔记本：")+name);label.setFocusable(true);label.setOnClickListener(v->choose.accept(id));
        label.setOnLongClickListener(v->{android.widget.Toast.makeText(getContext(),name,0).show();return true;});
        label.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(id.equals(selected)&&(l!=ol||r!=or))position(animation!=null&&animation.isRunning());});
        ids.add(id);labels.addView(label,new LinearLayout.LayoutParams(-2,ui.dp(40)));
    }
    private void position(boolean animate){
        int index=ids.indexOf(selected);if(index<0)return;View target=labels.getChildAt(index);if(target.getWidth()==0)return;
        if(animation!=null){animation.cancel();animation=null;}
        float from=capsule.getTranslationX(),to=target.getLeft(),old=capsule.width;int width=target.getWidth();
        final boolean canScroll=!touching;
        int scrollFrom=getScrollX(),scrollTo=Math.max(0,target.getLeft()-(getWidth()-width)/2);
        if(animate&&MotionPolicy.enabled(getContext())){animation=ValueAnimator.ofFloat(0,1);animation.setDuration(180);animation.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animation.addUpdateListener(value->{float f=(float)value.getAnimatedValue();place(from+(to-from)*f,old+(width-old)*f);if(canScroll&&!touching)scrollTo(Math.round(scrollFrom+(scrollTo-scrollFrom)*f),0);});animation.start();
        }else{place(to,width);if(!touching)scrollTo(scrollTo,0);}
    }
    private void place(float x,float width){capsule.setTranslationX(x);capsule.setTranslationY(ui.dp(4));capsule.width=width;capsule.invalidate();}
    private final class Capsule extends View {
        float width=1;final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF rect=new android.graphics.RectF();
        Capsule(Context c){super(c);paint.setColor((ui.accent()&0xffffff)|0x24000000);}
        @Override protected void onDraw(android.graphics.Canvas c){rect.set(0,0,width,getHeight());c.drawRoundRect(rect,ui.dp(16),ui.dp(16),paint);}
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){touching=true;position(false);}
        boolean handled=super.dispatchTouchEvent(event);
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP||event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)touching=false;
        return handled;
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();MotionPolicy.add(getContext(),motionChanged);post(()->position(false));}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE&&capsule!=null){touching=false;position(false);}}
    @Override protected void onDetachedFromWindow(){MotionPolicy.remove(motionChanged);touching=false;if(animation!=null){animation.cancel();animation=null;}super.onDetachedFromWindow();}
}
