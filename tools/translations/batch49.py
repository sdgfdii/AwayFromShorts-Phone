# -*- coding: utf-8 -*-
"""跨设备共享额度：手机端设置项、状态文案与连接反馈。"""

TEXT = {
    "跨设备额度 · 未连上电脑，暂按本机计":
        "Shared quota · PC unreachable, counting locally for now",
    "跨设备 · 已用 %.1f / %d 分钟（电脑 %.1f + 手机 %.1f）":
        "Shared · %.1f of %d min used (PC %.1f + phone %.1f)",
    "电脑端返回 HTTP ": "The PC app returned HTTP ",
    "未连接电脑": "Not connected to the PC",
    "与电脑共享 · 剩 %d 分钟": "Shared with the PC · %d min left",
    "先在使用额度里建一个额度格子": "Create a quota bucket first, under usage quota",
    "绑定哪个额度格子": "Which bucket should share the pool?",
    "先填电脑地址和令牌": "Fill in the PC address and token first",
    "正在连接电脑…": "Connecting to the PC…",
    "连上了 · 池子 %d 分钟，电脑已用 %.1f，手机已用 %.1f":
        "Connected · pool %d min, PC used %.1f, phone used %.1f",
    "连不上：%s": "Cannot reach it: %s",
    "未设置": "Not set",
    "未选择": "Not chosen",
    "已选择": "Chosen",
    "填好电脑地址和令牌，再绑定一个额度格子，这个功能才会开始工作。":
        "Enter the PC address and token, then bind a bucket — only then does this start working.",
    "还没有连上过电脑。手机和电脑要在同一个 WiFi 下。":
        "Never reached the PC yet. The phone and the PC must be on the same wifi.",
    " · 电脑端可查看详细用量": " · details are on the PC side",
    "启用": "Enabled",
    "电脑地址": "PC address",
    "连接令牌": "Access token",
    "电脑端设置里那一串": "the string shown in the PC app",
    "共享的额度格子": "Shared bucket",
    "测试连接": "Test connection",
    "池子的分钟数由电脑端决定，手机上这个格子只负责记手机自己的用量。电脑端要先把那条规则标成共享，两边才是同一份额度。":
        "The pool budget is decided on the PC; this bucket only contributes the phone's own usage. The PC rule must be flagged as shared for both sides to charge the same budget.",
    "跨设备": "Across devices",
    "共享额度": "Shared quota",
    "把手机上某一个额度格子接到电脑上：两边各记各的用量，合起来算一个池子，任一边用完另一边也停。只在家里 WiFi 下生效；连不上时退回各自记账，不会把人锁在外面。":
        "Binds one phone bucket to the PC: both sides report their own usage into one pool, and either side running dry stops both. Home wifi only; when the PC is unreachable each side falls back to its own ledger instead of locking you out.",
    "跨设备共享额度": "Shared quota across devices",
}
