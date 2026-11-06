package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.view.accessibility.AccessibilityNodeInfo;

/** Opens menus only: never selects documents, exports or restores user data. */
public final class BackupEntryDeviceTest extends Instrumentation {
    int checks;Activity page;
    void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
    boolean text(View v,String s){if(v instanceof TextView&&s.contentEquals(((TextView)v).getText()))return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(text(((ViewGroup)v).getChildAt(i),s))return true;return false;}
    boolean node(AccessibilityNodeInfo v,String s){if(v==null)return false;if(s.contentEquals(v.getText()==null?"":v.getText()))return true;for(int i=0;i<v.getChildCount();i++)if(node(v.getChild(i),s))return true;return false;}
    void open(Class<?> type,boolean backup){if(page!=null){Activity old=page;runOnMainSync(old::finish);}page=startActivitySync(new Intent(getTargetContext(),type).putExtra("backup_only",backup).putExtra(CardManagementActivity.EXTRA_MANAGE,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void onStart(){Bundle out=new Bundle();int status=0;try{
        check(Build.MODEL.toLowerCase().contains("sdk"),"emulator required");
        open(DataManagementActivity.class,false);Thread.sleep(500);View root=page.getWindow().getDecorView();
        for(String label:new String[]{"导出完整本地备份","目标与打卡备份","记忆备份（含图片与复习记录）","备忘录备份（含附件）"})check(text(root,T.s(label)),"central entry: "+label);
        open(MemoActivity.class,false);check(!text(page.getWindow().getDecorView(),T.s("备份与恢复")),"memo duplicate removed");
        open(MemoryActivity.class,false);check(!text(page.getWindow().getDecorView(),T.s("导出备份（含图片与复习记录）")),"memory duplicate removed");
        Class<?>[] types={CheckinActivity.class,MemoryActivity.class,MemoActivity.class};String[] titles={"备份 / 恢复","记忆备份","本机备份"};
        for(int i=0;i<types.length;i++){open(types[i],true);boolean shown=false;for(int j=0;j<80&&!shown;j++){Thread.sleep(50);shown=node(getUiAutomation().getRootInActiveWindow(),T.s(titles[i]));}check(shown,"central route opens module backup menu: "+types[i].getSimpleName());}
        out.putString("stream","Backup entry device: "+checks+" checks passed\n");status=-1;
    }catch(Throwable e){out.putString("stream",android.util.Log.getStackTraceString(e));}finally{if(page!=null)runOnMainSync(page::finish);finish(status,out);}}
}
