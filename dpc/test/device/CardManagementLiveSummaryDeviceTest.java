package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated device regression for the live learning-linkage summary in card management. */
public final class CardManagementLiveSummaryDeviceTest extends Instrumentation {
    private int checks;
    private final List<Activity> opened = new ArrayList<>();

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context c = getTargetContext();
        SharedPreferences language = c.getSharedPreferences(Lang.SP, 0);
        SharedPreferences linkage = c.getSharedPreferences("afs_learning_link", 0);
        SharedPreferences taboo = c.getSharedPreferences(StudyGate.TABOO_SP, 0);
        SharedPreferences completion = c.getSharedPreferences("afs_daily_completion", 0);
        SharedPreferences remote = c.getSharedPreferences(SyncConfig.cacheName(c, StudyGate.GIST_FILE), 0);
        Map<String, ?> oldLanguage = new HashMap<>(language.getAll());
        Map<String, ?> oldLinkage = new HashMap<>(linkage.getAll());
        Map<String, ?> oldTaboo = new HashMap<>(taboo.getAll());
        Map<String, ?> oldCompletion = new HashMap<>(completion.getAll());
        Map<String, ?> oldRemote = new HashMap<>(remote.getAll());
        File studyFile = new File(c.getFilesDir(), "study.json");
        File memoryFile = new File(c.getFilesDir(), "memory-v1.json");
        FileSnapshot study = new FileSnapshot(studyFile);
        FileSnapshot studyBackup = new FileSnapshot(new File(studyFile + ".bak"));
        FileSnapshot memory = new FileSnapshot(memoryFile);
        FileSnapshot memoryBackup = new FileSnapshot(new File(memoryFile + ".bak"));
        File cards = new File(c.getCacheDir(), "afs-live-summary-cards.json");
        File quiz = new File(c.getCacheDir(), "afs-live-summary-quiz.json");
        boolean fixtureStarted = false;
        try {
            check(android.os.Build.FINGERPRINT.contains("generic")
                    || android.os.Build.MODEL.contains("sdk_gphone"), "isolated emulator required");
            fixtureStarted = true;
            Lang.save(c, Lang.ZH);
            T.refresh(c);
            clear(linkage, taboo, completion, remote);
            study.file.delete();
            studyBackup.file.delete();
            memory.file.delete();
            memoryBackup.file.delete();
            cards.delete();
            quiz.delete();
            setMemoryCache(null, null);
            write(cards, new CardData(System.currentTimeMillis()).db.toString().getBytes("UTF-8"));
            CardStore.setTestFile(cards);
            QuizStore.setTestFile(quiz);
            StudyGate.resetRemote(c);
            StudyGate.invalidate(c);

            Activity manager = openManager(c);
            TextView state = tagged(manager, "learning_linkage_state");
            TextView elapsed = tagged(manager, "learning_linkage_elapsed");
            check(state != null && elapsed != null, "linkage state and elapsed use independent controls");
            awaitText(state, "当前无额外冻结", 1500);
            check(elapsed.getVisibility() == View.GONE, "elapsed line is hidden when no penalty exists");
            Handler handler = (Handler) field(manager, "linkageHandler");
            Runnable tick = (Runnable) field(manager, "linkageTick");
            check(handler.hasCallbacks(tick), "visible card manager schedules linkage refresh");

            final ScrollView homeScroll = scroll(manager);
            runMain(() -> homeScroll.scrollTo(0, 500));
            settle(120);
            final int homeY = homeScroll.getScrollY();
            check(homeY > 0, "card manager can be scrolled");

            AtomicInteger textChanges = new AtomicInteger();
            TextWatcher watcher = watcher(textChanges);
            runMain(() -> {
                state.addTextChangedListener(watcher);
                elapsed.addTextChangedListener(watcher);
            });
            Thread.sleep(1250);
            check(runInt(textChanges) == 0,
                    "one-second recomputation does not touch unchanged summary text");
            check(Math.abs(homeScroll.getScrollY() - homeY) < 3,
                    "idle summary refresh keeps scroll position");

            Calendar beforeDue = Calendar.getInstance();
            beforeDue.set(Calendar.HOUR_OF_DAY, 19);
            beforeDue.set(Calendar.MINUTE, 59);
            beforeDue.set(Calendar.SECOND, 0);
            beforeDue.set(Calendar.MILLISECOND, 0);
            runMain(() -> invoke(manager, "refreshLinkageSummary",
                    new Class<?>[]{Calendar.class}, beforeDue));
            check("当前无额外冻结".contentEquals(state.getText()),
                    "19:59 resolves through the production holdReason path");
            check(elapsed.getVisibility() == View.GONE,
                    "19:59 has no elapsed penalty line");

            publishPendingSummary(cards);
            StudyGate.invalidate(c);
            long changedAt = SystemClock.uptimeMillis();
            awaitContains(state, "总结", 1400);
            check(SystemClock.uptimeMillis() - changedAt <= 1400,
                    "visible summary reflects a new penalty within one refresh interval");
            check(elapsed.getVisibility() == View.VISIBLE && elapsed.getText().toString().contains("距本次检查已过"),
                    "overdue summary exposes elapsed time independently");
            check(Math.abs(homeScroll.getScrollY() - homeY) < 3,
                    "automatic penalty refresh keeps scroll position");
            String firstElapsed = runText(elapsed);
            Thread.sleep(6200);
            String laterElapsed = runText(elapsed);
            check(!firstElapsed.equals(laterElapsed),
                    "elapsed text increases while the page stays visible");
            check(Math.abs(homeScroll.getScrollY() - homeY) < 3,
                    "elapsed updates keep scroll position");

            runMain(() -> invoke(manager, "onPause", new Class<?>[0]));
            check(!handler.hasCallbacks(tick), "Activity pause stops linkage refresh");
            String pausedText = runText(state);
            completePendingSummaries(c);
            Thread.sleep(1250);
            check(runText(state).equals(pausedText),
                    "paused Activity performs no summary updates");
            runMain(() -> invoke(manager, "onResume", new Class<?>[0]));
            TextView resumedState = tagged(manager, "learning_linkage_state");
            TextView resumedElapsed = tagged(manager, "learning_linkage_elapsed");
            awaitText(resumedState, "当前无额外冻结", 1000);
            check(resumedElapsed.getVisibility() == View.GONE,
                    "returning after task completion immediately clears stale penalty");
            check(handler.hasCallbacks(tick), "resumed Activity restarts linkage refresh");
            check(Math.abs(scroll(manager).getScrollY() - homeY) < 3,
                    "resume refresh preserves scroll position");

            runMain(() -> {
                try {
                    set(manager, "category", "checkin");
                    invoke(manager, "render");
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });
            settle(150);
            check(!handler.hasCallbacks(tick), "card subcategory stops hidden linkage refresh");
            runMain(manager::onBackPressed);
            settle(250);
            check(handler.hasCallbacks(tick), "returning to manager home restarts linkage refresh");
            ScrollView returnedScroll = scroll(manager);
            check(Math.abs(returnedScroll.getScrollY() - homeY) < 3,
                    "home summary refresh restores prior scroll position");

            Activity destroyed = openManager(c);
            Handler destroyedHandler = (Handler) field(destroyed, "linkageHandler");
            Runnable destroyedTick = (Runnable) field(destroyed, "linkageTick");
            check(destroyedHandler.hasCallbacks(destroyedTick), "second manager schedules refresh");
            runMain(destroyed::finish);
            settle(700);
            check(!destroyedHandler.hasCallbacks(destroyedTick),
                    "Activity destruction removes linkage refresh callback");

            result.putString("stream", "Card management live summary device: " + checks + " checks passed\n");
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
        } finally {
            try {
                if (fixtureStarted) {
                    CardStore.setTestFile(null);
                    QuizStore.setTestFile(null);
                    cards.delete();
                    quiz.delete();
                    study.restore();
                    studyBackup.restore();
                    memory.restore();
                    memoryBackup.restore();
                    restore(linkage, oldLinkage);
                    restore(taboo, oldTaboo);
                    restore(completion, oldCompletion);
                    restore(remote, oldRemote);
                    setMemoryCache(null, null);
                    StudyGate.resetRemote(c);
                    StudyGate.invalidate(c);
                }
                restore(language, oldLanguage);
                T.refresh(c);
                runMain(() -> {
                    for (Activity activity : opened)
                        if (activity != null && !activity.isFinishing()) activity.finish();
                });
                settle(200);
            } catch (Throwable error) {
                String prior = result.getString("stream", "");
                result.putString("stream", prior + "Restore failed: " + android.util.Log.getStackTraceString(error));
            }
        }
        String output = result.getString("stream", "");
        finish(output.startsWith("Card management live summary device:")
                        && !output.contains("Restore failed")
                        && !output.contains("AssertionError")
                        ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
                result);
    }

