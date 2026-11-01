# -*- coding: utf-8 -*-
"""1.75.0：两种断网用法（不开代理用本 App VPN / 常开代理用代理里的拒绝规则）。"""

TEXT = {
    "复制代理拦截规则": "Copy proxy rules",
    "已复制。粘到代理 App 的覆写/配置 rules: 里（放在 MATCH 之前），断网就由代理完成；本 App 仍显示「被占用」是正常的。":
        "Copied. Paste it into the proxy app's override / config rules: (before MATCH); the proxy then does the blocking, and the app keeping “VPN slot taken” is expected.",
    "两种用法：不开代理的人什么都不用做 —— 本 App 用系统 VPN 断网；常开代理的人，断网交给代理做 —— 点上面「复制代理拦截规则」，把内容放进代理 App 的覆写/配置 rules:（MATCH 之前），代理照常用、这些应用没网。":
        "Two ways to use this: no proxy — do nothing, this app cuts the network with the system VPN. Proxy always on — hand blocking to the proxy: tap “Copy proxy rules” above, paste into the proxy app's override / config rules: (before MATCH), and the proxy keeps working while these apps lose their network.",
}
