# -*- coding: utf-8 -*-
"""补齐未译文案：挂后台说明改版 + 统计插件相关提示。"""

TEXT = {
    "「挂后台也计时」：勾中的应用切到后台后，只要还在跑（音乐播放、画中画这类前台服务在运行）就继续计时，关掉就停；锁屏、灭屏也不打断。不勾的应用维持只计前台。":
        "“Counts in background”: once a flagged app leaves the foreground, its clock keeps running while it is still active — music playback, picture-in-picture and other foreground services — even with the screen off or locked, and it stops the moment the app closes. Unflagged apps keep counting foreground time only.",

    "📊 ": "📊 ",

    "正在打开统计插件…": "Opening the stats plugin…",

    "统计插件未安装或未启用。\n\n它现在是独立 APK，需要你在「插件管理」里确认数据范围并启用；停用单个插件不会回到旧统计页。":
        "The stats plugin is not installed or not enabled.\n\nIt now ships as a separate APK. Confirm its data scope and enable it in “Plugin management”; disabling one plugin does not bring back the old stats page.",

    "打开插件管理": "Open plugin management",

    "统计插件启动失败：": "Stats plugin failed to start: ",

    "可以直接返回，或在插件管理中刷新后重试。":
        "You can go back, or refresh in plugin management and try again.",
}
