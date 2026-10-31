package com.afs.guard;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** 备忘录的排序、检索、摘要、时间格式和 JSON 容错；不连设备也不碰偏好。 */
public final class MemoTest {

    private static int count;

    private static void check(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
        count++;
    }

    private static MemoStore.Note note(String id, String title, String body, long updated, boolean pinned) {
        return new MemoStore.Note(id, title, body, updated, pinned);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    private static String repeat(char ch, int times) {
        StringBuilder builder = new StringBuilder(times);
        for (int index = 0; index < times; index++) {
            builder.append(ch);
        }
        return builder.toString();
    }

    private static String ids(List<MemoStore.Note> notes) {
        StringBuilder builder = new StringBuilder();
        for (MemoStore.Note note : notes) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(note.id);
        }
        return builder.toString();
    }

    public static void main(String[] args) {
        /* ---------- 排序：置顶 → 更新时间倒序 → id ---------- */
        List<MemoStore.Note> notes = new ArrayList<MemoStore.Note>();
        notes.add(note("b", "Beta", "", 300, false));
        notes.add(note("a", "Alpha", "", 100, false));
        notes.add(note("c", "Pinned", "", 50, true));
        notes.add(note("d", "Tie", "", 100, false));
        List<MemoStore.Note> ordered = MemoStore.order(notes);
        check(ids(ordered).equals("c,b,a,d"), "pinned first, newest next, id breaks ties");
        check(ids(notes).equals("b,a,c,d"), "sorting never mutates the stored order");
        check(MemoStore.compare(ordered.get(0), ordered.get(1)) == -MemoStore.compare(ordered.get(1), ordered.get(0)),
                "compare is symmetric");
        List<MemoStore.Note> withNulls = new ArrayList<MemoStore.Note>();
        withNulls.add(null);
        withNulls.add(note("x", "X", "", 1, false));
        check(MemoStore.order(withNulls).size() == 1, "a null slot is skipped, not fatal");

        /* ---------- 过滤：标题 + 正文，大小写不敏感 ---------- */
        List<MemoStore.Note> all = new ArrayList<MemoStore.Note>();
        all.add(note("1", "Alpha 计划", "", 10, false));
        all.add(note("2", "杂项", "跨行正文里有 Keyword", 20, false));
        all.add(note("3", "置顶的", "正文", 30, true));
        check(MemoStore.filter(all, "").size() == 3, "blank query keeps every note");
        check(MemoStore.filter(all, "   ").size() == 3, "whitespace query is treated as blank");
        check(MemoStore.filter(all, "alpha").size() == 1, "title match ignores case");
        check(MemoStore.filter(all, "keyword").size() == 1, "body match ignores case");
        check(MemoStore.filter(all, "跨行正文").size() == 1, "body match works for Chinese text");
        check(MemoStore.filter(all, "missing").isEmpty(), "no match returns an empty list");
        check(ids(MemoStore.filter(all, "")).equals("3,2,1"), "filtered results keep the display order");

        /* ---------- 标题回退与摘要 ---------- */
        check(MemoStore.displayTitle(note("t", "  Hello  ", "", 0, false)).equals("Hello"),
                "display title trims surrounding spaces");
        check(MemoStore.displayTitle(note("t", "a\nb   c", "", 0, false)).equals("a b c"),
                "display title flattens line breaks and repeated spaces");
        check(MemoStore.displayTitle(note("t", "", "\n\n  第一行  \n第二行", 0, false)).equals("第一行"),
                "empty title falls back to the first non-blank body line");
        check(MemoStore.displayTitle(note("t", "", "", 0, false)).equals("无标题"),
                "an empty note still needs a label");
        check(MemoStore.displayTitle(null).equals("无标题"), "missing note is handled");
        check(MemoStore.displayTitle(note("t", "", repeat('长', 60), 0, false)).length() == 41,
                "long body line used as title is clipped with an ellipsis");
        check(MemoStore.excerpt("a\nb\t c  d").equals("a b c d"), "excerpt folds all whitespace");
        check(MemoStore.excerpt(null).isEmpty() && MemoStore.excerpt("   ").isEmpty(),
                "blank excerpt is empty, not a fake word");
        check(MemoStore.excerpt(repeat('x', 100)).length() == 81, "excerpt is capped with an ellipsis");

        /* ---------- 相对时间 ---------- */
        long now = at(2026, 10, 25, 15, 0);
        check(MemoStore.formatTime(now - 1000, now).equals("刚刚"), "seconds read as just now");
        check(MemoStore.formatTime(now + 5000, now).equals("刚刚"), "clock skew does not print a negative age");
        check(MemoStore.formatTime(now - 60000, now).equals("1分钟前"), "one minute");
        check(MemoStore.formatTime(now - 59 * 60000, now).equals("59分钟前"), "minutes below an hour");
        check(MemoStore.formatTime(at(2026, 10, 25, 9, 5), now).equals("今天 09:05"), "earlier today keeps the clock");
        check(MemoStore.formatTime(at(2026, 10, 24, 23, 59), now).equals("昨天 23:59"), "yesterday keeps the clock");
        check(MemoStore.formatTime(at(2026, 3, 2, 8, 0), now).equals("3月2日"), "same year drops the clock");
        check(MemoStore.formatTime(at(2025, 12, 31, 8, 0), now).equals("2025年12月31日"), "older years keep the year");

        /* ---------- JSON 往返与坏数据 ---------- */
        List<MemoStore.Note> stored = new ArrayList<MemoStore.Note>();
        stored.add(note("id-1", "标题", "正文\n第二行", 123456789L, true));
        stored.add(note("id-2", "", "只有正文", 42L, false));
        String json = MemoStore.toJson(stored);
        List<MemoStore.Note> back = MemoStore.fromJson(json);
        check(back.size() == 2, "every note survives serialization");
        check(back.get(0).id.equals("id-1") && back.get(0).title.equals("标题")
                        && back.get(0).body.equals("正文\n第二行") && back.get(0).updated == 123456789L
                        && back.get(0).pinned,
                "serialized fields round trip");
        check(MemoStore.fromJson(null).isEmpty() && MemoStore.fromJson("").isEmpty()
                        && MemoStore.fromJson("not json").isEmpty() && MemoStore.fromJson("[1,2]").isEmpty(),
                "damaged preference content degrades to an empty list");
        check(MemoStore.fromJson("[{\"id\":\"keep\",\"title\":\"T\"},7,{\"title\":\"no id\"},null]").size() == 1,
                "only entries with an id are kept");
        check(MemoStore.fromJson("[{\"id\":\"empty\"},{\"id\":\"pinnedOnly\",\"pinned\":true}]").isEmpty(),
                "entries with no content at all are dropped instead of showing an empty card");
        List<MemoStore.Note> nullable = MemoStore.fromJson(
                "[{\"id\":\"k\",\"title\":null,\"body\":\"x\",\"updated\":5,\"pinned\":true}]");
        check(nullable.size() == 1 && nullable.get(0).title.isEmpty() && nullable.get(0).pinned,
                "explicit null fields are normalised");
        check(MemoStore.fromJson("[{\"id\":\"same\",\"title\":\"A\"},{\"id\":\"same\",\"title\":\"B\"}]").size() == 1,
                "a duplicated id is read once, so deleting one card removes the note");
        List<MemoStore.Note> rescued = MemoStore.fromJson("[{\"id\":\"d\"},{\"id\":\"d\",\"title\":\"Later\"}]");
        check(rescued.size() == 1 && rescued.get(0).title.equals("Later"),
                "an empty residue does not block a later note with the same id");
        check(MemoStore.fromJson("[{\"id\":123,\"title\":\"T\"}]").isEmpty(),
                "a non-string id is not a note");
        List<MemoStore.Note> garbage = MemoStore.fromJson(
                "[{\"id\":\"g\",\"title\":{\"a\":1},\"body\":[1,2],\"updated\":\"x\",\"pinned\":\"y\"}]");
        check(garbage.isEmpty(),
                "object/array field types leave no content, so the entry is dropped");

        List<MemoStore.Note> many = new ArrayList<MemoStore.Note>();
        many.add(note("pinned", "置顶", "", 1, true));
        for (int index = 0; index < MemoStore.MAX_NOTES + 100; index++) {
            many.add(note("id" + index, "t" + index, "", index, false));
        }
        List<MemoStore.Note> capped = MemoStore.fromJson(MemoStore.toJson(many));
        check(capped.size() == MemoStore.MAX_NOTES && capped.get(0).id.equals("pinned"),
                "reading an oversized store keeps the cap and the pinned note");
        check(capped.get(1).updated == MemoStore.MAX_NOTES + 99,
                "the capped read keeps the newest notes, not the oldest");

        /* ---------- 增改删置顶 ---------- */
        List<MemoStore.Note> list = new ArrayList<MemoStore.Note>();
        check(MemoStore.add(list, "   ", "  ", 1000) == null && list.isEmpty(),
                "a blank note is never created");
        check(MemoStore.add(list, "\u3000", "\u3000", 1000) == null && list.isEmpty(),
                "full-width spaces count as blank");
        check(MemoStore.add(list, "\u00A0", "", 1000) == null && list.isEmpty(),
                "a non-breaking space counts as blank");
        check(MemoStore.add(list, "\u200B", "\uFEFF", 1000) == null && list.isEmpty(),
                "zero-width and BOM characters count as blank too");
        check(MemoStore.blank("   ", "\u3000") && MemoStore.blank("\u00A0", ""),
                "blank() treats unicode spaces as empty, like the editor does");
        check(!MemoStore.blank("a", "") && !MemoStore.blank("", "b"), "blank() still sees real text");
        MemoStore.Note created = MemoStore.add(list, "  Hello  ", "  body  ", 1000);
        check(created != null && created.title.equals("Hello") && created.body.equals("body")
                        && created.updated == 1000 && !created.pinned && !created.id.isEmpty(),
                "create trims fields and stamps the time");
        MemoStore.Note untitled = MemoStore.add(list, "", "第一行\n第二行", 2000);
        check(untitled != null && untitled.title.equals("第一行"),
                "a titleless note borrows the first body line as its stored title");
        check(MemoStore.add(list, repeat('t', 100), "", 3000).title.length() == MemoStore.MAX_TITLE,
                "overlong titles are clipped");
        check(MemoStore.add(list, "long", repeat('b', MemoStore.MAX_BODY + 50), 4000).body.length() == MemoStore.MAX_BODY,
                "overlong bodies are clipped");
        check(!MemoStore.edit(list, "missing", "a", "b", 5000), "editing an unknown id changes nothing");
        check(!MemoStore.edit(list, created.id, " ", "  ", 5000) && created.title.equals("Hello"),
                "clearing both fields keeps the stored note");
        check(!MemoStore.edit(list, created.id, "\u3000", "\u00A0", 5000) && created.title.equals("Hello"),
                "clearing to full-width or non-breaking spaces keeps the stored note");
        check(MemoStore.edit(list, created.id, "New", "Body", 5000) && created.title.equals("New")
                        && created.body.equals("Body") && created.updated == 5000,
                "edit replaces both fields and bumps the timestamp");
        check(!MemoStore.edit(list, created.id, "New", "Body", 9000) && created.updated == 5000,
                "saving unchanged content does not bump the timestamp or reorder the list");
        check(MemoStore.pin(list, created.id, true) && created.pinned, "pinning an existing note works");
        check(!MemoStore.pin(list, "missing", true), "pinning an unknown id is a no-op");
        int before = list.size();
        check(MemoStore.drop(list, created.id) && list.size() == before - 1, "delete removes exactly one note");
        check(!MemoStore.drop(list, created.id), "deleting twice is a no-op");

        /* ---------- 条数上限 ---------- */
        List<MemoStore.Note> bounded = new ArrayList<MemoStore.Note>();
        for (int index = 0; index < MemoStore.MAX_NOTES; index++) {
            MemoStore.add(bounded, "n" + index, "", index);
        }
        check(bounded.size() == MemoStore.MAX_NOTES, "the store fills up to the cap");
        boolean capacity=false;
        try{MemoStore.add(bounded,"overflow","",9999);}catch(IllegalStateException expected){capacity=true;}
        check(capacity&&bounded.size()==MemoStore.MAX_NOTES,"capacity rejects new data without evicting old notes");
        check(bounded.get(0).title.equals("n0"),"oldest note remains intact at capacity");

        List<MemoStore.Note> pinnedOnly = new ArrayList<MemoStore.Note>();
        for (int index = 0; index < MemoStore.MAX_NOTES; index++) {
            MemoStore.Note pinned = MemoStore.add(pinnedOnly, "p" + index, "", index);
            MemoStore.pin(pinnedOnly, pinned.id, true);
        }
        capacity=false;try{MemoStore.add(pinnedOnly,"one more","",9999);}catch(IllegalStateException expected){capacity=true;}
        check(capacity&&pinnedOnly.size()==MemoStore.MAX_NOTES,"pinned records are never evicted");
        List<MemoStore.Note> active=new ArrayList<>(),trash=new ArrayList<>();
        MemoStore.Note original=MemoStore.add(active,"旧记录","保留正文",1234);original.pinned=true;
        check(MemoStore.move(active,trash,original.id)&&active.isEmpty(),"delete moves into trash");
        check(trash.get(0).id.equals(original.id)&&trash.get(0).pinned&&trash.get(0).updated==1234,"trash retains all metadata");
        List<MemoStore.Note> restoredJson=MemoStore.fromJson(MemoStore.toJson(trash));
        check(MemoStore.move(restoredJson,active,original.id)&&active.get(0).body.equals("保留正文"),"restore survives serialization");
        check(!MemoStore.move(restoredJson,active,original.id),"repeated restore is harmless");
        capacity=false;try{MemoStore.move(trash,bounded,original.id);}catch(IllegalStateException expected){capacity=true;}
        check(capacity&&trash.size()==1,"failed restore leaves trash intact");
        boolean duplicate=false;try{MemoStore.move(trash,active,original.id);}catch(IllegalStateException expected){duplicate=true;}
        check(duplicate&&trash.size()==1&&active.size()==1,"duplicate ids never overwrite existing notes");

        System.out.println("Memo: " + count + " assertions passed");
    }
}
