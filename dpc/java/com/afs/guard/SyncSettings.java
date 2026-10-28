package com.afs.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

final class SyncSettings {
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
    static void show(Activity a) { a.startActivity(new android.content.Intent(a,DataManagementActivity.class)); }
    static void showGist(Activity a) {
        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        form.setPadding(pad, 0, pad, 0);
        EditText owner = input(a, form, "GitHub 用户名", false);
        EditText id = input(a, form, "Gist ID（与电脑端一致）", false);
        EditText token = input(a, form, "GitHub token（需要 gist 权限）", true);
        SyncTarget current = SyncConfig.target(a);
        if (current != null) { owner.setText(current.owner); id.setText(current.gistId); }
        token.setHint("重新输入凭据以验证账号");
        TextView result = new TextView(a);
        form.addView(result);
        AlertDialog dialog = ThemeStore.dialog(a).setTitle("个人同步")
                .setMessage("可选功能；离线学习和冻结无需配置。\n连接后同步本机目标、打卡、冻结配置及使用统计。请使用自己创建的 Gist；新空间可先放一个 README 文件。")
                .setView(form).setPositiveButton("验证并启用", null)
                .setNeutralButton("停用同步", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try {
                    SyncConfig.save(a, null, "");
                    dialog.dismiss();
                    Toast.makeText(a, "已停用同步，本机数据保留", Toast.LENGTH_LONG).show();
                } catch (Exception e) { result.setText(e.getMessage()); }
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                final SyncTarget target;
                final String credential = token.getText().toString().trim();
                try { target = SyncTarget.create(owner.getText().toString(), id.getText().toString()); }
                catch (Exception e) { result.setText(e.getMessage()); return; }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                result.setText("正在验证账号与 Gist…");
                new Thread(() -> {
                    Exception failure = null;
                    try { Gist.verify(target, credential); }
                    catch (Exception e) { failure = e; }
                    final Exception error = failure;
                    a.runOnUiThread(() -> {
                        if (a.isFinishing() || !dialog.isShowing()) return;
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                        if (error != null) { result.setText("验证失败：" + error.getMessage()); return; }
                        try {
                            SyncConfig.save(a, target, credential);
                            dialog.dismiss();
                            Toast.makeText(a, "个人同步已启用", Toast.LENGTH_LONG).show();
                            Hub.kick(a);
                        } catch (Exception e) { result.setText(e.getMessage()); }
                    });
                }, "afs-sync-verify").start();
            });
        });
        dialog.show();
    }
}
