package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.TimeZone;

/** Regression tests focus on upstream parity, credit integrity and gate boundaries. */
public final class MemoryTest {
    private static int count;
    private static void check(boolean b, String message) {
        count++; if (!b) throw new AssertionError(message);
    }
    private static void reject(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException e) { count++; return; }
        throw new AssertionError(message);
    }
    private static String deck(MemoryData m, int i) { return m.decks().optJSONObject(i).optString("id"); }
    private static JSONObject add(MemoryData m, String d, String front) {
        JSONObject c = MemoryData.newCard(d, "qa", front, "answer", "教材第 10 页", "解释"); m.saveCard(c); return c;
    }
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        upstreamVectors(); learning(); quotas(); queues(); backupValidation(); text(); gate();customSubjects();subjectDeletion();
        planSettings();presets();
        System.out.println("Memory: " + count + " assertions passed");
    }
    private static void planSettings() {
        // The default retention must reproduce the legacy interval formula bit for bit.
        double[] stabilities = {0.001, 0.2, 0.5, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597, 10000};
        for (double s : stabilities) {
            long legacy = Math.max(1, Math.min(36500, Math.round(s))) * MemoryScheduler.DAY;
            check(MemoryScheduler.intervalDays(s, 0.90, 36500) == legacy, "default retention equals legacy interval");
        }
        check(MemoryScheduler.intervalDays(100, 0.90, 36500) == 100 * MemoryScheduler.DAY, "90% retention interval");
        check(MemoryScheduler.intervalDays(100, 0.95, 36500) == 42 * MemoryScheduler.DAY, "95% retention interval");
        check(MemoryScheduler.intervalDays(100, 0.85, 36500) == 181 * MemoryScheduler.DAY, "85% retention interval");
        check(MemoryScheduler.intervalDays(100, 0.90, 30) == 30 * MemoryScheduler.DAY, "max interval cap");
        check(MemoryScheduler.intervalDays(0.2, 0.90, 36500) == MemoryScheduler.DAY, "minimum interval");
        check(MemoryScheduler.intervalDays(100, 0.5, 36500) == 100 * MemoryScheduler.DAY, "illegal retention falls back");
        check(MemoryScheduler.intervalDays(100, 0.90, 0) == 100 * MemoryScheduler.DAY, "illegal cap falls back");

        long now = 1750000000000L;
        MemoryData m = new MemoryData(); String d = deck(m, 0);
        m.settings(d, "标准", 10, 50, 20, false);
        check(!m.deck(d).has("requestRetention") && !m.deck(d).has("maxInterval"), "old settings writes no new keys");
        check(Math.abs(MemoryData.retention(m.deck(d)) - 0.90) < 1e-12, "retention default");
        check(MemoryData.maxInterval(m.deck(d)) == 36500, "interval default");
        m.settings(d, "标准", 10, 50, 20, false, 0.95, 30);
        check(Math.abs(MemoryData.retention(m.deck(d)) - 0.95) < 1e-12, "retention stored");
        check(MemoryData.maxInterval(m.deck(d)) == 30, "max interval stored");
        check(new MemoryData(m.db.toString()).deck(d).has("requestRetention"), "new keys survive restart");
        JSONObject optional = new JSONObject(); MemoryData.put(optional, "requestRetention", 0.5);
        check(Math.abs(MemoryData.retention(optional) - 0.90) < 1e-12, "invalid retention reads default");
        MemoryData.put(optional, "maxInterval", 0);
        check(MemoryData.maxInterval(optional) == 36500, "invalid interval reads default");

        // Higher retention schedules the same card earlier, all else equal.
        MemoryData normal = new MemoryData(), strict = new MemoryData();
        String dn = deck(normal, 0), dsx = deck(strict, 0);
        JSONObject cn = add(normal, dn, "same card"), cs = add(strict, dsx, "same card");
        prepareReview(normal, cn.optString("id"), now);
        prepareReview(strict, cs.optString("id"), now);
        normal.settings(dn, "标准", 10, 50, 20, false, 0.90, 36500);
        strict.settings(dsx, "强化", 10, 50, 20, false, 0.95, 36500);
        normal.rate(cn.optString("id"), 3, now);
        strict.rate(cs.optString("id"), 3, now);
        check(normal.card(cn.optString("id")).optLong("due") > now, "review stays in the future");
        check(strict.card(cs.optString("id")).optLong("due") < normal.card(cn.optString("id")).optLong("due"),
                "higher retention schedules earlier");
        final MemoryData fm = normal; final String fd = dn;
        reject(() -> fm.settings(fd, "x", 10, 50, 20, false, 0.99, 36500), "retention out of range rejected");
        reject(() -> fm.settings(fd, "x", 10, 50, 20, false, 0.90, 0), "max interval out of range rejected");

        JSONObject bad = MemoryData.copy(new MemoryData().db);
        MemoryData.put(bad.optJSONArray("decks").optJSONObject(0), "requestRetention", 0.5);
        final String badRetention = bad.toString();
        reject(() -> new MemoryData(badRetention), "persisted retention out of range rejected");
        bad = MemoryData.copy(new MemoryData().db);
        MemoryData.put(bad.optJSONArray("decks").optJSONObject(0), "maxInterval", 0);
        final String badInterval = bad.toString();
        reject(() -> new MemoryData(badInterval), "persisted max interval out of range rejected");
        JSONObject good = MemoryData.copy(new MemoryData().db);
        MemoryData.put(good.optJSONArray("decks").optJSONObject(0), "requestRetention", 0.95);
        MemoryData.put(good.optJSONArray("decks").optJSONObject(0), "maxInterval", 30);
        check(new MemoryData(good.toString()).decks().length() == 3, "optional tuning keys accepted");
    }
    private static void prepareReview(MemoryData m, String id, long now) {
        JSONObject live = m.card(id);
        MemoryData.put(live, "reps", 5); MemoryData.put(live, "state", 1);
        MemoryData.put(live, "stability", 100); MemoryData.put(live, "difficulty", 5);
        MemoryData.put(live, "last", now - MemoryScheduler.DAY); MemoryData.put(live, "due", now - 1000);
    }
    private static void presets() {
        MemoryPlan.Preset[] list = MemoryPlan.presets();
        check(list.length == 4, "four presets");
        String[] ids = {"light", "standard", "intense", "exam"};
        for (int i = 0; i < ids.length; i++) check(ids[i].equals(list[i].id), "stable preset order " + ids[i]);
        for (MemoryPlan.Preset p : list) {
            check(p.newLimit >= 0 && p.newLimit <= 200, "newLimit range " + p.id);
            check(p.reviewLimit >= 1 && p.reviewLimit <= 1000, "reviewLimit range " + p.id);
            check(p.target >= 1 && p.target <= 1000, "target range " + p.id);
            check(p.retention >= 0.70 && p.retention <= 0.97, "retention range " + p.id);
            check(p.maxInterval >= 1 && p.maxInterval <= 36500, "maxInterval range " + p.id);
        }
        MemoryPlan.Preset standard = MemoryPlan.preset("standard");
        check(standard.newLimit == 10 && standard.reviewLimit == 50 && standard.target == 20
                && Math.abs(standard.retention - 0.90) < 1e-12 && standard.maxInterval == 36500, "standard equals defaults");
        check(MemoryPlan.preset("standard") != null && MemoryPlan.presets()[1] == standard, "preset lookup is stable");
        check("standard".equals(MemoryPlan.match(10, 50, 20, 0.90, 36500)), "exact match");
        check(MemoryPlan.CUSTOM.equals(MemoryPlan.match(11, 50, 20, 0.90, 36500)), "differing value is custom");
        check("standard".equals(MemoryPlan.match(10, 50, 20, 0.9 + 1e-12, 36500)), "retention epsilon");
        check("intense".equals(MemoryPlan.match(15, 80, 30, 0.93, 3650)), "intense preset match");
        check(MemoryPlan.CUSTOM.equals(MemoryPlan.match(10, 50, 20, 0.90, 3650)), "interval differences are custom");
        check("自定义".equals(MemoryPlan.label(MemoryPlan.CUSTOM)) && "自定义".equals(MemoryPlan.label("nope")), "unknown label");
        check("轻量".equals(MemoryPlan.label("light")), "preset label");
        check(MemoryPlan.preset("nope") == null && MemoryPlan.preset("") == null, "unknown preset is null");
        MemoryData fresh = new MemoryData();
        for (int i = 0; i < 3; i++) {
            JSONObject deck = fresh.decks().optJSONObject(i);
            check("standard".equals(MemoryPlan.match(deck.optInt("newLimit"), deck.optInt("reviewLimit"), deck.optInt("target"),
                    MemoryData.retention(deck), MemoryData.maxInterval(deck))), "default deck reads as standard");
        }
    }
    private static void subjectDeletion() {
        long now=1729500000000L;MemoryData m=new MemoryData();String id=deck(m,0);
        JSONObject first=add(m,id,"first"),second=add(m,id,"second");
        m.settings(id,"英语单词",10,50,2,true);m.plan(id,now);m.rate(first.optString("id"),4,now);
        String cardBefore=m.card(first.optString("id")).toString(),planBefore=m.plan(id,now).toString();
        int logs=m.db.optJSONArray("logs").length();check(m.pending(now)==1,"linked subject pending before delete");
        m.deleteDeck(id,now);
        check(m.activeDecks().length()==2 && m.deletedDecks().length()==1,"subject moved to trash");
        check(m.count(id)==2 && m.db.optJSONArray("logs").length()==logs,"trash keeps cards and review logs");
        check(m.pending(now)==0 && !m.hasTargets(now),"deleted subject no longer extends freezing");
        check(m.next(id,now)==null,"deleted subject cannot be reviewed");
        reject(()->m.rate(second.optString("id"),4,now),"stale review cannot grade deleted subject");
        reject(()->m.addDeck("英语单词","english"),"deleted name directs to restore");
        MemoryData restored=new MemoryData(m.db.toString());
        check(restored.deleted(id) && restored.activeDecks().length()==2,"deletion survives restart");
        restored.restoreDeck(id);
        check(restored.activeDecks().length()==3 && restored.deletedDecks().length()==0,"undo restores original subject");
        check(cardBefore.equals(restored.card(first.optString("id")).toString()),"undo preserves scheduling state");
        check(planBefore.equals(restored.plan(id,now).toString()) && restored.pending(now)==1,"undo restores fixed daily plan and gate");
        check(restored.db.optJSONArray("logs").length()==logs,"undo does not duplicate review records");
        org.json.JSONArray active=restored.activeDecks();for(int i=0;i<active.length();i++)restored.deleteDeck(active.optJSONObject(i).optString("id"),now);
        check(new MemoryData(restored.db.toString()).activeDecks().length()==0,"all defaults can stay deleted");
    }
    private static void customSubjects() {
        MemoryData m=new MemoryData();JSONObject d=m.addDeck(" 电路 ","电路");String id=d.optString("id");
        add(m,id,"欧姆定律");MemoryData restored=new MemoryData(m.db.toString());
        check(restored.deck(id).optString("name").equals("电路"),"custom subject survives backup");
        check(restored.deck(id).optString("subject").equals("电路"),"custom subject category survives");
        check(restored.count(id)==1,"custom subject cards preserved");
        check(!restored.deck(id).optBoolean("linked"),"new subject does not enable gate automatically");
        reject(()->m.addDeck("电路","电路"),"duplicate subject rejected");
        reject(()->m.addDeck("  ","电路"),"blank subject rejected");
        reject(()->m.addDeck("电路\n实验","电路"),"multiline subject rejected");
    }
    private static void upstreamVectors() {
        // Golden vectors copied from upstream FSRSTest.java at the pinned revision (MIT).
        int[] ratings = {3,3,3,3,3,3,1,1,3,3,3,3,3};
        int[] expected = {0,4,14,45,135,372,0,0,2,5,10,20,40};
        MemoryScheduler.State s = new MemoryScheduler.State(); long now = 1669725000000L;
        for (int i = 0; i < ratings.length; i++) {
            s = MemoryScheduler.next(s, ratings[i], now);
            check((s.due - now) / MemoryScheduler.DAY == expected[i], "upstream interval " + i);
            now = s.due;
        }
        s = new MemoryScheduler.State(); now = 1669725000000L;
        int[] r = {1,3,3,3,3,3}, intervals = {0,0,1,3,8,21};
        for (int i = 0; i < r.length; i++) { now += intervals[i] * MemoryScheduler.DAY; s = MemoryScheduler.next(s, r[i], now); }
        s = MemoryScheduler.next(s, 3, now);
        check(Math.abs(s.stability - 49.4472) < .0001, "upstream stability");
        check(Math.abs(s.difficulty - 6.8271) < .0001, "upstream difficulty");
        final MemoryScheduler.State invalid = s;
        reject(() -> MemoryScheduler.next(invalid, 0, 0), "invalid rating");
    }
    private static void learning() {
        long now = System.currentTimeMillis(); MemoryData m = new MemoryData(); String d = deck(m, 0);
        JSONObject c = add(m, d, "word"); String id = c.optString("id");
        final MemoryData firstModel = m; final String firstId = id;
        check(!m.rate(id, 3, now), "new GOOD is a learning step, no credit");
        check(m.progress(d, now).done == 0, "no early credit");
        reject(() -> firstModel.rate(firstId, 3, now + 1), "cannot repeat before due");
        check(m.next(d, now) == null, "learning waits");
        check(m.next(d, now + 600000) != null, "learning reappears at due");
        check(m.rate(id, 3, now + 600000), "graduation earns credit");
        check(m.progress(d, now + 600000).done == 1, "one distinct card completed");
        reject(() -> firstModel.rate(firstId, 4, now + 600001), "same-day duplicate rejected");
        m = new MemoryData(); d = deck(m, 0); c = add(m, d, "forgot"); id = c.optString("id");
        check(!m.rate(id, 1, now), "forgotten is not credit");
        check(m.card(id).optInt("lapses") == 1, "wrong book flag");
        check(m.next(d, now + 59999) == null, "AGAIN waits a minute");
        check(!m.rate(id, 3, now + 60000), "forgotten card must complete the second step");
        check(m.rate(id, 3, now + 660000), "relearning completed");
        MemoryData saved = new MemoryData(m.db.toString());
        check(saved.progress(d, now + 660000).done == 1, "progress survives restart");
        check(saved.next(d, now + 660000) == null, "completed card removed from queue");
    }
    private static void quotas() {
        long now = System.currentTimeMillis(); MemoryData m = new MemoryData();
        check(m.decks().length() == 3, "three subject defaults");
        check(m.pending(now) == 0 && !m.hasTargets(now), "empty library never holds");
        for (int i = 0; i < 3; i++) {
            String d = deck(m, i); add(m, d, "concept " + i);
            m.settings(d, "subject " + i, 10, 50, 20, true);
        }
        check(m.pending(now) == 3, "independent subject targets");
        check(m.progress(deck(m, 0), now).target == 1, "target capped to available cards");
        m.rate(m.next(deck(m, 0), now).optString("id"), 4, now);
        check(m.pending(now) == 2, "English does not pay off medicine/engineering");
        String d = deck(m, 1); String id = m.next(d, now).optString("id"); m.rate(id, 1, now);
        m.settings(d, "renamed", 0, 1, 1, false);
        check(m.progress(d, now).linked, "linked setting frozen after starting");
        add(m, d, "added after start");
        check(m.progress(d, now).total == 1, "new cards do not enlarge started daily plan");
        long tomorrow = now + MemoryScheduler.DAY;
        check(!m.progress(d, tomorrow).linked, "new settings apply next day");
        check(m.progress(d, tomorrow).done == 0, "daily credit resets");
        final MemoryData model = m; final String did = d;
        reject(() -> model.settings(did, "name", -1, 1, 1, true), "bad settings rejected");
    }
    private static void queues() {
        long now = System.currentTimeMillis(); MemoryData m = new MemoryData(); String d = deck(m, 0);
        add(m, d, "new");
        for (int i = 0; i < 3; i++) {
            JSONObject c = add(m, d, "review" + i); JSONObject live = m.card(c.optString("id"));
            MemoryData.put(live, "reps", 3); MemoryData.put(live, "state", 1); MemoryData.put(live, "stability", 3);
            MemoryData.put(live, "difficulty", 5); MemoryData.put(live, "last", now - 4 * MemoryScheduler.DAY);
            MemoryData.put(live, "due", now - (3 - i) * 10000);
        }
        m.settings(d, "deck", 10, 2, 20, true);
        MemoryData.Progress p = m.progress(d, now);
        check(p.newCount == 0 && p.reviewCount == 2, "backlog pauses new cards and caps reviews");
        check(m.next(d, now).optString("front").equals("review0"), "oldest due first");
        check(p.target == 2, "quota cannot exceed queue cap");
        m = new MemoryData(); d = deck(m, 0); JSONObject c = add(m, d, "across midnight");
        Calendar late = Calendar.getInstance(); late.setTimeInMillis(now);
        late.set(Calendar.HOUR_OF_DAY, 23); late.set(Calendar.MINUTE, 58); late.set(Calendar.SECOND, 0); late.set(Calendar.MILLISECOND, 0);
        long at = late.getTimeInMillis(); m.rate(c.optString("id"), 3, at);
        check(m.progress(d, at + 180000).total == 1, "short learning steps retained across midnight");
        check(m.next(d, at + 180000) == null, "next-day learning still waits until exact due");
        check(m.next(d, at + 600000) != null, "next-day learning becomes available");
    }
    private static void backupValidation() {
        MemoryData m = new MemoryData(); String d = deck(m, 0); JSONObject c = add(m, d, "restore me");
        m.plan(d, System.currentTimeMillis());
        MemoryData restored = new MemoryData(m.db.toString());
        check(restored.cards().length() == 1, "backup restores cards");
        check(restored.card(c.optString("id")).optString("source").equals("教材第 10 页"), "backup retains source");
        reject(() -> new MemoryData("{}"), "bad root rejected");
        reject(() -> new MemoryData("{"), "broken JSON rejected");
        JSONObject bad = MemoryData.copy(m.db); MemoryData.put(bad, "version", 999);
        reject(() -> new MemoryData(bad.toString()), "unsupported version rejected");
        JSONObject duplicate = MemoryData.copy(m.db); duplicate.optJSONArray("cards").put(c);
        reject(() -> new MemoryData(duplicate.toString()), "duplicate ids rejected");
        JSONObject image = MemoryData.newCard(d, "image", "diagram", "label", "", "");
        MemoryData.put(image, "image", "../../escape.jpg");
        reject(() -> m.saveCard(image), "media traversal rejected");
        MemoryData.put(image, "image", "valid.jpg");
        reject(() -> m.saveCard(image), "missing masks rejected");
        JSONArray masks = new JSONArray(); JSONArray r = new JSONArray();
        r.put(0); r.put(0); r.put(2); r.put(1); masks.put(r); MemoryData.put(image, "masks", masks);
        reject(() -> m.saveCard(image), "out-of-bounds masks rejected");
        JSONObject unknown = MemoryData.newCard("absent", "qa", "q", "a", "", "");
        reject(() -> m.saveCard(unknown), "unknown deck rejected");
        JSONObject fake = MemoryData.copy(m.db);
        fake.optJSONObject("plans").optJSONObject(MemoryData.date(System.currentTimeMillis())).optJSONObject(d).optJSONArray("passed").put("absent");
        reject(() -> new MemoryData(fake.toString()), "forged plan credit rejected");
        check(m.cards().length() == 1, "failed imports leave source model unchanged");
    }
    private static void text() {
        check(MemoryData.matches("  Memory ", "memory|记忆", true), "English trims/case-folds");
        check(MemoryData.matches("ｍｅｍｏｒｙ", "memory", true), "fullwidth normalization");
        check(!MemoryData.matches("", "", true), "empty input never passes");
        check(!MemoryData.matches("M", "m", false), "technical symbol case preserved");
        check(MemoryData.matches("Pa", "pascal|Pa", false), "explicit accepted variants");
        JSONObject c = MemoryData.newCard("x", "cloze", "单位 {{N}}\n符号 {{a\nb}}", "解释", "", "");
        check(!MemoryData.question(c).contains("N") && !MemoryData.question(c).contains("a\nb"), "multiline cloze hides all answers");
        check(MemoryData.answer(c).contains("N") && MemoryData.answer(c).contains("解释"), "cloze reveals answer and explanation");
        MemoryData m = new MemoryData(); JSONObject invalid = MemoryData.newCard(deck(m, 0), "cloze", "no blank", "", "", "");
        reject(() -> m.saveCard(invalid), "cloze requires placeholder");
    }
    private static void gate() {
        Calendar now = Calendar.getInstance(); now.set(Calendar.HOUR_OF_DAY, 19); now.set(Calendar.MINUTE, 59);
        String day = MemoryData.date(now.getTimeInMillis());
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(now.getTime());
        String pending = "{\"enabled\":true,\"allDone\":false,\"date\":\"" + day + "\",\"checkedAt\":\"" + stamp + "\"}";
        StudyGate.Snapshot s = StudyGate.parse(pending, day, now.getTimeInMillis());
        check(!StudyGate.shouldHold(s, now), "memory target does not hold before 20");
        now.set(Calendar.HOUR_OF_DAY, 20); now.set(Calendar.MINUTE, 0);
        check(StudyGate.shouldHold(s, now), "memory pending participates in existing gate at 20");
        StudyGate.Snapshot done = StudyGate.parse(pending.replace("false", "true"), day, now.getTimeInMillis());
        check(!StudyGate.shouldHold(done, now), "completion releases only additional study gate");
        Rule blocked = new Rule(0x7F, 22 * 60, 23 * 60);
        check(!blocked.inWindow(now), "base schedule still blocks at 20 despite completion");
    }
}
