package com.afs.guard;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;

/** Save scroll offsets independently of generated view IDs; restore before the first visible frame. */
final class PageScrollState {
    private PageScrollState(){}
    static Bundle capture(Activity activity){Bundle out=new Bundle();View root=activity.findViewById(android.R.id.content);if(root!=null)capture(root,"root",out);return out;}
    private static void capture(View view,String path,Bundle out){
        if(view instanceof ScrollView||view instanceof HorizontalScrollView)out.putIntArray(path,new int[]{view.getScrollX(),view.getScrollY()});
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)capture(((ViewGroup)view).getChildAt(i),path+"/"+i,out);
    }
    static void restore(Activity activity,Bundle state){if(state==null||state.isEmpty())return;View root=activity.findViewById(android.R.id.content);if(root==null)return;
        // 不要在这里 root.invalidate()：切页回来时整棵内容会被判脏，硬件渲染要重新录制整页
        // display list（冻结页实测 65ms，肉眼就是一帧卡住）。滚动位置在下面的 preDraw 里恢复，
        // 只有真正移动到的子视图才失效；窗口本来就会画这一帧。
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnPreDrawListener(this);apply(root,"root",state);return true;}});
    }
    private static void apply(View view,String path,Bundle state){int[] value=state.getIntArray(path);if(value!=null&&value.length==2&&(view instanceof ScrollView||view instanceof HorizontalScrollView))view.scrollTo(value[0],value[1]);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)apply(((ViewGroup)view).getChildAt(i),path+"/"+i,state);
    }
}
