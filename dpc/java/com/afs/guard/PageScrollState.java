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
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnPreDrawListener(this);apply(root,"root",state);return true;}});root.invalidate();
    }
    private static void apply(View view,String path,Bundle state){int[] value=state.getIntArray(path);if(value!=null&&value.length==2&&(view instanceof ScrollView||view instanceof HorizontalScrollView))view.scrollTo(value[0],value[1]);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)apply(((ViewGroup)view).getChildAt(i),path+"/"+i,state);
    }
}
