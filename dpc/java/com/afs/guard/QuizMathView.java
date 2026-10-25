package com.afs.guard;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.*;
import android.view.ViewGroup;
import org.json.JSONObject;

/** Only bundled, read-only assets are served. Imported content is escaped text, never markup or JS. */
public final class QuizMathView extends WebView {
    private static final String ORIGIN="https://quiz.afs.invalid/";
    public QuizMathView(Context context,Ui ui,String content) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);setVerticalScrollBarEnabled(false);
        setLayoutParams(new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ui.dp(110)));
        getSettings().setJavaScriptEnabled(true);
        getSettings().setAllowFileAccess(false);getSettings().setAllowContentAccess(false);
        getSettings().setBlockNetworkLoads(true);getSettings().setDomStorageEnabled(false);
        getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public boolean shouldOverrideUrlLoading(WebView v,String u){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r) {
                String url=r.getUrl().toString();
                if(url.startsWith(ORIGIN+"katex/")) {
                    String path=Uri.parse(url).getPath().substring(1);
                    if(path.contains("..")||!path.matches("katex/[a-zA-Z0-9_./-]+"))return empty();
                    String mime=path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":"font/woff2";
                    try{return new WebResourceResponse(mime,"UTF-8",context.getAssets().open("quiz/"+path));}
                    catch(Exception ignored){return empty();}
                }
                return empty();
            }
            private WebResourceResponse empty(){return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));}
            @Override public void onPageFinished(WebView v,String url){measureContent();}
        });
        String fg=String.format("#%06X",ui.text()&0xFFFFFF);
        // All imported strings go through HTML escaping; no Javascript bridge is exposed.
        android.util.DisplayMetrics metrics=context.getResources().getDisplayMetrics();
        float fontSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,15,metrics)/metrics.density;
        String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            +"<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'none'; connect-src 'none'\">"
            +"<link rel='stylesheet' href='katex/katex.min.css'><script src='katex/katex.min.js'></script>"
            +"<script src='katex/auto-render.min.js'></script><style>html,body{margin:0;padding:0;background:transparent;color:"+fg+";}"
            +"body{font:"+fontSize+"px/1.6 sans-serif;letter-spacing:0;text-align:left;overflow-wrap:anywhere;}#math{padding:4px 0 12px;white-space:pre-wrap;}"
            +".katex{font-size:1.12em;white-space:normal;}.katex-display{text-align:left;overflow-x:auto;overflow-y:hidden;margin:12px 0;padding:8px 0;}"
            +".katex-display>.katex{text-align:left;white-space:nowrap;}</style></head><body><div id='math'>"+escape(content)+"</div>"
            +"<script>renderMathInElement(document.getElementById('math'),{delimiters:[{left:'$$',right:'$$',display:true},"
            +"{left:'$',right:'$',display:false},"
            +"{left:'\\\\(',right:'\\\\)',display:false},{left:'\\\\[',right:'\\\\]',display:true}],"
            +"throwOnError:false,trust:false,maxExpand:300,maxSize:12,strict:'ignore'});</script></body></html>";
        loadDataWithBaseURL(ORIGIN,html,"text/html","UTF-8",null);
    }
    private void measureContent() {
        evaluateJavascript("Math.ceil(document.getElementById('math').getBoundingClientRect().height)",value->{
            try {
                int height=(int)Math.ceil(Double.parseDouble(value)*getResources().getDisplayMetrics().density);
                height=Math.max(48,Math.min(height,24000));
                ViewGroup.LayoutParams lp=getLayoutParams();if(lp!=null&&lp.height!=height){lp.height=height;setLayoutParams(lp);}
            } catch(Exception ignored) { }
        });
        postDelayed(()->evaluateJavascript("document.fonts.ready.then(function(){return true;})",v->{
            if(getParent()!=null) evaluateJavascript("Math.ceil(document.getElementById('math').getBoundingClientRect().height)",h->{
                try{ViewGroup.LayoutParams lp=getLayoutParams();lp.height=Math.max(48,(int)Math.ceil(Double.parseDouble(h)*getResources().getDisplayMetrics().density));setLayoutParams(lp);}catch(Exception ignored){}
            });
        }),200);
    }
    public static String escape(String s) {return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
