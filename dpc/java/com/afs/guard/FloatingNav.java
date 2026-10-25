package com.afs.guard;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Three live glass layers, velocity-preserving springs, and release-to-select navigation. */
public final class FloatingNav extends FrameLayout {
    static final int SHADOW_ROOM_DP = 16;
    private static final int[] ORDER = {3,0,4,1,2};
    private static final String[] LABELS = {"记忆", "刷题", "冻结", "打卡", "守卫"};
    private static final Class<?>[] PAGES = {MemoryActivity.class, QuizActivity.class,
            FreezeBoardActivity.class, CheckinActivity.class, MainActivity.class};
    private static int transitionFrom=-1, transitionTo=-1;
    private static long transitionAt, switchingUntil;

    private final Activity activity;
    private final Ui ui;
    private final AppearanceConfig appearance;
    private final View source;
    private final FrameLayout host, panel;
    private final LinearLayout tabs;
    private final Material material;
    private final Icon[] icons=new Icon[5];
    private final TextView[] labels=new TextView[5];
    private final int selected;
    private final DockSpring position=new DockSpring(0,300,.55);
    private final DockSpring lift=new DockSpring(0,576,.9);
    private final DockSpring swell=new DockSpring(0,311,.593);
    private final DockSpring jelly=new DockSpring(0,100,.4);
    private final DockSpring pull=new DockSpring(0,400,.8);
    private final DockSpring glow=new DockSpring(0,576,1);
    private final RectF lensRect=new RectF(), barOnWindow=new RectF(), lensOnWindow=new RectF();
    private NavGlassRenderer renderer;
    private boolean rendererAttempted, seeded, glassVisible, pressing, dragging, navigating, framePosted;
    private int pointer=-1, hoverPage, visualPage=-2;
    private float pitch, lensWidth, firstCenter, pressX, lightX, pullPixels;
    private VelocityTracker velocity;
    private long frameTime, playedTransition;
    private final ViewTreeObserver.OnPreDrawListener beforeDraw=()->{playTransition();renderGlass();return true;};
    private final Choreographer.FrameCallback frame=this::step;

    public static View wrap(Activity activity, View content, int selected) {
        fullscreen(activity);
        Ui ui=new Ui(activity);
        if(content instanceof ScrollView && ((ScrollView)content).getChildCount()>0) {
            View body=((ScrollView)content).getChildAt(0);
            body.setPadding(body.getPaddingLeft(),body.getPaddingTop(),body.getPaddingRight(),body.getPaddingBottom()+ui.dp(94));
            ((ScrollView)content).setClipToPadding(false);
        } else if(content instanceof LinearLayout) {
            content.setPadding(content.getPaddingLeft(),content.getPaddingTop(),content.getPaddingRight(),content.getPaddingBottom()+ui.dp(94));
        }
        FrameLayout host=new FrameLayout(activity);
        host.setBackgroundColor(ui.bg());host.setClipChildren(false);host.setClipToPadding(false);
        host.addView(content,new LayoutParams(-1,-1));
        FloatingNav nav=new FloatingNav(activity,content,host,selected);
        LiveNavWindow.attach(activity,host,nav);
        return host;
    }

