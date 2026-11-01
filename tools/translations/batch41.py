# -*- coding: utf-8 -*-
"""1.74.0：讲清「一个 VPN」限制，加代理占位时的处理方式与代理入口。"""

TEXT = {
    "打开代理 App": "Open the proxy app",
    "默认「只提示」不会动你的应用。要让断网在代理常开时也生效，得在代理 App 里加拒绝规则（Clash 覆写示例：DOMAIN-SUFFIX,bilibili.com,REJECT 与 DOMAIN-SUFFIX,bilivideo.com,REJECT）—— 代理是那台「唯一 VPN」，拦网只能它去做。":
        "The default “only warn” leaves your apps alone. To make blocking work while a proxy is always on, add reject rules inside the proxy app (Clash override examples: DOMAIN-SUFFIX,bilibili.com,REJECT and DOMAIN-SUFFIX,bilivideo.com,REJECT) — the proxy is the only VPN, so only it can block traffic.",
}
