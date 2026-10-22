package com.afs.guard;

import java.util.Arrays;
import java.util.Calendar;

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
        phoneCheckin();
        localPendingMerge();

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

    /* ---------- 8.5 手机打卡队列 ---------- */

    /**
     * 手机上滑打完卡 -> Gist 队列里有今天的条目 -> 守卫立刻不再 hold。
     * 这条如果判错，用户要么白等 PC 那 15 分钟，要么打完卡还被继续冻着。
     */
    private static void phoneCheckin() {
        System.out.println("---- 手机打卡队列 ----");

        // 队列里带 pendingAfter：合并手机打卡后还剩几个目标
        String q = "{\"date\":\"2026-10-22\",\"active\":3,\"pendingAfter\":0,"
                + "\"items\":[{\"id\":\"ck_1\",\"date\":\"2026-10-22\",\"amount\":20}]}";
        eq(StudyGate.parsePendingAfter(q, "2026-10-22"), 0, "打完最后一个 → 0");
        eq(StudyGate.parsePendingAfter(q, "2026-10-21"), -1, "日期对不上 → -1（不参与）");

        String q2 = "{\"date\":\"2026-10-22\",\"active\":3,\"pendingAfter\":2,\"items\":[]}";
        eq(StudyGate.parsePendingAfter(q2, "2026-10-22"), 2, "还剩 2 个 → 2");

        eq(StudyGate.parsePendingAfter("{\"items\":[]}", "2026-10-22"), -1, "空队列 → -1");
        eq(StudyGate.parsePendingAfter("", "2026-10-22"), -1, "空字符串 → -1");
        eq(StudyGate.parsePendingAfter(null, "2026-10-22"), -1, "null → -1");
        eq(StudyGate.parsePendingAfter("{\"date\":\"2026-10-22\"}", "2026-10-22"), -1,
                "没有 pendingAfter 字段 → -1");

        // Python 的 json.dumps 默认带空格，PC 写回来的可能长这样
        eq(StudyGate.parsePendingAfter(
                "{\"date\": \"2026-10-22\", \"pendingAfter\": 1, \"items\": []}", "2026-10-22"), 1,
                "带空格的 JSON 也能读");
        // 日期不能被别的字段前缀骗到
        eq(StudyGate.parsePendingAfter(
                "{\"date\":\"2026-10-221\",\"pendingAfter\":0}", "2026-10-22"), -1,
                "带引号匹配，2026-10-221 不算今天");

        StudyGate.Snapshot s = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\",\"end\":\"23:00\","
                        + "\"allDone\":false,\"pending\":2,\"checkedAt\":\"2026-10-22 22:30\"}",
                "2026-10-22", stampMs("2026-10-22 22:30"));
        Calendar at2230 = at(2026, 10, 22, 22, 30);

        ok(StudyGate.shouldHold(s, at2230, -1), "手机没打卡 → 继续冻");
        ok(StudyGate.shouldHold(s, at2230, 2), "手机只打了 1 个、还剩 2 个 → 还是冻（不能打一个就全豁免）");
        ok(!StudyGate.shouldHold(s, at2230, 0), "手机把目标补齐了 → 立刻放行（先解闸）");
        eq(StudyGate.stateOf(s, at2230, -1), StudyGate.ST_ACTIVE, "没打卡 → 生效中");
        eq(StudyGate.stateOf(s, at2230, 2), StudyGate.ST_ACTIVE, "还剩 2 个 → 仍是生效中");
        eq(StudyGate.stateOf(s, at2230, 0), StudyGate.ST_QUEUED, "补齐 → 已打卡待记账");

        // 数据过期时队列仍优先：手机确实打了卡，不该显示"保护已失效 + 继续冻结"
        StudyGate.Snapshot old = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\",\"end\":\"23:00\","
                        + "\"allDone\":false,\"pending\":2,\"checkedAt\":\"2026-10-22 20:00\"}",
                "2026-10-22", stampMs("2026-10-22 20:00"));
        eq(StudyGate.stateOf(old, at2230, -1), StudyGate.ST_STALE, "过期 → 保护已失效");
        eq(StudyGate.stateOf(old, at2230, 0), StudyGate.ST_QUEUED, "过期但手机补齐 → 已打卡");
        ok(StudyGate.textOf(old, at2230, 0).contains("已打卡"),
                "过期 + 手机补齐 → 文案要写已打卡");
    }

    /* ---------- 9. 学习闸门 ---------- */

    /**
     * 合并成一个 App 之后多出来的一件事：闸门有两份"还剩几个"。
     * 远端（Gist，可能还是十分钟前的）和本地（打卡页刚算的）。
     * 取小 = 早解闸，这条口径错了会直接表现为"打完了还在冻"。
     */
    private static void localPendingMerge() {
        System.out.println("---- 远端/本地 pending 合并 ----");

        eq(StudyGate.mergePending(-1, -1), -1, "两边都没数 → -1（按 PC 的 allDone 判）");
        eq(StudyGate.mergePending(-1, 0), 0, "只有本地说打完了 → 0（立刻解闸）");
        eq(StudyGate.mergePending(0, -1), 0, "只有远端说打完了 → 0");
        eq(StudyGate.mergePending(2, 0), 0, "远端落后、本地已补齐 → 取 0");
        eq(StudyGate.mergePending(0, 2), 0, "本地落后、远端已补齐 → 取 0");
        eq(StudyGate.mergePending(2, 3), 2, "都还有剩 → 取小的");
        eq(StudyGate.mergePending(3, 2), 2, "都还有剩 → 取小的（换序）");
        eq(StudyGate.mergePending(-1, 2), 2, "远端没数、本地还有 2 个 → 2");

        StudyGate.Snapshot s = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\",\"end\":\"23:00\","
                        + "\"allDone\":false,\"pending\":2,\"checkedAt\":\"2026-10-22 22:30\"}",
                "2026-10-22", stampMs("2026-10-22 22:30"));
        Calendar at2230 = at(2026, 10, 22, 22, 30);
        ok(!StudyGate.shouldHold(s, at2230, StudyGate.mergePending(2, 0)),
                "远端还写着剩 2 个，但本地刚补齐 → 不冻（别让人以为打卡没生效）");
        ok(StudyGate.shouldHold(s, at2230, StudyGate.mergePending(-1, 2)),
                "本地只剩 2 个目标 → 照冻结");
    }

    /* ---------- 10. 学习闸门 ---------- */

    private static void studyGate() {
        System.out.println("---- 学习闸门：解析 / 判定 / fail-open ----");

        // 8.1 inSpan 区间判定（含跨午夜，语义与 Rule.inWindow 一致）
        System.out.println("  · 区间判定 inSpan");
        ok(StudyGate.inSpan(22 * 60, 22 * 60, 23 * 60), "22:00 落在 [22:00,23:00)");
        ok(StudyGate.inSpan(22 * 60 + 59, 22 * 60, 23 * 60), "22:59 落在区间");
        ok(!StudyGate.inSpan(23 * 60, 22 * 60, 23 * 60), "23:00 不在区间（左闭右开）");
        ok(!StudyGate.inSpan(21 * 60 + 59, 22 * 60, 23 * 60), "21:59 不在区间");
        ok(StudyGate.inSpan(22 * 60, 22 * 60, 22 * 60), "起止相同 = 全天");
        ok(StudyGate.inSpan(23 * 60 + 30, 23 * 60, 1 * 60), "23:30 落在跨午夜区间 [23:00,01:00)");
        ok(StudyGate.inSpan(0 * 60 + 30, 23 * 60, 1 * 60), "00:30 仍在跨午夜区间");
        ok(!StudyGate.inSpan(1 * 60, 23 * 60, 1 * 60), "01:00 不在跨午夜区间（右开）");
        ok(!StudyGate.inSpan(12 * 60, 23 * 60, 1 * 60), "中午不在跨午夜区间");
        ok(!StudyGate.inSpan(22 * 60, -1, 23 * 60), "起点不合法 = 不 hold");

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
                "开启+可信+未打卡+22:30 在区间 → hold");
        ok(!StudyGate.shouldHold(ok1, at(2026, 10, 22, 21, 59)),
                "21:59 不在区间 → 放行");
        ok(!StudyGate.shouldHold(ok1, at(2026, 10, 22, 23, 0)),
                "23:00 区间结束 → 放行（右开）");

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

        // 8.6 跨午夜闸门区间
        // 跨午夜时"凌晨那半段"属于下一拍：date/checkedAt 都是次日推送的数据
        System.out.println("  · 跨午夜区间");
        StudyGate.Snapshot cross = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"23:00\","
              + "\"end\":\"01:00\",\"allDone\":false,\"checkedAt\":\"2026-10-22 23:20\"}",
                "2026-10-22", stampMs("2026-10-22 23:30"));
        ok(StudyGate.shouldHold(cross, at(2026, 10, 22, 23, 30)), "23:30 hold");
        ok(!StudyGate.shouldHold(cross, at(2026, 10, 22, 12, 0)), "中午放行");
        StudyGate.Snapshot crossAm = StudyGate.parse(
                "{\"date\":\"2026-10-23\",\"enabled\":true,\"start\":\"23:00\","
              + "\"end\":\"01:00\",\"allDone\":false,\"checkedAt\":\"2026-10-23 00:20\"}",
                "2026-10-23", stampMs("2026-10-23 00:30"));
        ok(StudyGate.shouldHold(crossAm, at(2026, 10, 23, 0, 30)), "00:30 hold（凌晨段）");

        // 8.7 与 Guard.blocked 的叠加语义（文档化行为：纯函数不含闸门）
        System.out.println("  · 与时间表的叠加");
        Rule douyin = new Rule(0x7F, 22 * 60, 23 * 60);
        ok(!Guard.blocked(douyin, at2230), "22:30 抖音按时间表是放行");
        ok(StudyGate.shouldHold(ok1, at2230) && !Guard.blocked(douyin, at2230),
                "闸门命中时 = 时间表放行 + 闸门延长 → apply() 里取 ||");

        // 8.8 展示文案必须写明"放行"（否则被读成"这段时间冻结"）
        System.out.println("  · 放行文案");
        eq(Guard.allowText(new Rule(0x7F, 22 * 60, 23 * 60)), "放行 每天 22:00-23:00",
                "普通规则带「放行」前缀");
        ok(Guard.allowText(new Rule(0x7F, 22 * 60, 23 * 60)).startsWith("放行"),
                "★ 文案必须以「放行」开头，不能只写时间");
        eq(Guard.allowText(new Rule(0x7F, new int[0][2])), "无放行时段 · 整天冻结",
                "空时段说清楚是整天冻结");
        eq(Guard.allowText(new Rule(0, 22 * 60, 23 * 60)), "没选放行星期 · 整天冻结",
                "没选星期说清楚是整天冻结");

        // 8.9 卡片状态：过期必须显式报"保护已失效"，不能乐观地显示"到点继续冻结"
        System.out.println("  · 卡片状态 cardState / cardText");

        // 区间内、未打卡 → 生效中
        eq(StudyGate.stateOf(ok1, at(2026, 10, 22, 22, 30)), StudyGate.ST_ACTIVE,
                "22:30 未打卡 → 生效中");
        ok(StudyGate.textOf(ok1, at(2026, 10, 22, 22, 30)).contains("生效中"),
                "生效中文案");

        // 区间外、未打卡、数据新鲜 → 待命
        StudyGate.Snapshot fresh2000 = StudyGate.parse(
                "{\"date\":\"2026-10-22\",\"enabled\":true,\"start\":\"22:00\","
              + "\"end\":\"23:00\",\"allDone\":false,\"checkedAt\":\"2026-10-22 20:00\"}",
                "2026-10-22", stampMs("2026-10-22 20:00"));
        eq(StudyGate.stateOf(fresh2000, at(2026, 10, 22, 20, 30)), StudyGate.ST_PENDING,
                "20:30 未到区间 → 待命");
        ok(StudyGate.textOf(fresh2000, at(2026, 10, 22, 20, 30)).contains("到点继续冻结"),
                "待命文案");

        // 超龄 90 分钟 → 保护已失效（关键：不能显示成"到点继续冻结"）
        int stStale = StudyGate.stateOf(fresh2000, at(2026, 10, 22, 21, 30));
        eq(stStale, StudyGate.ST_STALE, "90 分钟没更新 → ST_STALE");
        String staleTxt = StudyGate.textOf(fresh2000, at(2026, 10, 22, 21, 30));
        ok(staleTxt.contains("保护已失效"), "★ 过期文案必须写「保护已失效」：" + staleTxt);
        ok(staleTxt.contains("90 分钟"), "过期文案要说明多久没更新：" + staleTxt);
        ok(!staleTxt.contains("到点继续冻结"), "★ 过期时绝不能显示乐观文案");

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
        ok(Guard.allowText(new Rule(0x7F, 6 * 60, 0)).contains("关键应用"),
                "★ 不参与闸门的要在文案里标成「关键应用」");
        ok(!Guard.allowText(new Rule(0x7F, 22 * 60, 23 * 60)).contains("关键应用"),
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

    private static void eq(boolean got, boolean want, String name) {
        ok(got == want, name + "（got=" + got + " want=" + want + "）");
    }

    private static void eq(String got, String want, String name) {
        ok(got != null && got.equals(want), name + "（got=" + got + " want=" + want + "）");
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
