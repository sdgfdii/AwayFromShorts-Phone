package com.afs.guard;

import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import java.util.Map;
import java.util.WeakHashMap;

/** Shared typographic grid. Colors, chosen fonts and semantic control alignment remain independent. */
final class SwissTypography {
    static final int EDGE=20,INSET=16,SECTION_GAP=24,ROW_GAP=12;
    enum Role { TITLE, SECTION, BODY, META, CAPTION, BUTTON, NUMBER, LABEL, NAV, KEEP }
    private static final Map<TextView,Role> roles=new WeakHashMap<>();
    private static final Map<TextView,State> states=new WeakHashMap<>();
    private static int observerId;
    private SwissTypography(){}
    static <T extends TextView> T role(T view,Role role){roles.put(view,role);states.remove(view);return view;}
    static void observe(View root){
        if(observerId==0)observerId=root.getResources().getIdentifier("afs_swiss_observer","id",root.getContext().getPackageName());
        if(observerId!=0&&root.getTag(observerId)!=null)return;
        if(observerId!=0)root.setTag(observerId,Boolean.TRUE);
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->ThemeStore.typography(root));
    }
    static void apply(View root){walk(root,false);}
    static void applyNode(View view,boolean customFont){if(view instanceof TextView)applyText((TextView)view,customFont);}
    private static void walk(View view,boolean customFont){
        customFont|=ThemeStore.fontExcluded(view);
        if(view instanceof TextView)applyText((TextView)view,customFont);
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)walk(g.getChildAt(i),customFont);}
    }
    private static boolean glyph(CharSequence value){
        String s=value==null?"":value.toString().trim();
        return !s.isEmpty()&&s.length()<=4&&!s.matches(".*[\\p{L}\\p{N}].*");
    }
    private static Role infer(TextView view){
        android.util.DisplayMetrics metrics=view.getResources().getDisplayMetrics();
        float sp=Build.VERSION.SDK_INT>=34?TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP,view.getTextSize(),metrics):view.getTextSize()/metrics.scaledDensity;
        if(view instanceof EditText||view instanceof CompoundButton)return Role.BODY;
        if(view instanceof Button)return Role.BUTTON;
        int gravity=view.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK;
        if(view.isClickable()&&gravity==Gravity.CENTER_HORIZONTAL&&sp<22)return Role.BUTTON;
        if(sp>=36&&view.getText().toString().matches("[+\\-\\d.,% /]+"))return Role.NUMBER;
        if(sp>=22)return Role.TITLE;
        if(sp>=17)return Role.SECTION;
        if(sp>=14)return Role.BODY;
        if(sp>=12)return Role.META;
        return Role.CAPTION;
    }
    private static void applyText(TextView view,boolean customFont){
        Role explicit=roles.get(view);if(explicit==Role.KEEP||(explicit==null&&glyph(view.getText())))return;
        boolean ripple=Ui.rippleMode(view.getContext());
        State state=states.get(view);
        if(state==null||state.ripple!=ripple){state=new State(explicit==null?infer(view):explicit,ripple);states.put(view,state);}
        if(state.applied&&state.lineApplied&&(customFont||view.getTypeface()==state.face))return;
        android.util.DisplayMetrics metrics=view.getResources().getDisplayMetrics();
        int line=Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,state.line,metrics));float size=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,state.size,metrics);
        if(!state.applied){
            if(Math.abs(view.getTextSize()-size)>.2f)view.setTextSize(TypedValue.COMPLEX_UNIT_SP,state.size);
            view.setIncludeFontPadding(false);view.setLetterSpacing(state.role==Role.LABEL&&!state.ripple?.08f:0);
            if(Build.VERSION.SDK_INT>=28)view.setFallbackLineSpacing(true);
            if(state.role==Role.BODY){view.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);}
            if(state.role==Role.TITLE||state.role==Role.SECTION||state.role==Role.LABEL){view.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);view.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);}
            if(state.role==Role.BUTTON){view.setGravity(Gravity.CENTER);view.setMinHeight(Math.max(view.getMinimumHeight(),Math.round((state.ripple?52:44)*view.getResources().getDisplayMetrics().density)));}
            if(state.role==Role.NUMBER)view.setFontFeatureSettings("'tnum' 1");
            state.applied=true;
        }
        // Changes in selected family are handled by ThemeStore before this pass.
        if(!customFont&&view.getTypeface()!=state.face){
            Typeface face=view.getTypeface()==null?Typeface.DEFAULT:view.getTypeface();
            face=Build.VERSION.SDK_INT>=28?Typeface.create(face,state.weight,false):Typeface.create(face,state.weight>=600?Typeface.BOLD:Typeface.NORMAL);
            view.setTypeface(face);state.face=face;state.lineApplied=false;
        }
        if(!state.lineApplied){
            int natural=(int)Math.ceil(view.getPaint().getFontMetrics().descent-view.getPaint().getFontMetrics().ascent);
            view.setLineSpacing(Math.max(0,line-natural),1);state.lineApplied=true;
        }
    }
    private static final class State {
        final Role role;final float size,line;final int weight;final boolean ripple;boolean applied,lineApplied;Typeface face;
        State(Role role,boolean ripple){this.role=role;this.ripple=ripple;
            if(ripple){
                // 涟漪风格：标题更大更重，正文放宽行距，说明行抬到 13sp。
                switch(role){
                    case TITLE:size=30;line=38;weight=700;break;
                    case SECTION:size=19;line=27;weight=600;break;
                    case BODY:size=15;line=24;weight=400;break;
                    case META:size=13;line=20;weight=400;break;
                    case CAPTION:size=11;line=16;weight=400;break;
                    case BUTTON:size=16;line=22;weight=600;break;
                    case NUMBER:size=40;line=50;weight=600;break;
                    case LABEL:size=12;line=18;weight=500;break;
                    case NAV:size=11;line=16;weight=500;break;
                    default:size=15;line=24;weight=400;
                }
                return;
            }
            switch(role){
                case TITLE:size=28;line=36;weight=600;break;
                case SECTION:size=18;line=26;weight=500;break;
                case BODY:size=15;line=24;weight=400;break;
                case META:size=12;line=18;weight=400;break;
                case CAPTION:size=11;line=16;weight=400;break;
                case BUTTON:size=14;line=20;weight=500;break;
                case NUMBER:size=40;line=48;weight=500;break;
                case LABEL:size=11;line=16;weight=500;break;
                case NAV:size=11;line=16;weight=500;break;
                default:size=15;line=24;weight=400;
            }
        }
    }
}
