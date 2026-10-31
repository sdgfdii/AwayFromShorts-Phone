# -*- coding: utf-8 -*-
"""1.70.0：齿轮图标、备忘录回车续号、正念重做、应用选择重做、成就独立页、能力等级可选、朋友圈规则。"""

TEXT = {
    # AchievementActivity
    " 已解锁": " unlocked",
    "已解锁": "Unlocked",
    "已解锁。": "Unlocked.",
    "进度 ": "Progress ",
    "，还差 ": ", still ",
    # AchievementCenter
    "\u3000": "  ",
    "解锁成就：": "Achievement unlocked: ",
    "解锁了 ": "Unlocked ",
    " 个成就": " achievements",
    # AppPickerActivity
    "全选": "Select all",
    "选择要冻结的应用": "Choose apps to freeze",
    "搜索应用名或包名": "Search name or package",
    " 个 · 共 ": " selected · ",
    "保存（已选 ": "Save (",
    " 个）": ")",
    "没有匹配的应用": "No matching app",
    "屏蔽时段内这些应用会被系统冻结，点不开":
        "These apps are frozen by the system during blocked hours",
    "选中的应用会按这一项自己的规则执行":
        "Selected apps follow this setting's own rules",
    # Capability
    "能冻结、能锁机": "Can freeze and lock",
    "能读屏拦截": "Can read the screen",
    "只能计时提醒": "Timers only",
    "什么都没开": "Nothing granted",
    "现在：": "Now: ",
    "降级要去系统的无障碍设置里关掉服务，应用里关不了":
        "To step down, turn the service off in the system accessibility settings; the app cannot do it for you",
    "这个系统没有无障碍设置页": "This system has no accessibility settings page",
    "设备所有者只能用部署包一次性授权，系统设置里没有这个开关。\n\n":
        "Device owner can only be granted once by the deployment package; there is no switch for it in the system settings.\n\n",
    "拿到部署包后按说明执行，应用会自动变成「设备所有者」，冻结和锁机才真正生效。\n\n":
        "Run the deployment package as documented and the app becomes device owner, which is when freezing and locking actually work.\n\n",
    "现在只有「": "Right now you only have ",
    "」的权限：": ": ",
    # ControlActivity
    " · 点此切换": " · tap to switch",
    "微信朋友圈": "WeChat Moments",
    "已加上朋友圈规则": "Moments rule added",
    "实测「%s」把页面内容对无障碍隐藏了（一屏读到 0 个字），所以「微信视频号」「微信朋友圈」这两条在它那儿不会命中。想限住它只能用整应用冻结或每日限额。":
        "Measured: %s hides its page content from accessibility (0 characters read on a full screen), so the Channels and Moments rules never match inside it. Only whole-app freezing or a daily limit can hold it back.",
    "这条规则已经在里面了": "That rule is already in the list",
    "还没有规则。可以先用下面的「微信视频号」「微信朋友圈」加一条，或者自己挑应用和页面特征。":
        "No rules yet. Add the built-in Channels or Moments rule below, or pick an app and a page fingerprint yourself.",
    # HistoryActivity
    " 天 · 连续 ": " days · streak ",
    "月视图、每天明细与打卡日记 ›": "Month view, daily detail and journal ›",
    # SettingsActivity
    " · 点这一行切换": " · tap this row to switch",
    # ToolboxActivity（正念）
    "结束这一轮": "End this round",
    "鼻吸鼻呼，屏息不舒服就跳过，别硬撑。离开这一页会自动暂停。":
        "Breathe through the nose. Skip the hold if it feels bad. Leaving this page pauses automatically.",
    "先停下再换节律": "Stop first, then change the rhythm",
    "继续": "Resume",
    "已暂停，回来再从这一刻接上": "Paused — resume picks up from here",
    "就到这里，随时可以重来": "Stopping here; start again whenever",
    " 次呼吸 · 节律 ": " breaths · rhythm ",
    "节律 ": "Rhythm ",
    "今天已正念 ": "Mindfulness today: ",
    "今天还没有完成一轮": "No completed round today",
    # 正念节律/按钮里剩下的短词
    "暂停": "Pause",
    "再来一次": "Again",
    "呼吸": "Breathe",
}
