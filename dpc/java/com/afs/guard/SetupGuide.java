package com.afs.guard;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

final class SetupGuide {
    static void showIfNeeded(Activity a) {
        SharedPreferences p = a.getSharedPreferences("afs_setup", Context.MODE_PRIVATE);
        if (p.getBoolean("guide_v1_seen",false) || a.isFinishing()) return;
        boolean owner = Guard.isOwner(a);
        String message = owner
                ? T.s("设备所有者授权已验证。\n\n1. 在冻结管理中选择需要管理的应用与时段。\n2. 检查关键应用，保留必要的通讯和系统功能。\n3. 配置完成后点“立即应用”。\n\n首次设置有 10 分钟编辑宽限；冻结开始后按规则限制修改。日常无需连接电脑。")
                : T.s("当前尚未获得设备所有者授权，应用冻结尚未生效。\n\n请在 Windows 电脑解压完整部署包，连接手机并开启 USB 调试，再双击“开始安装.cmd”。部署工具会检查账号、分身、多用户及管理状态。\n\n部分手机需要自行备份后恢复出厂；工具不会自动清机。");
        if (android.os.Build.VERSION.SDK_INT < 28)
            message += T.s("\n\n本机冻结时段固定使用时区：") + GuardClock.pin(a).getID();
        message += T.s("\n\n个人同步可在打卡页点“同步”设置；离线学习和冻结不需要同步账号。");
        ThemeStore.dialog(a).setTitle(T.s("部署与首次设置")).setMessage(message)
                .setPositiveButton(T.s("我知道了"),(d,w)->p.edit().putBoolean("guide_v1_seen",true).apply())
                .show();
    }
}