    public static void fullscreen(Activity activity) {
        android.view.Window w=activity.getWindow();
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                |android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0);
        if(Build.VERSION.SDK_INT>=28) {
            android.view.WindowManager.LayoutParams lp=w.getAttributes();
            lp.layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private FloatingNav(Activity activity,View source,FrameLayout host,int selected) {
        super(activity);this.activity=activity;this.source=source;this.host=host;this.selected=selected;ui=new Ui(activity);appearance=ThemeStore.load(activity);
        setClipChildren(false);setClipToPadding(false);
        panel=new FrameLayout(activity);panel.setClipChildren(false);panel.setClipToPadding(false);
        LayoutParams panelLp=new LayoutParams(-1,ui.dp(62));
        panelLp.setMargins(ui.dp(shadowRoomDp()),ui.dp(shadowRoomDp()),ui.dp(shadowRoomDp()),0);
        addView(panel,panelLp);
        material=new Material();panel.addView(material,new LayoutParams(-1,-1));
        tabs=new LinearLayout(activity);tabs.setClipChildren(false);tabs.setClipToPadding(false);
        panel.addView(tabs,new LayoutParams(-1,-1));
        for(int index:ORDER) {
            LinearLayout tab=new LinearLayout(activity);
            tab.setGravity(Gravity.CENTER);tab.setOrientation(LinearLayout.VERTICAL);
            tab.setContentDescription(LABELS[index]);tab.setFocusable(true);
            Icon icon=new Icon(activity,index,ui.text2());icons[index]=icon;
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            tab.addView(icon,new LinearLayout.LayoutParams(ui.dp(28),ui.dp(28)));
            TextView label=new TextView(activity);labels[index]=label;
            label.setText(LABELS[index]);label.setTextSize(ui.amap?11:10);label.setIncludeFontPadding(false);
            SwissTypography.role(label,SwissTypography.Role.NAV);
            label.setGravity(Gravity.CENTER);label.setSingleLine(true);
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            label.setMinHeight(ui.dp(14.5f));
            LinearLayout.LayoutParams textLp=new LinearLayout.LayoutParams(-2,-2);textLp.topMargin=ui.dp(2);
            tab.addView(label,textLp);tabs.addView(tab,new LinearLayout.LayoutParams(0,-1,1));
            tab.setOnClickListener(v->navigate(index));
        }
        updateColors(selected);
        ThemeStore.typography(tabs);
        addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->layoutDock());
        if(selected<0 && activity instanceof MainActivity && motion()) {
            setAlpha(0);postDelayed(()->animate().alpha(1).setDuration(150).start(),650);
        }
    }

    private static int slotOf(int page) {for(int i=0;i<ORDER.length;i++)if(ORDER[i]==page)return i;return -1;}
    private static float clamp(float v,float lo,float hi) {return Math.max(lo,Math.min(hi,v));}
    private float value(DockSpring s) {return (float)s.value;}
    private boolean motion() {
        return !getContext().getSharedPreferences(Ui.SP,0).getBoolean("reduce_motion",false)
                &&(Build.VERSION.SDK_INT<26||ValueAnimator.areAnimatorsEnabled());
    }
    private void layoutDock() {
        float span=getWidth()-ui.dp(shadowRoomDp()*2+8);
        if(span<=0)return;
        float even=span/ORDER.length;
        lensWidth=appearance.floating?Math.min(span,Math.max(ui.dp(84),even+ui.dp(8))):even;
        pitch=!appearance.floating||even+ui.dp(8)>=ui.dp(84)?even:(span-lensWidth)/(ORDER.length-1);
        float inset=(lensWidth-pitch)/2;
        firstCenter=ui.dp(4)+inset+pitch/2;
        int pad=Math.round(ui.dp(4)+inset);
        tabs.setPadding(pad,ui.dp(4),pad,ui.dp(4));
        if(!seeded){position.snap(Math.max(0,slotOf(selected)));seeded=true;}
        updateGeometry();post(this::playTransition);
    }

