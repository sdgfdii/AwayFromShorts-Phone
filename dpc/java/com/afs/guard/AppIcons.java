package com.afs.guard;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import java.util.Collection;

/**
 * 应用图标缓存。
 *
 * 为什么需要：`getApplicationIcon` 在 ColorOS 上要过主题拦截、加载目标 APK 的资源表
 * （实测单个 2–7ms，个别 500ms+）。冻结页每次重建（拖一个应用就重建一次）都要取三十多个，
 * 冷开一次就是几十到几百毫秒的主线程卡顿；而且图标上会挂灰度滤镜，所以缓存 ConstantState、
 * 每次给一份新的 Drawable，不能共用实例。
 *
 * 启动时由 {@link Startup} 在后台预热，页面第一次构建时基本都能命中。
 */
final class AppIcons {

    private static final LruCache<String, Drawable.ConstantState> STATES =
            new LruCache<String, Drawable.ConstantState>(8 * 1024) {
                @Override protected int sizeOf(String key, Drawable.ConstantState state) {
                    Drawable probe = state.newDrawable();
                    if(probe instanceof android.graphics.drawable.BitmapDrawable)return Math.max(1,((android.graphics.drawable.BitmapDrawable)probe).getBitmap().getAllocationByteCount()/1024);
                    int width = probe == null ? 64 : Math.max(1, probe.getIntrinsicWidth());
                    int height = probe == null ? 64 : Math.max(1, probe.getIntrinsicHeight());
                    return Math.max(4, width * height * 4 / 1024);
                }
            };

    private AppIcons() {
    }

    private static String key(Context c,String pkg){return pkg+":"+c.getResources().getDisplayMetrics().densityDpi+":"+(c.getResources().getConfiguration().uiMode&48);}
    private static Drawable sized(Context c,Drawable source){
        if(source instanceof android.graphics.drawable.Animatable)return source;
        int side=Math.max(1,Math.round(64*c.getResources().getDisplayMetrics().density));
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(side,side,android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Rect old=new android.graphics.Rect(source.getBounds());
        source.setBounds(0,0,side,side);source.draw(new android.graphics.Canvas(bitmap));source.setBounds(old);
        android.graphics.drawable.BitmapDrawable result=new android.graphics.drawable.BitmapDrawable(c.getResources(),bitmap);result.setFilterBitmap(true);return result;
    }
    static void trim(){STATES.evictAll();}

    /** 取一份可安全挂滤镜的新图标；拿不到返回 null（调用方用占位图兜底）。 */
    static Drawable drawable(Context c, String pkg) {
        if (pkg == null) return null;
        Drawable.ConstantState state = STATES.get(key(c,pkg));
        if (state == null) {
            try {
                Drawable loaded = c.getPackageManager().getApplicationIcon(pkg);
                state = sized(c,loaded).getConstantState();
                if (state == null) return loaded;
                STATES.put(key(c,pkg), state);
            } catch (PackageManager.NameNotFoundException | RuntimeException missing) {
                return null;
            }
        }
        return state.newDrawable(c.getResources(), c.getTheme());
    }

    /** 后台预热：只填缓存，失败无所谓（页面自己会再取一次）。 */
    static void prewarm(Context c, Collection<String> packages) {
        if (packages == null) return;
        for (String pkg : packages) {
            if (pkg == null || STATES.get(key(c,pkg)) != null) continue;
            try {
                Drawable loaded = c.getPackageManager().getApplicationIcon(pkg);
                Drawable.ConstantState state = sized(c,loaded).getConstantState();
                if (state != null) STATES.put(key(c,pkg), state);
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            }
        }
    }
}
