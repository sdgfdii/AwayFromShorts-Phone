package com.afs.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checklist operations on the existing Markdown body; no second store or schema. */
public final class MemoChecklist {
    private MemoChecklist() { }
    private static final Pattern ITEM=Pattern.compile("^([ \\t]*)[-*+] \\[([ xX])\\] ");
    private static final Pattern FENCE=Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    public static final class Item {
        public final int start, marker, content, end;
        public final boolean checked;
        Item(int start,int marker,int content,int end,boolean checked){
            this.start=start;this.marker=marker;this.content=content;this.end=end;this.checked=checked;
        }
    }
    public static final class Change {
        public final String body;
        public final int cursor;
        Change(String body,int cursor){this.body=body;this.cursor=Math.max(0,Math.min(cursor,body.length()));}
    }
    public static List<Item> items(String body){
        List<Item> result=new ArrayList<>();char fence=0;int fenceSize=0;
        for(int start=0;start<=body.length();){
            int newline=body.indexOf('\n',start),end=newline<0?body.length():newline;
            if(end>start&&body.charAt(end-1)=='\r')end--;
            String line=body.substring(start,end);Matcher block=FENCE.matcher(line);
            if(block.matches()){
                String run=block.group(1);
                if(fence==0){fence=run.charAt(0);fenceSize=run.length();}
                else if(run.charAt(0)==fence&&run.length()>=fenceSize&&block.group(2).trim().isEmpty())fence=0;
            }else if(fence==0){
                Matcher match=ITEM.matcher(line);
                if(match.find())result.add(new Item(start,start+match.group(1).length(),start+match.end(),end,!match.group(2).equals(" ")));
            }
            if(newline<0)break;start=newline+1;
        }
        return result;
    }
    public static Item at(String body,int offset){
        for(Item item:items(body))if(offset>=item.start&&offset<=item.end)return item;
        return null;
    }
    public static Change toggle(String body,int offset){
        Item item=at(body,offset);if(item==null)return new Change(body,offset);
        int state=item.marker+3;
        return new Change(body.substring(0,state)+(item.checked?" ":"x")+body.substring(state+1),offset);
    }
    /** Convert selected paragraphs; pressing again on an all-checklist selection removes markers. */
    public static Change convert(String body,int anchor,int focus){
        int low=Math.max(0,Math.min(body.length(),Math.min(anchor,focus)));
        int high=Math.max(low,Math.min(body.length(),Math.max(anchor,focus)));
        int start=body.lastIndexOf('\n',low-1)+1;
        int last=high>low&&body.charAt(high-1)=='\n'?high-1:high;
        int end=body.indexOf('\n',last);if(end<0)end=body.length();
        List<Item> tasks=items(body);boolean all=true;
        for(int pos=start;pos<=end;){
            Item item=find(tasks,pos);if(item==null){all=false;break;}
            int next=body.indexOf('\n',pos);if(next<0||next>=end)break;pos=next+1;
        }
        StringBuilder out=new StringBuilder();int cursor=high;
        for(int pos=start;pos<=end;){
            int next=body.indexOf('\n',pos),lineEnd=next<0?body.length():next;
            Item item=find(tasks,pos);String line=body.substring(pos,lineEnd);
            if(all){
                int a=item.marker-pos,b=item.content-pos;out.append(line,0,a).append(line.substring(b));
                if(high>=item.content)cursor-=b-a;else if(high>item.marker)cursor-=high-item.marker;
            }else if(item==null){out.append("- [ ] ").append(line);if(high>=pos)cursor+=6;}
            else out.append(line);
            if(next<0||next>=end)break;out.append('\n');pos=next+1;
        }
        return new Change(body.substring(0,start)+out+body.substring(end),cursor);
    }
    private static Item find(List<Item> items,int lineStart){for(Item item:items)if(item.start==lineStart)return item;return null;}
    public static List<Item> selected(String body,int anchor,int focus){
        int low=Math.max(0,Math.min(body.length(),Math.min(anchor,focus))),high=Math.max(low,Math.min(body.length(),Math.max(anchor,focus)));
        int start=body.lastIndexOf('\n',low-1)+1;
        if(high>low&&body.charAt(high-1)=='\n')high--;
        List<Item> result=new ArrayList<>();for(Item item:items(body))if(item.start>=start&&item.start<=high)result.add(item);return result;
    }
    public static Change setChecked(String body,int anchor,int focus,boolean checked){
        StringBuilder out=new StringBuilder(body);for(Item item:selected(body,anchor,focus))out.setCharAt(item.marker+3,checked?'x':' ');
        return new Change(out.toString(),focus);
    }
    public static Change indent(String body,int anchor,int focus,boolean more){
        List<Item> chosen=selected(body,anchor,focus);StringBuilder out=new StringBuilder(body);int cursor=focus;
        for(int i=chosen.size()-1;i>=0;i--){Item item=chosen.get(i);String prefix=body.substring(item.start,item.marker);
            if(more){if(prefix.replace("\t","  ").length()>=12)continue;out.insert(item.start,"  ");if(focus>=item.start)cursor+=2;}
            else if(!prefix.isEmpty()){int count=prefix.startsWith("  ")?2:1;out.delete(item.start,item.start+count);if(focus>=item.start)cursor-=Math.min(count,focus-item.start);}
        }return new Change(out.toString(),cursor);
    }
    public static Change deleteItems(String body,int anchor,int focus){
        List<Item> chosen=selected(body,anchor,focus);StringBuilder out=new StringBuilder(body);int cursor=focus;
        for(int i=chosen.size()-1;i>=0;i--){Item item=chosen.get(i);int end=body.indexOf('\n',item.start);end=end<0?body.length():end+1;
            out.delete(item.start,end);if(focus>=end)cursor-=end-item.start;else if(focus>=item.start)cursor=item.start;
        }return new Change(out.toString(),cursor);
    }
    public static Change removeMarkers(String body,int anchor,int focus){
        List<Item> chosen=selected(body,anchor,focus);StringBuilder out=new StringBuilder(body);int cursor=focus;
        for(int i=chosen.size()-1;i>=0;i--){Item item=chosen.get(i);out.delete(item.marker,item.content);if(focus>=item.content)cursor-=item.content-item.marker;else if(focus>item.marker)cursor-=focus-item.marker;}
        return new Change(out.toString(),cursor);
    }
    public static Change move(String body,int anchor,int focus,boolean down){
        List<Item> chosen=selected(body,anchor,focus);if(chosen.isEmpty())return new Change(body,focus);
        int start=chosen.get(0).start,end=chosen.get(chosen.size()-1).end;
        // Move a complete physical-line block; keep all intervening paragraph contents intact.
        int lineEnd=body.indexOf('\n',end);int after=lineEnd<0?body.length():lineEnd+1;
        if(down){
            if(lineEnd<0||after>=body.length())return new Change(body,focus);
            int nextEnd=body.indexOf('\n',after),tail=nextEnd<0?body.length():nextEnd;
            if(tail>after&&body.charAt(tail-1)=='\r')tail--;
            String block=body.substring(start,end),next=body.substring(after,tail);
            String newline=body.substring(end,after);
            return new Change(body.substring(0,start)+next+newline+block+body.substring(tail),focus+next.length()+newline.length());
        }
        if(start==0)return new Change(body,focus);
        int previousEnd=start-1;if(previousEnd>0&&body.charAt(previousEnd-1)=='\r')previousEnd--;
        int previousStart=body.lastIndexOf('\n',previousEnd-1)+1;
        String previous=body.substring(previousStart,previousEnd),block=body.substring(start,end);
        String newline=body.substring(previousEnd,start);
        return new Change(body.substring(0,previousStart)+block+newline+previous+body.substring(end),focus-(start-previousStart));
    }
    /** Called only for one user-inserted newline, never for paste/undo/restoration. */
    public static Change enter(String before,int offset,String newline){
        if(!"\n".equals(newline)&&!"\r\n".equals(newline))return null;
        Item item=at(before,offset);if(item==null||offset<item.content)return null;
        if(before.substring(item.content,item.end).trim().isEmpty()){
            String body=before.substring(0,item.marker)+before.substring(item.end);
            return new Change(body,item.marker);
        }
        String prefix=before.substring(item.start,item.marker)+"- [ ] ";
        return new Change(before.substring(0,offset)+newline+prefix+before.substring(offset),offset+newline.length()+prefix.length());
    }
}