    private float localX(MotionEvent e) {
        return (e.getX()-panel.getX()-panel.getPivotX())/panel.getScaleX()+panel.getPivotX();
    }
    private float pointPosition(float x) {return (x-firstCenter)/Math.max(1,pitch);}
    private int indexAt(float p) {return Math.max(0,Math.min(ORDER.length-1,Math.round(p)));}

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN) {
            return e.getY()>=ui.dp(Math.max(0,shadowRoomDp()-8))&&e.getY()<=ui.dp(shadowRoomDp()+62+(appearance.floating?8:0));
        }
        return pressing;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            if(SystemClock.uptimeMillis()<switchingUntil)return true;
            pointer=e.getPointerId(0);pressing=true;dragging=false;
            if(velocity!=null)velocity.recycle();velocity=VelocityTracker.obtain();velocity.addMovement(e);
            pressX=localX(e);lightX=pressX;hoverPage=ORDER[indexAt(pointPosition(pressX))];
            position.tune(300,.55);if(appearance.floating)position.target=slotOf(hoverPage);
            lift.target=appearance.floating?1:0;swell.tune(311,.593);swell.target=appearance.floating?1:0;glow.tune(576,1);glow.target=1;
            updateColors(appearance.floating?hoverPage:selected);kick();return true;
        }
        if(!pressing)return false;
        if(velocity!=null)velocity.addMovement(e);
        if(action==MotionEvent.ACTION_MOVE) {
            int pi=e.findPointerIndex(pointer);if(pi<0){release(false,0);return true;}
            float x=localX(e);lightX=x;
            if(!dragging && Math.abs(x-pressX)>ViewConfiguration.get(getContext()).getScaledTouchSlop())dragging=true;
            if(dragging&&appearance.floating) {
                float p=pointPosition(x), last=ORDER.length-1;
                float over=p-clamp(p,0,last);
                float rubber=(float)(Math.signum(over)*(1-1/(1+Math.abs(over)*.55))*.35);
                position.tune(1024,.9);position.target=clamp(p,0,last)+rubber;
                pull.target=rubber;
                int next=ORDER[indexAt(p)];
                if(next!=hoverPage){hoverPage=next;performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);updateColors(hoverPage);}
            }
            kick();return true;
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL
                ||(action==MotionEvent.ACTION_POINTER_UP&&e.getPointerId(e.getActionIndex())==pointer)) {
            float speed=0;
            if(velocity!=null){velocity.computeCurrentVelocity(1000);speed=velocity.getXVelocity(pointer)/panel.getScaleX();}
            release(action!=MotionEvent.ACTION_CANCEL&&(appearance.floating||!dragging),speed);return true;
        }
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}

    private void release(boolean commit,float speed) {
        if(!pressing)return;
        if(commit&&dragging) {
            int finger=slotOf(hoverPage);
            int next=indexAt((float)position.target+speed/Math.max(1,pitch)*.1f);
            next=Math.max(Math.max(0,finger-1),Math.min(Math.min(ORDER.length-1,finger+1),next));
            if(ORDER[next]!=hoverPage)performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            hoverPage=ORDER[next];
        }
        pressing=false;dragging=false;pointer=-1;
        if(velocity!=null){velocity.recycle();velocity=null;}
        int target=commit?hoverPage:selected;
        position.tune(300,.55);position.target=Math.max(0,slotOf(target));
        lift.target=0;swell.tune(144,.55);swell.target=0;pull.target=0;
        glow.tune(100,1);glow.target=0;
        updateColors(target);kick();
        if(commit){performClick();navigate(target);}
    }

    private void updateColors(int page) {
        if(visualPage==page)return;visualPage=page;
        for(int i=0;i<5;i++){int color=i==page?ui.accent():(ui.amap?ui.text():ui.text2());icons[i].color=color;icons[i].invalidate();labels[i].setTextColor(color);}
    }
    private void kick() {
        if(!motion()) {
            for(DockSpring s:new DockSpring[]{position,lift,swell,jelly,pull,glow})s.snap(s.target);
            updateGeometry();return;
        }
        if(!framePosted&&isAttachedToWindow()&&glassVisible){framePosted=true;Choreographer.getInstance().postFrameCallback(frame);}
    }
    private void step(long now) {
        framePosted=false;
        if(!glassVisible||!isAttachedToWindow()){frameTime=0;return;}
        double dt=frameTime==0?1.0/60:Math.min(.1,(now-frameTime)/1_000_000_000.0);frameTime=now;
        position.advance(dt);lift.advance(dt);swell.advance(dt);pull.advance(dt);glow.advance(dt);
        jelly.target=Math.min(.16,Math.abs(position.velocity)*.014);jelly.advance(dt);
        updateGeometry();
        if(position.moving()||lift.moving()||swell.moving()||pull.moving()||glow.moving()||jelly.moving())kick();
        else {frameTime=0;if(!pressing)updateColors(selected);}
    }
    private void updateGeometry() {
        if(pitch<=0)return;
        float raised=ui.amap?0:clamp(value(lift),0,1), stretch=ui.amap?0:clamp(value(jelly),-.12f,.18f);
        float width=(lensWidth+ui.dp(16)*raised)*(1+stretch);
        float height=appearance.floating?(ui.dp(54)+ui.dp(16)*raised)*(1-stretch*1.4f):ui.dp(30);
        if(ui.amap&&appearance.floating)width=pitch-ui.dp(2);
        if(!appearance.floating)width=Math.min(ui.dp(64),pitch-ui.dp(8));
        float cx=firstCenter+value(position)*pitch,cy=ui.dp(appearance.floating?31:22);
        lensRect.set(cx-width/2,cy-height/2,cx+width/2,cy+height/2);
        float scale=appearance.floating&&!ui.amap?1+value(swell)*Math.min(.25f,ui.dp(16)/(float)Math.max(1,panel.getWidth())):1;
        pullPixels=ui.amap?0:value(pull)*ui.dp(38);
        panel.setScaleX(scale*(1+Math.abs(pullPixels)/Math.max(1,panel.getWidth())*.5f));
        panel.setScaleY(scale);panel.setTranslationX(pullPixels);
        material.invalidate();renderGlass();
    }
    boolean gpuGlass() {return renderer!=null&&renderer.active();}
    boolean floating(){return appearance.floating;}
    boolean hasGlass(){return appearance.floating&&!"none".equals(appearance.glass);}
    int shadowRoomDp(){return appearance.floating?SHADOW_ROOM_DP:0;}
    void glassVisible(boolean visible) {
        if(glassVisible==visible){if(visible)renderGlass();return;}
        glassVisible=visible;
        if(renderer!=null)renderer.setVisible(visible);
        if(visible){frameTime=0;playTransition();updateGeometry();kick();}
        else {
            Choreographer.getInstance().removeFrameCallback(frame);framePosted=false;frameTime=0;
            if(pressing)release(false,0);
        }
    }
    private void renderGlass() {
        if(!glassVisible||panel.getWidth()==0)return;
        if(hasGlass()&&renderer==null&&!rendererAttempted&&host.isHardwareAccelerated()) {
            rendererAttempted=true;renderer=NavGlassRenderer.create(activity,host,this,tabs,ui.dark);
            if(renderer!=null){renderer.setVisible(true);post(()->LiveNavWindow.refresh(activity));}
        }
        if(renderer==null)return;
        float sx=panel.getScaleX(),sy=panel.getScaleY();
        float ox=panel.getX()+panel.getPivotX()*(1-sx),oy=panel.getY()+panel.getPivotY()*(1-sy);
        barOnWindow.set(ox,oy,ox+panel.getWidth()*sx,oy+panel.getHeight()*sy);
        lensOnWindow.set(ox+lensRect.left*sx,oy+lensRect.top*sy,ox+lensRect.right*sx,oy+lensRect.bottom*sy);
        boolean was=renderer.active();renderer.update(barOnWindow,lensOnWindow,lensRect,ui.amap?0:clamp(value(lift),0,1));
        if(was&&!renderer.active())post(()->LiveNavWindow.refresh(activity));
    }

    private void navigate(int index) {
        if(index<0||index==selected||navigating||SystemClock.uptimeMillis()<switchingUntil)return;
        navigating=true;
        transitionFrom=selected;transitionTo=index;transitionAt=SystemClock.uptimeMillis();
        switchingUntil=transitionAt+160;open(activity,index);postDelayed(()->navigating=false,180);
    }
    public static void open(Activity activity,int index) {
        Intent intent=new Intent(activity,PAGES[index]);intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if(index==3||index==4)intent.putExtra("from_bottom_nav",true);
        activity.startActivity(intent);activity.overridePendingTransition(0,0);
    }
    /**
     * 跨 Activity 切页只做两件事：底栏选中态一次性归位 + 新页面短距离滑入。
     *
     * 旧实现会把上一个页面的整棵 View 树挪进新窗口做双页对滑 —— 每帧要画两整页，
     * 还要跨窗口挂载/归还 View，是切换卡顿的主因；这里不再碰旧窗口的内容。
     * 底栏也不再逐帧跑弹簧（每帧都会重建整个窗口的 RenderEffect），直接落到选中位置。
     */
    private void playTransition() {
        if(transitionTo!=selected||playedTransition==transitionAt||SystemClock.uptimeMillis()-transitionAt>1500||pitch<=0)return;
        playedTransition=transitionAt;
        position.snap(Math.max(0,slotOf(selected)));
        lift.snap(0);swell.snap(0);jelly.snap(0);pull.snap(0);glow.snap(0);
        updateColors(selected);updateGeometry();
        enterContent(slotOf(transitionFrom)<slotOf(selected)?1:-1);
    }
    /** 只动新窗口的内容：滑动靠 translation（便宜），淡入用短 alpha；不涉及任何跨窗口操作。 */
    private void enterContent(int direction) {
        if(!(source.getParent() instanceof FrameLayout))return;
        if(!motion()){source.setTranslationX(0);source.setAlpha(1);return;}
        source.animate().cancel();
        if("fade".equals(appearance.animation)) {
            source.setTranslationX(0);
            source.setAlpha(.35f);
            source.animate().alpha(1f).setDuration(170)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();
            return;
        }
        source.setAlpha(1f);
        float distance=Math.min(ui.dp(72),Math.max(ui.dp(24),getWidth()*.09f));
        source.setTranslationX(direction*distance);
        source.animate().translationX(0f).setDuration(190)
                .setInterpolator(new android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE&&source!=null)post(this::playTransition);
    }
    @Override protected void onAttachedToWindow() {super.onAttachedToWindow();getViewTreeObserver().addOnPreDrawListener(beforeDraw);}
    @Override protected void onDetachedFromWindow() {
        glassVisible(false);
        if(getViewTreeObserver().isAlive())getViewTreeObserver().removeOnPreDrawListener(beforeDraw);
        super.onDetachedFromWindow();
    }

    private final class Material extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds=new RectF();
        Material(){super(activity);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c) {
            float raised=clamp(value(lift),0,1),h=getHeight(),w=getWidth();bounds.set(.5f,.5f,w-.5f,h-.5f);
            if(ui.amap){
                p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(ui.palette.glassTint);p.setAlpha(hasGlass()?("ultra".equals(appearance.glass)?210:235):255);c.drawRoundRect(bounds,appearance.floating?h/2:0,appearance.floating?h/2:0,p);p.setAlpha(255);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(ui.dp(.55f));p.setColor(ui.line());c.drawRoundRect(bounds,appearance.floating?h/2:0,appearance.floating?h/2:0,p);
                if(selected>=0||pressing){p.setStyle(Paint.Style.FILL);p.setColor(ui.palette.selection);c.drawRoundRect(lensRect,lensRect.height()/2,lensRect.height()/2,p);}return;
            }
            p.setShader(null);p.setStyle(Paint.Style.STROKE);
            if(!appearance.floating){p.setColor(ui.line());p.setStrokeWidth(ui.dp(.5f));c.drawLine(0,0,w,0,p);}
            else {
            for(int i=2;i>=1;i--){p.setColor(ui.dark?0x06000000:0x03000000);p.setStrokeWidth(ui.dp(i*2));c.drawRoundRect(bounds,h/2,h/2,p);}
            p.setStrokeWidth(ui.dp(.45f));
            p.setShader(new LinearGradient(0,0,0,h,new int[]{ui.dark?0x60FFFFFF:0x85FFFFFF,0x048A96A5,ui.dark?0x285C708C:0x208A96A5},new float[]{0,.5f,1},Shader.TileMode.CLAMP));
            c.drawRoundRect(bounds,h/2,h/2,p);p.setShader(null);
            }
            if(selected>=0||pressing) {
                p.setStyle(Paint.Style.FILL);p.setColor(ui.palette.selection);p.setAlpha(hasGlass()?(int)(217*(1-raised)):217);
                c.drawRoundRect(lensRect,lensRect.height()/2,lensRect.height()/2,p);p.setAlpha(255);
            }
            if(raised>0&&hasGlass()) {
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(ui.dp(.6f));
                p.setShader(new LinearGradient(0,lensRect.top,0,lensRect.bottom,new int[]{(int)(185*raised)<<24|0xFFFFFF,0x027A96B8,(int)(105*raised)<<24|0xFFFFFF},new float[]{0,.5f,1},Shader.TileMode.CLAMP));
                c.drawRoundRect(lensRect,lensRect.height()/2,lensRect.height()/2,p);p.setShader(null);
            }
            if(glow.value>.001) {
                p.setStyle(Paint.Style.FILL);
                p.setShader(new RadialGradient(lightX,h/2,ui.dp(28),new int[]{((int)(15*clamp(value(glow),0,1))<<24)|0xFFFFFF,0x00FFFFFF},null,Shader.TileMode.CLAMP));
                c.drawRoundRect(bounds,h/2,h/2,p);p.setShader(null);
            }
        }
    }

    private final class Icon extends View {
        private final int type;private int color;private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Activity c,int type,int color){super(c);this.type=type;this.color=color;}
        @Override protected void onDraw(Canvas canvas) {
            Canvas c=canvas;c.save();c.scale(getWidth()/28f,getHeight()/28f);
            if(ui.amap){AmapStyle.glyph(c,p,LABELS[type],color,type==visualPage);c.restore();return;}
            int inside=(((color>>16)&255)*.2126+((color>>8)&255)*.7152+(color&255)*.0722)>200?0xE0101318:0xDDF3F4F6;
            p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.4f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            if(type==0){p.setStyle(Paint.Style.FILL);c.drawRoundRect(7,5,24,24,3,3,p);p.setStyle(Paint.Style.STROKE);c.drawLine(4,19,4,4,p);c.drawLine(4,4,18,4,p);p.setColor(inside);c.drawLine(11,11,20,11,p);c.drawLine(11,16,18,16,p);}
            else if(type==1){p.setStyle(Paint.Style.FILL);c.drawRoundRect(5,3,23,25,3,3,p);p.setStyle(Paint.Style.STROKE);p.setColor(inside);c.drawLine(9,9,18,9,p);c.drawLine(9,13,16,13,p);Path path=new Path();path.moveTo(10,19);path.lineTo(13,22);path.lineTo(20,16);c.drawPath(path,p);}
            else if(type==2){for(int i=0;i<6;i++){c.save();c.rotate(i*60,14,14);c.drawLine(14,3,14,14,p);c.drawLine(10,6,14,9,p);c.drawLine(18,6,14,9,p);c.restore();}}
            else if(type==4){c.drawCircle(14,14,7,p);c.drawCircle(14,14,2.5f,p);for(int i=0;i<8;i++){c.save();c.rotate(i*45,14,14);c.drawLine(14,3,14,6,p);c.restore();}}
            else{c.drawCircle(14,14,11,p);Path path=new Path();path.moveTo(8,14);path.lineTo(12,18);path.lineTo(20,10);c.drawPath(path,p);}
            c.restore();
        }
    }
}
