# -*- coding: utf-8 -*-
"""使用限额新增「挂后台也计时」（每个应用可选）的相关文案。"""

TEXT = {
    "\n挂后台计时：": "\nCounts in background: ",

    "守护运行时约每5秒检查额度。默认只计前台，锁屏、灭屏不计时；勾了「挂后台计时」的应用则打开就计到额度用完。额度耗尽会立刻冻结对应格子的应用，热爱奖励不抵扣。未授予使用情况访问权限时额度照常保存，但暂不计时、不冻结。":
        "The guard checks allowances about every 5 seconds. By default only foreground time counts; locked or off-screen minutes are free. Apps flagged “counts in background” burn their allowance from the moment they are opened until it runs out. When an allowance is spent the apps in that cell freeze at once; love rewards do not cover it. Without usage access the settings are still saved but nothing is counted or frozen.",

    "挂后台也计时 · %d 个应用": "Counts in background · %d apps",

    "先选择应用，再挑谁挂后台也计时": "Pick the apps first, then choose which ones count in background",

    "挂后台也计时（打开就计到额度用完）": "Counts in background (burns from open until the allowance is spent)",

    "取消挂后台计时要等调整时段 %s": "Turning background counting off must wait for the adjust window %s",

    "「挂后台也计时」：勾中的应用今天只要打开过，就一直计到这份额度用完——切到别的应用、锁屏、灭屏都照计。不勾的应用维持只计前台。":
        "“Counts in background”: once an app flagged here is opened today, its allowance keeps burning until spent — switching apps, locking the screen or turning it off does not stop the clock. Unflagged apps keep counting foreground time only.",

    "挂后台计时的应用必须先在这个格子里": "Background-counting apps must belong to this cell first",
}
