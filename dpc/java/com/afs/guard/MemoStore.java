package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 本机速记：标题 + 正文，可置顶、搜索、删除。
 *
 * 只落在自己的偏好里（不联网、不进打卡、不进学习数据），标题和正文都是纯文本。
 * 排序、过滤、摘要、时间格式都是不碰 Android 的纯函数，离线测试直接覆盖；
 * 带 Context 的那几个只是"读出来 → 纯函数改 → 写回去"的包装。
 */
public final class MemoStore {

    /** 上限只用来兜底，正常使用远到不了。 */
    public static final int MAX_NOTES = 500;
    public static final int MAX_BODY = 20000;
    public static final int MAX_TITLE = 80;

    private static final String SP = "afs_guard";
    private static final String KEY = "memos_v1";
    private static final String TRASH = "memos_trash_v1";
    private static final int EXCERPT_LENGTH = 80;
    private static final int FALLBACK_TITLE = 40;
    private static final String UNTITLED = "无标题";

    public static final class Note {
        public String id;
        public String title;
        public String body;
        public long updated;
        public boolean pinned;
        public long remindAt,remindedAt;

        public Note(String id, String title, String body, long updated, boolean pinned) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
            this.updated = updated;
            this.pinned = pinned;
        }

        public Note copy() {
            Note copy=new Note(id,title,body,updated,pinned);copy.remindAt=remindAt;copy.remindedAt=remindedAt;return copy;
        }
    }

    private MemoStore() {
    }

    /* ==================== 纯函数：排序 / 过滤 / 展示 ==================== */

    /** 置顶优先 → 更新时间倒序 → id 升序（同一毫秒内也稳定，列表不会自己抖）。 */
    public static int compare(Note a, Note b) {
        if (a.pinned != b.pinned) {
            return a.pinned ? -1 : 1;
        }
        if (a.updated != b.updated) {
            return a.updated > b.updated ? -1 : 1;
        }
        String left = a.id == null ? "" : a.id;
        String right = b.id == null ? "" : b.id;
        return left.compareTo(right);
    }

    public static List<Note> order(List<Note> notes) {
        List<Note> copy = new ArrayList<Note>(notes.size());
        for (Note note : notes) {
            if (note != null) {
                copy.add(note);
            }
        }
        Collections.sort(copy, new java.util.Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                return MemoStore.compare(a, b);
            }
        });
        return copy;
    }

    /** 标题和正文都参与匹配；空白查询等于不过滤。结果沿用 {@link #order} 的顺序。 */
    public static List<Note> filter(List<Note> notes, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Note> out = new ArrayList<Note>();
        for (Note note : notes) {
            if (note == null) {
                continue;
            }
            if (!needle.isEmpty()) {
                String title = note.title == null ? "" : note.title.toLowerCase(Locale.ROOT);
                String body = note.body == null ? "" : note.body.toLowerCase(Locale.ROOT);
                if (!title.contains(needle) && !body.contains(needle)) {
                    continue;
                }
            }
            out.add(note);
        }
        return order(out);
    }

    /** 标题为空时退回正文第一个非空行；再空就是「无标题」。 */
    public static String displayTitle(Note note) {
        if (note == null) {
            return UNTITLED;
        }
        String title = oneLine(note.title);
        if (!title.isEmpty()) {
            return clip(title, MAX_TITLE);
        }
        String body = note.body == null ? "" : note.body;
        for (String line : body.split("\r?\n")) {
            String text = oneLine(line);
            if (!text.isEmpty()) {
                return clip(text, FALLBACK_TITLE);
            }
        }
        return UNTITLED;
    }

    /** 列表里那一行摘要：换行/制表/连续空格折叠成一个空格，最多 80 字。 */
    public static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        return clip(oneLine(body), EXCERPT_LENGTH);
    }

    /**
     * 备忘录用的是相对时间：刚写完不说「0分钟前」；隔天要能一眼看出是昨天还是更早，
     * 所以今天/昨天带时分，同年只带月日，跨年才带年份。
     */
    public static String formatTime(long millis, long now) {
        long delta = now - millis;
        if (delta < 60000L) {          // 也覆盖时钟偏差导致的"未来"时间
            return T.s("刚刚");
        }
        long minutes = delta / 60000L;
        if (minutes < 60L) {
            return minutes + T.s("分钟前");
        }
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);
        clearClock(today);
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis(millis);
        clearClock(when);
        int noteYear = when.get(Calendar.YEAR);
        int noteMonth = when.get(Calendar.MONTH) + 1;
        int noteDay = when.get(Calendar.DAY_OF_MONTH);
        String clock = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(millis));
        if (today.equals(when)) {
            return T.s("今天 ") + clock;
        }
        when.add(Calendar.DAY_OF_MONTH, 1);
        if (today.equals(when)) {
            return T.s("昨天 ") + clock;
        }
        // 年月日交给格式化串：拼「月」「日」会在英文下变成 "10 23"，
        // 而这两个字本身又会被别处的日期格式当成虚词翻成空串。
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat(
                today.get(Calendar.YEAR) == noteYear ? T.s("M月d日") : T.s("yyyy年M月d日"), T.locale());
        return day.format(new Date(millis));
    }

    /* ==================== 纯函数：JSON ==================== */

    public static String toJson(List<Note> notes) {
        JSONArray array = new JSONArray();
        try {
            for (Note note : notes) {
                if (note == null) {
                    continue;
                }
                JSONObject object = new JSONObject();
                object.put("id", note.id == null ? "" : note.id);
                object.put("title", note.title == null ? "" : note.title);
                object.put("body", note.body == null ? "" : note.body);
                object.put("updated", note.updated);
                object.put("pinned", note.pinned);
                object.put("remindAt",note.remindAt);object.put("remindedAt",note.remindedAt);
                array.put(object);
            }
        } catch (Exception ignored) {
            // 偏好里写坏一条不值得让整个列表消失，能写多少写多少
        }
        return array.toString();
    }

    /** 坏数据不抛异常：读不出来的条目跳过，剩余照常显示。 */
    public static List<Note> fromJson(String json) {
        List<Note> out = new ArrayList<Note>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) {
                    continue;
                }
                String id = textOf(object, "id", 64);
                // 重复 id 会让「删除一条」只删掉一条、另一条还留在列表里，读的时候就去重。
                // 注意：只有真正留下的条目才登记 id —— 前面的空残渣不该把后面同 id 的好条目挡掉。
                if (id.isEmpty() || seen.contains(id)) {
                    continue;
                }
                String title = textOf(object, "title", MAX_TITLE);
                String body = textOf(object, "body", MAX_BODY);
                // 两条都是空的残渣不留空卡片（正常写入不可能产生这种条目）
                if (title.isEmpty() && body.isEmpty()) {
                    continue;
                }
                seen.add(id);
                Note note=new Note(id, title, body,
                        object.optLong("updated", 0L),
                        object.optBoolean("pinned", false));
                note.remindAt=Math.max(0,object.optLong("remindAt",0));note.remindedAt=Math.max(0,object.optLong("remindedAt",0));out.add(note);
            }
        } catch (Exception ignored) {
            // 整段坏掉就当没有备忘录，页面还能正常新建
        }
        if (out.size() > MAX_NOTES) {
            // 手改偏好等异常情况下也要守住上限：留优先级最高的那些
            out = new ArrayList<Note>(order(out).subList(0, MAX_NOTES));
        }
        return out;
    }

    /* ==================== 纯函数：增改删置顶 ==================== */

    /**
     * 只有空白（含中文输入法的全角空格、不换行空格）就是空内容。
     * 编辑页和存储层共用这一条判断，避免"界面以为有内容、存储层以为空"的两套标准。
     */
    public static boolean blank(String title, String body) {
        return cleanText(title, MAX_TITLE).isEmpty() && cleanText(body, MAX_BODY).isEmpty();
    }

    /**
     * 新建一条。标题正文都空 → 不写，返回 null；标题空时用正文首行当标题
     * （列表里不能出现一整排「无标题」，那还不如让正文自己说话）。
     */
    public static Note add(List<Note> notes, String title, String body, long now) {
        String cleanTitle = cleanText(title, MAX_TITLE);
        String cleanBody = cleanText(body, MAX_BODY);
        if (blank(cleanTitle, cleanBody)) {
            return null;
        }
        if (notes.size() >= MAX_NOTES) throw new IllegalStateException(T.s("备忘录已满，请先导出或整理已有记录"));
        String storedTitle = cleanTitle.isEmpty()
                ? displayTitle(new Note("", "", cleanBody, now, false))
                : cleanTitle;
        Note note = new Note(UUID.randomUUID().toString(), storedTitle, cleanBody, now, false);
        notes.add(note);
        return note;
    }

    /** 改标题/正文，顺手刷新时间。清空成空条目时不覆盖原内容；内容没变也不动时间。 */
    public static boolean edit(List<Note> notes, String id, String title, String body, long now) {
        Note note = find(notes, id);
        if (note == null) {
            return false;
        }
        String cleanTitle = cleanText(title, MAX_TITLE);
        String cleanBody = cleanText(body, MAX_BODY);
        if (blank(cleanTitle, cleanBody)) {
            return false;
        }
        String storedTitle = cleanTitle.isEmpty()
                ? displayTitle(new Note("", "", cleanBody, now, false))
                : cleanTitle;
        // 打开看一眼就返回的情况很常见：内容没变就别刷新时间，否则它会"跳到顶部显示刚刚"
        if (storedTitle.equals(note.title) && cleanBody.equals(note.body)) {
            return false;
        }
        note.title = storedTitle;
        note.body = cleanBody;
        note.updated = now;
        return true;
    }

    public static boolean drop(List<Note> notes, String id) {
        int index = indexOf(notes, id);
        if (index < 0) {
            return false;
        }
        notes.remove(index);
        return true;
    }

    public static boolean pin(List<Note> notes, String id, boolean pinned) {
        Note note = find(notes, id);
        if (note == null) {
            return false;
        }
        note.pinned = pinned;
        return true;
    }

    /* ==================== 持久化包装 ==================== */

    public static synchronized List<Note> all(Context context) {
        return order(load(context));
    }

    public static synchronized List<Note> search(Context context, String query) {
        return filter(load(context), query);
    }

    public static synchronized int count(Context context) {
        return load(context).size();
    }

    public static synchronized Note create(Context context, String title, String body) {
        return create(context, title, body, System.currentTimeMillis());
    }

    static synchronized Note create(Context context, String title, String body, long now) {
        List<Note> notes = load(context);
        Note note = add(notes, title, body, now);
        if (note != null) {
            save(context, notes);
        }
        return note;
    }

    public static synchronized boolean update(Context context, String id, String title, String body) {
        List<Note> notes = load(context);
        boolean changed = edit(notes, id, title, body, System.currentTimeMillis());
        if (changed) {
            save(context, notes);
        }
        return changed;
    }

    /** Persist content and reminder together before scheduling any side effect. */
    public static synchronized Note saveDraft(Context context,String id,String title,String body,long remindAt){
        List<Note> notes=load(context);Note note;
        if(id==null){note=add(notes,title,body,System.currentTimeMillis());if(note==null)return null;}
        else{note=find(notes,id);if(note==null)throw new IllegalStateException(T.s("这条备忘录已删除，请先复制当前内容"));edit(notes,id,title,body,System.currentTimeMillis());}
        if(note.remindAt!=remindAt){note.remindAt=Math.max(0,remindAt);note.remindedAt=0;}
        if(!context.getSharedPreferences(SP,0).edit().putString(KEY,toJson(notes)).commit())throw new IllegalStateException(T.s("保存失败，请保留当前内容后重试"));
        MemoReminder.schedule(context,note);return note.copy();
    }
    public static boolean reminderDue(Note note,long expected,long now){return note!=null&&expected>0&&note.remindAt==expected&&note.remindedAt!=expected&&now>=expected;}
    public static synchronized Note claimReminder(Context context,String id,long expected,long now){
        List<Note> notes=load(context);Note note=find(notes,id);if(!reminderDue(note,expected,now))return null;
        note.remindedAt=expected;
        if(!context.getSharedPreferences(SP,0).edit().putString(KEY,toJson(notes)).commit())return null;
        return note.copy();
    }

    /** Move whole records, retaining id, pin and timestamps. Validate before mutating either list. */
    public static boolean move(List<Note> from,List<Note> to,String id) {
        Note note=find(from,id);if(note==null)return false;
        if(find(to,id)!=null)throw new IllegalStateException(T.s("存在同编号记录，未覆盖任何内容"));
        if(to.size()>=MAX_NOTES)throw new IllegalStateException(T.s("目标列表已满，请先导出或整理记录"));
        to.add(note.copy());drop(from,id);return true;
    }
    public static synchronized List<Note> trash(Context context) {
        return order(fromJson(context.getSharedPreferences(SP,0).getString(TRASH,"[]")));
    }
    public static synchronized boolean remove(Context context, String id) { return transfer(context,id,false); }
    public static synchronized boolean restore(Context context, String id) { return transfer(context,id,true); }
    private static boolean transfer(Context context,String id,boolean restore) {
        List<Note> active=load(context),deleted=trash(context);
        boolean changed=restore?move(deleted,active,id):move(active,deleted,id);
        if(changed&&!context.getSharedPreferences(SP,0).edit().putString(KEY,toJson(active)).putString(TRASH,toJson(deleted)).commit())
            throw new IllegalStateException(T.s("保存失败，请保留当前内容后重试"));
        if(changed){if(restore){Note note=find(active,id);if(note!=null)MemoReminder.schedule(context,note);}else MemoReminder.cancel(context,id);}
        return changed;
    }
    public static synchronized void erase(Context context,String id) {
        List<Note> deleted=trash(context);if(drop(deleted,id))
            if(!context.getSharedPreferences(SP,0).edit().putString(TRASH,toJson(deleted)).commit())
                throw new IllegalStateException(T.s("删除未保存，请重试"));
    }

    public static synchronized boolean setPinned(Context context, String id, boolean pinned) {
        List<Note> notes = load(context);
        boolean changed = pin(notes, id, pinned);
        if (changed) {
            save(context, notes);
        }
        return changed;
    }

    /* ==================== 内部 ==================== */

    private static List<Note> load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(SP, Context.MODE_PRIVATE);
        return fromJson(preferences.getString(KEY, "[]"));
    }

    private static void save(Context context, List<Note> notes) {
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putString(KEY, toJson(notes)).apply();
    }

    private static Note find(List<Note> notes, String id) {
        int index = indexOf(notes, id);
        return index < 0 ? null : notes.get(index);
    }

    private static int indexOf(List<Note> notes, String id) {
        if (id == null) {
            return -1;
        }
        for (int index = 0; index < notes.size(); index++) {
            Note note = notes.get(index);
            if (note != null && id.equals(note.id)) {
                return index;
            }
        }
        return -1;
    }

    private static String textOf(JSONObject object, String key, int max) {
        Object value = object.opt(key);
        if (!(value instanceof String)) {
            return "";   // 类型不对当没写：别把 {"a":1} 这种残渣当标题显示
        }
        return cleanText((String) value, max);
    }

    private static String cleanText(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = trimBlank(value);
        if (text.length() > max) {
            text = text.substring(0, max);
        }
        return text;
    }

    /**
     * 中文输入法里的全角空格、不换行空格都算空白：只打了几个空格就返回，
     * 不该留下一条「无标题」的空备忘录。{@link String#trim()} 只认 ASCII 空白，不够。
     */
    private static String trimBlank(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isBlank(value.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isBlank(char ch) {
        // 零宽空格/连接符/BOM 不是 Unicode 空白，但粘进输入框同样"看不见"；
        // 键盘打不出来，只在粘贴时出现，一起当空内容处理才不会留幽灵条目。
        return Character.isWhitespace(ch) || Character.isSpaceChar(ch)
                || ch == '\u200B' || ch == '\u200C' || ch == '\u200D'
                || ch == '\u2060' || ch == '\uFEFF';
    }

    /** 折叠所有空白（含换行、制表、全角空格）为单个半角空格。 */
    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean space = false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            boolean blank = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u3000';
            if (blank) {
                space = out.length() > 0;
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    private static void clearClock(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }
}
