# -*- coding: utf-8 -*-
"""1.72.0：限额与冻结名单的改动闸统一绑到全局收束时段（只进不出）。"""

TEXT = {
    # Capability（上一轮修复遗留）
    " · 现在：": " · Now: ",
    # DailyQuotaActivity
    "移除或减少要等收束时段 %s": "Removals or reductions wait for the focus window %s",
    "一个格子放一个应用，就是它单独的一份额度；放多个应用，就是它们共用同一份额度。应用放进冻结时段后，只累计所绑时段内的用量，额度用完才冻结；未绑定时段则全天累计。%s重置，改格子不清空已用时间。加到格子里的操作随时都能做（加应用、加分钟数）；移出应用、调小分钟数、删格子只在收束时段 %s 内开放。学习联动与禁忌惩罚继续生效。":
        "One app in a cell gets its own allowance; several apps share one. Once tied to a freeze window, only in-window usage counts; untied cells count all day. Resets at %s; edits never reset used time. Adding (apps or minutes) works any time; removing apps, lowering minutes and deleting cells open only inside the focus window %s. Study links and taboo penalties keep working.",
    "收束时段 · %s": "Focus window · %s",
    "关闭限额要等收束时段 %s": "Turning quotas off waits for the focus window %s",
    "现在只能加：勾上就进这个格子。想把应用拿出去，等收束时段 %s。":
        "Add-only right now: tick apps to bring them in. Taking apps out waits for the focus window %s.",
    "现在只能加分钟数，调小要等收束时段 %s": "Minutes can only go up right now; lowering waits for the focus window %s",
    # DailyQuotaData / DailyQuotaStore
    "这个改动要减少额度，只在收束时段内开放": "This change lowers an allowance and opens only inside the focus window",
    "这个改动要减少额度，只在收束时段 %s 内开放": "This change lowers an allowance and opens only inside the focus window %s",
    # FreezeBoardActivity
    "移出冻结要等收束时段 %s": "Unfreezing waits for the focus window %s",
    "移出冻结时段要等收束时段 %s": "Leaving the freeze window waits for the focus window %s",
    "这一步会减少额度，只在收束时段 %s 内开放": "This lowers an allowance and opens only inside the focus window %s",
    "现在只能加：把成员减到一只要等收束时段 %s": "Add-only right now: shrinking to a single app waits for the focus window %s",
    "单独额度只有一只应用，换应用要等收束时段 %s": "A solo allowance holds one app; swapping waits for the focus window %s",
    "移出冻结列表要等收束时段 %s": "Leaving the freeze list waits for the focus window %s",
    "%d 个应用要等收束时段 %s 才能移出": "%d apps can leave only inside the focus window %s",
    "%d 个新应用要等收束时段 %s 才能加入": "%d new apps can join only inside the focus window %s",
    "关键应用名单要等收束时段 %s 才能改": "The key-app list can be changed only inside the focus window %s",
}
