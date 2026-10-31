# -*- coding: utf-8 -*-
"""第二批英文文案：成就、应用列表/冻结、额度、卡管理。

键就是代码里的中文原文（含前后空格），别 trim。
"""

TEXT = {
    # ---------- Achievement ----------
    "开张": "First step",
    "第一次打卡": "Check in once",
    "连三": "Three in a row",
    "连续打卡 3 天": "Check in 3 days straight",
    "一周不断": "A full week",
    "连续打卡 7 天": "Check in 7 days straight",
    "半月不息": "Half a month",
    "连续打卡 15 天": "Check in 15 days straight",
    "习惯成形": "Habit formed",
    "连续打卡 30 天": "Check in 30 days straight",
    "满勤 ×10": "Perfect days ×10",
    "单日完成全部目标，累计 10 天": "Finish every goal in a day, 10 times",
    "满勤 ×50": "Perfect days ×50",
    "单日完成全部目标，累计 50 天": "Finish every goal in a day, 50 times",
    "打卡 30 天": "30 days logged",
    "累计打卡天数": "Total days with check-ins",
    "百日记": "Hundred days",
    "累计打卡 100 天": "Check in on 100 days",
    "五百笔": "Five hundred entries",
    "累计写下 500 条记录": "Log 500 entries in total",

    # ---------- AfsAdmin ----------
    "设备所有者由系统保护，无法在此处停用。请按部署包的恢复说明处理。":
        "Device owner is protected by the system and cannot be turned off here. Follow the restore steps in the deployment package.",

    # ---------- AmapStyle（底栏 glyph 用的短词） ----------
    "记忆": "Memory",
    "目标管理": "Goals",
    "刷题": "Quiz",
    "屏幕统计": "Stats",

    # ---------- AppListActivity ----------
    "冻结应用": "Frozen apps",
    "点一行改它的冻结时间，长按从冻结应用移除。": "Tap a row to change its freeze time. Long-press to remove it.",
    "冻结应用是空的。点下面的「添加应用」选一个。": "No frozen apps yet. Tap \u201cAdd app\u201d below to pick one.",
    "＋ 添加应用": "+ Add app",
    "全部应用都在屏蔽中，等某一应用进入自己的放行时段再加":
        "Every app is in its block window. Wait until one enters its allowed hours.",
    "＋ 按包名添加": "+ Add by package",
    "⏱ 批量设时段": "⏱ Set hours in bulk",
    "🔒 关键应用名单": "🔒 Key apps",
    "立即应用": "Apply now",
    "检测并应用中…": "Checking and applying…",
    " · 已重新检测": " · re-checked",
    " 个应用加入已超过 10 分钟，按「只进不出」不能移出冻结应用":
        " apps have been here over 10 minutes, so \u201cadd-only\u201d keeps them frozen.",
    " 个应用": " apps",
    "改这一组的时段": "Change hours for this group",
    " 等": " and more",
    "分": "min",
    "时": "h",
    "未安装": "Not installed",
    "🧊 冻结中 ": "🧊 Frozen ",
    "学习闸门冻结 ": "Study gate freeze ",
    "放行中": "Allowed",
    "🧊 全部冻结": "🧊 All frozen",
    "全部放行": "All allowed",
    " 冻结": " frozen",
    "🔒 关键应用名单 · ": "🔒 Key apps · ",
    " 个": " ",
    "点一行改它的冻结时间。时段相同的一批应用会折成一张卡，点卡片展开明细。":
        "Tap a row to change its freeze time. Apps sharing the same hours fold into one card; tap it for details.",
    "只进不出：加入后不能移出（刚加的 10 分钟内除外）。":
        "Add-only: once added, an app cannot be removed (except within 10 minutes of adding).",
    "冻结星期（未选中的日子不定时冻结）": "Freeze days (unselected days have no scheduled freeze)",
    "现有规则保持生效：": "Current rules stay in effect:",
    "\n设置新的冻结星期和时间后保存，才会替换原规则。未修改时间则保留原规则。":
        "\nSave new freeze days and times to replace the old rule. Leave the times untouched to keep it.",
    "每天冻 22-23": "Freeze 22-23 daily",
    "每天冻 21-23": "Freeze 21-23 daily",
    "全天冻结": "Freeze all day",
    "参与学习闸门（只有关键应用可豁免）": "Include study gate (only key apps are exempt)",
    "只有关键应用不受学习闸门影响；其他应用在 20:00 检查后可能延长冻结。":
        "Only key apps skip the study gate. Other apps may be frozen longer after the 20:00 check.",
    "冻结星期 ": "Freeze days ",
    "没有冻结时段 = 不定时冻结（学习闸门仍可生效）":
        "No freeze hours = no scheduled freeze (the study gate still applies)",
    ".　": ". ",
    "改": "Edit",
    "删": "Del",
    "＋ 加一段": "+ Add a window",
    "冻结时间　": "Freeze hours ",
    "它正在自己的屏蔽时间里，等它的放行时段才能改":
        "It is inside its own block window. You can change it during its allowed hours.",
    "未选择冻结星期，不定时冻结，已保存": "No freeze days selected: no scheduled freeze. Saved.",
    "移除": "Remove",
    "只进不出：已加入的应用不能移出冻结应用（刚加的 10 分钟内除外）":
        "Add-only: apps already added cannot be removed (except within 10 minutes of adding).",
    "选中的应用只按各自的冻结时间执行，不受学习闸门延长。冻结时间仍可在应用列表中修改。":
        "Selected apps follow their own freeze hours and are not extended by the study gate. Hours are still editable in the app list.",
    "冻结应用为空，请先添加应用。": "No frozen apps. Add one first.",
    "关键应用名单": "Key apps",
    "冻结应用是空的，先添加应用": "No frozen apps. Add one first.",
    "（屏蔽中，跳过）": " (in block window, skipped)",
    "选要一起设时段的 ": "Pick apps to set hours for ",
    "下一步": "Next",
    "没选应用（屏蔽中的会被跳过）": "No apps selected (apps in block windows are skipped)",
    "给这 ": "Apply to these ",
    " 个应用套用": " apps",
    "套用": "Apply",
    "选中的应用都在自己的屏蔽时段里，改不了": "All selected apps are in their own block windows. Cannot change.",
    "已套用：": "Applied: ",
    "（": " (",
    " 个屏蔽中，跳过）": " in block windows, skipped)",
    "、": ", ",
    " 等 ": " and ",
    "新加了 ": "Added ",
    "\n\n现在统一设置冻结时间？之后也可以单独改。":
        "\n\nSet freeze hours for them now? You can change them one by one later.",
    "现在设": "Set now",
    "用默认的": "Use default",
    "无": "None",
    "起点和终点不能相同": "Start and end cannot be the same",
    "按包名添加到冻结应用": "Add to frozen apps by package name",
    "应用还没装也可以先加：装上之后它会被自动冻结。\n包名必须完全一致，填错了这条规则不会匹配任何东西。":
        "You can add an app before it is installed; it will be frozen once installed.\nThe package name must match exactly, or the rule matches nothing.",
    "移出冻结应用": "Remove from frozen apps",
    " 将不再被管理（立刻解冻）。": " will no longer be managed (unfrozen immediately).",

    # ---------- AppPickerActivity ----------
    "已选 ": "Selected ",
    " 个。屏蔽时段内这些 App 会被系统冻结，点不开。":
        " apps. During block hours the system freezes them and they cannot be opened.",

    # ---------- AppUsageQuota ----------
    "使用额度须为1–1440分钟，0为取消": "Quota must be 1-1440 minutes; 0 means off",
    "使用记录暂不可读": "Usage records are not readable right now",
    "未授予使用情况权限，暂不计时": "Usage access not granted, so timing is paused",
    "%s · 已用 %.1f / %d 分钟": "%s · used %.1f / %d min",
    "全天额度": "All-day quota",
    "所绑时段额度": "Quota within bound hours",
    "系统使用记录暂不可读，额度保护性冻结": "System usage records unreadable; quota frozen as a safeguard",

    # ---------- Backdrop ----------
    "读不到这张图": "Cannot read this image",
    "这不是一张图片": "This is not an image",
    "解不开这张图": "Cannot decode this image",
    "导入失败": "Import failed",

    # ---------- CalendarActivity ----------
    " 月": " mo",
    "  周": "  wk",

    # ---------- CardData ----------
    "卡管理数据无法读取，原文件已保留": "Card data unreadable; the original file was kept",
    "奖励抵扣记录无效": "Invalid reward offset record",
    "热爱名称须为 1–80 字": "Passion name must be 1-80 characters",
    "每张热爱卡的奖励只能为 1–60 分钟": "Each passion card rewards 1-60 minutes",
    "热爱卡不存在": "Passion card not found",
    "奖励分钟数无效": "Invalid reward minutes",

    # ---------- CardManagementActivity ----------
    "‹  守卫": "‹  Guard",
    "‹  卡管理": "‹  Cards",
    "请稍后重试": "Try again shortly",
    "统一管理卡片、学习联动和奖励。": "Manage cards, study linkage and rewards in one place.",
    "热爱奖励余额 · 跨天累计 · 自动抵扣定时冻结":
        "Passion reward balance · carries over days · auto-offsets scheduled freezes",
    "本次减免剩余 ": "Remaining offset ",
    "学习联动罚时": "Study gate penalty",
    "每日20:00检查；未完成则在各应用自身冻结结束后接续，持续至任务完成，不设固定分钟数。正在放行的应用接在下一段冻结之后，不提前打断放行。待接续跨零点保留；完成任务解除。热爱奖励不抵扣。":
        "Checked daily at 20:00. If goals are unmet, the penalty continues after each app's own freeze ends and lasts until goals are done, with no fixed minutes. Apps currently allowed are not interrupted; the penalty starts after their next freeze. Pending penalties survive midnight and clear when goals are finished. Passion rewards do not offset this.",
    "当前无额外冻结": "No extra freeze right now",
    "距本次检查已过 ": "Since this check: ",
    " · 完成后解除": " · clears when done",
    "打卡设置": "Check-in settings",
    "外观 · 字体 · 同步 · 上传": "Look · fonts · sync · upload",
    "热爱卡": "Passion cards",
    "完成热爱，积累冻结减免": "Do what you love, earn freeze relief",
    "记忆卡": "Memory cards",
    "抽取、学习计划、学科与卡片": "Draws, study plan, subjects and cards",
    " · 周期末必须完成": " · required at cycle end",
    "打卡外观": "Check-in look",
    "背景图片与亚克力": "Background image and acrylic",
    "上传打卡数据": "Upload check-in data",
    "手动上传最新本机台账": "Manually upload the latest local ledger",
    "正在上传": "Uploading",
    "已上传 ": "Uploaded ",
    "上传失败，检查网络后重试": "Upload failed. Check the network and retry.",
    "云端同步未启用或已暂停，请点「同步设置」":
        "Cloud sync is off or paused. Tap \u201cSync settings\u201d.",
    "目标卡": "Goal card",
    "禁忌卡": "Taboo card",
    "刷题卡": "Quiz card",
    "总结卡": "Summary card",
    "可用奖励余额": "Reward balance",
    "每天最多完成一张热爱卡，等概率抽取 1–60 分钟。余额自动抵扣后续定时冻结，学习联动和禁忌惩罚仍然有效。":
        "One passion card per day. The reward is drawn evenly from 1-60 minutes. The balance auto-offsets later scheduled freezes; study-gate and taboo penalties still apply.",
    "今天的热爱奖励已领取": "Today's passion reward is already claimed",
    "今天还可完成一张热爱卡，在打卡页面上滑完成。":
        "You can still finish one passion card today. Swipe up on the check-in page.",
    "＋ 创建热爱卡": "+ Create passion card",
    "添加一件自己热爱的事，例如练琴、运动或绘画。":
        "Add something you love, like piano, sport or drawing.",
    "奖励明细": "Reward history",
    "热爱卡回收站": "Passion card trash",
    "热爱名称": "Passion name",
    "创建热爱卡": "Create passion card",
    "编辑热爱卡": "Edit passion card",
    "移入回收站": "Move to trash",
    "移入回收站？": "Move to trash?",
    "已获得的奖励与余额会保留。": "Earned rewards and the current balance are kept.",
    "移入": "Move",
    "可用余额 ": "Balance ",
    "热爱奖励": "Passion reward",
    "周期最后一天出现总结卡，20:00 后未完成会延长学习联动冻结。跨周期继续保留待办，完成后解除这项联动。":
        "The summary card appears on the last day of a cycle. If unfinished after 20:00, the study-gate freeze is extended. It carries into the next cycle and clears when finished.",
    "直接填写周期天数，例如21天、40天。自定义周期可与每周、每月总结同时启用；各周期分别完成，取消周期不会清除已经到期的总结。":
        "Enter a cycle length in days, such as 21 or 40. The custom cycle can run alongside weekly and monthly summaries; each cycle is completed separately, and cancelling a cycle does not erase summaries already due.",
    "每天总结": "Daily summary",
    "每周总结": "Weekly summary",
    "每月总结": "Monthly summary",
    "启用自定义天数": "Enable custom day count",
    "每隔你填写的天数完成一次总结。填写天数会自动勾选启用。":
        "A summary is due every N days you enter. Typing a number enables this automatically.",
    "每": "Every ",
    "例如21或40": "e.g. 21 or 40",
    "天总结一次": " days",
    "周期天数：1–365天，可输入21、40等整数。":
        "Cycle length: 1-365 days. Whole numbers such as 21 or 40.",
    "周期开始日期": "Cycle start date",
    "选择周期首日 ›": "Pick the first day ›",
    "同时启用其他周期": "Also enable other cycles",
    "可选，可与自定义天数叠加。": "Optional; stacks with the custom day count.",
    "保存总结周期": "Save summary cycle",
    "周期天数须为 1–365 的整数": "Cycle length must be a whole number from 1 to 365",
    "总结周期已保存": "Summary cycle saved",
    "待完成：": "Pending: ",
    " 至 ": " to ",
    "。需单独完成这张总结卡。": ". This summary card must be completed on its own.",
    "自定义周期修改将在旧总结补完后生效：每 ": "Cycle changes apply after pending summaries are done: every ",
    " 天，从 ": " days, from ",
    "例如填写21：每21天总结一次；填写40：每40天总结一次。":
        "Enter 21 for a summary every 21 days; 40 for every 40 days.",
    "天总结一次\n首个周期：": " days\nFirst cycle: ",
    "\n此后每": "\nThen every ",
    "天重复，旧总结未完成会继续保留。": " days; unfinished summaries are kept.",
    "请输入1–365的整数天数和有效的开始日期。": "Enter a whole number of days from 1 to 365 and a valid start date.",
    "%.1f 分钟": "%.1f min",

    # ---------- CardStore ----------
    "卡管理记录超过 8MB": "Card data exceeds 8MB",
    "卡管理读取失败，原文件已保留": "Failed to read card data; the original file was kept",
    "卡管理保存失败，本次操作未确认": "Failed to save card data; this change was not confirmed",
}
