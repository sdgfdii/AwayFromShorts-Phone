package com.afs.guard;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Native conversion parity and workbook edge cases; no Android runtime or private app data. */
public final class QuizImportTest {
    private static int checks;
    private static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    private interface Action{void run()throws Exception;}
    private static void reject(Action action,String label)throws Exception{try{action.run();}catch(IllegalArgumentException|IOException e){checks++;return;}throw new AssertionError(label);}
    private static JSONObject q(String json)throws Exception{return new JSONObject(json).getJSONArray("questions").getJSONObject(0);}
    private static String read(byte[] bytes,String name)throws Exception{return QuizImport.read(new ByteArrayInputStream(bytes),name,"高数");}
    private static String utf(String text,String name)throws Exception{return read(text.getBytes(StandardCharsets.UTF_8),name);}
    private static byte[] workbook(String sheet,String shared,boolean external)throws Exception{
        Map<String,String> parts=new LinkedHashMap<>();
        parts.put("xl/workbook.xml","<workbook xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='题库' r:id='r7'/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels","<Relationships><Relationship Id='r7' Target='worksheets/custom.xml'"+(external?" TargetMode='External'":"")+"/></Relationships>");
        parts.put("xl/worksheets/custom.xml",sheet);
        if(shared!=null)parts.put("xl/sharedStrings.xml",shared);
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(Map.Entry<String,String> e:parts.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return out.toByteArray();
    }
    private static String cell(String ref,String value){return "<c r='"+ref+"' t='inlineStr'><is><t>"+value+"</t></is></c>";}
    public static void main(String[] args)throws Exception{
        String csv="科目,题干,A,B,C,D,答案,解析\r\n线性代数,\"求 $\\det A$, 含\"\"引号\"\"\n续行\",1,2,,,B,解析";
        JSONObject row=q(read(csv.getBytes("GB18030"),"线代.csv"));
        check("线代".equals(row.getString("subject")),"subject aliases");
        check(row.getString("stem").contains("\n续行")&&row.getString("stem").contains("\"引号\""),"quoted multiline CSV");
        check("single".equals(row.getString("type"))&&"B".equals(row.getString("answer")),"inferred single");
        check(row.getJSONArray("options").length()==2,"trailing empty columns");
        check(q(utf("题干\t答案\n求值\t3","bank.tsv")).getString("answer").equals("3"),"TSV");
        String utf16="\ufeff题干\t答案\n测试\t1";
        check(q(read(utf16.getBytes("UTF-16LE"),"bank.csv")).getString("answer").equals("1"),"Excel UTF16 tab CSV");
        JSONObject numeric=q(utf("[{\"学科\":\"电路\",\"题干\":\"电阻\",\"答案\":12}]","b.json"));
        check(numeric.getString("answer").equals("12")&&numeric.getString("subject").equals("电路"),"numeric answer custom subject");
        check(q(utf("[{\"stem\":\"q\",\"type\":\"判断题\",\"answer\":false}]","b.json")).getString("answer").equals("B"),"boolean judge");
        check(q(utf("[{\"stem\":\"q\",\"options\":[1,2,3],\"answer\":[\"C\",\"A\"]}]","b.json")).getString("answer").equals("AC"),"multiple infer");
        for(String fmt:new String[]{"csv","txt"}){
            String value=read(Files.readAllBytes(Paths.get("../docs/examples/quiz-source."+fmt)),"source."+fmt);
            check(new QuizData().prepareImport(value,"高数","test").added==(fmt.equals("txt")?2:1),"desktop template "+fmt);
        }
        String plain="[题目] 求积分 $\\int x dx$\n[答案] $x^2/2+C$\n[解析] 第一行\n第二行\n---\n[科目] 线代\n[题型] 判断\n[题目] 单位矩阵可逆\n[答案] √";
        String text=QuizImport.text(plain,"","高数");
        check(new JSONObject(text).getJSONArray("questions").length()==2,"paste marked blocks");
        check(q(text).getString("analysis").contains("\n第二行"),"multiline analysis");
        String combined=QuizImport.combine(Arrays.asList(text,text));
        QuizData.ImportResult result=new QuizData().prepareImport(combined,"高数","test");
        check(result.added==2&&result.duplicates==2,"cross-file duplicates visible in preview");
        reject(()->utf("stem,A,B,C,answer\nq,1,,3,C","bad.csv"),"option gap");
        reject(()->utf("stem,题干,answer\na,b,1","bad.csv"),"alias duplicate");
        reject(()->utf("stem,answer\n\"unclosed,1","bad.csv"),"unclosed quote");
        reject(()->utf("[题目] a\n[答案] b\n[题目] c","bad.txt"),"missing separator");
        reject(()->utf("[{\"stem\":\"valid\",\"answer\":1},{\"stem\":\"invalid\"}]","bad.json"),"invalid row rejects batch");
        reject(()->utf("binary","paper.pdf"),"unsupported PDF");
        reject(()->utf("binary","old.xls"),"unsupported XLS");
        String header="<row>"+cell("A1","题干")+cell("B1","答案")+cell("C1","科目")+"</row>";
        String sheet="<worksheet><sheetData>"+header+"<row>"+cell("A2","求值 $x$")+"<c r='B2'><v>2</v></c>"+cell("C2","电路")+"</row></sheetData></worksheet>";
        JSONObject excel=q(read(workbook(sheet,null,false),"book.xlsx"));
        check(excel.getString("stem").equals("求值 $x$")&&excel.getString("subject").equals("电路"),"XLSX inline, numeric, custom relationship path");
        String shared="<sst><si><t>题干</t></si><si><t>答案</t></si><si><r><t>共享</t></r><r><t>文本</t></r></si><si><t>42</t></si></sst>";
        String sharedSheet="<worksheet><sheetData><row><c r='A1' t='s'><v>0</v></c><c r='B1' t='s'><v>1</v></c></row><row><c r='A2' t='s'><v>2</v></c><c r='B2' t='s'><v>3</v></c></row></sheetData></worksheet>";
        check(q(read(workbook(sharedSheet,shared,false),"book.xlsx")).getString("stem").equals("共享文本"),"shared rich strings");
        String sparse="<worksheet><sheetData><row>"+cell("A1","题干")+cell("B1","答案")+cell("C1","解析")+"</row><row>"+cell("A2","问题")+cell("B2","1")+"</row></sheetData></worksheet>";
        check(q(read(workbook(sparse,null,false),"book.xlsx")).getString("answer").equals("1"),"missing trailing cells");
        reject(()->read(workbook(sheet.replace("<v>2</v>","<f>1+1</f><v>2</v>"),null,false),"formula.xlsx"),"formula rejected");
        reject(()->read(workbook(sheet,null,true),"external.xlsx"),"external relationship rejected");
        reject(()->read(workbook("<!DOCTYPE worksheet [<!ENTITY x 'bad'>]>"+sheet,null,false),"dtd.xlsx"),"DTD rejected");
        reject(()->read(new byte[]{1,2,3},"bad.xlsx"),"invalid workbook");
        check(new QuizData().questions().length()==0,"conversion never modifies persistent model");
        System.out.println("Quiz import: "+checks+" assertions passed");
    }
}
