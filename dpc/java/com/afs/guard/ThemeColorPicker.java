package com.afs.guard;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** Local draft color, shared by grid/spectrum/HSV/hex controls; only confirmation writes settings. */
final class ThemeColorPicker extends Dialog {
    private final Activity owner;
    private final Ui ui;
    private final IntConsumer confirmed;
    private final float[] hsv=new float[3];
    private final SeekBar[] sliders=new SeekBar[3];
    private final TextView[] numbers=new TextView[3];
    private final TextView[] segments=new TextView[3];
    private EditText hex;
    private TextView confirm;
    private ThemePreviewView light,dark;
    private LinearLayout controls;
    private ColorField field;
    private int color,mode;
    private boolean updating,committed;

    static ThemeColorPicker show(Activity activity,int initial,IntConsumer confirmed){
        ThemeColorPicker p=new ThemeColorPicker(activity,initial,confirmed);p.show();return p;
    }
    private ThemeColorPicker(Activity owner,int initial,IntConsumer confirmed){
        super(owner);this.owner=owner;this.confirmed=confirmed;ui=new Ui(owner);color=initial|0xFF000000;Color.colorToHSV(color,hsv);
        requestWindowFeature(Window.FEATURE_NO_TITLE);setOwnerActivity(owner);
        LinearLayout root=new LinearLayout(owner);root.setOrientation(LinearLayout.VERTICAL);root.setBackground(ui.round(ui.bg(),28));root.setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(24));
        LinearLayout header=row();TextView close=text("×",28,ui.text());close.setContentDescription("关闭选色器");close.setGravity(Gravity.CENTER);header.addView(close,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));close.setOnClickListener(v->dismiss());
        TextView title=text("自定义颜色",20,ui.text());title.setTypeface(Typeface.DEFAULT_BOLD);title.setGravity(Gravity.CENTER);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        confirm=text("✓",26,ui.accent());confirm.setContentDescription("确定颜色");confirm.setGravity(Gravity.CENTER);header.addView(confirm,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));confirm.setOnClickListener(v->commit());root.addView(header);
        ScrollView scroll=new ScrollView(owner);scroll.setClipToPadding(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=new LinearLayout(owner);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(0,ui.dp(12),0,ui.dp(12));scroll.addView(body);
        LinearLayout preview=row();preview.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));preview.setBackground(ui.round(ui.surface(),22));
        AppearanceConfig a=ThemeStore.load(owner);a.selectColor(color);
        light=new ThemePreviewView(owner,a,false,false);dark=new ThemePreviewView(owner,a,true,false);
        preview.addView(light,new LinearLayout.LayoutParams(ui.dp(68),ui.dp(96)));LinearLayout.LayoutParams darkLp=new LinearLayout.LayoutParams(ui.dp(68),ui.dp(96));darkLp.leftMargin=ui.dp(8);preview.addView(dark,darkLp);
        LinearLayout value=row();TextView hash=text("#",18,ui.text2());value.addView(hash);hex=new EditText(owner);hex.setSingleLine();hex.setTextSize(16);hex.setTypeface(Typeface.MONOSPACE);hex.setTextColor(ui.text());hex.setSelectAllOnFocus(true);hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6),new InputFilter.AllCaps()});hex.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD|android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);hex.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_FORCE_ASCII|android.view.inputmethod.EditorInfo.IME_ACTION_DONE);hex.setContentDescription("六位十六进制颜色");hex.setText(AppearanceConfig.hex(color));ThemeStore.excludeFont(hex);value.addView(hex,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams valueLp=new LinearLayout.LayoutParams(0,-2,1);valueLp.leftMargin=ui.dp(10);preview.addView(value,valueLp);body.addView(preview);
        LinearLayout tabs=row();tabs.setPadding(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3));tabs.setBackground(ui.round(ui.surface(),99));LinearLayout.LayoutParams tabLp=new LinearLayout.LayoutParams(-1,ui.dp(44));tabLp.topMargin=ui.dp(16);body.addView(tabs,tabLp);
        for(int i=0;i<3;i++){final int index=i;TextView t=text(new String[]{"网格","光谱","滑块"}[i],15,ui.text2());t.setGravity(Gravity.CENTER);t.setOnClickListener(v->selectMode(index));tabs.addView(t,new LinearLayout.LayoutParams(0,-1,1));segments[i]=t;}
        controls=new LinearLayout(owner);controls.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams controlsLp=new LinearLayout.LayoutParams(-1,-2);controlsLp.topMargin=ui.dp(12);body.addView(controls,controlsLp);
        hex.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(updating)return;try{int value=AppearanceConfig.parseHex(s.toString());hex.setError(null);confirm.setEnabled(true);confirm.setAlpha(1);setColor(value,false);}catch(IllegalArgumentException e){confirm.setEnabled(false);confirm.setAlpha(.35f);if(s.length()==6)hex.setError("请输入有效的六位颜色值");}}public void afterTextChanged(Editable e){}});
        setContentView(root);selectMode(0);
        setOnShowListener(d->{Window w=getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(-1,(int)(owner.getResources().getDisplayMetrics().heightPixels*.9f));w.setGravity(Gravity.BOTTOM);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.32f);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}ThemeStore.typography(root);});
    }
    int color(){return color;}
    private TextView text(String text,float size,int color){TextView t=new TextView(owner);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(owner);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private void commit(){if(committed||!confirm.isEnabled())return;committed=true;((InputMethodManager)owner.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(hex.getWindowToken(),0);int selected=color;dismiss();owner.getWindow().getDecorView().post(()->{if(!owner.isDestroyed()&&!owner.isFinishing())confirmed.accept(selected);});}
    private void selectMode(int next){
        mode=next;controls.removeAllViews();field=null;
        for(int i=0;i<3;i++){segments[i].setBackground(i==mode?ui.round(ui.accent(),99):null);segments[i].setTextColor(i==mode?ui.onAccent():ui.text2());segments[i].setSelected(i==mode);}
        if(mode<2){field=new ColorField(mode==0);controls.addView(field,new LinearLayout.LayoutParams(-1,ui.dp(mode==0?280:260)));}
        else for(int i=0;i<3;i++){
            final int channel=i;LinearLayout line=row();line.addView(text(new String[]{"色相","饱和度","亮度"}[i],15,ui.text2()),new LinearLayout.LayoutParams(0,-2,1));numbers[i]=text("",15,ui.text());line.addView(numbers[i]);controls.addView(line);
            SeekBar slider=new SeekBar(owner);sliders[i]=slider;slider.setMax(i==0?360:100);slider.setProgress(Math.round(hsv[i]*(i==0?1:100)));styleSlider(slider,ui);controls.addView(slider,new LinearLayout.LayoutParams(-1,ui.dp(52)));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int value,boolean user){if(updating||!user)return;hsv[channel]=channel==0?value:value/100f;setColor(Color.HSVToColor(hsv),true);}});
        }
        updateControls();ThemeStore.typography(controls);
    }
    private void setColor(int value,boolean updateHex){
        color=value|0xFF000000;Color.colorToHSV(color,hsv);light.color(color);dark.color(color);
        updating=true;if(updateHex){hex.setText(AppearanceConfig.hex(color));hex.setError(null);confirm.setEnabled(true);confirm.setAlpha(1);}updating=false;
        updateControls();if(field!=null)field.invalidate();
    }
    private void updateControls(){
        if(mode!=2)return;updating=true;
        for(int i=0;i<3;i++){
            int value=Math.round(hsv[i]*(i==0?1:100));sliders[i].setProgress(value);numbers[i].setText(value+(i==0?"°":"%"));sliders[i].setContentDescription(new String[]{"色相","饱和度","亮度"}[i]+"，"+value+(i==0?"度":"%"));
            int[] colors=i==0?new int[]{Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED}:i==1?new int[]{Color.HSVToColor(new float[]{hsv[0],0,hsv[2]}),Color.HSVToColor(new float[]{hsv[0],1,hsv[2]})}:new int[]{Color.BLACK,Color.HSVToColor(new float[]{hsv[0],hsv[1],1})};
            GradientDrawable track=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,colors);track.setCornerRadius(ui.dp(8));track.setSize(1,ui.dp(16));sliders[i].setProgressDrawable(track);
            sliders[i].setProgressTintList(null);
        }
        updating=false;
    }
    static void styleSlider(SeekBar slider,Ui ui){GradientDrawable thumb=ui.round(ui.accent(),3);thumb.setSize(ui.dp(4),ui.dp(36));slider.setThumb(thumb);slider.setThumbOffset(ui.dp(2));slider.setSplitTrack(true);slider.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.accent()));}
    private final class ColorField extends View {
        private final boolean grid;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect=new RectF();
        ColorField(boolean grid){super(owner);this.grid=grid;setContentDescription(grid?"颜色网格，滑动或点按选择":"颜色光谱，滑动或点按选择");setFocusable(true);if(Build.VERSION.SDK_INT<28)setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        private int gridColor(int col,int row){if(row==0){int v=Math.round(255*(1-col/11f));return Color.rgb(v,v,v);}float saturation=row<5?.2f+row*.16f:1;float value=row<5?1:1-(row-4)*.13f;return Color.HSVToColor(new float[]{col*30,saturation,value});}
        @Override protected void onDraw(Canvas canvas){
            float w=getWidth(),h=getHeight();paint.setStyle(Paint.Style.FILL);paint.setShader(null);
            if(grid){float cellW=w/12,cellH=h/10;for(int y=0;y<10;y++)for(int x=0;x<12;x++){int fill=gridColor(x,y);paint.setColor(fill);canvas.drawRect(x*cellW,y*cellH,(x+1)*cellW+1,(y+1)*cellH+1,paint);if(fill==color){paint.setColor(hct.Hct.fromInt(color).getTone()>60?Color.BLACK:Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(ui.dp(2));rect.set(x*cellW+ui.dp(1),(y*cellH)+ui.dp(1),(x+1)*cellW-ui.dp(1),(y+1)*cellH-ui.dp(1));canvas.drawRect(rect,paint);paint.setStyle(Paint.Style.FILL);}}}
            else {
                Shader hue=new LinearGradient(0,0,w,0,new int[]{Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED},null,Shader.TileMode.CLAMP);
                Shader shade=new LinearGradient(0,0,0,h,new int[]{Color.WHITE,0x00FFFFFF,Color.BLACK},new float[]{0,.5f,1},Shader.TileMode.CLAMP);
                paint.setShader(new ComposeShader(hue,shade,PorterDuff.Mode.SRC_OVER));rect.set(0,0,w,h);canvas.drawRoundRect(rect,ui.dp(12),ui.dp(12),paint);paint.setShader(null);
                float x=hsv[0]/360*w,y=hsv[2]<1?(1-hsv[2]/2)*h:hsv[1]/2*h;paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(ui.dp(2.5f));canvas.drawCircle(x,y,ui.dp(8),paint);paint.setStyle(Paint.Style.FILL);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e){int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP){getParent().requestDisallowInterceptTouchEvent(true);float x=Math.max(0,Math.min(.9999f,e.getX()/Math.max(1,getWidth()))),y=Math.max(0,Math.min(.9999f,e.getY()/Math.max(1,getHeight())));if(grid)setColor(gridColor((int)(x*12),(int)(y*10)),true);else setColor(Color.HSVToColor(new float[]{x*360,y<.5f?y*2:1,y<.5f?1:2*(1-y)}),true);if(action==MotionEvent.ACTION_UP){performClick();getParent().requestDisallowInterceptTouchEvent(false);}return true;}if(action==MotionEvent.ACTION_CANCEL)getParent().requestDisallowInterceptTouchEvent(false);return true;}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
