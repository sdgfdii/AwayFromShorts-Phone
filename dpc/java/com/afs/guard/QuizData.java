package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/** Offline question bank, validated imports and exactly-once session grading. No Android dependencies. */
public final class QuizData {
    public static final int MAX_QUESTIONS = 5000;
    public final JSONObject db;
    public QuizData() { this("{\"version\":1,\"questions\":[],\"progress\":{},\"days\":{},\"session\":{}}"); }
    public QuizData(String json) {
        try {
            db = new JSONObject(json);
            if (db.optInt("version") != 1 || db.optJSONArray("questions") == null
                    || db.optJSONObject("progress") == null || db.optJSONObject("days") == null
                    || db.optJSONObject("session") == null) throw new IllegalArgumentException("刷题数据格式不正确");
            // Migrate the initial choices once. An explicit empty list stays empty after deletion.
            if(db.optJSONArray("subjects")==null)put(db,"subjects",new JSONArray().put("高数").put("线代"));
            if (questions().length() > MAX_QUESTIONS) throw new IllegalArgumentException("题库超过 5000 题");
        } catch (Exception e) { throw invalid(e); }
    }
    static IllegalArgumentException invalid(Exception e) {
        return e instanceof IllegalArgumentException ? (IllegalArgumentException)e
                : new IllegalArgumentException("格式错误：" + e.getMessage(), e);
    }
    static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception e) { throw invalid(e); }
    }
    public JSONArray questions() { return db.optJSONArray("questions"); }
    public JSONObject session() { return db.optJSONObject("session"); }
    public JSONObject question(String id) {
        for (int i=0; i<questions().length(); i++) {
            JSONObject q=questions().optJSONObject(i);
            if(q != null && id.equals(q.optString("id"))) return q;
        }
        return null;
    }
    public JSONObject progress(String id) {
        JSONObject p=db.optJSONObject("progress").optJSONObject(id);
        return p == null ? new JSONObject() : p;
    }
    public static String subject(String s) {
        s=s.trim();
        if (Arrays.asList("高数","高等数学","calculus","math").contains(s.toLowerCase(Locale.ROOT))) return "高数";
        if (Arrays.asList("线代","线性代数","linear","linear algebra").contains(s.toLowerCase(Locale.ROOT))) return "线代";
        if(s.isEmpty() || s.length()>40 || s.matches(".*[\\p{Cntrl}].*")) throw new IllegalArgumentException("科目名称请填写 1 至 40 个字，不能包含换行");
        return s;
    }
    public List<String> subjects() {
        LinkedHashSet<String> names=new LinkedHashSet<>();
        JSONArray registered=db.optJSONArray("subjects");
        for(int i=0;i<registered.length();i++)names.add(subject(registered.optString(i)));
        for(int i=0;i<questions().length();i++)names.add(subject(questions().optJSONObject(i).optString("subject")));
        names.removeAll(deletedSubjects());return new ArrayList<>(names);
    }
    public List<String> deletedSubjects() {
        List<String> names=new ArrayList<>();JSONArray deleted=db.optJSONArray("deletedSubjects");
        if(deleted!=null)for(int i=0;i<deleted.length();i++)names.add(deleted.optString(i));
        return names;
    }
    public boolean deleted(String name) {return deletedSubjects().contains(name);}
    public void addSubject(String name) {
        name=subject(name);
        if(deleted(name))throw new IllegalArgumentException("回收站中已有同名学科，请先恢复");
        List<String> names=subjects();if(!names.contains(name))names.add(name);
        JSONArray saved=new JSONArray();for(String value:names)saved.put(value);put(db,"subjects",saved);
    }
    public int subjectCount(String name) {
        int count=0;for(int i=0;i<questions().length();i++)if(name.equals(questions().optJSONObject(i).optString("subject")))count++;return count;
    }
    public void deleteSubject(String name) {
        name=subject(name);if(!subjects().contains(name))throw new IllegalArgumentException("学科不存在或已删除");
        List<String> names=deletedSubjects();names.add(name);JSONArray deleted=new JSONArray();for(String value:names)deleted.put(value);put(db,"deletedSubjects",deleted);
    }
    public void restoreSubject(String name) {
        List<String> names=deletedSubjects();if(!names.remove(name))throw new IllegalArgumentException("回收站中没有这个学科");
        JSONArray deleted=new JSONArray();for(String value:names)deleted.put(value);put(db,"deletedSubjects",deleted);
        addSubject(name);
    }
    public static String typeName(String t) {
        switch(t) { case "single":return "单选题"; case "multiple":return "多选题";
            case "judge":return "判断题"; case "short":return "填空题"; default:return "解答题"; }
    }
    public static boolean objective(JSONObject q) {
        String t=q.optString("type"); return "single".equals(t)||"multiple".equals(t)||"judge".equals(t);
    }
    private static String text(JSONObject o, String k, String fallback) {
        Object value=o.opt(k); if(value==null||value==JSONObject.NULL) return fallback;
        if(!(value instanceof String)) throw new IllegalArgumentException(k+" 必须是文字");
        String s=((String)value).trim();
        if(s.length()>16000) throw new IllegalArgumentException(k+" 超过 16000 字");
        return s;
    }
    private static String hash(String s) {
        try {
            byte[] b=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(); for(byte x:b) out.append(String.format(Locale.ROOT,"%02x",x&255));
            return out.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private static String fingerprint(JSONObject q) {
        return hash(q.optString("subject")+"\u0000"+q.optString("type")+"\u0000"+q.optString("stem")
                +"\u0000"+q.optJSONArray("options")+"\u0000"+q.optString("answer"));
    }
    private static JSONObject normalize(JSONObject raw,String fallback,String bank) {
        if(raw==null) throw new IllegalArgumentException("每道题必须是 JSON 对象");
        JSONObject q=new JSONObject();
        put(q,"subject",subject(text(raw,"subject",fallback)));
        put(q,"chapter",text(raw,"chapter","未分章"));
        String t=text(raw,"type",text(raw,"qtype","solution"));
        switch(t) {
            case "单选":case "单选题":t="single";break;
            case "多选":case "多选题":t="multiple";break;
            case "判断":case "判断题":t="judge";break;
            case "填空":case "填空题":t="short";break;
            case "解答":case "解答题":case "计算题":case "证明题":t="solution";break;
        }
        if(!Arrays.asList("single","multiple","judge","short","solution").contains(t))
            throw new IllegalArgumentException("未知题型 "+t);
        put(q,"type",t); put(q,"stem",text(raw,"stem",""));
        if(q.optString("stem").isEmpty()) throw new IllegalArgumentException("题干 stem 不能为空");
        JSONArray opts=new JSONArray(); Object options=raw.opt("options");
        if("judge".equals(t)) { opts.put("正确");opts.put("错误"); }
        else if(options instanceof JSONArray) {
            JSONArray a=(JSONArray)options;
            for(int i=0;i<a.length();i++) {
                Object v=a.opt(i); if(!(v instanceof String)||((String)v).trim().isEmpty()||((String)v).length()>16000)
                    throw new IllegalArgumentException("选项须是非空文字");
                opts.put(((String)v).trim());
            }
        } else if(options instanceof JSONObject) {
            JSONObject o=(JSONObject)options;
            boolean gap=false;
            for(char c='A';c<='H';c++) {
                if(o.has(""+c)) {
                    String value=text(o,""+c,"");
                    if(gap||value.isEmpty())throw new IllegalArgumentException("选项不能留空或跳号");
                    opts.put(value);
                }else gap=true;
            }
        } else if(options!=null&&options!=JSONObject.NULL) throw new IllegalArgumentException("options 请用文字数组或 A/B/C 对象");
        put(q,"options",opts);
        String answer;
        if(raw.opt("answer") instanceof JSONArray) {
            JSONArray a=raw.optJSONArray("answer"); StringBuilder b=new StringBuilder();
            for(int i=0;i<a.length();i++) b.append(a.optString(i)); answer=b.toString();
        } else answer=text(raw,"answer","");
        if(answer.isEmpty()) throw new IllegalArgumentException("答案 answer 不能为空");
        if(objective(q)) {
            if(opts.length()<2||opts.length()>8) throw new IllegalArgumentException("选择题需要 2 至 8 个选项");
            if("judge".equals(t)) {
                if(Arrays.asList("正确","对","true","TRUE","T").contains(answer)) answer="A";
                else if(Arrays.asList("错误","错","false","FALSE","F").contains(answer)) answer="B";
            }
            answer=letters(answer);
            if(answer.isEmpty()||(!"multiple".equals(t)&&answer.length()!=1)) throw new IllegalArgumentException("答案请填选项字母");
            for(char c:answer.toCharArray()) if(c<'A'||c>='A'+opts.length()) throw new IllegalArgumentException("答案超出选项范围");
        }
        put(q,"answer",answer);put(q,"analysis",text(raw,"analysis",text(raw,"explanation","")));
        put(q,"source",text(raw,"source",""));put(q,"bank",text(raw,"bank",bank));
        String id=text(raw,"id","");
        if(id.isEmpty()) id="q-"+fingerprint(q);
        if(id.length()>128||!id.matches("[\\p{L}\\p{N}_.:-]+")) throw new IllegalArgumentException("id 只能使用文字、数字、下划线、点、冒号和短横线，最多 128 字");
        put(q,"id",id);return q;
    }
    public static String letters(String s) {
        s=s.toUpperCase(Locale.ROOT).replaceAll("[\\s,，、;；]","");
        TreeSet<Character> set=new TreeSet<>();
        for(char c:s.toCharArray()) { if(c<'A'||c>'H'||!set.add(c)) throw new IllegalArgumentException("选项字母格式不正确"); }
        StringBuilder b=new StringBuilder();for(char c:set)b.append(c);return b.toString();
    }
    public static final class ImportResult {
        public final QuizData candidate; public final int added,duplicates;
        ImportResult(QuizData d,int a,int n) {candidate=d;added=a;duplicates=n;}
    }
    /** Complete validation and deduplication happen on a copy, so invalid rows never partly import. */
    public ImportResult prepareImport(String content,String fallback,String name) {
        try {
            String s=content.replaceFirst("^\\uFEFF","").trim();
            JSONArray rows; String bank=name;
            if(s.startsWith("[")) rows=new JSONArray(s);
            else if(s.startsWith("{")) {
                JSONObject root=new JSONObject(s);rows=root.optJSONArray("questions");
                if(rows==null) throw new IllegalArgumentException("JSON 需要 questions 数组");
                fallback=text(root,"subject",fallback);bank=text(root,"name",name);
            } else rows=csv(s);
            if(rows.length()==0||rows.length()>MAX_QUESTIONS) throw new IllegalArgumentException("一次请导入 1 至 5000 题");
            QuizData next=new QuizData(db.toString());
            Map<String,String> ids=new HashMap<>();Set<String> fingerprints=new HashSet<>();
            for(int i=0;i<questions().length();i++) {JSONObject q=questions().optJSONObject(i);ids.put(q.optString("id"),fingerprint(q));fingerprints.add(fingerprint(q));}
            int added=0,dup=0;
            for(int i=0;i<rows.length();i++) {
                JSONObject q;
                try {q=normalize(rows.optJSONObject(i),fallback,bank);}
                catch(IllegalArgumentException e) {throw new IllegalArgumentException("第 "+(i+1)+" 题："+e.getMessage());}
                if(next.deleted(q.optString("subject")))throw new IllegalArgumentException("学科「"+q.optString("subject")+"」在回收站，请先恢复或更换科目名称");
                String fp=fingerprint(q),id=q.optString("id");
                if(ids.containsKey(id)&&!ids.get(id).equals(fp)) throw new IllegalArgumentException("第 "+(i+1)+" 题 id 与已有不同题目冲突，请更换 id");
                if(fingerprints.contains(fp)) {dup++;continue;}
                if(next.questions().length()>=MAX_QUESTIONS) throw new IllegalArgumentException("题库总量最多 5000 题");
                next.questions().put(q);ids.put(id,fp);fingerprints.add(fp);added++;
            }
            return new ImportResult(next,added,dup);
        } catch(Exception e) {throw invalid(e);}
    }
    /** RFC4180-style comma CSV with escaped quotes, CRLF and multiline cells. */
    private static JSONArray csv(String s) {
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();
        boolean quoted=false,closed=false;
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(quoted) {
                if(c=='"') {if(i+1<s.length()&&s.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}
                else cell.append(c);
            } else if(c=='"') {if(cell.length()!=0||closed)throw new IllegalArgumentException("CSV 引号位置错误");quoted=true;}
            else if(c==',') {row.add(cell.toString());cell.setLength(0);closed=false;}
            else if(c=='\r'||c=='\n') {
                if(c=='\r'&&i+1<s.length()&&s.charAt(i+1)=='\n')i++;
                row.add(cell.toString());rows.add(row);row=new ArrayList<>();cell.setLength(0);closed=false;
            } else {if(closed)throw new IllegalArgumentException("CSV 结束引号后应为逗号或换行");cell.append(c);}
        }
        if(quoted)throw new IllegalArgumentException("CSV 引号未闭合");
        row.add(cell.toString());rows.add(row);
        if(rows.isEmpty())throw new IllegalArgumentException("CSV 为空");
        List<String> header=rows.remove(0);Set<String> seen=new HashSet<>();
        for(int i=0;i<header.size();i++) {
            String h=header.get(i).trim();
            switch(h){case "科目":h="subject";break;case "章节":h="chapter";break;case "题型":h="type";break;
                case "题目":case "题干":h="stem";break;case "答案":h="answer";break;case "解析":h="analysis";break;case "来源":h="source";break;}
            if(!seen.add(h))throw new IllegalArgumentException("CSV 表头重复："+h);header.set(i,h);
        }
        if(!header.contains("stem")||!header.contains("answer")) throw new IllegalArgumentException("CSV 首行需有 stem/题干 和 answer/答案 表头");
        int nextOption='A';
        for(String h:header)if(h.matches("[A-H]")&&h.charAt(0)!=nextOption++)throw new IllegalArgumentException("CSV 选项表头必须从 A 连续排列");
        JSONArray out=new JSONArray();int line=1;
        for(List<String> r:rows) {
            line++;boolean blank=true;for(String v:r)if(!v.trim().isEmpty())blank=false;if(blank)continue;
            if(r.size()!=header.size())throw new IllegalArgumentException("CSV 第 "+line+" 条记录列数不一致");
            JSONObject q=new JSONObject();JSONArray opts=new JSONArray();boolean optionGap=false;
            for(int j=0;j<header.size();j++) {
                String k=header.get(j),v=r.get(j);
                if(k.matches("[A-H]")) {if(!v.trim().isEmpty()){if(optionGap)throw new IllegalArgumentException("CSV 选项不能留空或跳号");opts.put(v);}else optionGap=true;}
                else if(!v.trim().isEmpty())put(q,k,v);
            }
            put(q,"options",opts);out.put(q);
        }
        return out;
    }
    public List<JSONObject> filter(String subject,String chapter,String mode,String search) {
        List<JSONObject> list=new ArrayList<>();String needle=search.toLowerCase(Locale.ROOT);Set<String> hidden=new HashSet<>(deletedSubjects());
        for(int i=0;i<questions().length();i++) {
            JSONObject q=questions().optJSONObject(i),p=progress(q.optString("id"));
            if(hidden.contains(q.optString("subject")))continue;
            if(!subject.isEmpty()&&!subject.equals(q.optString("subject")))continue;
            if(!chapter.isEmpty()&&!chapter.equals(q.optString("chapter")))continue;
            if("wrong".equals(mode)&&!p.optBoolean("wrong"))continue;
            if("star".equals(mode)&&!p.optBoolean("star"))continue;
            if("new".equals(mode)&&p.optInt("attempts")>0)continue;
            if(!needle.isEmpty()&&!(q.optString("stem")+q.optString("chapter")+q.optString("source")).toLowerCase(Locale.ROOT).contains(needle))continue;
            list.add(q);
        }
        return list;
    }
    public void star(String id) {
        if(question(id)==null)throw new IllegalArgumentException("题目不存在");
        JSONObject p=progress(id);put(p,"star",!p.optBoolean("star"));put(db.optJSONObject("progress"),id,p);
    }
    public void start(List<JSONObject> list,boolean random) {
        if(list.isEmpty())throw new IllegalArgumentException("这里还没有题目");
        list=new ArrayList<>(list);if(random)Collections.shuffle(list);
        JSONArray ids=new JSONArray();for(JSONObject q:list){if(deleted(q.optString("subject")))throw new IllegalArgumentException("学科已移入回收站，请先恢复");ids.put(q.optString("id"));}
        JSONObject session=new JSONObject();put(session,"ids",ids);put(session,"index",0);put(session,"right",0);put(session,"done",0);
        put(db,"session",session);
    }
    public JSONObject current() {
        JSONArray a=session().optJSONArray("ids");int n=session().optInt("index");
        JSONObject q=a==null||n<0||n>=a.length()?null:question(a.optString(n));
        return q!=null && deleted(q.optString("subject"))?null:q;
    }
    public void draft(String selected,String input) {put(session(),"selected",selected);put(session(),"input",input);}
    public void reveal() {if(current()!=null)put(session(),"revealed",true);}
    public boolean grade(Boolean self,long now) {
        JSONObject s=session(),q=current();
        if(q==null||s.optBoolean("graded"))throw new IllegalArgumentException("本题已经记录，请进入下一题");
        boolean right;
        if(objective(q)) {
            String selected=letters(s.optString("selected"));
            if(selected.isEmpty())throw new IllegalArgumentException("请先选择答案");
            if(!"multiple".equals(q.optString("type"))&&selected.length()!=1)throw new IllegalArgumentException("请只选择一个答案");
            for(char c:selected.toCharArray()) if(c>='A'+q.optJSONArray("options").length())throw new IllegalArgumentException("选项超出范围");
            right=selected.equals(q.optString("answer"));
        } else {
            if(self==null||!s.optBoolean("revealed"))throw new IllegalArgumentException("请先查看答案，再自行核对");
            right=self;
        }
        String id=q.optString("id");JSONObject p=progress(id);
        put(p,"attempts",p.optInt("attempts")+1);put(p,"correct",p.optInt("correct")+(right?1:0));
        put(p,"wrong",!right);put(p,"last",now);put(db.optJSONObject("progress"),id,p);
        String day=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(now));
        JSONObject days=db.optJSONObject("days"),d=days.optJSONObject(day);if(d==null)d=new JSONObject();
        put(d,"attempts",d.optInt("attempts")+1);put(d,"correct",d.optInt("correct")+(right?1:0));put(days,day,d);
        put(s,"graded",true);put(s,"revealed",true);put(s,"correct",right);
        put(s,"done",s.optInt("done")+1);put(s,"right",s.optInt("right")+(right?1:0));return right;
    }
    public void next() {
        JSONObject s=session();if(current()==null)return;
        if(!s.optBoolean("graded"))throw new IllegalArgumentException("请先完成本题");
        put(s,"index",s.optInt("index")+1);
        for(String key:Arrays.asList("selected","input","revealed","graded","correct"))s.remove(key);
    }
    public int[] stats(String dayPrefix) {
        int attempts=0,right=0;JSONObject days=db.optJSONObject("days");Iterator<String> it=days.keys();
        while(it.hasNext()){String d=it.next();if(d.startsWith(dayPrefix)){JSONObject a=days.optJSONObject(d);attempts+=a.optInt("attempts");right+=a.optInt("correct");}}
        return new int[]{attempts,right};
    }
}
