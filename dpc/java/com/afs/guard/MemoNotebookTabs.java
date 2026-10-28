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
    private final View capsule;
    private final Consumer<String> choose;
    private final List<String> ids=new ArrayList<>();
    private String signature="",selected="*";
    private ValueAnimator animation;
    MemoNotebookTabs(Context context,Consumer<String> choose){
        super(context);this.choose=choose;ui=new Ui(context);setFillViewport(true);setHorizontalScrollBarEnabled(false);
        setBackground(ui.round(ui.surface(),20));setClipToOutline(true);
        track=new FrameLayout(context);addView(track,new LayoutParams(-2,ui.dp(48)));
        capsule=new View(context);capsule.setBackground(ui.round((ui.accent()&0xffffff)|0x24000000,16));
        track.addView(capsule,new FrameLayout.LayoutParams(1,ui.dp(40)));
        labels=new LinearLayout(context);labels.setGravity(Gravity.CENTER_VERTICAL);labels.setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4));
        track.addView(labels,new FrameLayout.LayoutParams(-2,-1));
        labels.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol)position(false);});
    }
    void bind(MemoLibrary.Model model,String active){
        StringBuilder key=new StringBuilder();for(MemoLibrary.Book book:model.notebooks)key.append(book.id).append(':').append(book.name).append('\n');
        boolean rebuild=!signature.equals(key.toString())||ids.isEmpty();
        if(rebuild){signature=key.toString();labels.removeAllViews();ids.clear();addTab("*","全部");addTab("","默认笔记本");for(MemoLibrary.Book book:model.notebooks)addTab(book.id,book.name);}
        boolean changed=!selected.equals(active);selected=active;
        for(int i=0;i<ids.size();i++){TextView label=(TextView)labels.getChildAt(i);boolean current=ids.get(i).equals(active);label.setSelected(current);label.setTextColor(current?ui.accent():ui.text2());label.setTypeface(null,current?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);}
        if(rebuild)post(()->position(false));else if(changed)position(true);
    }
    private void addTab(String id,String name){
        TextView label=new TextView(getContext());label.setText(name);label.setTextSize(14);label.setGravity(Gravity.CENTER);label.setSingleLine();
        label.setMaxWidth(ui.dp(200));label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setPadding(ui.dp(18),0,ui.dp(18),0);
        label.setContentDescription("笔记本："+name);label.setFocusable(true);label.setOnClickListener(v->choose.accept(id));
        label.setOnLongClickListener(v->{android.widget.Toast.makeText(getContext(),name,0).show();return true;});
        ids.add(id);labels.addView(label,new LinearLayout.LayoutParams(-2,ui.dp(40)));
    }
    private void position(boolean animate){
        int index=ids.indexOf(selected);if(index<0)return;View target=labels.getChildAt(index);if(target.getWidth()==0)return;
        if(animation!=null)animation.cancel();float from=capsule.getTranslationX(),to=target.getLeft();int old=capsule.getLayoutParams().width,width=target.getWidth();
        if(animate){animation=ValueAnimator.ofFloat(0,1);animation.setDuration(220);animation.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animation.addUpdateListener(value->{float fraction=(float)value.getAnimatedValue();place(from+(to-from)*fraction,(int)(old+(width-old)*fraction));});animation.start();
        }else place(to,width);
        smoothScrollTo(Math.max(0,target.getLeft()-(getWidth()-width)/2),0);
    }
    private void place(float x,int width){capsule.setTranslationX(x);capsule.setTranslationY(ui.dp(4));android.view.ViewGroup.LayoutParams lp=capsule.getLayoutParams();lp.width=width;capsule.setLayoutParams(lp);}
    @Override protected void onDetachedFromWindow(){if(animation!=null)animation.cancel();super.onDetachedFromWindow();}
}
