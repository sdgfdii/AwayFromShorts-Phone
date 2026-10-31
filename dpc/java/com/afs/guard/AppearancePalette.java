package com.afs.guard;

import hct.Hct;

/** Native HCT roles for the four theme styles, with independent semantic success/error colors. */
public final class AppearancePalette {
    public int bg,surface,surface2,line,text,text2,text3,accent,onAccent,selection,glassTint;
    public AppearancePalette(AppearanceConfig c,boolean dark,int systemSeed){
        int seed=c.selectedSeed()==0?systemSeed:c.selectedSeed();Hct hct=Hct.fromInt(seed);
        if(c.ripple()){ripple(seed,dark);return;}
        boolean mono="monochrome".equals(c.style),soft="soft".equals(c.style),tinted="tinted".equals(c.style);
        double hue=hct.getHue(),chroma=hct.getChroma();
        double primaryChroma=mono?0:soft?36:chroma;
        double primaryTone=mono?(dark?100:0):soft?(dark?80:40)
                :dark?limit(hct.getTone()+3,50,75):limit(hct.getTone(),35,60);
        accent=c.amap()&&!soft&&!mono?seed:Hct.from(hue,primaryChroma,primaryTone).toInt();
        onAccent=(c.amap()&&!soft&&!mono?hct.getTone():primaryTone)<=60?0xFFFFFFFF:Hct.from(hue,primaryChroma,10).toInt();
        double neutral=mono?0:tinted?Math.min(chroma/6,10):dark?3:1.5;
        double lower=dark&&c.pureBlack?6:0;
        bg=dark&&c.pureBlack?0xFF000000:c.amap()?(dark?0xFF101418:0xFFF5F6F7):tone(hue,neutral,dark?6:96);
        surface=c.amap()?(dark?(c.pureBlack?0xFF111418:0xFF1C2229):0xFFFFFFFF):tone(hue,neutral,dark?12-lower:100);
        surface2=c.amap()?(dark?0xFF282F38:0xFFF0F2F4):tone(hue,neutral,dark?17-lower:93);
        line=c.amap()?(dark?0xFF343C46:0xFFE5E7EB):tone(hue,neutral,dark?25-lower:88);
        text=c.amap()?(dark?0xFFF4F6F8:0xFF15171A):tone(hue,mono?0:4,dark?94:10);
        text2=c.amap()?(dark?0xFFB0B7C1:0xFF626971):tone(hue,mono?0:4,dark?68:43);
        text3=c.amap()?(dark?0xFF87919E:0xFF838B94):tone(hue,mono?0:4,dark?53:56);
        selection=c.amap()?(dark?0xFF333B46:0xFFE8E9EB):tone(hue,Math.min(primaryChroma,48),dark?8:90);
        glassTint=c.amap()?(dark?0xFF222A34:0xFFF7F8FA):tone(hue,neutral,dark?20-lower:96);
    }
    /** 涟漪风格：中性色是采样自参考稿的固定值，强调色跟随用户选的种子色。 */
    private void ripple(int seed,boolean dark){
        if(dark){
            bg=0xFF1B2432;surface=0xFF232F40;surface2=0xFF2A3648;line=0xFF33404F;
            text=0xFFE9EFF8;text2=0xFFA9B6C6;text3=0xFF7C8A9C;
            Hct s=Hct.fromInt(seed);accent=Hct.from(s.getHue(),Math.max(28,s.getChroma()),72).toInt();
            onAccent=0xFF101820;selection=0xFF2F3D52;glassTint=0xFF25303F;
        }else{
            bg=0xFFF9FAFD;surface=0xFFFFFFFF;surface2=0xFFEEF1F6;line=0xFFE7EBF1;
            text=0xFF36475A;text2=0xFF61718A;text3=0xFF8591A5;
            accent=seed;onAccent=0xFFFFFFFF;selection=0xFFE3EBF5;glassTint=0xFFFAFCFF;
        }
    }
    private static double limit(double v,double min,double max){return Math.max(min,Math.min(max,v));}
    private static int tone(double h,double c,double t){return Hct.from(h,c,Math.max(0,t)).toInt();}
}
