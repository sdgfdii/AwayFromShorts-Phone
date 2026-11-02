# -*- coding: utf-8 -*-
"""跨设备额度的 Gist 兜底：离线估算文案与配置提示。"""

TEXT = {
    "离线估算 · ": "Offline estimate · ",
    "跨设备 · ": "Shared · ",
    "已用 %.1f / %d 分钟（电脑 %.1f + 手机 %.1f）":
        "%.1f of %d min used (PC %.1f + phone %.1f)",
    "离线估算 · 剩 %d 分钟": "Offline estimate · %d min left",
    "剩 %d 分钟": "%d min left",
    "连不上电脑：%s": "Cannot reach the PC: %s",
    " · 局域网不通，用的是 Gist 上的旧账，回到家里 WiFi 会自动恢复准确":
        " · LAN unreachable, using the last snapshot from Gist; it corrects itself back on the home wifi",
    "只能填局域网地址，比如 192.168.1.5":
        "Only a LAN address works here, for example 192.168.1.5",
}
