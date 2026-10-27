package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.*;
import java.util.regex.*;

/** Phone counterpart of make_memory_cards.py. Parsing and preview never mutate the live library. */
public final class MemoryAuthoring {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final String TEMPLATE = "# 英语单词\n\n## 根据释义拼写单词：记忆；回忆\n题型：input\n答案：memory\n来源：自编示例\n\n# 医学\n\n## 术语 cell 的中文名称是什么？\n答案：细胞\n\n# 工科概念\n\n## 国际单位制中，力的单位是 {{牛顿（N）}}。\n题型：cloze\n";
    private static final Map<String,String> FIELDS = new HashMap<>();
    static {
        aliases("subject", "deck", "学科", "科目", "卡组"); aliases("type", "题型", "类型");
        aliases("front", "question", "题目", "正面"); aliases("answer", "back", "答案", "背面");
        aliases("source", "来源"); aliases("note", "notes", "explanation", "备注", "解释", "解析");
        aliases("word", "单词"); aliases("meaning", "释义");
    }
    private static void aliases(String key, String... names) { FIELDS.put(key,key); for(String s:names) FIELDS.put(s,key); }
    private static void require(boolean ok, String message) { if(!ok) throw new IllegalArgumentException(message); }
    private static String field(String name) {
        String k=FIELDS.get(name.trim().replace("\uFEFF", "").toLowerCase(Locale.ROOT));
        require(k!=null,"未知字段："+name); return k;
    }
    private static String text(Object v) {
        if(v==null || v==JSONObject.NULL) return "";
        require(v instanceof String || v instanceof Number || v instanceof Boolean,"字段必须是文字或数字");
        if(v instanceof Number) require(Double.isFinite(((Number)v).doubleValue()),"数字必须有限");
        String s=v.toString().trim(); require(s.length()<=20000,"每个字段最多 20000 字");
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i); require(!(c<32 && c!='\n' && c!='\r' && c!='\t') && c!=127,"内容包含控制字符");
            if(Character.isHighSurrogate(c)) { require(i+1<s.length() && Character.isLowSurrogate(s.charAt(i+1)),"无效 Unicode 字符"); i++; }
            else require(!Character.isLowSurrogate(c),"无效 Unicode 字符");
        }
        return s;
    }
    public static String decode(byte[] bytes) {
        require(bytes.length<=MAX_BYTES,"文件最多 16MB");
        for(String charset:new String[]{"UTF-8","GB18030"}) try {
            String s=Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return s.startsWith("\uFEFF")?s.substring(1):s;
        } catch(Exception ignored) { }
        throw new IllegalArgumentException("无法解码文件，请另存为 UTF-8");
    }
    private static Map<String,Object> mapped(JSONObject object) {
        require(object!=null,"每张卡片必须是对象"); Map<String,Object> out=new LinkedHashMap<>();
        Iterator<String> keys=object.keys(); while(keys.hasNext()) { String key=keys.next(), k=field(key);
            require(!out.containsKey(k),"字段重复："+key); out.put(k,object.opt(key)); }
        return out;
    }
    private static Object json(String s) throws Exception {
        new StrictJson(s).check();
        JSONTokener t=new JSONTokener(s); Object value=t.nextValue(); require(t.nextClean()==0,"JSON 结尾有多余内容"); return value;
    }
    /** Android JSONTokener accepts duplicate keys and non-JSON syntax. Check the source first. */
    private static final class StrictJson {
        final String s; int p;
        StrictJson(String s) {this.s=s;}
        void check() {value(0);space();require(p==s.length(),"JSON 结尾有多余内容");}
        void space() {while(p<s.length() && " \r\n\t".indexOf(s.charAt(p))>=0)p++;}
        boolean take(char c) {space();if(p<s.length() && s.charAt(p)==c){p++;return true;}return false;}
        void expect(char c) {require(take(c),"JSON 位置 "+(p+1)+" 应为 "+c);}
        String string() {
            space(); int start=p; expect('"');
            while(p<s.length()) {char c=s.charAt(p++);if(c=='"') {
                try {return (String)new JSONTokener(s.substring(start,p)).nextValue();}catch(Exception e){throw new IllegalArgumentException("JSON 字符串无效");}
            }
                require(c>=32,"JSON 字符串中须转义换行");
                if(c=='\\') {require(p<s.length(),"JSON 转义未完成");char e=s.charAt(p++);
                    if(e=='u') {for(int i=0;i<4;i++){require(p<s.length() && Character.digit(s.charAt(p++),16)>=0,"JSON Unicode 转义无效");}}
                    else require("\"\\/bfnrt".indexOf(e)>=0,"JSON 转义无效"); }
            }
            throw new IllegalArgumentException("JSON 字符串未闭合");
        }
        void value(int depth) {
            require(depth<=64,"JSON 嵌套最多 64 层");space();require(p<s.length(),"JSON 内容不完整"); char c=s.charAt(p);
            if(c=='{') {p++;Set<String> keys=new HashSet<>();if(take('}'))return;
                do {String k=string();require(keys.add(k),"JSON 字段重复："+k);expect(':');value(depth+1);}while(take(','));expect('}');
            } else if(c=='[') {p++;if(take(']'))return;do {value(depth+1);}while(take(','));expect(']');}
            else if(c=='"')string();
            else {
                int start=p;while(p<s.length() && " \r\n\t,]}".indexOf(s.charAt(p))<0)p++;
                String token=s.substring(start,p);
                require(Arrays.asList("true","false","null").contains(token) || token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"),"JSON 值无效："+clip(token));
            }
        }
    }
    public static final class Batch {
        private final List<JSONObject> cards=new ArrayList<>();
        public int duplicates;
        public int size() { return cards.size(); }
    }
    public static Batch parse(String input, String format, String defaultSubject, String defaultType) {
        require(input!=null && input.getBytes(Charset.forName("UTF-8")).length<=MAX_BYTES,"内容最多 16MB");
        String s=input.startsWith("\uFEFF")?input.substring(1):input;
        Batch batch=new Batch(); Set<String> seen=new HashSet<>();
        Map<String,Object> defaults=new HashMap<>(); defaults.put("subject",defaultSubject); defaults.put("type",defaultType);
        try {
            if("json".equals(format)) {
                Object root=json(s); JSONArray rows;
                if(root instanceof JSONObject) {
                    JSONObject o=(JSONObject)root;
                    require("afs-memory-cards/v1".equals(o.optString("schema","afs-memory-cards/v1")),"不支持的制卡 schema");
                    if(o.has("defaults")) defaults.putAll(mapped(o.getJSONObject("defaults")));
                    Iterator<String> keys=o.keys(); Set<String> extra=new HashSet<>();
                    while(keys.hasNext()) { String k=keys.next(); if(Arrays.asList("schema","defaults","cards").contains(k))continue;
                        String f=field(k); require(extra.add(f) && !(o.optJSONObject("defaults")!=null && mapped(o.optJSONObject("defaults")).containsKey(f)),"默认字段重复："+k); defaults.put(f,o.opt(k)); }
                    require(Arrays.asList("subject","type","source","note").containsAll(defaults.keySet()),"默认值只支持学科、题型、来源和备注");
                    rows=o.getJSONArray("cards");
                } else { require(root instanceof JSONArray,"JSON 须包含 cards 数组"); rows=(JSONArray)root; }
                require(rows.length()<=30000,"单次最多处理 30000 张卡片");
                for(int i=0;i<rows.length();i++) add(batch,seen,mapped(rows.optJSONObject(i)),defaults,"卡片 "+(i+1));
            } else if("jsonl".equals(format)) {
                String[] lines=s.split("\r?\n",-1);
                for(int i=0;i<lines.length;i++) if(!lines[i].trim().isEmpty()) {
                    Object row=json(lines[i]); require(row instanceof JSONObject,"第 "+(i+1)+" 行须为对象");
                    add(batch,seen,mapped((JSONObject)row),defaults,"第 "+(i+1)+" 行");
                }
            } else if("md".equals(format)) markdown(s,batch,seen,defaults);
            else {
                require(Arrays.asList("csv","tsv","txt").contains(format),"请选择文件格式");
                List<List<String>> rows=table(s,"csv".equals(format)?',':'\t'); List<String> headers=null;
                int n=0; for(List<String> row:rows) { n++; if(row.size()==1 && row.get(0).trim().isEmpty())continue;
                    if(headers==null && !"txt".equals(format)) {
                        headers=new ArrayList<>(); for(String h:row) { String k=field(h); require(!headers.contains(k),"表头字段重复："+h); headers.add(k); } continue;
                    }
                    Map<String,Object> r=new LinkedHashMap<>();
                    if("txt".equals(format)) { require(row.size()>=2 && row.size()<=4,"第 "+n+" 行需 2–4 列，用 Tab 分隔");
                        String[] names={"front","answer","source","note"}; for(int i=0;i<row.size();i++)r.put(names[i],row.get(i)); }
                    else { require(row.size()==headers.size(),"第 "+n+" 行列数与表头不一致"); for(int i=0;i<row.size();i++)r.put(headers.get(i),row.get(i)); }
                    add(batch,seen,r,defaults,"第 "+n+" 行");
                }
            }
        } catch(Exception e) { throw new IllegalArgumentException("制卡失败："+e.getMessage(),e); }
        require(batch.size()>0,"没有有效卡片"); return batch;
    }
    private static void add(Batch batch, Set<String> seen, Map<String,Object> row, Map<String,Object> defaults, String location) {
        try {
            Map<String,Object> v=new HashMap<>(defaults);
            for(String k:row.keySet()) { Object x=row.get(k); if(!Arrays.asList("subject","type","source","note").contains(k) || !text(x).isEmpty())v.put(k,x); }
            String subject=text(v.get("subject")); require(!subject.isEmpty() && subject.length()<=40,"请填写 1–40 字的学科");
            for(char c:subject.toCharArray())require(c>=32 && !(c>=127 && c<=159),"学科名称不能包含控制字符");
            boolean word=v.containsKey("word") || v.containsKey("meaning");
            String type=text(v.get("type")).toLowerCase(Locale.ROOT); if(type.isEmpty())type=word?"input":"qa";
            if(Arrays.asList("问答","问答卡").contains(type))type="qa";
            if(Arrays.asList("输入","输入答案").contains(type))type="input";
            if(Arrays.asList("填空","填空背诵").contains(type))type="cloze";
            require(Arrays.asList("qa","input","cloze").contains(type),"仅支持问答、输入、填空；图片遮挡请在手机卡片编辑器制作");
            String front,answer;
            if(word) {
                require(text(v.get("front")).isEmpty() && text(v.get("answer")).isEmpty(),"单词/释义不能与题目/答案混用");
                String w=text(v.get("word")), meaning=text(v.get("meaning")); require(!w.isEmpty() && !meaning.isEmpty(),"单词与释义必须同时填写");
                require(!type.equals("cloze"),"词表只支持问答或输入卡"); front=type.equals("qa")?w:"根据释义拼写单词："+meaning; answer=type.equals("qa")?meaning:w;
            } else {
                front=text(v.get("front")); Object raw=v.get("answer");
                if(raw instanceof JSONArray) { JSONArray a=(JSONArray)raw; require(a.length()>0,"答案列表不能为空"); StringBuilder b=new StringBuilder();
                    for(int i=0;i<a.length();i++) { String x=text(a.opt(i)); require(!x.isEmpty(),"可接受答案不能为空"); if(i>0)b.append('|'); b.append(x); } answer=b.toString(); }
                else answer=text(raw);
            }
            front=text(front); answer=text(answer); require(!front.isEmpty(),"题目不能为空");
            require(!answer.isEmpty() || type.equals("cloze"),"请填写答案");
            if(type.equals("cloze")) { require(Pattern.compile("\\{\\{[^{}]+\\}\\}").matcher(front).find(),"填空请用 {{答案}}");
                require(!Pattern.compile("\\{\\{c\\d+::").matcher(front).find(),"不支持 Anki 填空语法，请使用 {{答案}}"); }
            JSONObject c=MemoryData.newCard("",type,front,answer,text(v.get("source")),text(v.get("note"))); MemoryData.put(c,"subject",subject);
            String key=new JSONArray().put(subject).put(type).put(front).put(answer).toString();
            if(seen.add(key))batch.cards.add(c); else batch.duplicates++;
            require(batch.size()+batch.duplicates<=30000,"单次最多处理 30000 张卡片");
        } catch(RuntimeException e) { throw new IllegalArgumentException(location+"："+e.getMessage(),e); }
    }
    /** RFC-style quoted cells, escaped quotes and embedded newlines; reject malformed quoting. */
    private static List<List<String>> table(String s, char delimiter) {
        List<List<String>> rows=new ArrayList<>(); List<String> row=new ArrayList<>(); StringBuilder cell=new StringBuilder();
        boolean quoted=false,closed=false;
        for(int i=0;i<s.length();i++) { char c=s.charAt(i);
            if(quoted) { if(c=='"') { if(i+1<s.length() && s.charAt(i+1)=='"') {cell.append('"');i++;} else {quoted=false;closed=true;} } else cell.append(c); }
            else if(c==delimiter || c=='\n' || c=='\r') {
                row.add(cell.toString()); cell.setLength(0); closed=false;
                if(c!=delimiter) { rows.add(row); require(rows.size()<=30001,"单次最多处理 30000 张卡片"); row=new ArrayList<>(); if(c=='\r' && i+1<s.length() && s.charAt(i+1)=='\n')i++; }
            } else if(c=='"') { require(cell.length()==0 && !closed,"引号位置不正确"); quoted=true; }
            else { require(!closed,"结束引号后只能是分隔符或换行"); cell.append(c); }
        }
        require(!quoted,"引号未闭合"); if(cell.length()>0 || !row.isEmpty() || closed) {row.add(cell.toString());rows.add(row);} return rows;
    }
    private static void markdown(String s, Batch b, Set<String> seen, Map<String,Object> defaults) {
        Map<String,Object> card=null; String subject="",last="front"; int at=0;
        String[] lines=s.split("\r?\n",-1); Pattern p=Pattern.compile("^([^\\s:：]{1,20})\\s*[:：]\\s*(.*)$");
        for(int i=0;i<lines.length;i++) { String line=lines[i];
            if(line.matches("^#\\s+.*") || line.matches("^##\\s+.*") || line.trim().equals("---")) {
                if(card!=null)add(b,seen,card,defaults,"第 "+at+" 行"); card=null; last="front";
                if(line.matches("^#\\s+.*"))subject=line.replaceFirst("^#\\s+(?:学科\\s*[:：]\\s*)?","").trim();
                else if(line.matches("^##\\s+.*")) { card=new LinkedHashMap<>(); card.put("front",line.replaceFirst("^##\\s+","").trim()); if(!subject.isEmpty())card.put("subject",subject);at=i+1; }
            } else if(line.trim().isEmpty()) {if(card!=null)card.put(last,text(card.get(last))+"\n");}
            else {require(card!=null,"第 "+(i+1)+" 行请先用 ## 题目 开始卡片"); Matcher m=p.matcher(line);
                if(m.matches()) {String k=field(m.group(1)); require(!card.containsKey(k) || (k.equals("subject") && subject.equals(card.get(k))),"第 "+(i+1)+" 行字段重复");card.put(k,m.group(2));last=k;}
                else card.put(last,text(card.get(last))+"\n"+line.trim()); }
        }
        if(card!=null)add(b,seen,card,defaults,"第 "+at+" 行");
    }
    public static final class Result {
        public final MemoryData data;
        public int added, skipped, subjects;
        public final Map<String,Integer> groups=new LinkedHashMap<>();
        public final List<String> samples=new ArrayList<>();
        private Result(MemoryData data) {this.data=data;}
        public String summary() {
            StringBuilder s=new StringBuilder("新增 "+added+" 张 · 跳过重复 "+skipped+" 张\n新建学科 "+subjects+" 个\n");
            for(Map.Entry<String,Integer> e:groups.entrySet())s.append("\n").append(e.getKey()).append("：").append(e.getValue()).append(" 张");
            if(!samples.isEmpty()) {s.append("\n\n卡片预览（前 3 张）");for(String x:samples)s.append("\n\n").append(x);}
            return s.toString();
        }
    }
    /** Copy first; all capacity, subject and model checks complete before the caller saves once. */
    public static Result prepare(MemoryData original, Batch batch) {
        MemoryData m=new MemoryData(original.db.toString()); Result r=new Result(m); r.skipped=batch.duplicates;
        Map<String,String> ids=new HashMap<>(); Set<String> deleted=new HashSet<>(), seen=new HashSet<>();
        for(int i=0;i<m.decks().length();i++) {JSONObject d=m.decks().optJSONObject(i);String id=d.optString("id"),name=d.optString("name");
            if(m.deleted(id))deleted.add(name);else { require(!ids.containsKey(name),"存在重名学科，请先在学科设置中改名："+name);ids.put(name,id); } }
        for(int i=0;i<m.cards().length();i++) {JSONObject c=m.cards().optJSONObject(i);seen.add(key(c));}
        for(JSONObject raw:batch.cards) {
            String subject=raw.optString("subject"),id=ids.get(subject); require(id!=null || !deleted.contains(subject),"回收站中已有学科「"+subject+"」，请先恢复");
            if(id==null) {id=m.addDeck(subject,subject).optString("id");ids.put(subject,id);r.subjects++;}
            JSONObject c=MemoryData.copy(raw);c.remove("subject");MemoryData.put(c,"deck",id);MemoryData.put(c,"id",MemoryData.id());
            if(!seen.add(key(c))) {r.skipped++;continue;}
            m.saveCard(c);r.added++; String type=c.optString("type"); String group=subject+" · "+(type.equals("input")?"输入答案":type.equals("cloze")?"填空":"问答");
            r.groups.put(group,r.groups.containsKey(group)?r.groups.get(group)+1:1);
            if(r.samples.size()<3) {String f=c.optString("front"),a=c.optString("answer");r.samples.add(group+"\n"+clip(f)+"\n答案："+(a.isEmpty()?"见 {{填空}}":clip(a)));}
        }
        return r;
    }
    private static String key(JSONObject c) {return new JSONArray().put(c.optString("deck")).put(c.optString("type")).put(c.optString("front")).put(c.optString("answer")).toString();}
    private static String clip(String s) {return s.length()>140?s.substring(0,140)+"…":s;}
    private MemoryAuthoring() { }
}
