package com.afs.guard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent appearance only; never shares storage with study/check-in records or freeze rules. */
public final class AppearanceConfig {
    public static final int DEFAULT_COLOR=0xFF007AFF;
    public static final int AMAP_COLOR=0xFF2D7DFF;
    public static final int[] PRESETS={DEFAULT_COLOR,0xFFAF52DE,0xFFFF2D55,0xFFFF3B30,
            0xFFFF9500,0xFFFFCC00,0xFF34C759,0xFF8E8E93};
    public String mode="auto",style="vivid",animation="slide",glass="ultra",font="",skin="classic";
    public boolean pureBlack,floating=true,customScale;
    public int seed=DEFAULT_COLOR;
    public int amapSeed=AMAP_COLOR;
    public float scale=1;
    public final List<Integer> colors=new ArrayList<>();
    public AppearanceConfig(){for(int c:PRESETS)colors.add(c);}
    private static String valid(String value,String fallback,String... choices){return Arrays.asList(choices).contains(value)?value:fallback;}
    public static float clampScale(float scale){return Float.isNaN(scale)||Float.isInfinite(scale)?1:Math.max(.8f,Math.min(1.4f,Math.round(scale*20)/20f));}
    public static AppearanceConfig parse(String raw){
        AppearanceConfig c=new AppearanceConfig();
        if(raw==null||raw.trim().isEmpty())return c;
        try {
            JSONObject j=new JSONObject(raw);
            c.skin=valid(j.optString("skin"),"classic","classic","amap");
            c.amapSeed=j.optInt("amapSeed",AMAP_COLOR);if(c.amapSeed!=0)c.amapSeed|=0xFF000000;
            c.mode=valid(j.optString("mode"),"auto","auto","light","dark");
            c.style=valid(j.optString("style"),"vivid","vivid","tinted","soft","monochrome");
            c.animation=valid(j.optString("animation"),"slide","slide","fade");
            c.glass=valid(j.optString("glass"),"ultra","none","standard","ultra");
            c.font=j.optString("font","");c.pureBlack=j.optBoolean("pureBlack");
            c.floating=j.optBoolean("floating",true);c.customScale=j.optBoolean("customScale");
            c.scale=clampScale((float)j.optDouble("scale",1));c.seed=j.optInt("seed",DEFAULT_COLOR);
            if(c.seed!=0)c.seed|=0xFF000000;
            JSONArray list=j.optJSONArray("colors");
            if(list!=null){c.colors.clear();for(int i=0;i<list.length();i++){int v=list.optInt(i)|0xFF000000;if(!c.colors.contains(v))c.colors.add(v);}}
        }catch(Exception ignored){}
        return c;
    }
    public String json(){
        JSONObject j=new JSONObject();
        try {j.put("mode",mode).put("skin",skin).put("amapSeed",amapSeed).put("style",style).put("animation",animation).put("glass",glass)
                .put("font",font).put("pureBlack",pureBlack).put("floating",floating)
                .put("customScale",customScale).put("scale",clampScale(scale)).put("seed",seed)
                .put("colors",new JSONArray(colors));}catch(Exception ignored){}
        return j.toString();
    }
    /** 字段级浅拷贝：给主题缓存用，别为了复制一份配置又走一遍 JSON 序列化。 */
    public AppearanceConfig copy(){
        AppearanceConfig c=new AppearanceConfig();
        c.colors.clear();c.colors.addAll(colors);
        c.mode=mode;c.style=style;c.animation=animation;c.glass=glass;c.font=font;c.skin=skin;
        c.pureBlack=pureBlack;c.floating=floating;c.customScale=customScale;
        c.seed=seed;c.amapSeed=amapSeed;c.scale=scale;
        return c;
    }
    public boolean amap(){return "amap".equals(skin);}
    public int selectedSeed(){return amap()?amapSeed:seed;}
    public int defaultSeed(){return amap()?AMAP_COLOR:DEFAULT_COLOR;}
    public void selectColor(int color){if(amap())amapSeed=color;else seed=color;}
    public void addColor(int color){color|=0xFF000000;if(!colors.contains(color))colors.add(color);selectColor(color);}
    public void editColor(int oldColor,int newColor){
        oldColor|=0xFF000000;newColor|=0xFF000000;
        int at=colors.indexOf(oldColor);if(oldColor==newColor)return;if(at<0){if(selectedSeed()==oldColor)addColor(newColor);return;}
        if(colors.contains(newColor))colors.remove(at);else colors.set(at,newColor);
        if(seed==oldColor)seed=newColor;
        if(amapSeed==oldColor)amapSeed=newColor;
    }
    public void removeColor(int color){
        colors.remove(Integer.valueOf(color));
        if(seed==color)seed=colors.contains(DEFAULT_COLOR)?DEFAULT_COLOR:0;
        if(amapSeed==color)amapSeed=color==AMAP_COLOR?DEFAULT_COLOR:AMAP_COLOR;
    }
    public void resetColors(){colors.clear();for(int c:PRESETS)colors.add(c);selectColor(defaultSeed());style="vivid";}
    public static int parseHex(String text){
        String s=text.trim().replaceFirst("^#","");
        if(!s.matches("[0-9a-fA-F]{6}"))throw new IllegalArgumentException("请输入六位颜色值，例如 007AFF");
        return 0xFF000000|Integer.parseInt(s,16);
    }
    public static String hex(int color){return String.format(java.util.Locale.ROOT,"%06X",color&0xFFFFFF);}
}
