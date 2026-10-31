# -*- coding: utf-8 -*-
"""1.68.1 收尾：页面拦截的两条说明改成「文字才是主力」。"""

TEXT = {
    "认页面靠页面文字或窗口类名。有些手机读不到窗口类名，所以文字才是主力：几个词用 + 连接，都出现才算，只填一个词很容易误拦。":
        "A page is recognized by its text or by the window class name. Some phones can't report the "
        "window class, so text is the reliable one: join several words with + and all of them must "
        "appear — a single word blocks far too much.",
    "填页面上会出现的字，几个词用 + 连接（都要出现才拦），例如 视频号+推荐。也可以填窗口类名的一段，但有些手机读不到。含中文按文字比对，全英文按类名比对。":
        "Enter words that appear on the page; join several with + (all must appear), for example "
        "视频号+推荐. You can also enter part of the window class name, but some phones can't report "
        "it. Text containing Chinese is matched as text; all-English input is matched as a class name.",
}
