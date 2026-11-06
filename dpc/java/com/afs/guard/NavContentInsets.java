package com.afs.guard;
import android.view.View;
import android.widget.ScrollView;
import java.util.WeakHashMap;
/** Bottom clearance is owned by the dock, never accumulated by page refreshes. */
final class NavContentInsets {
    private static final WeakHashMap<View,Integer> baseline=new WeakHashMap<>();
    private final View content;private final int base;
    NavContentInsets(View content){this.content=content;Integer saved=baseline.get(content);base=saved==null?content.getPaddingBottom():saved;baseline.put(content,base);if(content instanceof ScrollView)((ScrollView)content).setClipToPadding(false);}
    void apply(int clearance){int bottom=base+Math.max(0,clearance);if(content.getPaddingBottom()!=bottom)content.setPadding(content.getPaddingLeft(),content.getPaddingTop(),content.getPaddingRight(),bottom);}
    static int clearance(int hostBottom,int dockTop,int dockBottom,int gap,int fallback){return dockBottom>dockTop&&dockTop>0&&dockTop<hostBottom?Math.max(0,hostBottom-dockTop)+gap:fallback;}
}
