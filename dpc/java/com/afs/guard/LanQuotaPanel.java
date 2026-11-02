package com.afs.guard;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 跨设备共享额度的配置面板。
 *
 * 单独成一个类是因为入口有两个：设置页的「跨设备 → 共享额度」，以及额度页
 * 那条被共享的格子自身。两边必须是同一份界面 —— 否则用户在两个地方看到
 * 不同的字段、不同的状态描述，只会以为功能坏了。
 *
 * 面板本身不决定共享哪条格子（那是额度页 / 格子上的按钮干的事），
 * 它只管连接：开关、电脑地址、令牌、绑定目标、测试连接，以及两端的明细。
 */
final class LanQuotaPanel {

    private LanQuotaPanel() {
    }

    /**
     * @param after 面板关闭后要刷新的页面（额度页 / 设置页），可为 null
     */
    static void show(final ThemedActivity host, final Runnable after) {
        if (host == null || host.isFinishing()) {
            return;
        }
        final Ui ui = new Ui(host);
        final Handler main = new Handler(Looper.getMainLooper());

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(2), ui.dp(20), ui.dp(2));

        final AlertDialog dialog = ThemeStore.dialog(host)
                .setTitle(Lang.t(host, "跨设备共享额度", "Shared quota"))
                .setView(box)
                .setNegativeButton(T.s("关闭"), null)
                .create();

        final TextView switchValue = value(host, ui);
        final TextView serverValue = value(host, ui);
        final TextView tokenValue = value(host, ui);
        final TextView groupValue = value(host, ui);
        final TextView state = new TextView(host);
        state.setTextSize(11);
        state.setTextColor(ui.text3());
        state.setPadding(0, ui.dp(10), 0, ui.dp(2));
        // 两端的明细单独一块：这是「谁花了这笔时间」的答案，比一句剩多少有用得多
        final TextView detail = new TextView(host);
        detail.setTextSize(11);
        detail.setTextColor(ui.text3());
        detail.setPadding(0, ui.dp(6), 0, ui.dp(2));

        final Runnable paint = () -> {
            boolean on = LanQuota.enabled(host);
            switchValue.setText(on ? T.s("开") : T.s("关"));
            switchValue.setTextColor(on ? ui.accent() : ui.text3());
            serverValue.setText(LanQuota.host(host).isEmpty()
                    ? T.s("未设置") : LanQuota.host(host) + ":" + LanQuota.port(host));
            tokenValue.setText(LanQuota.token(host).isEmpty() ? T.s("未设置") : maskToken(LanQuota.token(host)));
            String name = LanQuota.groupName(host);
            groupValue.setText(LanQuota.group(host).isEmpty()
                    ? T.s("未选择") : (name.isEmpty() ? T.s("已选择") : name));
            if (!LanQuota.active(host)) {
                state.setText(T.s("填好电脑地址和令牌，再绑定一个额度格子，这个功能才会开始工作。"));
            } else if (!LanQuota.fresh(host)) {
                String err = LanQuota.lastError(host);
                state.setText(err.isEmpty()
                        ? T.s("还没有连上过电脑。手机和电脑要在同一个 WiFi 下。")
                        : T.f("连不上电脑：%s", err));
            } else if ("gist".equals(LanQuota.source(host))) {
                state.setText(LanQuota.summary(host) + T.s(" · 局域网不通，用的是 Gist 上的旧账，回到家里 WiFi 会自动恢复准确"));
            } else {
                state.setText(LanQuota.summary(host) + T.s(" · 两端明细见下"));
            }
            detail.setText(detailText(host));
        };

        box.addView(row(host, ui, T.s("启用"), switchValue, () -> {
            boolean next = !LanQuota.enabled(host);
            LanQuota.setEnabled(host, next);
            if (!next) {
                LanQuota.clearCache(host);
            }
            paint.run();
        }));

