package com.afs.guard;

import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 离线回归 —— 不连手机、不需要 Android 运行时。
 *
 * 判定已经完全参数化：每个应用一个 {@link Rule}（星期掩码 + 起止分钟），
 * 一个进程里就能把多套规则、多个应用一起跑，不用 sed 改常量重编第二份。
 * 学习闸门的判定也是纯函数（{@link StudyGate#parse} / {@link StudyGate#shouldHold}），
 * 在这里一起对拍。
 */
public final class GuardTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        everyDay();
        crossMidnight();
        onlyMonday();
        weekendOnly();
        multiApp();
        twoWindows();
        textTests();
        groupKeyTests();
        studyGate();
        tabooCard();
        pcStats();
        ok(StudyStore.canUndoDate("2026-10-24", "2026-10-24"), "当天打卡允许撤回");
        ok(!StudyStore.canUndoDate("2026-10-23", "2026-10-24"), "昨天打卡不允许撤回");
        ok(!StudyStore.canUndoDate("2026-10-24", "2026-10-25"), "确认期间跨午夜也不允许撤回");
        ok(!StudyStore.canUndoDate("", ""), "缺少日期不能撤回");

        System.out.println();
        System.out.println("== " + pass + " PASS / " + fail + " FAIL ==");
        if (fail > 0) {
            System.exit(1);
        }
    }

    /* ---------- 1. 每天 22:00–23:00 ---------- */

    private static void everyDay() {
        System.out.println("---- 每天 22:00–23:00 ----");
        Rule r = new Rule(0x7F, 22 * 60, 23 * 60);

        eq(blocked(r, 2026, 10, 19, 21, 59), true, "周一 21:59 屏蔽");
        eq(blocked(r, 2026, 10, 19, 22, 0), false, "周一 22:00 放行");
        eq(blocked(r, 2026, 10, 19, 22, 59), false, "周一 22:59 放行");
        eq(blocked(r, 2026, 10, 19, 23, 0), true, "周一 23:00 屏蔽");
        eq(blocked(r, 2026, 10, 25, 12, 0), true, "周日 12:00 屏蔽");

        int allowMin = 0;
        for (int m = 0; m < 1440; m++) {
            if (r.inWindow(at(2026, 10, 19, m / 60, m % 60))) {
                allowMin++;
            }
        }
        eq(allowMin, 60, "每天放行恰好 60 分钟");

        flipProperty(r);
    }

    /* ---------- 2. 跨午夜 23:00–20:00 ---------- */

    private static void crossMidnight() {
        System.out.println("---- 每天 23:00–次日 20:00（跨午夜）----");
        Rule r = new Rule(0x7F, 23 * 60, 20 * 60);

        eq(blocked(r, 2026, 10, 19, 22, 59), true, "周一 22:59 屏蔽");
        eq(blocked(r, 2026, 10, 19, 23, 0), false, "周一 23:00 放行");
        eq(blocked(r, 2026, 10, 20, 3, 0), false, "周二 03:00 仍在放行（属于周一窗口）");
        eq(blocked(r, 2026, 10, 20, 19, 59), false, "周二 19:59 放行");
        eq(blocked(r, 2026, 10, 20, 20, 0), true, "周二 20:00 屏蔽");

        int allowMin = 0;
        for (int m = 0; m < 1440; m++) {
            if (r.inWindow(at(2026, 10, 19, m / 60, m % 60))) {
                allowMin++;
            }
        }
        eq(allowMin, 21 * 60, "每天放行 21 小时（1260 分）");

        flipProperty(r);
    }

    /* ---------- 3. 只选周一 ---------- */

    private static void onlyMonday() {
        System.out.println("---- 只选周一 22:00–23:00 ----");
        Rule r = new Rule(0x01, 22 * 60, 23 * 60);

        eq(blocked(r, 2026, 10, 19, 22, 30), false, "周一 22:30 放行");
        eq(blocked(r, 2026, 10, 20, 22, 30), true, "周二 22:30 屏蔽（未选周二）");
        eq(blocked(r, 2026, 10, 25, 22, 30), true, "周日 22:30 屏蔽");
        eq(blocked(r, 2026, 10, 20, 12, 0), true, "周二中午 屏蔽");

        Calendar f = cal(r.nextFlip(at(2026, 10, 19, 22, 30)));
        eq(Rule.hm(f.get(Calendar.HOUR_OF_DAY), f.get(Calendar.MINUTE)), "23:00",
                "周一 22:30 的下次翻转是当天 23:00");

        Calendar f2 = cal(r.nextFlip(at(2026, 10, 19, 23, 30)));
        eq(Rule.hm(f2.get(Calendar.HOUR_OF_DAY), f2.get(Calendar.MINUTE)), "22:00",
                "周一 23:30 的下次翻转是 22:00");
        eq(f2.get(Calendar.DAY_OF_MONTH), 26, "而且落在下周一（10/26）而不是本周二");
    }

    /* ---------- 4. 只选周末 ---------- */

    private static void weekendOnly() {
        System.out.println("---- 只选周六日 22:00–23:00 ----");
        Rule r = new Rule(0x60, 22 * 60, 23 * 60);

        eq(blocked(r, 2026, 10, 24, 22, 30), false, "周六 22:30 放行");
        eq(blocked(r, 2026, 10, 25, 22, 30), false, "周日 22:30 放行");
        eq(blocked(r, 2026, 10, 23, 22, 30), true, "周五 22:30 屏蔽");
    }

    /* ---------- 5. 多个应用互不影响 ---------- */

    private static void multiApp() {
        System.out.println("---- 多应用：各自时段 / 取最早的翻转 ----");
        Rule douyin = new Rule(0x7F, 22 * 60, 23 * 60);   // 每天 22:00-23:00 能刷
        Rule wechat = new Rule(0x7F, 7 * 60, 22 * 60);    // 每天 07:00-22:00 能用

        eq(blocked(douyin, 2026, 10, 19, 12, 0), true, "中午：抖音冻结");
        eq(blocked(wechat, 2026, 10, 19, 12, 0), false, "中午：微信可用（同一时刻不同结论）");

        eq(blocked(douyin, 2026, 10, 19, 22, 30), false, "22:30：抖音放行");
        eq(blocked(wechat, 2026, 10, 19, 22, 30), true, "22:30：微信冻结");

        // 06:00 时：微信 07:00 开始放行，抖音要到 22:00 → 只能排最早的 07:00
        Calendar early = at(2026, 10, 19, 6, 0);
        Calendar f = cal(Guard.nextFlipOf(Arrays.asList(douyin, wechat), early));
        eq(Rule.hm(f.get(Calendar.HOUR_OF_DAY), f.get(Calendar.MINUTE)), "07:00",
                "★ 下次翻转取最早的（07:00，不是抖音的 22:00）");

        // 21:30 时：两个应用都在 22:00 换挡
        Calendar late = at(2026, 10, 19, 21, 30);
        Calendar f2 = cal(Guard.nextFlipOf(Arrays.asList(douyin, wechat), late));
        eq(Rule.hm(f2.get(Calendar.HOUR_OF_DAY), f2.get(Calendar.MINUTE)), "22:00",
                "21:30 的下次翻转是 22:00（两个应用同一时刻换挡）");

        // 空列表不能返回"过去的时间"，否则闹钟会立即空转
        long none = Guard.nextFlipOf(java.util.Collections.<Rule>emptyList(), noon());
        ok(none > noon().getTimeInMillis(), "没有应用时也要给出未来的时刻");
    }

    /* ---------- 6. 一个应用两段时段 ---------- */

    private static void twoWindows() {
        System.out.println("---- 一个应用两段时段（12:00-13:00 + 21:00-23:00）----");
        // 故意把 21:00 段写在前，验证 normalize 会按 start 升序重排
        Rule r = new Rule(0x7F, new int[][] { { 21 * 60, 23 * 60 }, { 12 * 60, 13 * 60 } });

        eq(r.windowCount(), 2, "两段都在");
        eq(r.startAt(0), 12 * 60, "段0 = 12:00（已按起点排序）");
        eq(r.startAt(1), 21 * 60, "段1 = 21:00");

        eq(blocked(r, 2026, 10, 19, 12, 30), false, "12:30 放行（第一段）");
        eq(blocked(r, 2026, 10, 19, 14, 0), true, "14:00 屏蔽（两段之间）");
        eq(blocked(r, 2026, 10, 19, 21, 30), false, "21:30 放行（第二段）");
        eq(blocked(r, 2026, 10, 19, 11, 0), true, "11:00 屏蔽");

        int allowMin = 0;
        for (int m = 0; m < 1440; m++) {
            if (r.inWindow(at(2026, 10, 19, m / 60, m % 60))) {
                allowMin++;
            }
        }
        eq(allowMin, 180, "两段合计放行 180 分钟（60 + 120）");

        Calendar f = cal(r.nextFlip(at(2026, 10, 19, 14, 0)));
        eq(Rule.hm(f.get(Calendar.HOUR_OF_DAY), f.get(Calendar.MINUTE)), "21:00",
                "14:00 的下次翻转是当晚 21:00（跳过中间的空档）");

        Calendar f2 = cal(r.nextFlip(at(2026, 10, 19, 22, 0)));
        eq(Rule.hm(f2.get(Calendar.HOUR_OF_DAY), f2.get(Calendar.MINUTE)), "23:00",
                "22:00 的下次翻转是 23:00");

        Calendar f3 = cal(r.nextFlip(at(2026, 10, 19, 23, 30)));
        eq(Rule.hm(f3.get(Calendar.HOUR_OF_DAY), f3.get(Calendar.MINUTE)), "12:00",
                "23:30 的下次翻转是次日 12:00");
        eq(f3.get(Calendar.DAY_OF_MONTH), 20, "确实是明天");

        flipProperty(r);

        // 增删段不可变 + 文案
        Rule a = r.addWindow(7 * 60, 8 * 60);
        eq(a.windowCount(), 3, "addWindow 后三段");
        eq(r.windowCount(), 2, "原规则不被 addWindow 改动");
        Rule b = a.removeWindow(0);
        eq(b.startAt(0), 12 * 60, "removeWindow(0) 删掉的是 07:00 段（按 start 排序后）");
        Rule c = r.withWindow(1, 22 * 60, 23 * 60);
        eq(c.startAt(1), 22 * 60, "withWindow 改第二段起点");
        eq(r.startAt(1), 21 * 60, "原规则不被 withWindow 改动");
        ok(r.windowText().contains("12:00-13:00") && r.windowText().contains("21:00-23:00"),
                "windowText 两段都列出");

        // 空时段 = 整天冻结
        Rule none = new Rule(0x7F, new int[0][2]);
        eq(none.windowCount(), 0, "空时段");
        eq(blocked(none, 2026, 10, 19, 12, 30), true, "空时段 = 任何时刻都屏蔽");
        ok(none.windowText().contains("整天冻结"), "空时段文案要说明白");
    }

    /* ---------- 7. 文案 ---------- */

    private static void textTests() {
        System.out.println("---- 文案 ----");
        eq(Rule.daysText(0x7F), "每天", "0x7F → 每天");
        eq(Rule.daysText(0x1F), "工作日", "0x1F → 工作日");
        eq(Rule.daysText(0x60), "周末", "0x60 → 周末");
        eq(Rule.daysText(0x41), "周一、周日", "0x41 → 周一、周日");
        ok(Rule.daysText(0).contains("一天都没选"), "0 → 提示一天都没选");
        eq(Rule.hm(22, 0), "22:00", "hm 格式化");
        eq(new Rule(0x7F, 23 * 60, 20 * 60).summary(), "每天 23:00-20:00（含跨午夜）",
                "跨午夜要在文案里标出来");
    }

    /* ---------- 8. 分组键（黑名单折叠成卡片） ---------- */

    /**
     * 分组键决定"哪些应用折成同一张卡"，判错的后果是：
     * 卡上写着"每天 22:00-23:00 放行"，里面却混进一个 21:00-23:00 的 —— 
     * 用户照着卡上的信息去理解行为，只会得出错误结论。
     */
    private static void groupKeyTests() {
        System.out.println("---- 分组键 ----");
        Rule a = new Rule(0x7F, 22 * 60, 23 * 60);
        Rule b = new Rule(0x7F, 22 * 60, 23 * 60);
        eq(a.groupKey(), b.groupKey(), "同规则同键（可折叠）");
        ok(a.same(b), "同规则的 same() 也成立");

        Rule c = new Rule(0x7F, 21 * 60, 23 * 60);
        ok(!a.groupKey().equals(c.groupKey()), "时段不同 → 不同键");

        Rule d = new Rule(0x1F, 22 * 60, 23 * 60);
        ok(!a.groupKey().equals(d.groupKey()), "放行星期不同 → 不同键");

        Rule e = a.withGate(false);
        ok(!a.groupKey().equals(e.groupKey()), "gate 不同 → 不同键（关键应用要单独一张卡）");

        // 段的顺序不影响分组：构造时就按 start 排好了
        Rule f = new Rule(0x7F, new int[][] { { 21 * 60, 23 * 60 }, { 12 * 60, 13 * 60 } });
        Rule g = new Rule(0x7F, new int[][] { { 12 * 60, 13 * 60 }, { 21 * 60, 23 * 60 } });
        eq(f.groupKey(), g.groupKey(), "段顺序不同但内容相同 → 同一张卡");

        Rule h = new Rule(0x7F, new int[][] { { 12 * 60, 13 * 60 } });
        ok(!f.groupKey().equals(h.groupKey()), "段数不同 → 不同键");

        eq(new Rule(0, new int[0][2]).groupKey(), new Rule(0, new int[0][2]).groupKey(),
                "空规则同键（都是整天冻结）");
        ok(!new Rule(0x7F, new int[0][2]).groupKey()
                .equals(new Rule(0x1F, new int[0][2]).groupKey()),
                "空时段下星期不同仍是不同键");
    }

    /* ---------- 10. 学习闸门 ---------- */

    private static void studyGate() {
        System.out.println("---- 学习闸门：解析 / 判定 / fail-open ----");

        // 8.1 闸门不再挑时间：只要今天没打完，任何时刻都 hold
        System.out.println("  · 不看时段（直接延长冻结）");

        // 8.2 解析：好 JSON
        System.out.println("  · 解析 parse");
        StudyGate.Snapshot ok1 = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":false,\"pending\":2,"
              + "\"checkedAt\":\"2026-10-22 22:00\"}",
                "2026-10-22", stampMs("2026-10-22 22:10"));
        ok(ok1.valid, "好 JSON 解析成功");
        ok(ok1.enabled, "enabled=true");
        eq(ok1.startMin, 22 * 60, "start=22:00");
        eq(ok1.endMin, 23 * 60, "end=23:00");
        ok(!ok1.allDone, "allDone=false");
        ok(ok1.trustworthy("2026-10-22", stampMs("2026-10-22 22:30")),
                "30 分钟前检查的数据可信（45 分钟新鲜线内）");

        // 8.3 fail-open：各种不可信数据都必须放行
        System.out.println("  · fail-open");
        StudyGate.Snapshot bad1 = StudyGate.parse("not a json", "2026-10-22", 0L);
        ok(!bad1.valid, "坏 JSON → 不可信");
        StudyGate.Snapshot bad2 = StudyGate.parse(
                "{\"date\":\"2026-10-21\",\"enabled\":true,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":false,\"checkedAt\":\"2026-10-21 20:00\"}",
                "2026-10-22", stampMs("2026-10-22 20:10"));
        ok(!bad2.trustworthy("2026-10-22", stampMs("2026-10-22 20:10")),
                "日期不是今天 → 不可信");
        StudyGate.Snapshot bad3 = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":false,\"checkedAt\":\"2026-10-22 11:00\"}",
                "2026-10-22", stampMs("2026-10-22 20:10"));
        ok(!bad3.trustworthy("2026-10-22", stampMs("2026-10-22 20:10")),
                "checkedAt 超过 45 分钟 → 不可信（fail-open 与 PC 一致）");
        StudyGate.Snapshot bad4 = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"error\":\"flag-error\",\"checkedAt\":\"2026-10-22 20:00\"}",
                "2026-10-22", stampMs("2026-10-22 20:10"));
        ok(!bad4.valid, "PC 端报 error 字段 → 不可信");
        ok(StudyGate.parse("", "2026-10-22", 0L) == StudyGate.Snapshot.EMPTY
                || !StudyGate.parse("", "2026-10-22", 0L).valid,
                "空串 → 不可信");
        StudyGate.Snapshot nullSnap = null;
        ok(!StudyGate.shouldHold(nullSnap, at(2026, 10, 22, 22, 30)), "null 快照 → 放行");

        // 8.4 shouldHold 判定
        System.out.println("  · 生效判定 shouldHold");
        Calendar at2230 = at(2026, 10, 22, 22, 30);
        ok(StudyGate.shouldHold(ok1, at2230),
                "开启+可信+未打卡 → hold");
        // 时段边界前后的两刻，各用新鲜数据判（否则先被 fail-open 拦下，测的就不是这件事）
        StudyGate.Snapshot early = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\",\"end\":\"23:00\","
                        + "\"allDone\":false,\"checkedAt\":\"2026-10-22 21:20\"}",
                "2026-10-22", stampMs("2026-10-22 21:30"));
        ok(StudyGate.shouldHold(early, at(2026, 10, 22, 21, 59)),
                "★ 21:59 还没到「学习时段」也 hold（不再按固定时间延长）");
        StudyGate.Snapshot late = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\",\"end\":\"23:00\","
                        + "\"allDone\":false,\"checkedAt\":\"2026-10-22 22:40\"}",
                "2026-10-22", stampMs("2026-10-22 22:40"));
        ok(StudyGate.shouldHold(late, at(2026, 10, 22, 23, 0)),
                "★ 23:00 时段结束后仍在 hold（直到打完为止）");
        // 20:00 前只按应用原有冻结时间运行。
        StudyGate.Snapshot morning = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\",\"end\":\"23:00\","
                        + "\"allDone\":false,\"checkedAt\":\"2026-10-22 09:00\"}",
                "2026-10-22", stampMs("2026-10-22 09:10"));
        ok(!StudyGate.shouldHold(morning, at(2026, 10, 22, 9, 30)),
                "★ 早上 9:30 未打卡，不延长冻结");
        StudyGate.Snapshot beforeCheck = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"allDone\":false,"
                        + "\"checkedAt\":\"2026-10-22 19:55\"}",
                "2026-10-22", stampMs("2026-10-22 19:55"));
        ok(!StudyGate.shouldHold(beforeCheck, at(2026, 10, 22, 19, 59)),
                "19:59 未打卡，仍不延长冻结");
        ok(StudyGate.shouldHold(beforeCheck, at(2026, 10, 22, 20, 0)),
                "20:00 未打卡，开始延长冻结");
        eq(StudyGate.stateOf(beforeCheck, at(2026, 10, 22, 19, 59)), StudyGate.ST_PENDING,
                "20:00 前显示待检查");
        ok(StudyGate.textOf(beforeCheck, at(2026, 10, 22, 19, 59)).contains("20:00"),
                "待检查文案说明 20:00 边界");
        eq(StudyGate.nextCheckBoundaryMillis(at(2026, 10, 22, 19, 59)),
                at(2026, 10, 22, 20, 0).getTimeInMillis(), "下次检查精确排到 20:00");
        eq(StudyGate.nextCheckBoundaryMillis(at(2026, 10, 22, 20, 0)),
                at(2026, 10, 23, 0, 0).getTimeInMillis(), "20:00 后次日零点重置");

        StudyGate.Snapshot done = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":true,\"checkedAt\":\"2026-10-22 22:00\"}",
                "2026-10-22", stampMs("2026-10-22 22:10"));
        ok(!StudyGate.shouldHold(done, at2230), "全部打卡 → 放行");

        StudyGate.Snapshot off = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":false,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":false,\"checkedAt\":\"2026-10-22 22:00\"}",
                "2026-10-22", stampMs("2026-10-22 22:10"));
        ok(!StudyGate.shouldHold(off, at2230), "PC 端开关关 → 放行");

        StudyGate.Snapshot badHold = StudyGate.parse(
                "{\"date\":\"2026-10-21\",\"enabled\":true,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":false,\"checkedAt\":\"2026-10-21 20:00\"}",
                "2026-10-21", stampMs("2026-10-22 20:10"));
        ok(!StudyGate.shouldHold(badHold, at2230),
                "数据过期(旧日期) → 必须放行，不锁死");

        // 8.5 解析细节
        System.out.println("  · 解析细节");
        eq(StudyGate.parseHm("22:00"), 22 * 60, "parseHm 22:00");
        eq(StudyGate.parseHm("7:05"), 7 * 60 + 5, "parseHm 7:05（单数字小时）");
        ok(StudyGate.parseHm("25:00") < 0, "25:00 不合法");
        ok(StudyGate.parseHm("22") < 0, "没有冒号不合法");
        ok(StudyGate.parseHm("") < 0, "空串不合法");
        ok(StudyGate.parseHm(null) < 0, "null 不合法");
        StudyGate.Snapshot legacy = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":false,\"checkedAt\":\"2026-10-22\"}",
                "2026-10-22", stampMs("2026-10-22 20:10"));
        ok(legacy.checkedAtMs > 0, "checkedAt 只有日期也能解析（按 00:00 算）");

        // 8.6 跨午夜配置：现在只当展示信息，判定不看时段
        System.out.println("  · 跨午夜配置不再影响判定");
        StudyGate.Snapshot cross = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"23:00\","
              + "\"end\":\"01:00\",\"allDone\":false,\"checkedAt\":\"2026-10-22 23:20\"}",
                "2026-10-22", stampMs("2026-10-22 23:30"));
        ok(StudyGate.shouldHold(cross, at(2026, 10, 22, 23, 30)), "23:30 没打完 → hold");
        ok(StudyGate.shouldHold(cross, at(2026, 10, 22, 23, 45)),
                "23:45 仍 hold（不再有 01:00 右开这种边界）");
        StudyGate.Snapshot crossAm = StudyGate.parse(
                "{\"date\":\"2026-10-23\",\"enabled\":true,\"start\":\"23:00\","
              + "\"end\":\"01:00\",\"allDone\":false,\"checkedAt\":\"2026-10-23 00:20\"}",
                "2026-10-23", stampMs("2026-10-23 00:30"));
        ok(!StudyGate.shouldHold(crossAm, at(2026, 10, 23, 0, 30)),
                "次日 00:30 不继承昨日延长冻结");

        // 8.7 与 Guard.blocked 的叠加语义（文档化行为：纯函数不含闸门）
        System.out.println("  · 与时间表的叠加");
        Rule douyin = new Rule(0x7F, 22 * 60, 23 * 60);
        ok(!Guard.blocked(douyin, at2230), "22:30 抖音按时间表是放行");
        ok(StudyGate.shouldHold(ok1, at2230) && !Guard.blocked(douyin, at2230),
                "闸门命中时 = 时间表放行 + 闸门延长 → apply() 里取 ||");

        // 8.8 展示文案必须写明"放行"（否则被读成"这段时间冻结"）
        System.out.println("  · 放行文案");
        eq(Guard.allowText(new Rule(0x7F, 22 * 60, 23 * 60)), "冻结 每天 00:00-22:00、23:00-00:00（含跨午夜）",
                "旧规则显示对应冻结时段");
        ok(Guard.allowText(new Rule(0x7F, 22 * 60, 23 * 60)).startsWith("冻结"),
                "文案明确显示冻结时间");
        eq(Guard.allowText(new Rule(0x7F, new int[0][2])), "全天冻结 · 不受学习闸门影响",
                "空时段说清楚是整天冻结");
        eq(Guard.allowText(new Rule(0, 22 * 60, 23 * 60)), "全天冻结",
                "没选星期说清楚是整天冻结");

        // 8.9 卡片状态：过期必须显式报"保护已失效"，不能乐观地显示"到点继续冻结"
        System.out.println("  · 卡片状态 cardState / cardText");

        // 区间内、未打卡 → 生效中
        eq(StudyGate.stateOf(ok1, at(2026, 10, 22, 22, 30)), StudyGate.ST_ACTIVE,
                "22:30 未打卡 → 生效中");
        ok(StudyGate.textOf(ok1, at(2026, 10, 22, 22, 30)).contains("生效中"),
                "生效中文案");

        // 没到 22:00 也一样：没打完就已经在延长冻结，不再有"待命"这一态
        StudyGate.Snapshot fresh2000 = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":false,\"checkedAt\":\"2026-10-22 20:00\"}",
                "2026-10-22", stampMs("2026-10-22 20:00"));
        eq(StudyGate.stateOf(fresh2000, at(2026, 10, 22, 20, 30)), StudyGate.ST_ACTIVE,
                "20:30 未打卡 → 生效中（不挑时间）");
        ok(StudyGate.textOf(fresh2000, at(2026, 10, 22, 20, 30)).contains("直接延长冻结"),
                "文案写「直接延长冻结」");

        // 超龄 90 分钟 → 保护已失效（关键：不能显示成"还在延长冻结"）
        int stStale = StudyGate.stateOf(fresh2000, at(2026, 10, 22, 21, 30));
        eq(stStale, StudyGate.ST_STALE, "90 分钟没更新 → ST_STALE");
        String staleTxt = StudyGate.textOf(fresh2000, at(2026, 10, 22, 21, 30));
        ok(staleTxt.contains("保护已失效"), "★ 过期文案必须写「保护已失效」：" + staleTxt);
        ok(staleTxt.contains("90 分钟"), "过期文案要说明多久没更新：" + staleTxt);
        ok(!staleTxt.contains("延长冻结"), "★ 过期时绝不能显示乐观文案");

        // 跨天 → 保护已失效，且要说清"进度还停在哪天"
        StudyGate.Snapshot yesterday = StudyGate.parse(
                "{\"date\":\"2026-10-21\",\"enabled\":true,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":false,\"checkedAt\":\"2026-10-21 23:00\"}",
                "2026-10-21", stampMs("2026-10-21 23:00"));
        eq(StudyGate.stateOf(yesterday, at(2026, 10, 22, 10, 0)), StudyGate.ST_STALE,
                "昨天的数据 → ST_STALE");
        ok(StudyGate.textOf(yesterday, at(2026, 10, 22, 10, 0)).contains("2026-10-21"),
                "跨天文案要点出日期");

        // 已打卡 / 关闭 / 无数据
        eq(StudyGate.stateOf(StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":true,\"checkedAt\":\"2026-10-22 22:00\"}",
                "2026-10-22", stampMs("2026-10-22 22:00")), at(2026, 10, 22, 22, 30)),
                StudyGate.ST_DONE, "全部打卡 → ST_DONE");
        eq(StudyGate.stateOf(StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":false,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":false,\"checkedAt\":\"2026-10-22 22:00\"}",
                "2026-10-22", stampMs("2026-10-22 22:00")), at(2026, 10, 22, 22, 30)),
                StudyGate.ST_OFF, "开关关 → ST_OFF");
        eq(StudyGate.stateOf(null, at(2026, 10, 22, 22, 30)), StudyGate.ST_NONE,
                "null → ST_NONE");
        eq(StudyGate.stateOf(StudyGate.Snapshot.EMPTY, at(2026, 10, 22, 22, 30)),
                StudyGate.ST_NONE, "EMPTY → ST_NONE");
        ok(StudyGate.textOf(null, at(2026, 10, 22, 22, 30)).contains("暂无数据"),
                "无数据文案");

        // 8.10 闸门的作用范围：只有 gate=true 的应用被牵连
        System.out.println("  · 闸门作用范围 autoGate");
        ok(new Rule(0x7F, 22 * 60, 23 * 60).gate, "每天放行 1 小时 → 参与闸门");
        ok(!new Rule(0x7F, 6 * 60, 0).gate, "放行 06:00-00:00（18 小时）→ 不参与闸门");
        ok(new Rule(0x7F, new int[][] { { 12 * 60, 13 * 60 }, { 17 * 60, 18 * 60 } }).gate,
                "两段共 2 小时 → 参与闸门");
        ok(!Rule.autoGate(new int[][] { { 0, 0 } }), "全天放行 → 不参与闸门");
        ok(!new Rule(0x7F, 22 * 60, 23 * 60, false).gate, "显式关闭生效");
        ok(!new Rule(0x7F, 22 * 60, 23 * 60).withGate(false).gate, "withGate(false) 生效");
        ok(new Rule(0x7F, 22 * 60, 23 * 60).withGate(false).withGate(true).gate,
                "withGate(true) 能改回来");
        ok(Guard.allowText(new Rule(0x7F, 6 * 60, 0)).contains("不受学习闸门影响"),
                "不参与闸门的要明确显示豁免");
        ok(!Guard.allowText(new Rule(0x7F, 22 * 60, 23 * 60)).contains("不受学习闸门影响"),
                "参与闸门的不加后缀");
        // 闸门命中时：只有 gate=true 的被冻
        Rule gateOn = new Rule(0x7F, 22 * 60, 23 * 60);
        Rule gateOff = new Rule(0x7F, 6 * 60, 0);
        ok(!Guard.blocked(gateOn, at2230) && gateOn.gate, "抖音类 22:30 本该放行，但受闸门管");
        ok(!Guard.blocked(gateOff, at2230) && !gateOff.gate, "工具类 22:30 放行，且不受闸门管");

        // ---- 关键应用名单：用户自己列的，只在自身时段外冻结 ----
        System.out.println("  · 关键应用名单");
        Rule key = new Rule(0x7F, 6 * 60, 0);   // 06:00–00:00，跨午夜到 0 点
        ok(!Guard.blocked(key, at(2026, 10, 22, 14, 0)), "关键应用白天放行");
        ok(!Guard.blocked(key, at(2026, 10, 22, 22, 30)),
                "★ 关键应用在闸门时段（22:30）不因学习目标被冻");
        ok(Guard.blocked(key, at(2026, 10, 23, 2, 0)),
                "★ 关键应用在午夜 02:00（自己时段外）照常冻结");
        ok(!key.gate, "★ 午夜冻结来自时间表，与学习闸门无关（gate=false）");

        // 名单切换只能改 gate，绝不能顺手洗掉时段/星期
        Rule two = new Rule(0x3, new int[][] { { 12 * 60, 13 * 60 }, { 17 * 60, 18 * 60 } }, true);
        Rule toKey = two.withGate(false);
        eq(toKey.windowCount(), 2, "withGate 不动段数");
        eq(toKey.startAt(0), 12 * 60, "withGate 不动第一段起点");
        eq(toKey.endAt(1), 18 * 60, "withGate 不动第二段终点");
        eq(toKey.days, 0x3, "withGate 不动放行星期");
        ok(!toKey.gate && toKey.withGate(true).gate, "withGate 双向可切换");
        Map<String, Rule> all = new LinkedHashMap<>();
        all.put("com.tencent.mm", key);
        all.put("com.android.camera", two);
        Set<String> picked = new LinkedHashSet<>();
        picked.add("com.android.camera");
        Map<String, Rule> changed = RuleStore.withKeyApps(all, picked);
        ok(RuleStore.keyAppsOf(changed).equals(picked), "关键名单可从微信改为相机");
        ok(changed.get("com.tencent.mm").gate, "取消微信关键身份后参与闸门");
        ok(!changed.get("com.android.camera").gate, "相机被选为关键应用后豁免闸门");
        ok(changed.get("com.android.camera").same(two.withGate(false)),
                "切换关键身份保留相机原冻结时间");
        ok(!all.get("com.tencent.mm").gate && all.get("com.android.camera").gate,
                "设置名单不修改输入规则");
    }

    private static void pcStats() {
        String json = "{\"month\":\"2026-10\",\"monthApps\":{\"editor\":300,\"browser\":50},"
                + "\"totalApps\":{\"editor\":500,\"browser\":90},\"days\":{"
                + "\"2026-09-30\":{\"apps\":{\"editor\":20}},"
                + "\"2026-10-01\":{\"apps\":{\"editor\":30,\"browser\":10}},"
                + "\"2026-10-24\":{\"apps\":{\"editor\":5}}}}";
        eq(PcStats.totalMinutes(PcStats.periodApps(json, "month", "2026-10-24")), 350L,
                "电脑本月读取完整汇总，不把每日前列漏项当成零");
        eq(PcStats.totalMinutes(PcStats.periodApps(json, "total", "")), 590L,
                "电脑累计读取历史汇总");
        eq(PcStats.periodApps(json, "month", "2026-10-24").get(0).name, "editor",
                "电脑排行按时长排序");
        eq(PcStats.totalMinutes(PcStats.periodApps(json, "month", "2026-11-01")), 0L,
                "跨月不显示上月汇总");
        String old = "{\"days\":{\"2026-09-30\":{\"apps\":{\"editor\":20}},"
                + "\"2026-10-01\":{\"apps\":{\"editor\":30}},\"2026-10-24\":{\"apps\":{\"editor\":5}}}}";
        eq(PcStats.totalMinutes(PcStats.periodApps(old, "month", "2026-10-24")), 35L,
                "旧快照按本月日期聚合");
        eq(PcStats.totalMinutes(PcStats.periodApps(old, "total", "")), 55L,
                "旧快照累计所有已有日期");
    }

    /* ---------- 工具 ---------- */

    private static long stampMs(String s) {
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
            f.setLenient(false);
            return f.parse(s).getTime();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void flipProperty(Rule r) {
        int bad = 0;
        int notFuture = 0;
        for (int d = 19; d <= 25; d++) {
            for (int m = 0; m < 1440; m += 7) {
                Calendar now = at(2026, 10, d, m / 60, m % 60);
                long flip = r.nextFlip(now);
                if (flip <= now.getTimeInMillis()) {
                    notFuture++;
                }
                Calendar f = cal(flip);
                Calendar before = cal(flip - 60000L);
                if (r.inWindow(f) == r.inWindow(before)) {
                    bad++;
                }
            }
        }
        ok(bad == 0, "翻转点前后状态确实不同（异常 " + bad + "）");
        ok(notFuture == 0, "翻转点严格晚于当前时刻（异常 " + notFuture + "）");
    }

    private static boolean blocked(Rule r, int y, int mo, int d, int hh, int mm) {
        return Guard.blocked(r, at(y, mo, d, hh, mm));
    }

    private static Calendar noon() {
        return at(2026, 10, 19, 12, 0);
    }

    private static Calendar cal(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c;
    }

    private static Calendar at(int y, int mo, int d, int hh, int mm) {
        Calendar c = Calendar.getInstance();
        c.set(y, mo - 1, d, hh, mm, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static void eq(int got, int want, String name) {
        ok(got == want, name + "（got=" + got + " want=" + want + "）");
    }

    /* ---------- 禁忌卡（黑卡） ---------- */

    private static void tabooCard() {
        System.out.println("-- taboo card");
        long now = 1_000_000_000L;
        // 惩罚期未过 → hold；正好到期 → 放行
        eq(StudyGate.tabooHolding(now, now + 1), true, "惩罚期内继续冻结");
        eq(StudyGate.tabooHolding(now, now), false, "到点即放行");
        eq(StudyGate.tabooHolding(now, 0), false, "没惩罚在身放行");
        // shouldHold 为 false（目标打完）时，holding 由禁忌卡兜住 —— 逻辑在
        // holding(Context) 里，离线只能验纯层：这里保证纯判定与 holding 的组合口径一致
        eq(StudyGate.tabooHolding(now - 60_000L, now + 60_000L), true, "剩余 1 分钟也照样冻");

        tabooLogParse();
        tabooDaily();
        tabooPending();
    }

    /** 黑卡并进卡组后，要按天判「今天做了没有」—— 判错就会天天显示已认领。 */
    private static void tabooDaily() {
        System.out.println("-- taboo daily");
        String log = "[{\"name\":\"吃辣\",\"ts\":100},{\"name\":\"吃甜\",\"ts\":5000}]";
        eq(StudyGate.tabooClaimedOn(log, "吃辣", 0, 1000), true, "区间内认过算今天做过");
        eq(StudyGate.tabooClaimedOn(log, "吃辣", 1000, 9000), false, "昨天的认领不算今天");
        eq(StudyGate.tabooClaimedOn(log, "吃甜", 0, 1000), false, "没认过的项不算（1/2）");
        eq(StudyGate.tabooClaimedOn(log, "吃甜", 4000, 9000), true, "没认过的项不算（2/2）");
        eq(StudyGate.tabooClaimedOn(log, "", 0, 9000), false, "空名不算");
        eq(StudyGate.tabooClaimedOn("bad", "吃辣", 0, 9000), false, "坏 JSON 当没认过");
        // 区间左闭右开：正好压在零点的算新的一天的第一笔
        eq(StudyGate.tabooClaimedOn("[{\"name\":\"吃辣\",\"ts\":1000}]", "吃辣", 1000, 2000),
                true, "左闭");
        eq(StudyGate.tabooClaimedOn("[{\"name\":\"吃辣\",\"ts\":1000}]", "吃辣", 0, 1000),
                false, "右开");

        String dec = "{\"吃辣\":100,\"吃甜\":5000}";
        eq(StudyGate.tabooDeclinedOn(dec, "吃辣", 0, 1000), true, "今天滑过没做");
        eq(StudyGate.tabooDeclinedOn(dec, "吃辣", 1000, 9000), false, "昨天的没做不算今天");
        eq(StudyGate.tabooDeclinedOn(dec, "吃甜", 0, 1000), false, "没滑过的项不算");
        eq(StudyGate.tabooDeclinedOn("bad", "吃辣", 0, 9000), false, "坏 JSON 当没滑过");
        eq(StudyGate.tabooDeclinedOn("{}", "吃辣", 0, 9000), false, "空表当没滑过");
    }

    /**
     * 首页「打卡」按钮要把禁忌卡算进待办：禁忌卡并进队列后，
     * 只数目标会出现"按钮说打完了、点进去还有黑卡"。
     */
    private static void tabooPending() {
        System.out.println("-- taboo pending");
        String items = "[\"吃辣\",\"吃甜\",\"吃油\"]";
        eq(StudyGate.tabooItems(items).size(), 3, "解析出 3 张禁忌卡");
        eq(StudyGate.tabooItems("[]").size(), 0, "空表没有卡");
        eq(StudyGate.tabooItems("bad").size(), 0, "坏 JSON 当没有卡");
        eq(StudyGate.tabooItems("[\"\",\"  \",\"吃辣\"]").size(), 1, "空名不计入");

        // 一张都没答
        eq(StudyGate.tabooPendingOn(items, "[]", "{}", 0, 1000), 3, "全没答");
        // 认了一张、滑了一张 → 只剩一张
        eq(StudyGate.tabooPendingOn(items, "[{\"name\":\"吃辣\",\"ts\":100}]",
                "{\"吃甜\":200}", 0, 1000), 1, "认+没做各抵一张");
        // 昨天的认领不算今天
        eq(StudyGate.tabooPendingOn(items, "[{\"name\":\"吃辣\",\"ts\":100}]",
                "{}", 1000, 2000), 3, "昨天的认领不抵今天的卡");
        // 全答完
        eq(StudyGate.tabooPendingOn(items,
                "[{\"name\":\"吃辣\",\"ts\":100},{\"name\":\"吃甜\",\"ts\":300},{\"name\":\"吃油\",\"ts\":500}]",
                "{}", 0, 1000), 0, "全答完为 0");
        // 没有禁忌项 = 没有待办（不挡住"今天已经打完"）
        eq(StudyGate.tabooPendingOn("[]", "[]", "{}", 0, 1000), 0, "没配禁忌卡时不影响打卡状态");

        // 首页「打卡」按钮文案：学习联动并进来之后，"冻不冻"只能靠这颗按钮说
        eq(StudyGate.checkinLabel(3, 0, StudyGate.ST_ACTIVE, true),
                "打卡 · 还有 3 个目标 · 冻结中", "没打完 + 冻结中");
        eq(StudyGate.checkinLabel(3, 2, StudyGate.ST_ACTIVE, true),
                "打卡 · 还有 3 个目标 · 禁忌 2 · 冻结中", "目标+禁忌一起欠");
        eq(StudyGate.checkinLabel(0, 2, StudyGate.ST_DONE, true),
                "打卡 · 只剩禁忌 2 张 · 冻结中", "目标打完但禁忌还欠");
        eq(StudyGate.checkinLabel(0, 0, StudyGate.ST_DONE, false),
                "打卡 · 今天已经打完", "全清 + 不冻结");
        eq(StudyGate.checkinLabel(0, 0, StudyGate.ST_STALE, false),
                "打卡 · 今天已经打完 · ⚠ 联动过期", "★ 数据过期必须盖过\"打完了\"");
        eq(StudyGate.checkinLabel(-1, 0, StudyGate.ST_NONE, false),
                "打卡", "没有数据就退回光秃秃的「打卡」");
    }

    /** 认领流水的纯解析/裁剪：打卡历史页红色条目的数据层。 */
    private static void tabooLogParse() {
        System.out.println("-- taboo log parse");
        // 空数据 / 坏 JSON 都当不存在
        eq(StudyGate.tabooLogParse("[]", 20).size(), 0, "空流水");
        eq(StudyGate.tabooLogParse("not json", 20).size(), 0, "坏 JSON 当不存在");
        eq(StudyGate.tabooLogParse("{\"a\":1}", 20).size(), 0, "对象当不存在");
        // 最新在前；空名/坏条目跳过；limit 截断
        java.util.List<StudyGate.TabooClaim> got = StudyGate.tabooLogParse(
                "[{\"name\":\"吃辣\",\"ts\":100},{\"name\":\"\",\"ts\":200},"
                        + "\"junk\",{\"name\":\"吃甜\",\"ts\":300}]", 20);
        eq(got.size(), 2, "空名与非对象条目跳过");
        eq(got.get(0).name, "吃甜", "最新在前（1/2）");
        eq(got.get(0).ts, 300L, "最新在前（2/2）");
        eq(got.get(1).name, "吃辣", "次序保持");
        eq(StudyGate.tabooLogParse(
                "[{\"name\":\"吃辣\",\"ts\":100},{\"name\":\"吃甜\",\"ts\":300}]", 1).size(),
                1, "limit 截断");
        // 裁剪：只留最近 max 条，丢数组头（最旧的）
        try {
            org.json.JSONArray a = new org.json.JSONArray(
                    "[{\"name\":\"旧1\",\"ts\":1},{\"name\":\"旧2\",\"ts\":2},{\"name\":\"新\",\"ts\":3}]");
            org.json.JSONArray capped = StudyGate.capTabooLog(a, 2);
            eq(capped.length(), 2, "裁剪到上限");
            eq(capped.getJSONObject(0).optString("name"), "旧2", "丢的是最旧的");
            eq(capped.getJSONObject(1).optString("name"), "新", "最新的保留");
            eq(StudyGate.capTabooLog(new org.json.JSONArray("[]"), 2).length(), 0, "空数组不裁");
        } catch (Throwable t) {
            ok(false, "capTabooLog 异常: " + t);
        }
    }

    private static void eq(boolean got, boolean want, String name) {
        ok(got == want, name + "（got=" + got + " want=" + want + "）");
    }

    private static void eq(String got, String want, String name) {
        ok(got != null && got.equals(want), name + "（got=" + got + " want=" + want + "）");
    }

    private static void eq(long got, long want, String name) {
        ok(got == want, name + "（got=" + got + " want=" + want + "）");
    }

    private static void ok(boolean cond, String name) {
        if (cond) {
            pass++;
            System.out.println("  OK   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
