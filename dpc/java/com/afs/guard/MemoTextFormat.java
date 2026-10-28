package com.afs.guard;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded Markdown formatting; display spans never replace source text or composing spans. */
public final class MemoTextFormat {
    private MemoTextFormat(){}
    private static final int MAX_BODY=20000;
    private static final Pattern FENCE=Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern TASK=Pattern.compile("^[ \\t]*[-*+] \\[[ xX]\\] ");
    private static final Pattern HEADING=Pattern.compile("^(#{1,6}) ");
    private static final Pattern LIST=Pattern.compile("^(?:[-*+] |[0-9]{1,9}[.)] )");
    public static final class Change {
        public final String body;
        public final int cursor;
        public Change(String body,int cursor){this.body=body;this.cursor=clamp(cursor,body.length());}
    }
    private static final class Line {
        int start,end;boolean code;
        Line(int start,int end,boolean code){this.start=start;this.end=end;this.code=code;}
    }
    private static int clamp(int value,int size){return Math.max(0,Math.min(value,size));}
    private static List<Line> lines(String body){
        List<Line> out=new ArrayList<>();char fence=0;int length=0;
        for(int start=0;start<=body.length();){
            int next=body.indexOf('\n',start),end=next<0?body.length():next;if(end>start&&body.charAt(end-1)=='\r')end--;
            Matcher m=FENCE.matcher(body.substring(start,end));boolean code=fence!=0;
            if(m.matches()){
                code=true;String run=m.group(1);
                if(fence==0){fence=run.charAt(0);length=run.length();}
                else if(run.charAt(0)==fence&&run.length()>=length&&m.group(2).trim().isEmpty())fence=0;
            }
            out.add(new Line(start,end,code));if(next<0)break;start=next+1;
        }return out;
    }
    private static List<Line> selected(String body,int anchor,int focus){
        int low=clamp(Math.min(anchor,focus),body.length()),high=clamp(Math.max(anchor,focus),body.length());
        int start=body.lastIndexOf('\n',low-1)+1;if(high>low&&body.charAt(high-1)=='\n')high--;
        List<Line> out=new ArrayList<>();for(Line line:lines(body))if(line.start>=start&&line.start<=high)out.add(line);return out;
    }
    private static boolean codeAt(String body,int start,int end){
        for(Line line:lines(body))if(line.code&&(start==end?start>=line.start&&start<=line.end:start<line.end&&end>line.start))return true;return false;
    }
    private static int indentEnd(String line){int i=0;while(i<line.length()&&(line.charAt(i)==' '||line.charAt(i)=='\t'))i++;return i;}
    private static Change changed(String original,String body,int cursor,int oldFocus){return body.length()>MAX_BODY?new Change(original,oldFocus):new Change(body,cursor);}
    /** level=0 restores normal paragraph; completed tasks are never converted to headings. */
    public static Change heading(String body,int anchor,int focus,int level){
        if(level<0||level>3)throw new IllegalArgumentException("Heading level must be 0..3");
        return paragraphs(body,anchor,focus,0,level);
    }
    /** Existing task lines stay task lines, including their checked state. */
    public static Change list(String body,int anchor,int focus,boolean ordered){return paragraphs(body,anchor,focus,1,ordered?1:0);}
    /** Each level adds two spaces; at most twelve indentation characters. */
    public static Change indent(String body,int anchor,int focus,boolean more){return paragraphs(body,anchor,focus,2,more?1:0);}
    private static Change paragraphs(String body,int anchor,int focus,int operation,int argument){
        List<Line> chosen=selected(body,anchor,focus);StringBuilder out=new StringBuilder(body);int cursor=clamp(focus,body.length());
        int number=0;java.util.HashMap<Integer,Integer> numbers=new java.util.HashMap<>();
        for(Line line:chosen){String text=body.substring(line.start,line.end);if(!line.code&&!TASK.matcher(text).find())numbers.put(line.start,++number);}
        for(int n=chosen.size()-1;n>=0;n--){
            Line line=chosen.get(n);if(line.code)continue;String text=body.substring(line.start,line.end);int indentation=indentEnd(text),start=line.start,end=start;String replacement;
            if(operation==2){
                if(argument==1){if(text.substring(0,indentation).replace("\t","  ").length()>=12)continue;replacement="  ";}
                else{if(indentation==0)continue;end=start+(text.startsWith("  ")?2:1);replacement="";}
            }else{
                if(TASK.matcher(text).find())continue;start+=indentation;end=start;String remainder=text.substring(indentation);
                Matcher h=HEADING.matcher(remainder);if(h.find())end+=h.end();
                if(operation==0){replacement=argument==0?"":(argument==1?"# ":argument==2?"## ":"### ");}
                else{
                    Matcher list=LIST.matcher(remainder.substring(end-start));if(list.find())end+=list.end();
                    replacement=argument==1?numbers.get(line.start)+". ":"- ";
                }
            }
            out.replace(start,end,replacement);int delta=replacement.length()-(end-start);
            if(cursor>=end)cursor+=delta;else if(cursor>start)cursor=start+replacement.length();
        }return changed(body,out.toString(),cursor,focus);
    }
    /** Styles: bold, italic, strike, underline, highlight. Select text, or insert an empty pair at the caret. */
    public static Change inline(String body,int anchor,int focus,String style){
        int start=clamp(Math.min(anchor,focus),body.length()),end=clamp(Math.max(anchor,focus),body.length());
        String left,right;
        if("bold".equals(style)){left=right="**";}else if("italic".equals(style)){left=right="*";}
        else if("strike".equals(style)){left=right="~~";}else if("underline".equals(style)){left="<u>";right="</u>";}
        else if("highlight".equals(style)){left=right="==";}else throw new IllegalArgumentException("Unknown inline style");
        if(codeAt(body,start,end))return new Change(body,focus);
        // Formatting inside the marker would corrupt completion state.
        for(MemoChecklist.Item task:MemoChecklist.items(body))if(start<task.content&&end>task.marker||start==end&&start>=task.marker&&start<task.content)return new Change(body,focus);
        String selected=body.substring(start,end);
        if(start>=left.length()&&end+right.length()<=body.length()&&body.substring(start-left.length(),start).equals(left)&&body.substring(end,end+right.length()).equals(right))
            return new Change(body.substring(0,start-left.length())+selected+body.substring(end+right.length()),end-left.length());
        if(selected.length()>=left.length()+right.length()&&selected.startsWith(left)&&selected.endsWith(right)){
            String plain=selected.substring(left.length(),selected.length()-right.length());return new Change(body.substring(0,start)+plain+body.substring(end),start+plain.length());
        }
        return changed(body,body.substring(0,start)+left+selected+right+body.substring(end),start+left.length()+selected.length(),focus);
    }
    private static final class Styled extends StyleSpan {Styled(int value){super(value);}}
    private static final class Underlined extends UnderlineSpan{}
    private static final class Struck extends StrikethroughSpan{}
    private static final class Highlighted extends BackgroundColorSpan{Highlighted(){super(0x66FFC107);}}
    private static final class Ink extends ForegroundColorSpan{Ink(int color){super(color);}}
    private static final class Sized extends RelativeSizeSpan{Sized(float size){super(size);}}
    private static void span(Editable text,Object value,int start,int end){if(start<end)text.setSpan(value,start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
    private static void removeOwned(Editable text,Class<?> type){for(Object span:text.getSpans(0,text.length(),type))text.removeSpan(span);}
    /** Call after checklist decoration. Only spans owned by this class are removed. */
    public static void decorate(Editable text,int textColor,int mutedColor){
        if(text==null)return;
        for(Class<?> type:new Class<?>[]{Styled.class,Underlined.class,Struck.class,Highlighted.class,Ink.class,Sized.class})removeOwned(text,type);
        String body=text.toString();String[] patterns={"\\*\\*(.+?)\\*\\*","(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)","~~(.+?)~~","<u>(.+?)</u>","==(.+?)=="};
        for(int kind=0;kind<patterns.length;kind++){
            Matcher m=Pattern.compile(patterns[kind]).matcher(body);
            while(m.find())if(!codeAt(body,m.start(),m.end())){
                Object value=kind==0?new Styled(Typeface.BOLD):kind==1?new Styled(Typeface.ITALIC):kind==2?new Struck():kind==3?new Underlined():new Highlighted();
                span(text,value,m.start(1),m.end(1));span(text,new Ink(mutedColor),m.start(),m.start(1));span(text,new Ink(mutedColor),m.end(1),m.end());
            }
        }
        for(Line line:lines(body))if(!line.code){String value=body.substring(line.start,line.end);int indent=indentEnd(value);Matcher h=HEADING.matcher(value.substring(indent));
            if(h.find()){int start=line.start+indent+h.end();span(text,new Styled(Typeface.BOLD),start,line.end);span(text,new Sized(h.group(1).length()==1?1.5f:h.group(1).length()==2?1.3f:1.15f),start,line.end);span(text,new Ink(textColor),start,line.end);span(text,new Ink(mutedColor),line.start+indent,start);}
        }
    }
    /** Apply to already HTML-escaped text before the Markor filter. Enables only exact safe underline/highlight pairs. */
    public static String previewExtras(String escaped){
        StringBuilder out=new StringBuilder();int previous=0;
        for(Line line:lines(escaped)){
            out.append(escaped,previous,line.start);String value=escaped.substring(line.start,line.end);
            if(!line.code)value=value.replaceAll("&lt;u&gt;([^\\n]+?)&lt;/u&gt;","<u>$1</u>")
                    .replaceAll("==([^\\n]+?)==","<afshighlight style='background-color:#FFF3B0'>$1</afshighlight>");
            out.append(value);previous=line.end;
        }return out.append(escaped.substring(previous)).toString();
    }
    /** Android Html ignores CSS backgrounds; handle our escaped-source-only highlight tag explicitly. */
    public static Spanned previewRich(String html){
        final java.util.ArrayDeque<Integer> starts=new java.util.ArrayDeque<>();
        return android.text.Html.fromHtml(html,android.text.Html.FROM_HTML_MODE_COMPACT,null,(opening,tag,output,reader)->{
            if(!"afshighlight".equalsIgnoreCase(tag))return;
            if(opening)starts.push(output.length());else if(!starts.isEmpty())span(output,new Highlighted(),starts.pop(),output.length());
        });
    }
}
