# -*- coding: utf-8 -*-
"""挂后台计时口径修正（1.88.0）：切到后台「还在跑」（前台服务运行）才计，关掉就停。"""

TEXT = {
    "挂后台也计时（切到后台还在跑就照计）": "Counts in background (keeps counting while it still runs)",

    "「挂后台也计时」：勾中的应用切到后台后，只要还在跑（音乐播放、画中画这类前台服务在运行）就继续计时，关掉就停；锁屏、灭屏也不打断。切走闲置、应用已关闭都不计。不勾的应用维持只计前台。":
        "“Counts in background”: after a flagged app leaves the foreground, its clock keeps running while it is still active — music playback, picture-in-picture and other foreground services — even with the screen off or locked. It stops the moment the app closes. Idle or closed apps never count. Unflagged apps keep counting foreground time only.",

    "守护运行时约每5秒检查额度。默认只计前台，锁屏、灭屏不计时；勾了「挂后台计时」的应用，切到后台只要还在跑（前台服务在运行）就照计，关掉就停。额度耗尽会立刻冻结对应格子的应用，热爱奖励不抵扣。未授予使用情况访问权限时额度照常保存，但暂不计时、不冻结。":
        "The guard checks allowances about every 5 seconds. By default only foreground time counts; locked or off-screen minutes are free. Apps flagged “counts in background” keep counting while a foreground service is still running after they leave the foreground, and stop once closed. When an allowance is spent the apps in that cell freeze at once; love rewards do not cover it. Without usage access the settings are still saved but nothing is counted or frozen.",
}
