package com.afs.guard;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

/** Uses isolated preferences/files and never performs a network request. */
public final class SyncConfigDeviceTest extends Instrumentation {
    private Class<?> config, target;
    private int checks;
    private Object call(Class<?> type, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = type.getDeclaredMethod(name, types); m.setAccessible(true);
        try { return m.invoke(null, args); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    private void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private void save(Context c, Object t, String token) throws Exception {
        call(config, "save", new Class<?>[]{Context.class, target, String.class}, c, t, token);
    }
    private String cache(Context c) throws Exception {
        return (String) call(config, "cacheName", new Class<?>[]{Context.class, String.class}, c, "afs_hub");
    }
    public void onCreate(Bundle args) { super.onCreate(args); start(); }
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Context real = getTargetContext();
            String prefix = "sync_qa_" + System.nanoTime() + "_";
            Context c = new ContextWrapper(real) {
                public Context getApplicationContext() { return this; }
                public SharedPreferences getSharedPreferences(String n, int m) {
                    return real.getSharedPreferences(prefix + n, m);
                }
                public File getFilesDir() { File f = new File(real.getCacheDir(), prefix); f.mkdirs(); return f; }
            };
            config = Class.forName("com.afs.guard.SyncConfig", true, real.getClassLoader());
            target = Class.forName("com.afs.guard.SyncTarget", true, real.getClassLoader());
            Class<?>[] ctx = {Context.class};
            check(!(Boolean)call(config,"configured",ctx,c), "fresh install has no sync target");
            try { call(config,"acquire",ctx,c); throw new AssertionError("unconfigured request accepted"); }
            catch (IllegalStateException expected) { checks++; }
            String rules = "{\"example.frozen\":{\"d\":0,\"w\":[]}}";
            SharedPreferences guard = c.getSharedPreferences("afs_guard",0);
            guard.edit().putString("rules",rules).commit();
            Object a = call(target,"create",new Class<?>[]{String.class,String.class},"user-a","abcdef01");
            Object b = call(target,"create",new Class<?>[]{String.class,String.class},"user-b","abcdef02");
            save(c,a,"fixture_token");
            String first = cache(c);
            c.getSharedPreferences(first,0).edit().putString("ledger_base","old-account").commit();
            Object lease = call(config,"acquire",ctx,c);
            Method current = lease.getClass().getDeclaredMethod("isCurrent",Context.class); current.setAccessible(true);
            check((Boolean)current.invoke(lease,c),"current request may update UI");
            try { save(c,b,"fixture_token"); throw new AssertionError("destination switched mid-request"); }
            catch (IllegalStateException expected) { checks++; }
            check(first.equals(cache(c)),"busy save left config intact");
            Method close = lease.getClass().getDeclaredMethod("close"); close.setAccessible(true);
            close.invoke(lease); close.invoke(lease);
            save(c,a,"replacement_token");
            check(first.equals(cache(c)),"credential refresh preserves baseline");
            save(c,b,"fixture_token");
            check(!(Boolean)current.invoke(lease,c),"old request cannot update UI after target switch");
            check(!first.equals(cache(c)),"new destination has separate cache");
            check(!c.getSharedPreferences(cache(c),0).contains("ledger_base"),"old baseline not used");
            check(rules.equals(guard.getString("rules","")),"switch preserves freeze rules");
            save(c,null,"");
            check(!(Boolean)call(config,"configured",ctx,c),"disabled sync has no target");
            guard.edit().putBoolean("armed",true).commit();
            // 冻结中被卡的只有"换 Gist / 停用"，重新接上同一个 Gist 必须放行 ——
            // 否则凭据一丢，一天里只有放行那几十分钟能配，台账只能一直躺在手机里。
            save(c,a,"fixture_token");
            check((Boolean)call(config,"configured",ctx,c),"freeze allows reconnecting the same destination");
            try { save(c,b,"fixture_token"); throw new AssertionError("destination switched during freeze"); }
            catch (IllegalStateException expected) { checks++; }
            try { save(c,null,""); throw new AssertionError("sync disabled during freeze"); }
            catch (IllegalStateException expected) { checks++; }
            check(rules.equals(guard.getString("rules","")),"rejected save preserves freeze rules");
            // 本地完整恢复会把旧版同步挂起：目标与凭据都还在，所以界面必须给得出"重新启用"，
            // 而且重新启用不能换缓存基线 —— 台账、冻结规则和历史都得原样接上。
            String beforePause = cache(c);
            c.getSharedPreferences("afs_data_status",0).edit().putBoolean("pause_legacy_sync",true).commit();
            check((Boolean)call(config,"paused",ctx,c),"restore pause is visible");
            check(!(Boolean)call(config,"configured",ctx,c),"paused sync is not configured");
            try { call(config,"acquire",ctx,c); throw new AssertionError("paused sync accepted a request"); }
            catch (IllegalStateException expected) { checks++; }
            call(config,"resume",ctx,c);
            check(!(Boolean)call(config,"paused",ctx,c),"resume clears the pause");
            check((Boolean)call(config,"configured",ctx,c),"resume re-enables the same destination");
            check(beforePause.equals(cache(c)),"resume keeps the merge baseline");
            Object resumed = call(config,"acquire",ctx,c);
            Method resumedClose = resumed.getClass().getDeclaredMethod("close"); resumedClose.setAccessible(true);
            resumedClose.invoke(resumed);
            call(config,"resume",ctx,c); checks++;   // 已启用时再点一次不能抛错
            guard.edit().putBoolean("armed",false).commit();
            // 直接清掉目标（不走 save：它与常驻进程里的同步会话互斥，测试不该跟它抢）
            c.getSharedPreferences("afs_sync",0).edit().clear().commit();
            check(!(Boolean)call(config,"configured",ctx,c),"cleared target has no sync");
            try { call(config,"resume",ctx,c); throw new AssertionError("resume without a destination"); }
            catch (IllegalStateException expected) { checks++; }
            check(!(Boolean)call(config,"configured",ctx,c),"resume never invents a destination");
            result.putString("stream","Sync config device: " + checks + " checks passed\n");
            finish(-1,result);
        } catch (Throwable e) {
            result.putString("stream","FAILED " + android.util.Log.getStackTraceString(e)); finish(1,result);
        }
    }
}
