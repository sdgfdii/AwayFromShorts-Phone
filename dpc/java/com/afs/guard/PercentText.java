package com.afs.guard;

import java.util.Locale;

/**
 * 完成度百分比文案：目标页统一显示到小数点后一位。
 *
 * 以前是 floor 成整数（33%），看不出一分钟之内的推进；改成一位小数后
 * 「33.3%」既保留粗读节奏，又能看出真的在往前走。
 * 纯函数，不碰 Android，离线回归可以直接断言。
 */
final class PercentText {
    private PercentText(){}

    /** "33.3" —— 不含百分号，负数、除零、NaN 都按 0.0 处理。 */
    static String of(double done,double total){
        double value=total<=0||Double.isNaN(done)||Double.isNaN(total)?0:done*100/total;
        if(!(value>0))value=0;
        return String.format(Locale.CHINA,"%.1f",value);
    }

    /** "33.3%" —— 页面直接用这一份，省得每个调用点自己拼百分号。 */
    static String percent(double done,double total){
        return of(done,total)+"%";
    }
}