        box.addView(row(host, ui, T.s("电脑地址"), serverValue, () -> {
            String hint = LanQuota.host(host).isEmpty()
                    ? "192.168.1.5:8738" : LanQuota.host(host) + ":" + LanQuota.port(host);
            input(host, ui, T.s("电脑地址"), hint, hint, value -> {
                if (!LanQuota.setServer(host, value, LanQuota.token(host))) {
                    toast(host, T.s("只能填局域网地址，比如 192.168.1.5"));
                    return;
                }
                paint.run();
            });
        }));

        box.addView(row(host, ui, T.s("连接令牌"), tokenValue, () ->
                input(host, ui, T.s("连接令牌"), T.s("电脑端设置里那一串"), LanQuota.token(host), value -> {
                    LanQuota.setToken(host, value);
                    LanQuota.clearCache(host);
                    paint.run();
                })));

        box.addView(row(host, ui, T.s("共享的额度格子"), groupValue, () -> pickGroup(host, ui, () -> {
            paint.run();
            if (after != null) {
                after.run();
            }
        })));

        TextView probe = new TextView(host);
        probe.setText(T.s("测试连接"));
        probe.setTextSize(13);
        probe.setTextColor(ui.accent());
        probe.setGravity(Gravity.CENTER);
        probe.setPadding(0, ui.dp(14), 0, ui.dp(14));
        probe.setOnClickListener(v -> probe(host, ui, main, state));
        box.addView(probe);
        box.addView(state);
        box.addView(detail);

        TextView note = new TextView(host);
        note.setTextSize(11);
        note.setTextColor(ui.text3());
        note.setPadding(0, ui.dp(8), 0, 0);
        note.setText(Lang.t(host,
                "池子的分钟数由电脑端那条共享格子决定，手机上这个格子只负责记手机自己的用量。额度页里那条跨设备格子上也能进到这里。",
                "The pool budget comes from the shared bucket on the PC side; this phone bucket only contributes its own usage. The shared bucket on the quota page opens this panel too."));
        box.addView(note);

        // 每 2 秒重画一次：轮询是 3 秒一轮，面板开着的时候数字该跟着动
        final Runnable tick = new Runnable() {
            @Override public void run() {
                paint.run();
                main.postDelayed(this, 2000);
            }
        };
        dialog.setOnDismissListener(d -> main.removeCallbacksAndMessages(null));

