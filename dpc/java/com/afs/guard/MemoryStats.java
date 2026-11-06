package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;

/**
 * 记忆情况的统计口径：纯计算，不碰 Android，可离线测。
 *
 * 数据全部来自 memory-v1.json 已有的两份记录 —— 卡片本身的 FSRS 状态（state / stability / due）
 * 和评分日志（logs：ts + rating）。不另存任何计数器：同一份日志重算永远是同一个数，
 * 也不会因为补做迁移而出现「统计和账本对不上」。
 */
public final class MemoryStats {
    /** 曲线分桶：距上次复习的时间。间隔越长还答得出来的比例就是留存率。 */
    static final double[] BUCKET_DAYS = {1, 2, 4, 7, 15, 30};
    private MemoryStats() { }

    public static final class Summary {
        public int total, learned, dueToday, reviewedToday;
        /** 近 7 天（含今天）的评分次数与其中记住的次数（评分 ≥3）。 */
        public int reviews7, correct7;
        /** 连续有复习记录的天数（今天还没复习就从昨天算起）。 */
        public int streak;
        public double accuracy7() { return reviews7 == 0 ? 0 : correct7 / (double) reviews7; }
    }

    public static Summary summary(MemoryData m, long now) {
        return summary(m, now, Collections.<String>emptySet());
    }
    public static Summary summary(MemoryData m, long now, Set<String> selectedDecks) {
        Summary s = new Summary();
        JSONArray cards = m.cards();
        long endOfToday = dayStart(now) + MemoryScheduler.DAY;
        for (int i = 0; i < cards.length(); i++) {
            JSONObject c = cards.optJSONObject(i); if (c == null) continue;
            if (!included(m, c.optString("deck"), selectedDecks)) continue;
            s.total++;
            // 从未学过的新卡 reps=0、due=0，不能算「到期」——否则 7000 张新卡会把今天写成灾难。
            if (c.optInt("reps") > 0) {
                s.learned++;
                // 和「未来 7 天」的第一格同口径：今天稍后到期的也算今天要还的债。
                if (c.optLong("due") < endOfToday) s.dueToday++;
            }
        }
        long weekStart = dayStart(now - 6 * MemoryScheduler.DAY);
        List<JSONObject> logs = logs(m);
        Map<String, Boolean> days = new HashMap<>();
        for (JSONObject log : logs) {
            long ts = log.optLong("ts");
            if (ts >= weekStart && ts <= now && included(m, log.optString("deck"), selectedDecks)) {
                s.reviews7++;
                if (log.optInt("rating") >= 3) s.correct7++;
            }
            if (ts <= now && included(m, log.optString("deck"), selectedDecks)) days.put(MemoryData.date(ts), Boolean.TRUE);
        }
        s.reviewedToday = 0;
        for (JSONObject log : logs) if (log.optLong("ts") <= now && included(m, log.optString("deck"), selectedDecks) && MemoryData.date(now).equals(MemoryData.date(log.optLong("ts")))) s.reviewedToday++;
        // 今天还没开始也算连续：从昨天往回数，免得一觉醒来连续天数归零。
        long cursor = days.containsKey(MemoryData.date(now)) ? now : now - MemoryScheduler.DAY;
        while (days.containsKey(MemoryData.date(cursor))) { s.streak++; cursor -= MemoryScheduler.DAY; }
        return s;
    }

    private static int countOn(List<JSONObject> logs, MemoryData m, long now) {
        String today = MemoryData.date(now);
        int n = 0;
        for (JSONObject log : logs)
            if (today.equals(MemoryData.date(log.optLong("ts"))) && !m.deleted(log.optString("deck"))) n++;
        return n;
    }

