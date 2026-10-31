package com.afs.guard;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.util.Set;

/**
 * 屏蔽联网：本地 VPN 黑洞，只接管用户选中的应用。
 *
 * 原理：{@code addAllowedApplication} 让隧道**只**收这几个应用的流量，
 * 路由给 0.0.0.0/0，然后把收到的包直接丢掉、不转发 —— 对这些应用来说就是
 * 连不上任何服务器（表现为转圈、超时），其余应用完全不受影响。
 *
 * 三个必须写明的限制：
 * 1. 系统同一时间只允许一个 VPN。开了它就是「全网唯一 VPN」，代理、公司内网、
 *    其他加速器全部失效 —— 界面里明说，切换前先关代理。
 * 2. 没选应用时不允许启动：一个都不允许的隧道等于接管全机，那个方向是灾难。
 * 3. 用户随时可以在系统设置里撤销 VPN 授权；撤销时 {@link #onRevoke()} 会被调用，
 *    这里只如实把状态改成"没在跑"，不假装还生效。
 */
public class NetBlockService extends VpnService {

    public static final String ACTION_START = "com.afs.guard.NETBLOCK_START";
    public static final String ACTION_STOP = "com.afs.guard.NETBLOCK_STOP";

    private static final String SESSION = "AFS 断网";
    private static final String ADDRESS = "10.111.0.2";
    private static final String DNS = "10.111.0.1";

    private ParcelFileDescriptor tunnel;
    private Thread pump;
    private Thread beat;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tunnel != null) return START_STICKY;      // 已经在跑，不重建
        open();
        return START_STICKY;
    }

    private void open() {
        Set<String> packages = NetBlockStore.packages(this);
        if (packages.isEmpty()) {
            Log.w(Guard.TAG, "netblock: no packages, refuse to establish");
            stopSelf();
            return;
        }
        Builder builder = new Builder()
                .setSession(SESSION)
                .addAddress(ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS)
                .setMtu(1500)
                .setBlocking(true);
        int allowed = 0;
        for (String pkg : packages) {
            if (pkg == null || pkg.isEmpty()) continue;
            try {
                builder.addAllowedApplication(pkg);
                allowed++;
            } catch (Exception missing) {
                Log.w(Guard.TAG, "netblock: skip " + pkg);
            }
        }
        if (allowed == 0) {
            Log.w(Guard.TAG, "netblock: none of the packages installed");
            stopSelf();
            return;
        }
        try {
            tunnel = builder.establish();
        } catch (Throwable error) {
            Log.e(Guard.TAG, "netblock establish failed", error);
        }
        if (tunnel == null) {
            NetBlockStore.setRunning(this, false);
            stopSelf();
            return;
        }
        NetBlockStore.setRunning(this, true);
        Log.i(Guard.TAG, "netblock established for " + allowed + " apps");
        pump = new Thread(new Runnable() {
            @Override
            public void run() {
                discard();
            }
        }, "afs-netblock");
        pump.setDaemon(true);
        pump.start();
        startBeat();
    }

    /**
     * 每 20 秒盖一次心跳。{@code running} 这个标志本身活不过进程被杀，
     * 界面又拿它当「真的在断网」的依据，所以得有个东西证明服务还在。
     */
    private void startBeat() {
        beat = new Thread(new Runnable() {
            @Override public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    if (tunnel == null) return;
                    NetBlockStore.beat(NetBlockService.this);
                    try {
                        Thread.sleep(20000L);
                    } catch (InterruptedException stop) {
                        return;
                    }
                }
            }
        }, "afs-netblock-beat");
        beat.setDaemon(true);
        beat.start();
    }

    /** 收包就丢。读到 -1 表示隧道被系统关掉了。 */
    private void discard() {
        ParcelFileDescriptor descriptor = tunnel;
        if (descriptor == null) return;
        FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
        byte[] buffer = new byte[32767];
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int read = input.read(buffer);
                if (read < 0) break;
                if (read == 0) {
                    try {
                        Thread.sleep(20L);
                    } catch (InterruptedException stop) {
                        break;
                    }
                }
            }
        } catch (Throwable error) {
            if (!Thread.currentThread().isInterrupted()) {
                Log.w(Guard.TAG, "netblock read ended: " + error.getClass().getSimpleName());
            }
        } finally {
            try {
                input.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void shutdown() {
        Thread clock = beat;
        beat = null;
        if (clock != null) clock.interrupt();
        Thread worker = pump;
        pump = null;
        if (worker != null) worker.interrupt();
        ParcelFileDescriptor descriptor = tunnel;
        tunnel = null;
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (Throwable ignored) {
            }
        }
        NetBlockStore.setRunning(this, false);
    }

    @Override
    public void onRevoke() {
        Log.w(Guard.TAG, "netblock revoked by system");
        shutdown();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    /* ---------- 对外开关 ---------- */

    /** 开：需要先在界面里调 VpnService.prepare() 拿到授权。 */
    public static void start(Context c) {
        try {
            // 不用 startForegroundService：VpnService 不需要常驻通知，
            // 而前台服务必须 5 秒内 startForeground()，否则直接崩。
            c.startService(new Intent(c, NetBlockService.class).setAction(ACTION_START));
        } catch (Throwable error) {
            Log.e(Guard.TAG, "netblock start failed", error);
        }
    }

    public static void stop(Context c) {
        try {
            c.startService(new Intent(c, NetBlockService.class).setAction(ACTION_STOP));
        } catch (Throwable error) {
            Log.w(Guard.TAG, "netblock stop failed: " + error.getClass().getSimpleName());
        }
        NetBlockStore.setRunning(c, false);
    }
}
