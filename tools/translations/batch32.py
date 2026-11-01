# -*- coding: utf-8 -*-
"""1.71.0：全局收束时段（TimeWindow）——断网与每日限额统一绑定，附耗电降频。"""

TEXT = {
    # NetBlockStore / FreezeBoardActivity
    "%d 个应用 · %s 才生效": "%d apps · active %s",
    "%d 个应用断网中 · %s": "%d apps cut off · %s",
    "已开启，%s 才断网": "Enabled, cuts network at %s",
    "断网只在全局收束时段 %s 内生效，到点自动断、过点自动放。":
        "Network cut only takes effect inside the global focus window %s; it switches on and off automatically.",
    # DailyQuotaStore
    "时段外不计 · ": "Outside window · ",
    # TimeWindow
    "时刻只能是 0:00–23:59": "Time must be 0:00-23:59",
    "收束时段未能保存": "Could not save the focus window",
}
