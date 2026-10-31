package com.afs.guard;

import java.io.*;

public final class MemoSketchTest {
    private static int checks;
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static MemoSketchActivity.Stroke stroke(float x,float y){MemoSketchActivity.Stroke stroke=new MemoSketchActivity.Stroke(0xff123456,6);stroke.points.add(x);stroke.points.add(y);return stroke;}
    private static byte[] encode(MemoSketchActivity.Draft draft)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();draft.write(out);return out.toByteArray();}
    public static void main(String[] args)throws Exception{
        MemoSketchActivity.Draft draft=new MemoSketchActivity.Draft();check(!draft.undo(),"empty undo no-op");
        draft.add(stroke(100,200));draft.add(stroke(1024,1400));check(draft.pointCount()==2,"model stores bounded document coordinates");
        byte[] bytes=encode(draft);MemoSketchActivity.Draft restored=MemoSketchActivity.Draft.read(new ByteArrayInputStream(bytes));
        check(restored.strokes.size()==2&&restored.pointCount()==2,"cache roundtrip preserves strokes");
        check(restored.strokes.get(0).color==0xff123456&&restored.strokes.get(0).width==6,"color and width survive recreation");
        check(restored.strokes.get(0).points.get(0)==100f&&restored.strokes.get(1).points.get(1)==1400f,"points survive recreation");
        check(restored.undo()&&restored.strokes.size()==1,"undo after cache restore");restored.clear();check(restored.strokes.isEmpty(),"clear all strokes");
        check(MemoSketchActivity.map(60,10,.5f,1024)==100,"touch coordinates account for centering and scale");
        check(MemoSketchActivity.map(-1,10,.5f,1024)==0,"coordinate lower clamp");
        check(MemoSketchActivity.map(9000,10,.5f,1024)==1024,"coordinate upper clamp");
        check(MemoSketchActivity.map(1,0,0,1024)==0,"zero measured viewport safe");
        boolean bad=false;try{new MemoSketchActivity.Stroke(0,Float.NaN);}catch(IllegalArgumentException e){bad=true;}check(bad,"NaN width refused");
        bad=false;try{draft.add(stroke(-1,0));}catch(IllegalArgumentException e){bad=true;}check(bad,"off-page coordinate refused");
        bad=false;try{MemoSketchActivity.Draft.read(new ByteArrayInputStream(new byte[]{1,2,3,4}));}catch(IOException e){bad=true;}check(bad,"invalid draft header refused");
        bad=false;try{MemoSketchActivity.Draft.read(new ByteArrayInputStream(java.util.Arrays.copyOf(bytes,bytes.length-1)));}catch(IOException e){bad=true;}check(bad,"truncated draft refused");
        ByteArrayOutputStream extra=new ByteArrayOutputStream();extra.write(bytes);extra.write(7);bad=false;try{MemoSketchActivity.Draft.read(new ByteArrayInputStream(extra.toByteArray()));}catch(IOException e){bad=true;}check(bad,"trailing draft bytes refused");
        ByteArrayOutputStream huge=new ByteArrayOutputStream();DataOutputStream header=new DataOutputStream(huge);header.writeInt(0x41465331);header.writeInt(Integer.MAX_VALUE);bad=false;try{MemoSketchActivity.Draft.read(new ByteArrayInputStream(huge.toByteArray()));}catch(IOException e){bad=true;}check(bad,"unbounded stroke allocation refused");
        check(MemoSketchActivity.PAGE_WIDTH<=1024&&MemoSketchActivity.PAGE_HEIGHT<=1400,"PNG memory bounded");
        System.out.println("Memo sketch: "+checks+" checks passed");
    }
}
