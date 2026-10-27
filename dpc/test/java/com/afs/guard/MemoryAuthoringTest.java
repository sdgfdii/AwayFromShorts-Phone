package com.afs.guard;

import org.json.JSONObject;
import java.nio.charset.Charset;
import java.nio.file.*;

public final class MemoryAuthoringTest {
    private static int count;
    private static void check(boolean ok,String label) {count++;if(!ok)throw new AssertionError(label);}
    private static void bad(Runnable r,String label) {try{r.run();}catch(IllegalArgumentException expected){count++;return;}throw new AssertionError(label);}
    private static MemoryAuthoring.Batch parse(String s,String fmt) {return MemoryAuthoring.parse(s,fmt,"","");}
    public static void main(String[] args) throws Exception {
        MemoryData original=new MemoryData();String before=original.db.toString();
        MemoryAuthoring.Batch template=parse(MemoryAuthoring.TEMPLATE,"md");check(template.size()==3,"three subject template");
        MemoryAuthoring.Result r=MemoryAuthoring.prepare(original,template);
        check(original.db.toString().equals(before),"preview never mutates original");check(r.added==3 && r.subjects==0,"reuse existing subjects");
        check(r.groups.size()==3 && r.samples.size()==3,"preview breakdown and samples");
        check(r.data.cards().optJSONObject(2).optString("type").equals("cloze"),"cloze supported");
        check(MemoryAuthoring.prepare(r.data,template).added==0,"repeat import idempotent");
        check(MemoryAuthoring.prepare(r.data,template).skipped==3,"repeat skip count");
        check(r.data.db.optJSONArray("logs").length()==0,"no review history");
        for(int i=0;i<r.data.decks().length();i++)check(!r.data.decks().optJSONObject(i).optBoolean("linked"),"no freeze opt in");
        MemoryAuthoring.Batch vocab=parse("[{\"学科\":\"词汇\",\"单词\":\"memory\",\"释义\":\"记忆\"}]","json");
        r=MemoryAuthoring.prepare(original,vocab);check(r.subjects==1 && r.added==1,"custom subject created on candidate only");
        check(r.data.cards().optJSONObject(0).optString("type").equals("input"),"word default spelling");
        check(r.data.cards().optJSONObject(0).optString("answer").equals("memory"),"word answer");
        MemoryAuthoring.Batch csv=parse("subject,type,front,answer,note\n医学,qa,\"Q, quoted\",\"A\"\"B\",\"line1\nline2\"\n","csv");
        r=MemoryAuthoring.prepare(original,csv);JSONObject card=r.data.cards().optJSONObject(0);
        check(card.optString("front").equals("Q, quoted"),"quoted comma");check(card.optString("answer").equals("A\"B"),"escaped quote");
        check(card.optString("note").equals("line1\nline2"),"preserve paragraphs in native store");
        r=MemoryAuthoring.prepare(original,parse("{\"defaults\":{\"subject\":\"医学\"},\"cards\":[{\"front\":\"Q\",\"answer\":[\"A\",\"B\"]}]}","json"));
        check(r.data.cards().optJSONObject(0).optString("answer").equals("A|B"),"alternative answers");
        check(parse("{\"subject\":\"医学\",\"front\":\"Q\",\"answer\":\"A\"}\n","jsonl").size()==1,"jsonl");
        check(parse("学科\t题目\t答案\n医学\tQ\tA\n","tsv").size()==1,"Chinese headers");
        check(MemoryAuthoring.parse("Q\tA\tBook\tNote","txt","医学","qa").size()==1,"headerless defaults");
        bad(()->parse("Q\tA","txt"),"missing subject");
        bad(()->parse("[{\"subject\":\"医学\",\"front\":\"Q\",\"answer\":\"A\",\"extra\":1}]","json"),"unknown field");
        bad(()->parse("[{\"subject\":\"医学\",\"front\":\"Q\",\"front\":\"bad\",\"answer\":\"A\"}]","json"),"duplicate JSON key");
        bad(()->parse("[{\"subject\":\"医学\",\"front\":\"Q\",\"题目\":\"bad\",\"answer\":\"A\"}]","json"),"duplicate aliases");
        bad(()->parse("[{\"subject\":\"医学\",\"front\":NaN,\"answer\":\"A\"}]","json"),"NaN");
        bad(()->parse("{'cards':[]}","json"),"strict JSON quotes on Android");
        bad(()->parse("[] garbage","json"),"trailing content");
        bad(()->parse("[1,]","json"),"trailing comma");
        bad(()->parse("["+String.join("",java.util.Collections.nCopies(66,"["))+"0]","json"),"depth guard");
        bad(()->parse("学科,题目,答案\n医学,\"bad,A","csv"),"unterminated quote");
        bad(()->parse("学科,题目,答案\n医学,\"Q\"oops,A","csv"),"trailing quote text");
        bad(()->parse("学科,题目,题目\n医学,Q,A","csv"),"duplicate header");
        bad(()->parse("学科,题目,答案\n医学,Q","csv"),"column mismatch");
        bad(()->parse("# 医学\n## {{c1::答案}}\n题型：cloze","md"),"Anki rejected");
        bad(()->parse("# 医学\n## 没有填空\n题型：cloze","md"),"cloze marker required");
        bad(()->parse("# 医学\n## Q\n题型：image\n答案：A","md"),"images rejected");
        bad(()->parse("# 医学\n## Q\n答案：A\n## Bad","md"),"last invalid row rejects all");
        check(original.db.toString().equals(before),"failed imports leave original intact");
        MemoryData trash=new MemoryData();trash.deleteDeck(trash.decks().optJSONObject(0).optString("id"),System.currentTimeMillis());
        bad(()->MemoryAuthoring.prepare(trash,template),"deleted subject requires restore");
        check(MemoryAuthoring.decode("\uFEFFhello".getBytes("UTF-8")).equals("hello"),"BOM");
        check(MemoryAuthoring.decode("中文测试".getBytes(Charset.forName("GB18030"))).equals("中文测试"),"GB18030 fallback when not UTF-8");
        MemoryAuthoring.Batch dedup=parse("# 医学\n## Q\n答案：A\n## Q\n答案：A\n备注：later","md");
        check(dedup.size()==1 && dedup.duplicates==1,"first duplicate wins");
        for(String ext:new String[]{"json","csv","tsv","md"}) {
            Path p=Paths.get("../docs/examples/memory-source."+ext);
            if(Files.exists(p))check(parse(MemoryAuthoring.decode(Files.readAllBytes(p)),ext).size()==3,"desktop template "+ext);
        }
        Path master=Paths.get("../docs/examples/memory-import-ready/cards.json");
        if(Files.exists(master))check(parse(MemoryAuthoring.decode(Files.readAllBytes(master)),"json").size()==3,"desktop cards.json");
        System.out.println("Memory authoring: "+count+" assertions passed");
    }
}
