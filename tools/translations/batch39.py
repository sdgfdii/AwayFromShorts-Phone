# -*- coding: utf-8 -*-
"""1.73.2：微信页面拦截改成按窗口类名，界面说明跟着改。"""

TEXT = {
    "「%s」不把页面文字交给系统，所以微信视频号 / 朋友圈这两条是按窗口类名（plugin.finder.ui.* / plugin.sns.ui.*）认页面的；进到这两个页面会被盖住并送回桌面。":
        "“%s” never exposes page text to the system, so the WeChat Channels / Moments rules recognise pages by window class (plugin.finder.ui.* / plugin.sns.ui.*). Entering those pages shows the mask and sends you back to the home screen.",
}