    private Activity openManager(Context c) throws Exception {
        ActivityMonitor monitor = addMonitor(CardManagementActivity.class.getName(), null, false);
        runMain(() -> c.startActivity(new Intent(c, CardManagementActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));
        Activity activity = waitForMonitorWithTimeout(monitor, 5000);
        removeMonitor(monitor);
        check(activity != null, "card manager starts");
        opened.add(activity);
        settle(450);
        return activity;
    }

    private static void publishPendingSummary(File cards) throws Exception {
        long now = System.currentTimeMillis();
        CardData data = new CardData(now);
        data.summary(now).configure("daily", 1, CycleSummary.day(now), now);
        Calendar start = Calendar.getInstance();
        start.add(Calendar.DAY_OF_MONTH, -3);
        MemoryData.put(data.db.optJSONObject("summary"), "cursor",
                CycleSummary.day(start.getTimeInMillis()));
        write(cards, data.db.toString().getBytes("UTF-8"));
        CardStore.setTestFile(cards);
    }

    private void completePendingSummaries(Context c) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            CycleSummary.Period pending = CardStore.summaryPending(c, now);
            if (pending == null) break;
            CardStore.completeSummary(c, pending.key, "QA complete");
        }
        check(CardStore.summaryPending(c, now) == null, "fixture summary completed");
        StudyGate.invalidate(c);
    }

    private static TextWatcher watcher(AtomicInteger count) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int countValue, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int countValue) {
                count.incrementAndGet();
            }
            public void afterTextChanged(Editable editable) { }
        };
    }

    private static TextView tagged(Activity activity, String tag) {
        return (TextView) activity.getWindow().getDecorView().findViewWithTag(tag);
    }

    private static ScrollView scroll(Activity activity) {
        return findScroll(activity.getWindow().getDecorView());
    }

    private static ScrollView findScroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = findScroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void awaitText(TextView view, String expected, long timeout) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            if (expected.contentEquals(runText(view))) return;
            Thread.sleep(50);
        }
        check(false, "summary text did not become " + expected);
    }

    private void awaitContains(TextView view, String expected, long timeout) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            if (runText(view).contains(expected)) return;
            Thread.sleep(50);
        }
        check(false, "summary text did not contain " + expected);
    }

    private String runText(final TextView view) throws Exception {
        final String[] value = new String[1];
        runMain(() -> value[0] = view.getText().toString());
        return value[0];
    }

    private int runInt(final AtomicInteger counter) throws Exception {
        final int[] value = new int[1];
        runMain(() -> value[0] = counter.get());
        return value[0];
    }

    private void runMain(final Runnable action) throws Exception {
        final Throwable[] failure = new Throwable[1];
        final CountDownLatch done = new CountDownLatch(1);
        runOnMainSync(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure[0] = error;
            } finally {
                done.countDown();
            }
        });
        if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("main thread timeout");
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static Object invoke(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(owner);
    }

    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(owner, args);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static void setMemoryCache(Object cache, String path) {
        try {
            Field cacheField = MemoryStore.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            cacheField.set(null, cache);
            Field pathField = MemoryStore.class.getDeclaredField("cachePath");
            pathField.setAccessible(true);
            pathField.set(null, path);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static void clear(SharedPreferences... preferences) {
        for (SharedPreferences preference : preferences)
            if (!preference.edit().clear().commit())
                throw new IllegalStateException("QA preference reset failed");
    }

    private static void restore(SharedPreferences preference, Map<String, ?> values) {
        SharedPreferences.Editor editor = preference.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet())
            put(editor, entry.getKey(), entry.getValue());
        if (!editor.commit()) throw new IllegalStateException("QA preference restore failed");
    }

    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof java.util.Set) editor.putStringSet(key, (java.util.Set<String>) value);
    }

    private static byte[] bytes(File file) throws Exception {
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static void write(File file, byte[] value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value);
            out.getFD().sync();
        }
    }

    private static final class FileSnapshot {
        final File file;
        final byte[] value;
        FileSnapshot(File file) {
            this.file = file;
            byte[] current;
            try {
                current = bytes(file);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
            value = current;
        }
        void restore() throws Exception {
            if (value == null) file.delete();
            else write(file, value);
        }
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private void settle(long milliseconds) throws Exception {
        waitForIdleSync();
        Thread.sleep(milliseconds);
    }
}