        paint.run();
        dialog.show();
        main.postDelayed(tick, 2000);
    }

    /* ---------------- 两端明细 ---------------- */

    /** 「手机这一份」本地算，「电脑这一份」用轮询缓存的；两边都没有就说清楚没有。 */
    private static String detailText(ThemedActivity host) {
        if (!LanQuota.active(host) || !LanQuota.fresh(host)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        DailyQuotaData data = DailyQuotaStore.snapshot(host);
        JSONObject group = data.groupById(LanQuota.group(host));
        out.append(T.f("池子 %d 分钟 · 已用 %.1f 分钟 · 剩 %d 分钟",
                LanQuota.minutes(host), LanQuota.total(host) / 60000.0,
                Math.max(0L, LanQuota.minutes(host) * 60L - LanQuota.total(host) / 1000L) / 60));
        out.append('\n').append(T.s("手机这一份：")).append(appsText(LanQuota.appDetail(host, data, group)));
        out.append('\n').append(T.s("电脑这一份：")).append(appsText(LanQuota.pcApps(host)));
        if ("gist".equals(LanQuota.source(host))) {
            out.append('\n').append(T.s("（离线估算，数字偏乐观）"));
        }
        return out.toString();
    }

    static String appsText(JSONArray list) {
        if (list == null || list.length() == 0) {
            return T.s("今天还没有用量");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            double seconds = item.optDouble("seconds", 0);
            // 不到 6 秒的条目在列表里只会显示成「0.0 分钟」，占位置又没信息
            if (seconds < 6) {
                continue;
            }
            if (out.length() > 0) {
                out.append(T.s("、"));
            }
            out.append(item.optString("name")).append(' ').append(minutes(seconds)).append(T.s(" 分钟"));
        }
        return out.length() == 0 ? T.s("今天还没有用量") : out.toString();
    }

    static String minutes(double seconds) {
        double m = seconds / 60.0;
        return m >= 100 ? String.valueOf(Math.round(m)) : String.format(T.locale(), "%.1f", m);
    }

    /* ---------------- 小部件 ---------------- */

    private static TextView value(ThemedActivity host, Ui ui) {
        TextView view = new TextView(host);
        view.setTextSize(13);
        view.setTextColor(ui.accent());
        return view;
    }

    private static LinearLayout row(ThemedActivity host, Ui ui, String title, TextView value, Runnable onClick) {
        LinearLayout line = new LinearLayout(host);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, ui.dp(12), 0, ui.dp(12));
        TextView name = new TextView(host);
        name.setText(title);
        name.setTextSize(14);
        name.setTextColor(ui.text());
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(value);
        if (onClick != null) {
            line.setOnClickListener(v -> onClick.run());
        }
        return line;
    }

    private interface TextSink {
        void accept(String value);
    }

    private static void input(ThemedActivity host, Ui ui, String title, String hint, String current, final TextSink onOk) {
        final EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setHint(hint);
        if (current != null) {
            input.setText(current);
        }
        input.setSelection(input.getText().length());
        LinearLayout wrap = new LinearLayout(host);
        wrap.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ThemeStore.dialog(host).setTitle(title).setView(wrap)
                .setNegativeButton(T.s("取消"), null)
                .setPositiveButton(T.s("保存"), (d, w) -> onOk.accept(input.getText().toString().trim()))
                .show();
    }

    private static void pickGroup(final ThemedActivity host, Ui ui, final Runnable after) {
        DailyQuotaData data = DailyQuotaStore.snapshot(host);
        JSONArray groups = data.groups();
        if (groups.length() == 0) {
            toast(host, T.s("先在使用额度里建一个额度格子"));
            return;
        }
        final String[] ids = new String[groups.length()];
        String[] labels = new String[groups.length()];
        int checked = -1;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            ids[i] = group.optString("id");
            labels[i] = group.optString("name") + " · " + group.optInt("minutes") + T.s(" 分钟");
            if (ids[i].equals(LanQuota.group(host))) {
                checked = i;
            }
        }
        ThemeStore.dialog(host).setTitle(T.s("绑定哪个额度格子"))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    LanQuota.setGroup(host, ids[which], groups.optJSONObject(which).optString("name"));
                    if (after != null) {
                        after.run();
                    }
                    d.dismiss();
                })
                .setNegativeButton(T.s("取消"), null).show();
    }

    private static void probe(final ThemedActivity host, Ui ui, final Handler main, final TextView state) {
        if (LanQuota.host(host).isEmpty() || LanQuota.token(host).isEmpty()) {
            toast(host, T.s("先填电脑地址和令牌"));
            return;
        }
        state.setText(T.s("正在连接电脑…"));
        final String hostValue = LanQuota.host(host);
        final int port = LanQuota.port(host);
        final String token = LanQuota.token(host);
        new Thread(() -> {
            String text;
            try {
                JSONObject o = LanQuota.probe(hostValue, port, token);
                text = T.f("连上了 · 池子 %d 分钟，电脑已用 %.1f，手机已用 %.1f",
                        o.optInt("poolMinutes", 0),
                        o.optDouble("pcUsed", 0) / 60.0,
                        o.optDouble("phoneUsed", 0) / 60.0);
            } catch (Exception error) {
                text = T.f("连不上：%s", error.getMessage() == null ? error.toString() : error.getMessage());
            }
            final String out = text;
            main.post(() -> state.setText(out));
        }, "afs-lan-probe").start();
    }

    static String maskToken(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        if (token.length() <= 6) {
            return token.substring(0, 1) + "***";
        }
        return token.substring(0, 4) + "****" + token.substring(token.length() - 3);
    }

    private static void toast(ThemedActivity host, String value) {
        Toast.makeText(host, value, Toast.LENGTH_SHORT).show();
    }
}
