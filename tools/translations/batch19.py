# -*- coding: utf-8 -*-
"""第十九批：自律规则（定时锁机 / 钥匙应用 / 亲友解锁码 / 关键词拦截 / 屏蔽联网）的文案。"""

TEXT = {
    # AppPickerActivity：通用选择器的计数后缀
    " 个。": ". ",

    # Capability
    "无障碍": "Accessibility",
    "基本": "Basic",
    "未授权": "No access",
    "能冻结应用、能真锁屏、能把你请出应用。这是完整形态。":
        "Can freeze apps, really lock the screen and kick you out of an app. This is the full form.",
    "能读屏做关键词拦截、能按返回键，但冻不住应用、锁屏拦不住 Home。":
        "Can read the screen for keyword blocking and press Back, but cannot freeze apps and Home still escapes a lock.",
    "只能计时和提醒。冻结、锁屏、拦截都需要更高权限。":
        "Timing and reminders only. Freezing, locking and blocking need more access.",
    "什么都做不了，先把权限开起来。": "Nothing works yet — grant access first.",
    "需要设备所有者（要装部署包，系统设置里开不了）":
        "Needs device owner (install the provisioning package; Settings has no switch for it)",
    "需要开启无障碍服务（在系统设置 → 无障碍里打开）":
        "Needs the accessibility service (turn it on in Settings → Accessibility)",

    # ControlActivity
    "都没开": "All off",
    "%d 项生效": "%d active",
    "要装部署包": "Needs the provisioning package",
    "周期性锁机": "Periodic lock",
    "没有设备所有者，锁机拦不住 Home 键": "Without device owner a lock cannot stop the Home key",
    "时间段": "Window",
    "每隔多久锁一次": "Lock every",
    "每次锁多久": "Lock for",
    "从时段起点整点起算：8:00 开始、每 45 分钟的话，8:00、8:45、9:30 各锁一次。灭屏期间不弹。":
        "Counted from the window start. From 08:00 every 45 min means 08:00, 08:45 and 09:30 each lock once. Nothing pops up while the screen is off.",
    "单应用久用锁": "Long-session lock",
    "连续用多久就锁": "Lock after",
    "锁多久": "Rest for",
    "一直待在同一个应用里才算连续；切走再回来重新计时。":
        "Only staying in one app counts as continuous; switching away restarts the clock.",
    "关键应用白名单": "Always allowed",
    "%d 个": "%d",
    "锁机时这些应用照常可用": "These apps stay usable during a lock",
    "把电话、导航、备忘录这类放进白名单，锁机不会把它们一起挡住。":
        "Put phone, maps or notes here so a lock never blocks them.",
    "从几点开始": "Starts at",
    "到几点结束": "Ends at",
    "开启钥匙闸门": "Key-app gate",
    "钥匙应用": "Key app",
    "在这些应用里待够时间才放行娱乐应用": "Entertainment apps open only after enough time here",
    "需要待够": "Required",
    "今天进度": "Today",
    "看的是系统使用记录里今天在这些应用里的累计时长，不看打卡，也不上传。没开使用情况权限时不计时。":
        "Uses today's accumulated time in these apps from the system usage log. No check-ins involved, nothing uploaded. Without usage access it cannot count.",
    "启用解锁码": "Unlock code",
    "密钥": "Secret",
    "还没生成": "Not generated",
    "已有": "Set",
    "更换密钥": "Replace secret",
    "试一次码": "Try a code",
    "还剩 %d 分钟": "%d min left",
    "放行已结束": "Pass ended",
    "否": "No",
    "这是标准的 6 位动态码，30 秒一换。把密钥加进家长手机上的验证器，Google Authenticator、1Password、微软验证器都行。":
        "A standard 6-digit code that changes every 30 s. Add the secret to the parent's authenticator — Google Authenticator, 1Password or Microsoft Authenticator all work.",
    "孩子这边只验证、不显示码，验过一次放行 10 分钟，可以提前结束锁机。":
        "This device only verifies and never shows a code. One valid code opens a 10-minute pass and can end a lock early.",
    "要说明的边界：密钥存在这台手机上，能打开这一页的人就看得到。":
        "Be clear about the limit: the secret lives on this phone, so anyone who can open this page can read it.",
    "它防的是「手上没有别人手机时的一时冲动」，不是防技术破解。":
        "It guards against impulse when the other person is not around — not against a determined user.",
    "要更硬的隔离，让家长在自己手机上生成 32 位密钥再手输进来。":
        "For a harder barrier, have the parent generate a 32-character secret on their own phone and type it in.",
    "已生成新密钥": "New secret generated",
    "这是新密钥，旧验证器里的条目立刻失效。\n\n":
        "This is a new secret; the old authenticator entry stops working.\n\n",
    "\n\n在家长手机的验证器里选「手动输入密钥」，账号名随便填，密钥就是上面这串，选「基于时间」。":
        "\n\nIn the parent's authenticator choose \"Enter a setup key\", any account name, the secret above, and time-based codes.",
    "复制": "Copy",
    "已复制": "Copied",
    "换掉之后，家长验证器里原来那条就废了，要重新加一次。确定？":
        "The old entry in the parent's authenticator stops working and must be added again. Continue?",
    "换": "Replace",
    "先启用并生成密钥": "Enable it and generate a secret first",
    "6 位数字": "6 digits",
    "输入家长手机验证器上此刻的码，验证配置对不对。验过会直接进入 10 分钟放行期。":
        "Type the code showing in the parent's authenticator to check the setup. A valid code starts a 10-minute pass.",
    "验证": "Verify",
    "对上了，放行 10 分钟": "Matched — 10-minute pass started",
    "错太多次，%d 秒后再试": "Too many wrong tries, retry in %d s",
    "码不对，或者已经过期了": "Wrong or expired code",
    "开启拦截": "Keyword blocking",
    "还要去系统设置里打开无障碍服务": "Also turn on the accessibility service in system settings",
    "关键词": "Keywords",
    "还没填": "Empty",
    "目标应用": "Watched apps",
    "只在这些应用里检查屏幕文字": "Screen text is only checked in these apps",
    "状态": "Status",
    "清除拦截记录": "Clear history",
    "记录已清除": "History cleared",
    "命中就盖住整屏并回桌面。文字只在本机比对，不保存屏幕内容、不上传。":
        "A hit covers the screen and sends you home. Text is matched on-device only; screen content is never stored or uploaded.",
    "只记录哪个应用、哪个词、第几次。": "Only the app, the keyword and the count are recorded.",
    "有 %d 个词只有一个字，几乎一定会误拦，建议改成两三个字的词。":
        "%d keyword(s) are a single character and will almost certainly misfire — use two or three.",
    "一行一个词": "One keyword per line",
    "一行一个，英文不分大小写。视频号那类可以填「推荐」「直播」。":
        "One per line; English is case-insensitive. For short-video feeds try \"For you\" or \"Live\".",
    "已保存 %d 个词": "Saved %d keyword(s)",
    "开启断网": "Cut network",
    "断网的应用": "Cut-off apps",
    "这些应用连不上任何服务器": "These apps cannot reach any server",
    "只接管选中的应用，别的应用照常上网。用系统 VPN 通道实现，所以和代理、公司内网、加速器互斥。":
        "Only the selected apps go through the tunnel; everything else keeps its network. It uses the system VPN slot, so it excludes proxies, corporate networks and accelerators.",
    "系统同一时间只允许一个 VPN：开了它就要关掉代理，否则两边都用不了。":
        "The system allows one VPN at a time: turn the proxy off first, or neither will work.",
    "应用会表现为转圈、超时，而不是弹「已断网」；视频类通常还会显示缓存过的内容。":
        "Apps show spinners and timeouts rather than a \"no internet\" notice, and video apps still play cached content.",
    "先选要断网的应用": "Pick the apps to cut off first",
    "这台系统不允许建 VPN": "This system does not allow creating a VPN",
    "没给 VPN 授权，断网开不了": "No VPN permission, so the cut cannot start",

    # Guard
    "钥匙应用未达标": "Key app not enough yet",

    # KeyGate
    "已开启 · 未授予使用情况权限，无法计时": "On · no usage access, cannot count",
    "已达标（%d 分钟）": "Reached (%d min)",
    "已待 %d / %d 分钟，还差 %d 分钟": "%d / %d min, %d min to go",

    # KeywordBlockActivity
    "这个应用": "this app",
    "这一屏被拦下了": "This screen was blocked",
    "在「%s」里看到「%s」": "Saw \"%s\" in %s",
    "今天第 %d 次": "#%d today",
    "回桌面": "Back to home",
    "屏幕内容只在本机比对，没有上传。":
        "Screen content was matched on-device and never uploaded.",

    # KeywordStore
    "缺关键词或目标应用，不会生效": "No keywords or no target apps, so nothing happens",
    "无障碍服务没开，不会生效": "Accessibility off, so nothing happens",
    "%d 个词 · %d 个应用 · 已拦 %d 次": "%d words · %d apps · %d blocked",

    # LockPlan
    "%d 分钟": "%d min",

    # LockScreenActivity
    "该休息了": "Time for a break",
    "这个应用用得有点久": "You have been here a while",
    "到点自己开，不用手动解": "Unlocks itself when time is up",
    " 连续用了 ": " used for ",
    "用亲友解锁码提前结束": "End early with a family code",
    "设备所有者已锁住屏幕：返回、Home、最近任务都不起作用。时间走完自动解除。":
        "Device owner holds the screen: Back, Home and Recents do nothing. It releases itself when time is up.",
    "当前不是设备所有者，拦不住 Home 键。要真正锁住装部署包后重新授权。":
        "Not device owner, so Home is not blocked. Install the provisioning package and re-grant to really lock.",
    "到 %s 结束，还剩 %s": "Ends at %s, %s left",
    "亲友解锁码": "Family unlock code",
    "向家长要此刻的 6 位码": "Ask the parent for the current 6-digit code",
    "确认": "OK",
    "已放行 10 分钟": "10-minute pass started",
    "还没配亲友解锁码": "No family code configured",
    "这一轮还没走完": "This round is not over yet",

    # NetBlockStore
    "还没选应用": "No apps selected",
    "%d 个应用断网中": "%d apps cut off",
    "%d 个应用待断网（没连上隧道）": "%d apps waiting (tunnel not up)",

    # SettingsActivity
    "应用内关键词拦截要用；开了才读得到屏幕文字":
        "Needed for in-app keyword blocking; screen text is unreadable without it",
    "能力等级": "Capability level",
}
