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
 * 时段的朝向是「默认常开」：只有 {@link NetWindow} 的断网调整时段内才放开隧道，
 * 其余时间只要开了断网、选了应用，隧道就该一直挂着。判断只有一行，见 {@link #reconcile}。
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

    /** 建不起来的原因码，落到 {@link NetBlockStore} 里给界面照实说。 */
    static final String WHY_PERMISSION = "perm";
    static final String WHY_OTHER_VPN = "vpn";

    private ParcelFileDescriptor tunnel;
    private Thread pump;
    private Thread beat;

    /**
     * 我们的隧道是不是活的（同进程静态标志）。
     *
     * 不能只看 prefs 里的 running + 心跳：Doze 里 beat 线程会被暂停，
     * 心跳过期会被误判成「我们的隧道没了」，进而把正在跑的自己当成别人的 VPN 去让路。
     */
    private static volatile boolean tunnelAlive;

    static boolean tunnelAlive() {
        return tunnelAlive;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tunnel != null) return START_STICKY;      // 已经在跑，不重建
        if (VpnSlot.foreignVpnActive(this)) {         // 代理在用：一步都不动，别把代理顶掉
            Log.i(Guard.TAG, "netblock: proxy owns the VPN slot, service stands down");
            NetBlockStore.setWhy(this, WHY_OTHER_VPN);
            NetBlockStore.setRunning(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (NetWindow.inside(this)) {                 // 调整时段内放开：起服务也只起个空壳
            Log.i(Guard.TAG, "netblock: inside adjust window " + NetWindow.label(this) + ", release");
            NetBlockStore.setRunning(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
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
        // 建隧道前再确认一次：这一下是真正会把代理顶掉的操作，不能有竞态漏网。
        if (VpnSlot.foreignVpnActive(this)) {
            NetBlockStore.setWhy(this, WHY_OTHER_VPN);
            NetBlockStore.setRunning(this, false);
            Log.w(Guard.TAG, "netblock: proxy owns the VPN slot, refuse to establish");
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
            // establish() 只返回 null、不抛异常这条静默路径踩过两次，必须留原因：
            // 要么从没授权（用户没点过系统弹窗 / 覆盖安装后授权丢了），
            // 要么系统同一时间只允许一个 VPN，被代理之类占着。界面直接照这句说。
            String why = VpnService.prepare(this) != null ? WHY_PERMISSION : WHY_OTHER_VPN;
            NetBlockStore.setWhy(this, why);
            NetBlockStore.setRunning(this, false);
            Log.w(Guard.TAG, "netblock: establish returned null, reason=" + why);
            stopSelf();
            return;
        }
        NetBlockStore.setWhy(this, "");
        NetBlockStore.setRunning(this, true);
        tunnelAlive = true;
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
        tunnelAlive = false;
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

    /**
     * 按断网调整时段把隧道拨到正确状态，反复调用是安全的。
     *
     * 方向是「除调整时段外都断」：想要（开了断网、选了应用、不在调整时段）而没在跑就拉起，
     * 反过来（进了调整时段）就收掉。边界跨越靠守护服务的周期 tick 和闹钟兜着，最晚延迟一个节拍。
     *
     * 常开意味着「隧道没了」的每种原因都会走到重连：进程被系统杀过、VPN 授权被撤、
     * establish 失败。连续重试没有意义，退避 60 秒再试。
     */
    public static void reconcile(Context c) {
        // 用户明说「我常开代理」：一步都不碰 VPN 通道，断网交给代理里的拒绝规则。
        if (NetBlockStore.proxyMode(c)) {
            if (NetBlockStore.running(c)) stop(c);
            NetBlockStore.setProxyConflict(c, false);
            NetBlockStore.clearTried(c);
            NetFallback.reconcile(c);
            return;
        }
        boolean cut = NetWindow.shouldCut(NetBlockStore.enabled(c), NetWindow.inside(c));
        boolean foreign = VpnSlot.foreignVpnActive(c);
        boolean conflictNow = standDown(cut, foreign);

        if (conflictNow) {
            // 看得见别人的 VPN：让路，绝不抢 —— establish() 一建就可能把代理顶掉。
            // why 只在「刚进入冲突」时写一次：写多了会把 whyAt 一直刷新，
            // 依赖 whyAt 的兜底冻结判定就永远等不到宽限期。
            if (!NetBlockStore.proxyConflict(c)) {
                NetBlockStore.setProxyConflict(c, true);
                NetBlockStore.setWhy(c, WHY_OTHER_VPN);
                Log.i(Guard.TAG, "netblock: stand down, proxy owns the VPN slot");
            }
            if (NetBlockStore.running(c)) stop(c);
            NetBlockStore.clearTried(c);
        } else {
            if (NetBlockStore.proxyConflict(c)) {
                // 代理刚放掉通道：立刻接管，不再等退避（实测几秒内就能断上）。
                NetBlockStore.setProxyConflict(c, false);
                NetBlockStore.clearTried(c);
                NetBlockStore.setWhy(c, "");
                Log.i(Guard.TAG, "netblock: proxy released the VPN slot, take over now");
            }
            long gap = retryGap(NetBlockStore.proxyConflict(c));
            boolean retryDue = retryDue(System.currentTimeMillis() - NetBlockStore.triedAt(c), gap);
            if (shouldStartTunnel(cut, foreign, NetBlockStore.running(c), retryDue)) start(c);
            else if (!cut && NetBlockStore.running(c)) stop(c);
        }
        // VPN 通道被代理占着时，退到应用层：冻结名单里的应用（可选，默认关）。
        NetFallback.reconcile(c);
    }

    /* ---------- 判定（纯逻辑，离线可测） ---------- */

    /** 该不该给代理让路：想断网、而通道确实被别人的 VPN 占着。 */
    static boolean standDown(boolean cut, boolean foreignVpn) {
        return cut && foreignVpn;
    }

    /** 该不该动手建隧道：想断网、通道没被占、现在没跑、且过了重试退避。 */
    static boolean shouldStartTunnel(boolean cut, boolean foreignVpn, boolean running, boolean retryDue) {
        return cut && !foreignVpn && !running && retryDue;
    }

    /** 退避到点没有。 */
    static boolean retryDue(long sinceLastTryMs, long retryGapMs) {
        return sinceLastTryMs >= retryGapMs;
    }

    /**
     * 退避多长：确实看得见代理占着通道（或刚被占过）就退久一点（10 分钟），
     * 别每 60 秒去跟代理抢一次；其它情况（没授权、残留 VPN 记录、普通失败）60 秒足够，
     * 免得代理一关还要等十分钟才接管。
     */
    static long retryGap(boolean proxyConflict) {
        return proxyConflict ? CONFLICT_RETRY_GAP_MS : RETRY_GAP_MS;
    }

    /** 常规重连退避：隧道起不来时别每 30 秒刷一次日志、白烧电。 */
    private static final long RETRY_GAP_MS = 60_000L;

    /** 通道被占时的重试间隔：拉长到 10 分钟，尽量不去碰代理的 VPN 通道。 */
    private static final long CONFLICT_RETRY_GAP_MS = 10L * 60L * 1000L;

    /** 开：需要先在界面里调 VpnService.prepare() 拿到授权。 */
    public static void start(Context c) {
        // 最后一道保险：代理在用就一步都不动，绝不把用户的代理顶掉。
        if (VpnSlot.foreignVpnActive(c)) {
            NetBlockStore.setWhy(c, WHY_OTHER_VPN);
            Log.i(Guard.TAG, "netblock: refuse to start, proxy owns the VPN slot");
            return;
        }
        NetBlockStore.markTried(c);
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
