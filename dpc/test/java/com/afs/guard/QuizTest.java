package com.afs.guard;

import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class QuizTest {
    private static int count;
    private static void check(boolean value,String message){count++;if(!value)throw new AssertionError(message);}
    private static void reject(Runnable fn,String message){try{fn.run();}catch(IllegalArgumentException e){count++;return;}throw new AssertionError(message);}
    private static JSONObject q(String id,String type,String answer){JSONObject q=new JSONObject();
        QuizData.put(q,"id",id);QuizData.put(q,"subject","高数");QuizData.put(q,"chapter","极限");QuizData.put(q,"type",type);
        QuizData.put(q,"stem","计算极限 "+id+" $\\frac{1}{2}$");QuizData.put(q,"answer",answer);
        if(type.equals("single")||type.equals("multiple")){JSONArray a=new JSONArray();a.put("一");a.put("二");a.put("三");QuizData.put(q,"options",a);}return q;}
    private static String bank(JSONObject... qs){JSONArray a=new JSONArray();for(JSONObject q:qs)a.put(q);return a.toString();}
    public static void main(String[] args)throws Exception{
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        imports();grading();starter();engineeringSubjects();subjectDeletion();
        System.out.println("Quiz: "+count+" assertions passed");
    }
    private static void subjectDeletion(){
        QuizData m=new QuizData().prepareImport(bank(q("delete-q","single","B")),"高数","x").candidate;
        m.addSubject("材料力学");m.start(m.filter("高数","","all",""),false);m.draft("B","");m.grade(null,1729500000000L);m.star("delete-q");
        String progress=m.progress("delete-q").toString(),session=m.session().toString();int total=m.stats("")[0];
        m.deleteSubject("高数");
        check(!m.subjects().contains("高数") && m.deletedSubjects().contains("高数"),"default subject deleted");
        check(m.filter("","","all","").isEmpty() && m.current()==null,"deleted questions absent from browsing and current session");
        check(m.subjectCount("高数")==1 && m.stats("")[0]==total,"trash retains questions and historical totals");
        check(progress.equals(m.progress("delete-q").toString()),"trash retains wrong and favorite history");
        QuizData reopened=new QuizData(m.db.toString());
        check(!reopened.subjects().contains("高数"),"deleted default does not respawn after restart");
        reject(()->reopened.prepareImport(bank(q("more","short","1")),"高数","x"),"import into trash rejected without invisible questions");
        reject(()->reopened.addSubject("高等数学"),"alias cannot duplicate deleted default");
        reopened.restoreSubject("高数");
        check(reopened.subjects().contains("高数") && reopened.filter("高数","","star","").size()==1,"undo restores questions and favorite");
        check(session.equals(reopened.session().toString()) && reopened.current()!=null,"undo preserves practice session");
        check(reopened.stats("")[0]==total,"undo does not inflate totals");
        List<String> active=new ArrayList<>(reopened.subjects());for(String name:active)reopened.deleteSubject(name);
        QuizData empty=new QuizData(reopened.db.toString());
        check(empty.subjects().isEmpty(),"all subjects removable without reseeding");
        empty.addSubject("电路");
        check(empty.subjects().equals(Collections.singletonList("电路")),"new custom subject does not restore removed defaults");
        empty.restoreSubject("材料力学");
        check(empty.subjects().contains("材料力学"),"empty custom subject restored after another addition");
    }
    private static void engineeringSubjects(){
        QuizData data=new QuizData();data.addSubject("  电路  ");data.addSubject("材料力学");data.addSubject("电路");
        data=new QuizData(data.db.toString());
        check(Collections.frequency(data.subjects(),"电路")==1,"empty custom subject persists without duplicates");
        JSONObject circuit=q("circuit","short","2");QuizData.put(circuit,"subject","电路");
        JSONObject signal=q("signal","short","3");QuizData.put(signal,"subject","信号与系统");
        QuizData imported=data.prepareImport(bank(circuit,signal),"材料力学","工科").candidate;
        check(imported.subjects().contains("信号与系统"),"imported subjects discovered");
        check(imported.filter("电路","","all","").size()==1,"engineering subject filter");
        check(imported.filter("材料力学","","all","").isEmpty(),"empty subject does not mix banks");
        check(imported.prepareImport(bank(circuit,signal),"电路","工科").duplicates==2,"engineering dedup");
        JSONObject missing=q("mechanics","short","4");missing.remove("subject");
        check(data.prepareImport(bank(missing),"材料力学","工科").candidate.filter("材料力学","","all","").size()==1,"custom import fallback");
        QuizData csv=data.prepareImport("subject,stem,answer\n自动控制原理,传递函数,1","电路","工科").candidate;
        check(csv.subjects().contains("自动控制原理"),"custom CSV subject");
        reject(()->QuizData.subject("  "),"blank subject rejected");
        reject(()->QuizData.subject("电路\n实验"),"multiline subject rejected");
        reject(()->QuizData.subject(String.join("",Collections.nCopies(41,"电"))),"long subject rejected");
    }
    private static void imports(){
        QuizData empty=new QuizData();String original=empty.db.toString();
        QuizData.ImportResult r=empty.prepareImport(bank(q("q1","single","B"),q("q2","multiple","C,A")),"高数","test");
        check(r.added==2&&r.duplicates==0,"adds two");check(empty.db.toString().equals(original),"preview does not mutate");
        QuizData m=r.candidate;check(m.question("q2").optString("answer").equals("AC"),"multiple normalized");
        check(m.prepareImport(bank(q("q1","single","B")),"高数","x").duplicates==1,"repeat import duplicate");
        JSONObject same=q("alternate","single","B");QuizData.put(same,"stem",m.question("q1").optString("stem"));
        check(m.prepareImport(bank(same),"高数","x").added==0,"content duplicate skips even with different id");
        JSONObject conflict=q("q1","single","A");
        reject(()->m.prepareImport(bank(conflict),"高数","x"),"id conflict");
        JSONObject noAnswer=q("bad","short","");String before=m.db.toString();
        reject(()->m.prepareImport(bank(q("new","short","1"),noAnswer),"高数","x"),"partial import rejected");
        check(before.equals(m.db.toString()),"failed import leaves model intact");
        reject(()->m.prepareImport(bank(q("bad","single","D")),"高数","x"),"out of bounds");
        reject(()->m.prepareImport(bank(q("bad","single","AB")),"高数","x"),"single multiple answer");
        reject(()->m.prepareImport(bank(q("bad","multiple","AA")),"高数","x"),"repeated letter");
        reject(()->m.prepareImport(bank(q("bad","xyz","A")),"高数","x"),"unknown type");
        JSONObject noSubject=q("linear","solution","1");noSubject.remove("subject");
        JSONObject root=new JSONObject();QuizData.put(root,"name","线代专项");QuizData.put(root,"subject","线性代数");QuizData.put(root,"questions",new JSONArray().put(noSubject));
        QuizData linear=empty.prepareImport("\uFEFF"+root,"高数","x").candidate;
        check(linear.filter("线代","","all","").size()==1,"root subject alias");
        check(linear.question("linear").optString("bank").equals("线代专项"),"root bank name");
        String csv="科目,章节,题型,题干,A,B,C,答案,解析\r\n高数,极限,单选题,\"求 \\\\lim，含逗号,和\"\"引号\"\"\n第二行\",1,2,3,B,解释";
        QuizData csvData=empty.prepareImport(csv,"高数","csv").candidate;
        JSONObject cq=csvData.questions().optJSONObject(0);
        check(cq.optString("stem").contains("\n第二行"),"multiline CSV");
        check(cq.optString("stem").contains("\"引号\""),"escaped quote CSV");
        check(cq.optString("answer").equals("B"),"CSV answer");
        check(empty.prepareImport("stem,type,A,B,C,D,answer\nq,single,1,2,,,B","高数","csv").added==1,"CSV trailing empty options allowed");
        reject(()->empty.prepareImport("stem,type,A,B,C,answer\nq,single,1,,3,C","高数","x"),"CSV option gaps rejected");
        reject(()->empty.prepareImport("stem,type,B,C,answer\nq,single,1,2,A","高数","x"),"CSV option header gaps rejected");
        reject(()->empty.prepareImport("stem,answer\n\"unclosed,1","高数","x"),"unclosed CSV quote");
        reject(()->empty.prepareImport("stem,answer\nhello,1,2","高数","x"),"bad CSV column count");
        reject(()->empty.prepareImport("stem,stem,answer\na,b,c","高数","x"),"duplicate header");
        reject(()->empty.prepareImport("[]","高数","x"),"empty import");
        reject(()->empty.prepareImport("{broken","高数","x"),"invalid JSON");
        JSONObject judge=q("judge","judge","正确");
        check(empty.prepareImport(bank(judge),"高数","x").candidate.question("judge").optString("answer").equals("A"),"judge alias");
        JSONObject malicious=q("safe","solution","$\\href{javascript:alert(1)}{x}$");QuizData.put(malicious,"stem","<script>alert(1)</script>");
        check(empty.prepareImport(bank(malicious),"高数","x").candidate.question("safe").optString("stem").startsWith("<script>"),"import keeps literal text for escaped rendering");
        reject(()->new QuizData("{}"),"invalid stored format");
    }
    private static void grading(){
        long now=1792857600000L;
        QuizData m=new QuizData().prepareImport(bank(q("single","single","B"),q("multi","multiple","AC"),q("self","solution","x")),"高数","test").candidate;
        m.start(Collections.singletonList(m.question("single")),false);
        final QuizData first=m;
        reject(()->first.grade(null,now),"empty answer");
        m.draft("A","");check(!m.grade(null,now),"wrong grade");
        check(m.progress("single").optBoolean("wrong"),"wrong queued");
        reject(()->first.grade(null,now+1),"exactly once grade");
        check(m.stats("")[0]==1,"one attempt only");
        m=new QuizData(m.db.toString());check(m.session().optBoolean("graded"),"restored graded state");
        check(m.current().optString("id").equals("single"),"session question restored");
        m.next();check(m.current()==null,"session completed");
        m.start(Collections.singletonList(m.question("single")),false);m.draft("B","");check(m.grade(null,now+1),"correct retry");
        check(!m.progress("single").optBoolean("wrong"),"correct clears active wrong");
        check(m.progress("single").optInt("attempts")==2,"history retained");
        m.star("single");check(m.filter("高数","","star","").size()==1,"favorite");
        m.star("single");check(m.filter("高数","","star","").isEmpty(),"unfavorite");
        m.start(Collections.singletonList(m.question("multi")),false);m.draft("CA","");check(m.grade(null,now+2),"unordered multiple exact match");
        m.start(Collections.singletonList(m.question("multi")),false);m.draft("A","");check(!m.grade(null,now+3),"partial multiple wrong");
        m.start(Collections.singletonList(m.question("self")),false);final QuizData self=m;
        reject(()->self.grade(true,now),"cannot self grade before reveal");
        m.draft("","my working");m.reveal();m=new QuizData(m.db.toString());
        check(m.session().optString("input").equals("my working"),"notes restored");
        check(m.session().optBoolean("revealed"),"reveal restored");
        check(m.grade(true,now+4),"self grade");
        int[] all=m.stats("");check(all[0]==5&&all[1]==3,"aggregate totals");
        String day=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(now));
        check(m.stats(day)[0]==5&&m.stats(day.substring(0,7))[0]==5,"daily monthly counters");
        check(m.filter("高数","极限","wrong","").size()==1,"wrong chapter filter");
        check(m.filter("高数","","all","self").size()==1,"search stem");
        check(m.filter("线代","","all","").isEmpty(),"subject isolation");
        check(m.filter("高数","","new","").isEmpty(),"unanswered filter");
    }
    private static void starter()throws Exception{
        String json=new String(Files.readAllBytes(Paths.get("assets/quiz/starter.json")),StandardCharsets.UTF_8);
        QuizData m=new QuizData().prepareImport(json,"高数","x").candidate;
        check(m.questions().length()==24,"24 starter");
        check(m.filter("高数","","all","").size()==12,"12 calculus");
        check(m.filter("线代","","all","").size()==12,"12 linear");
        check(m.question("afs-linear-3").optString("stem").contains("\\begin{pmatrix}"),"matrix backslashes intact");
        check(m.prepareImport(json,"高数","x").duplicates==24,"starter dedup");
        String template=new String(Files.readAllBytes(Paths.get("assets/quiz/template.json")),StandardCharsets.UTF_8);
        check(new QuizData().prepareImport(template,"高数","x").added==2,"template valid");
    }
}
