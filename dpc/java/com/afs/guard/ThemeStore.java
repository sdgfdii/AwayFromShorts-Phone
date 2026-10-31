package com.afs.guard;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.xmlpull.v1.XmlPullParser;

final class ThemeStore {
    static final String CONFIG="appearance_v1",REVISION="appearance_revision";
    private static final Map<String,AppearancePalette> palettes=new HashMap<>();
    private static final Map<String,Typeface> fonts=new HashMap<>();
    private static int overrideId,signatureId;
    private static void ids(Context c){if(overrideId==0){overrideId=c.getResources().getIdentifier("afs_font_override","id",c.getPackageName());signatureId=c.getResources().getIdentifier("afs_typography_signature","id",c.getPackageName());}}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(Ui.SP,0);}

    /* 主题页每次布局都会被 typography 走一遍；这里按原始串缓存解析结果，避免反复 JSON 解析。
       保存时写回的串会变，缓存自然失效；调用方拿到的仍是副本，可以随便改再 save。
       加锁：缓存本身是跨线程共享的静态状态（虽然现在只有 UI 线程读）。 */
    private static String cachedRaw;
    private static AppearanceConfig cachedConfig;
    static synchronized AppearanceConfig load(Context c){
        SharedPreferences p=prefs(c);
        String raw=p.getString(CONFIG,"");
        AppearanceConfig a;
        if(cachedConfig!=null&&raw.equals(cachedRaw))a=cachedConfig.copy();
        else {a=AppearanceConfig.parse(raw);cachedConfig=a.copy();cachedRaw=raw;}
        a.mode=p.getString(Ui.K_THEME,a.mode);return a;
    }
    static void save(Context c,AppearanceConfig a){
        prefs(c).edit().putString(CONFIG,a.json()).putString(Ui.K_THEME,a.mode)
                .putLong(REVISION,prefs(c).getLong(REVISION,0)+1).apply();
    }
    static long revision(Context c){return prefs(c).getLong(REVISION,0);}
    static boolean dark(Context c){String mode=load(c).mode;return "dark".equals(mode)||("auto".equals(mode)
            &&(c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    static String signature(Context c){return revision(c)+":"+dark(c)+":"+systemSeed(c);}
    static int systemSeed(Context c){
        if(Build.VERSION.SDK_INT>=31){int id=c.getResources().getIdentifier("system_accent1_500","color","android");if(id!=0)return c.getColor(id);}
        return AppearanceConfig.DEFAULT_COLOR;
    }
    static synchronized AppearancePalette palette(Context c,boolean dark){return palette(load(c),dark,systemSeed(c));}
    static synchronized AppearancePalette palette(AppearanceConfig a,boolean dark,int systemSeed){
        String key=a.skin+":"+(a.selectedSeed()==0?systemSeed:a.selectedSeed())+":"+a.style+":"+a.pureBlack+":"+dark;
        AppearancePalette p=palettes.get(key);if(p==null){if(palettes.size()>32)palettes.clear();p=new AppearancePalette(a,dark,systemSeed);palettes.put(key,p);}return p;
    }
    static Context context(Context base){
        AppearanceConfig a=load(base);if(!a.customScale)return base;
        Configuration configuration=new Configuration(base.getResources().getConfiguration());configuration.fontScale=AppearanceConfig.clampScale(a.scale);
        return base.createConfigurationContext(configuration);
    }
    static float systemScale(Context c){return c.getApplicationContext().getResources().getConfiguration().fontScale;}
    static synchronized Typeface font(Context c,String family,int style){
        if(family==null||family.isEmpty())return Typeface.defaultFromStyle(style);
        String key=family+":"+style;Typeface face=fonts.get(key);if(face!=null)return face;
        try {
            if(family.startsWith("asset_"))face=Typeface.create(Typeface.createFromAsset(c.getAssets(),
                    "fonts/"+("asset_xiaowei".equals(family)?"xiaowei.ttf":"mashanzheng.ttf")),style);
            else face=Typeface.create(family,style);
        }catch(RuntimeException e){face=Typeface.defaultFromStyle(style);}
        fonts.put(key,face);return face;
    }
    static void excludeFont(View view){ids(view.getContext());view.setTag(overrideId,Boolean.TRUE);}
    static boolean fontExcluded(View view){ids(view.getContext());return Boolean.TRUE.equals(view.getTag(overrideId));}
    static void typography(View root){String family=load(root.getContext()).font;ids(root.getContext());applyFont(root,family.isEmpty()?"sans-serif":family,false);}
    private static void applyFont(View view,String family,boolean excluded){
        excluded|=Boolean.TRUE.equals(view.getTag(overrideId));
        if(!excluded&&view instanceof TextView){
            TextView text=(TextView)view;int style=text.getTypeface()==null?0:text.getTypeface().getStyle();String key=family+":"+style;
            if(!key.equals(view.getTag(signatureId))){text.setTypeface(font(view.getContext(),family,style));view.setTag(signatureId,key);}
        }
        SwissTypography.applyNode(view,excluded);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)applyFont(group.getChildAt(i),family,excluded);}
    }
    static List<String> installedFonts(){
        TreeSet<String> names=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try(FileInputStream input=new FileInputStream("/system/etc/fonts.xml")){
            XmlPullParser parser=android.util.Xml.newPullParser();parser.setInput(input,"UTF-8");
            for(int type=parser.getEventType();type!=XmlPullParser.END_DOCUMENT;type=parser.next()){
                if(type==XmlPullParser.START_TAG&&"family".equals(parser.getName())){String name=parser.getAttributeValue(null,"name");if(name!=null&&!name.trim().isEmpty()&&!name.startsWith("."))names.add(name);}
            }
        }catch(Exception ignored){}
        if(names.isEmpty())Collections.addAll(names,"sans-serif","serif","monospace");
        return new ArrayList<>(names);
    }
    static AlertDialog.Builder dialog(Context c){return new AlertDialog.Builder(c){
        @Override public AlertDialog create(){
            AlertDialog d=super.create();
            if(d.getWindow()!=null)d.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                public void onViewAttachedToWindow(View view){styleDialog(d);}
                public void onViewDetachedFromWindow(View view){}
            });
            return d;
        }
    };}
    static void styleDialog(AlertDialog dialog){
        Ui ui=new Ui(dialog.getContext());
        if(dialog.getWindow()!=null){View root=dialog.getWindow().getDecorView();typography(root);SwissTypography.observe(root);tintChoices(root,ui);}
        for(int button:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){
            android.widget.Button b=dialog.getButton(button);if(b!=null){String label=b.getText().toString();b.setTextColor(ActionFeedback.textColors(button==AlertDialog.BUTTON_POSITIVE&&(label.contains("删除")||label.contains("重置")||label.contains("移入回收站"))?ui.red():ui.accent()));}
        }
    }
    private static void tintChoices(View view,Ui ui){
        if(view instanceof android.widget.CheckedTextView)((android.widget.CheckedTextView)view).setCheckMarkTintList(
                new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ui.accent(),ui.text3()}));
        if(view instanceof android.widget.CompoundButton)((android.widget.CompoundButton)view).setButtonTintList(
                new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ui.accent(),ui.text3()}));
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)tintChoices(g.getChildAt(i),ui);}
    }
}
