package com.afs.guard;
import android.app.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.List;
/** One finite confirmation. Text remains complete with motion disabled. */
final class AchievementCelebration {
    static AlertDialog show(Activity host,List<Achievement.Item> fresh){
        if(fresh.isEmpty()||host.isFinishing()||host.isDestroyed())return null;
        Ui ui=new Ui(host);LinearLayout content=new LinearLayout(host);content.setOrientation(1);content.setPadding(ui.dp(24),ui.dp(20),ui.dp(24),ui.dp(12));
        AchievementMark badge=new AchievementMark(host,ui);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(ui.dp(64),ui.dp(64));bp.gravity=Gravity.CENTER_HORIZONTAL;bp.bottomMargin=ui.dp(20);content.addView(badge,bp);
        badge.setContentDescription(T.s("成就已完成"));
        StringBuilder detail=new StringBuilder();for(Achievement.Item item:fresh){if(detail.length()>0)detail.append("\n\n");detail.append(item.glyph).append(' ').append(item.name).append('\n').append(item.note);}
        TextView text=new TextView(host);text.setText(detail);text.setTextSize(16);text.setTextColor(ui.text());text.setLineSpacing(ui.dp(4),1);
        ScrollView scroll=new ScrollView(host);scroll.addView(text);content.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=ThemeStore.dialog(host).setTitle(fresh.size()==1?T.s("解锁成就：")+fresh.get(0).name:T.s("解锁了 ")+fresh.size()+T.s(" 个成就")).setView(content).setPositiveButton(T.s("收下"),null).create();
        Runnable settle=()->{badge.finishDrawing();content.animate().withEndAction(null).cancel();badge.animate().cancel();content.setAlpha(1);content.setTranslationY(0);badge.setAlpha(1);badge.setScaleX(1);badge.setScaleY(1);};
        Runnable policy=()->{if(!MotionPolicy.enabled(host))settle.run();};
        Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}
            public void onActivityPaused(Activity a){if(a==host)dialog.dismiss();}public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){if(a==host)dialog.dismiss();}
        };
        dialog.setOnDismissListener(d->{settle.run();MotionPolicy.remove(policy);host.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);});
        dialog.setOnShowListener(d->{
            dialog.getWindow().setWindowAnimations(0);MotionPolicy.add(host,policy);host.getApplication().registerActivityLifecycleCallbacks(lifecycle);
            if(MotionPolicy.enabled(host)){
                badge.play();
                content.setAlpha(0);content.setTranslationY(ui.dp(8));badge.setScaleX(.9f);badge.setScaleY(.9f);
                content.animate().alpha(1).translationY(0).setDuration(MotionPolicy.duration(host,Motion.ENTER)).setInterpolator(Motion.OUT).start();
                badge.animate().scaleX(1).scaleY(1).setDuration(MotionPolicy.duration(host,Motion.ENTER)).setInterpolator(Motion.OUT_SOFT).start();
            }else settle.run();
            dialog.getButton(-1).setOnClickListener(v->{
                settle.run();if(!MotionPolicy.enabled(host)){dialog.dismiss();return;}
                content.animate().alpha(0).translationY(ui.dp(4)).setDuration(MotionPolicy.duration(host,Motion.EXIT)).setInterpolator(Motion.IN).withEndAction(dialog::dismiss).start();
            });
        });dialog.show();return dialog;
    }
}
