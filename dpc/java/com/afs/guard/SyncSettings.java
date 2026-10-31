package com.afs.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class SyncSettings {

    /** 云端同步状态一句话（页面/弹窗共用，避免两处各写一份措辞）。 */
    static String state(Activity a) {
        SyncTarget target = SyncConfig.target(a);
        if (target == null) {
            return T.s("未启用 · 本机照常保存，只是不往云端推");
        }
        String tail = T.s("上次上传 ") + at(a) + T.s(" · 云端 rev ") + Hub.ledgerRev(a);
        if (SyncConfig.paused(a)) {
            return T.s("已暂停（本地完整恢复后挂起）· 点「重新启用」即可恢复 · ") + tail;
        }
        return T.s("已启用 · ") + target.owner + " / " + target.gistId + " · " + tail;
    }

    static String at(Activity a) {
        long last = Hub.lastPushAt(a);
        return last <= 0L ? T.s("还没上传过")
                : new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(last));
    }

    private static EditText input(Activity a, LinearLayout form, String label, boolean secret) {
        TextView title = new TextView(a);
        title.setText(label);
        form.addView(title);
        EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        field.setSaveEnabled(false);
        form.addView(field);
        return field;
    }

    /** 「同步」入口直达云端同步本身；数据与备份页另有一份完整列表。 */
    static void show(Activity a) { showGist(a); }

    static void showGist(Activity a) {
        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        form.setPadding(pad, 0, pad, 0);
        final TextView stateView = new TextView(a);
        stateView.setText(state(a));
        form.addView(stateView);
        EditText owner = input(a, form, T.s("GitHub 用户名"), false);
        EditText id = input(a, form, T.s("Gist ID（与电脑端一致）"), false);
        EditText token = input(a, form, T.s("GitHub token（需要 gist 权限）"), true);
        SyncTarget current = SyncConfig.target(a);
        if (current != null) { owner.setText(current.owner); id.setText(current.gistId); }
        token.setHint(T.s("重新输入凭据以验证账号"));
        TextView result = new TextView(a);
        form.addView(result);
        AlertDialog dialog = ThemeStore.dialog(a).setTitle(T.s("个人同步"))
                .setMessage(T.s("可选功能；离线学习和冻结无需配置。\n连接后同步本机目标、打卡、冻结配置及使用统计。请使用自己创建的 Gist；新空间可先放一个 README 文件。"))
                .setView(form).setPositiveButton(T.s("验证并启用"), null)
                .setNeutralButton(SyncConfig.paused(a) ? T.s("重新启用") : T.s("停用同步"), null)
                .setNegativeButton(T.s("取消"), null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try {
                    if (SyncConfig.paused(a)) {
                        SyncConfig.resume(a);
                        dialog.dismiss();
                        Toast.makeText(a, T.s("云端同步已重新启用，正在补推本机台账"), Toast.LENGTH_LONG).show();
                        new Thread(() -> {
                            final boolean ok = Hub.pushNowSync(a);
                            if (!ok) a.runOnUiThread(() -> Toast.makeText(a,
                                    T.s("补推失败，检查网络后点「上传打卡数据」重试"), Toast.LENGTH_LONG).show());
                        }, "afs-sync-resume").start();
                        return;
                    }
                    SyncConfig.save(a, null, "");
                    dialog.dismiss();
                    Toast.makeText(a, T.s("已停用同步，本机数据保留"), Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    result.setText(e.getMessage());
                }
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                final SyncTarget target;
                final String credential = token.getText().toString().trim();
                try { target = SyncTarget.create(owner.getText().toString(), id.getText().toString()); }
                catch (Exception e) { result.setText(e.getMessage()); return; }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                result.setText(T.s("正在验证账号与 Gist…"));
                new Thread(() -> {
                    Exception failure = null;
                    try { Gist.verify(target, credential); }
                    catch (Exception e) { failure = e; }
                    final Exception error = failure;
                    a.runOnUiThread(() -> {
                        if (a.isFinishing() || !dialog.isShowing()) return;
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                        if (error != null) { result.setText(T.s("验证失败：") + error.getMessage()); return; }
                        try {
                            SyncConfig.save(a, target, credential);
                            dialog.dismiss();
                            Toast.makeText(a, T.s("个人同步已启用"), Toast.LENGTH_LONG).show();
                            Hub.kick(a);
                        } catch (Exception e) { result.setText(e.getMessage()); }
                    });
                }, "afs-sync-verify").start();
            });
        });
        dialog.show();
    }
}
