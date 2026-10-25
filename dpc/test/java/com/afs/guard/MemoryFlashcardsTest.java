package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

public final class MemoryFlashcardsTest {
    private static int count;
    private static final long NOW = 1792893600000L;
    private static void check(boolean value, String message) { count++; if (!value) throw new AssertionError(message); }
    private static void reject(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException e) { count++; return; }
        throw new AssertionError(message);
    }
    private static String deck(MemoryData m, int i) { return m.activeDecks().optJSONObject(i).optString("id"); }
    private static JSONArray selected(String... ids) { JSONArray a = new JSONArray(); for (String id : ids) a.put(id); return a; }
    private static String add(MemoryData m, String d, String word) {
        JSONObject c = MemoryData.newCard(d, "qa", word, "answer", "", ""); m.saveCard(c); return c.optString("id");
    }
    private static void rate(MemoryData m, int rating, long now) {
        m.rateFlashcard(MemoryFlashcards.next(m, now).optString("id"), rating, now, MemoryData.date(now));
    }
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        sequence(); random(); changes(); validation();
        System.out.println("Memory flashcards: " + count + " assertions passed");
    }
    private static void sequence() {
        MemoryData m = new MemoryData(); String en = deck(m, 0), med = deck(m, 1);
        String a = add(m, en, "a"), b = add(m, med, "b"), c = add(m, en, "c"), d = add(m, med, "d");
        MemoryFlashcards.configure(m, selected(en, med), 2, "sequence", NOW);
        check(MemoryFlashcards.session(m, NOW).optJSONArray("ids").toString().equals(selected(a, b).toString()), "insertion order across subjects");
        reject(() -> m.rateFlashcard(b, 4, NOW, MemoryData.date(NOW)), "cannot skip queue order");
        reject(() -> m.rateFlashcard(a, 0, NOW, MemoryData.date(NOW)), "invalid rating rejected");
        check(MemoryFlashcards.progress(m, NOW).done == 0 && m.card(a).optInt("reps") == 0, "failed rating has no progress");
        rate(m, 3, NOW);
        check(MemoryFlashcards.next(m, NOW).optString("id").equals(b), "GOOD advances without minute wait");
        reject(() -> m.rateFlashcard(a, 4, NOW, MemoryData.date(NOW)), "same-day duplicate rejected");
        rate(m, 1, NOW);
        check(MemoryFlashcards.next(m, NOW) == null, "daily set finishes");
        check(m.card(b).optInt("lapses") == 1, "forgotten card enters wrong book");
        check(m.card(a).optInt("reps") == 1, "FSRS state updated");
        check(MemoryFlashcards.progress(m, NOW).done == 2, "all graded cards count as reviewed, not mastered");
        long tomorrow = NOW + MemoryScheduler.DAY;
        check(MemoryFlashcards.session(m, tomorrow).optJSONArray("ids").toString().equals(selected(c, d).toString()), "sequence continues tomorrow");
        check(MemoryFlashcards.progress(m, tomorrow).done == 0, "daily count resets");
        reject(() -> m.rateFlashcard(c, 3, tomorrow, MemoryData.date(NOW)), "stale midnight UI rejected");
        rate(m, 4, tomorrow); rate(m, 4, tomorrow);
        check(MemoryFlashcards.next(m, tomorrow + MemoryScheduler.DAY).optString("id").equals(a), "sequence wraps around");
    }
    private static void random() {
        MemoryData m = new MemoryData(); String en = deck(m, 0), med = deck(m, 1); Set<String> expected = new HashSet<>();
        for (int i = 0; i < 40; i++) expected.add(add(m, en, "word " + i));
        String excluded = add(m, med, "excluded"); MemoryFlashcards.configure(m, selected(en), 12, "random", NOW);
        JSONArray ids = MemoryFlashcards.session(m, NOW).optJSONArray("ids"); check(ids.length() == 12, "requested count");
        Set<String> actual = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) { check(expected.contains(ids.optString(i)), "only selected subjects"); actual.add(ids.optString(i)); }
        check(actual.size() == 12 && !actual.contains(excluded), "no duplicates or other subjects");
        String fixed = ids.toString(); MemoryData restored = new MemoryData(m.db.toString());
        check(MemoryFlashcards.session(restored, NOW + 1000).optJSONArray("ids").toString().equals(fixed), "restart does not reshuffle");
        rate(restored, 4, NOW); MemoryData again = new MemoryData(restored.db.toString());
        check(MemoryFlashcards.progress(again, NOW).done == 1, "progress survives restart");
        check(!MemoryFlashcards.next(again, NOW).optString("id").equals(ids.optString(0)), "completed card not repeated");
        MemoryFlashcards.configure(again, selected(en), 100, "sequence", NOW);
        check(MemoryFlashcards.progress(again, NOW).total == 39, "short bank caps quantity and redraw excludes completed cards");
        check(MemoryFlashcards.progress(again, NOW).reviewedToday == 1, "redraw preserves cumulative progress");
    }
    private static void changes() {
        MemoryData m = new MemoryData(); String a = deck(m, 0), b = deck(m, 1);
        check(MemoryFlashcards.progress(m, NOW).total == 0, "empty bank safe"); String first = add(m, a, "first");
        check(MemoryFlashcards.next(m, NOW).optString("id").equals(first), "empty set refreshes after adding cards");
        add(m, b, "second"); MemoryFlashcards.configure(m, selected(a, b), 20, "sequence", NOW); m.deleteDeck(a, NOW);
        check(!MemoryFlashcards.next(m, NOW).optString("deck").equals(a), "trashed subject skipped");
        check(MemoryFlashcards.progress(m, NOW).total == 1, "trashed subject removed from denominator");
        m.restoreDeck(a); check(MemoryFlashcards.progress(m, NOW).total == 2, "restore preserves queue safely");
        rate(m, 2, NOW); rate(m, 1, NOW); MemoryFlashcards.configure(m, selected(a, b), 20, "random", NOW);
        check(MemoryFlashcards.next(m, NOW) == null, "fully reviewed bank does not repeat after redraw");
        long tomorrow = NOW + MemoryScheduler.DAY;
        check(MemoryFlashcards.next(m, tomorrow) != null, "full bank includes future scheduled reviews");
        rate(m, 3, tomorrow); check(MemoryFlashcards.progress(m, tomorrow).done == 1, "custom review can grade future cards");
    }
    private static void validation() {
        MemoryData m = new MemoryData(); String en = deck(m, 0);
        reject(() -> MemoryFlashcards.configure(m, selected(), 20, "random", NOW), "subject required");
        reject(() -> MemoryFlashcards.configure(m, selected(en), 0, "random", NOW), "zero count");
        reject(() -> MemoryFlashcards.configure(m, selected(en), 1001, "random", NOW), "count bound");
        reject(() -> MemoryFlashcards.configure(m, selected("missing"), 20, "random", NOW), "unknown subject");
        reject(() -> MemoryFlashcards.configure(m, selected(en), 20, "bad", NOW), "unknown mode");
        add(m, en, "a"); MemoryFlashcards.configure(m, selected(en), 1, "sequence", NOW);
        check(new MemoryData(m.db.toString()).cards().length() == 1, "new backup roundtrip");
        JSONObject duplicate = MemoryData.copy(m.db); JSONArray ids = duplicate.optJSONObject("flashcards").optJSONObject("session").optJSONArray("ids"); ids.put(ids.optString(0));
        reject(() -> new MemoryData(duplicate.toString()), "duplicate backup queue rejected");
        JSONObject bad = MemoryData.copy(m.db); MemoryData.put(bad.optJSONObject("flashcards").optJSONObject("settings"), "count", -1);
        reject(() -> new MemoryData(bad.toString()), "malformed settings rejected");
        check(new MemoryData().db.optJSONObject("flashcards") == null, "old data stays compatible");
    }
}
