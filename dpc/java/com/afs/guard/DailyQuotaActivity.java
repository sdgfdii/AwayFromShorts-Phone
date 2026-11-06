package com.afs.guard;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Independent/shared allowances follow each app into its schedule. */
public final class DailyQuotaActivity extends ThemedActivity {
    private Ui ui;private LinearLayout body;private final Handler handler=new Handler();
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final List<String> catalog=new ArrayList<>();private boolean destroyed;private TextView window;
    private final Map<String,String> names=new HashMap<>();private final Map<TextView,String> counters=new HashMap<>();private final List<View> controls=new ArrayList<>();private final Map<TextView,UsageQuotaProgress> bars=new HashMap<>();
    /** 跨设备格子的明细行：group id → 那一块 TextView。 */
    private final Map<TextView,String> lanDetails=new LinkedHashMap<>();
    private final Runnable tick=new Runnable(){public void run(){refreshLabels();handler.postDelayed(this,1000);}};
    @Override protected void onCreate(Bundle state){super.onCreate(state);ui=new Ui(this);render();
        worker.execute(()->{Set<String> found=new HashSet<>();Map<String,String> labels=new HashMap<>();for(android.content.pm.ApplicationInfo app:getPackageManager().getInstalledApplications(0))if(!app.packageName.equals(getPackageName())&&getPackageManager().getLaunchIntentForPackage(app.packageName)!=null){found.add(app.packageName);labels.put(app.packageName,Guard.labelOf(getPackageManager(),app.packageName));}
            runOnUiThread(()->{if(destroyed)return;names.putAll(labels);catalog.addAll(found);java.text.Collator collator=java.text.Collator.getInstance(Locale.CHINA);Collections.sort(catalog,(a,b)->collator.compare(name(a),name(b)));String pkg=getIntent().getStringExtra("add_quota_app");getIntent().removeExtra("add_quota_app");if(pkg!=null)edit(null,new LinkedHashSet<>(),pkg);});});
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(tick);handler.post(tick);worker.execute(()->{try{DailyQuotaStore.refresh(this);}catch(RuntimeException ignored){}runOnUiThread(()->{if(!destroyed)refreshLabels();});});}
    @Override protected void onPause(){handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy();}
    private String name(String pkg){String value=names.get(pkg);return value==null?Guard.labelOf(getPackageManager(),pkg):value;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private void hint(LinearLayout box,String value){TextView t=text(value,13,ui.text2());t.setPadding(0,ui.dp(8),0,ui.dp(10));box.addView(t);}
    /** 常开：补额度随时都能做。 */
    private static final int ALWAYS=0;
    /** 只在 22:00–23:00 开放：凡是会「减少」额度的动作都走这条。 */
    private static final int WINDOW_ONLY=1;
    private TextView action(LinearLayout box,String label,int mode,Runnable run){TextView t=text(label,15,ui.accent());t.setPadding(ui.dp(12),ui.dp(13),ui.dp(12),ui.dp(13));t.setBackground(ui.round(ui.surface2(),14));box.addView(t,ui.gap(8));if(mode==WINDOW_ONLY)controls.add(t);t.setOnClickListener(v->{if(mode==WINDOW_ONLY&&!canReduce()){toast(T.f("移除或减少要等调整时段 %s",TimeWindow.label(this)));return;}try{run.run();}catch(RuntimeException error){toast(error.getMessage());}});return t;}
    private void toast(String value){Toast.makeText(this,value==null?T.s("操作未完成"):value,Toast.LENGTH_SHORT).show();}
    /** 能不能「减」：移出应用、调小分钟数、删格子、关总开关。只在编辑窗口里为真。 */
    private boolean canReduce(){return DailyQuotaStore.editable(this);}
    private void render(){Bundle position=PageScrollState.capture(this);counters.clear();controls.clear();bars.clear();lanDetails.clear();ScrollView scroll=new ScrollView(this);ui.page(scroll);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(36));scroll.addView(body);
        action(body,T.s("‹  冻结应用"),ALWAYS,this::finish);body.addView(ui.title(T.s("使用限额")));hint(body,T.f("一个格子放一个应用，就是它单独的一份额度；放多个应用，就是它们共用同一份额度。应用放进冻结时段后，只累计所绑时段内的用量，额度用完才冻结；未绑定时段则全天累计。%s重置，改格子不清空已用时间。加到格子里的操作随时都能做（加应用、加分钟数）；移出应用、调小分钟数、删格子只在调整时段 %s 内开放。学习联动与禁忌惩罚继续生效。",DayBoundary.label(this),TimeWindow.label(this)));
        window=text("",13,ui.accent());body.addView(window);
        action(body,T.f("调整时段 · %s",TimeWindow.label(this)),ALWAYS,()->TimeWindow.pick(this,this::render));
        DailyQuotaData d=DailyQuotaStore.snapshot(this);CheckBox enabled=new CheckBox(this);enabled.setText(T.s("启用每日限额"));enabled.setTextColor(ui.text());enabled.setChecked(d.enabled());body.addView(enabled);enabled.setOnClickListener(v->{if(!canReduce()&&!enabled.isChecked()){enabled.setChecked(true);toast(T.f("关闭限额要等调整时段 %s",TimeWindow.label(this)));return;}JSONObject next=MemoryData.copy(d.config);DailyQuotaData.put(next,"enabled",enabled.isChecked());save(next);});
        action(body,T.s("使用情况访问权限"),ALWAYS,()->startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        for(int i=0;i<d.groups().length();i++){JSONObject g=d.groups().optJSONObject(i);LinearLayout box=ui.card();body.addView(box,ui.gap(12));
            // 跨设备格子：标题上带标记，下面摊开「手机这一份 / 电脑这一份」，
            // 再给一个进设置面板的按钮 —— 和电脑面板那条格子的按钮是同一个意思。
            boolean shared=LanQuota.isShared(this,g.optString("id"));
            box.addView(ui.title(shared?g.optString("name")+T.s(" · 跨设备"):g.optString("name")));JSONArray apps=g.optJSONArray("apps");StringBuilder labels=new StringBuilder();for(int j=0;j<apps.length();j++){if(j>0)labels.append(T.s("、"));String pkg=apps.optString(j);Rule rule=RuleStore.all(this).get(pkg);labels.append(name(pkg)).append(rule==null||FreezeSlotStore.unassigned(rule)?T.s("（全天）"):T.s("（所绑时段）"));}Set<String> burningApps=DailyQuotaData.backgroundApps(g);StringBuilder burningLabel=new StringBuilder();
            for(String bApp:burningApps){if(burningLabel.length()>0)burningLabel.append(T.s("、"));burningLabel.append(name(bApp));}
            hint(box,(apps.length()>1?T.s("共用 · "):"")+labels+(burningLabel.length()>0?T.s("\n挂后台计时：")+burningLabel:""));
            TextView counter=text("",16,ui.text());box.addView(counter);counters.put(counter,g.optString("id"));UsageQuotaProgress bar=new UsageQuotaProgress(this);LinearLayout.LayoutParams barLp=new LinearLayout.LayoutParams(-1,ui.dp(8));barLp.topMargin=ui.dp(8);barLp.bottomMargin=ui.dp(14);box.addView(bar,barLp);bars.put(counter,bar);
            if(shared){TextView lan=text("",12,ui.text3());lan.setPadding(0,ui.dp(2),0,ui.dp(10));box.addView(lan);lanDetails.put(lan,g.optString("id"));}
            action(box,T.s("修改应用和时长"),ALWAYS,()->edit(g,new LinkedHashSet<>(),null));
            // 挂后台也计时：直接摆在额度卡上，一眼能看到「谁在挂后台也计时」，点开就能勾，
            // 不用再钻进「修改应用和时长」对话框里找。解决「找不到入口」的反馈。
            final Set<String> cardBg=new LinkedHashSet<>(burningApps);
            action(box,backgroundLabel(cardBg),ALWAYS,()->pickBackground(g,cardBg,this::render,true));
            action(box,shared?T.s("⇄ 跨设备设置"):T.s("⇄ 设为跨设备共享"),ALWAYS,()->{
                if(shared){LanQuotaPanel.show(this,this::render);return;}
                ThemeStore.dialog(this).setTitle(T.s("把这一格设成跨设备共享？"))
                        .setMessage(T.s("这个格子的额度和电脑合成一个池子：电脑那边也要有一条格子标成跨设备，两边合计到上限就同时停。池子的分钟数以电脑那条为准。"))
                        .setNegativeButton(T.s("取消"),null)
                        .setPositiveButton(T.s("设为共享"),(a,b)->{LanQuota.setGroup(this,g.optString("id"),g.optString("name"));LanQuota.setEnabled(this,true);LanQuota.clearCache(this);render();LanQuotaPanel.show(this,this::render);}).show();
            });
            action(box,T.s("移除格子"),WINDOW_ONLY,()->ThemeStore.dialog(this).setTitle(T.s("移除这个限额格子？")).setMessage(T.s("当天已用时间会保留。重新添加相同应用会继续累计；原有冻结规则仍然生效。")).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("移除"),(a,b)->remove(g.optString("id"))).show());
        }
        if(d.groups().length()==0)hint(body,T.s("尚未设置每日限额。一个格子可以只放一个应用，也可以放多个应用共用一份。"));
        action(body,T.s("＋ 添加限额格子"),ALWAYS,()->edit(null,new LinkedHashSet<>(),null));hint(body,T.s("守护运行时约每5秒检查额度。默认只计前台，锁屏、灭屏不计时；勾了「挂后台计时」的应用，切到后台只要还在跑（前台服务在运行）就照计，关掉就停。额度耗尽会立刻冻结对应格子的应用，热爱奖励不抵扣。未授予使用情况访问权限时额度照常保存，但暂不计时、不冻结。"));
        setContentView(FloatingNav.wrap(this,scroll,2));FloatingNav.fullscreen(this);PageScrollState.restore(this,position);refreshLabels();
    }
    private void refreshLabels(){if(window==null)return;Calendar now=GuardClock.now(this);boolean allowed=DailyQuotaData.editable(now,TimeWindow.start(this),TimeWindow.end(this));window.setText(allowed?T.s("随时可修改 · 当天已用时间保留"):T.s("现在只能加 · 移除或减少要等 ")+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(DailyQuotaData.nextEdit(now,TimeWindow.start(this),TimeWindow.end(this)))));window.setTextColor(allowed?ui.accent():ui.red());for(View control:controls){control.setEnabled(allowed);control.setAlpha(allowed?1f:.45f);}
        DailyQuotaData data=DailyQuotaStore.snapshot(this);for(Map.Entry<TextView,String> row:counters.entrySet())for(int i=0;i<data.groups().length();i++){JSONObject group=data.groups().optJSONObject(i);if(row.getValue().equals(group.optString("id"))){row.getKey().setText(DailyQuotaStore.description(this,data,group));bars.get(row.getKey()).bind(data.used(group),group.optInt("minutes")*60000L,DailyQuotaStore.readable(this,data),data.enabled());}}
        // 跨设备格子每轮跟着刷新：两端明细是从轮询缓存读的，慢一秒就不同步
        for(Map.Entry<TextView,String> row:lanDetails.entrySet())row.getKey().setText(lanDetail(row.getValue()));}
    /** 跨设备格子下面那三行：池子余量、手机这一份、电脑这一份。 */
    private String lanDetail(String groupId){
        DailyQuotaData data=DailyQuotaStore.snapshot(this);
        JSONObject group=data.groupById(groupId);
        StringBuilder out=new StringBuilder(LanQuota.summary(this));
        if(!LanQuota.fresh(this))return out.toString();
        out.append('\n').append(T.s("手机这一份：")).append(LanQuotaPanel.appsText(LanQuota.appDetail(this,data,group)));
        out.append('\n').append(T.s("电脑这一份：")).append(LanQuotaPanel.appsText(LanQuota.pcApps(this)));
        return out.toString();
    }
    private void save(JSONObject config){try{DailyQuotaStore.save(this,config);toast(T.s("每日限额已保存"));render();worker.execute(()->{try{Guard.apply(this);}catch(RuntimeException ignored){}});}catch(RuntimeException error){toast(error.getMessage());render();}}
    private JSONArray without(Set<String> ids){JSONArray out=new JSONArray();JSONArray groups=DailyQuotaStore.snapshot(this).groups();for(int i=0;i<groups.length();i++)if(!ids.contains(groups.optJSONObject(i).optString("id")))out.put(groups.optJSONObject(i));return out;}
    private void remove(String id){JSONObject next=MemoryData.copy(DailyQuotaStore.snapshot(this).config);JSONArray groups=without(Collections.singleton(id));DailyQuotaData.put(next,"groups",groups);if(groups.length()==0)DailyQuotaData.put(next,"enabled",false);save(next);}
    private static String shortName(String value){return value.substring(0,Math.min(80,value.length()));}
    /** 额度卡上那一行按钮的文案：直接告诉用户有几只在挂后台计时。 */
    private String backgroundLabel(Set<String> burning){
        if(burning.isEmpty())return T.s("挂后台也计时：未开启（点这里选择应用）");
        StringBuilder names=new StringBuilder();for(String p:burning){if(names.length()>0)names.append(T.s("、"));names.append(name(p));}
        return T.f("挂后台也计时：%d 个应用 · %s",burning.size(),names.toString());
    }
    /**
     * 挂后台也计时选择器（额度卡按钮与编辑对话框共用）。
     * 只能在本格子的成员里选。persist=true 表示在额度卡上直接改，立即保存并刷新；
     * persist=false 用于编辑格子对话框，只改内存里的名单，等「保存」时随格子一起落盘。
     */
    private void pickBackground(JSONObject group,Set<String> burning,Runnable onChange,boolean persist){
        JSONArray a=group.optJSONArray("apps");if(a==null||a.length()==0){toast(T.s("先给这个格子选好应用，再挑谁挂后台也计时"));return;}
        final boolean growOnly=!canReduce();
        final List<String> apps=new ArrayList<>();for(int i=0;i<a.length();i++)apps.add(a.optString(i));
        boolean[] on=new boolean[apps.size()];for(int i=0;i<apps.size();i++)on[i]=burning.contains(apps.get(i));
        String[] labels=new String[apps.size()];for(int i=0;i<apps.size();i++)labels[i]=name(apps.get(i));
        ThemeStore.dialog(this).setTitle(T.s("挂后台也计时（切到后台还在跑就照计）"))
            .setMultiChoiceItems(labels,on,(d,b,checked)->on[b]=checked)
            .setNegativeButton(T.s("取消"),null)
            .setPositiveButton(T.s("确定"),(d,b)->{
                Set<String> next=new LinkedHashSet<>();for(int i=0;i<apps.size();i++)if(on[i])next.add(apps.get(i));
                if(growOnly&&!next.containsAll(burning)){toast(T.f("取消挂后台计时要等调整时段 %s",TimeWindow.label(this)));return;}
                if(persist){Set<String> keep=saveBackground(group,next);burning.clear();burning.addAll(keep);onChange.run();}
                else{burning.clear();burning.addAll(next);onChange.run();}
            }).show();
    }
    /** 把某一格的「挂后台也计时」名单写回配置；名单先与成员取交集，空集则删掉这个字段。返回真正落盘的名单。 */
    private Set<String> saveBackground(JSONObject group,Set<String> burning){
        Set<String> keep=new LinkedHashSet<>(burning);
        try{
            Set<String> members=new LinkedHashSet<>();JSONArray a=group.optJSONArray("apps");if(a!=null)for(int i=0;i<a.length();i++)members.add(a.optString(i));
            keep.retainAll(members);
            JSONObject updated=new JSONObject(group.toString());if(keep.isEmpty())updated.remove("background");else DailyQuotaData.put(updated,"background",new JSONArray(keep));
            JSONObject next=MemoryData.copy(DailyQuotaStore.snapshot(this).config);JSONArray groups=new JSONArray();JSONArray all=next.optJSONArray("groups");
            for(int i=0;i<all.length();i++){JSONObject g=all.optJSONObject(i);groups.put(g!=null&&group.optString("id").equals(g.optString("id"))?updated:g);}
            DailyQuotaData.put(next,"groups",groups);DailyQuotaData.validate(next);
            DailyQuotaStore.save(this,next);toast(T.s("已保存挂后台计时设置"));
            worker.execute(()->{try{Guard.apply(this);}catch(RuntimeException ignored){}});
        }catch(RuntimeException|JSONException error){toast(error.getMessage());}
        return keep;
    }
    private void edit(JSONObject original,Set<String> replace,String pkg){
        final boolean growOnly=!canReduce();Set<String> chosen=new LinkedHashSet<>();if(original!=null){JSONArray a=original.optJSONArray("apps");for(int i=0;i<a.length();i++)chosen.add(a.optString(i));}if(pkg!=null)chosen.add(pkg);
        // 挂后台也计时的应用：只能是这个格子里的成员，随应用选择联动裁剪。
        final Set<String> burning=new LinkedHashSet<>();if(original!=null)for(String p:DailyQuotaData.backgroundApps(original))if(chosen.contains(p))burning.add(p);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(ui.dp(24),ui.dp(8),ui.dp(24),0);EditText title=new EditText(this);title.setSingleLine(true);title.setHint(T.s("格子名称（可选）"));title.setText(original==null?"":original.optString("name"));box.addView(title);EditText minutes=new EditText(this);minutes.setInputType(2);minutes.setHint(T.s("每天1–1440分钟"));minutes.setText(""+(original==null?120:original.optInt("minutes")));box.addView(minutes);
        TextView picked=text("",14,ui.text2());box.addView(picked);Runnable describe=()->{StringBuilder s=new StringBuilder();for(String p:chosen){if(s.length()>0)s.append(T.s("、"));s.append(name(p));}picked.setText(s.length()==0?T.s("尚未选择应用"):s.toString());};        TextView burnButton=action(box,"",ALWAYS,null);
        Runnable burnLabel=()->burnButton.setText(backgroundLabel(burning));burnLabel.run();
        burnButton.setOnClickListener(v->{
            if(chosen.isEmpty()){toast(T.s("先选择应用，再挑谁挂后台也计时"));return;}
            try{JSONObject synthetic=new JSONObject().put("id",original==null?"":original.optString("id")).put("apps",new JSONArray(new ArrayList<>(chosen)));
                pickBackground(synthetic,burning,burnLabel,false);}catch(JSONException error){toast(T.s("操作未完成"));}
        });
describe.run();action(box,T.s("选择应用"),ALWAYS,()->{List<String> apps=new ArrayList<>(catalog);for(String p:chosen)if(!apps.contains(p))apps.add(p);if(growOnly)for(String p:chosen)apps.remove(p);if(apps.isEmpty()){toast(growOnly?T.s("所有应用都已经在这个格子里了"):T.s("应用列表正在加载，请稍后重试"));return;}boolean[] on=new boolean[apps.size()];String[] labels=new String[apps.size()];for(int i=0;i<apps.size();i++){on[i]=!growOnly&&chosen.contains(apps.get(i));labels[i]=name(apps.get(i));}ThemeStore.dialog(this).setTitle(T.s("选择限额应用")).setMultiChoiceItems(labels,on,(a,b,checked)->on[b]=checked).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("确定"),(a,b)->{if(!growOnly)chosen.clear();for(int i=0;i<on.length;i++)if(on[i])chosen.add(apps.get(i));burning.retainAll(chosen);describe.run();burnLabel.run();}).show();});        hint(box,growOnly?T.f("现在只能加：勾上就进这个格子。想把应用拿出去，等调整时段 %s。",TimeWindow.label(this)):T.s("勾选多个应用，它们就共用这一份额度，不必再合并；只勾一个就是它单独的额度。新增默认120分钟；每个应用只在自身绑定的时段内计时，未绑定则全天计时。"));
        hint(box,T.s("「挂后台也计时」：勾中的应用切到后台后，只要还在跑（音乐播放、画中画这类前台服务在运行）就继续计时，关掉就停；锁屏、灭屏也不打断。切走闲置、应用已关闭都不计。不勾的应用维持只计前台。"));
        ScrollView content=new ScrollView(this);content.addView(box);AlertDialog dialog=ThemeStore.dialog(this).setTitle(original==null?T.s("添加限额格子"):T.s("修改限额格子")).setView(content).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("保存"),null).create();
        dialog.setOnShowListener(a->dialog.getButton(-1).setOnClickListener(v->{try{int limit=Integer.parseInt(minutes.getText().toString().trim());if(growOnly&&original!=null&&limit<original.optInt("minutes"))throw new IllegalArgumentException(T.f("现在只能加分钟数，调小要等调整时段 %s",TimeWindow.label(this)));if(chosen.contains(getPackageName()))throw new IllegalArgumentException(T.s("不能限制守卫自身"));String id=original==null?UUID.randomUUID().toString():original.optString("id"),label=title.getText().toString().trim();if(label.isEmpty())label=chosen.size()==1?shortName(name(chosen.iterator().next())):T.s("共用额度");JSONObject group=new JSONObject().put("id",id).put("name",label).put("minutes",limit).put("apps",new JSONArray(chosen));
            Set<String> bgSave=new LinkedHashSet<>(burning);bgSave.retainAll(chosen);if(!bgSave.isEmpty())group.put("background",new JSONArray(bgSave));Set<String> ids=new HashSet<>(replace);ids.add(id);JSONArray groups=without(ids);groups.put(group);JSONObject next=MemoryData.copy(DailyQuotaStore.snapshot(this).config);DailyQuotaData.put(next,"groups",groups);DailyQuotaData.validate(next);DailyQuotaStore.save(this,next);dialog.dismiss();render();worker.execute(()->{try{Guard.apply(this);}catch(RuntimeException ignored){}});}catch(RuntimeException|JSONException error){toast(error.getMessage());}}));dialog.show();
    }
}
