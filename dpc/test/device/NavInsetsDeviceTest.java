package com.afs.guard;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import java.lang.reflect.*;
/** Layout inspection only: never creates content, checks in or edits rules. */
public final class NavInsetsDeviceTest extends Instrumentation {
    int checks;Activity page;
    void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    Object field(Class<?> cls,Object o,String name)throws Exception{Field f=cls.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void onStart(){Bundle out=new Bundle();int status=0;try{
        check(NavContentInsets.clearance(1000,850,950,12,95)==162,"measured dock position includes actual obstruction");
        check(NavContentInsets.clearance(1000,0,0,12,95)==95,"unmeasured window uses bounded fallback");
        for(Class<?> type:new Class<?>[]{MainActivity.class,CheckinActivity.class,MemoryActivity.class,QuizActivity.class,FreezeBoardActivity.class,DailyQuotaActivity.class}){
            page=startActivitySync(new Intent(getTargetContext(),type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(800);
            Method find=LiveNavWindow.class.getDeclaredMethod("find",Activity.class);find.setAccessible(true);Object window=find.invoke(null,page);check(window!=null,"dock attached: "+type.getSimpleName());
            FloatingNav nav=(FloatingNav)field(LiveNavWindow.class,window,"nav");NavContentInsets helper=(NavContentInsets)field(FloatingNav.class,nav,"contentInsets");View content=(View)field(NavContentInsets.class,helper,"content");int base=(Integer)field(NavContentInsets.class,helper,"base");
            runOnMainSync(()->{if(content instanceof ScrollView)((ScrollView)content).fullScroll(View.FOCUS_DOWN);});Thread.sleep(250);
            int[] a=new int[2],b=new int[2];runOnMainSync(()->{content.getLocationOnScreen(a);nav.getLocationOnScreen(b);});
            int contentBottom=a[1]+content.getHeight()-content.getPaddingBottom();
            check(contentBottom<=b[1]-new Ui(page).dp(10),"content ends above dock with clearance: "+type.getSimpleName()+" bottom="+contentBottom+" dock="+b[1]);
            if(content instanceof ScrollView&&((ScrollView)content).getChildCount()>0){View last=((ScrollView)content).getChildAt(0);int[] position=new int[2];runOnMainSync(()->last.getLocationOnScreen(position));check(position[1]+last.getHeight()-last.getPaddingBottom()<=b[1]-new Ui(page).dp(10),"last scroll content is reachable above dock: "+type.getSimpleName());}
            int once=content.getPaddingBottom();runOnMainSync(()->{for(int i=0;i<20;i++)nav.updateContentInsets(false,0,new Ui(page).dp(21));});
            check(content.getPaddingBottom()==once,"repeated refresh never accumulates padding: "+type.getSimpleName());
            runOnMainSync(()->nav.updateContentInsets(true,0,0));check(content.getPaddingBottom()>=base&&content.getPaddingBottom()<=once,"keyboard uses visible viewport instead of dock padding: "+type.getSimpleName());
            runOnMainSync(()->nav.updateContentInsets(false,0,new Ui(page).dp(21)));check(content.getPaddingBottom()==once,"closing keyboard restores clearance: "+type.getSimpleName());
            if(type==MainActivity.class){
                Activity keyboardPage=page;EditText input=new EditText(page);input.setSingleLine(true);
                int previousMode=page.getWindow().getAttributes().softInputMode;
                runOnMainSync(()->{keyboardPage.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);keyboardPage.addContentView(input,new ViewGroup.LayoutParams(-1,new Ui(keyboardPage).dp(48)));input.requestFocus();((android.view.inputmethod.InputMethodManager)keyboardPage.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
                boolean visible=false;for(int n=0;n<60&&!visible;n++){Thread.sleep(50);WindowInsets insets=content.getRootWindowInsets();visible=android.os.Build.VERSION.SDK_INT>=30&&insets!=null&&insets.isVisible(WindowInsets.Type.ime());}
                check(visible,"real keyboard opens on device");Thread.sleep(350);
                Dialog dockDialog=(Dialog)field(LiveNavWindow.class,window,"dialog");check(!dockDialog.isShowing(),"dock hides above real keyboard");
                View pageHost=(View)field(LiveNavWindow.class,window,"host");int[] pos=new int[2];android.graphics.Rect viewport=new android.graphics.Rect();runOnMainSync(()->{content.getLocationOnScreen(pos);pageHost.getWindowVisibleDisplayFrame(viewport);});
                check(pos[1]+content.getHeight()-content.getPaddingBottom()<=viewport.bottom,"content stays above real keyboard");
                runOnMainSync(()->{((android.view.inputmethod.InputMethodManager)keyboardPage.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);((ViewGroup)input.getParent()).removeView(input);keyboardPage.getWindow().setSoftInputMode(previousMode);});
                for(int n=0;n<60&&content.getPaddingBottom()!=once;n++)Thread.sleep(50);check(content.getPaddingBottom()==once,"real keyboard dismissal restores original dock clearance");
            }
            Activity done=page;runOnMainSync(done::finish);page=null;
        }
        out.putString("stream","Nav insets device: "+checks+" checks passed\n");status=-1;
    }catch(Throwable e){out.putString("stream",android.util.Log.getStackTraceString(e));}finally{if(page!=null)runOnMainSync(page::finish);finish(status,out);}}
}
