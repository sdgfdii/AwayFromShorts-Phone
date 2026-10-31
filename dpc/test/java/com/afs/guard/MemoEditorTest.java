package com.afs.guard;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class MemoEditorTest {
    private static int checks;
    private static void check(boolean result,String name){if(!result)throw new AssertionError(name);checks++;}
    public static void main(String[] args)throws Exception {
        MemoEditor.History h=new MemoEditor.History(new MemoEditor.State("old","body"));
        check(!h.canUndo()&&!h.canRedo(),"initial history");
        h.record(new MemoEditor.State("new","body2"));
        check(h.canUndo()&&h.undo().title.equals("old"),"undo title and body together");
        check(h.redo().body.equals("body2"),"redo both fields");
        h.undo();h.record(new MemoEditor.State("branch","third"));check(!h.canRedo(),"edit discards redo branch");
        h.record(new MemoEditor.State("branch","third"));check(h.undo().title.equals("old"),"duplicate snapshot ignored");
        for(int i=0;i<100;i++)h.record(new MemoEditor.State(""+i,"text"));
        int count=0;while(h.canUndo()){h.undo();count++;}check(count==39,"bounded history");
        String preview=MemoEditor.preview("# 标题\n**粗体**\n- [ ] 待办\n- [x] 已做",0xffffff);
        check(preview.contains("<b>粗体</b>"),"upstream bold rendering");
        check(preview.contains("#FFFFFF")&&preview.contains("☐")&&preview.contains("☑"),"theme heading and checklists");
        String html=MemoEditor.preview("<script>alert(1)</script><img src='https://x'>",0);
        check(!html.contains("<script>")&&!html.contains("<img"),"raw HTML escaped");
        check(MemoEditor.preview("one\ntwo",0).contains("one<br/>two"),"plain newlines retained");
        check(MemoEditor.readText(new ByteArrayInputStream("\uFEFF中文\nline".getBytes(StandardCharsets.UTF_8))).equals("中文\nline"),"UTF8 and BOM import");
        boolean invalid=false;try{MemoEditor.readText(new ByteArrayInputStream(new byte[]{(byte)0xff}));}catch(java.io.IOException e){invalid=true;}
        check(invalid,"reject invalid UTF8 instead of silently corrupting text");
        boolean oversized=false;try{MemoEditor.readText(new ByteArrayInputStream(new byte[MemoStore.MAX_BODY+1]));}catch(java.io.IOException e){oversized=true;}
        check(oversized,"overlong import refused");
        check(!MemoEditor.filename("a/b:c").contains("/")&&MemoEditor.filename("").equals("备忘录.md"),"safe export names");
        System.out.println("Memo editor: "+checks+" checks passed");
    }
}
