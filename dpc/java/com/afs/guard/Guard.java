package com.afs.guard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * 全部判定与落地逻辑都在这里，UI / 定时器 / 开机广播共用一套。
 *
 * 核心：Device Owner 的 setPackagesSuspended() 会把目标 App 直接冻结，
 * 图标变灰、点不开、后台被杀，而且没有「忽略限制」「延长 15 分钟」这种口子。
 *
 * 每个应用有自己的 {@link Rule}（放行星期 + 放行时段），判定一律按包名走。
 * 另有 {@link StudyGate}：今天学习目标没打完 → 直接延长冻结（不看时段，打完即解）。
 */
public final class Guard {

    /* ==================== 可调参数 ==================== */

    /** logcat 标签：adb logcat -s AFS 就能看到闹钟排点/触发 */
    public static final String TAG = "AFS";

    /** 上次打印过的闹钟时刻（守护服务每 30s 重排，避免日志刷屏） */
    private static long lastLoggedAt = -1L;

    /** 默认管哪些包 —— 用户没在「添加应用」里改过就用这个 */
    public static final String[] DEFAULT_TARGETS = new String[] {
            "com.ss.android.ugc.aweme",       // 抖音
            "com.ss.android.ugc.aweme.lite",  // 抖音极速版
            "com.ss.android.ugc.live"         // 抖音火山版
    };

    /** 加固开关。想松一点就改 false 重新编译。 */
    public static final boolean BLOCK_FACTORY_RESET = true;  // 禁止恢复出厂（真正的锁死）
    public static final boolean BLOCK_SAFE_BOOT     = true;  // 禁止安全模式
    public static final boolean BLOCK_APPS_CONTROL  = true;  // 禁止设置里强停 / 卸载 / 清数据
    public static final boolean BLOCK_TIME_CHANGE   = true;  // 禁止改系统时间（否则改个钟就绕过去了）
    public static final boolean BLOCK_UNINSTALL_ALL = false; // 禁止卸载任何应用（比较狠，默认关）

    /** 只要有应用处于冻结，就把浏览器一起冻结（堵短视频网页版）。默认关。 */
    public static final boolean ALSO_SUSPEND_BROWSERS = false;

    private static final String[] BROWSERS = new String[] {
            "com.android.chrome",
            "com.coloros.browser",
            "com.heytap.browser",
            "com.android.browser",
            "com.quark.browser",
            "com.UCMobile"
    };

    /* ==================== 常量 ==================== */

    private static final int ALARM_REQ = 1001;
    private static final int JOB_ID    = 2001;
    private static final long FALLBACK_MS = 15L * 60L * 1000L;

    private Guard() {
    }

    /* ==================== 基础设施 ==================== */

    public static ComponentName admin(Context ctx) {
        return new ComponentName(ctx.getPackageName(), AfsAdmin.class.getName());
    }

