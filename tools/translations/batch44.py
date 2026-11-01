# -*- coding: utf-8 -*-
"""1.77.0：断网方式可选（自动用 VPN / 我常开代理不占 VPN），避免顶掉用户的代理。"""

TEXT = {
    "断网方式": "Cut-off mode",
    "自动（本 App 用系统 VPN）": "Auto (this app uses the system VPN)",
    "我常开代理（不占 VPN）": "My proxy stays on (never touches the VPN)",
    "%d 个应用 · 由代理负责拦截（本 App 不占 VPN）": "%d apps · blocked by the proxy (this app keeps off the VPN)",
    "默认「自动」：本 App 用系统 VPN 断网。代理常开的人把「断网方式」改成「我常开代理（不占 VPN）」后，本 App 一步都不碰 VPN 通道、绝不会顶掉你的代理，拦网交给代理里的拒绝规则（用上面「复制代理拦截规则」）。":
        "Default is “Auto”: this app cuts the network with the system VPN. If your proxy is always on, switch “Cut-off mode” to “My proxy stays on (never touches the VPN)”; this app then never touches the VPN slot, can never knock your proxy off, and the proxy's reject rules do the blocking (see “Copy proxy rules” above).",
}
