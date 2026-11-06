# -*- coding: utf-8 -*-
"""记忆卡改为纯手势评分（认识 / 忘了 / 完全不认识）后新增的文案。"""

TEXT = {
    "手势与评分\n第一次上滑＝认识，评完直接翻下一张；下滑＝忘了，点卡片＝完全不认识，这两条会先揭晓答案，之后只能上滑进入下一张。输入题填了答案再点卡片就是核对：答对算认识，答错算忘了。整个流程只有“认识”和“忘了”两档评分，原来“费力 / 轻松”按钮已删除。今日闪卡每张每天只复习一次；评了“忘了”会记录到错题和重学计划，不会在今日闪卡里反复出现。":
        "Gestures and ratings\nOn the first pass, swipe up for “known” (the card moves straight on), swipe down for “forgot”, or tap the card for “no idea”; the last two reveal the answer, after which only an upward swipe moves on. For typed cards, tapping after typing runs the check: a match counts as known, a mismatch as forgot. Only the “known” and “forgot” ratings exist now — the old “hard / easy” buttons are gone. Each flashcard is reviewed once a day; “forgot” logs a mistake and a relearning item, and the card never repeats in today's set.",

    "新卡的短期学习\n“忘了”：约 1 分钟后可重学；首次“认识”：约 10 分钟后再确认，再次认识后转入按天复习。分钟间隔表示重学计划的到期时间，不会让今日闪卡当天重复抽到该卡。":
        "Short-term learning for new cards\n“Forgot”: relearn in about 1 minute. First “known”: confirm again in about 10 minutes, then move to day-based reviews. Those minute gaps are relearning due times and do not make the card reappear in today's set.",

    "已学卡的复习\n按天复习时评“忘了”，约 10 分钟后进入重学；重学中仍然“忘了”，再等约 10 分钟；认识后恢复按天复习。按天间隔根据评分动态计算，并从到期日零点开放，不是一律等满 24 小时。":
        "Reviews for learned cards\nRating “forgot” in a day-based review starts relearning in about 10 minutes; forgetting again during relearning adds another 10. Becoming “known” returns the card to day-based reviews. Day intervals are computed from ratings and open at the start of the due day, not after a full 24 hours.",

    "共 %d 张，最近 7 天 %d 张。红线是当天的记住率（评“认识”的占比），没有复习的日子不画点。":
        "%d in total, %d in the last 7 days. The red line is that day's recall rate (share of “known” ratings); days without reviews get no dot.",

    "\n上滑＝认识，下滑＝忘了，点卡片＝完全不认识。评“忘了”会进入错题与重学计划。":
        "\nSwipe up = known, swipe down = forgot, tap the card = no idea. “Forgot” adds the card to mistakes and relearning.",

    "上滑＝认识，下滑＝忘了，点卡片＝完全不认识。认识直接翻下一张；忘了和完全不认识先看答案，再上滑继续。":
        "Swipe up = known, swipe down = forgot, tap the card = no idea. “Known” moves straight on; the other two show the answer first, then swipe up to continue.",

    "↑ 上滑进入下一张": "↑ Swipe up for the next card",

    "↑ 上滑＝认识\u3000·\u3000↓ 下滑＝忘了\u3000·\u3000点卡片＝核对答案":
        "↑ swipe up = known   ·   ↓ swipe down = forgot   ·   tap = check answer",

    "↑ 上滑＝认识\u3000·\u3000↓ 下滑＝忘了\u3000·\u3000点卡片＝完全不认识":
        "↑ swipe up = known   ·   ↓ swipe down = forgot   ·   tap = no idea",

    "答案不匹配，本次计为遗忘": "Answer does not match; counted as forgotten",
}
