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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    private Ui look;
    private LinearLayout screen, slotsView, library;
    private ScrollView scroll;
    private List<FreezeSlotStore.Slot> slots;
    private Map<String, Rule> rules;
    private String ruleSnapshot = "";
    private Calendar statusNow;
    private Set<String> statusKeys;
    private boolean statusHold, statusTaboo;
    private TextView gateNote;   // 学习联动还差什么（点一下跳到对应页面）
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
    private int libraryGeneration;
    private boolean disposed;
    private Runnable fillLibrary;
    private final java.util.concurrent.ExecutorService catalogWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Map<String,String> appNames = new java.util.HashMap<>();
    @Override protected void onDestroy() {
        disposed=true;libraryGeneration++;catalogWorker.shutdownNow();
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
    private void immersive() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(0);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(look.bg()));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
    @Override protected void onResume() {
        super.onResume();
        if (look != null && look.dark != Ui.isDark(this)) { recreate(); return; }
        immersive();
        // 规则比对要序列化全部规则；放到首帧之后再跑，别和切页入场动画抢同一帧
        if (screen != null) screen.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (!ruleSnapshot.equals(RuleStore.exportRules(this))) rebuild();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
    }
    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(autoScroll);
        if (dragging) { pendingDrop = null; finishDrag(); }
    }
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!dragging && !sheetMoving && screen != null) {
                if (!ruleSnapshot.equals(RuleStore.exportRules(FreezeBoardActivity.this))) rebuild();
                else { refreshBadges(screen); for (FreezeTimelineView timeline : timelines) timeline.invalidate(); }
            }
            handler.postDelayed(this, 1000);
        }
    };
    private boolean motion() {
        return !getSharedPreferences(Ui.SP, 0).getBoolean("reduce_motion", false)
                && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
    }
    private int dp(float n) { return look.dp(n); }
    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private TextView action(String s, View.OnClickListener click) {
        TextView t = text(s, 13, look.accent()); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(10), dp(12), dp(10)); t.setOnClickListener(click); return t;
    }
    private void rebuild() {
        if (libraryAnimator != null) libraryAnimator.cancel();
        int oldY = scroll == null ? 0 : scroll.getScrollY();
        rules = RuleStore.all(this); slots = FreezeSlotStore.all(this);
        statusSnapshot();
        timelines.clear(); libraryApps.clear();
        libraryGeneration++;fillLibrary=null;
        ruleSnapshot = RuleStore.exportRules(this);
        screen = new LinearLayout(this); screen.setOrientation(LinearLayout.VERTICAL);
        look.page(screen); screen.setPadding(dp(8), dp(24), dp(8), dp(16));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("冻结应用", 27, look.text()); title.setTypeface(Typeface.DEFAULT_BOLD);
        SwissTypography.role(title,SwissTypography.Role.TITLE);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(action("设置", v -> settings()));
        TextView hint = text("蓝色表示定时冻结 · 学习联动另计", 12, look.text2());
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
        scroll.setPadding(0, dp(14), 0, dp(8));
        slotsView = new LinearLayout(this); slotsView.setOrientation(LinearLayout.VERTICAL);
        slotsView.setClipChildren(false); slotsView.setClipToPadding(false); scroll.addView(slotsView);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (slots.isEmpty()) {
            TextView empty = text("还没有冻结时段\n在设置里新建时间格，再拖入应用", 16, look.text2());
            empty.setPadding(dp(18), dp(30), dp(18), dp(30)); slotsView.addView(empty);
        }
        for (FreezeSlotStore.Slot slot : slots) slotsView.addView(slotCard(slot), gap(16));
        slotsView.addView(gateNote,gateLp);
        slotsView.addView(action("冻结设置",v->settings()));
        buildLibrary(); setContentView(FloatingNav.wrap(this,screen,2)); immersive();
        updateGateNote();
        scroll.setOnDragListener((view, event) -> {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return validDrag(event);
            if (event.getAction() == DragEvent.ACTION_DRAG_LOCATION) trackDrag(view, event);
            if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) finishDrag();
            return true;
        });
        scroll.post(() -> scroll.scrollTo(0, oldY));
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
        TextView caption = text("冻结时段  /  " + members.size() + " 个应用", 11, look.text2());
        header.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(action("编辑", v -> editSlot(slot))); card.addView(header);
        FreezeTimelineView timeline = new FreezeTimelineView(this, slot.rule); timelines.add(timeline);
        card.addView(timeline, new LinearLayout.LayoutParams(-1, dp(64)));
        TextView days = text((slot.rule.freeze ? Rule.daysText(slot.rule.days) + " · " : "")
                + timeTitle(slot.rule).replace('\n', ' '), 12, look.text2());
        days.setPadding(0, dp(3), 0, dp(13)); card.addView(days);
        LinearLayout row = null;
        for (int i = 0; i < members.size(); i++) {
            if (i % 2 == 0) { row = new LinearLayout(this); row.setPadding(0, 0, 0, dp(8)); card.addView(row); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 2 == 1) lp.leftMargin = dp(8);
            row.addView(appTile(members.get(i), false), lp);
            if (i == members.size() - 1 && i % 2 == 0) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        }
        final TextView drop = text(members.isEmpty() ? "拖入应用，放到这个时间里" : "长按应用可移动到其他时段", 12, look.text3());
        drop.setGravity(Gravity.CENTER); drop.setPadding(dp(8), dp(10), dp(8), dp(10));
        drop.setBackground(look.round(look.surface2(), 10)); card.addView(drop);
        card.setOnDragListener((v, e) -> {
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: return validDrag(e);
                case DragEvent.ACTION_DRAG_ENTERED:
                    card.setBackground(slotBackground(true)); drop.setText("松手放入这个时段"); drop.setTextColor(look.accent());
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
    private GradientDrawable slotBackground(boolean active) {
        return look.round(look.surface(), 22, active ? look.accent() : look.line(), active ? 2 : 0.6f);
    }
    private void resetTarget(View card, TextView drop, boolean empty) {
        card.setBackground(slotBackground(false)); drop.setText(empty ? "拖入应用，放到这个时间里" : "长按应用可移动到其他时段");
        drop.setTextColor(look.text3());
        if (motion()) card.animate().translationZ(0).scaleX(1).scaleY(1).setDuration(170).start();
        else { card.setTranslationZ(0); card.setScaleX(1); card.setScaleY(1); }
    }
    private String timeTitle(Rule rule) {
        if (!rule.freeze) return "原有冻结计划";
        if (rule.days == 0 || rule.windowCount() == 0) return "无定时冻结";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < rule.windowCount(); i++) {
            if (i > 0) s.append("\n");
            int a = rule.startAt(i), b = rule.endAt(i);
            if (a == b) s.append("全天冻结");
            else s.append(Rule.hm(a / 60, a % 60)).append(" — ").append(a > b ? "次日 " : "").append(Rule.hm(b / 60, b % 60));
        }
        return s.toString();
    }
    private void buildLibrary() {
        libraryHidden = getSharedPreferences(Ui.SP, 0).getBoolean("freeze_library_hidden", false);
        library = new LibrarySheet(); library.setOrientation(LinearLayout.VERTICAL);
        library.setPadding(dp(12), dp(5), dp(12), dp(7)); library.setBackground(look.round(look.surface(), 20));
        libraryHandle = text("", 11, look.text3()); libraryHandle.setGravity(Gravity.CENTER);
        libraryHandle.setOnClickListener(v -> setLibraryHidden(!libraryHidden, true));
        libraryHandle.setContentDescription("展开或收起应用库");
        library.addView(libraryHandle, new LinearLayout.LayoutParams(-1, dp(24)));
        libraryContent = new LinearLayout(this); libraryContent.setOrientation(LinearLayout.VERTICAL);
        library.addView(libraryContent, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        final TextView label = text("应用库", 14, look.text()); label.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(text("长按拖入时段", 11, look.text3())); libraryContent.addView(head);
        final HorizontalScrollView tray = new HorizontalScrollView(this); tray.setHorizontalScrollBarEnabled(false);
        final LinearLayout apps = new LinearLayout(this);
        tray.addView(apps);libraryContent.addView(tray,new LinearLayout.LayoutParams(-1,dp(104)));screen.addView(library);
        final int generation=libraryGeneration;
        final Set<String> enrolled=new LinkedHashSet<>(rules.keySet());
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
                label.setText("应用库  ·  "+ordered.size());
                // Keep the full catalog, but inflate only tiles near the viewport.
                final List<android.widget.FrameLayout> cells=new ArrayList<>();
                for(String pkg:ordered) {
                    android.widget.FrameLayout cell=new android.widget.FrameLayout(this);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(100),dp(104));lp.rightMargin=dp(8);
                    apps.addView(cell,lp);cells.add(cell);
                }
                final Runnable populate=()->{
                    if(disposed||generation!=libraryGeneration||libraryHidden)return;
                    int first=Math.max(0,tray.getScrollX()/dp(108)-1);
                    int last=Math.min(ordered.size(),(tray.getScrollX()+Math.max(tray.getWidth(),dp(320)))/dp(108)+2);
                    for(int i=first;i<last;i++)if(cells.get(i).getChildCount()==0)
                        cells.get(i).addView(appTile(ordered.get(i),true),new android.widget.FrameLayout.LayoutParams(-1,-1));
                };
                fillLibrary=populate;
                tray.setOnScrollChangeListener((v,x,y,ox,oy)->populate.run());
                tray.postOnAnimation(populate);
            });
        });
        libraryContent.setVisibility(libraryHidden ? View.GONE : View.VISIBLE);
        updateLibraryHandle();
    }
    private void updateLibraryHandle() {
        libraryHandle.setText(libraryHidden ? "应用库  ·  上滑展开" : "━━");
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
        ViewGroup.LayoutParams lp = libraryContent.getLayoutParams(); lp.height = height; libraryContent.setLayoutParams(lp);
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
    private View appTile(final String pkg, boolean compact) {
        LinearLayout tile = new LinearLayout(this); tile.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL); tile.setPadding(dp(10), dp(10), dp(10), dp(10));
        tile.setBackground(look.round(look.surface2(), 13));
        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.sym_def_app_icon);
        try { icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); } catch (Exception ignored) { }
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
        tile.setOnLongClickListener(v -> {
            if (lockedForMove(pkg)) { toast("应用正在冻结，解冻后才能移动"); return true; }
            dragging = true; dragSource = tile; pendingDrop = null;
            boolean started = tile.startDragAndDrop(ClipData.newPlainText("freeze-app", pkg), new LiftShadow(tile), pkg, 0);
            if (!started) { dragging = false; dragSource = null; return true; }
            tile.setAlpha(0.35f); tile.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            handler.post(autoScroll); return true;
        });
        if (pkg.equals(landed) && !compact && motion()) {
            tile.setScaleX(1.05f); tile.setScaleY(1.05f);
            tile.animate().scaleX(1).scaleY(1).setDuration(260).setInterpolator(new OvershootInterpolator(1.1f)).start();
        }
        return tile;
    }
    private void refreshBadges(View view) {
        if (view == screen) {statusSnapshot();updateGateNote();for (LibraryApp app : libraryApps) updateLibraryApp(app);}
        if (view instanceof TextView && view.getTag() instanceof String) {
            String pkg = (String)view.getTag(); int reason = statusReason(pkg); boolean blocked = reason != 0;
            TextView t = (TextView)view; t.setText(Guard.blockReasonText(reason) + (statusKeys.contains(pkg) ? " · 关键" : ""));
            t.setTextColor(blocked ? look.red() : look.green());
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) refreshBadges(((ViewGroup)view).getChildAt(i));
    }
    private void statusSnapshot() {
        statusNow=Calendar.getInstance();statusKeys=RuleStore.keyApps(this);
        statusHold=StudyGate.holding(this,statusNow);
        statusTaboo=StudyGate.tabooHolding(statusNow.getTimeInMillis(),StudyGate.tabooUntil(this));
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
            toast("禁忌惩罚到点自动解除，没有解除按钮");
        }
    }
    private int statusReason(String pkg) {
        Rule rule=rules.get(pkg);return rule==null?0:Guard.blockReason(rule,statusKeys.contains(pkg),statusHold,statusTaboo,statusNow,
                CardStore.rewardUntil(this,statusNow.getTimeInMillis())>0);
    }
    private void updateLibraryApp(LibraryApp app) {
        int reason = app.installed ? statusReason(app.pkg) : 0;
        // The library reflects the phone's current suspension state, not a future schedule.
        boolean frozen = false;
        if (app.installed) {
            try { frozen = (getPackageManager().getApplicationInfo(app.pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0; }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        }
        boolean muted = frozen;
        if (muted) {
            android.graphics.ColorMatrix matrix = new android.graphics.ColorMatrix(); matrix.setSaturation(0);
            app.icon.setColorFilter(new android.graphics.ColorMatrixColorFilter(matrix));
        } else app.icon.clearColorFilter();
        app.tile.setAlpha(muted ? .5f : 1f);
        app.name.setTextColor(muted ? look.text3() : look.text());
        app.detail.setText(!app.installed ? "未安装" : frozen ? (reason == 0 ? "已冻结" : Guard.blockReasonText(reason))
                : (!rules.containsKey(app.pkg) || FreezeSlotStore.unassigned(rules.get(app.pkg))) ? "待安排" : "可使用");
        app.detail.setTextColor(muted ? look.text3() : look.text2());
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
        if (RuleStore.packages(this).contains(pkg) && Guard.lockedNow(this, pkg)) return true;
        try {
            return (getPackageManager().getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
    private void moveApp(String pkg, String targetId) {
        if (!availableApps.contains(pkg)) { toast("应用已不在应用库中"); rebuild(); return; }
        if (lockedForMove(pkg)) { toast("应用正在冻结，解冻后才能移动"); rebuild(); return; }
        FreezeSlotStore.Slot target = null;
        for (FreezeSlotStore.Slot slot : FreezeSlotStore.all(this)) if (slot.id.equals(targetId)) target = slot;
        if (target == null) { toast("这个时段已不存在"); rebuild(); return; }
        RuleStore.put(this, pkg, target.rule); landed = pkg; rebuild(); landed = null; applyRules();
        toast("已放入 " + timeTitle(target.rule).replace('\n', ' '));
    }
    private void chooseDestination(String pkg) {
        String[] labels = new String[slots.size() + 1];
        for (int i = 0; i < slots.size(); i++) labels[i] = timeTitle(slots.get(i).rule).replace('\n', ' ') + " · " + Rule.daysText(slots.get(i).rule.days);
        labels[slots.size()] = "当前状态 / 应用详情";
        ThemeStore.dialog(this).setTitle(Guard.labelOf(getPackageManager(), pkg)).setItems(labels, (d, which) -> {
            if (which < slots.size()) moveApp(pkg, slots.get(which).id); else appDetails(pkg);
        }).setNegativeButton("取消", null).show();
    }
    private void appDetails(String pkg) {
        if (!rules.containsKey(pkg)) {
            ThemeStore.dialog(this).setTitle(Guard.labelOf(getPackageManager(), pkg))
                    .setMessage("尚未设置冻结时段。长按应用，拖入上方时段即可安排冻结。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        int reason = Guard.blockReasonNow(this, pkg);
        String explanation = reason == 2 ? "\n20:00 后学习打卡未完成，学习闸门正在延长冻结；完成后恢复原时段。"
                : reason == 3 ? "\n禁忌卡认领后的延长冻结尚未结束。" : "";
        ThemeStore.dialog(this).setTitle(Guard.labelOf(getPackageManager(), pkg))
                .setMessage("当前状态：" + Guard.blockReasonText(reason) + "\n自身时段："
                        + RuleStore.get(this, pkg).freezeSummary() + explanation + "\n\n长按应用可拖到其他冻结时段。")
                .setPositiveButton("关闭", null).setNeutralButton("移出冻结列表", (d,w) -> {
                    if (!Guard.canRemove(this, pkg)) { toast("加入后不能移出，刚加入的十分钟内可纠正"); return; }
                    RuleStore.remove(this, pkg); rebuild(); applyRules();
                }).show();
    }
    private boolean canAdd() {
        if (rules.isEmpty() || Config.inGrace(this)) return true;
        for (String pkg : rules.keySet()) if (!Guard.lockedNow(this, pkg)) return true;
        return false;
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null) return;
        ArrayList<String> picked = data.getStringArrayListExtra("picked");
        if (picked == null) return;
        rules = RuleStore.all(this);
        boolean allowAdd = canAdd();
        int added = 0;
        int kept = 0;
        for (String pkg : rules.keySet()) if (!picked.contains(pkg)) {
            if (Guard.canRemove(this, pkg)) RuleStore.remove(this, pkg); else kept++;
        }
        for (String pkg : picked) if (!rules.containsKey(pkg) && allowAdd) { RuleStore.put(this, pkg, FreezeSlotStore.withoutSchedule()); added++; }
        rebuild(); if (added > 0) { toast("已加入应用库，长按拖入冻结时段"); applyRules(); }
        else applyRules();
        if (kept > 0) toast("已有应用已保留，加入后不能直接移出");
    }
    private void settings() {
        boolean reduce = getSharedPreferences(Ui.SP, 0).getBoolean("reduce_motion", false);
        ThemeStore.dialog(this).setTitle("冻结应用设置")
                .setItems(new String[]{"新建冻结时段", "关键应用名单", reduce ? "开启动画效果" : "减少动态效果", "按包名添加", "立即应用"}, (d,w) -> {
                    if (w == 0) editSlot(null);
                    if (w == 1) keyApps();
                    if (w == 2) { getSharedPreferences(Ui.SP, 0).edit().putBoolean("reduce_motion", !reduce).apply(); rebuild(); }
                    if (w == 3) addPackage();
                    if (w == 4) { Config.arm(this); applyRules(); }
                }).show();
    }
    private void keyApps() {
        final Set<String> selected = new LinkedHashSet<>(RuleStore.keyApps(this));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(18), dp(10), dp(18), dp(12));
        TextView hint = text("关键应用只按自身冻结时段执行，不受学习闸门延长。", 13, Ui.L_TEXT2); content.addView(hint);
        for (String pkg : rules.keySet()) {
            CheckBox check = new CheckBox(this); check.setText(Guard.labelOf(getPackageManager(), pkg)); check.setTextColor(Ui.L_TEXT); check.setChecked(selected.contains(pkg));
            check.setOnCheckedChangeListener((button, on) -> { if (on) selected.add(pkg); else selected.remove(pkg); }); content.addView(check);
        }
        ScrollView wrap = new ScrollView(this); wrap.addView(content);
        ThemeStore.dialog(this).setTitle("关键应用名单").setView(wrap).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d,w) -> { RuleStore.setKeyApps(this, selected); rebuild(); applyRules(); }).show();
    }
    private void addPackage() {
        if (!canAdd()) { toast("放行后再添加应用"); return; }
        android.widget.EditText name = new android.widget.EditText(this); name.setHint("com.example.app"); name.setSingleLine(true);
        ThemeStore.dialog(this).setTitle("按包名添加").setView(name).setNegativeButton("取消", null).setPositiveButton("添加", (d,w) -> {
            String pkg = name.getText().toString().trim();
            if (!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) { toast("包名格式不正确"); return; }
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
            if (legacy != null) { TextView note = text("原计划保留；选择新的冻结时间后才会替换。\n" + legacy.freezeSummary(), 12, Ui.L_TEXT2); form.addView(note); }
            TextView label = text("冻结星期", 14, Ui.L_TEXT); form.addView(label);
            LinearLayout dayRow = new LinearLayout(FreezeBoardActivity.this);
            String[] names = {"一","二","三","四","五","六","日"};
            for (int i = 0; i < 7; i++) {
                final int bit = i;
                LinearLayout cell = new LinearLayout(FreezeBoardActivity.this); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
                CheckBox c = new CheckBox(FreezeBoardActivity.this); c.setChecked((days & (1 << i)) != 0); checks[i] = c;
                c.setOnCheckedChangeListener((v,on) -> { days = on ? days | (1 << bit) : days & ~(1 << bit); changed = true; });
                TextView t = text(names[i], 12, Ui.L_TEXT); cell.addView(t); cell.addView(c); dayRow.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
            }
            form.addView(dayRow);
            timeRows = new LinearLayout(FreezeBoardActivity.this); timeRows.setOrientation(LinearLayout.VERTICAL); form.addView(timeRows); refreshTimes();
            TextView add = text("＋ 添加一段冻结时间", 14, look.accent()); add.setPadding(0,dp(15),0,dp(15));
            add.setOnClickListener(v -> pickTime(-1)); form.addView(add);
            TextView help = text("选中的时段内禁止使用；开始与结束相同表示全天冻结。", 12, Ui.L_TEXT2); form.addView(help);
            ScrollView wrap = new ScrollView(FreezeBoardActivity.this); wrap.addView(form);
            AlertDialog dialog = ThemeStore.dialog(FreezeBoardActivity.this).setTitle(old == null ? "新建冻结时段" : "编辑冻结时段")
                    .setView(wrap).setNegativeButton("取消", null).setPositiveButton("保存", null)
                    .setNeutralButton(old == null ? "" : "删除空时段", null).create();
            dialog.setOnShowListener(ignored -> {
                if (old == null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    Rule rule = legacy != null && !changed ? legacy : new Rule(days, windows.toArray(new int[0][]), true, true);
                    if (rule.freeze && (days == 0 || windows.isEmpty())) { toast("请选择冻结星期和至少一段时间"); return; }
                    List<FreezeSlotStore.Slot> current = FreezeSlotStore.all(FreezeBoardActivity.this);
                    for (FreezeSlotStore.Slot slot : current) if ((old == null || !old.id.equals(slot.id)) && FreezeSlotStore.scheduleKey(rule).equals(FreezeSlotStore.scheduleKey(slot.rule))) {
                        toast("已有相同冻结时段，直接把应用拖进去即可"); return;
                    }
                    List<String> members = old == null ? new ArrayList<>() : FreezeSlotStore.members(old, RuleStore.all(FreezeBoardActivity.this));
                    for (String pkg : members) if (Guard.lockedNow(FreezeBoardActivity.this, pkg)) { toast("时段内有正在冻结的应用，放行后再修改"); return; }
                    if (old == null) current.add(new FreezeSlotStore.Slot(java.util.UUID.randomUUID().toString(), rule));
                    else for (FreezeSlotStore.Slot slot : current) if (slot.id.equals(old.id)) slot.rule = rule;
                    for (String pkg : members) RuleStore.put(FreezeBoardActivity.this, pkg, rule);
                    FreezeSlotStore.save(FreezeBoardActivity.this, current); dialog.dismiss(); rebuild(); applyRules();
                });
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    if (old == null) return;
                    if (!FreezeSlotStore.members(old, RuleStore.all(FreezeBoardActivity.this)).isEmpty()) { toast("先把应用移到其他时段，再删除空格"); return; }
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
                TextView t = text(Rule.hm(w[0]/60,w[0]%60) + " — " + (w[0] > w[1] ? "次日 " : "") + Rule.hm(w[1]/60,w[1]%60), 18, Ui.L_TEXT);
                t.setPadding(0,dp(12),0,dp(12)); t.setOnClickListener(v -> pickTime(index)); row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
                TextView remove = text("移除", 12, Ui.L_TEXT2); remove.setPadding(dp(12),dp(12),0,dp(12));
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
                }, end/60,end%60,true); second.setTitle("冻结结束时间"); second.show();
            },start/60,start%60,true); first.setTitle("冻结开始时间"); first.show();
        }
    }
}
