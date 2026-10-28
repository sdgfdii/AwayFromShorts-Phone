package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;

/** A sync transaction pins its destination and credential until close(). */
final class SyncConfig {
    static final Object LOCK = new Object();
    private static int active;
    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("afs_sync", Context.MODE_PRIVATE);
    }
    static SyncTarget target(Context c) {
        synchronized (LOCK) {
            try { return SyncTarget.create(prefs(c).getString("owner", ""), prefs(c).getString("gist", "")); }
            catch (IllegalArgumentException e) { return null; }
        }
    }
    static boolean configured(Context c) { return target(c) != null && !c.getSharedPreferences("afs_data_status",0).getBoolean("pause_legacy_sync",false); }
    static String cacheName(Context c, String base) {
        return base + "_sync_" + prefs(c).getString("cache_epoch", "none");
    }

    static final class Session implements AutoCloseable {
        final SyncTarget target;
        final String token;
        private final String epoch;
        private boolean closed;
        private Session(SyncTarget t, String token, String epoch) { this.target = t; this.token = token; this.epoch = epoch; }
        boolean isCurrent(Context c) {
            synchronized (LOCK) { return epoch.equals(prefs(c).getString("cache_epoch", "none")); }
        }
        public void close() {
            synchronized (LOCK) { if (!closed) { closed = true; active--; } }
        }
    }
    static Session acquire(Context c) {
        synchronized (LOCK) {
            if (!configured(c)) throw new IllegalStateException("尚未启用同步或本地恢复后已暂停同步");
            SyncTarget t = target(c);
            if (t == null) throw new IllegalStateException("尚未配置同步");
            String token = prefs(c).getString("token", "");
            if (token.isEmpty()) throw new IllegalStateException("尚未配置同步凭据");
            active++;
            return new Session(t, token, prefs(c).getString("cache_epoch", "none"));
        }
    }

    /** Called after explicit user action. No change is permitted mid-sync or mid-freeze. */
    static void save(Context c, SyncTarget target, String token) {
        if (target != null && (token == null || token.trim().isEmpty()))
            throw new IllegalArgumentException("请填写 GitHub token");
        // 从"没有同步"到"配置好同步"是首次接入 / 凭据丢失后的恢复，不能拿冻结时段卡死：
        // 否则一天里只有放行那几十分钟能配，打卡台账只能一直躺在手机里。
        // 只换 token（Gist 还是那个）同理。
        // 反过来"换成别的 Gist"或"停用同步"仍然受冻结限制 —— 那才是绕开守卫的口子。
        SyncTarget old = target(c);
        boolean destinationKept = target != null
                && (old == null
                    || (old.gistId.equalsIgnoreCase(target.gistId)
                        && old.owner.equalsIgnoreCase(target.owner)));
        // Do not acquire store locks while holding LOCK: a local edit may mark
        // the hub dirty from inside those stores.
        if (!Config.inGrace(c) && !destinationKept) {
                Calendar now = GuardClock.now(c);
                if (StudyGate.holding(c, now)) throw new IllegalStateException("学习冻结期间不能修改同步");
                for (Rule rule : RuleStore.all(c).values())
                    if (Guard.blocked(rule, now)) throw new IllegalStateException("冻结时段内不能修改同步");
        }
        synchronized (LOCK) {
            if (active != 0) throw new IllegalStateException("同步正在执行，请结束后重试");
            // Guard's read above may start a background request. acquire() uses this
            // same monitor, so it cannot pin the old destination after this commit.
            boolean changed = old == null || target == null
                    || !old.gistId.equals(target.gistId) || !old.owner.equalsIgnoreCase(target.owner);
            SharedPreferences.Editor e = prefs(c).edit().clear();
            e.putString("cache_epoch", changed ? java.util.UUID.randomUUID().toString()
                    : prefs(c).getString("cache_epoch", "none"));
            if (target != null) e.putString("owner", target.owner).putString("gist", target.gistId)
                    .putString("token", token.trim());
            if (!e.commit()) throw new IllegalStateException("同步配置保存失败");
            c.getSharedPreferences("afs_data_status",0).edit().putBoolean("pause_legacy_sync",false).commit();
            if (changed) {
                // Only remote caches are cleared. Local goals, check-ins, cards,
                // rule schedules and the initial grace timestamp remain intact.
                StudyGate.resetRemote(c);
                PcStats.resetRemote(c);
            }
        }
    }
}
