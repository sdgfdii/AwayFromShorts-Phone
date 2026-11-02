package com.afs.guard;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A time-first freeze board. App tiles move between persistent schedule containers. */
public class FreezeBoardActivity extends ThemedActivity {
    private static final int PICK = 71;
    private static final int REQ_NET = 72;
    private static final int REQ_VPN = 73;
    private static final int REQ_KEY = 74;
    private static final int REQ_QUOTA = 75;
    private org.json.JSONObject quotaDraftGroup;
    private Set<String> quotaDraftChosen;
    private boolean quotaDraftShared, quotaDraftGrowOnly;
    private String quotaDraftTitle, quotaDraftMinutes;
    private Ui look;
    private LinearLayout screen, slotsView, library;
    private ScrollView scroll;
    private final Map<String,View> slotViews=new java.util.HashMap<>();
    private LinearLayout usageContainer;
    private List<FreezeSlotStore.Slot> slots;
    private Map<String, Rule> rules;
    private String ruleSnapshot = "";
    private String dailySnapshot="";
    private Calendar statusNow;
    private Set<String> statusKeys;
    private Map<String, Rule> statusRules;
    private boolean statusHold, statusTaboo, statusReward;
    private int statusTick;
    private boolean statusWorkerBusy;
    private String reasonRevision="";
    private Map<String,Integer> reasonCache=java.util.Collections.emptyMap();
    private Map<String,Boolean> frozenCache=java.util.Collections.emptyMap();
    private String reasonLabel(int reason){return reason<0?T.s("正在检查状态"):Guard.blockReasonText(reason);}
    private TextView gateNote;   // 学习联动还差什么（点一下跳到对应页面）
    private TextView netHint;    // 底部「断网应用设置」那行小字，运行状态会变，单独留个引用
    private final Handler handler = new Handler();
    private View dragSource;
    private boolean dragging;
    private int dragScreenY;
    private Runnable pendingDrop;
    private String landed;
    private int applies;
    private final List<FreezeTimelineView> timelines = new ArrayList<>();
    private final List<LibraryApp> libraryApps = new ArrayList<>();
    private final Set<String> availableApps = new LinkedHashSet<>();
    private LinearLayout libraryContent;
    private TextView libraryHandle;
    private TextView libraryLabel;
    private HorizontalScrollView libraryTray;
    private LinearLayout libraryTrayApps;
    private final Map<String,android.widget.FrameLayout> libraryCells=new java.util.LinkedHashMap<>();
    private int libraryRenderSeq;
    private final List<String> libraryCatalog=new ArrayList<>();
    /** 应用库分两页：0＝冻结时段、1＝时间限额；拖拽时悬停另一页会自动切过去。 */
    private int libraryTab;
    private TextView slotTab, quotaTab;
    private final Map<UsageQuotaProgress,String> dailyBars=new java.util.HashMap<>();
    private final Map<TextView,String> dailyTexts=new java.util.HashMap<>();
    private final Map<UsageQuotaProgress,String> legacyBars=new java.util.HashMap<>();
    private int libraryGeneration;
    private boolean disposed;
    private Runnable fillLibrary;
    private final java.util.concurrent.ExecutorService catalogWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Map<String,String> appNames = new java.util.HashMap<>();
    @Override protected void onDestroy() {
        dismissApplicationPanel();finishLibraryMotion();disposed=true;libraryGeneration++;catalogWorker.shutdownNow();
        handler.removeCallbacksAndMessages(null);super.onDestroy();
    }
    private boolean libraryHidden, sheetMoving;
    private ValueAnimator libraryAnimator;
    private static final class LibraryApp {
        String pkg; View tile; ImageView icon; TextView name, detail; boolean installed;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        look = new Ui(this);
        rebuild();
    }
    private int immersiveBg = Integer.MIN_VALUE;
    private int immersiveCutout = Integer.MIN_VALUE;
    private int immersiveSysUi = Integer.MIN_VALUE;
    private void immersive() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(0);
        if (Build.VERSION.SDK_INT >= 28) {
            if (immersiveCutout != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                immersiveCutout = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = immersiveCutout;
                getWindow().setAttributes(lp);
            }
        }
        // 每次 onResume 都换一个新的窗口背景 = 让窗口重新判脏；颜色没变就别碰。
        if (immersiveBg != look.bg()) {
            immersiveBg = look.bg();
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(immersiveBg));
        }
        int visibility = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (immersiveSysUi != visibility) {
            immersiveSysUi = visibility;
            getWindow().getDecorView().setSystemUiVisibility(visibility);
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (look != null && look.dark != Ui.isDark(this)) { recreate(); return; }
        immersive();
        // 规则比对要序列化全部规则；放到首帧之后再跑，别和切页入场动画抢同一帧
        if (screen != null) screen.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (!ruleSnapshot.equals(RuleStore.exportRules(this))||!dailySnapshot.equals(DailyQuotaStore.snapshot(this).config.toString())) rebuild();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
    }
    @Override protected void onPause() {
        finishLibraryMotion();super.onPause();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(autoScroll);
        if (dragging) { pendingDrop = null; finishDrag(); }
    }
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!dragging && !sheetMoving && screen != null) {
                if (!ruleSnapshot.equals(RuleStore.exportRules(FreezeBoardActivity.this))||!dailySnapshot.equals(DailyQuotaStore.snapshot(FreezeBoardActivity.this).config.toString())) rebuild();
                else {
                    // 整屏徽标刷新（含学习闸门/罚时判定）一次 8–20ms，正好卡在 90Hz 的 11ms 预算上；
                    // 这些状态最细也只按分钟变，每两拍（约 2 秒）刷一次就够，页面不再每秒掉一帧。
                    updateNetHint();
                    if ((statusTick++ & 1) == 0) {
                        refreshBadges(screen);refreshDailyCounters();
                        for (FreezeTimelineView timeline : timelines) timeline.invalidate();
                    }
                }
            }
            handler.postDelayed(this, 1000);
        }
    };
    private boolean motion() {return MotionPolicy.enabled(this);}
    @Override protected void onMotionPolicyChanged(){if(!motion())finishLibraryMotion();}
    private void finishLibraryMotion(){
        if(libraryAnimator!=null){libraryAnimator.cancel();libraryAnimator=null;}
        if(libraryContent==null)return;
        libraryContent.setVisibility(libraryHidden?View.GONE:View.VISIBLE);libraryContent.setAlpha(1f);
        ViewGroup.LayoutParams lp=libraryContent.getLayoutParams();if(lp.height!=-2){lp.height=-2;libraryContent.setLayoutParams(lp);}
    }
    private int dp(float n) { return look.dp(n); }
    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private TextView action(String s, View.OnClickListener click) {
        TextView t = text(s, 13, look.accent()); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(10), dp(12), dp(10)); t.setOnClickListener(click); return t;
    }
    /** 复制到剪贴板：给「代理常开」的人一段 Clash 规则，粘到代理里即可。 */
    private void copyText(String value) {
        try {
            android.content.ClipboardManager manager =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(android.content.ClipData.newPlainText("AFS proxy rules", value));
            }
        } catch (RuntimeException ignored) {
        }
    }
    private void rebuild() {
        finishLibraryMotion();
        if (libraryAnimator != null) libraryAnimator.cancel();
        int oldY = scroll == null ? 0 : scroll.getScrollY();
        rules = RuleStore.all(this); slots = FreezeSlotStore.all(this);
        statusSnapshot();
        timelines.clear(); libraryApps.clear();dailyBars.clear();dailyTexts.clear();legacyBars.clear();slotViews.clear();
        libraryGeneration++;fillLibrary=null;
        ruleSnapshot = RuleStore.exportRules(this);
        dailySnapshot=DailyQuotaStore.snapshot(this).config.toString();
        screen = new LinearLayout(this); screen.setOrientation(LinearLayout.VERTICAL);
        look.page(screen); screen.setPadding(dp(8), dp(24), dp(8), dp(16));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(T.s("冻结应用"), 27, look.text()); title.setTypeface(Typeface.DEFAULT_BOLD);
        SwissTypography.role(title,SwissTypography.Role.TITLE);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(action(T.s("设置"), v -> settings()));
        TextView hint = text(T.s("蓝色表示定时冻结 · 学习联动另计"), 12, look.text2());
        hint.setPadding(0, dp(5), 0, dp(4));
        // 只写「学习联动冻结」太含糊：这里直接写清是记忆闪卡还是打卡没完成，并且可点过去
        gateNote = text("", 12, look.red());
        gateNote.setPadding(dp(12), dp(9), dp(12), dp(9));
        gateNote.setBackground(look.round(look.surface2(), 14));
        gateNote.setVisibility(View.GONE);
        gateNote.setOnClickListener(v -> openGateSource());
        LinearLayout.LayoutParams gateLp = new LinearLayout.LayoutParams(-1, -2);
        gateLp.topMargin = dp(4);

        scroll = new ScrollView(this); scroll.setClipToPadding(false);
        scroll.setOnScrollChangeListener((v,x,y,ox,oy)->{for(View card:slotViews.values())if(card instanceof ViewGroup){ViewGroup group=(ViewGroup)card;for(int i=0;i<group.getChildCount();i++)if(group.getChildAt(i) instanceof VirtualApps)((VirtualApps)group.getChildAt(i)).schedule();}});
        scroll.setPadding(0, dp(14), 0, dp(8));
        slotsView = new LinearLayout(this); slotsView.setOrientation(LinearLayout.VERTICAL);
        slotsView.setClipChildren(false); slotsView.setClipToPadding(false); scroll.addView(slotsView);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (slots.isEmpty()) {
            TextView empty = text(T.s("还没有冻结时段\n在设置里新建时间格，再拖入应用"), 16, look.text2());
            empty.setPadding(dp(18), dp(30), dp(18), dp(30)); slotsView.addView(empty);
        }
        for (FreezeSlotStore.Slot slot : slots){View card=slotCard(slot);slotViews.put(slot.id,card);slotsView.addView(card,gap(16));}
        // 首帧只建首屏：使用限额 / 设置区在首屏以下，以前它们也在同一帧里录绘制指令，
        // 白白把切页那一帧拉长（整页 drawCmd 实测 55–77ms）。放到首帧之后再建，视觉无差别；
        // 代次不匹配说明期间又 rebuild 过，这一批旧视图直接丢掉，避免串到新页面上。
        final int rebuildGeneration = libraryGeneration;
        final int restoreY = oldY;
        final LinearLayout.LayoutParams gateParams = gateLp;
        screen.post(() -> {
            if (rebuildGeneration != libraryGeneration || isFinishing() || isDestroyed()) return;
            usageCell();
            if (gateNote != null && gateNote.getParent() == null) slotsView.addView(gateNote, gateParams);
            settingsArea();
            updateNetHint();
            if (scroll != null) scroll.post(() -> scroll.scrollTo(0, restoreY));
        });
        buildLibrary(); setContentView(FloatingNav.wrap(this,screen,2)); immersive();
        updateGateNote();
        scroll.setOnDragListener((view, event) -> {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return validDrag(event);
            if (event.getAction() == DragEvent.ACTION_DRAG_LOCATION) trackDrag(view, event);
            if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) finishDrag();
            return true;
        });
    }
    private LinearLayout.LayoutParams gap(int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(bottom); return lp;
    }
    private LinearLayout slotCard(final FreezeSlotStore.Slot slot) {
        final LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(slotBackground(false)); card.setElevation(dp(look.amap?2:7));
        card.setClipChildren(false); card.setClipToPadding(false);
        List<String> members = FreezeSlotStore.members(slot, rules);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = text(T.s("冻结时段  /  ") + members.size() + T.s(" 个应用"), 11, look.text2());
        header.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(action(T.s("编辑"), v -> editSlot(slot))); card.addView(header);
        FreezeTimelineView timeline = new FreezeTimelineView(this, slot.rule); timelines.add(timeline);
        card.addView(timeline, new LinearLayout.LayoutParams(-1, dp(64)));
        TextView days = text((slot.rule.freeze ? Rule.daysText(slot.rule.days) + " · " : "")
                + timeTitle(slot.rule).replace('\n', ' '), 12, look.text2());
        days.setPadding(0, dp(3), 0, dp(13)); card.addView(days);
        card.addView(new VirtualApps(members),new LinearLayout.LayoutParams(-1,-2));
        quotaRows(card,members);
        final TextView drop = text(members.isEmpty() ? T.s("拖入应用，放到这个时间里") : T.s("长按应用可移动到其他时段"), 12, look.text3());
        drop.setGravity(Gravity.CENTER); drop.setPadding(dp(8), dp(10), dp(8), dp(10));
        drop.setBackground(look.round(look.surface2(), 10)); card.addView(drop);
        card.setOnDragListener((v, e) -> {
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: return validDrag(e);
                case DragEvent.ACTION_DRAG_ENTERED:
                    card.setBackground(slotBackground(true)); drop.setText(T.s("松手放入这个时段")); drop.setTextColor(look.accent());
                    if (motion()) card.animate().translationZ(dp(6)).scaleX(1.015f).scaleY(1.015f).setDuration(140).start();
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION: trackDrag(v, e); return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    resetTarget(card, drop, members.isEmpty()); return true;
                case DragEvent.ACTION_DROP:
                    final String pkg = (String)e.getLocalState();
                    pendingDrop = () -> moveApp(pkg, slot.id); return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    resetTarget(card, drop, members.isEmpty()); finishDrag(); return true;
                default: return true;
            }
        });
        return card;
    }
    /** Lightweight row shells stay in layout; only viewport tiles retain images/listeners. */
    private final class VirtualApps extends LinearLayout {
        final List<String> members=new ArrayList<>();final List<LinearLayout> rows=new ArrayList<>();final java.util.ArrayDeque<View> pool=new java.util.ArrayDeque<>();int created,reused;boolean scheduled;final String landing=landed;
        final android.view.ViewTreeObserver.OnScrollChangedListener listener=()->schedule();
        final Runnable update=()->{scheduled=false;if(!isAttachedToWindow())return;int[] viewport=new int[2];scroll.getLocationOnScreen(viewport);int top=viewport[1]-dp(100),bottom=viewport[1]+scroll.getHeight()+dp(100);
            for(int i=0;i<rows.size();i++){LinearLayout row=rows.get(i);int[] at=new int[2];row.getLocationOnScreen(at);boolean visible=at[1]+row.getHeight()>=top&&at[1]<=bottom;
                if(visible&&row.getChildCount()==0){row.setMinimumHeight(0);int first=i*2;for(int j=0;j<2;j++){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(j==1)lp.leftMargin=dp(8);View tile;if(first+j<members.size()){String pkg=members.get(first+j);tile=pool.pollFirst();if(tile==null){tile=appTile(pkg,false);created++;}else{bindSlotTile(tile,pkg);reused++;}if(pkg.equals(landing)&&motion()){tile.setScaleX(1.05f);tile.setScaleY(1.05f);tile.animate().scaleX(1).scaleY(1).setDuration(260).setInterpolator(new OvershootInterpolator(1.1f)).start();}}else tile=new View(FreezeBoardActivity.this);row.addView(tile,lp);}}
                else if(!visible&&!dragging&&row.getChildCount()>0){int height=row.getHeight();for(int n=0;n<row.getChildCount();n++){View tile=row.getChildAt(n);if(tile instanceof LinearLayout&&pool.size()<24){tile.animate().cancel();((ImageView)((LinearLayout)tile).getChildAt(0)).setImageDrawable(null);tile.setOnClickListener(null);tile.setOnLongClickListener(null);pool.addLast(tile);}}row.removeAllViews();row.setMinimumHeight(height);}
            }};
        VirtualApps(List<String> values){super(FreezeBoardActivity.this);members.addAll(values);setOrientation(VERTICAL);setBaselineAligned(false);
            for(int i=0;i<values.size();i+=2){LinearLayout row=new LinearLayout(FreezeBoardActivity.this);row.setBaselineAligned(false);row.setPadding(0,0,0,dp(8));row.setMinimumHeight(dp(76));rows.add(row);addView(row,new LinearLayout.LayoutParams(-1,-2));}
            addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->schedule());
        }
        void schedule(){if(!scheduled){scheduled=true;postOnAnimation(update);}}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();getViewTreeObserver().addOnScrollChangedListener(listener);schedule();}
        @Override protected void onDetachedFromWindow(){getViewTreeObserver().removeOnScrollChangedListener(listener);removeCallbacks(update);scheduled=false;pool.clear();super.onDetachedFromWindow();}
    }
    private boolean inside(View child,View parent){for(ViewParent p=child.getParent();p!=null;p=p.getParent())if(p==parent)return true;return child==parent;}
    private void forget(View parent){timelines.removeIf(v->inside(v,parent));dailyBars.keySet().removeIf(v->inside(v,parent));dailyTexts.keySet().removeIf(v->inside(v,parent));legacyBars.keySet().removeIf(v->inside(v,parent));}
    private void refreshAssignments(){
        Map<String,Rule> old=rules;rules=RuleStore.all(this);statusSnapshot();ruleSnapshot=RuleStore.exportRules(this);dailySnapshot=DailyQuotaStore.snapshot(this).config.toString();
        for(FreezeSlotStore.Slot slot:slots)if(!FreezeSlotStore.members(slot,old).equals(FreezeSlotStore.members(slot,rules))){View before=slotViews.get(slot.id);if(before==null){rebuild();return;}int at=slotsView.indexOfChild(before);forget(before);slotsView.removeView(before);View next=slotCard(slot);slotViews.put(slot.id,next);slotsView.addView(next,at,gap(16));}
        if(usageContainer!=null&&usageContainer.getParent()==slotsView){int at=slotsView.indexOfChild(usageContainer);forget(usageContainer);slotsView.removeView(usageContainer);usageCell();slotsView.removeView(usageContainer);slotsView.addView(usageContainer,at,gap(16));}
        renderLibrary();refreshBadges(screen);refreshDailyCounters();
    }
    private GradientDrawable slotBackground(boolean active) {
        return look.round(look.surface(), 22, active ? look.accent() : look.line(), active ? 2 : 0.6f);
    }
    private void resetTarget(View card, TextView drop, boolean empty) {
        card.setBackground(slotBackground(false)); drop.setText(empty ? T.s("拖入应用，放到这个时间里") : T.s("长按应用可移动到其他时段"));
        drop.setTextColor(look.text3());
        if (motion()) card.animate().translationZ(0).scaleX(1).scaleY(1).setDuration(170).start();
        else { card.setTranslationZ(0); card.setScaleX(1); card.setScaleY(1); }
    }
    private String timeTitle(Rule rule) {
        if (!rule.freeze) return T.s("原有冻结计划");
        if (rule.days == 0 || rule.windowCount() == 0) return T.s("无定时冻结");
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < rule.windowCount(); i++) {
            if (i > 0) s.append("\n");
            int a = rule.startAt(i), b = rule.endAt(i);
            if (a == b) s.append(T.s("全天冻结"));
            else s.append(Rule.hm(a / 60, a % 60)).append(" — ").append(a > b ? T.s("次日 ") : "").append(Rule.hm(b / 60, b % 60));
        }
        return s.toString();
    }
    private void buildLibrary() {
        libraryCells.clear();
        libraryHidden = getSharedPreferences(Ui.SP, 0).getBoolean("freeze_library_hidden", false);
        library = new LibrarySheet(); library.setOrientation(LinearLayout.VERTICAL);
        library.setPadding(dp(12), dp(5), dp(12), dp(7)); library.setBackground(look.round(look.surface(), 20));
        libraryHandle = text("", 11, look.text3()); libraryHandle.setGravity(Gravity.CENTER);
        libraryHandle.setOnClickListener(v -> setLibraryHidden(!libraryHidden, true));
        libraryHandle.setContentDescription(T.s("展开或收起应用库"));
        library.addView(libraryHandle, new LinearLayout.LayoutParams(-1, dp(24)));
        libraryContent = new LinearLayout(this); libraryContent.setOrientation(LinearLayout.VERTICAL);
        library.addView(libraryContent, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        libraryLabel = text(T.s("应用库"), 14, look.text()); libraryLabel.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(libraryLabel, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(text(T.s("可拖入／拖回"), 11, look.text3())); libraryContent.addView(head);
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(0,dp(6),0,dp(2));
        slotTab=text(T.s("冻结时段"),12,look.text2());quotaTab=text(T.s("时间限额"),12,look.text2());
        slotTab.setGravity(Gravity.CENTER);quotaTab.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slotLp=new LinearLayout.LayoutParams(0,dp(32),1f);slotLp.rightMargin=dp(6);
        tabs.addView(slotTab,slotLp);tabs.addView(quotaTab,new LinearLayout.LayoutParams(0,dp(32),1f));
        libraryContent.addView(tabs);
        slotTab.setOnClickListener(v->setLibraryTab(0));quotaTab.setOnClickListener(v->setLibraryTab(1));
        hoverTab(slotTab,0);hoverTab(quotaTab,1);
        paintLibraryTabs();
        libraryTray = new HorizontalScrollView(this); libraryTray.setHorizontalScrollBarEnabled(false);
        libraryTrayApps = new LinearLayout(this);
        libraryTray.addView(libraryTrayApps);
        libraryContent.addView(libraryTray,new LinearLayout.LayoutParams(-1,dp(104)));
        screen.addView(library);
        final int generation=libraryGeneration;
        final Set<String> enrolled=new LinkedHashSet<>(rules.keySet());enrolled.addAll(DailyQuotaStore.packages(this));
        availableApps.clear();availableApps.addAll(enrolled);
        final android.content.pm.PackageManager pm=getPackageManager();
        catalogWorker.execute(() -> {
            final Set<String> found=new LinkedHashSet<>(enrolled);
            final Map<String,String> names=new java.util.HashMap<>();
            try {
                Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for(android.content.pm.ResolveInfo info:pm.queryIntentActivities(launcher,0)) {
                    if(info.activityInfo!=null&&!getPackageName().equals(info.activityInfo.packageName))
                        found.add(info.activityInfo.packageName);
                }
                for(String pkg:found)names.put(pkg,Guard.labelOf(pm,pkg));
            } catch (RuntimeException ignored) { }
            final List<String> ordered=new ArrayList<>(found);
            final java.text.Collator collator=java.text.Collator.getInstance(java.util.Locale.CHINA);
            java.util.Collections.sort(ordered,(a,b)->collator.compare(names.getOrDefault(a,a),names.getOrDefault(b,b)));
            handler.post(() -> {
                if(disposed||generation!=libraryGeneration)return;
                appNames.clear();appNames.putAll(names);availableApps.addAll(found);
                libraryCatalog.clear();libraryCatalog.addAll(ordered);
                renderLibrary();
            });
        });
        libraryContent.setVisibility(libraryHidden ? View.GONE : View.VISIBLE);
        updateLibraryHandle();
        library.setOnDragListener((v,e)->{
            switch(e.getAction()){
                case DragEvent.ACTION_DRAG_STARTED:return validDrag(e);
                case DragEvent.ACTION_DRAG_ENTERED:
                    library.setBackground(look.round(look.surface(),20,look.accent(),1.5f));libraryHandle.setText(T.s("松手返回应用库"));return true;
                case DragEvent.ACTION_DRAG_LOCATION:return true;
                case DragEvent.ACTION_DROP:
                    if(!validDrag(e))return false;
                    String pkg=(String)e.getLocalState();pendingDrop=()->moveToLibrary(pkg);return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    library.setBackground(look.round(look.surface(),20));updateLibraryHandle();return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    library.setBackground(look.round(look.surface(),20));updateLibraryHandle();finishDrag();return true;
                default:return true;
            }
        });
    }
    /**
     * 按当前页签过滤并铺格子：
     * 冻结页只列“待安排”（还没进任何冻结时段）的应用，限额页只列还能设额度的应用。
     */
    private void renderLibrary(){
        if(libraryTrayApps==null)return;
        final int seq=++libraryRenderSeq;final List<String> shown=new ArrayList<>();for(String pkg:libraryCatalog)if(libraryVisible(pkg))shown.add(pkg);
        Set<String> wanted=new LinkedHashSet<>(shown);
        java.util.Iterator<Map.Entry<String,android.widget.FrameLayout>> entries=libraryCells.entrySet().iterator();
        while(entries.hasNext()){Map.Entry<String,android.widget.FrameLayout> e=entries.next();if(!wanted.contains(e.getKey())){libraryTrayApps.removeView(e.getValue());libraryApps.removeIf(app->app.pkg.equals(e.getKey()));entries.remove();}}
        final List<android.widget.FrameLayout> cells=new ArrayList<>();
        for(int i=0;i<shown.size();i++){String pkg=shown.get(i);android.widget.FrameLayout cell=libraryCells.get(pkg);
            if(cell==null){cell=new android.widget.FrameLayout(this);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(100),dp(104));lp.rightMargin=dp(8);libraryCells.put(pkg,cell);libraryTrayApps.addView(cell,i,lp);}
            else if(libraryTrayApps.indexOfChild(cell)!=i){libraryTrayApps.removeView(cell);libraryTrayApps.addView(cell,i);}
            cells.add(cell);
        }
        if(libraryLabel!=null)libraryLabel.setText(T.s("应用库  ·  ")+shown.size());
        final Runnable populate=()->{
            if(seq!=libraryRenderSeq||disposed||libraryHidden||libraryTray==null)return;
            int first=Math.max(0,libraryTray.getScrollX()/dp(108)-1),last=Math.min(shown.size(),(libraryTray.getScrollX()+Math.max(libraryTray.getWidth(),dp(320)))/dp(108)+2);
            if(!dragging){Set<String> visible=new LinkedHashSet<>(shown.subList(Math.min(first,shown.size()),Math.max(Math.min(first,shown.size()),last)));java.util.Iterator<LibraryApp> apps=libraryApps.iterator();while(apps.hasNext()){LibraryApp app=apps.next();if(!visible.contains(app.pkg)){ViewParent parent=app.tile.getParent();if(parent instanceof android.widget.FrameLayout)((android.widget.FrameLayout)parent).removeAllViews();apps.remove();}}}
            for(int i=first;i<last;i++)if(cells.get(i).getChildCount()==0)cells.get(i).addView(appTile(shown.get(i),true),new android.widget.FrameLayout.LayoutParams(-1,-1));
        };
        fillLibrary=populate;libraryTray.setOnScrollChangeListener((v,x,y,ox,oy)->populate.run());libraryTray.postOnAnimation(populate);
    }
    private boolean libraryVisible(String pkg){
        if(libraryTab==1)return DailyQuotaStore.snapshot(this).group(pkg)==null;    // 还能设额度
        return !rules.containsKey(pkg)||FreezeSlotStore.unassigned(rules.get(pkg)); // 待安排
    }
    private void updateLibraryHandle() {
        libraryHandle.setText(libraryHidden ? T.s("应用库  ·  上滑展开") : "━━");
    }
    private int libraryFullHeight() {
        int width = library.getWidth() - library.getPaddingLeft() - library.getPaddingRight();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels - dp(40);
        libraryContent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return libraryContent.getMeasuredHeight();
    }
    private void libraryHeight(int height, int full) {
        libraryContent.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams lp = libraryContent.getLayoutParams(); if(lp.height!=height){lp.height = height; libraryContent.setLayoutParams(lp);}
        libraryContent.setAlpha(full == 0 ? 1f : Math.max(0f, Math.min(1f, height / (float)full)));
    }
    private void setLibraryHidden(boolean hidden, boolean animate) {
        if (libraryAnimator != null) { libraryAnimator.cancel(); libraryAnimator = null; }
        int start = libraryContent.getVisibility() == View.GONE ? 0 : libraryContent.getHeight();
        final int full = libraryFullHeight();
        libraryHidden = hidden; updateLibraryHandle();
        if(!hidden&&fillLibrary!=null)library.postOnAnimation(fillLibrary);
        getSharedPreferences(Ui.SP, 0).edit().putBoolean("freeze_library_hidden", hidden).apply();
        final LinearLayout content = libraryContent;
        final int target = hidden ? 0 : full;
        Runnable finish = () -> {
            if (libraryContent != content) return;
            content.setVisibility(hidden ? View.GONE : View.VISIBLE); content.setAlpha(1f);
            ViewGroup.LayoutParams lp = content.getLayoutParams(); lp.height = -2; content.setLayoutParams(lp);
        };
        if (!animate || !motion() || start == target) { finish.run(); return; }
        libraryAnimator = ValueAnimator.ofInt(start, target);
        libraryAnimator.setDuration(210);
        libraryAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        libraryAnimator.addUpdateListener(a -> { if (libraryContent == content) libraryHeight((Integer)a.getAnimatedValue(), full); });
        libraryAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean canceled;
            @Override public void onAnimationCancel(android.animation.Animator a) { canceled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!canceled) finish.run(); }
        });
        libraryAnimator.start();
    }
    private final class LibrarySheet extends LinearLayout {
        private float downX, downY;
        private boolean vertical, startedHidden;
        private int full, start;
        LibrarySheet() { super(FreezeBoardActivity.this); }
        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            if (dragging) return false;
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                downX=e.getRawX(); downY=e.getRawY(); vertical=false; startedHidden=libraryHidden;
            } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                float dx=e.getRawX()-downX, dy=e.getRawY()-downY;
                if (Math.abs(dy)>dp(10) && Math.abs(dy)>Math.abs(dx)*1.3f) {
                    if (libraryAnimator != null) libraryAnimator.cancel();
                    full=libraryFullHeight(); start=startedHidden ? 0 : full;
                    vertical=true; sheetMoving=true; return true;
                }
            }
            return vertical;
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (!vertical) return super.onTouchEvent(e);
            float dy=e.getRawY()-downY;
            if (e.getActionMasked()==android.view.MotionEvent.ACTION_MOVE) {
                libraryHeight(Math.max(0, Math.min(full, start-(int)dy)), full);
            } else if (e.getActionMasked()==android.view.MotionEvent.ACTION_UP
                    || e.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL) {
                boolean hidden=e.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL ? startedHidden
                        : startedHidden ? dy>=-dp(24) : dy>dp(28);
                vertical=false; sheetMoving=false; setLibraryHidden(hidden,true);
            }
            return true;
        }
    }
    private android.graphics.drawable.Drawable appIcon(String pkg) {
        return AppIcons.drawable(this, pkg);
    }

    private View appTile(final String pkg, boolean compact) {
        LinearLayout tile = new LinearLayout(this); tile.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL); tile.setPadding(dp(10), dp(10), dp(10), dp(10));
        tile.setBackground(look.round(look.surface2(), 13));
        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.sym_def_app_icon);
        android.graphics.drawable.Drawable loaded = appIcon(pkg);
        if (loaded != null) icon.setImageDrawable(loaded);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(compact ? 25 : 29), dp(compact ? 25 : 29)));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(appNames.containsKey(pkg)?appNames.get(pkg):Guard.labelOf(getPackageManager(), pkg), compact ? 11 : 13, look.text());
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); labels.addView(name);
        if (compact) {
            LibraryApp visual = new LibraryApp(); visual.pkg = pkg; visual.tile = tile; visual.icon = icon; visual.name = name;
            try { getPackageManager().getApplicationInfo(pkg, 0); visual.installed = true; } catch (Exception ignored) { }
            visual.detail = text("", 9, look.text3()); labels.addView(visual.detail);
            tile.setTag(visual); libraryApps.add(visual); updateLibraryApp(visual);
        }
        if (!compact) {
            TextView state = text("", 10, look.text2()); state.setTag(pkg); labels.addView(state); refreshBadges(state);
        }
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(compact ? -1 : 0, -2, compact ? 0 : 1);
        if (!compact) labelLp.leftMargin = dp(8); else labelLp.topMargin = dp(6);
        tile.addView(labels, labelLp);
        tile.setOnClickListener(v -> chooseDestination(pkg));
        tile.setOnLongClickListener(v -> beginAppDrag(tile,pkg));
        if (pkg.equals(landed) && !compact && motion()) {
            tile.setScaleX(1.05f); tile.setScaleY(1.05f);
            tile.animate().scaleX(1).scaleY(1).setDuration(260).setInterpolator(new OvershootInterpolator(1.1f)).start();
        }
        return tile;
    }
    private boolean beginAppDrag(View tile,String pkg){
        if(lockedForMove(pkg)){toast(T.s("应用正在冻结，解冻后才能移动"));return true;}
        dragging=true;dragSource=tile;pendingDrop=null;
        if(!tile.startDragAndDrop(ClipData.newPlainText("freeze-app",pkg),new LiftShadow(tile),pkg,0)){dragging=false;dragSource=null;return true;}
        tile.setAlpha(.35f);tile.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);handler.post(autoScroll);return true;
    }
    private void bindSlotTile(View view,String pkg){
        LinearLayout tile=(LinearLayout)view;ImageView icon=(ImageView)tile.getChildAt(0);icon.clearColorFilter();android.graphics.drawable.Drawable drawable=appIcon(pkg);if(drawable!=null)icon.setImageDrawable(drawable);else icon.setImageResource(android.R.drawable.sym_def_app_icon);
        LinearLayout labels=(LinearLayout)tile.getChildAt(1);((TextView)labels.getChildAt(0)).setText(appNames.containsKey(pkg)?appNames.get(pkg):Guard.labelOf(getPackageManager(),pkg));TextView state=(TextView)labels.getChildAt(1);state.setTag(pkg);refreshBadges(state);
        tile.setAlpha(1f);tile.setScaleX(1f);tile.setScaleY(1f);tile.setOnClickListener(v->chooseDestination(pkg));tile.setOnLongClickListener(v->beginAppDrag(tile,pkg));
    }
    private DailyQuotaData badgeQuota;
    private Map<String, Integer> badgeLimits;
    private void refreshBadges(View view) {
        if (view == screen) {
            // 快照/额度表一屏共用一份：以前每个瓦片各取一次，等于每次刷新几十次 prefs 读+解析。
            badgeQuota = DailyQuotaStore.snapshot(this);
            badgeLimits = AppUsageQuota.limits(this);
            statusSnapshot();updateGateNote();
            for (LibraryApp app : libraryApps) updateLibraryApp(app);
        }
        if (view instanceof TextView && view.getTag() instanceof String) {
            String pkg = (String)view.getTag(); int reason = statusReason(pkg); boolean blocked = reason > 0;
            org.json.JSONObject quotaGroup=(badgeQuota == null ? DailyQuotaStore.snapshot(this) : badgeQuota).group(pkg);
            String quotaLabel=quotaGroup==null?"":" · "+(quotaGroup.optJSONArray("apps").length()>1?T.s("共享 "):"")+quotaGroup.optInt("minutes")+T.s("分/日");
            Integer limitBox = badgeLimits == null ? null : badgeLimits.get(pkg);
            int limit = limitBox == null ? 0 : limitBox;
            TextView t = (TextView)view;
            // 文案没变就别 setText：setText 会给这个 TextView 判脏，整屏几十个一起改就是一次
            // 全页重新录制 display list（实测这类刷新帧 40ms+）。颜色同理。
            CharSequence next = reasonLabel(reason)+quotaLabel + (statusKeys.contains(pkg) ? T.s(" · 关键") : "")+(limit>0?" · "+(FreezeSlotStore.unassigned(rules.get(pkg))?T.s("全天"):T.s("时段"))+T.s("额度 ")+limit+T.s("分/日"):"");
            if (!next.equals(t.getText())) t.setText(next);
            int color = blocked ? look.red() : look.green();
            if (t.getCurrentTextColor() != color) t.setTextColor(color);
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) refreshBadges(((ViewGroup)view).getChildAt(i));
    }
    /**
     * 一次刷新共用一份全局状态：学习闸门、禁忌、关键应用、生效规则、卡片奖励。
     *
     * 以前 statusReason 逐个应用调 Guard.blockReasonNow，里面每个应用都重跑一遍
     * StudyGate.holding（读联动配置+罚时），一屏四十多个应用就是几百毫秒。
     */
    private void visibleStatusPackages(View view,Set<String> result){
        if(view==null)return;if(view instanceof TextView&&view.getTag() instanceof String)result.add((String)view.getTag());
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)visibleStatusPackages(((ViewGroup)view).getChildAt(i),result);
    }
    private void statusSnapshot() {
        statusNow=GuardClock.now(this);statusKeys=RuleStore.keyApps(this);statusRules=Guard.enforcedRules(this);
        final String revision=RuleStore.exportRules(this)+DailyQuotaStore.snapshot(this).config.toString();
        if(!revision.equals(reasonRevision)){reasonRevision=revision;reasonCache=java.util.Collections.emptyMap();frozenCache=java.util.Collections.emptyMap();}
        if(statusWorkerBusy||disposed)return;statusWorkerBusy=true;
        final Calendar now=(Calendar)statusNow.clone();final Map<String,Rule> snapshot=new java.util.LinkedHashMap<>(statusRules);final Set<String> keys=new LinkedHashSet<>(statusKeys);final Set<String> catalog=new LinkedHashSet<>();visibleStatusPackages(slotsView,catalog);for(LibraryApp app:libraryApps)catalog.add(app.pkg);
        if(catalog.isEmpty())for(String pkg:snapshot.keySet()){catalog.add(pkg);if(catalog.size()>=24)break;}
        catalogWorker.execute(()->{
            Map<String,Integer> reasons=new java.util.HashMap<>();Map<String,Boolean> frozen=new java.util.HashMap<>();
            try{boolean hold=StudyGate.holding(this,now),taboo=StudyGate.tabooHolding(now.getTimeInMillis(),StudyGate.tabooUntil(this)),reward=CardStore.rewardUntil(this,now.getTimeInMillis())>0;
                Set<String> packages=catalog;
                for(String pkg:packages){reasons.put(pkg,Guard.blockReasonNow(this,pkg,now,snapshot,keys,hold,taboo,reward));try{frozen.put(pkg,(getPackageManager().getApplicationInfo(pkg,0).flags&android.content.pm.ApplicationInfo.FLAG_SUSPENDED)!=0);}catch(android.content.pm.PackageManager.NameNotFoundException ignored){frozen.put(pkg,false);}}
            }catch(RuntimeException ignored){}
            handler.post(()->{statusWorkerBusy=false;if(disposed)return;if(!revision.equals(reasonRevision)){statusSnapshot();return;}reasonCache=reasons;frozenCache=frozen;for(LibraryApp app:libraryApps)updateLibraryApp(app);refreshBadges(slotsView);});
        });
    }
    /** 写清「学习联动为什么还在冻」，点这一行直接跳到该补的那件事。 */
    private void updateGateNote() {
        if (gateNote == null) return;
        Calendar now = Calendar.getInstance();
        String reason = StudyGate.holdReason(this, now);
        gateNote.setText(reason);
        gateNote.setContentDescription(reason);
        gateNote.setVisibility(reason.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private void openGateSource() {
        LearningLink.Progress memory = LearningLink.memory(this);
        LearningLink.Progress quiz = LearningLink.quiz(this);
        boolean dailyTime=Calendar.getInstance().get(Calendar.HOUR_OF_DAY)>=20;
        if (dailyTime && StudyGate.studyPending(this) > 0) {
            startActivity(new Intent(this, CheckinActivity.class));
        } else if (dailyTime && memory.available && memory.selected && !memory.complete()) {
            startActivity(new Intent(this, MemoryActivity.class));
        } else if (dailyTime && quiz.available && quiz.selected && !quiz.complete()) {
            startActivity(new Intent(this, QuizActivity.class));
        } else if (CardStore.summaryHolding(this,System.currentTimeMillis())) {
            startActivity(new Intent(this, HistoryActivity.class).putExtra(HistoryActivity.EXTRA_WEEKLY, true));
        } else if (StudyGate.tabooActive(this)) {
            toast(T.s("禁忌惩罚到点自动解除，没有解除按钮"));
        }
    }
    private int statusReason(String pkg) {Integer reason=reasonCache.get(pkg);return reason==null?-1:reason;}
    private void updateLibraryApp(LibraryApp app) {
        int reason = app.installed ? statusReason(app.pkg) : 0;
        // The library reflects the phone's current suspension state, not a future schedule.
        boolean frozen = Boolean.TRUE.equals(frozenCache.get(app.pkg));
        boolean muted = frozen;
        if (muted) {
            android.graphics.ColorMatrix matrix = new android.graphics.ColorMatrix(); matrix.setSaturation(0);
            app.icon.setColorFilter(new android.graphics.ColorMatrixColorFilter(matrix));
        } else app.icon.clearColorFilter();
        app.tile.setAlpha(muted ? .5f : 1f);
        app.name.setTextColor(muted ? look.text3() : look.text());
        if(libraryTab==1&&app.installed){
            org.json.JSONObject group=DailyQuotaStore.snapshot(this).group(app.pkg);
            if(group!=null){
                org.json.JSONArray members=group.optJSONArray("apps");
                app.detail.setText((members!=null&&members.length()>1?T.s("共享格子 · "):"")+group.optInt("minutes")+T.s(" 分钟/日"));
            }else app.detail.setText(T.s("未限额"));
            app.detail.setTextColor(muted?look.text3():look.text2());
            return;
        }
        app.detail.setText(!app.installed ? T.s("未安装") : frozen ? (reason == 0 ? T.s("已冻结") : reasonLabel(reason))
                : reason<0?T.s("正在检查状态"): (!rules.containsKey(app.pkg) || FreezeSlotStore.unassigned(rules.get(app.pkg))) ? T.s("待安排") : T.s("可使用"));
        app.detail.setTextColor(muted ? look.text3() : look.text2());
    }
    /** 切换应用库的分页：只换每块瓦片上的说明，不重建列表，所以拖拽中切页也不会中断。 */
    private void setLibraryTab(int tab){
        if(libraryTab==tab)return;
        libraryTab=tab;paintLibraryTabs();renderLibrary();
    }
    private void paintLibraryTabs(){
        if(slotTab==null||quotaTab==null)return;
        slotTab.setTextColor(libraryTab==0?look.onAccent():look.text2());
        slotTab.setBackground(look.round(libraryTab==0?look.accent():look.surface2(),12f));
        quotaTab.setTextColor(libraryTab==1?look.onAccent():look.text2());
        quotaTab.setBackground(look.round(libraryTab==1?look.accent():look.surface2(),12f));
    }
    /** 拖着应用停在这一页上约 0.35 秒就自动切过去，手指不用离开屏幕。 */
    private void hoverTab(View chip,final int tab){
        chip.setOnDragListener((v,e)->{
            if(e.getAction()==DragEvent.ACTION_DRAG_STARTED)return validDrag(e);
            if(e.getAction()==DragEvent.ACTION_DRAG_ENTERED&&dragging)
                v.postDelayed(()->{if(dragging)setLibraryTab(tab);},350);
            return true;
        });
    }
    private boolean validDrag(DragEvent e) { return e.getLocalState() instanceof String && availableApps.contains((String)e.getLocalState()); }
    private void trackDrag(View view, DragEvent e) { int[] xy = new int[2]; view.getLocationOnScreen(xy); dragScreenY = xy[1] + (int)e.getY(); }
    private final Runnable autoScroll = new Runnable() {
        public void run() {
            if (!dragging) return;
            int[] xy = new int[2]; scroll.getLocationOnScreen(xy);
            if (dragScreenY > 0 && dragScreenY < xy[1] + dp(64)) scroll.scrollBy(0, -dp(9));
            else if (dragScreenY > xy[1] + scroll.getHeight() - dp(64)) scroll.scrollBy(0, dp(9));
            handler.postDelayed(this, 16);
        }
    };
    private void finishDrag() {
        if (!dragging) return;
        dragging = false; dragScreenY = 0; handler.removeCallbacks(autoScroll);
        if (dragSource != null) {
            if (dragSource.getTag() instanceof LibraryApp) updateLibraryApp((LibraryApp)dragSource.getTag());
            else dragSource.setAlpha(1f);
        }
        dragSource = null;
        Runnable drop = pendingDrop; pendingDrop = null;
        if (drop != null) handler.post(drop);
    }
    private final class LiftShadow extends View.DragShadowBuilder {
        LiftShadow(View view) { super(view); }
        @Override public void onProvideShadowMetrics(Point size, Point touch) {
            size.set(getView().getWidth() + dp(28), getView().getHeight() + dp(32)); touch.set(size.x / 2, size.y / 2);
        }
        @Override public void onDrawShadow(Canvas canvas) {
            canvas.translate(dp(14), dp(12));
            if (motion()) canvas.rotate(-3f, getView().getWidth() / 2f, getView().getHeight() / 2f);
            android.graphics.Paint shadow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            shadow.setColor(look.surface2()); shadow.setShadowLayer(dp(7), 0, dp(5), 0x55000000);
            canvas.drawRoundRect(0, 0, getView().getWidth(), getView().getHeight(), dp(13), dp(13), shadow);
            getView().draw(canvas);
        }
    }
    /** 正在冻结（冻结时段内或系统里已被暂停）的应用不允许移动；解冻后才能调整。 */
    private boolean lockedForMove(String pkg) {
        if (DailyQuotaStore.exceeded(this,pkg)) return true;
        if (RuleStore.packages(this).contains(pkg) && Guard.lockedNow(this, pkg)) return true;
        try {
            return (getPackageManager().getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
    private void moveApp(String pkg, String targetId) {
        if (!availableApps.contains(pkg)) { toast(T.s("应用已不在应用库中")); rebuild(); return; }
        if (lockedForMove(pkg)) { toast(T.s("应用正在冻结，解冻后才能移动")); rebuild(); return; }
        FreezeSlotStore.Slot target = null;
        for (FreezeSlotStore.Slot slot : FreezeSlotStore.all(this)) if (slot.id.equals(targetId)) target = slot;
        if (target == null) { toast(T.s("这个时段已不存在")); rebuild(); return; }
        try{RuleStore.put(this, pkg, target.rule);}catch(RuntimeException error){toast(error.getMessage());return;} landed = pkg; refreshAssignments(); landed = null; applyRules();
        toast(T.s("已放入 ") + timeTitle(target.rule).replace('\n', ' '));
    }
    private void moveToLibrary(String pkg){
        if(!availableApps.contains(pkg)){toast(T.s("应用已不在应用库中"));return;}
        Rule current=RuleStore.all(this).get(pkg);
        if(current==null||FreezeSlotStore.unassigned(current)){toast(T.s("应用已在应用库中"));return;}
        if(lockedForMove(pkg)){toast(T.s("应用正在冻结，解冻后才能移动"));return;}
        // 移出冻结时段=解冻，只进不出：收束时段才放行；刚加入的十分钟内仍可纠错
        if(!windowOpen()&&!Guard.canRemove(this,pkg)){toast(T.f("移出冻结要等收束时段 %s",TimeWindow.label(this)));return;}
        try{
            RuleStore.put(this,pkg,FreezeSlotStore.withoutSchedule());refreshAssignments();applyRules();
            toast(AppUsageQuota.minutes(this,pkg)>0||DailyQuotaStore.snapshot(this).group(pkg)!=null?T.s("已返回应用库，使用限额改为全天累计"):T.s("已返回应用库"));
        }catch(RuntimeException error){toast(error.getMessage());}
    }
    /** Small vector actions retain labels for TalkBack and long-press hints. */
    private void dialogIcon(AlertDialog dialog,int which,String label,boolean remove){
        android.widget.Button button=dialog.getButton(which);button.setText("");button.setContentDescription(label);
        if(Build.VERSION.SDK_INT>=26)button.setTooltipText(label);
        button.setOnLongClickListener(v->{toast(label);return true;});
        android.graphics.drawable.Drawable icon=new android.graphics.drawable.Drawable(){
            private final android.graphics.Paint ink=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas canvas){
                canvas.save();android.graphics.Rect b=getBounds();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
                ink.setColor(remove?look.red():look.accent());ink.setStyle(android.graphics.Paint.Style.STROKE);ink.setStrokeWidth(look.amap?1.25f:1.6f);ink.setStrokeCap(android.graphics.Paint.Cap.ROUND);ink.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                if(remove){
                    android.graphics.Path box=new android.graphics.Path();box.moveTo(11,4);box.lineTo(5,4);box.quadTo(3,4,3,6);box.lineTo(3,18);box.quadTo(3,20,5,20);box.lineTo(11,20);canvas.drawPath(box,ink);
                    canvas.drawLine(9,12,21,12,ink);canvas.drawLine(17,8,21,12,ink);canvas.drawLine(21,12,17,16,ink);
                }else{canvas.drawCircle(12,12,9,ink);canvas.drawLine(12,11,12,17,ink);ink.setStyle(android.graphics.Paint.Style.FILL);canvas.drawCircle(12,7,0.9f,ink);}
                canvas.restore();
            }
            @Override public void setAlpha(int alpha){}
            @Override public void setColorFilter(android.graphics.ColorFilter filter){}
            @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        };
        icon.setBounds(0,0,dp(24),dp(24));button.setCompoundDrawables(icon,null,null,null);button.setCompoundDrawablePadding(0);button.setPadding(dp(12),dp(12),dp(12),dp(12));
        button.setMinWidth(dp(48));button.setMinimumWidth(dp(48));button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(look.accent()&0x00ffffff|0x22000000),look.round(look.surface2(),14),look.round(0xffffffff,14)));
        ViewGroup.LayoutParams lp=button.getLayoutParams();lp.width=dp(48);lp.height=dp(48);button.setLayoutParams(lp);
    }
    /** One capsule slides between the three existing dialog actions. */
    private final class DetailActionSlider extends android.widget.FrameLayout {
        private final View capsule;
        private final android.widget.Button[] actions=new android.widget.Button[3];
        private final AlertDialog dialog;
        private int selected=2;
        private Runnable pending;
        DetailActionSlider(AlertDialog dialog){
            super(FreezeBoardActivity.this);this.dialog=dialog;
            setBackground(look.round(look.surface2(),18));
            capsule=new View(FreezeBoardActivity.this);capsule.setBackground(look.round(look.surface(),14));
            android.widget.FrameLayout.LayoutParams pill=new android.widget.FrameLayout.LayoutParams(1,dp(48));pill.topMargin=dp(4);addView(capsule,pill);
            LinearLayout row=new LinearLayout(FreezeBoardActivity.this);row.setBaselineAligned(false);row.setPadding(dp(4),dp(4),dp(4),dp(4));
            addView(row,new android.widget.FrameLayout.LayoutParams(-1,dp(56)));
            int[] kinds={AlertDialog.BUTTON_NEUTRAL,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE};
            for(int i=0;i<kinds.length;i++){
                final int index=i;final android.widget.Button original=dialog.getButton(kinds[i]);
                android.widget.Button button=i==0?new android.widget.Button(FreezeBoardActivity.this){
                    @Override protected void onDraw(Canvas canvas){android.graphics.drawable.Drawable icon=original.getCompoundDrawables()[0];canvas.save();canvas.translate((getWidth()-dp(24))/2f,(getHeight()-dp(24))/2f);icon.draw(canvas);canvas.restore();}
                }:new android.widget.Button(FreezeBoardActivity.this);actions[i]=button;
                button.setText(original.getText());button.setTextSize(14);button.setAllCaps(false);button.setTextColor(look.accent());
                String label=i==0?T.s("移出冻结列表"):i==1?T.s("使用额度"):T.s("关闭");button.setContentDescription(label);button.setGravity(Gravity.CENTER);
                button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));button.setPadding(dp(8),0,dp(8),0);
                if(i==0){android.graphics.drawable.Drawable icon=original.getCompoundDrawables()[0];button.setCompoundDrawables(icon,null,null,null);button.setPadding(dp(12),dp(12),dp(12),dp(12));}
                button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((look.accent()&0x00ffffff)|0x18000000),null,look.round(0xffffffff,14)));
                if(Build.VERSION.SDK_INT>=26)button.setTooltipText(label);
                button.setOnLongClickListener(v->{toast(label);return true;});
                button.setOnClickListener(v->{
                    if(pending!=null)return;selected=index;position(true);
                    pending=()->{pending=null;if(dialog.isShowing())original.performClick();};
                    if(motion())postDelayed(pending,180);else pending.run();
                });
                row.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));
            }
            addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol)position(false);});
        }
        private void position(boolean animated){
            int width=Math.max(1,(getWidth()-dp(8))/3);ViewGroup.LayoutParams lp=capsule.getLayoutParams();lp.width=width;capsule.setLayoutParams(lp);
            float x=dp(4)+selected*width;capsule.animate().cancel();
            if(animated&&motion())capsule.animate().translationX(x).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();else capsule.setTranslationX(x);
            capsule.setBackground(look.round(selected==0?((look.red()&0x00ffffff)|0x18000000):look.surface(),14));
            for(int i=0;i<actions.length;i++)if(actions[i]!=null)actions[i].setSelected(i==selected);
        }
        @Override protected void onDetachedFromWindow(){if(pending!=null){removeCallbacks(pending);pending=null;}capsule.animate().cancel();super.onDetachedFromWindow();}
    }
    private void detailActionSlider(AlertDialog dialog){
        dialogIcon(dialog,AlertDialog.BUTTON_NEUTRAL,T.s("移出冻结列表"),true);
        ViewGroup parent=(ViewGroup)dialog.getButton(AlertDialog.BUTTON_POSITIVE).getParent();
        DetailActionSlider slider=new DetailActionSlider(dialog);parent.removeAllViews();
        if(parent instanceof LinearLayout)((LinearLayout)parent).setOrientation(LinearLayout.VERTICAL);
        parent.setPadding(dp(16),dp(8),dp(16),dp(12));parent.addView(slider,new ViewGroup.LayoutParams(-1,dp(56)));
    }
    private void chooseDestination(String pkg) {appDetails(pkg);}
    /** 使用限额容器：一行 = 一个额度（一个格子）；共享就是同一个格子里放多个应用。 */
    private void usageCell(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),dp(16),dp(16),dp(16));box.setBackground(look.round(look.surface(),20));
        usageContainer=box;
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(T.s("使用限额"),19,look.text());
        head.addView(title,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        TextView more=look.iconButton("⋯",look.text2(),look.surface2());
        more.setContentDescription(T.s("额度的更多操作"));
        more.setOnClickListener(v->quotaAddMenu());
        head.addView(more,new LinearLayout.LayoutParams(dp(34),dp(34)));
        box.addView(head);
        box.addView(text(T.s("一行就是一个额度：只放一个应用是它单独的一份，放多个应用就是共用同一份。点一行改成员和分钟数，长按可以并到另一个额度或删掉。未绑定时段按全天计时，放进时段只累计时段内的用量，额度用完才冻结，今日额度")+DayBoundary.label(this)+T.s("重置。"),12,look.text2()));
        LinearLayout reset=new LinearLayout(this);reset.setGravity(Gravity.CENTER_VERTICAL);reset.setPadding(0,dp(10),0,dp(2));
        reset.addView(text(T.s("额度重置时刻"),13,look.text2()),new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        reset.addView(text(DayBoundary.label(this),13,look.accent()));
        reset.setContentDescription(T.s("额度重置时刻 ")+DayBoundary.label(this));
        reset.setOnClickListener(v->pickResetHour());
        box.addView(reset);
        List<String> allDay=new ArrayList<>();for(String pkg:DailyQuotaStore.packages(this))if(!rules.containsKey(pkg)||FreezeSlotStore.unassigned(rules.get(pkg)))allDay.add(pkg);
        for(String pkg:rules.keySet())if(FreezeSlotStore.unassigned(rules.get(pkg))&&AppUsageQuota.minutes(this,pkg)>0&&!allDay.contains(pkg))allDay.add(pkg);
        if(DailyQuotaStore.snapshot(this).groups().length()==0)box.addView(text(T.s("还没有额度。点右上角 ⋯ 新建一个单独额度或共享额度。"),12,look.text3()));
        quotaRows(box,allDay);
        box.setOnDragListener((v,e)->{if(e.getAction()==DragEvent.ACTION_DRAG_STARTED)return validDrag(e);if(e.getAction()==DragEvent.ACTION_DROP&&validDrag(e)){String pkg=(String)e.getLocalState();pendingDrop=()->moveToAllDay(pkg);return true;}if(e.getAction()==DragEvent.ACTION_DRAG_ENDED)finishDrag();return true;});slotsView.addView(box,gap(16));
    }
    /** 额度重置时刻：原来在设置页，用额度的地方就在这一页，搬过来少跳一次。 */
    private void pickResetHour(){
        final int current=DayBoundary.selectedHour(this);
        String[] labels=new String[24];
        for(int hour=0;hour<24;hour++)labels[hour]=hour==0?T.s("00:00（零点）"):String.format(java.util.Locale.CHINA,"%02d:00",hour);
        ThemeStore.dialog(this).setTitle(T.s("每天几点重置额度")).setSingleChoiceItems(labels,current,(dialog,which)->{
            try{
                if(!DayBoundary.saveHour(this,which))throw new IllegalStateException(T.s("没能保存"));
                dialog.dismiss();
                toast(T.s("重置时间已保存，将在当前额度日结束后生效"));
                rebuild();
            }catch(RuntimeException error){toast(error.getMessage());}
        }).setNegativeButton(T.s("取消"),null).show();
    }
    private void moveToAllDay(String pkg){
        if(!availableApps.contains(pkg))return;
        if(DailyQuotaStore.snapshot(this).group(pkg)==null){quotaEditor(null,pkg,false);return;}   // 直接给它建一份新额度
        if(lockedForMove(pkg)){toast(T.s("当前已冻结，解冻后才能移动"));return;}
        // 摘掉冻结时段=解冻，跟移出应用同一道闸；十分钟纠错期里仍可改
        if(!windowOpen()&&!Guard.canRemove(this,pkg)){toast(T.f("移出冻结时段要等收束时段 %s",TimeWindow.label(this)));return;}
        try{RuleStore.put(this,pkg,FreezeSlotStore.withoutSchedule());refreshAssignments();applyRules();toast(T.s("已改为全天累计，额度和已用时间保留"));}catch(RuntimeException error){toast(error.getMessage());}

    }
    /**
     * 新建额度的二级菜单：单独 / 共享 / 管理，一行一个图标和说明，不再是干巴巴的一列文字。
     */
    private void quotaAddMenu(){
        LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setPadding(dp(18),dp(6),dp(18),dp(6));
        final AlertDialog dialog=ThemeStore.dialog(this).setView(sheet).setNegativeButton(T.s("取消"),null).create();
        sheet.addView(menuRow(dialog,"▣",look.accent(),T.s("新建单独额度"),T.s("只放一个应用，单独计时，用完就冻"),()->quotaEditor(null,null,false)));
        sheet.addView(menuRow(dialog,"⧉",look.green(),T.s("新建共享额度"),T.s("多个应用共用同一份分钟数"),()->quotaEditor(null,null,true)));
        dialog.show();
    }
    private View menuRow(final AlertDialog dialog,String glyph,int color,String title,String subtitle,final Runnable run){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(6),dp(13),dp(6),dp(13));
        row.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((look.accent()&0x00ffffff)|0x22000000),null,look.round(0xffffffff,14)));
        TextView icon=text(glyph,15,0xFFFFFFFF);icon.setGravity(Gravity.CENTER);icon.setBackground(look.round(color,12f));
        row.addView(icon,new LinearLayout.LayoutParams(dp(34),dp(34)));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title,15,look.text()));
        labels.addView(text(subtitle,11,look.text3()));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);lp.leftMargin=dp(12);row.addView(labels,lp);
        row.addView(text("›",18,look.text3()));
        row.setOnClickListener(v->{dialog.dismiss();if(run!=null)run.run();});
        return row;
    }
    /**
     * 一个额度一行：成员、剩余分钟、进度条都在这一行里。
     *
     * 以前额度是挂在“应用”下面的，共享只能靠管理页的合并按钮；现在一行就是一个格子，
     * 点进去勾成员就是共享，长按可以并到另一个额度。时段卡片里也用同一张卡。
     */
    private void quotaRows(LinearLayout box,List<String> members){
        DailyQuotaData data=DailyQuotaStore.snapshot(this);
        for(int i=0;i<data.groups().length();i++){
            org.json.JSONObject group=data.groups().optJSONObject(i);
            if(!hasMember(group,members))continue;
            box.addView(quotaCard(group),gap(8));
        }
        for(String pkg:members){
            if(DailyQuotaStore.snapshot(this).group(pkg)!=null||AppUsageQuota.minutes(this,pkg)<=0)continue;
            box.addView(appTile(pkg,false),gap(8));     // 旧全天额度还没搬进格子：先按应用显示
            addLegacyBar(box,pkg);
        }
        refreshDailyCounters();
    }
    private static boolean hasMember(org.json.JSONObject group,List<String> members){
        org.json.JSONArray apps=group.optJSONArray("apps");if(apps==null)return false;
        for(int i=0;i<apps.length();i++)if(members.contains(apps.optString(i)))return true;
        return false;
    }
    /**
     * 额度卡：点一下改成员/分钟数，长按空白处出菜单（并到别的额度、删除）。
     *
     * 成员图标是真子 View：长按一枚图标就能把它拖到另一张卡上，松手即迁移；
     * 卡片本身也是拖放目标，从应用库拖一只应用进来就是加入这个额度。
     * 共享格子标题固定写「共享格子」，不再拿某只应用的名字当标题。
     */
    private View quotaCard(final org.json.JSONObject group){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12),dp(11),dp(12),dp(12));
        final android.graphics.drawable.Drawable normal=look.round(look.surface2(),14),hot=look.round((look.accent()&0x00ffffff)|0x33000000,14);
        card.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((look.accent()&0x00ffffff)|0x22000000),normal,look.round(0xffffffff,14)));
        org.json.JSONArray apps=group.optJSONArray("apps");
        final int count=apps==null?0:apps.length();
        TextView name=text(count>1?T.s("共享格子"):group.optString("name"),14,look.text());
        name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(name);
        if(count>0){
            MemberStrip strip=new MemberStrip(this,dp(30),dp(6));
            for(int i=0;i<count;i++){
                final String pkg=apps.optString(i);
                ImageView icon=new ImageView(this);
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
                try{icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg));}catch(Exception ignored){}
                icon.setBackground(look.round(0xFFFFFFFF,8f));icon.setClipToOutline(true);
                icon.setContentDescription(appLabel(pkg)+T.s("，长按可以拖到别的额度"));
                icon.setOnLongClickListener(v->{
                    dragging=true;dragSource=v;pendingDrop=null;
                    boolean started=v.startDragAndDrop(ClipData.newPlainText("freeze-app",pkg),new LiftShadow(v),pkg,0);
                    if(!started){dragging=false;dragSource=null;}
                    return true;
                });
                strip.addView(icon);
            }
            LinearLayout.LayoutParams stripLp=new LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT);stripLp.topMargin=dp(7);
            card.addView(strip,stripLp);
        }
        TextView detail=text("",11,look.text2());
        LinearLayout.LayoutParams detailLp=new LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT);detailLp.topMargin=dp(7);
        card.addView(detail,detailLp);dailyTexts.put(detail,group.optString("id"));
        UsageQuotaProgress bar=new UsageQuotaProgress(this);
        LinearLayout.LayoutParams barLp=new LinearLayout.LayoutParams(-1,dp(8));barLp.topMargin=dp(8);card.addView(bar,barLp);
        dailyBars.put(bar,group.optString("id"));
        card.setOnClickListener(v->quotaEditor(group,null,false));
        card.setOnLongClickListener(v->{quotaMenu(group);return true;});
        card.setOnDragListener((v,e)->{
            switch(e.getAction()){
                case DragEvent.ACTION_DRAG_STARTED: return validDrag(e);
                case DragEvent.ACTION_DRAG_ENTERED: v.setBackground(hot);return true;
                case DragEvent.ACTION_DRAG_EXITED: v.setBackground(normal);return true;
                case DragEvent.ACTION_DRAG_ENDED: v.setBackground(normal);finishDrag();return true;
                case DragEvent.ACTION_DROP:
                    v.setBackground(normal);
                    if(validDrag(e)){String pkg=(String)e.getLocalState();pendingDrop=()->moveIntoGroup(group,pkg);return true;}
                    return false;
            }
            return true;
        });
        return card;
    }
    /** 收束时段：限额的「减」、冻结名单的移出都只在这一小时里放开；部署宽限期内不设限。 */
    private boolean windowOpen(){return TimeWindow.on(this)||Config.inGrace(this);}
    private boolean quotaEditable(){return DailyQuotaData.editable(GuardClock.now(this),TimeWindow.start(this),TimeWindow.end(this));}
    /** 把一只应用迁进目标额度：从原格子里摘掉，加到这一格；被搬空的格子一起删掉。 */
    private void moveIntoGroup(final org.json.JSONObject target,String pkg){
        if(pkg==null||!availableApps.contains(pkg))return;
        if(!quotaEditable()){toast(T.f("这一步会减少额度，只在收束时段 %s 内开放",TimeWindow.label(this)));return;}
        try{
            org.json.JSONObject next=MemoryData.copy(DailyQuotaStore.snapshot(this).config);
            int moved=DailyQuotaData.moveInto(next,target.optString("id"),pkg);
            if(moved==0){toast(T.s("这只应用已经在这个额度里"));return;}
            if(moved<0){toast(T.s("额度已不存在"));rebuild();return;}
            DailyQuotaData.put(next,"enabled",true);
            DailyQuotaData.validate(next);
            DailyQuotaStore.save(this,next);rebuild();applyRules();
            toast(T.s("已迁到「")+groupTitle(next,target.optString("id"))+"」");
        }catch(RuntimeException error){toast(error.getMessage());}
    }
    private static String groupTitle(org.json.JSONObject config,String id){
        org.json.JSONArray groups=config.optJSONArray("groups");
        if(groups!=null)for(int i=0;i<groups.length();i++){
            org.json.JSONObject g=groups.optJSONObject(i);if(g==null||!id.equals(g.optString("id")))continue;
            org.json.JSONArray apps=g.optJSONArray("apps");
            return apps!=null&&apps.length()>1?T.s("共享格子"):g.optString("name");
        }
        return T.s("额度");
    }
    private void quotaMenu(final org.json.JSONObject group){
        DailyQuotaData data=DailyQuotaStore.snapshot(this);
        final List<org.json.JSONObject> others=new ArrayList<>();
        for(int i=0;i<data.groups().length();i++){org.json.JSONObject g=data.groups().optJSONObject(i);if(!group.optString("id").equals(g.optString("id")))others.add(g);}
        org.json.JSONArray members=group.optJSONArray("apps");
        int count=members==null?0:members.length();
        String title=count>1?T.s("共享额度"):group.optString("name");
        String subtitle=count>1?memberSummary(group):T.f("每天 %d 分钟 · 单独计时",group.optInt("minutes"));

        ScrollView scroll=new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20),dp(3),dp(20),dp(8));scroll.addView(sheet);
        sheet.addView(panelHeader("▣",look.accent(),title,subtitle));
        if(count>0){
            LinearLayout identity=new LinearLayout(this);identity.setGravity(Gravity.CENTER_VERTICAL);
            identity.addView(memberStrip(group,34),new LinearLayout.LayoutParams(0,-2,1f));
            identity.addView(panelPill(T.f("%d 个应用",count),look.accent()));
            panelGap(sheet,identity);
        }

        long used=data.used(group),limit=Math.max(1,group.optInt("minutes")*60000L);
        int remain=(int)Math.max(0,Math.ceil((limit-used)/60000.0));
        LinearLayout metrics=panelMetricRow();
        metrics.addView(panelMetric(T.s("每天额度"),String.valueOf(group.optInt("minutes")),look.text()),new LinearLayout.LayoutParams(0,-2,1f));
        LinearLayout.LayoutParams mid=new LinearLayout.LayoutParams(0,-2,1f);
        mid.leftMargin=dp(8);mid.rightMargin=dp(8);
        metrics.addView(panelMetric(T.s("今日已用"),String.format(java.util.Locale.CHINA,"%.0f",used/60000.0),look.accent()),mid);
        metrics.addView(panelMetric(T.s("剩余"),String.valueOf(remain),remain>0?look.green():look.red()),new LinearLayout.LayoutParams(0,-2,1f));
        sheet.addView(metrics, new LinearLayout.LayoutParams(-1, -2));

        TextView state=text(DailyQuotaStore.description(this,data,group),12,look.text2());
        state.setPadding(dp(4),dp(10),dp(4),dp(2));sheet.addView(state);

        sheet.addView(panelSection(T.s("调整额度")));
        panelGap(sheet,panelAction("✎",look.accent(),T.s("修改成员和分钟数"),T.s("调整应用组合、名称和每日上限"),()->quotaEditor(group,null,false)));
        if(!others.isEmpty())panelGap(sheet,panelAction("⇄",look.green(),T.s("并到另一个额度"),T.s("成员合并，共用较小的一份分钟数"),()->pickMergeTarget(group,others)));
        sheet.addView(panelSection(T.s("删除")));
        panelGap(sheet,panelAction("⌫",look.red(),T.s("删除这个额度"),T.s("今天已用时间保留，冻结规则仍会生效"),()->confirmRemove(group)));

        ThemeStore.dialog(this).setView(scroll).setNegativeButton(T.s("关闭"),null).show();
    }

    private void pickMergeTarget(final org.json.JSONObject from,final List<org.json.JSONObject> others){
        String[] labels=new String[others.size()];
        for(int i=0;i<labels.length;i++){org.json.JSONObject g=others.get(i);labels[i]=g.optString("name")+" · "+memberSummary(g)+T.f(" · 每天 %d 分钟",g.optInt("minutes"));}
        ThemeStore.dialog(this).setTitle(T.f("把「%s」并到",from.optString("name"))).setItems(labels,(d,which)->confirmMerge(others.get(which),from)).setNegativeButton(T.s("取消"),null).show();
    }
    private void confirmMerge(final org.json.JSONObject into,final org.json.JSONObject from){
        if(!quotaEditable()){toast(T.f("这一步会减少额度，只在收束时段 %s 内开放",TimeWindow.label(this)));return;}
        final int minutes=Math.min(into.optInt("minutes"),from.optInt("minutes"));
        ThemeStore.dialog(this).setTitle(T.s("合并成一个额度？"))
                .setMessage(T.f("「%s」的成员会并进「%s」，共用每天 %d 分钟（取两者较小值）。今天各自已用的分钟数照常累加进这个格子。",from.optString("name"),into.optString("name"),minutes))
                .setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("合并"),(d,w)->{
                    try{
                        org.json.JSONObject next=MemoryData.copy(DailyQuotaStore.snapshot(this).config);
                        if(!DailyQuotaData.mergeInto(next,into.optString("id"),from.optString("id"),minutes))throw new IllegalStateException(T.s("额度已不再存在"));
                        DailyQuotaData.validate(next);
                        DailyQuotaStore.save(this,next);rebuild();applyRules();
                        toast(T.s("已合并成一个额度"));
                    }catch(RuntimeException error){toast(error.getMessage());}
                }).show();
    }
    private void confirmRemove(final org.json.JSONObject group){
        if(!quotaEditable()){toast(T.f("这一步会减少额度，只在收束时段 %s 内开放",TimeWindow.label(this)));return;}
        ThemeStore.dialog(this).setTitle(T.s("删除这个额度？"))
                .setMessage(T.f("「%s」今天已用的时间会保留；重新添加同一只应用会接着累计。原有冻结规则仍然生效。",group.optString("name")))
                .setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("删除"),(d,w)->{
                    try{
                        org.json.JSONObject next=MemoryData.copy(DailyQuotaStore.snapshot(this).config);
                        org.json.JSONArray groups=DailyQuotaData.without(next,group.optString("id"));
                        DailyQuotaData.put(next,"groups",groups);
                        if(groups.length()==0)DailyQuotaData.put(next,"enabled",false);
                        DailyQuotaStore.save(this,next);rebuild();applyRules();
                        toast(T.s("已删除额度"));
                    }catch(RuntimeException error){toast(error.getMessage());}
                }).show();
    }
    /** 新建／修改额度：先选「单独」还是「共享」，再挑应用；勾多个应用就是共用一份。 */
    private void quotaEditor(final org.json.JSONObject group,String preset,boolean initialShared){
        quotaEditor(group,preset,initialShared,null,null,null);
    }

    private void quotaEditor(final org.json.JSONObject group,String preset,boolean initialShared,
                             String draftTitle,String draftMinutes,Set<String> draftChosen){
        // 收束时段外只能「加」：新应用、新额度、加分钟数随时都行；移出成员、调小要等时段
        final boolean growOnly=!quotaEditable();
        final Set<String> chosen=new java.util.LinkedHashSet<>();
        org.json.JSONArray original=group==null?null:group.optJSONArray("apps");
        if(original!=null)for(int i=0;i<original.length();i++)chosen.add(original.optString(i));
        if(draftChosen!=null)chosen.addAll(draftChosen);
        if(preset!=null)chosen.add(preset);
        final boolean[] shared={draftChosen!=null?initialShared:original!=null?original.length()>1:initialShared};
        if(!shared[0]&&chosen.size()>1){String first=chosen.iterator().next();chosen.clear();chosen.add(first);}
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(22),dp(6),dp(22),0);
        final TextView single=text(T.s("单独"),13,look.text2()),share=text(T.s("共享"),13,look.text2());
        single.setGravity(Gravity.CENTER);share.setGravity(Gravity.CENTER);
        single.setPadding(dp(6),dp(9),dp(6),dp(9));share.setPadding(dp(6),dp(9),dp(6),dp(9));
        LinearLayout modes=new LinearLayout(this);
        LinearLayout.LayoutParams singleLp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);singleLp.rightMargin=dp(8);
        modes.addView(single,singleLp);modes.addView(share,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        box.addView(modes);
        final TextView modeHint=text("",11,look.text3());box.addView(modeHint);
        final android.widget.EditText title=new android.widget.EditText(this);title.setSingleLine(true);title.setHint(T.s("额度名称（可选）"));title.setText(draftTitle!=null?draftTitle:group==null?"":group.optString("name"));box.addView(title);
        final android.widget.EditText minutes=new android.widget.EditText(this);minutes.setInputType(2);minutes.setHint(T.s("每天 1–1440 分钟"));minutes.setText(draftMinutes!=null?draftMinutes:""+(group==null?120:group.optInt("minutes")));box.addView(minutes);
        final TextView picked=text("",13,look.text2());box.addView(picked);
        final Runnable paint=()->{
            single.setTextColor(shared[0]?look.text2():look.onAccent());
            single.setBackground(look.round(shared[0]?look.surface2():look.accent(),14f));
            share.setTextColor(shared[0]?look.onAccent():look.text2());
            share.setBackground(look.round(shared[0]?look.accent():look.surface2(),14f));
            modeHint.setText(shared[0]?T.s("共享额度：勾几只应用，它们共用这一份分钟数。"):T.s("单独额度：只锁一只应用，额度用完就冻结。"));
        };
        final Runnable describe=()->picked.setText(chosen.isEmpty()?T.s("尚未选择应用"):memberSummary(chosen));
        paint.run();describe.run();
        single.setOnClickListener(v->{if(growOnly&&chosen.size()>1){toast(T.f("现在只能加：把成员减到一只要等收束时段 %s",TimeWindow.label(this)));return;}shared[0]=false;if(chosen.size()>1){String first=chosen.iterator().next();chosen.clear();chosen.add(first);}paint.run();describe.run();});
        share.setOnClickListener(v->{shared[0]=true;paint.run();describe.run();});
        final AlertDialog[] holder=new AlertDialog[1];
        box.addView(action(T.s("选择应用"),v->{
            if(growOnly&&!shared[0]&&!chosen.isEmpty()){toast(T.f("单独额度只有一只应用，换应用要等收束时段 %s",TimeWindow.label(this)));return;}
            List<String> apps=new ArrayList<>();
            for(String p:availableApps)if(!growOnly||!chosen.contains(p))apps.add(p);
            for(String p:chosen)if(!apps.contains(p))apps.add(p);
            apps.remove(getPackageName());
            if(apps.isEmpty()){toast(growOnly?T.s("所有应用都已经在这个额度里了"):T.s("应用列表正在加载，请稍后重试"));return;}
            quotaDraftGroup=group;quotaDraftChosen=new LinkedHashSet<>(chosen);quotaDraftShared=shared[0];quotaDraftGrowOnly=growOnly;
            quotaDraftTitle=title.getText().toString();quotaDraftMinutes=minutes.getText().toString();
            if(holder[0]!=null)holder[0].dismiss();
            Intent intent=new Intent(this,AppPickerActivity.class)
                    .putStringArrayListExtra(AppPickerActivity.EXTRA_CANDIDATES,new ArrayList<>(apps))
                    .putStringArrayListExtra(AppPickerActivity.EXTRA_PRESELECT,
                            growOnly?new ArrayList<String>():new ArrayList<String>(chosen))
                    .putExtra(AppPickerActivity.EXTRA_SINGLE,!shared[0])
                    .putExtra(AppPickerActivity.EXTRA_TITLE,shared[0]?T.s("选择共享成员"):T.s("选择一只应用"))
                    .putExtra(AppPickerActivity.EXTRA_HINT,shared[0]
                            ? T.s("选中的应用共用这一份额度，可多选")
                            : T.s("单独额度只放一只应用"));
            startActivityForResult(intent,REQ_QUOTA);
        }));
        final AlertDialog dialog=ThemeStore.dialog(this).setTitle(group==null?T.s("新建额度"):T.s("修改额度")).setView(box).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("保存"),null).create();
        holder[0]=dialog;
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                if(chosen.isEmpty())throw new IllegalArgumentException(T.s("先选应用"));
                if(!shared[0]&&chosen.size()>1)throw new IllegalArgumentException(T.s("单独额度只能放一只应用"));
                if(chosen.contains(getPackageName()))throw new IllegalArgumentException(T.s("不能限制守卫自身"));
                int limit=Integer.parseInt(minutes.getText().toString().trim());
                if(growOnly&&group!=null&&limit<group.optInt("minutes"))throw new IllegalArgumentException(T.f("现在只能加分钟数，调小要等收束时段 %s",TimeWindow.label(this)));
                String label=title.getText().toString().trim();
                if(label.isEmpty())label=chosen.size()==1?appLabel(chosen.iterator().next()):T.s("共用额度");
                org.json.JSONObject next=MemoryData.copy(DailyQuotaStore.snapshot(this).config);
                String id=group==null?java.util.UUID.randomUUID().toString():group.optString("id");
                org.json.JSONArray groups=DailyQuotaData.without(next,id);
                groups.put(new org.json.JSONObject().put("id",id).put("name",label).put("minutes",limit).put("apps",new org.json.JSONArray(chosen)));
                DailyQuotaData.put(next,"groups",groups);
                DailyQuotaData.put(next,"enabled",true);
                DailyQuotaData.validate(next);
                DailyQuotaStore.save(this,next);dialog.dismiss();rebuild();applyRules();
                toast(group==null?T.s("额度已建好"):T.s("额度已更新"));
            }catch(RuntimeException|org.json.JSONException error){toast(error.getMessage());}
        }));
        dialog.show();
    }
    private String appLabel(String pkg){
        String cached=appNames.get(pkg);
        return cached!=null&&!cached.isEmpty()?cached:Guard.labelOf(getPackageManager(),pkg);
    }
    private String memberSummary(org.json.JSONObject group){
        List<String> apps=new ArrayList<>();org.json.JSONArray list=group.optJSONArray("apps");
        if(list!=null)for(int i=0;i<list.length();i++)apps.add(list.optString(i));
        return memberSummary(apps);
    }
    private String memberSummary(java.util.Collection<String> apps){
        if(apps.isEmpty())return T.s("没有应用");
        if(apps.size()==1)return appLabel(apps.iterator().next());
        List<String> all=new ArrayList<>(apps);StringBuilder sb=new StringBuilder();
        for(int i=0;i<Math.min(2,all.size());i++){if(i>0)sb.append(T.s("、"));sb.append(appLabel(all.get(i)));}
        return sb.append(T.s(" · 共 ")).append(all.size()).append(T.s(" 个应用共用")).toString();
    }
    /** 旧全天额度（还没迁移）的条：点一下改分钟数。 */
    private void addLegacyBar(LinearLayout box,String pkg){
        UsageQuotaProgress bar=new UsageQuotaProgress(this);
        LinearLayout touch=new LinearLayout(this);touch.setOrientation(LinearLayout.VERTICAL);touch.setPadding(dp(6),dp(2),dp(6),dp(14));
        touch.addView(bar,new LinearLayout.LayoutParams(-1,dp(10)));
        touch.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((look.accent()&0x00ffffff)|0x22000000),null,look.round(0xffffffff,14)));
        touch.setOnClickListener(v->usageEditor(pkg,false));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(touch);legacyBars.put(bar,pkg);
    }
    private void refreshDailyCounters(){
        DailyQuotaData data=DailyQuotaStore.snapshot(this);boolean readable=DailyQuotaStore.readable(this,data);
        for(Map.Entry<UsageQuotaProgress,String> row:dailyBars.entrySet())for(int i=0;i<data.groups().length();i++){
            org.json.JSONObject group=data.groups().optJSONObject(i);if(!row.getValue().equals(group.optString("id")))continue;
            row.getKey().bind(data.used(group),group.optInt("minutes")*60000L,readable,data.enabled());
            describeQuota(row.getKey(),group.optString("name"),DailyQuotaStore.description(this,data,group));
        }
        for(Map.Entry<TextView,String> row:dailyTexts.entrySet())for(int i=0;i<data.groups().length();i++){
            org.json.JSONObject group=data.groups().optJSONObject(i);if(!row.getValue().equals(group.optString("id")))continue;
            org.json.JSONArray members=group.optJSONArray("apps");
            row.getKey().setText((members!=null&&members.length()>1?T.s("共用 ")+members.length()+T.s(" 个应用 · "):"")+DailyQuotaStore.description(this,data,group));
        }
        long now=GuardClock.now(this).getTimeInMillis();
        for(Map.Entry<UsageQuotaProgress,String> row:legacyBars.entrySet()){
            String pkg=row.getValue();int limit=AppUsageQuota.minutes(this,pkg);Rule rule=rules.get(pkg);boolean known=AppUsageQuota.permission(this),counted=known;long used=0;
            try{if(known)used=AppUsageQuota.used(this,pkg,rule,now);}catch(RuntimeException error){counted=false;}
            String status=!known?T.s("未授予使用情况权限，暂不计时"):counted?String.format(java.util.Locale.CHINA,T.s("已用 %.1f / %d 分钟 · 剩余 %.1f 分钟"),used/60000.0,limit,Math.max(0,limit*60000.0-used)/60000.0):T.s("用量暂不可读，保护性冻结");
            row.getKey().bind(used,limit*60000L,counted,limit>0);
            describeQuota(row.getKey(),Guard.labelOf(getPackageManager(),pkg),status);
        }
    }
    /** 条上不写字，读数通过无障碍说明交付；点按仍然进对应的额度设置。 */
    private void describeQuota(View bar,String name,String detail){
        ViewParent parent=bar.getParent();
        View touch=parent instanceof View?(View)parent:bar;
        touch.setContentDescription((name==null||name.isEmpty()?T.s("使用限额"):name)+" · "+detail);
    }
    private void usageEditor(String pkg,boolean allDay){
        if(DailyQuotaStore.snapshot(this).group(pkg)!=null){quotaEditor(DailyQuotaStore.snapshot(this).group(pkg),null,false);return;}
        if(lockedForMove(pkg)){toast(T.s("当前已冻结，解冻后再调整额度"));return;}
        // 先放开权限：没授权也能把额度存下来，只是不计时、不冻结；授权后自动开始累计。
        boolean counting=AppUsageQuota.permission(this);
        android.widget.EditText input=new android.widget.EditText(this);input.setInputType(2);input.setHint(T.s("每日分钟数（1–1440），0为取消"));input.setText(""+(AppUsageQuota.minutes(this,pkg)>0?AppUsageQuota.minutes(this,pkg):30));
        AlertDialog.Builder builder=ThemeStore.dialog(this).setTitle(Guard.labelOf(getPackageManager(),pkg)+(allDay?T.s(" · 全天额度"):T.s(" · 使用额度"))).setView(input)
                .setMessage(T.s("加入时段后仅累计时段内使用量，额度用完才冻结；未放入时段则全天累计。使用量按今天已有前台记录计算。取消时段内额度后恢复强制冻结。学习联动和禁忌惩罚仍独立生效。")
                        +(counting?"":T.s("\n未授予使用情况访问权限时只保存额度，不计时、不冻结。")))
                .setPositiveButton(T.s("保存"),null).setNegativeButton(T.s("取消"),null);
        if(!counting)builder.setNeutralButton(T.s("去授权"),(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        AlertDialog dialog=builder.create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{int limit=Integer.parseInt(input.getText().toString());if(limit<0||limit>1440)throw new IllegalArgumentException(T.s("额度须为0–1440分钟"));if(limit>0&&(!rules.containsKey(pkg)||allDay))RuleStore.put(this,pkg,FreezeSlotStore.withoutSchedule());AppUsageQuota.save(this,pkg,limit);dialog.dismiss();rebuild();applyRules();}catch(RuntimeException error){toast(error.getMessage());}}));dialog.show();
    }
    private AlertDialog applicationPanel;
    private void dismissApplicationPanel(){if(applicationPanel!=null){applicationPanel.dismiss();applicationPanel=null;}}
    private void appDetails(String pkg) {
        dismissApplicationPanel();
        Rule rule = RuleStore.get(this, pkg);
        org.json.JSONObject group = DailyQuotaStore.snapshot(this).group(pkg);
        int reason = statusReason(pkg);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(3), dp(20), dp(8));
        scroll.addView(sheet);
        sheet.addView(appDetailHeader(pkg, reason));

        // 状态优先：一句结论 + 一句原因，不再把所有模块状态拼成一大段。
        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setPadding(dp(14), dp(13), dp(14), dp(13));
        statusBox.setBackground(look.round((((reason<0?look.text3():reason == 0 ? look.green() : look.red()) & 0x00ffffff) | 0x14000000), 16f));
        TextView statusTitle = text(reason == 0 ? T.s("现在可以使用") : reasonLabel(reason), 16,
                reason<0?look.text3():reason == 0 ? look.green() : look.red());
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusBox.addView(statusTitle);
        String why = reason<0?T.s("正在后台核验冻结状态，请稍候。") : reason == 1 ? T.s("当前正好落在自身设置的冻结时段里，过点会自动放开。")
                : reason == 2 ? T.s("20:00 后打卡没完成，学习联动会一直冻到打完；打完还要走完打卡罚时。")
                : reason == 3 ? T.s("禁忌惩罚的延长冻结还没结束。")
                : reason == 4 ? AppUsageQuota.status(this, pkg, rule, System.currentTimeMillis())
                : reason == 5 ? DailyQuotaStore.status(this, pkg)
                : reason == 6 ? T.s("钥匙应用的解锁条件还没达标。")
                : rule == null ? T.s("这只应用还没加入冻结时段。") : T.s("当前不在任何冻结时段内。");
        TextView statusWhy = text(why, 12, look.text2());
        statusWhy.setLineSpacing(dp(3), 1f);
        statusWhy.setPadding(0, dp(5), 0, 0);
        statusBox.addView(statusWhy);
        panelGap(sheet, statusBox);

        if (rule != null) {
            LinearLayout metrics = panelMetricRow();
            metrics.addView(panelMetric(T.s("冻结安排"), rule.freeze ? T.f("%d 段", rule.windowCount()) : T.s("无定时"), look.text()),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, -2, 1f);
            mid.leftMargin = dp(8); mid.rightMargin = dp(8);
            metrics.addView(panelMetric(T.s("生效星期"), Rule.daysText(rule.days), look.text2()), mid);
            metrics.addView(panelMetric(T.s("每日额度"), group == null ? T.s("未设") : T.f("%d 分钟", group.optInt("minutes")),
                    group == null ? look.text3() : look.accent()), new LinearLayout.LayoutParams(0, -2, 1f));
            sheet.addView(metrics, new LinearLayout.LayoutParams(-1, -2));

            sheet.addView(panelSection(T.s("规则详情")));
            panelGap(sheet, panelNote(T.s("自身时段") + "\n" + timeTitle(rule).replace('\n', ' ')
                    + (group != null ? "\n" + DailyQuotaStore.status(this, pkg) : "")));
            String extension = StudyGate.extensionStatus(this, pkg);
            if (extension != null && !extension.trim().isEmpty()) panelGap(sheet, panelNote(extension));
            if (AppUsageQuota.minutes(this, pkg) > 0 && reason != 4)
                panelGap(sheet, panelNote(AppUsageQuota.status(this, pkg, rule, System.currentTimeMillis())));
            panelGap(sheet, panelNote(T.s("长按应用可拖到其他冻结时段；从应用库拖入即可加入新的时段。")));
        } else if (group != null) {
            sheet.addView(panelSection(T.s("额度")));
            panelGap(sheet, panelNote(DailyQuotaStore.status(this, pkg)));
        } else {
            panelGap(sheet, panelNote(T.s("还没有安排。先选择一个冻结时段，再从应用库把它拖进去；只想限制时长可以直接设使用额度。")));
        }

        sheet.addView(panelSection(T.s("操作")));
        sheet.addView(panelSection(T.s("冻结时段")));
        for(FreezeSlotStore.Slot slot:slots)panelGap(sheet,panelAction("◷",look.accent(),timeTitle(slot.rule),Rule.daysText(slot.rule.days),()->{dismissApplicationPanel();moveApp(pkg,slot.id);}));
        panelGap(sheet, panelAction("◔", look.accent(), T.s("使用额度"),
                group == null ? T.s("设置每日可用分钟数") : T.s("修改这个额度的成员和分钟数"),
                () -> {dismissApplicationPanel();usageEditor(pkg, false);}));
        if (rule != null) {
            panelGap(sheet, panelAction("⌫", look.red(), T.s("移出冻结列表"),
                    T.f("需要收束时段 %s；刚加入的十分钟内可直接纠错", TimeWindow.label(this)), () -> {
                        if (!TimeWindow.on(this) && !Guard.canRemove(this, pkg)) {
                            toast(T.f("移出冻结列表要等收束时段 %s", TimeWindow.label(this)));
                            return;
                        }
                        RuleStore.remove(this, pkg); rebuild(); applyRules();
                    }));
        }

        sheet.addView(panelSection(T.s("断网与修复")));
        boolean netSelected=NetBlockStore.packages(this).contains(pkg);
        panelGap(sheet,panelNote((netSelected?T.s("此应用已加入断网名单"):T.s("此应用未加入断网名单"))+"\n"+NetBlockStore.status(this)));
        panelGap(sheet,panelAction("◉",look.accent(),netSelected?T.s("从断网名单移出"):T.s("加入断网名单"),T.f("修改只在调整时段 %s 开放",NetWindow.label(this)),()->{
            if(!netEditAllowed())return;
            Set<String> selected=new LinkedHashSet<>(NetBlockStore.packages(this));if(selected.contains(pkg))selected.remove(pkg);else selected.add(pkg);
            NetBlockStore.setPackages(this,selected);NetBlockService.stop(this);if(selected.isEmpty())NetBlockStore.setOn(this,false);
            dismissApplicationPanel();updateNetHint();if(NetBlockStore.on(this)&&!vpnGranted())askVpn();else appDetails(pkg);
        }));
        panelGap(sheet,panelAction("⚙",look.accent(),T.s("断网设置"),T.s("名单、运行模式及代理规则"),()->{dismissApplicationPanel();netSettings();}));
        if(!AppUsageQuota.permission(this))panelGap(sheet,panelAction("!",look.red(),T.s("授权使用情况访问"),T.s("缺少权限，无法统计应用使用时长"),()->{dismissApplicationPanel();startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS));}));
        if(!Guard.isOwner(this))panelGap(sheet,panelAction("!",look.red(),T.s("部署与授权修复"),T.s("尚未取得设备所有者授权，查看部署步骤"),()->{dismissApplicationPanel();SetupGuide.show(this);}));
        applicationPanel=ThemeStore.dialog(this).setView(scroll).setNegativeButton(T.s("关闭"), null).create();applicationPanel.show();
    }
    private boolean canAdd() {
        if (rules.isEmpty() || Config.inGrace(this)) return true;
        for (String pkg : rules.keySet()) if (!Guard.lockedNow(this, pkg)) return true;
        return false;
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_VPN) {
            if (result == RESULT_OK) NetBlockService.reconcile(this);
            else {
                // 授权窗若恰好跨出调整时段，不能借返回结果在时段外关掉设置。
                if (NetWindow.inside(this)) NetBlockStore.setOn(this, false);
                toast(T.s("没给 VPN 授权，断网开不了"));
            }
            rebuild();
            if (result == RESULT_OK && screen != null) screen.post(this::netSettings);
            return;
        }
        if (request == REQ_NET) {
            boolean pending = false;
            if (result == RESULT_OK && data != null) {
                ArrayList<String> picked = data.getStringArrayListExtra("picked");
                // 选择页可能一直开到调整时段结束；真正写入前必须再检查一次。
                if (picked != null && !netEditAllowed()) picked = null;
                if (picked != null) {
                    NetBlockStore.setPackages(this, new LinkedHashSet<String>(picked));
                    // 名单变了：旧隧道是按旧名单建的，先收掉；调整时段内本来就不该有隧道，
                    // 时段一结束由 reconcile 按新名单重开。
                    NetBlockService.stop(this);
                    if (picked.isEmpty()) NetBlockStore.setOn(this, false);
                    else if (NetBlockStore.on(this) && !vpnGranted()) pending = askVpn();
                }
            }
            rebuild();
            // 授权窗自己会带着结果回来，这时别先弹一个再弹第二个
            if (!pending && screen != null) screen.post(this::netSettings);
            return;
        }
        if (request == REQ_QUOTA) {
            if (quotaDraftChosen != null) {
                ArrayList<String> picked = result == RESULT_OK && data != null
                        ? data.getStringArrayListExtra("picked") : null;
                LinkedHashSet<String> chosen = picked == null
                        ? new LinkedHashSet<>(quotaDraftChosen) : new LinkedHashSet<>(picked);
                // 跨出可编辑时段后，已进格子的应用不能被这次返回偷偷拿掉。
                if (picked != null && !quotaEditable()) chosen.addAll(quotaDraftChosen);
                quotaEditor(quotaDraftGroup, null, quotaDraftShared,
                        quotaDraftTitle, quotaDraftMinutes, chosen);
            }
            quotaDraftGroup = null; quotaDraftChosen = null;
            return;
        }
        if (request == REQ_KEY) {
            if (result == RESULT_OK && data != null) {
                ArrayList<String> picked = data.getStringArrayListExtra("picked");
                if (picked != null) {
                    Set<String> selected = new LinkedHashSet<>(picked);
                    for (String pkg : selected) if (!rules.containsKey(pkg)) RuleStore.put(this, pkg, FreezeSlotStore.withoutSchedule());
                    RuleStore.setKeyApps(this, selected);
                    rebuild(); applyRules();
                    toast(T.f("关键应用已更新 · %d 个", selected.size()));
                }
            }
            return;
        }
        if (request != PICK || result != RESULT_OK || data == null) return;
        ArrayList<String> picked = data.getStringArrayListExtra("picked");
        if (picked == null) return;
        rules = RuleStore.all(this);
        boolean allowAdd = canAdd();
        int added = 0;
        int held = 0;
        for (String pkg : rules.keySet()) if (!picked.contains(pkg)) {
            if (TimeWindow.on(this) || Guard.canRemove(this, pkg)) RuleStore.remove(this, pkg); else held++;
        }
        int skipped = 0;
        for (String pkg : picked) if (!rules.containsKey(pkg)) {
            if (allowAdd && windowOpen()) { RuleStore.put(this, pkg, FreezeSlotStore.withoutSchedule()); added++; } else skipped++;
        }
        rebuild(); if (added > 0) { toast(T.s("已加入应用库，长按拖入冻结时段")); applyRules(); }
        else applyRules();
        if (held > 0) toast(T.f("%d 个应用要等收束时段 %s 才能移出", held, TimeWindow.label(this)));
        if (skipped > 0) toast(T.f("%d 个新应用要等收束时段 %s 才能加入", skipped, TimeWindow.label(this)));
    }
    private void settings() {
        boolean reduce = getSharedPreferences(Ui.SP, 0).getBoolean("reduce_motion", false);
        ThemeStore.dialog(this).setTitle(T.s("冻结应用设置"))
                .setItems(new String[]{T.s("新建冻结时段"), T.s("关键应用名单"), reduce ? T.s("开启动画效果") : T.s("减少动态效果"), T.s("按包名添加"), T.s("立即应用")}, (d,w) -> {
                    if (w == 0) editSlot(null);
                    if (w == 1) keyApps();
                    if (w == 2) { getSharedPreferences(Ui.SP, 0).edit().putBoolean("reduce_motion", !reduce).apply(); rebuild(); }
                    if (w == 3) addPackage();
                    if (w == 4) { Config.arm(this); applyRules(); }
                }).show();
    }
    /* ==================== 底部设置：冻结 / 断网 ==================== */

    /**
     * 底部设置卡：冻结应用设置和断网应用设置并排。
     *
     * 这两件事本来是同一个问题的两个面 —— 一个不让打开，一个打开了也连不上 ——
     * 所以放在同一页的同一组里，调「今天要多紧」时不用来回跳页。
     */
    private void settingsArea() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView caption = text(T.s("设置"), 12, look.text3());
        caption.setTypeface(Typeface.DEFAULT_BOLD);
        caption.setPadding(dp(12), dp(6), 0, dp(8));
        box.addView(caption);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackground(look.round(look.surface(), 20));
        card.setElevation(dp(look.amap ? 2 : 7));

        TextView freezeSub = subText();
        freezeSub.setText(T.f("%d 个应用 · %d 个时段", rules.size(), slots.size()));
        card.addView(settingsRow("❄", look.accent(), T.s("冻结应用设置"), freezeSub, v -> settings()));

        View split = new View(this);
        split.setBackgroundColor(look.line());
        LinearLayout.LayoutParams splitLp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        splitLp.leftMargin = dp(58);
        splitLp.rightMargin = dp(12);
        card.addView(split, splitLp);

        netHint = subText();
        netHint.setText(netSummary());
        card.addView(settingsRow("⊘", look.red(), T.s("断网应用设置"), netHint, v -> netSettings()));

        box.addView(card);
        slotsView.addView(box, gap(16));
    }

    private TextView subText() {
        TextView t = text("", 11, look.text3());
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** 一行设置：图标 + 标题 + 小字 + 箭头。和额度菜单里那种行同一个长相。 */
    private View settingsRow(String glyph, int color, String title, TextView subtitle, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(12), dp(10), dp(12));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((look.accent() & 0x00ffffff) | 0x22000000),
                null, look.round(0xffffffff, 14)));
        TextView icon = text(glyph, 15, 0xFFFFFFFF);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(look.round(color, 12f));
        row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, dp(8), 0);
        labels.addView(text(title, 15, look.text()));
        if (subtitle != null) labels.addView(subtitle);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text("›", 18, look.text3()));
        row.setOnClickListener(click);
        return row;
    }

    /** 断网那行小字：选了几个、开没开、隧道通没通，都在这一句里。 */
    private String netSummary() {
        int n = NetBlockStore.packages(this).size();
        if (n == 0) return T.s("还没选应用");
        if (!NetBlockStore.on(this)) return T.f("%d 个应用 · 没开", n);
        return NetBlockStore.status(this);
    }

    private void updateNetHint() {
        if (netHint != null) netHint.setText(netSummary());
    }

    /** 「代理占着 VPN 时」那一行的当前值。 */
    private String fallbackLabel() {
        return NetBlockStore.fallbackMode(this) == NetBlockStore.FALLBACK_FREEZE
                ? T.s("改用冻结这些应用") : T.s("只提示不处理");
    }

    /** 「断网方式」那一行的当前值。 */
    private String modeLabel() {
        return NetBlockStore.proxyMode(this) ? T.s("我常开代理（不占 VPN）") : T.s("自动（本 App 用系统 VPN）");
    }

    /** 断网名单、开关和调整时段本身都属于设置，只允许在断网调整时段内修改。 */
    private boolean netEditAllowed() {
        if (NetWindow.canEdit(this)) return true;
        toast(T.f("断网设置只能在调整时段 %s 内修改", NetWindow.label(this)));
        return false;
    }

    /** 断网设置：开关、名单、时段、连接方式；时段外仅可查看。 */
    private void netSettings() {
        if (isFinishing() || isDestroyed()) return;
        final boolean editable = netEditAllowed();
        final java.util.Set<String> selected = NetBlockStore.packages(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(3), dp(20), dp(8));
        scroll.addView(sheet);

        sheet.addView(panelHeader("⊘", NetBlockStore.on(this) ? look.red() : look.text3(),
                T.s("断网应用"), NetBlockStore.status(this)));
        if (!editable) {
            panelGap(sheet, panelNote(T.f("现在只能查看。名单、开关、时段和连接方式只在调整时段 %s 内开放。", NetWindow.label(this))));
        }

        LinearLayout master = new LinearLayout(this);
        master.setGravity(Gravity.CENTER_VERTICAL);
        master.setPadding(dp(14), dp(14), dp(12), dp(14));
        master.setBackground(look.round(look.surface2(), 17f));
        LinearLayout masterCopy = new LinearLayout(this);
        masterCopy.setOrientation(LinearLayout.VERTICAL);
        TextView masterTitle = text(T.s("开启断网"), 16, look.text());
        masterTitle.setTypeface(Typeface.DEFAULT_BOLD);
        masterCopy.addView(masterTitle);
        final TextView masterState = text("", 12, look.text2());
        masterState.setPadding(0, dp(3), 0, 0);
        masterCopy.addView(masterState);
        master.addView(masterCopy, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView toggleValue = panelPill("", look.text3());
        master.addView(toggleValue);
        panelGap(sheet, master);

        final Runnable paint = () -> {
            boolean on = NetBlockStore.on(this);
            toggleValue.setText(on ? T.s("已开启") : T.s("已关闭"));
            toggleValue.setTextColor(on ? look.green() : look.text3());
            toggleValue.setBackground(look.round((((on ? look.green() : look.text3()) & 0x00ffffff) | 0x18000000), 99f));
            masterState.setText(on ? T.s("到点自动断网") : T.s("不会拦截任何应用"));
            masterState.setTextColor(on ? look.green() : look.text3());
        };

        final AlertDialog dialog = ThemeStore.dialog(this).setView(scroll)
                .setNegativeButton(T.s("关闭"), null).create();
        master.setOnClickListener(v -> {
            if (!netEditAllowed()) return;
            if (NetBlockStore.on(this)) {
                NetBlockStore.setOn(this, false);
                NetBlockService.stop(this);
                paint.run();
                updateNetHint();
                return;
            }
            if (NetBlockStore.packages(this).isEmpty()) {
                toast(T.s("先选要断网的应用"));
                return;
            }
            NetBlockStore.setOn(this, true);
            if (!vpnGranted()) {
                dialog.dismiss();
                askVpn();
                return;
            }
            NetBlockService.reconcile(this);
            if (NetWindow.inside(this))
                toast(T.f("已开启，%s 结束后开始断网", NetWindow.label(this)));
            paint.run();
            handler.postDelayed(paint, 900);
            updateNetHint();
            handler.postDelayed(this::updateNetHint, 900);
        });

        sheet.addView(panelSection(T.s("拦截名单")));
        if (!selected.isEmpty()) {
            LinearLayout identity = new LinearLayout(this);
            identity.setGravity(Gravity.CENTER_VERTICAL);
            identity.addView(packageStrip(selected, 34), new LinearLayout.LayoutParams(0, -2, 1f));
            identity.addView(panelPill(T.f("%d 个应用", selected.size()), look.red()));
            panelGap(sheet, identity);
        }
        panelGap(sheet, panelAction("▤", look.red(), T.s("断网的应用"),
                selected.isEmpty() ? T.s("还没有选择应用") : T.f("%d 个应用连不上服务器", selected.size()),
                () -> {
                    if (!netEditAllowed()) return;
                    dialog.dismiss();
                    pickNetApps();
                }));
        panelGap(sheet, panelAction("◷", look.accent(), T.s("调整时段"), NetWindow.label(this), () -> {
            if (!netEditAllowed()) return;
            NetWindow.pick(this, () -> {
                updateNetHint();
                dialog.dismiss();
                screen.post(this::netSettings);
            });
        }));

        sheet.addView(panelSection(T.s("连接方式")));
        // 断网方式：自动（占 VPN）或「我常开代理」（不占 VPN，交给代理规则）。
        panelGap(sheet, panelAction("⇄", look.accent(), T.s("断网方式"), modeLabel(), () -> {
            if (!netEditAllowed()) return;
            String[] labels = {T.s("自动（本 App 用系统 VPN）"), T.s("我常开代理（不占 VPN）")};
            int checked = NetBlockStore.proxyMode(this) ? 1 : 0;
            ThemeStore.dialog(this).setTitle(T.s("断网方式"))
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        NetBlockStore.setProxyMode(this, which == 1);
                        d.dismiss();
                        updateNetHint();
                        NetBlockService.reconcile(this);
                        dialog.dismiss();
                        screen.post(this::netSettings);
                    })
                    .setNegativeButton(T.s("取消"), null).show();
        }));
        // 系统只允许一个 VPN：代理占着时隧道建不起来，这里决定「断网」怎么退而求其次。
        panelGap(sheet, panelAction("◇", look.text2(), T.s("代理占着 VPN 时"), fallbackLabel(), () -> {
            if (!netEditAllowed()) return;
            final int[] modes = {NetBlockStore.FALLBACK_FREEZE, NetBlockStore.FALLBACK_NOTIFY};
            String[] labels = {T.s("改用冻结这些应用"), T.s("只提示不处理")};
            int checked = NetBlockStore.fallbackMode(this) == NetBlockStore.FALLBACK_FREEZE ? 0 : 1;
            ThemeStore.dialog(this).setTitle(T.s("代理占着 VPN 时"))
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        NetBlockStore.setFallbackMode(this, modes[which]);
                        d.dismiss();
                        updateNetHint();
                        NetFallback.reconcile(this);
                        dialog.dismiss();
                        screen.post(this::netSettings);
                    })
                    .setNegativeButton(T.s("取消"), null).show();
        }));

        sheet.addView(panelSection(T.s("工具")));
        final String proxyPkg = "com.follow.clash";
        boolean proxyInstalled = true;
        try {
            getPackageManager().getApplicationInfo(proxyPkg, 0);
        } catch (Exception missing) {
            proxyInstalled = false;
        }
        if (proxyInstalled) {
            panelGap(sheet, panelAction("↗", look.text2(), T.s("打开代理 App"), T.s("FlClash"), () -> {
                try {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(proxyPkg);
                    if (launch != null) startActivity(launch);
                } catch (RuntimeException ignored) {
                }
            }));
        }
        panelGap(sheet, panelAction("⧉", look.text2(), T.s("复制代理拦截规则"),
                T.s("交给代理完成拦截，本 App 不占 VPN"), () -> {
                    String snippet = ProxyRules.snippet(NetBlockStore.packages(this));
                    copyText(snippet);
                    toast(T.s("已复制。粘到代理 App 的覆写/配置 rules: 里（放在 MATCH 之前），断网就由代理完成；本 App 仍显示「被占用」是正常的。"));
                }));

        final View notes = panelNote(T.s("只接管选中的应用，别的应用照常上网。用系统 VPN 通道实现，所以和代理、公司内网、加速器互斥。")
                + "\n\n" + T.s("应用会表现为转圈、超时，而不是弹「已断网」；视频类通常还会显示缓存过的内容。")
                + "\n\n" + T.f("断网默认常开：除了调整时段 %s 内自动放开，其余时间一直断网；开关、名单和调整时段本身都只能在此时段内修改。", NetWindow.label(this))
                + "\n\n" + T.s("系统同一时间只允许一个 VPN：代理（FlClash 等）占着时隧道建不起来。选「改用冻结这些应用」后，时段外名单里的应用会被直接冻结（打不开，也就等于没网），时段内自动解冻；名单里别放必须用的应用。")
                + "\n\n" + T.s("常开代理的人，断网交给代理做：点「复制代理拦截规则」，把内容放进代理 App 的覆写/配置 rules:（MATCH 之前）。"));
        notes.setVisibility(View.GONE);
        final TextView toggleNotes = text(T.s("查看使用限制 ›"), 12, look.accent());
        toggleNotes.setPadding(dp(4), dp(12), dp(4), dp(6));
        final boolean[] open = {false};
        toggleNotes.setOnClickListener(v -> {
            open[0] = !open[0];
            notes.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            toggleNotes.setText(open[0] ? T.s("收起说明 ›") : T.s("查看使用限制 ›"));
        });
        sheet.addView(toggleNotes);
        panelGap(sheet, notes);

        paint.run();
        dialog.show();
    }

    /** 设置弹窗里的一行：左边标题，右边值（值由调用方自己塞进去）。 */
    private LinearLayout dialogRow(String title) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(13), dp(10), dp(13));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((look.accent() & 0x00ffffff) | 0x22000000),
                null, look.round(0xffffffff, 14)));
        row.addView(text(title, 14, look.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout dialogRow(String title, TextView value) {
        LinearLayout row = dialogRow(title);
        row.addView(value);
        return row;
    }

    /* ---------- 统一详情面板：身份区、状态指标、成组操作 ---------- */

    private LinearLayout panelHeader(String glyph, int color, String title, String subtitle) {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), dp(4), dp(2), dp(14));
        TextView icon = text(glyph, 20, 0xFFFFFFFF);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(look.round(color, 16f));
        head.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(14), 0, 0, 0);
        TextView name = text(title, 21, look.text());
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(name);
        TextView sub = text(subtitle, 12, look.text2());
        sub.setMaxLines(3);
        sub.setLineSpacing(dp(2), 1f);
        sub.setPadding(0, dp(4), 0, 0);
        copy.addView(sub);
        head.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        return head;
    }

    private TextView panelSection(String title) {
        TextView section = text(title, 11, look.text3());
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setLetterSpacing(0.08f);
        section.setPadding(dp(4), dp(14), dp(4), dp(7));
        return section;
    }

    private LinearLayout panelMetricRow() {
        LinearLayout row = new LinearLayout(this);
        row.setClipToPadding(false);
        return row;
    }

    private View panelMetric(String label, String value, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(11), dp(12), dp(12));
        box.setBackground(look.round(look.surface2(), 14f));
        TextView number = text(value, 16, color);
        number.setTypeface(Typeface.DEFAULT_BOLD);
        number.setSingleLine(true);
        number.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(number);
        TextView caption = text(label, 10, look.text3());
        caption.setPadding(0, dp(3), 0, 0);
        box.addView(caption);
        return box;
    }

    private View panelAction(String glyph, int color, String title, String subtitle, Runnable run) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(10), dp(12));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((look.accent() & 0x00ffffff) | 0x1F000000),
                look.round(look.surface2(), 16f), look.round(0xffffffff, 16f)));
        TextView icon = text(glyph, 15, color);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(look.round((color & 0x00ffffff) | 0x18000000, 12f));
        row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, dp(8), 0);
        copy.addView(text(title, 15, color == look.red() ? look.red() : look.text()));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 11, look.text3());
            sub.setPadding(0, dp(2), 0, 0);
            sub.setMaxLines(2);
            copy.addView(sub);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(text("›", 18, look.text3()));
        row.setOnClickListener(v -> run.run());
        return row;
    }

    private TextView panelPill(String value, int color) {
        TextView pill = text(value, 11, color);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(9), dp(4), dp(9), dp(4));
        pill.setBackground(look.round((color & 0x00ffffff) | 0x18000000, 99f));
        return pill;
    }

    private View panelNote(String value) {
        TextView note = text(value, 12, look.text2());
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(dp(12), dp(11), dp(12), dp(11));
        note.setBackground(look.round(look.surface2(), 13f));
        return note;
    }

    private void panelGap(LinearLayout box, View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        box.addView(view, lp);
    }

    private View packageStrip(java.util.Collection<String> packages, int size) {
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        int count = 0;
        for (String pkg : packages) {
            if (count >= 6) break;
            ImageView icon = new ImageView(this);
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
            try { icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); } catch (Exception ignored) { }
            icon.setBackground(look.round(0xFFFFFFFF, 9f));
            icon.setClipToOutline(true);
            icon.setContentDescription(appLabel(pkg));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
            if (count > 0) lp.leftMargin = dp(6);
            strip.addView(icon, lp);
            count++;
        }
        if (packages.size() > 6) {
            TextView more = text("+" + (packages.size() - 6), 11, look.text2());
            more.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
            lp.leftMargin = dp(6);
            strip.addView(more, lp);
        }
        return strip;
    }

    private LinearLayout memberStrip(org.json.JSONObject group, int size) {
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        org.json.JSONArray apps = group.optJSONArray("apps");
        int count = apps == null ? 0 : apps.length();
        for (int i = 0; i < Math.min(6, count); i++) {
            String pkg = apps.optString(i);
            ImageView icon = new ImageView(this);
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
            try { icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); } catch (Exception ignored) { }
            icon.setBackground(look.round(0xFFFFFFFF, 9f));
            icon.setClipToOutline(true);
            icon.setContentDescription(appLabel(pkg));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
            if (i > 0) lp.leftMargin = dp(6);
            strip.addView(icon, lp);
        }
        if (count > 6) {
            TextView more = text("+" + (count - 6), 11, look.text2());
            more.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
            lp.leftMargin = dp(6);
            strip.addView(more, lp);
        }
        return strip;
    }

    private View appDetailHeader(String pkg, int reason) {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), dp(4), dp(2), dp(14));
        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.sym_def_app_icon);
        try { icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); } catch (Exception ignored) { }
        icon.setBackground(look.round(0xFFFFFFFF, 14f));
        icon.setClipToOutline(true);
        head.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(appLabel(pkg), 21, look.text());
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(name);
        TextView id = text(pkg, 10, look.text3());
        id.setSingleLine(true);
        id.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        id.setPadding(0, dp(3), 0, 0);
        copy.addView(id);
        head.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(panelPill(reason == 0 ? T.s("当前可用") : reasonLabel(reason),
                reason<0?look.text3():reason == 0 ? look.green() : look.red()));
        return head;
    }

    /** 系统给的 VPN 授权还在不在（第一次开要弹系统窗）。 */
    private boolean vpnGranted() {
        try { return VpnService.prepare(this) == null; }
        catch (RuntimeException error) { return false; }
    }

    /**
     * 第一次开要系统授权，之后直接开。
     *
     * @return true 表示要去系统窗口走授权，调用方别再弹自己的弹窗（等回来再说）
     */
    private boolean askVpn() {
        try {
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) {
                startActivityForResult(prepare, REQ_VPN);
                return true;
            }
            // 调整时段内本来就放开：授权先拿到手，隧道等时段结束由 reconcile 拉起。
            if (!NetWindow.inside(this)) NetBlockService.start(this);
        } catch (RuntimeException error) {
            toast(T.s("这台系统不允许建 VPN"));
            if (NetWindow.inside(this)) NetBlockStore.setOn(this, false);
        }
        updateNetHint();
        return false;
    }

    private void pickNetApps() {
        if (!netEditAllowed()) return;
        Intent intent = new Intent(this, AppPickerActivity.class)
                .putStringArrayListExtra(AppPickerActivity.EXTRA_PRESELECT, new ArrayList<String>(NetBlockStore.packages(this)))
                .putExtra(AppPickerActivity.EXTRA_HINT, T.s("这些应用连不上任何服务器"));
        startActivityForResult(intent, REQ_NET);
    }

    /** 关键应用名单：走统一选择器，只把冻结/额度相关应用作为候选。 */
    private void keyApps() {
        if (!windowOpen()) { toast(T.f("关键应用名单要等收束时段 %s 才能改", TimeWindow.label(this))); return; }
        Set<String> candidates = new LinkedHashSet<>(rules.keySet());
        candidates.addAll(DailyQuotaStore.packages(this));
        candidates.addAll(RuleStore.keyApps(this));
        Intent intent = new Intent(this, AppPickerActivity.class)
                .putStringArrayListExtra(AppPickerActivity.EXTRA_PRESELECT,
                        new ArrayList<String>(RuleStore.keyApps(this)))
                .putStringArrayListExtra(AppPickerActivity.EXTRA_CANDIDATES, new ArrayList<String>(candidates))
                .putExtra(AppPickerActivity.EXTRA_TITLE, T.s("关键应用名单"))
                .putExtra(AppPickerActivity.EXTRA_HINT, T.s("不受学习闸门延长；自身时段和额度仍然生效"));
        startActivityForResult(intent, REQ_KEY);
    }
    private void paintCount(TextView view, Set<String> selected, List<String> all) {
        if (view == null) return;
        view.setText(T.s("已选 ") + selected.size() + " / " + all.size());
    }
    /** 名单里那行小字：这只应用现在归哪个时段、有没有额度、此刻是不是冻结着。 */
    private String stateLine(String pkg) {
        StringBuilder sb = new StringBuilder();
        Rule rule = rules.get(pkg);
        if (rule == null) sb.append(T.s("待安排"));
        else if (FreezeSlotStore.unassigned(rule)) sb.append(T.s("全天"));
        else sb.append(timeTitle(rule).replace('\n', ' '));
        org.json.JSONObject group = DailyQuotaStore.snapshot(this).group(pkg);
        if (group != null) {
            org.json.JSONArray apps = group.optJSONArray("apps");
            sb.append(" · ").append(apps != null && apps.length() > 1 ? T.s("共享格子 ") : T.s("额度 ")).append(group.optInt("minutes")).append(T.s(" 分钟/日"));
        }
        int reason = statusReason(pkg);
        if (reason != 0) sb.append(" · ").append(reasonLabel(reason));
        return sb.toString();
    }
    private void addPackage() {
        if (!canAdd()) { toast(T.s("放行后再添加应用")); return; }
        android.widget.EditText name = new android.widget.EditText(this); name.setHint("com.example.app"); name.setSingleLine(true);
        ThemeStore.dialog(this).setTitle(T.s("按包名添加")).setView(name).setNegativeButton(T.s("取消"), null).setPositiveButton(T.s("添加"), (d,w) -> {
            String pkg = name.getText().toString().trim();
            if (!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) { toast(T.s("包名格式不正确")); return; }
            if (!rules.containsKey(pkg)) RuleStore.put(this, pkg, FreezeSlotStore.withoutSchedule());
            rebuild(); applyRules();
        }).show();
    }
    private void applyRules() {
        applies++;
        new Thread(() -> { Guard.apply(this); runOnUiThread(() -> { applies--; if (!isFinishing()) refreshBadges(screen); }); }, "afs-board-apply").start();
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void editSlot(final FreezeSlotStore.Slot old) {
        new SlotEditor(old).show();
    }
    private final class SlotEditor {
        private final FreezeSlotStore.Slot old;
        private int days;
        private final List<int[]> windows = new ArrayList<>();
        private LinearLayout timeRows;
        private final CheckBox[] checks = new CheckBox[7];
        private Rule legacy;
        private boolean changed;
        SlotEditor(FreezeSlotStore.Slot old) {
            this.old = old;
            Rule rule = old == null ? new Rule(127, new int[][]{{22*60, 23*60}}, true, true) : old.rule.freezeEditorRule();
            if (rule == null) { legacy = old.rule; rule = new Rule(127, new int[0][2], true, true); }
            days = rule.days;
            for (int[] pair : rule.windows()) windows.add(pair);
        }
        void show() {
            LinearLayout form = new LinearLayout(FreezeBoardActivity.this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(18), dp(10), dp(18), dp(12));
            if (legacy != null) { TextView note = text(T.s("原计划保留；选择新的冻结时间后才会替换。\n") + legacy.freezeSummary(), 12, Ui.L_TEXT2); form.addView(note); }
            TextView label = text(T.s("冻结星期"), 14, Ui.L_TEXT); form.addView(label);
            LinearLayout dayRow = new LinearLayout(FreezeBoardActivity.this);
            String[] names = {T.s("周一"),T.s("周二"),T.s("周三"),T.s("周四"),T.s("周五"),T.s("周六"),T.s("周日")};
            for (int i = 0; i < 7; i++) {
                final int bit = i;
                LinearLayout cell = new LinearLayout(FreezeBoardActivity.this); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
                CheckBox c = new CheckBox(FreezeBoardActivity.this); c.setChecked((days & (1 << i)) != 0); checks[i] = c;
                c.setOnCheckedChangeListener((v,on) -> { days = on ? days | (1 << bit) : days & ~(1 << bit); changed = true; });
                TextView t = text(names[i], 12, Ui.L_TEXT); cell.addView(t); cell.addView(c); dayRow.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
            }
            form.addView(dayRow);
            timeRows = new LinearLayout(FreezeBoardActivity.this); timeRows.setOrientation(LinearLayout.VERTICAL); form.addView(timeRows); refreshTimes();
            TextView add = text(T.s("＋ 添加一段冻结时间"), 14, look.accent()); add.setPadding(0,dp(15),0,dp(15));
            add.setOnClickListener(v -> pickTime(-1)); form.addView(add);
            TextView help = text(T.s("选中的时段内禁止使用；开始与结束相同表示全天冻结。"), 12, Ui.L_TEXT2); form.addView(help);
            ScrollView wrap = new ScrollView(FreezeBoardActivity.this); wrap.addView(form);
            AlertDialog dialog = ThemeStore.dialog(FreezeBoardActivity.this).setTitle(old == null ? T.s("新建冻结时段") : T.s("编辑冻结时段"))
                    .setView(wrap).setNegativeButton(T.s("取消"), null).setPositiveButton(T.s("保存"), null)
                    .setNeutralButton(old == null ? "" : T.s("删除空时段"), null).create();
            dialog.setOnShowListener(ignored -> {
                if (old == null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    Rule rule = legacy != null && !changed ? legacy : new Rule(days, windows.toArray(new int[0][]), true, true);
                    if (rule.freeze && (days == 0 || windows.isEmpty())) { toast(T.s("请选择冻结星期和至少一段时间")); return; }
                    List<FreezeSlotStore.Slot> current = FreezeSlotStore.all(FreezeBoardActivity.this);
                    for (FreezeSlotStore.Slot slot : current) if ((old == null || !old.id.equals(slot.id)) && FreezeSlotStore.scheduleKey(rule).equals(FreezeSlotStore.scheduleKey(slot.rule))) {
                        toast(T.s("已有相同冻结时段，直接把应用拖进去即可")); return;
                    }
                    List<String> members = old == null ? new ArrayList<>() : FreezeSlotStore.members(old, RuleStore.all(FreezeBoardActivity.this));
                    for (String pkg : members) if (Guard.lockedNow(FreezeBoardActivity.this, pkg)) { toast(T.s("时段内有正在冻结的应用，放行后再修改")); return; }
                    if (old == null) current.add(new FreezeSlotStore.Slot(java.util.UUID.randomUUID().toString(), rule));
                    else for (FreezeSlotStore.Slot slot : current) if (slot.id.equals(old.id)) slot.rule = rule;
                    for (String pkg : members) RuleStore.put(FreezeBoardActivity.this, pkg, rule);
                    FreezeSlotStore.save(FreezeBoardActivity.this, current); dialog.dismiss(); rebuild(); applyRules();
                });
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    if (old == null) return;
                    if (!FreezeSlotStore.members(old, RuleStore.all(FreezeBoardActivity.this)).isEmpty()) { toast(T.s("先把应用移到其他时段，再删除空格")); return; }
                    List<FreezeSlotStore.Slot> current = FreezeSlotStore.all(FreezeBoardActivity.this);
                    for (int i = current.size()-1; i >= 0; i--) if (old.id.equals(current.get(i).id)) current.remove(i);
                    FreezeSlotStore.save(FreezeBoardActivity.this, current); dialog.dismiss(); rebuild();
                });
            }); dialog.show();
        }
        void refreshTimes() {
            timeRows.removeAllViews();
            for (int i = 0; i < windows.size(); i++) {
                final int index = i; int[] w = windows.get(i);
                LinearLayout row = new LinearLayout(FreezeBoardActivity.this); row.setGravity(Gravity.CENTER_VERTICAL);
                TextView t = text(Rule.hm(w[0]/60,w[0]%60) + " — " + (w[0] > w[1] ? T.s("次日 ") : "") + Rule.hm(w[1]/60,w[1]%60), 18, Ui.L_TEXT);
                t.setPadding(0,dp(12),0,dp(12)); t.setOnClickListener(v -> pickTime(index)); row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
                TextView remove = text(T.s("移除"), 12, Ui.L_TEXT2); remove.setPadding(dp(12),dp(12),0,dp(12));
                remove.setOnClickListener(v -> { windows.remove(index); changed=true; refreshTimes(); }); row.addView(remove); timeRows.addView(row);
            }
        }
        void pickTime(int index) {
            int start = index < 0 ? 22*60 : windows.get(index)[0];
            int end = index < 0 ? 23*60 : windows.get(index)[1];
            TimePickerDialog first = new TimePickerDialog(FreezeBoardActivity.this, (v,h,m) -> {
                int from = h*60+m;
                TimePickerDialog second = new TimePickerDialog(FreezeBoardActivity.this, (v2,h2,m2) -> {
                    int[] pair = {from,h2*60+m2}; if(index<0) windows.add(pair); else windows.set(index,pair);
                    changed=true; refreshTimes();
                }, end/60,end%60,true); second.setTitle(T.s("冻结结束时间")); second.show();
            },start/60,start%60,true); first.setTitle(T.s("冻结开始时间")); first.show();
        }
    }
}
