package com.afs.guard;

public final class MemoBlocksTest {
    private static int checks;
    private static void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
    private static void eq(String expected,String actual){check(expected.equals(actual),expected+" != "+actual);}
    public static void main(String[] args){
        check(MemoBlocks.split("").isEmpty(),"empty");
        String body="标题\n- [x] 已完成\n\n末段";
        check(MemoBlocks.split(body).size()==4,"line blocks including blank");
        eq("- [x] 已完成\n标题\n\n末段",MemoBlocks.edit(body,1,"up").body);
        eq(body,MemoBlocks.edit(MemoBlocks.edit(body,1,"up").body,0,"down").body);
        eq("标题\n\n末段",MemoBlocks.edit(body,1,"delete").body);
        eq("标题\n- [x] 已完成\n- [x] 已完成\n\n末段",MemoBlocks.edit(body,1,"copy").body);
        eq("标题\n- [x] 已完成\n\n末段\n末段",MemoBlocks.edit(body,3,"copy").body);
        eq("b\na",MemoBlocks.edit("a\nb",0,"down").body);
        eq("b\r\na",MemoBlocks.edit("a\r\nb",1,"up").body);
        eq("b\r\na\r\n",MemoBlocks.edit("a\r\nb\r\n",1,"up").body);
        eq("",MemoBlocks.edit("唯一段落",0,"delete").body);
        eq(body,MemoBlocks.edit(body,0,"up").body);eq(body,MemoBlocks.edit(body,3,"down").body);
        String code="a\n```md\n- [x] code\n```\nz";
        check(MemoBlocks.split(code).size()==3,"code atomic");
        eq("```md\n- [x] code\n```\na\nz",MemoBlocks.edit(code,1,"up").body);
        check(MemoBlocks.split("~~~\na\n~~\nb").size()==1,"unclosed fence atomic");
        check(MemoBlocks.split("a\n| A | B |\n| --- | --- |\n| x | y |\nz").size()==3,"table atomic");
        StringBuilder large=new StringBuilder();for(int i=0;i<20000;i++)large.append('a');
        eq(large.toString(),MemoBlocks.edit(large.toString(),0,"copy").body);
        check(MemoBlocks.at(MemoBlocks.split(body),body.length())==3,"EOF block");
        check(MemoBlocks.at(MemoBlocks.split(body),3)==1,"boundary picks next block");
        System.out.println("Memo blocks: "+checks+" checks passed");
    }
}
