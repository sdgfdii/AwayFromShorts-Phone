# -*- coding: utf-8 -*-
"""1.68.1 收尾：微信这类应用隐藏视图树时，界面要照实说规则不会命中。"""

TEXT = {
    "实测「%s」把页面内容对无障碍隐藏了（一屏读到 0 个字），所以「微信视频号」这条在它那儿不会命中。想限住它只能用整应用冻结或每日限额。":
        "Measured: %s hides its page content from accessibility (a full screen yields 0 characters), "
        "so the WeChat Channels rule can never match there. To limit it, use whole-app freeze or a "
        "daily limit instead.",
}
