package com.afs.guard;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.zip.*;

/** Temporary same-signature instrumentation, removed after device QA. */
public class MemoryDeviceTest extends Instrumentation {
    private Bundle args;
    private int count;
    public void onCreate(Bundle b) { args = b; start(); }
    private void check(boolean v, String s) { if (!v) throw new AssertionError(s); count++; }
    private File original(Context c) { return new File(c.getCacheDir(), "afs-memory-qa-original.zip"); }
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Context c = getTargetContext(); String mode = args.getString("mode", "smoke");
            if (mode.equals("inspect")) {
                MemoryData m = MemoryStore.snapshot(c);
                StringBuilder report = new StringBuilder();
                report.append("cards=").append(m.cards().length()).append(" backup=").append(original(c).exists()).append('\n');
                for (int i = 0; i < m.decks().length(); i++) {
                    JSONObject d = m.decks().optJSONObject(i);
                    report.append(d.optString("id")).append(' ').append(d.optString("name")).append(" cards=").append(m.count(d.optString("id"))).append('\n');
                }
                result.putString("stream", report.toString());
            } else if (mode.equals("restore")) {
                File backup = original(c);
                if (!backup.exists()) throw new IllegalStateException("No QA backup");
                // Remove only the known QA deck; preserve any real edits made since seeding.
                String temporary = args.getString("deck", "");
                File marker = new File(c.getCacheDir(), "afs-memory-qa-deck.txt");
                if (temporary.isEmpty() && marker.exists()) {
                    try (InputStream in = new FileInputStream(marker)) { temporary = new String(bytes(in), "UTF-8"); }
                }
                if (temporary.isEmpty()) throw new IllegalArgumentException("Pass the QA deck id for cleanup");
                MemoryData live = MemoryStore.snapshot(c);
                JSONObject d = live.deck(temporary);
                if (d != null && !"QA temporary".equals(d.optString("name"))) throw new IllegalStateException("Not a QA deck");
                JSONArray decks = new JSONArray(), cards = new JSONArray(), logs = new JSONArray();
                for (int i = 0; i < live.decks().length(); i++) if (!temporary.equals(live.decks().optJSONObject(i).optString("id"))) decks.put(live.decks().optJSONObject(i));
                for (int i = 0; i < live.cards().length(); i++) if (!temporary.equals(live.cards().optJSONObject(i).optString("deck"))) cards.put(live.cards().optJSONObject(i));
                JSONArray allLogs = live.db.optJSONArray("logs");
                for (int i = 0; i < allLogs.length(); i++) if (!temporary.equals(allLogs.optJSONObject(i).optString("deck"))) logs.put(allLogs.optJSONObject(i));
                MemoryData.put(live.db, "decks", decks); MemoryData.put(live.db, "cards", cards); MemoryData.put(live.db, "logs", logs);
                JSONObject flash = live.db.optJSONObject("flashcards");
                if (flash != null) {
                    java.util.Set<String> keptCards = new java.util.HashSet<>();
                    for (int i = 0; i < cards.length(); i++) keptCards.add(cards.optJSONObject(i).optString("id"));
                    JSONObject cfg = flash.optJSONObject("settings");
                    if (cfg != null && cfg.optJSONArray("subjects") != null) {
                        JSONArray keptSubjects = new JSONArray(), all = cfg.optJSONArray("subjects");
                        for (int i = 0; i < all.length(); i++) if (!temporary.equals(all.optString(i))) keptSubjects.put(all.optString(i));
                        if (keptSubjects.length() == 0) flash.remove("settings");
                        else MemoryData.put(cfg, "subjects", keptSubjects);
                    }
                    for (String[] pair : new String[][]{{"session", "ids"}, {"day", "seen"}}) {
                        JSONObject part = flash.optJSONObject(pair[0]); if (part == null) continue;
                        JSONArray originalIds = part.optJSONArray(pair[1]), keptIds = new JSONArray();
                        if (originalIds != null) for (int i = 0; i < originalIds.length(); i++) if (keptCards.contains(originalIds.optString(i))) keptIds.put(originalIds.optString(i));
                        MemoryData.put(part, pair[1], keptIds);
                    }
                    if (!keptCards.contains(flash.optString("sequenceAfter"))) flash.remove("sequenceAfter");
                }
                JSONObject plans = live.db.optJSONObject("plans"); java.util.Iterator<String> dates = plans.keys();
                while (dates.hasNext()) plans.optJSONObject(dates.next()).remove(temporary);
                ByteArrayOutputStream clean = new ByteArrayOutputStream(); java.util.Set<String> media = new java.util.HashSet<>();
                try (ZipOutputStream zip = new ZipOutputStream(clean)) {
                    zip.putNextEntry(new ZipEntry("memory.json")); zip.write(live.db.toString().getBytes("UTF-8")); zip.closeEntry();
                    for (int i = 0; i < cards.length(); i++) {
                        String name = cards.optJSONObject(i).optString("image"); if (name.isEmpty() || !media.add(name)) continue;
                        zip.putNextEntry(new ZipEntry("media/" + name));
                        try (InputStream in = new FileInputStream(MemoryStore.media(c, name))) { zip.write(bytes(in)); }
                        zip.closeEntry();
                    }
                }
                MemoryStore.restoreBackup(c, new ByteArrayInputStream(clean.toByteArray()));
                backup.delete(); marker.delete(); result.putString("stream", "REMOVED QA deck; other memory edits preserved\n");
            } else if (mode.equals("seed")) {
                File backup = original(c);
                if (backup.exists()) throw new IllegalStateException("Existing QA backup must be restored first");
                try (OutputStream out = new FileOutputStream(backup)) { MemoryStore.exportBackup(c, out); }
                String id = seed(c);
                try (OutputStream out = new FileOutputStream(new File(c.getCacheDir(), "afs-memory-qa-deck.txt"))) { out.write(id.getBytes("UTF-8")); }
                result.putString("stream", "SEEDED temporary QA deck=" + id + "\n");
            } else {
                ByteArrayOutputStream backup = new ByteArrayOutputStream(); MemoryStore.exportBackup(c, backup);
                try { smoke(c); }
                finally { MemoryStore.restoreBackup(c, new ByteArrayInputStream(backup.toByteArray())); }
                result.putString("stream", "DEVICE MEMORY: " + count + " assertions passed; original library restored\n");
            }
            finish(-1, result);
        } catch (Throwable e) {
            StringWriter out = new StringWriter(); e.printStackTrace(new PrintWriter(out));
            result.putString("stream", out.toString()); finish(0, result);
        }
    }
    private byte[] bytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); return out.toByteArray();
    }
    private String seed(Context c) throws Exception {
        MemoryStore.addDeck(c, "QA temporary", "english");
        MemoryData m = MemoryStore.snapshot(c);
        String id = m.decks().optJSONObject(m.decks().length() - 1).optString("id");
        MemoryStore.importText(c, id, "Type the word memory\tmemory\tQA\tTemporary test card", "input");
        MemoryStore.importText(c, id, "The unit is {{N}}\t\tQA\tTemporary cloze", "cloze");
        JSONObject image = MemoryData.newCard(id, "image", "QA diagram: reveal the masked area", "Uncovered region", "QA", "Temporary image");
        MemoryData.put(image, "image", image(c));
        JSONArray rect = new JSONArray(); rect.put(.25); rect.put(.25); rect.put(.5); rect.put(.5);
        JSONArray masks = new JSONArray(); masks.put(rect); MemoryData.put(image, "masks", masks);
        MemoryStore.saveCard(c, image); MemoryStore.settings(c, id, "QA temporary", 10, 50, 3, true);
        return id;
    }
    private String image(Context c) throws Exception {
        Bitmap b = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888); b.eraseColor(0xFFFFFFFF);
        File f = new File(c.getCacheDir(), "afs-memory-qa-image.png");
        try (OutputStream out = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
        b.recycle(); String name = MemoryStore.importImage(c, Uri.fromFile(f)); f.delete(); return name;
    }
    private void smoke(Context c) throws Exception {
        int checkinCount = StudyStore.recs(c).size();
        int checkinPending = StudyStore.pendingToday(c);
        boolean checkinHasGoals = StudyStore.hasGoals(c);
        int baselineCards = MemoryStore.snapshot(c).cards().length();
        String id = seed(c); MemoryData m = MemoryStore.snapshot(c);
        check(m.count(id) == 3, "native imports");
        check(m.progress(id, System.currentTimeMillis()).target == 3, "daily plan");
        check(m.progress(id, System.currentTimeMillis()).linked, "linked plan");
        check(new File(c.getFilesDir(), "memory-v1.json").length() > 0, "atomic persistence");
        JSONObject q = m.next(id, System.currentTimeMillis());
        check(MemoryStore.rate(c, q.optString("id"), 4), "native review credit");
        check(MemoryStore.snapshot(c).progress(id, System.currentTimeMillis()).done == 1, "persisted credit");
        boolean blocked = false;
        try { StudyStore.check(c, MemoryData.GOAL_PREFIX + id, 100, StudyStore.today()); }
        catch (IllegalArgumentException e) { blocked = true; }
        check(blocked, "manual check-in cannot bypass memory");
        check(StudyStore.recs(c).size() == checkinCount, "memory review does not enter check-in history");
        check(StudyStore.pendingToday(c) == checkinPending, "memory target does not enter check-in pending count");
        check(StudyStore.hasGoals(c) == checkinHasGoals, "memory target does not create check-in goals");
        boolean leaked = false;
        for (Goal goal : StudyStore.uiGoals(c)) if (goal.id.startsWith(MemoryData.GOAL_PREFIX)) leaked = true;
        check(!leaked, "memory target does not enter swipe check-in queue");
        MemoryStore.configureFlashcards(c, new JSONArray().put(id), 2, "sequence");
        MemoryData flash = MemoryStore.flashSnapshot(c); long now = System.currentTimeMillis();
        String drawn = MemoryFlashcards.next(flash, now).optString("id");
        MemoryStore.rateFlashcard(c, drawn, 3, MemoryData.date(now));
        check(MemoryFlashcards.progress(MemoryStore.flashSnapshot(c), now).done == 1, "daily flashcard progress persists");
        check(StudyStore.recs(c).size() == checkinCount, "daily flashcards remain independent of check-ins");
        JSONObject img = null;
        for (int i = 0; i < m.cards().length(); i++) if (id.equals(m.cards().optJSONObject(i).optString("deck")) && "image".equals(m.cards().optJSONObject(i).optString("type"))) img = m.cards().optJSONObject(i);
        final JSONObject picture = img;
        check(picture != null && MemoryStore.media(c, picture.optString("image")).isFile(), "private image");
        final int[] pixels = new int[2];
        runOnMainSync(() -> {
            MemoryImageView v = new MemoryImageView(c, picture.optString("image"), picture.optJSONArray("masks"), false);
            v.layout(0, 0, 400, 200); Bitmap output = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(output)); pixels[0] = output.getPixel(200, 100);
            v.reveal(); v.draw(new Canvas(output)); pixels[1] = output.getPixel(200, 100); output.recycle();
        });
        check(pixels[0] == 0xFF2F6FED, "mask hides underlying pixel");
        check(pixels[1] == 0xFFFFFFFF, "reveal restores image pixel");
        ByteArrayOutputStream roundtrip = new ByteArrayOutputStream(); MemoryStore.exportBackup(c, roundtrip);
        int cardCount = MemoryStore.snapshot(c).cards().length();
        MemoryStore.importText(c, id, "additional\tanswer", "qa");
        MemoryStore.restoreBackup(c, new ByteArrayInputStream(roundtrip.toByteArray()));
        check(MemoryStore.snapshot(c).cards().length() == cardCount, "ZIP restores cards");
        check(MemoryStore.snapshot(c).progress(id, System.currentTimeMillis()).done == 1, "ZIP restores progress");
        String before = MemoryStore.snapshot(c).db.toString();
        ByteArrayOutputStream broken = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(broken)) { zip.putNextEntry(new ZipEntry("memory.json")); zip.write("{}".getBytes("UTF-8")); zip.closeEntry(); }
        boolean rejected = false;
        try { MemoryStore.restoreBackup(c, new ByteArrayInputStream(broken.toByteArray())); }
        catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "bad ZIP rejected");
        check(before.equals(MemoryStore.snapshot(c).db.toString()), "bad ZIP leaves live data untouched");
        boolean rowRejected = false;
        try { MemoryStore.importText(c, id, "good\tanswer\nbad row", "qa"); }
        catch (IllegalArgumentException e) { rowRejected = true; }
        check(rowRejected && MemoryStore.snapshot(c).cards().length() == cardCount, "text import is all-or-nothing");
        check(baselineCards + 3 == cardCount, "test does not remove existing cards");
    }
}
