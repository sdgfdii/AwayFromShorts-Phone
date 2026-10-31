# -*- coding: utf-8 -*-
"""第二十批：定时提醒（监督玩机）的文案，以及题库题型名的显示文案。"""

TEXT = {
    # ControlActivity：定时提醒分组
    "开启提醒": "Reminders",
    "通知没开，提醒发不出来": "Notifications are off — the reminder can't show up",
    "每隔多久提醒": "Remind me every",
    "只发一条通知，不拦人。灭屏、正在锁机、放行期里都不打扰。":
        "Just a notification, nothing gets blocked. Stays quiet when the screen is off, "
        "while a lock is running, or during an unlock pass.",
    "和上面的定时锁机是两回事：锁机是硬拦，这个是轻提醒，可以只开一个。":
        "Separate from the scheduled lock above: the lock blocks, this one only nudges. "
        "Either can run on its own.",

    # RestAlert
    "休息提醒": "Break reminders",
    "该歇会儿了": "Time for a break",
    "已经连续 %d 分钟没休息，站起来走走，看看远处。":
        "%d minutes without a break — stand up, walk around, look into the distance.",

    # ReminderStore
    "每 %d 分钟 · %s": "Every %d min · %s",

    # QuizData：题型名（只用于显示，不参与存储）
    "单选题": "Single choice",
    "多选题": "Multiple choice",
    "判断题": "True / false",
    "填空题": "Fill in the blank",
    "解答题": "Free response",
}
