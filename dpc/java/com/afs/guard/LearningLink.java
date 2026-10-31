package com.afs.guard;

import android.content.Context;
import org.json.JSONObject;

/** Derived progress; learning records remain owned by their respective stores. */
final class LearningLink {
    private LearningLink() { }
    static final class Progress {
        int done, target;
        boolean available, selected;
        boolean complete() { return available && LearningPolicy.complete(selected, target, done); }
    }
    static Progress memory(Context c) {
        Progress out = new Progress();
        try {
            MemoryData data = MemoryStore.snapshot(c);
            long now = System.currentTimeMillis();
            for (int i = 0; i < data.decks().length(); i++) {
                JSONObject deck = data.decks().optJSONObject(i);
                if (deck == null || data.deleted(deck.optString("id"))) continue;
                MemoryData.Progress p = data.progress(deck.optString("id"), now);
                if (p.linked && memoryEnabled(c, deck.optString("id"))) {
                    out.selected = true;
                    out.target += p.target;
                    out.done += Math.min(p.done, p.target);
                }
            }
            out.available = true;
        } catch (RuntimeException ignored) { }
        return out;
    }
    static boolean memoryEnabled(Context c, String deck) {
        return c.getSharedPreferences("afs_learning_link", 0).getBoolean("memory:" + deck, false);
    }
    static void setMemoryEnabled(Context c, String deck, boolean enabled) {
        c.getSharedPreferences("afs_learning_link", 0).edit().putBoolean("memory:" + deck, enabled).apply();
        StudyGate.invalidate(c);
    }
    static boolean quizEnabled(Context c) {
        return c.getSharedPreferences("afs_learning_link", 0).getBoolean("quiz_enabled", false);
    }
    static int quizTarget(Context c) {
        return Math.max(1, c.getSharedPreferences("afs_learning_link", 0).getInt("quiz_target", 10));
    }
    static Progress quiz(Context c) {
        Progress p = new Progress();
        if (!quizEnabled(c)) return p;
        p.selected = true;
        try {
            p.done = QuizStore.snapshot(c).stats(StudyStore.today())[0];
            p.target = quizTarget(c);
            p.available = true;
        } catch (RuntimeException ignored) { }
        return p;
    }
    static void configureQuiz(Context c, boolean enabled, int target) {
        if (target < 1 || target > 1000) throw new IllegalArgumentException(T.s("每日题数须为 1–1000"));
        c.getSharedPreferences("afs_learning_link", 0).edit()
                .putBoolean("quiz_enabled", enabled).putInt("quiz_target", target).apply();
        StudyGate.invalidate(c);
    }
}
