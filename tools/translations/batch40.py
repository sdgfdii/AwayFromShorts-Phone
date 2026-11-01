# -*- coding: utf-8 -*-
"""1.74.0：VPN 被代理占着时，断网改用冻结兜底（界面与状态文案）。"""

TEXT = {
    "代理占着 VPN 时": "When a proxy holds the VPN",
    "改用冻结这些应用": "Freeze these apps instead",
    "只提示不处理": "Only warn, do nothing",
    "%d 个应用 · 已改用冻结（VPN 被占用）": "%d apps · frozen instead (VPN slot taken)",
    "系统同一时间只允许一个 VPN：代理（FlClash 等）占着时隧道建不起来。选「改用冻结这些应用」后，时段外名单里的应用会被直接冻结（打不开，也就等于没网），时段内自动解冻；名单里别放必须用的应用。":
        "Android allows only one VPN at a time: while a proxy (FlClash etc.) holds it, the tunnel cannot be built. With “Freeze these apps instead”, the listed apps are frozen outside the adjustment window (they cannot open, which also means no network) and released inside it. Do not put apps you must use in this list.",
}
