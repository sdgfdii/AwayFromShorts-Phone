# -*- coding: utf-8 -*-
"""1.76.0：开了代理时断网主动让路，不抢用户的 VPN。"""

TEXT = {
    "%d 个应用 · 断网让路：VPN 被代理占用（不抢）": "%d apps · standing down: the proxy holds the VPN slot (not stolen)",
    "不会抢你的代理：检测到代理正占着 VPN 通道时，断网会让路（状态显示「被代理占用（不抢）」），代理关掉后几秒内自动接管。":
        "Your proxy is never taken over: when a proxy holds the VPN slot, blocking stands down (the status says “the proxy holds the VPN slot (not stolen)”) and takes over again within seconds after the proxy is turned off.",
}
