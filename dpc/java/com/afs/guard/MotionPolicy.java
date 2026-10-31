package com.afs.guard;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/** Existing preferences and platform settings only; never owns an Activity or business work. */
final class MotionPolicy {
    private static volatile float animator=1, transition=1;
    private static boolean watching;
    private static final java.util.WeakHashMap<Runnable,Boolean> listeners=new java.util.WeakHashMap<>();
    static void add(Context c,Runnable listener){watch(c);listeners.put(listener,true);}
    static void remove(Runnable listener){listeners.remove(listener);}
    private static void changed(){
        for(Runnable listener:new java.util.ArrayList<Runnable>(listeners.keySet()))if(listener!=null)listener.run();
    }
    private static final android.content.SharedPreferences.OnSharedPreferenceChangeListener preferences=(prefs,key)->{
        if("reduce_motion".equals(key))new Handler(Looper.getMainLooper()).post(MotionPolicy::changed);
    };
    private MotionPolicy(){}
    private static float read(Context c,String key){
        try{return Math.max(0,Settings.Global.getFloat(c.getContentResolver(),key,1));}
        catch(RuntimeException unavailable){return 1;}
    }
    private static void refresh(Context c){animator=read(c,Settings.Global.ANIMATOR_DURATION_SCALE);transition=read(c,Settings.Global.TRANSITION_ANIMATION_SCALE);}
    private static synchronized void watch(Context context){
        if(watching)return;final Context c=context.getApplicationContext();refresh(c);watching=true;
        c.getSharedPreferences(Ui.SP,0).registerOnSharedPreferenceChangeListener(preferences);
        ContentObserver observer=new ContentObserver(new Handler(Looper.getMainLooper())){
            @Override public void onChange(boolean self){refresh(c);changed();}
        };
        try{c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer);
            c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),false,observer);
        }catch(RuntimeException unavailable){try{c.getContentResolver().unregisterContentObserver(observer);}catch(RuntimeException ignored){}watching=true;}
    }
    static boolean enabled(Context c){watch(c);return !c.getSharedPreferences(Ui.SP,0).getBoolean("reduce_motion",false)&&animator>0;}
    static boolean navigation(Context c){return enabled(c)&&transition>0;}
    static long duration(Context c,long normal){return enabled(c)?normal:0;}
    static double timeScale(Context c){return enabled(c)?Math.max(.01f,animator):0;}
    static long remaining(Context c,long budget,long elapsed){
        if(!navigation(c))return 0;long remaining=Math.max(0,budget-(long)(elapsed/Math.max(.01f,animator)));
        return remaining<80?0:remaining;
    }
}
