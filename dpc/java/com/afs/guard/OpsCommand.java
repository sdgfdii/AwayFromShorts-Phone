package com.afs.guard;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 运维指令通道：从 {@code files/afs-command.json} 读一条指令，执行完把文件改名成 .applied。
 *
 * 为什么需要它：守卫是常驻的，配置全在 SharedPreferences 里，而 SharedPreferences 在
 * 进程内是一份缓存 —— 外部直接改 XML 文件不会被重读，必须重启进程。可这台机器上
 * {@code am force-stop} / {@code pm disable-user} / {@code am stop-app} 对守卫全部无效
 * （它是 active device admin，系统护着），于是「改一个数字」变成了必须先发一次版才能做的事。
 *
 * 普通文件不受这个缓存约束：{@code getFilesDir()} 下的文件每次都是真读盘。所以把要改的
 * 数值写进这个文件，节拍里发现就执行，改的是同一批 SP，走的是同一个写入路径 ——
 * 不用杀进程，也不用为一次配置调整发一个版本。
 *
 * 文件格式（缺哪项就跳过哪项）：
 * <pre>
 * {
 *   "quotaMinutes":  { "&lt;额度格子 id&gt;": 120 },
 *   "freezeWindows": { "com.example.app": [[0, 1290], [1380, 0]] }
 * }
 * </pre>
 * 时段单位是「从零点起的分钟数」，与 Rule 的 windows 完全一致：{@code [start,end]}，
 * {@code start > end} 表示跨午夜，{@code start == end} 表示全天。
 *
 * 刻意不做的事：不校验「是不是在调整时段内」。这条通道是给维护者用的，
 * 不是给应用内操作开的后门 —— 界面上该拦的仍然拦。
 */
final class OpsCommand {

    private static final String TAG = "AfsOps";
    private static final String FILE = "afs-command.json";
    /** 4 秒一问：和额度节拍同量级，够快又不会每 1 秒 stat 一次。 */
    private static final long MIN_GAP_MS = 4000L;

    private static long lastCheck;

    private OpsCommand() { }

    /** 节拍里调用；没有指令文件时只是一次 exists()，开销可以忽略。 */
    static void tick(Context c) {
        long now = System.currentTimeMillis();
        synchronized (OpsCommand.class) {
            if (now - lastCheck < MIN_GAP_MS) return;
            lastCheck = now;
        }
        File file = new File(c.getFilesDir(), FILE);
        if (!file.isFile()) return;
        String raw;
        try {
            raw = read(file);
        } catch (Exception error) {
            Log.w(TAG, "read failed: " + error);
            return;
        }
        JSONObject command;
        try {
            command = new JSONObject(raw);
        } catch (Exception error) {
            // 坏 JSON 留着只会每 4 秒报一次同样的错；改名存档，人不至于看不到它
            Log.w(TAG, "malformed command: " + error);
            file.renameTo(new File(c.getFilesDir(), FILE + ".bad"));
            return;
        }
        try {
            applyQuotaMinutes(c, command.optJSONObject("quotaMinutes"));
            applyFreezeWindows(c, command.optJSONObject("freezeWindows"));
            Guard.apply(c);
            Log.i(TAG, "command applied");
        } catch (Exception error) {
            Log.w(TAG, "apply failed: " + error);
        }
        file.renameTo(new File(c.getFilesDir(), FILE + ".applied"));
    }

    /**
     * 直接改额度格子的分钟数。
     *
     * 走 {@link DailyQuotaStore#syncSharedMinutes}：那条路专门为「状态对齐」准备，
     * 不会被「调整时段外只能加不能减」挡住 —— 这正是本通道存在的理由。
     */
    private static void applyQuotaMinutes(Context c, JSONObject spec) throws Exception {
        if (spec == null) return;
        Iterator<String> keys = spec.keys();
        while (keys.hasNext()) {
            String groupId = keys.next();
            int minutes = spec.optInt(groupId, 0);
            if (groupId.isEmpty() || minutes < 1 || minutes > 1440) continue;
            DailyQuotaStore.syncSharedMinutes(c, groupId, minutes);
        }
    }

    /**
     * 改一批应用的冻结时段。
     *
     * 只换时段，天数、闸门、freeze 标记全部沿用原规则 —— 这条通道不该顺手改别的东西。
     * 换完让 FreezeSlotStore 自己补出新时段的格子，再把因此变空的旧格子清掉，
     * 否则冻结页上会留一个没有成员的壳。
     */
    private static void applyFreezeWindows(Context c, JSONObject spec) {
        if (spec == null) return;
        Map<String, Rule> rules = RuleStore.all(c);
        Set<Rule> touched = new LinkedHashSet<>();
        Iterator<String> keys = spec.keys();
        while (keys.hasNext()) {
            String pkg = keys.next();
            JSONArray windows = spec.optJSONArray(pkg);
            if (pkg.isEmpty() || windows == null || windows.length() == 0) continue;
            Rule old = rules.get(pkg);
            if (old == null) continue;
            // 旧时段先记一笔：改完之后要拿它去清空壳格子
            Rule normalized = old.freezeEditorRule();
            touched.add(normalized == null ? old : normalized);
            int[][] parsed = new int[windows.length()][2];
            for (int i = 0; i < windows.length(); i++) {
                JSONArray pair = windows.optJSONArray(i);
                if (pair == null) { parsed[i][0] = parsed[i][1] = 0; continue; }
                parsed[i][0] = pair.optInt(0);
                parsed[i][1] = pair.optInt(1);
            }
            RuleStore.put(c, pkg, new Rule(old.days, parsed, old.gate, old.freeze));
        }
        if (touched.isEmpty()) return;
        FreezeSlotStore.all(c);
        for (Rule rule : touched) FreezeSlotStore.pruneEmpty(c, rule);
    }

    private static String read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }
}