    public static DevicePolicyManager dpm(Context ctx) {
        return (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public static boolean isOwner(Context ctx) {
        try {
            DevicePolicyManager d = dpm(ctx);
            return d != null && d.isDeviceOwnerApp(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 判定（按应用） ==================== */

    /**
     * 纯判定：现在是否该冻结 = 不在自己的放行窗口里。
     * 不碰 Context —— 离线回归直接调它。
     * 学习闸门不在这里：它依赖 Context（拉数据），走 blockedNow / apply。
     */
    public static boolean blocked(Rule r, Calendar c) {
        return !r.inWindow(c);
    }

    /**
     * 多个应用里最早的下一次翻转。
     * 闹钟只能排一个时刻，排最早的；响了之后 apply() 会把所有应用重判一遍。
     */
    public static long nextFlipOf(java.util.Collection<Rule> rules, Calendar now) {
        long best = Long.MAX_VALUE;
        for (Rule r : rules) {
            long t = r.nextFlip(now);
            if (t < best) {
                best = t;
            }
        }
        return best == Long.MAX_VALUE ? now.getTimeInMillis() + 3600000L : best;
    }

    /**
     * 这个应用现在是否该冻结。
     * 两条路：① 不在自己的放行窗口里；② 它参与学习闸门、且闸门正在生效。
     */
    public static boolean blockedNow(Context ctx, String pkg) {
        return blockReasonNow(ctx, pkg) != 0;
    }

    /** 0=可使用，1=自身时段，2=学习联动，3=禁忌惩罚。与实际冻结使用同一判定。 */
    public static int blockReasonNow(Context ctx, String pkg) {
        Calendar c = GuardClock.now(ctx);
        Rule r = enforcedRules(ctx).get(pkg);if(r==null)return 0;
        boolean holding = StudyGate.holding(ctx, c);
        boolean taboo = StudyGate.tabooHolding(c.getTimeInMillis(), StudyGate.tabooUntil(ctx));
        return effectiveReason(ctx,pkg,r,RuleStore.keyApps(ctx).contains(pkg),holding,taboo,c,CardStore.rewardUntil(ctx,c.getTimeInMillis())>0);
    }

    static int blockReason(Rule rule, boolean keyApp, boolean holding, boolean taboo, Calendar now) {
        return blockReason(rule,keyApp,holding,taboo,now,false);
    }
    /**
     * 最终判定 = 时段/额度/禁忌这些基础规则，再叠一层钥匙闸门。
     *
     * 拆成两层是故意的：基础规则是纯函数（离线回归 207 项断言全压在上面），
     * 钥匙闸门要读使用记录，只能在带 Context 的这一层追加。reason 6 只在这里出现。
     */
    private static int effectiveReason(Context c,String pkg,Rule rule,boolean key,boolean hold,boolean taboo,Calendar now,boolean reward){
        int base=effectiveBase(c,pkg,rule,key,hold,taboo,now,reward);
        if(base!=0)return base;
        return KeyGate.blocks(c,pkg,now.getTimeInMillis())?6:0;
    }
    private static int effectiveBase(Context c,String pkg,Rule rule,boolean key,boolean hold,boolean taboo,Calendar now,boolean reward){
        if(DailyQuotaStore.exceeded(c,pkg))return 5;
        key=key||!rule.gate;
        hold=hold&&StudyGate.learningFor(c,rule,now);
        taboo=PenaltyStore.holding(c,pkg,now.getTimeInMillis());
        if(AppUsageQuota.minutes(c,pkg)==0&&!DailyQuotaStore.hasAllowance(c,pkg))return blockReason(rule,key,hold,taboo,now,reward);
        int base=blockReason(FreezeSlotStore.withoutSchedule(),key,hold,taboo,now,false);
        if(base!=0)return base;return AppUsageQuota.exceeded(c,pkg,rule,now.getTimeInMillis())?4:0;
    }

    static int blockReason(Rule rule, boolean keyApp, boolean holding, boolean taboo, Calendar now, boolean reward) {
        if (blocked(rule, now) && !reward) return 1;
        if (keyApp) return 0;
        if (taboo) return 3;
        return holding ? 2 : 0;
    }

    public static String blockReasonText(int reason) {
        return reason == 1 ? T.s("时段冻结") : reason == 2 ? T.s("学习联动冻结") : reason == 3 ? T.s("禁忌延长冻结") : reason==4?T.s("使用额度冻结"):reason==5?T.s("每日限额用尽"):reason==6?T.s("钥匙应用未达标"):T.s("可使用");
    }

    /** 最近一次「额度用完请出去」的对象与时刻：同一次使用只拉一次提示页。 */
    private static long lastQuotaKickAt;
    private static String lastQuotaKickPkg;

    /**
     * 额度用尽时把用户请出正在用的那只应用。
     *
     * setPackagesSuspended 只挡「下一次启动」，已经在前台的实例照样能刷，
     * 所以额度一到点就主动把提示页拉到最前，当场结束这次使用。
     * 设备所有者不受后台启动 Activity 的限制，这条路不依赖无障碍服务。
     */
    static void kickQuotaExceeded(Context ctx) {
        try {
            android.os.PowerManager power = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (power != null && !power.isInteractive()) return;   // 灭屏时不必抢前台
            long now = GuardClock.now(ctx).getTimeInMillis();
            String pkg = AppUsageQuota.foreground(ctx, now);
            if (pkg == null || pkg.equals(ctx.getPackageName())) return;
            int reason = blockReasonNow(ctx, pkg);
            if (reason != 4 && reason != 5) return;
            if (pkg.equals(lastQuotaKickPkg) && now - lastQuotaKickAt < 8000L) return;
            lastQuotaKickPkg = pkg; lastQuotaKickAt = now;
            ctx.startActivity(new Intent(ctx, QuotaBlockedActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(QuotaBlockedActivity.EXTRA_PACKAGE, pkg));
        } catch (RuntimeException error) {
            Log.w(TAG, "quota kick failed: " + error.getClass().getSimpleName());
        }
    }
    private static Map<String,Rule> enforcedRules(Context c){Map<String,Rule> rules=RuleStore.all(c);for(String pkg:DailyQuotaStore.packages(c))if(!rules.containsKey(pkg))rules.put(pkg,FreezeSlotStore.withoutSchedule());return rules;}

    /** 这个应用现在是否在自己的放行窗口里（不看学习闸门）。 */
    public static boolean inWindowNow(Context ctx, String pkg) {
        return RuleStore.get(ctx, pkg).inWindow(GuardClock.now(ctx));
    }

    /**
     * 这个应用现在能不能改设置。
     *
     * ★ 只看它自己的窗口，不看学习闸门 —— 否则闸门一生效就永远"锁定"，
     * 连放行时段也改不了，自己把自己锁死。
     */
    public static boolean lockedNow(Context ctx, String pkg) {
        if (Config.inGrace(ctx)) {
            return false;
        }
        // 刚加进来的 10 分钟里允许改时段，否则上午加应用、当场锁死到 21 点
        if (RuleStore.isFresh(ctx, pkg)) {
            return false;
        }
        if(DailyQuotaStore.hasAllowance(ctx,pkg))return DailyQuotaStore.exceeded(ctx,pkg)||AppUsageQuota.exceeded(ctx,pkg,RuleStore.get(ctx,pkg),GuardClock.now(ctx).getTimeInMillis());
        if(AppUsageQuota.minutes(ctx,pkg)>0)return AppUsageQuota.exceeded(ctx,pkg,RuleStore.get(ctx,pkg),GuardClock.now(ctx).getTimeInMillis());
        return !inWindowNow(ctx, pkg);
    }

    /**
     * 能不能把它移出黑名单。
     *
     * ★ 黑名单是「只进不出」的：已列的应用永远不能移除——否则"放行时段里
     * 先移出、再趁重新加入时把时段改宽"就是一条绕过路径。
     * 唯一例外：刚加进来 10 分钟内（typo 逃生口，包名输错还能删）；
     * 首次安装的全局宽限同样保留（初始配置阶段）。
     */
    public static boolean canRemove(Context ctx, String pkg) {
        return Config.inGrace(ctx) || RuleStore.isFresh(ctx, pkg);
    }

    /** 有没有应用正处在放行窗口（决定强制模式能不能关）。 */
    public static boolean anyUnlocked(Context ctx) {
        Calendar now = GuardClock.now(ctx);
        for (Map.Entry<String,Rule> entry : enforcedRules(ctx).entrySet()) {
            if(DailyQuotaStore.exceeded(ctx,entry.getKey()))continue;
            Rule r=entry.getValue();
            if ((DailyQuotaStore.hasAllowance(ctx,entry.getKey())||r.inWindow(now)||AppUsageQuota.minutes(ctx,entry.getKey())>0)&&!AppUsageQuota.exceeded(ctx,entry.getKey(),r,now.getTimeInMillis())) {
                return true;
            }
        }
        return false;
    }

    /** 全部应用都处于屏蔽 → 整页切深色。 */
    public static boolean allBlocked(Context ctx) {
        return !anyUnlocked(ctx);
    }

    public static int blockedCount(Context ctx) {
        Calendar now = GuardClock.now(ctx);
        // 必须把学习闸门算进去，否则状态卡显示"5 个冻结"、下面结果却是"12 个冻结"
        boolean gate = StudyGate.holding(ctx, now);
        java.util.Set<String> keys = RuleStore.keyApps(ctx);
        PackageManager pm = ctx.getPackageManager();
        int n = 0;
        for (Map.Entry<String, Rule> e : enforcedRules(ctx).entrySet()) {
            // 与 apply() 的统计口径一致：没装的应用不算"冻结中"
            if (!installed(pm, e.getKey())) {
                continue;
            }
            Rule r = e.getValue();
            if (blockReasonNow(ctx,e.getKey())!=0) {
                n++;
            }
        }
        return n;
    }

    /**
     * 下一次有应用要翻转状态的时刻 —— 取所有应用里最早的那个。
     *
     * 每个应用各有各的窗口，闹钟只能排最早的一个边界；响了之后
     * apply() 会把所有应用重新判一遍，并重新排下一次。
     *
     * 学习联动还需要在 20:00 检查，并在次日零点清除前一天的延长冻结。
     */
    public static long nextFlipMillis(Context ctx) {
        Calendar now = GuardClock.now(ctx);
        long next=Math.min(Math.min(nextFlipOf(RuleStore.all(ctx).values(), now),StudyGate.nextCheckBoundaryMillis(now)),PenaltyStore.boundary(ctx,now.getTimeInMillis()));
        long reward=CardStore.rewardUntil(ctx,now.getTimeInMillis());
        return reward>0?Math.min(next,reward):next;
    }

    /* ==================== 落地 ==================== */

    /**
     * 按当前时间把每个目标 App 单独冻结/解冻，并补齐各项限制。
     * 幂等：反复调用没有副作用。
     */
    public static String apply(Context ctx) {
        try{DailyQuotaStore.refresh(ctx);}catch(IllegalStateException error){Log.e(TAG,"daily quota state unavailable",error);}
        DailyCardCompletion.capture(ctx);
        DevicePolicyManager d = dpm(ctx);
        if (d == null) {
            return T.s("拿不到 DevicePolicyManager");
        }
        if (!isOwner(ctx)) {
            return T.s("尚未成为设备所有者，什么都没做。\n请先按 README 用 adb 执行 set-device-owner。");
        }

        // 顺手触发学习闸门的数据刷新（内部节流 10 分钟 + 后台线程，绝不阻塞）
        StudyGate.refreshIfNeeded(ctx);

        ComponentName a = admin(ctx);
        applyRestrictions(ctx, d, a);

        java.util.Set<String> scheduled=RuleStore.packages(ctx);
        Map<String, Rule> rules = enforcedRules(ctx);
        Calendar now = GuardClock.now(ctx);
        // 学习闸门：整轮判一次。true = 今天目标没打完且此刻在延长区间里
        boolean gateHold = StudyGate.holding(ctx, now);
        java.util.Set<String> keys = RuleStore.keyApps(ctx);
        boolean taboo=StudyGate.tabooHolding(now.getTimeInMillis(),StudyGate.tabooUntil(ctx));
        boolean eligible=false;
        PackageManager pm=ctx.getPackageManager();
        for(Map.Entry<String,Rule> e:rules.entrySet()){
            if(installed(pm,e.getKey())&&blocked(e.getValue(),now)
                    && effectiveReason(ctx,e.getKey(),e.getValue(),keys.contains(e.getKey()),gateHold,taboo,now,true)==0){eligible=true;break;}
        }
        long rewardBoundary=Math.min(nextFlipOf(rules.values(),now),StudyGate.nextCheckBoundaryMillis(now));
        boolean reward=false;
        try{reward=CardStore.reconcile(ctx,now.getTimeInMillis(),eligible,rewardBoundary);}
        catch(RuntimeException error){Log.w(TAG,"reward accounting: "+error.getClass().getSimpleName());}
        boolean anyBlocked = false;
        for (Map.Entry<String, Rule> entry : rules.entrySet()) {
            if (effectiveReason(ctx,entry.getKey(),entry.getValue(),keys.contains(entry.getKey()),gateHold,taboo,now,reward)!=0) {
                anyBlocked = true;
                break;
            }
        }

        // 学习闸门开启且今日未完成时，系统级灰度覆盖桌面和所有应用。
        // 打卡完成即恢复彩色。需要 WRITE_SECURE_SETTINGS 权限。
        int st = StudyGate.cardState(ctx, now);
        GrayMode.apply(ctx, !MainActivity.isHomeVisible()
                && (st == StudyGate.ST_ACTIVE || st == StudyGate.ST_PENDING));

        StringBuilder sb = new StringBuilder();
        int frozen = 0;
        boolean rewardUsed=false;

        // 被移出列表的必须先解冻，否则它会永远停在被冻结的状态
        for (String p : RuleStore.applied(ctx)) {
            if (!rules.containsKey(p)) {
                try {
                    setSuspended(d, a, new String[] { p }, false);
                    setUninstall(d, a, p, false);
                } catch (Throwable ignored) {
                }
            }
        }

        for (Map.Entry<String, Rule> e : rules.entrySet()) {
            String pkg = e.getKey();
            Rule r = e.getValue();
            if (!installed(pm, pkg)) {
                sb.append("- ").append(pkg).append(T.s("：未安装\n"));
                continue;
            }
            // 只有关键应用不受学习闸门牵连。
            boolean gateBlock = gateHold && !keys.contains(pkg);
            boolean block = effectiveReason(ctx,pkg,r,keys.contains(pkg),gateHold,taboo,now,reward)!=0;
            try {
                setUninstall(d, a, pkg, true);
            } catch (Throwable ignored) {
            }
            String state;
            try {
                String[] failed=setSuspended(d, a, new String[] { pkg }, block);
                if(reward&&!block&&blocked(r,now)&&(failed==null||failed.length==0))rewardUsed=true;
                state = block
                        ? (gateBlock && !blocked(r, now) ? T.s("冻结（学习闸门）") : T.s("已冻结"))
                        : T.s("放行中");
            } catch (Throwable t) {
                state = T.s("冻结失败：") + t.getClass().getSimpleName();
            }
            if (block) {
                frozen++;
            }
            // 应用名给人看，包名方便核对是哪个版本，后面带它自己的放行时段
            sb.append("- ").append(labelOf(pm, pkg)).append(T.s("（")).append(pkg).append(T.s("）："))
                    .append(state).append(" · ").append(r.freezeSummary())
                    .append(keys.contains(pkg) ? T.s(" · 关键应用") : scheduled.contains(pkg)?T.s(" · 参与学习闸门"):T.s(" · 每日限额 · 参与学习闸门")).append('\n');
        }
        RuleStore.setApplied(ctx, rules.keySet());
        if(reward&&!rewardUsed){try{CardStore.reconcile(ctx,now.getTimeInMillis(),false,rewardBoundary);}catch(RuntimeException ignored){ }reward=false;}

        if (ALSO_SUSPEND_BROWSERS) {
            for (int i = 0; i < BROWSERS.length; i++) {
                if (!installed(pm, BROWSERS[i])) {
                    continue;
                }
                try {
                    setSuspended(d, a, new String[] { BROWSERS[i] }, anyBlocked);
                    if (anyBlocked) {
                        sb.append("- ").append(BROWSERS[i]).append(T.s("：已冻结（堵网页版）\n"));
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // ★ 每次 apply 都重排一次定时。package 覆盖安装会清掉所有 alarm/job，
        // 而 schedule() 原本只在开机/闹钟/Job 回调里调用 —— 装完就再也排不上了。
        schedule(ctx);

        String head = T.s("结果：") + rules.size() + T.s(" 个应用，") + frozen + T.s(" 个冻结")
                + (gateHold ? T.s("（学习闸门生效中）") : "") + (reward?T.s(" · 热爱奖励减免中"):"") + "\n";
        String result = head + sb.toString();
        ctx.getSharedPreferences("afs_status", Context.MODE_PRIVATE).edit()
                .putString("last_result", result)
                .putLong("last_at", System.currentTimeMillis()).apply();
        return result;
    }

    static final Object POLICY_LOCK = new Object();
    private static String[] setSuspended(DevicePolicyManager d, ComponentName a, String[] packages, boolean value) {
        synchronized (POLICY_LOCK) { return d.setPackagesSuspended(a,packages,value); }
    }
    private static void setUninstall(DevicePolicyManager d, ComponentName a, String pkg, boolean value) {
        synchronized (POLICY_LOCK) { d.setUninstallBlocked(a,pkg,value); }
    }
    private static void applyRestrictions(Context ctx, DevicePolicyManager d, ComponentName a) {
        synchronized (POLICY_LOCK) { applyRestrictionsUnlocked(ctx,d,a); }
    }
    private static void applyRestrictionsUnlocked(Context ctx, DevicePolicyManager d, ComponentName a) {
        add(d, a, BLOCK_FACTORY_RESET ? UserManager.DISALLOW_FACTORY_RESET : null);
        add(d, a, BLOCK_SAFE_BOOT     ? UserManager.DISALLOW_SAFE_BOOT     : null);
        add(d, a, BLOCK_APPS_CONTROL  ? UserManager.DISALLOW_APPS_CONTROL  : null);
        if (BLOCK_TIME_CHANGE) {
            if (Build.VERSION.SDK_INT >= 28) {
                add(d, a, UserManager.DISALLOW_CONFIG_DATE_TIME);
            } else {
                // DISALLOW_CONFIG_DATE_TIME was added in API 28. On Android 7/8
                // require network time and pin the schedule timezone in GuardClock.
                try { d.setAutoTimeRequired(a, true); }
                catch (RuntimeException e) { Log.e(TAG, "Cannot enforce automatic time", e); }
            }
        }
        add(d, a, BLOCK_UNINSTALL_ALL ? UserManager.DISALLOW_UNINSTALL_APPS : null);
        try {
            d.setUninstallBlocked(a, ctx.getPackageName(), true);
        } catch (Throwable ignored) {
        }
        // 设备所有者可以直接给自己发精确闹钟权限，边界时刻才准
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                d.setPermissionGrantState(a, ctx.getPackageName(),
                        "android.permission.SCHEDULE_EXACT_ALARM",
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Throwable ignored) {
            }
        }
        // ★ Android 13+ 的通知是运行时权限，没授就 importance=NONE —— 常驻通知
        //   （也就是"今天目标没完成"的唯一提醒渠道）根本不会显示。
        //   DO 可以代授，用户不用去设置里点。
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                d.setPermissionGrantState(a, ctx.getPackageName(),
                        "android.permission.POST_NOTIFICATIONS",
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void add(DevicePolicyManager d, ComponentName a, String restriction) {
        if (restriction == null) {
            return;
        }
        try {
            d.addUserRestriction(a, restriction);
        } catch (Throwable ignored) {
        }
    }

    /** 应用名；取不到就退回包名。 */
    static String labelOf(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
        } catch (Throwable t) {
            return pkg;
        }
    }

    /**
     * 规则的展示文案。
     *
     * ★ 必须写明"放行"两个字 —— 光写 "每天 22:00-23:00"，谁都会读成
     *   "每天 22:00-23:00 冻结"，而实际语义正好相反：这段是唯一能用的时间，
     *   其余 23 小时全冻结。这是最容易误读的地方。
     */
    static String allowText(Rule r) {
        return r.freezeSummary() + (r.gate ? "" : T.s(" · 不受学习闸门影响"));
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ==================== 定时 ==================== */

    /**
     * 保证常驻守护在跑。
     *
     * ColorOS 的待机分组会把 AlarmManager 的闹钟整体往后推（实测被推 2 天 23 小时），
     * 光靠闹钟"到点自动生效"在这台机器上不成立 —— 前台服务是唯一稳定的节拍源。
     */
    public static void ensureService(Context ctx) {
        try {
            Intent i = new Intent(ctx, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            Log.i(TAG, "ensureService ok");
        } catch (Throwable t) {
            Log.e(TAG, "ensureService failed: " + t);
        }
    }

    /**
     * 排闹钟 + 15 分钟 JobScheduler 兜底。
     *
     * ★ 双路排点，两个 PendingIntent 都指向 AlarmReceiver（apply 是幂等的）：
     *   1) setAlarmClock —— 系统闹钟级，ColorOS 的 OplusAlarmAdjustment 一般不压
     *   2) setExactAndAllowWhileIdle —— Doze 里也允许唤醒（每分钟最多一次）
     * 只排一路不够：实测闹钟被子系统的待机策略整体推迟（adjustment 加了 2 天 23 小时），
     * 前台服务的 30 秒 tick 在休眠时也不跑（Doze 冻结主线程定时器）。两路 + Job 一起上。
     */
    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            long at = nextFlipMillis(ctx);
            PendingIntent piClock = alarms(ctx, ALARM_REQ);
            PendingIntent piIdle = alarms(ctx, ALARM_REQ + 1);
            // Android 12+ 精确闹钟受 appop 控制：没授权时 setAlarmClock 直接抛 SecurityException，
            // 以前这里 catch 掉就静默失败 —— 结果闹钟永远不存在，只有手动打开 App 才冻得上。
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    exact = am.canScheduleExactAlarms();
                } catch (Throwable ignored) {
                }
            }
            boolean okClock = false;
            boolean okIdle = false;
            try {
                if (exact) {
                    am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, piClock), piClock);
                    okClock = true;
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, piClock);
                    okClock = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "setAlarmClock failed: " + t);
            }
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, piIdle);
                okIdle = true;
            } catch (Throwable t) {
                Log.e(TAG, "setExactAndAllowWhileIdle failed: " + t);
            }
            if (!okClock && !okIdle) {
                try {
                    am.set(AlarmManager.RTC_WAKEUP, at, piClock);
                } catch (Throwable ignored) {
                }
            }
            if (at != lastLoggedAt) {   // 守护服务每 30s 会重排一次，别刷屏
                lastLoggedAt = at;
                Log.i(TAG, "alarm armed at " + at + " (clock=" + okClock + " idle=" + okIdle + ")");
            }
        }

        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) {
            return;
        }
        try {
            List<JobInfo> pending = js.getAllPendingJobs();
            for (int i = 0; i < pending.size(); i++) {
                if (pending.get(i).getId() == JOB_ID) {
                    return;
                }
            }
            JobInfo ji = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, GuardJob.class))
                    .setPeriodic(FALLBACK_MS)
                    .setPersisted(true)
                    .build();
            js.schedule(ji);
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent alarms(Context ctx, int req) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        return PendingIntent.getBroadcast(
                ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* ==================== 展示 ==================== */

    public static String describe(Context ctx) {
        Calendar now = GuardClock.now(ctx);
        int total = RuleStore.count(ctx);
        int frozen = blockedCount(ctx);

        StringBuilder sb = new StringBuilder();
        sb.append(T.s("设备所有者　")).append(isOwner(ctx) ? T.s("是（已锁死）") : T.s("否 —— 冻结不会生效")).append('\n');
        sb.append(T.s("当前时间　")).append(stamp(now.getTimeInMillis())).append('\n');
        sb.append(T.s("应用　　　")).append(total).append(T.s(" 个，")).append(frozen).append(T.s(" 个冻结中")).append('\n');
        // 关键应用名单：用户自己列的，学习目标没完成时只按自己的时段走。
        // 不显示这一行的话，用户没法确认"我列的那几个到底生效了没有"。
        int keys = RuleStore.keyApps(ctx).size();
        sb.append(T.s("关键应用　")).append(keys).append(T.s(" 个（学习目标未完成也不延长冻结）")).append('\n');
        // 学习联动不在这里 —— 它现在并进首页的「打卡」按钮了（styleCheckin：
        // 后缀带「冻结中 / ⚠ 联动过期 / 等记账」），埋在这个同色同字号的
        // 文本块里没人看得见。
        sb.append(T.s("下次切换　")).append(total == 0 ? T.s("—（没添加应用）") : stamp(nextFlipMillis(ctx))).append('\n');
        sb.append('\n');
        sb.append(T.s("加固项　")).append(restrictionSummary()).append('\n');
        return sb.toString();
    }

    private static String restrictionSummary() {
        java.util.List<String> items = new java.util.ArrayList<String>();
        if (BLOCK_FACTORY_RESET) items.add(T.s("禁恢复出厂"));
        if (BLOCK_SAFE_BOOT)     items.add(T.s("禁安全模式"));
        if (BLOCK_APPS_CONTROL)  items.add(T.s("禁强停/清数据"));
        if (BLOCK_TIME_CHANGE)   items.add(T.s("禁改时间"));
        if (BLOCK_UNINSTALL_ALL) items.add(T.s("禁卸载"));
        if (items.isEmpty()) {
            return T.s("无");
        }
        // 每两项一行。(char)10 = 换行, (char)0x3000 = 全角空格
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(i % 2 == 0 ? (char) 10 : (char) 0x3000);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    public static String stamp(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        int[] wd = { 0, 7, 1, 2, 3, 4, 5, 6 };
        String[] names = { "", T.s("周一"), T.s("周二"), T.s("周三"), T.s("周四"), T.s("周五"), T.s("周六"), T.s("周日") };
        StringBuilder sb = new StringBuilder();
        sb.append(c.get(Calendar.MONTH) + 1).append('/')
          .append(c.get(Calendar.DAY_OF_MONTH)).append(' ')
          .append(pad2(c.get(Calendar.HOUR_OF_DAY))).append(':')
          .append(pad2(c.get(Calendar.MINUTE))).append('（')
          .append(names[wd[c.get(Calendar.DAY_OF_WEEK)]]).append('）');
        return sb.toString();
    }

    private static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
