package com.afs.guard;

import com.afs.guard.vendor.GsSimpleMarkdownParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Offline editor operations; the small Markdown renderer is adapted from Markor. */
public final class MemoEditor {
    private MemoEditor() { }
    public static final class State {
        public final String title, body;
        public State(String title, String body) { this.title=title;this.body=body; }
        boolean same(State other) { return title.equals(other.title)&&body.equals(other.body); }
    }
    public static final class History {
        private final ArrayList<State> states=new ArrayList<>();
        private int position;
        public History(State initial) { states.add(initial); }
        public void record(State state) {
            if(states.get(position).same(state))return;
            while(states.size()>position+1)states.remove(states.size()-1);
            states.add(state);
            if(states.size()>40)states.remove(0);
            position=states.size()-1;
        }
        public boolean canUndo(){return position>0;}
        public boolean canRedo(){return position+1<states.size();}
        public State undo(){if(canUndo())position--;return states.get(position);}
        public State redo(){if(canRedo())position++;return states.get(position);}
    }
    public static String preview(String source,int color) {
        return preview(source,color,null);
    }
    public static String preview(String source,int color,String taskLinkPrefix) {
        // Only TextView consumes this output. Escape raw HTML; never load images or run scripts.
        String safe=source.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
        java.util.List<MemoChecklist.Item> tasks=MemoChecklist.items(safe);
        for(int i=tasks.size()-1;i>=0;i--){MemoChecklist.Item item=tasks.get(i);String symbol=item.checked?"☑":"☐";
            if(taskLinkPrefix!=null)symbol="<a href='"+taskLinkPrefix+i+"'>"+symbol+"</a>";
            safe=safe.substring(0,item.marker)+symbol+(taskLinkPrefix==null?" ":"")+safe.substring(item.content);}
        String html=GsSimpleMarkdownParser.FILTER_ANDROID_TEXTVIEW.filter(safe);
        return html.replace("#000000",String.format(java.util.Locale.ROOT,"#%06X",color&0xffffff)).replace("\n","<br/>");
    }
    public static String readText(InputStream input) throws java.io.IOException {
        if(input==null)throw new java.io.IOException("无法读取文件");
        try(InputStreamReader reader=new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            StringBuilder out=new StringBuilder();char[] buffer=new char[2048];int count;
            while((count=reader.read(buffer))!=-1){out.append(buffer,0,count);
                if(out.length()>MemoStore.MAX_BODY)throw new java.io.IOException("文件超过 2 万字，请拆分后导入");}
            String value=out.toString();return value.startsWith("\uFEFF")?value.substring(1):value;
        }
    }
    public static String filename(String title) {
        String clean=title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();
        return (clean.isEmpty()?"备忘录":clean.substring(0,Math.min(clean.length(),60)))+".md";
    }
}
