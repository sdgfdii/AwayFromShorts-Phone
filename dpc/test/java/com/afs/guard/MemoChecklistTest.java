package com.afs.guard;

import java.util.List;

public final class MemoChecklistTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void eq(String expected,String actual,String label){check(expected.equals(actual),label+" expected="+expected+" actual="+actual);}
    public static void main(String[] args){
        String mixed="正文\n- [ ] 第一项\n- [X] 第二项\n  * [x] 子项\n+ [ ] 另一项";
        List<MemoChecklist.Item> items=MemoChecklist.items(mixed);
        check(items.size()==4,"task markers, indentation and alternate bullets");
        check(!items.get(0).checked&&items.get(1).checked&&items.get(2).checked,"checked state");
        eq("第一项",mixed.substring(items.get(0).content,items.get(0).end),"content bounds");
        String done=MemoChecklist.toggle(mixed,items.get(0).marker).body;
        eq(mixed.replace("第一项","第一项").replace("- [ ] 第一项","- [x] 第一项"),done,"toggle only selected marker");
        eq(mixed,MemoChecklist.toggle(done,items.get(0).marker).body,"toggle restores original order and content");
        eq(mixed,MemoChecklist.toggle(mixed,0).body,"ordinary paragraph unaffected");
        check(MemoChecklist.items("```md\n- [ ] code\n```\n- [ ] real").size()==1,"ignore fenced code");
        check(MemoChecklist.items("~~~~\n- [x] code\n~~~\n- [ ] still code\n~~~~\n- [ ] real").size()==1,"fence closing length respected");
        check(MemoChecklist.items("something - [ ] inline\n- [ ]no space").isEmpty(),"ignore inline and malformed markers");
        eq("- [ ] ",MemoChecklist.convert("",0,0).body,"empty note creates task");
        eq("- [ ] abc\ndef",MemoChecklist.convert("abc\ndef",1,1).body,"convert current paragraph");
        eq("- [ ] abc\n- [ ] def",MemoChecklist.convert("abc\ndef",0,7).body,"convert selection");
        eq("- [ ] abc\ndef",MemoChecklist.convert("abc\ndef",0,4).body,"terminal newline excludes following paragraph");
        eq("- [ ] abc\n- [x] def",MemoChecklist.convert("abc\n- [x] def",0,13).body,"mixed selection retains existing completion");
        eq("abc\ndef",MemoChecklist.convert("- [ ] abc\n- [x] def",0,19).body,"all-task selection removes boxes");
        eq("  abc",MemoChecklist.convert("  - [ ] abc",5,5).body,"remove marker preserves indentation");
        check(MemoChecklist.convert("abc",3,3).cursor==9,"cursor follows added marker");
        check(MemoChecklist.convert("- [ ] abc",3,3).cursor==0,"cursor within removed prefix clamps to text");
        MemoChecklist.Change enter=MemoChecklist.enter("- [ ] abc",9,"\n");
        eq("- [ ] abc\n- [ ] ",enter.body,"return continues unchecked checklist");
        check(enter.cursor==16,"return cursor after new marker");
        eq("- [x] abc\n- [ ] ",MemoChecklist.enter("- [x] abc",9,"\n").body,"completed item continues with unchecked item");
        eq("- [ ] ab\n- [ ] c",MemoChecklist.enter("- [ ] abc",8,"\n").body,"split current item");
        eq("- [ ] abc\n",MemoChecklist.enter("- [ ] abc\n- [ ] ",16,"\n").body,"empty task return exits checklist");
        eq("- [ ] abc\n\n尾段",MemoChecklist.enter("- [ ] abc\n- [ ] \n尾段",16,"\n").body,"exit preserves following paragraph");
        eq("",MemoChecklist.enter("- [ ] ",6,"\n").body,"first empty task exits without leftover syntax");
        eq("",MemoChecklist.enter("- [ ]   ",6,"\n").body,"empty exit also removes trailing whitespace");
        eq("  - [ ] abc\n  - [ ] ",MemoChecklist.enter("  - [ ] abc",11,"\n").body,"continue indentation");
        eq("- [ ] abc\r\n- [ ] ",MemoChecklist.enter("- [ ] abc",9,"\r\n").body,"CRLF continuation");
        check(MemoChecklist.enter("plain",5,"\n")==null,"normal paragraph return unchanged");
        check(MemoChecklist.enter("- [ ] abc",9,"\ntext")==null,"paste never automatically rewritten");
        check(MemoChecklist.enter("```\n- [ ] abc",13,"\n")==null,"code return unchanged");
        check(MemoChecklist.items("- [x] abc\r\n- [ ] def").get(0).end==9,"CRLF excludes carriage return");
        MemoEditor.History history=new MemoEditor.History(new MemoEditor.State("title",mixed));
        history.record(new MemoEditor.State("title",done));eq(mixed,history.undo().body,"completion undo");eq(done,history.redo().body,"completion redo");
        String encoded=MemoStore.toJson(java.util.Arrays.asList(new MemoStore.Note("qa","title",done,123,true)));
        eq(done,MemoStore.fromJson(encoded).get(0).body,"existing store preserves task state");
        check(MemoEditor.preview("  - [x] 完成",0).contains("☑"),"indented preview");
        String linked=MemoEditor.preview("☐ ordinary text\n- [ ] real task",0,"afs-task:test:");
        check(linked.contains("href='afs-task:test:0'")&&!linked.contains("href='afs-task:test:1'"),"preview links identify real tasks instead of literal box text");
        System.out.println("Memo checklist: "+checks+" checks passed");
    }
}
