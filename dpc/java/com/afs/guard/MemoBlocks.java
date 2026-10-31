package com.afs.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lossless newline blocks; fenced code and contiguous table rows stay together. */
public final class MemoBlocks {
    private MemoBlocks(){}
    private static final Pattern FENCE=Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    public static final class Block {
        public final int start,end;
        Block(int start,int end){this.start=start;this.end=end;}
    }
    public static List<Block> split(String body){
        List<Block> out=new ArrayList<>();int start=0,pos=0;char fence=0;int fenceLength=0;boolean table=false;
        while(pos<body.length()){
            int newline=body.indexOf('\n',pos),end=newline<0?body.length():newline+1;
            String line=body.substring(pos,newline<0?end:newline).replaceFirst("\\r$","");Matcher m=FENCE.matcher(line);
            if(fence!=0){
                if(m.matches()&&m.group(1).charAt(0)==fence&&m.group(1).length()>=fenceLength&&m.group(2).trim().isEmpty()){
                    fence=0;out.add(new Block(start,end));start=end;
                }
            }else if(m.matches()){
                if(start<pos)out.add(new Block(start,pos));start=pos;table=false;fence=m.group(1).charAt(0);fenceLength=m.group(1).length();
            }else if(line.trim().startsWith("|")&&line.trim().endsWith("|")){
                if(!table){start=pos;table=true;}
            }else{
                if(table){out.add(new Block(start,pos));table=false;}
                start=pos;out.add(new Block(start,end));start=end;
            }
            pos=end;
        }
        if(start<body.length())out.add(new Block(start,body.length()));
        return out;
    }
    public static int at(List<Block> blocks,int cursor){
        for(int i=0;i<blocks.size();i++)if(cursor<blocks.get(i).end)return i;
        return Math.max(0,blocks.size()-1);
    }
    private static String ending(String value){return value.endsWith("\r\n")?"\r\n":value.endsWith("\n")?"\n":"";}
    /** operation: up, down, copy, delete. EOF newline policy and CRLF separators are retained. */
    public static MemoTextFormat.Change edit(String body,int index,String operation){
        List<Block> blocks=split(body);
        if(index<0||index>=blocks.size())return new MemoTextFormat.Change(body,0);
        Block b=blocks.get(index);String value=body.substring(b.start,b.end),result=body;int cursor=b.start;
        if("delete".equals(operation))result=body.substring(0,b.start)+body.substring(b.end);
        else if("copy".equals(operation)){
            String separator=value.endsWith("\n")?"":"\n";
            result=body.substring(0,b.end)+separator+value+body.substring(b.end);cursor=b.end+separator.length();
        }else{
            int other="up".equals(operation)?index-1:"down".equals(operation)?index+1:-1;
            if(other<0||other>=blocks.size())return new MemoTextFormat.Change(body,b.start);
            int low=Math.min(index,other),high=Math.max(index,other);Block a=blocks.get(low),z=blocks.get(high);
            String first=body.substring(a.start,a.end),second=body.substring(z.start,z.end);
            String firstEnd=ending(first),secondEnd=ending(second);
            String movedSecond=second.substring(0,second.length()-secondEnd.length())+firstEnd;
            result=body.substring(0,a.start)+movedSecond+first.substring(0,first.length()-firstEnd.length())+secondEnd+body.substring(z.end);
            cursor=other<index?a.start:a.start+movedSecond.length();
        }
        if(result.length()>MemoStore.MAX_BODY)return new MemoTextFormat.Change(body,b.start);
        return new MemoTextFormat.Change(result,cursor);
    }
}
