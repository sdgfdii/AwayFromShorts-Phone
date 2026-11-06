package com.afs.host.plugins;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import com.afs.guard.Lang;
import com.afs.guard.T;
import com.afs.guard.Ui;
import com.afs.pluginapi.IPluginEndpoint;
import com.afs.pluginapi.IPrepareCallback;
import com.afs.pluginapi.LaunchTicket;
import com.afs.pluginapi.PageLaunch;
import com.afs.pluginapi.PluginProtocol;
import com.afs.pluginapi.PluginStatus;
import com.afs.pluginapi.PrepareResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-side routing. It re-validates the install, calls preparePage with a
 * two-second deadline, then starts the verified plugin Activity with a
 * one-time ticket. Async Binder work never blocks the UI.
 */
public final class HostPluginRouter {

    public interface Failure {
        void onFailure(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ThreadPoolExecutor OUTGOING = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<Runnable>(8), r -> {
                Thread t=new Thread(r,"afs-plugin-prepare"); t.setDaemon(true); return t;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    static {OUTGOING.allowCoreThreadTimeOut(true);}

    private HostPluginRouter() {}

    public static void openPage(Activity host, PluginEntry entry, String pageId,
                                int requestCode, Failure failure) {
        if (host == null || entry == null) return;
        HostRuntime runtime = HostRuntime.get(host);
        PluginEntry current = runtime.registry().findByPackage(entry.packageName());
        if (current == null || !current.usable()) {
            failure(failure, "插件未启用或已更新，请在插件管理中刷新");
            return;
        }
        PluginRegistry.Verified verified = runtime.registry().verify(current.packageName(),
                current.pluginId(), current.fingerprint.value());
        if (verified == null) {
            failure(failure, "插件身份或安装状态已变化，请刷新后重试");
            return;
        }
        PluginManifestParser.Page page = current.page(pageId);
        if (page == null) {
            failure(failure, "插件没有声明这个页面");
            return;
        }
        ActivityInfo info;
        try {
            info = host.getPackageManager().getActivityInfo(
                    new ComponentName(current.packageName(), page.activity), 0);
        } catch (PackageManager.NameNotFoundException e) {
            failure(failure, "插件页面不存在");
            return;
        }
        if (!info.exported || !PluginProtocol.PERMISSION.equals(info.permission)) {
            failure(failure, "插件页面未受签名权限保护");
            return;
        }
        new PrepareTask(host, current, page, requestCode, failure).start();
    }

    private static void failure(Failure failure, String message) {
        if (failure != null) failure.onFailure(T.s(message));
    }

    private static PageLaunch launchFor(Activity host, String pageId, long requestId) {
        Ui ui = new Ui(host);
        boolean reduce;
        try {
            reduce = host.getSharedPreferences(Ui.SP, Context.MODE_PRIVATE)
                    .getBoolean("reduce_motion", false)
                    || Settings.Global.getFloat(host.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f;
        } catch (RuntimeException unavailable) {
            reduce = true;
        }
        return new PageLaunch(requestId, pageId, PluginProtocol.PROTOCOL,
                ui.bg(), ui.surface(), ui.text(), ui.text2(), ui.line(), ui.accent(),
                ui.red(), ui.green(), ui.dark, T.locale().toLanguageTag(),
                host.getResources().getConfiguration().fontScale, reduce);
    }

    private static void putTheme(Activity host, Intent intent) {
        Ui ui = new Ui(host);
        com.afs.guard.AppearanceConfig config=com.afs.guard.AppearanceConfig.parse(
                host.getSharedPreferences(Ui.SP,Context.MODE_PRIVATE).getString("appearance_v1",""));
        int seed=com.afs.guard.AppearanceConfig.DEFAULT_COLOR;
        if(android.os.Build.VERSION.SDK_INT>=31){int id=host.getResources().getIdentifier("system_accent1_500","color","android");if(id!=0)seed=host.getColor(id);}
        intent.putExtra(PluginProtocol.EXTRA_FOLLOW_SYSTEM,"auto".equals(Ui.themeName(host)));
        intent.putExtra(PluginProtocol.EXTRA_CUSTOM_FONT_SCALE,config.customScale);
        intent.putExtra(PluginProtocol.EXTRA_LIGHT_COLORS,colors(new com.afs.guard.AppearancePalette(config,false,seed),false));
        intent.putExtra(PluginProtocol.EXTRA_DARK_COLORS,colors(new com.afs.guard.AppearancePalette(config,true,seed),true));
        intent.putExtra(PluginProtocol.EXTRA_THEME_BG, ui.bg());
        intent.putExtra(PluginProtocol.EXTRA_THEME_SURFACE, ui.surface());
        intent.putExtra(PluginProtocol.EXTRA_THEME_TEXT, ui.text());
        intent.putExtra(PluginProtocol.EXTRA_THEME_TEXT2, ui.text2());
        intent.putExtra(PluginProtocol.EXTRA_THEME_TRACK, ui.line());
        intent.putExtra(PluginProtocol.EXTRA_THEME_ACCENT, ui.accent());
        intent.putExtra(PluginProtocol.EXTRA_THEME_RED, ui.red());
        intent.putExtra(PluginProtocol.EXTRA_THEME_GREEN, ui.green());
        intent.putExtra(PluginProtocol.EXTRA_DARK, ui.dark);
        intent.putExtra(PluginProtocol.EXTRA_LOCALE, T.locale().toLanguageTag());
        intent.putExtra(PluginProtocol.EXTRA_FONT_SCALE,
                host.getResources().getConfiguration().fontScale);
        try {
            intent.putExtra(PluginProtocol.EXTRA_REDUCE_MOTION,
                    host.getSharedPreferences(Ui.SP, Context.MODE_PRIVATE)
                            .getBoolean("reduce_motion", false));
        } catch (RuntimeException ignored) { }
    }
    private static int[] colors(com.afs.guard.AppearancePalette p,boolean dark){
        return new int[]{p.bg,p.surface,p.text,p.text2,p.line,p.accent,
                dark?0xFFFF7B72:0xFFD93A3A,dark?0xFF3DDC84:0xFF12A150};
    }

    /** Best-effort close notification after the host has already revoked access. */
    public static void notifyClose(Context context, PluginEntry entry, List<String> leaseIds) {
        if (context == null || entry == null || entry.service == null || leaseIds == null
                || leaseIds.isEmpty()) return;
        Intent intent = new Intent();
        intent.setComponent(entry.service);
        final AtomicBoolean done = new AtomicBoolean(false);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!done.compareAndSet(false, true)) return;
                IPluginEndpoint endpoint = IPluginEndpoint.Stub.asInterface(binder);
                try {
                    OUTGOING.execute(() -> {
                    try {
                    for (String leaseId : leaseIds) {
                        endpoint.closeSession(leaseId == null ? "" : leaseId, PluginStatus.REVOKED);
                    }
                    } catch (RemoteException | RuntimeException ignored) { }
                    finally { MAIN.post(() -> { try { context.unbindService(this); } catch(RuntimeException ignored){} }); }
                    });
                } catch (java.util.concurrent.RejectedExecutionException full) {
                    try { context.unbindService(this); } catch(RuntimeException ignored){}
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        try { context.bindService(intent, connection, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException ignored) { return; }
        MAIN.postDelayed(() -> {
            done.set(true);
            try { context.unbindService(connection); } catch (RuntimeException ignored) { }
        }, 1_000L);
    }

    private static final class PrepareTask {
        final Activity host;
        final PluginEntry entry;
        final PluginManifestParser.Page page;
        final int requestCode;
        final Failure failure;
        final long requestId = new java.security.SecureRandom().nextLong();
        final PrepareDeadline gate = new PrepareDeadline(
                SystemClock.elapsedRealtime(), PluginProtocol.PREPARE_TIMEOUT_MS);
        boolean bound;
        IPluginEndpoint endpoint;

        final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (gate.settled()) { unbind(); return; }
                if (name == null || !name.getPackageName().equals(entry.packageName())) {
                    fail("插件服务身份不符");
                    return;
                }
                HostRuntime runtime = HostRuntime.get(host);
                PluginRegistry.Verified verified = runtime.registry().verify(entry.packageName(),
                        entry.pluginId(), entry.fingerprint.value());
                if (verified == null) {
                    fail("插件安装状态已变化");
                    return;
                }
                endpoint = IPluginEndpoint.Stub.asInterface(binder);
                final PageLaunch launch=launchFor(host, page.id, requestId);
                try {
                    OUTGOING.execute(() -> {
                    try {
                    endpoint.preparePage(launch,
                            new IPrepareCallback.Stub() {
                                @Override public boolean onTransact(int code,android.os.Parcel data,android.os.Parcel reply,int flags)throws RemoteException{
                                    if(code!=IBinder.INTERFACE_TRANSACTION&&(Binder.getCallingUid()!=entry.fingerprint.uid||data==null||data.dataSize()>PluginProtocol.MAX_TRANSACTION))return false;
                                    try{if(!com.afs.pluginapi.WireFrame.valid("com.afs.pluginapi.IPrepareCallback",code,data))return false;return super.onTransact(code,data,reply,flags);}catch(RuntimeException malformed){return false;}
                                }
                                @Override public void onResult(PrepareResult result) {
                                    if (Binder.getCallingUid() != entry.fingerprint.uid) return;
                                    if (result == null || result.requestId != requestId) return;
                                    MAIN.post(() -> onPrepared(result));
                                }
                            });
                    } catch (RemoteException | RuntimeException e) {
                        MAIN.post(() -> fail("插件服务连接中断"));
                    }
                    });
                } catch (java.util.concurrent.RejectedExecutionException full) {
                    fail("插件连接繁忙，请重试");
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                fail("插件服务已断开");
            }
        };

        PrepareTask(Activity host, PluginEntry entry, PluginManifestParser.Page page,
                    int requestCode, Failure failure) {
            this.host = host;
            this.entry = entry;
            this.page = page;
            this.requestCode = requestCode;
            this.failure = failure;
        }

        void start() {
            Intent intent = new Intent();
            intent.setComponent(entry.service);
            try {
                bound = host.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException denied) {
                bound = false;
            }
            if (!bound) {
                fail("无法绑定插件服务");
                return;
            }
            MAIN.postDelayed(() -> {
                if (gate.timeout(SystemClock.elapsedRealtime())) {
                    unbind();
                    failure(failure, "插件初始化超时");
                }
            }, PluginProtocol.PREPARE_TIMEOUT_MS);
        }

        void onPrepared(PrepareResult result) {
            if (gate.settled()) return;
            if (result.status != PluginStatus.OK) {
                fail(result.message == null || result.message.isEmpty()
                        ? "插件初始化失败" : result.message);
                return;
            }
            HostRuntime runtime = HostRuntime.get(host);
            PluginRegistry.Verified verified = runtime.registry().verify(entry.packageName(),
                    entry.pluginId(), entry.fingerprint.value());
            if (verified == null || !verified.state.enabled) {
                fail("插件已停用或更新");
                return;
            }
            LaunchTicket ticket = runtime.sessions().issueTicket(entry, page);
            if (ticket == null) {
                fail("无法签发启动票据");
                return;
            }
            if (host.isFinishing() || host.isDestroyed()) {
                gate.ready();
                unbind();
                return;
            }
            if (!gate.ready()) return;
            ComponentName target = new ComponentName(entry.packageName(), page.activity);
            Intent intent = new Intent();
            intent.setComponent(target);
            intent.putExtra(PluginProtocol.EXTRA_TICKET, ticket);
            putTheme(host, intent);
            unbind();
            try {
                host.startActivityForResult(intent, requestCode);
            } catch (RuntimeException denied) {
                failure(failure, "无法启动插件页面");
            }
        }

        void fail(String message) {
            if (!gate.fail()) return;
            unbind();
            failure(failure, message);
        }

        void unbind() {
            if (!bound) return;
            bound = false;
            try { host.unbindService(connection); } catch (RuntimeException ignored) { }
        }
    }
}
