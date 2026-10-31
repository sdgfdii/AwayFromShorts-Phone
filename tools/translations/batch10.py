# -*- coding: utf-8 -*-
"""第十批英文文案：补漏（打卡页顶栏与背景、限额说明、备忘录页头）。

这批是分段读取时被切在边界上的条目，不是新功能。
"""

TEXT = {
    # ---------- CheckinActivity 顶栏与背景 ----------
    "日历": "Calendar",
    "背景": "Background",
    "同步": "Sync",
    "上传": "Upload",
    "上传中": "Uploading",
    "历史": "History",
    "上滑平移打卡 · 左右推换目标 · 长按改数量": "Swipe up to check in · left/right to switch goals · long-press to change the amount",
    "无背景（纯色）": "No background (solid colour)",
    "内置亚克力底": "Built-in acrylic",
    "从相册选一张…": "Pick one from the gallery…",
    "清除自定义图片": "Clear the custom image",
    "打卡页背景": "Check-in background",
    "已关掉背景": "Background turned off",
    "已换成内置亚克力底": "Switched to the built-in acrylic",
    "已清除自定义图片": "Custom image cleared",
    "#RRGGBB，如 FFD700": "#RRGGBB, e.g. FFD700",

    # ---------- DailyQuotaActivity 补漏 ----------
    "‹  冻结应用": "‹  Frozen apps",
    "一个格子放一个应用，就是它单独的一份额度；放多个应用，就是它们共用同一份额度。应用放进冻结时段后，只累计所绑时段内的用量，额度用完才冻结；未绑定时段则全天累计。":
        "One app in a quota means its own allowance; several apps share it. Apps inside a freeze window only count usage within their bound hours and freeze when the quota runs out; unbound quotas count all day.",
    "重置，改格子不清空已用时间。学习联动与禁忌惩罚继续生效。":
        " resets. Editing a quota does not clear time already used. Study-gate and taboo penalties still apply.",
    "启用每日限额": "Enable daily quota",
    "（全天）": " (all day)",

    # ---------- MemoActivity 页头与备份 ----------
    "无法写入备份": "Cannot write the backup",
    "完整备份已导出": "Full backup exported",
    "合并恢复备忘录？": "Merge the backup into your notes?",
    "备份有 ": "The backup has ",
    " 条笔记和 ": " notes and ",
    " 条回收站记录。现有内容保留，冲突记录另存副本。":
        " trash entries. Existing content is kept and conflicting records are saved as copies.",
    "合并恢复": "Merge",
    "备份已合并恢复": "Backup merged",
    "恢复失败：": "Restore failed: ",
    "备份操作失败：": "Backup failed: ",
    "文件是空的": "The file is empty",
    "导入失败：请使用不超过 2 万字的 UTF-8 文本或 Markdown 文件":
        "Import failed: use a UTF-8 text or Markdown file of at most 20000 characters",
    "导出失败，请重试": "Export failed. Try again.",
    "搜索备忘录": "Search notes",
    "笔记本": "Notebooks",
    "列表/网格": "List/grid",
    "导入文件": "Import file",
    "开源说明": "Open-source notice",
    "共 0 条": "0 notes",
    " 条": "",
    "没有找到备忘录": "No notes found",
    "还没有备忘录": "No notes yet",
    "换个词试试，再点 ⌕ 收起搜索": "Try another word, then tap ⌕ to close search",
    "点右上角 ＋ 写第一条": "Tap + at the top right to write the first one",
    "待办 ": "To-do ",
    "提醒 ": "Reminder ",
    "无标题": "Untitled",
    "删除这条备忘录": "Delete this note",
    "标题": "Title",
    "写点什么…": "Write something…",
    "高亮 / 取消高亮": "Highlight / remove highlight",
    "块编辑": "Block edit",
}
