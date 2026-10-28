package com.afs.guard;

public final class MemoTextFormatTest {
    private static int checks;
    private static void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
    private static void eq(String expected,String actual,String label){check(expected.equals(actual),label+" expected="+expected+" actual="+actual);}
    public static void main(String[] args){
        eq("# abc",MemoTextFormat.heading("abc",0,0,1).body,"heading");
        eq("### abc",MemoTextFormat.heading("## abc",2,2,3).body,"replace heading");
        eq("abc",MemoTextFormat.heading("# abc",0,0,0).body,"normal paragraph");
        eq("## one\ntwo",MemoTextFormat.heading("one\ntwo",0,4,2).body,"terminal newline excludes next paragraph");
        eq("  ## one\r\n## two",MemoTextFormat.heading("  one\r\ntwo",0,99,2).body,"CRLF and indentation retained");
        eq("- [x] done",MemoTextFormat.heading("- [x] done",0,99,1).body,"heading does not destroy checked task");
        eq("**abc**",MemoTextFormat.inline("abc",0,3,"bold").body,"bold selection");
        eq("abc",MemoTextFormat.inline("**abc**",2,5,"bold").body,"unwrap surrounding pair");
        eq("abc",MemoTextFormat.inline("**abc**",0,7,"bold").body,"unwrap selected pair");
        eq("*abc*",MemoTextFormat.inline("abc",3,0,"italic").body,"reverse selection");
        eq("<u>abc</u>",MemoTextFormat.inline("abc",0,3,"underline").body,"underline convention");
        eq("==abc==",MemoTextFormat.inline("abc",0,3,"highlight").body,"highlight convention");
        eq("~~abc~~",MemoTextFormat.inline("abc",0,3,"strike").body,"strike convention");
        check(MemoTextFormat.inline("abc",1,1,"bold").cursor==3,"caret inside empty pair");
        eq("- [x] abc",MemoTextFormat.inline("- [x] abc",0,4,"bold").body,"task prefix guarded");
        eq("- [x] **abc**",MemoTextFormat.inline("- [x] abc",6,9,"bold").body,"task body formatting keeps completion");
        eq("1. one\n2. two",MemoTextFormat.list("one\ntwo",0,99,true).body,"ordered list");
        eq("- one\n- two",MemoTextFormat.list("1. one\n2. two",0,99,false).body,"ordered to unordered");
        eq("1. one\n- [x] done\n2. two",MemoTextFormat.list("one\n- [x] done\ntwo",0,99,true).body,"list preserves task state and numbering");
        eq("- one",MemoTextFormat.list("## one",0,6,false).body,"heading to list");
        eq("  - [x] done",MemoTextFormat.indent("- [x] done",0,99,true).body,"task indentation");
        eq("- [x] done",MemoTextFormat.indent("  - [x] done",0,99,false).body,"task outdent");
        eq("abc",MemoTextFormat.indent("abc",0,0,false).body,"top level outdent no-op");
        String max="            abc";eq(max,MemoTextFormat.indent(max,0,99,true).body,"indent limit");
        String code="```md\nabc\n```\ntext";
        eq("```md\nabc\n```\n# text",MemoTextFormat.heading(code,0,99,1).body,"skip fenced paragraphs");
        eq(code,MemoTextFormat.inline(code,6,9,"bold").body,"skip fenced inline text");
        String tilde="~~~~\nabc\n~~~\ntext\n~~~~\nend";
        eq("~~~~\nabc\n~~~\ntext\n~~~~\n# end",MemoTextFormat.heading(tilde,0,99,1).body,"closing fence length");
        StringBuilder full=new StringBuilder();for(int i=0;i<20000;i++)full.append('a');
        eq(full.toString(),MemoTextFormat.heading(full.toString(),0,0,1).body,"capacity protection");
        eq("<u>中文</u>",MemoTextFormat.previewExtras("&lt;u&gt;中文&lt;/u&gt;"),"safe underline preview");
        check(MemoTextFormat.previewExtras("&lt;script&gt;x&lt;/script&gt;").contains("&lt;script&gt;"),"arbitrary HTML remains escaped");
        check(MemoTextFormat.previewExtras("==中文==").contains("background-color"),"highlight preview");
        check(MemoTextFormat.previewExtras("&lt;u onclick=x&gt;x&lt;/u&gt;").contains("onclick=x&gt;"),"underline attributes never enabled");
        eq("```\n==example==\n```",MemoTextFormat.previewExtras("```\n==example==\n```"),"preview leaves fenced examples untouched");
        eq("==abc==\n```\ncode\n```",MemoTextFormat.inline("abc\n```\ncode\n```",0,3,"highlight").body,"selection before code remains formatable");
        boolean invalid=false;try{MemoTextFormat.heading("a",0,0,7);}catch(IllegalArgumentException e){invalid=true;}check(invalid,"invalid heading rejected");
        invalid=false;try{MemoTextFormat.inline("a",0,1,"invalid");}catch(IllegalArgumentException e){invalid=true;}check(invalid,"unknown format rejected");
        System.out.println("Memo text format: "+checks+" checks passed");
    }
}
