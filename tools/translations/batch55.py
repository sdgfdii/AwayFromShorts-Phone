# -*- coding: utf-8 -*-
"""打卡罚时改为覆盖所有被冻结的应用（1.89.0）。"""

TEXT = {
    "今天 20:00 打卡没打完，所有被冻结的应用在今日放行点后各延长 ":
        "Today's 20:00 check-in was unfinished, so every frozen app stays frozen ",
    "20:00 前打完卡就不延长；没打完，所有被冻结的应用本次冻结多 ":
        "Finish before 20:00 and nothing is added; otherwise every frozen app stays frozen ",
    " 分钟（补打也不撤销）": " minutes longer at today's release point (finishing later does not undo it)",
    " 分钟": " minutes longer",
}
