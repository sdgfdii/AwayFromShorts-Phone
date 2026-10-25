package com.afs.guard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Private atomic JSON store; no network, no external storage permission. */
public final class MemoryStore {
    private static MemoryData cache;
    private static String cachePath;
    private static String error = "";
    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getFilesDir(), "memory-v1.json")); }
    private static MemoryData data(Context c) {
        String path = c.getFilesDir().getAbsolutePath();
        if (cache != null && path.equals(cachePath)) return cache;
        cachePath = path;
        AtomicFile f = file(c);
        if (!f.getBaseFile().exists() && !new File(f.getBaseFile() + ".bak").exists()) {
            cache = new MemoryData(); error = ""; return cache;
        }
        try (InputStream in = f.openRead()) {
            cache = new MemoryData(new String(read(in, 64 * 1024 * 1024), "UTF-8")); error = "";
        } catch (Exception e) {
            error = "记忆库读取失败，已保留原文件。请恢复备份：" + e.getMessage();
            throw new IllegalStateException(error, e);
        }
        return cache;
    }
    private static void save(Context c) {
        FileOutputStream out = null;
        AtomicFile f = file(c);
        try {
            byte[] bytes = cache.db.toString().getBytes("UTF-8");
            if (bytes.length > 64 * 1024 * 1024) throw new IllegalStateException("记忆库超过 64MB，请先导出备份");
            out = f.startWrite(); out.write(bytes); f.finishWrite(out);
        } catch (Exception e) {
            if (out != null) f.failWrite(out);
            cache = null;
            throw new IllegalStateException("保存失败，未确认本次学习进度", e);
        }
        StudyGate.invalidate(c);
    }
    private static void plans(Context c, long now) {
        MemoryData m = data(c);
        JSONObject day = m.db.optJSONObject("plans").optJSONObject(MemoryData.date(now));
        boolean changed = false;
        for (int i = 0; i < m.decks().length(); i++) {
            String id = m.decks().optJSONObject(i).optString("id");
            if(m.deleted(id))continue;
            if (day == null || day.optJSONObject(id) == null) { m.plan(id, now); changed = true; }
        }
        if (changed) save(c);
    }
    public static synchronized String error() { return error; }
    public static synchronized MemoryData snapshot(Context c) {
        plans(c, System.currentTimeMillis()); return new MemoryData(data(c).db.toString());
    }
    public static synchronized MemoryData flashSnapshot(Context c) {
        long now = System.currentTimeMillis(); MemoryData m = data(c);
        String before = m.db.optJSONObject("flashcards") == null ? "" : m.db.optJSONObject("flashcards").toString();
        MemoryFlashcards.session(m, now);
        if (!before.equals(m.db.optJSONObject("flashcards").toString())) save(c);
        return new MemoryData(m.db.toString());
    }
    public static synchronized void configureFlashcards(Context c, JSONArray subjects, int count, String mode) {
        MemoryData candidate = new MemoryData(data(c).db.toString());
        MemoryFlashcards.configure(candidate, subjects, count, mode, System.currentTimeMillis());
        cache = candidate; save(c);
    }
    public static synchronized void rateFlashcard(Context c, String id, int rating, String date) {
        data(c).rateFlashcard(id, rating, System.currentTimeMillis(), date); save(c);
    }
    public static synchronized int pending(Context c) {
        try { long now = System.currentTimeMillis(); plans(c, now); return data(c).pending(now); }
        catch (RuntimeException e) { return 0; } // Unreadable memory data must not lock the phone.
    }
    public static synchronized boolean hasTargets(Context c) {
        try { long now = System.currentTimeMillis(); plans(c, now); return data(c).hasTargets(now); }
        catch (RuntimeException e) { return false; }
    }
    public static synchronized void settings(Context c, String id, String name, int nl, int rl, int target, boolean linked) {
        data(c).settings(id, name, nl, rl, target, linked); save(c);
    }
    public static synchronized void addDeck(Context c, String name, String subject) {
        if (name.trim().isEmpty()) throw new IllegalArgumentException("请输入卡组名称");
        data(c).addDeck(name.trim(), subject); save(c);
    }
    public static synchronized void deleteDeck(Context c,String id) {data(c).deleteDeck(id,System.currentTimeMillis());save(c);}
    public static synchronized void restoreDeck(Context c,String id) {data(c).restoreDeck(id);save(c);}
    public static synchronized void saveCard(Context c, JSONObject card) { data(c).saveCard(card); save(c); }
    public static synchronized boolean rate(Context c, String id, int rating) {
        boolean credit = data(c).rate(id, rating, System.currentTimeMillis()); save(c); return credit;
    }
    public static File media(Context c, String name) {
        if (!name.matches("[a-zA-Z0-9_-]+\\.jpg")) throw new IllegalArgumentException("图片文件名无效");
        return new File(new File(c.getFilesDir(), "memory-media"), name);
    }
    /** Decode sampled, then re-encode locally: bounded memory and no retained URI dependency. */
    public static String importImage(Context c, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("无法读取图片");
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / opts.inSampleSize > 1800) opts.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, opts); }
        if (bitmap == null) throw new IllegalArgumentException("图片解码失败");
        String name = MemoryData.id() + ".jpg"; File target = media(c, name); target.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw new IllegalStateException("图片保存失败");
        } finally { bitmap.recycle(); }
        return name;
    }
    public static synchronized int importText(Context c, String deck, String text, String type) {
        // Full preflight on a candidate. One malformed row never produces a partial import.
        MemoryData candidate = new MemoryData(data(c).db.toString());
        Set<String> existing = new HashSet<>();
        for (int i = 0; i < candidate.cards().length(); i++) {
            JSONObject old = candidate.cards().optJSONObject(i);
            if (deck.equals(old.optString("deck"))) existing.add(old.optString("type") + "\u0000" + old.optString("front") + "\u0000" + old.optString("answer"));
        }
        int n = 0, line = 0;
        for (String row : text.replace("\r", "").split("\n")) {
            line++; if (row.trim().isEmpty()) continue;
            String[] cols = row.split("\t", -1);
            if (cols.length < 2 || cols.length > 4) throw new IllegalArgumentException("第 " + line + " 行：用 Tab 分隔题目、答案、来源、备注");
            JSONObject card = MemoryData.newCard(deck, type, cols[0], cols[1], cols.length > 2 ? cols[2] : "", cols.length > 3 ? cols[3] : "");
            String key = type + "\u0000" + card.optString("front") + "\u0000" + card.optString("answer");
            if (existing.add(key)) { candidate.saveCard(card); n++; }
            if (n > 10000) throw new IllegalArgumentException("单次最多导入 10000 张");
        }
        if (n == 0) throw new IllegalArgumentException("没有可导入的新卡片（空内容或完全重复）");
        cache = candidate; save(c); return n;
    }
    public static synchronized void exportBackup(Context c, OutputStream output) throws Exception {
        MemoryData m = data(c);
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("memory.json")); zip.write(m.db.toString().getBytes("UTF-8")); zip.closeEntry();
            Set<String> names = new HashSet<>();
            for (int i = 0; i < m.cards().length(); i++) {
                String name = m.cards().optJSONObject(i).optString("image");
                if (name.isEmpty() || !names.add(name)) continue;
                zip.putNextEntry(new ZipEntry("media/" + name));
                try (InputStream in = new FileInputStream(media(c, name))) { copy(in, zip); }
                zip.closeEntry();
            }
        }
    }
    public static synchronized void restoreBackup(Context c, InputStream input) throws Exception {
        Map<String, File> images = new HashMap<>(); File stage = new File(c.getCacheDir(), "memory-restore-" + MemoryData.id());
        stage.mkdirs(); String json = null; long total = 0; Set<String> entries = new HashSet<>();
        try {
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!entries.add(name) || entries.size() > 30001) throw new IllegalArgumentException("重复或过多备份条目");
                    if (!name.equals("memory.json") && !name.matches("media/[a-zA-Z0-9_-]+\\.jpg"))
                        throw new IllegalArgumentException("备份包含不支持的路径");
                    byte[] bytes = read(zip, name.equals("memory.json") ? 64 * 1024 * 1024 : 12 * 1024 * 1024);
                    total += bytes.length;
                    if (total > 256L * 1024 * 1024) throw new IllegalArgumentException("备份解压后超过 256MB");
                    if (name.equals("memory.json")) json = new String(bytes, "UTF-8");
                    else {
                        String filename = name.substring(6);
                        BitmapFactory.Options b = new BitmapFactory.Options(); b.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, b);
                        if (b.outWidth <= 0 || b.outHeight <= 0 || Math.max(b.outWidth, b.outHeight) > 4096)
                            throw new IllegalArgumentException("备份图片损坏或尺寸过大");
                        File temp = new File(stage, filename);
                        try (OutputStream out = new FileOutputStream(temp)) { out.write(bytes); }
                        images.put(filename, temp);
                    }
                    zip.closeEntry();
                }
            }
            if (json == null) throw new IllegalArgumentException("备份缺少 memory.json");
            MemoryData candidate = new MemoryData(json);
            // New media names ensure even a failed restore cannot overwrite live images.
            Map<String, String> renamed = new HashMap<>();
            java.util.List<File> installed = new java.util.ArrayList<>();
            boolean committed = false;
            try {
                for (int i = 0; i < candidate.cards().length(); i++) {
                    JSONObject card = candidate.cards().optJSONObject(i); String old = card.optString("image");
                    if (old.isEmpty()) continue;
                    if (!images.containsKey(old)) throw new IllegalArgumentException("备份缺少图片：" + old);
                    String fresh = renamed.get(old);
                    if (fresh == null) {
                        fresh = MemoryData.id() + ".jpg"; File dest = media(c, fresh); dest.getParentFile().mkdirs();
                        try (InputStream in = new FileInputStream(images.get(old)); OutputStream out = new FileOutputStream(dest)) { copy(in, out); }
                        renamed.put(old, fresh); installed.add(dest);
                    }
                    MemoryData.put(card, "image", fresh);
                }
                MemoryData previous = cache; cache = candidate;
                try { save(c); committed = true; }
                catch (RuntimeException e) { cache = previous; throw e; }
            } finally { if (!committed) for (File f : installed) f.delete(); }
            error = "";
            pruneUnusedMedia(c, candidate);
        } finally {
            File[] files = stage.listFiles(); if (files != null) for (File f : files) f.delete(); stage.delete();
        }
    }
    /** Only after a successful commit: repeated restores must not retain orphaned image copies. */
    private static void pruneUnusedMedia(Context c, MemoryData model) {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < model.cards().length(); i++) used.add(model.cards().optJSONObject(i).optString("image"));
        File[] files = new File(c.getFilesDir(), "memory-media").listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().matches("[a-zA-Z0-9_-]+\\.jpg") && !used.contains(f.getName())) {
                try { f.delete(); } catch (SecurityException ignored) { }
            }
        }
    }
    static byte[] read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > limit) throw new IllegalArgumentException("文件内容超过大小限制");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }
}
