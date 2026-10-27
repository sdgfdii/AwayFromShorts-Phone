package com.afs.guard;

import hct.Hct;

/** Palette edits must be lossless and reset only their own scope, across process restarts. */
public final class AppearanceTest {
    private static int count;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);count++;}
    public static void main(String[] args){
        AppearanceConfig a=new AppearanceConfig();a.mode="dark";a.pureBlack=true;a.floating=false;a.font="serif";a.animation="fade";a.glass="none";a.customScale=true;a.scale=1.35f;
        a.addColor(0xFF123456);a.addColor(0xFF123456);check(a.colors.size()==9,"adding an existing color selects it without duplicating it");
        a.editColor(0xFF123456,0xFF34C759);check(a.colors.size()==8&&a.seed==0xFF34C759,"editing to another existing color merges without losing selection");
        a.removeColor(0xFF34C759);check(a.seed==AppearanceConfig.DEFAULT_COLOR,"removing the selected color falls back to the default preset");
        a.removeColor(AppearanceConfig.DEFAULT_COLOR);a.seed=0xFFAF52DE;a.removeColor(a.seed);check(a.seed==0,"a missing default falls back to system color");
        AppearanceConfig b=AppearanceConfig.parse(a.json());check(b.mode.equals("dark")&&b.pureBlack&&!b.floating&&b.font.equals("serif")&&b.animation.equals("fade")&&b.glass.equals("none")&&b.customScale&&Math.abs(b.scale-1.35)<.001,"all appearance choices survive serialization");
        b.resetColors();check(b.colors.size()==8&&b.seed==AppearanceConfig.DEFAULT_COLOR&&b.style.equals("vivid"),"palette reset restores all palette choices");
        check(b.mode.equals("dark")&&b.pureBlack&&!b.floating&&b.font.equals("serif")&&b.customScale&&b.animation.equals("fade"),"palette reset preserves unrelated settings");
        check(AppearanceConfig.clampScale(.1f)==.8f&&AppearanceConfig.clampScale(7)==1.4f&&AppearanceConfig.clampScale(Float.NaN)==1,"invalid text scale is bounded safely");
        check(AppearanceConfig.parseHex("#ab12EF")==0xFFAB12EF&&AppearanceConfig.hex(0xFF00AAFF).equals("00AAFF"),"hex colors round trip including leading zeroes");
        boolean rejected=false;try{AppearanceConfig.parseHex("nothex");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"invalid hex cannot be confirmed");
        AppearanceConfig broken=AppearanceConfig.parse("{broken");check(broken.colors.size()==8&&broken.scale==1,"damaged preference content recovers defaults");
        for(String style:new String[]{"vivid","tinted","soft","monochrome"}){
            AppearanceConfig c=new AppearanceConfig();c.style=style;c.seed=0xFFFF9500;
            AppearancePalette light=new AppearancePalette(c,false,0),dark=new AppearancePalette(c,true,0);
            check(Hct.fromInt(light.bg).getTone()>90&&Hct.fromInt(dark.bg).getTone()<12,"light and dark surfaces remain distinct: "+style);
            c.pureBlack=true;check(new AppearancePalette(c,true,0).bg==0xFF000000&&new AppearancePalette(c,false,0).bg==light.bg,"pure black changes dark surfaces only: "+style);
            if(style.equals("monochrome"))check(Hct.fromInt(light.accent).getChroma()<3,"monochrome removes accent chroma");
        }
        AppearanceConfig system=new AppearanceConfig();system.seed=0;check(new AppearancePalette(system,false,0xFFFF0000).accent!=new AppearancePalette(system,false,0xFF0000FF).accent,"system accent updates are resolved at read time");
        AppearanceConfig skin=new AppearanceConfig();skin.seed=0xFFFFCC00;skin.skin="amap";
        check(skin.selectedSeed()==AppearanceConfig.AMAP_COLOR,"Amap starts with its own blue and preserves the classic seed");
        skin.selectColor(0xFF1565C0);skin.font="serif";skin.customScale=true;skin.scale=1.2f;skin.glass="standard";
        AppearanceConfig restored=AppearanceConfig.parse(skin.json());restored.skin="classic";
        check(restored.selectedSeed()==0xFFFFCC00&&restored.font.equals("serif")&&restored.customScale&&restored.glass.equals("standard"),"switching skins restores classic color without resetting font, scale or glass");
        restored.skin="amap";check(restored.selectedSeed()==0xFF1565C0,"custom Amap color survives process restart and round trip");
        restored.resetColors();check(restored.selectedSeed()==AppearanceConfig.AMAP_COLOR&&restored.seed==0xFFFFCC00,"resetting Amap color does not reset classic color");
        AppearancePalette amapLight=new AppearancePalette(restored,false,0),amapDark=new AppearancePalette(restored,true,0);
        check(amapLight.bg==0xFFF5F6F7&&amapLight.surface==0xFFFFFFFF&&amapLight.selection==0xFFE8E9EB,"Amap uses gray page, white panels and neutral selection");
        check(amapDark.bg==0xFF101418&&amapDark.text!=amapLight.text,"Amap has a separate dark palette");
        restored.pureBlack=true;check(new AppearancePalette(restored,true,0).bg==0xFF000000,"Amap honors pure black mode");
        restored.selectColor(0);check(new AppearancePalette(restored,false,0xFFFF0000).accent!=new AppearancePalette(restored,false,0xFF0000FF).accent,"Amap also supports system accent");
        check(AppearanceConfig.parse("{\"skin\":\"unknown\"}").skin.equals("classic"),"unknown skins recover classic without corrupting preferences");
        System.out.println("Appearance: "+count+" assertions passed");
    }
}
