# -*- coding: utf-8 -*-
"""1.68.1：亮屏累计锁机、久用锁选应用、应用内页面拦截。"""

TEXT = {
    "%d 条": "%d rules",
    "finder": "finder",
    "一直待在同一个应用里才算连续；切走再回来重新计时。不选应用时对全部应用生效，短视频这类最容易被抓。":
        "Only staying in one app counts as continuous; switching away restarts the clock. "
        "With no app selected this applies to everything — short-video apps are what it catches first.",
    "一键添加": "Add for me",
    "亮屏累计锁机": "Screen-on lock",
    "今天拦了 %d 次": "Blocked %d times today",
    "全部应用": "All apps",
    "删掉": "Delete",
    "删掉这条规则": "Delete this rule",
    "加一条": "Add rule",
    "加了 %d 条规则": "Added %d rules",
    "只监控选中的应用；一个都不选就是全部": "Only the apps you pick are watched; none means all",
    "只算屏幕亮着的时间：灭屏、正在锁着的那几分钟都不累计。累计满一轮就锁一次，锁完从零开始算。":
        "Only counts time with the screen on: screen-off and the locked minutes are not counted. "
        "Each full round triggers one lock, then the count restarts.",
    "和「每隔 45 分钟锁 5 分钟」不一样：不按钟点走，摸鱼少的日子不会照样被锁，盯屏久的下午会锁得更勤。":
        "Unlike \"lock 5 minutes every 45 minutes\", this does not follow the clock: "
        "light days are not locked anyway, heavy afternoons get locked more.",
    "在「%s」里挡住了「%s」": "Blocked in \"%s\": \"%s\"",
    "在哪个应用里拦这个页面": "Which app should this page be blocked in",
    "填窗口类名里的一段（如 finder），或页面上的固定文字。含中文按文字比对，全英文按类名比对。":
        "Enter part of the window class name (e.g. finder) or a fixed string on the page. "
        "Chinese text is matched against page text, plain ASCII against the class name.",
    "已关闭": "Off",
    "已删掉": "Deleted",
    "已加上视频号规则": "Channels rule added",
    "已添加": "Added",
    "已累计够一轮": "One round reached",
    "开启页面拦截": "Block these pages",
    "得填点东西": "Type something first",
    "微信视频号": "WeChat Channels",
    "拦什么页面": "Which page to block",
    "每天几点重置额度": "Daily limit reset time",
    "生效时段": "Active hours",
    "监控哪些应用": "Apps to watch",
    "窗口类名": "Window class",
    "累计亮屏多久就锁": "Lock after this much screen-on time",
    "规则": "Rules",
    "规则只在选定的应用里生效。屏幕内容只在本机比对，不保存、不上传。":
        "Rules apply only in the apps you picked. Screen text is compared on this device only; "
        "nothing is stored or uploaded.",
    "视频号规则已经在里面了": "The Channels rule is already there",
    "认页面靠窗口类名或页面文字：类名更准（视频号那种独立页面用 finder 就够），填文字方便但同样的字出现在别处也会被拦。":
        "Pages are recognised by window class or on-screen text: class names are precise "
        "(finder is enough for Channels); text is easier to fill, but the same words elsewhere get blocked too.",
    "还没有拦到": "Nothing blocked yet",
    "还没有规则。可以先用下面的「微信视频号」加一条，或者自己挑应用和页面特征。":
        "No rules yet. Add the WeChat Channels one below, or pick an app and a page marker yourself.",
    "还没有规则，先在下面加一条": "No rules yet — add one below first",
    "还没配规则": "No rules yet",
    "这一轮": "This round",
    "这个页面不看了": "Not this page",
    "这条规则已经有了": "That rule already exists",
    "进到指定页面就盖住并把人送回桌面，同一个应用里的其他页面不受影响。":
        "Opening the chosen page covers it and sends you home; other pages in the same app are unaffected.",
    "页面文字": "Page text",
    "页面规则": "Page rules",
    "额度重置时刻": "Limit reset time",
    "额度重置时刻 ": "Limit reset time ",
}
