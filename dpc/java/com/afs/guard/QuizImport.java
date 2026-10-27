package com.afs.guard;

import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** On-device counterpart of scripts/import_quiz.py. Produces data, never runs scripts or commits. */
public final class QuizImport {
    public static final int SOURCE_LIMIT=16*1024*1024, OUTPUT_LIMIT=4*1024*1024;
    private static final Map<String,String> FIELDS=new HashMap<>();
    static {
        String[][] aliases={{"科目","subject"},{"学科","subject"},{"章节","chapter"},{"题型","type"},
            {"题目","stem"},{"题干","stem"},{"question","stem"},{"question_text","stem"},
            {"答案","answer"},{"正确答案","answer"},{"解析","analysis"},{"解答","analysis"},
            {"explanation","analysis"},{"来源","source"},{"题号","id"},{"编号","id"},
            {"选项","options"},{"题库","bank"},{"qtype","type"}};
        for(String[] a:aliases)FIELDS.put(a[0],a[1]);
    }
    private static void need(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
    private static String field(String s){s=s.trim().replace("\uFEFF","");if(FIELDS.containsKey(s))return FIELDS.get(s);return s.matches("[a-hA-H]")?s.toUpperCase(Locale.ROOT):s.toLowerCase(Locale.ROOT);}
    private static String scalar(Object value){
        if(value==null||value==JSONObject.NULL)return "";
        need(value instanceof String||value instanceof Number||value instanceof Boolean,"字段须为文字或数字");
        if(value instanceof Number)need(!Double.isNaN(((Number)value).doubleValue())&&!Double.isInfinite(((Number)value).doubleValue()),"数字不能是 NaN 或无穷大");
        String s=value.toString().trim();need(s.length()<=16000,"文字字段超过 16000 字");return s;
    }
    private static byte[] bytes(InputStream in,int max)throws IOException{
        if(in==null)throw new IOException("无法打开文件");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("题库源文件最多 16MB");out.write(b,0,n);}return out.toByteArray();
    }
    private static String decode(byte[] b)throws Exception{
        if(b.length>=2&&(b[0]&255)==255&&(b[1]&255)==254)return new String(b,2,b.length-2,"UTF-16LE");
        if(b.length>=2&&(b[0]&255)==254&&(b[1]&255)==255)return new String(b,2,b.length-2,"UTF-16BE");
        for(String encoding:new String[]{"UTF-8","GB18030"})try{
            return Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        }catch(CharacterCodingException ignored){}
        throw new IllegalArgumentException("无法读取文字编码，请另存为 UTF-8");
    }
    public static String read(InputStream in,String name,String subject)throws Exception{
        byte[] raw=bytes(in,SOURCE_LIMIT);String lower=name.toLowerCase(Locale.ROOT);
        try{
            if(lower.endsWith(".xlsx")||(raw.length>4&&raw[0]=='P'&&raw[1]=='K'))return pack(excel(raw),subject,name);
            need(!lower.endsWith(".xls"),"旧版 .xls 请先另存为 .xlsx 或 CSV");
            need(!lower.matches(".*\\.(pdf|doc|docx|png|jpg|jpeg)$"),"请选择 JSON、CSV、TSV、XLSX 或带标记的 TXT/Markdown 题库");
            return text(decode(raw),name,subject);
        }catch(Exception e){throw new IllegalArgumentException((name.isEmpty()?"题库":name)+"："+e.getMessage(),e);}
    }
    public static String text(String value,String name,String fallback){
        try{
            String s=value.replaceFirst("^\\uFEFF","").trim();need(!s.isEmpty(),"文件为空");
            JSONArray rows;String bank=name.isEmpty()?"导入题库":name;
            boolean tagged=s.matches("(?s)^\\[(科目|学科|章节|题型|题目|题干|subject|stem|chapter|type)\\].*");
            if(s.startsWith("[")&&!tagged)rows=new JSONArray(s);
            else if(s.startsWith("{")){
                JSONObject root=new JSONObject(s);rows=root.optJSONArray("questions");need(rows!=null,"JSON 需要 questions 数组");
                if(root.has("subject"))fallback=scalar(root.opt("subject"));
                if(root.has("name"))bank=scalar(root.opt("name"));
            }else if(name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|markdown)$")||s.startsWith("[")||s.startsWith("【")||s.matches("(?s)^(科目|题目|题干)[：:].*"))rows=marked(s);
            else {String first=s.split("\\r?\\n",2)[0];char separator=name.toLowerCase(Locale.ROOT).endsWith(".tsv")||(!first.contains(",")&&first.contains("\t"))?'\t':',';rows=table(csv(s,separator));}
            return pack(rows,fallback,bank);
        }catch(Exception e){throw QuizData.invalid(e);}
    }
    public static String combine(List<String> files){
        try{JSONArray rows=new JSONArray();for(String file:files){JSONArray a=new JSONObject(file).getJSONArray("questions");for(int i=0;i<a.length();i++){need(rows.length()<5000,"多选合并最多 5000 题");rows.put(a.getJSONObject(i));}}
            JSONObject root=new JSONObject();QuizData.put(root,"name","导入题库");QuizData.put(root,"questions",rows);return bounded(root);
        }catch(Exception e){throw QuizData.invalid(e);}
    }
    private static String pack(JSONArray rows,String fallback,String bank)throws Exception{
        need(rows.length()>0&&rows.length()<=5000,"请选择包含 1–5000 题的文件");JSONArray out=new JSONArray();
        for(int i=0;i<rows.length();i++)try{
            JSONObject raw=rows.optJSONObject(i);need(raw!=null,"每道题必须是对象");JSONObject row=new JSONObject();Iterator<String> keys=raw.keys();
            while(keys.hasNext()){String k=keys.next(),canonical=field(k);need(!row.has(canonical),"字段重复："+canonical);QuizData.put(row,canonical,raw.opt(k));}
            JSONObject q=new JSONObject();
            for(String k:new String[]{"id","chapter","stem","analysis","source"})if(row.has(k))QuizData.put(q,k,scalar(row.opt(k)));
            QuizData.put(q,"subject",QuizData.subject(row.has("subject")&&!scalar(row.opt("subject")).isEmpty()?scalar(row.opt("subject")):fallback));
            QuizData.put(q,"bank",row.has("bank")?scalar(row.opt("bank")):bank);
            Object options=row.opt("options");
            if(options instanceof String){String v=((String)options).trim();options=v.isEmpty()?null:v.startsWith("[")?new JSONArray(v):new JSONObject(v);}
            JSONArray opts=new JSONArray();
            if(options==null||options==JSONObject.NULL){boolean gap=false;for(char c='A';c<='H';c++){String v=scalar(row.opt(""+c));if(!v.isEmpty()){need(!gap,"选项须从 A 连续排列，不能跳号");opts.put(v);}else gap=true;}}
            else if(options instanceof JSONArray){JSONArray a=(JSONArray)options;for(int n=0;n<a.length();n++)opts.put(scalar(a.opt(n)));}
            else if(options instanceof JSONObject){JSONObject a=(JSONObject)options;boolean gap=false;Iterator<String> it=a.keys();while(it.hasNext())need(it.next().matches("[A-H]"),"选项对象的键须为 A–H");for(char c='A';c<='H';c++){if(a.has(""+c)){need(!gap,"选项不能跳号");opts.put(scalar(a.opt(""+c)));}else gap=true;}}
            else throw new IllegalArgumentException("options 请使用数组或 A/B/C 对象");
            String t=scalar(row.opt("type"));Object answer=row.opt("answer");
            if(t.isEmpty())t=opts.length()>0?(answerLetters(answer).length()>1?"multiple":"single"):"solution";
            if(t.matches("[a-zA-Z]+"))t=t.toLowerCase(Locale.ROOT);
            if("judge".equals(t)||"判断".equals(t)||"判断题".equals(t)){
                String v=scalar(answer).toLowerCase(Locale.ROOT);
                if(Arrays.asList("正确","对","true","t","是","√").contains(v))answer="A";
                else if(Arrays.asList("错误","错","false","f","否","×").contains(v))answer="B";
            }
            if(answer instanceof JSONArray)answer=answerLetters(answer);else answer=scalar(answer);
            QuizData.put(q,"type",t);QuizData.put(q,"options",opts);QuizData.put(q,"answer",answer);out.put(q);
        }catch(Exception e){throw new IllegalArgumentException("第 "+(i+1)+" 题："+e.getMessage(),e);}
        JSONObject root=new JSONObject();QuizData.put(root,"name",bank);QuizData.put(root,"questions",out);
        String json=bounded(root);new QuizData().prepareImport(json,fallback,bank);return json;
    }
    private static String answerLetters(Object answer){if(answer instanceof JSONArray){StringBuilder s=new StringBuilder();JSONArray a=(JSONArray)answer;for(int i=0;i<a.length();i++)s.append(scalar(a.opt(i)));return QuizData.letters(s.toString());}return QuizData.letters(scalar(answer));}
    private static String bounded(JSONObject root){String s=root.toString();need(s.getBytes(StandardCharsets.UTF_8).length<=OUTPUT_LIMIT,"转换后超过 4MB，请拆分题库");return s;}
    private static List<List<String>> csv(String s,char separator){
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(quoted){if(c=='"'){if(i+1<s.length()&&s.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}else cell.append(c);}
            else if(c=='"'){need(cell.length()==0&&!closed,"表格引号位置错误");quoted=true;}
            else if(c==separator){row.add(cell.toString());cell.setLength(0);closed=false;}
            else if(c=='\n'||c=='\r'){if(c=='\r'&&i+1<s.length()&&s.charAt(i+1)=='\n')i++;row.add(cell.toString());rows.add(row);need(rows.size()<=5002,"表格最多 5000 题");row=new ArrayList<>();cell.setLength(0);closed=false;}
            else{need(!closed,"结束引号后应为分隔符或换行");cell.append(c);}}
        need(!quoted,"表格引号未闭合");row.add(cell.toString());rows.add(row);return rows;
    }
    private static JSONArray table(List<List<String>> rows){
        need(!rows.isEmpty(),"表格为空");List<String> headers=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String h:rows.get(0)){String k=field(h);need(!k.isEmpty()&&seen.add(k),"表头为空或重复："+k);headers.add(k);}
        need(headers.contains("stem")&&headers.contains("answer"),"首行需要题干/stem 和答案/answer 表头");JSONArray out=new JSONArray();
        for(int i=1;i<rows.size();i++){List<String> row=rows.get(i);boolean empty=true;for(String v:row)if(!v.trim().isEmpty())empty=false;if(empty)continue;
            need(row.size()==headers.size(),"第 "+(i+1)+" 行列数与表头不一致");JSONObject q=new JSONObject();for(int n=0;n<headers.size();n++)if(!row.get(n).trim().isEmpty())QuizData.put(q,headers.get(n),row.get(n));out.put(q);need(out.length()<=5000,"最多 5000 题");}
        return out;
    }
    private static final Pattern MARKER=Pattern.compile("^\\s*(?:\\[([^\\]]+)\\]|【([^】]+)】|([^：:]{1,12})[：:])\\s*(.*)$");
    private static JSONArray marked(String s){
        JSONArray out=new JSONArray();JSONObject row=new JSONObject();String active=null;int line=0;
        for(String v:s.split("\\r?\\n",-1)){line++;if(v.matches("\\s*-{3,}\\s*")){if(row.length()>0)out.put(row);row=new JSONObject();active=null;continue;}
            Matcher m=MARKER.matcher(v);String key="";
            if(m.matches())for(int i=1;i<=3;i++)if(m.group(i)!=null){key=field(m.group(i));break;}
            if(m.matches()&&Arrays.asList("subject","chapter","type","stem","answer","analysis","source","id","bank").contains(key)){
                need(!row.has(key),"第 "+line+" 行重复字段；两道题之间请用 --- 分隔");QuizData.put(row,key,m.group(4));active=key;continue;}
            Matcher option=Pattern.compile("^\\s*([A-H])[.、．)）]\\s*(.+)$").matcher(v);
            if(option.matches()&&!Arrays.asList("answer","analysis","source").contains(active)){
                active=option.group(1);need(!row.has(active),"第 "+line+" 行选项重复");QuizData.put(row,active,option.group(2));
            }else if(!v.trim().isEmpty()){need(active!=null,"第 "+line+" 行需要 [题目]、[答案] 等标记");QuizData.put(row,active,row.optString(active)+"\n"+v);}
            else if(active!=null)QuizData.put(row,active,row.optString(active)+"\n");
        }if(row.length()>0)out.put(row);return out;
    }
    private static void xml(byte[] content,DefaultHandler handler)throws Exception{
        need(!new String(content,StandardCharsets.UTF_8).replace("\u0000","").contains("<!DOCTYPE"),"Excel XML 不支持 DTD 声明");
        SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);
        for(String feature:new String[]{"http://xml.org/sax/features/external-general-entities","http://xml.org/sax/features/external-parameter-entities"})try{factory.setFeature(feature,false);}catch(Exception ignored){}
        XMLReader reader=factory.newSAXParser().getXMLReader();reader.setEntityResolver((publicId,systemId)->{throw new SAXException("不支持外部 XML 实体");});reader.setContentHandler(handler);
        reader.setDTDHandler(handler);reader.setErrorHandler(handler);reader.parse(new InputSource(new ByteArrayInputStream(content)));
    }
    private static JSONArray excel(byte[] raw)throws Exception{
        Map<String,byte[]> parts=new HashMap<>();int total=0,entries=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(raw))){ZipEntry entry;byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null){need(++entries<=10000,"Excel 压缩包条目过多");String path=entry.getName();boolean keep=path.equals("xl/workbook.xml")||path.equals("xl/_rels/workbook.xml.rels")||path.equals("xl/sharedStrings.xml")||path.matches("xl/worksheets/[^/]+\\.xml");
                ByteArrayOutputStream out=keep?new ByteArrayOutputStream():null;int n;while((n=zip.read(buffer))!=-1){total+=n;need(total<=48*1024*1024,"Excel 解压后过大，请另存为 CSV");if(keep){need(out.size()+n<=16*1024*1024,"Excel 工作表过大，请拆分");out.write(buffer,0,n);}}
                if(keep){need(!parts.containsKey(path),"Excel 存在重复文件条目");parts.put(path,out.toByteArray());}}
        }
        need(parts.containsKey("xl/workbook.xml")&&parts.containsKey("xl/_rels/workbook.xml.rels"),"不是有效的 XLSX 文件");
        final String[] relation={null};xml(parts.get("xl/workbook.xml"),new DefaultHandler(){public void startElement(String u,String local,String q,Attributes a){if("sheet".equals(local)&&relation[0]==null){relation[0]=a.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");if(relation[0]==null)relation[0]=a.getValue("r:id");}}});
        final String[] target={null};xml(parts.get("xl/_rels/workbook.xml.rels"),new DefaultHandler(){public void startElement(String u,String local,String q,Attributes a){if("Relationship".equals(local)&&Objects.equals(relation[0],a.getValue("Id"))){need(!"External".equals(a.getValue("TargetMode")),"不支持外部工作表");target[0]=a.getValue("Target");}}});
        need(target[0]!=null,"找不到 Excel 的第一个工作表");String sheet=target[0].startsWith("/")?target[0].substring(1):new java.net.URI("xl/").resolve(target[0]).normalize().toString();need(sheet.matches("xl/worksheets/[^/]+\\.xml")&&parts.containsKey(sheet),"Excel 工作表路径无效");
        List<String> shared=new ArrayList<>();if(parts.containsKey("xl/sharedStrings.xml"))xml(parts.get("xl/sharedStrings.xml"),new DefaultHandler(){StringBuilder text;boolean collecting;
            public void startElement(String u,String local,String q,Attributes a){if("si".equals(local))text=new StringBuilder();if("t".equals(local))collecting=true;}
            public void characters(char[] c,int s,int n){if(collecting&&text!=null){need(text.length()+n<=16000,"Excel 单元格超过 16000 字");text.append(c,s,n);}}
            public void endElement(String u,String local,String q){if("t".equals(local))collecting=false;if("si".equals(local)){need(shared.size()<100000,"Excel 文本项目过多");shared.add(text.toString());}}});
        List<List<String>> rows=new ArrayList<>();xml(parts.get(sheet),new DefaultHandler(){List<String> row;String type;StringBuilder value;int column;boolean reading,inCell;
            public void startElement(String u,String local,String q,Attributes a){
                if("row".equals(local)){need(rows.size()<5001,"Excel 最多 5000 题");row=new ArrayList<>();}
                if("c".equals(local)){need(row!=null,"Excel 单元格缺少行");inCell=true;type=a.getValue("t");value=new StringBuilder();String ref=a.getValue("r");column=row.size();if(ref!=null){String letters=ref.replaceAll("[0-9]","");need(letters.matches("[A-Z]{1,2}"),"Excel 列编号无效");column=0;for(char c:letters.toCharArray())column=column*26+c-'A'+1;column--;}
                    need(column>=row.size()&&column<64,"Excel 列次序错误或超过 64 列");}
                if("f".equals(local))throw new IllegalArgumentException("Excel 含公式单元格，请先复制并粘贴为值");
                if(inCell&&("v".equals(local)||"t".equals(local)))reading=true;
            }
            public void characters(char[] c,int s,int n){if(reading){need(value.length()+n<=16000,"Excel 单元格超过 16000 字");value.append(c,s,n);}}
            public void endElement(String u,String local,String q){
                if("v".equals(local)||"t".equals(local))reading=false;
                if("c".equals(local)){String v=value.toString();if("s".equals(type)){int index=Integer.parseInt(v);need(index>=0&&index<shared.size(),"Excel 共享文本索引无效");v=shared.get(index);}else if("b".equals(type))v="1".equals(v)?"true":"false";else need(!"e".equals(type),"Excel 含错误值，请先修正");
                    while(row.size()<column)row.add("");row.add(v);inCell=false;}
                if("row".equals(local))rows.add(row);
            }});
        need(!rows.isEmpty(),"Excel 没有题目");int width=rows.get(0).size();for(List<String> row:rows){need(row.size()<=width,"Excel 数据超出表头列数");while(row.size()<width)row.add("");}return table(rows);
    }
}