    /** 近 days 天（含今天）每天复习张数与其中记住的张数。 */
    public static void daily(MemoryData m, long now, int days, int[] reviewed, int[] correct, String[] labels) {
        List<JSONObject> logs = logs(m);
        long start = dayStart(now);
        for (int i = 0; i < days; i++) { reviewed[i] = 0; correct[i] = 0; }
        for (JSONObject log : logs) {
            long ts = log.optLong("ts");
            if (ts > now || m.deleted(log.optString("deck"))) continue;
            long diff = (dayStart(ts) - (start - (days - 1) * MemoryScheduler.DAY));
            int slot = (int) Math.round(diff / (double) MemoryScheduler.DAY);
            if (slot < 0 || slot >= days) continue;
            reviewed[slot]++;
            if (log.optInt("rating") >= 3) correct[slot]++;
        }
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < days; i++) {
            long day = start - (days - 1 - i) * MemoryScheduler.DAY;
            c.setTimeInMillis(day);
            labels[i] = (c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.DAY_OF_MONTH);
        }
    }

    /**
     * 未来 days 天的到期量：第 0 格是「今天及已逾期」，其余是往后每天。
     * 用于看积压会不会堆起来 —— 曲线陡的地方就是需要提前清理的地方。
     */
    public static int[] forecast(MemoryData m, long now, int days) {
        return forecast(m, now, days, Collections.<String>emptySet());
    }
    public static int[] forecast(MemoryData m, long now, int days, Set<String> selectedDecks) {
        int[] out = new int[days];
        long start = dayStart(now);
        JSONArray cards = m.cards();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject c = cards.optJSONObject(i); if (c == null) continue;
            if (!included(m, c.optString("deck"), selectedDecks)) continue;
            if (c.optInt("reps") <= 0) continue;            // 新卡不在到期预测里
            long due = c.optLong("due");
            if (due <= now) { out[0]++; continue; }
            int slot = (int) Math.round((dayStart(due) - start) / (double) MemoryScheduler.DAY);
            if (slot >= 0 && slot < days) out[slot]++;
        }
        return out;
    }

    /** 每个学科各自的到期量（含今天稍后到期的），只列有到期的学科。 */
    public static List<String> dueByDeck(MemoryData m, long now) {
        return dueByDeck(m, now, Collections.<String>emptySet());
    }
    public static List<String> dueByDeck(MemoryData m, long now, Set<String> selectedDecks) {
        long end = dayStart(now) + MemoryScheduler.DAY;
        Map<String, Integer> counter = new HashMap<>();
        JSONArray cards = m.cards();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject c = cards.optJSONObject(i); if (c == null) continue;
            String deck = c.optString("deck");
            if (!included(m, deck, selectedDecks) || c.optInt("reps") <= 0 || c.optLong("due") >= end) continue;
            Integer n = counter.get(deck);
            counter.put(deck, n == null ? 1 : n + 1);
        }
        List<String> rows = new ArrayList<>();
        JSONArray decks = m.activeDecks();
        for (int i = 0; i < decks.length(); i++) {
            JSONObject d = decks.optJSONObject(i); if (d == null) continue;
            Integer n = counter.get(d.optString("id"));
            if (n != null && n > 0) rows.add(d.optString("name") + "\t" + n);
        }
        return rows;
    }

    /**
     * 留存曲线：把每次评分按「距同一张卡上一次复习的天数」分桶，算桶内记住的比例。
     * 数据不足的桶返回 -1（调用方不画点），样本数单独给，避免 1 张卡撑起一条假曲线。
     */
    public static void retention(MemoryData m, int[] samples, int[] correct, String[] labels) {
        retention(m, samples, correct, labels, Collections.<String>emptySet());
    }
    public static void retention(MemoryData m, int[] samples, int[] correct, String[] labels, Set<String> selectedDecks) {
        for (int i = 0; i < samples.length; i++) { samples[i] = 0; correct[i] = 0; }
        Map<String, JSONObject> last = new HashMap<>();
        List<JSONObject> logs = logs(m);
        for (JSONObject log : logs) {
            if (!included(m, log.optString("deck"), selectedDecks)) continue;
            String card = log.optString("card");
            JSONObject prev = last.get(card);
            if (prev != null) {
                double days = (log.optLong("ts") - prev.optLong("ts")) / (double) MemoryScheduler.DAY;
                int slot = bucket(days);
                if (slot >= 0) { samples[slot]++; if (log.optInt("rating") >= 3) correct[slot]++; }
            }
            last.put(card, log);
        }
        String[] names = {"<1 天", "1–2 天", "2–4 天", "4–7 天", "7–15 天", "15–30 天", "30 天+"};
        for (int i = 0; i < labels.length; i++) labels[i] = names[i];
    }

    private static int bucket(double days) {
        for (int i = 0; i < BUCKET_DAYS.length; i++) if (days < BUCKET_DAYS[i]) return i;
        return BUCKET_DAYS.length;
    }

    public static final int BUCKET_NEW = 0, BUCKET_LEARNING = 1, BUCKET_SHORT = 2, BUCKET_LONG = 3;
    /** 记忆强度构成：未学 / 学习中 / 稳定度 ≤7 天 / 稳定度 >7 天。 */
    public static int[] strength(MemoryData m) {
        return strength(m, Collections.<String>emptySet());
    }
    public static int[] strength(MemoryData m, Set<String> selectedDecks) {
        int[] out = new int[4];
        JSONArray cards = m.cards();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject c = cards.optJSONObject(i); if (c == null) continue;
            if (!included(m, c.optString("deck"), selectedDecks)) continue;
            if (c.optInt("reps") <= 0) { out[BUCKET_NEW]++; continue; }
            if (c.optInt("state", MemoryScheduler.LEARNING) != MemoryScheduler.REVIEW) { out[BUCKET_LEARNING]++; continue; }
            out[c.optDouble("stability", 0) <= 7 ? BUCKET_SHORT : BUCKET_LONG]++;
        }
        return out;
    }

    static boolean included(MemoryData m, String deck, Set<String> selected) {
        return m.deck(deck) != null && !m.deleted(deck) && (selected == null || selected.isEmpty() || selected.contains(deck));
    }
    public enum Period { DAY, WEEK, MONTH }
    public static final class PeriodSeries {
        public final String[] labels;
        public final int[] reviews, correct, uniqueCards;
        public final long[] starts, ends;
        PeriodSeries(int count) {
            labels=new String[count];reviews=new int[count];correct=new int[count];uniqueCards=new int[count];
            starts=new long[count];ends=new long[count];
        }
    }
    /** Natural local calendar buckets, weeks start Monday; no persistence or plan access. */
    public static PeriodSeries periods(MemoryData m,long now,Period unit,int count,Set<String> decks) {
        if(unit==null || count<1 || count>366)throw new IllegalArgumentException("Invalid period range");
        PeriodSeries out=new PeriodSeries(count);
        Calendar c=Calendar.getInstance();c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        int field=unit==Period.MONTH?Calendar.MONTH:unit==Period.WEEK?Calendar.WEEK_OF_YEAR:Calendar.DAY_OF_MONTH;
        if(unit==Period.MONTH)c.set(Calendar.DAY_OF_MONTH,1);
        else if(unit==Period.WEEK)c.add(Calendar.DAY_OF_MONTH,-((c.get(Calendar.DAY_OF_WEEK)+5)%7));
        c.add(field,-(count-1));
        List<Set<String>> seen=new ArrayList<>();
        for(int i=0;i<count;i++) {
            out.starts[i]=c.getTimeInMillis();
            out.labels[i]=unit==Period.MONTH?String.format(java.util.Locale.US,"%04d/%02d",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1)
                    :(c.get(Calendar.MONTH)+1)+"/"+c.get(Calendar.DAY_OF_MONTH);
            c.add(field,1);out.ends[i]=c.getTimeInMillis();seen.add(new HashSet<String>());
        }
        for(JSONObject log:logs(m)) {
            long ts=log.optLong("ts");if(ts>now || !included(m,log.optString("deck"),decks))continue;
            int slot=java.util.Arrays.binarySearch(out.starts,ts);if(slot<0)slot=-slot-2;
            if(slot<0 || ts>=out.ends[slot])continue;
            out.reviews[slot]++;if(log.optInt("rating")>=3)out.correct[slot]++;
            String id=log.optString("card");if(!id.isEmpty())seen.get(slot).add(id);
        }
        for(int i=0;i<count;i++)out.uniqueCards[i]=seen.get(i).size();
        return out;
    }
    /** Only learned REVIEW cards; five mutually exclusive stability ranges in days. */
    public static int[] stabilityBands(MemoryData m,Set<String> decks) {
        int[] out=new int[5];JSONArray cards=m.cards();
        for(int i=0;i<cards.length();i++) {
            JSONObject c=cards.optJSONObject(i);if(c==null || !included(m,c.optString("deck"),decks)
                    || c.optInt("reps")<=0 || c.optInt("state")!=MemoryScheduler.REVIEW)continue;
            double stability=c.optDouble("stability",0);
            if(Double.isNaN(stability)||Double.isInfinite(stability)||stability<0)continue;
            out[stability<10?0:stability<30?1:stability<60?2:stability<90?3:4]++;
        }
        return out;
    }

    static List<JSONObject> logs(MemoryData m) {
        JSONArray a = m.db.optJSONArray("logs");
        List<JSONObject> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject log = a.optJSONObject(i);
            if (log != null) out.add(log);
        }
        return out;
    }

    static long dayStart(long now) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
