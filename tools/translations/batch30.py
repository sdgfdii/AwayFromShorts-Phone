# -*- coding: utf-8 -*-
"""1.69.0：打卡没完成 → 冻结延长 30 分钟（20:00 判断点），禁忌卡默认时长同步成 30。"""

TEXT = {
    "今日目标已完成 · 20:00 那笔罚时还要等满":
        "Today's goals are done · the 20:00 penalty still has to run out",
    "打卡没完成 · 20:00 判断点":
        "Missed check-in · judged at 20:00",
    "打卡罚时未能保存":
        "Failed to save the check-in penalty",
    "今天 20:00 打卡没打完，各应用冻结已各延长 ":
        "Check-in was unfinished at 20:00, so each app's freeze is extended by ",
    " 分钟（补打也不撤销）":
        " min (finishing later does not undo it)",
    "20:00 前打完卡就不延长；没打完，各应用本次冻结多 ":
        "Finish check-in before 20:00 and nothing is added; miss it and each app's freeze gets ",
    "今天按时打完了卡，不延长":
        "Check-in finished on time, nothing added",
    "\n20:00 后打卡没完成，学习联动会一直冻到打完；打完还要走完那 30 分钟的打卡罚时。":
        "\nAfter 20:00 an unfinished check-in keeps the study gate holding until it is done, and the 30-minute check-in penalty still has to run out.",
    "\n罚时延长冻结还没结束（禁忌卡认领或 20:00 打卡没完成）。":
        "\nA penalty extension is still running (a taboo card was claimed, or check-in was missed at 20:00).",
    "打卡追加：":
        "Check-in extension: ",
    "打卡 + 禁忌追加：":
        "Check-in + taboo extension: ",
    "罚时还没到点：继续冻结到 ":
        "Penalty still running: frozen until ",
}
