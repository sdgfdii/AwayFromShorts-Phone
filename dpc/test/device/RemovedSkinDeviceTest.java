package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;

/** Checks legacy preference fallback and actual theme choices, with exact preference restoration. */
public final class RemovedSkinDeviceTest extends Instrumentation {
    int checks;
    void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    boolean text(View v,String value){
        if(v instanceof TextView&&((TextView)v).getText().toString().startsWith(value))return true;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(text(((ViewGroup)v).getChildAt(i),value))return true;
        return false;
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Context c=getTargetContext();SharedPreferences prefs=c.getSharedPreferences(Ui.SP,0);
        boolean had=prefs.contains(ThemeStore.CONFIG);String raw=prefs.getString(ThemeStore.CONFIG,"");
        Activity page=null;Bundle out=new Bundle();int result=0;
        try{
            check(Build.MODEL.toLowerCase().contains("sdk"),"emulator required");
            prefs.edit().putString(ThemeStore.CONFIG,"{\"skin\":\"ripple\",\"font\":\"serif\",\"seed\":-256,\"amapSeed\":-65536}").commit();
            AppearanceConfig config=ThemeStore.load(c);
            check("classic".equals(config.skin),"stored removed style loads as classic");
            check(config.seed==-256&&config.amapSeed==-65536&&"serif".equals(config.font),"other appearance preferences preserved");
            page=startActivitySync(new Intent(c,ThemeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(600);
            View root=page.getWindow().getDecorView();
            check(text(root,T.s("原有风格")),"classic choice visible");
            check(text(root,T.s("简约风格")),"minimal choice visible");
            check(!text(root,"涟漪风格")&&!text(root,"Ripple"),"removed style absent from actual theme page");
            out.putString("stream","Removed skin device: "+checks+" checks passed\n");result=-1;
        }catch(Throwable error){out.putString("stream",android.util.Log.getStackTraceString(error));}
        finally{
            if(page!=null){Activity opened=page;runOnMainSync(opened::finish);}
            SharedPreferences.Editor edit=prefs.edit();if(had)edit.putString(ThemeStore.CONFIG,raw);else edit.remove(ThemeStore.CONFIG);edit.commit();
            finish(result,out);
        }
    }
}
