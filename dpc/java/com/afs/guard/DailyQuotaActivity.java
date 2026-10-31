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
    private TextView action(LinearLayout box,String label,int mode,Runnable run){TextView t=text(label,15,ui.accent());t.setPadding(ui.dp(12),ui.dp(13),ui.dp(12),ui.dp(13));t.setBackground(ui.round(ui.surface2(),14));box.addView(t,ui.gap(8));if(mode==WINDOW_ONLY)controls.add(t);t.setOnClickListener(v->{if(mode==WINDOW_ONLY&&!canReduce()){toast(T.s("移除或减少要等每天 22:00–23:00"));return;}try{run.run();}catch(RuntimeException error){toast(error.getMessage());}});return t;}
    private void toast(String value){Toast.makeText(this,value==null?T.s("操作未完成"):value,Toast.LENGTH_SHORT).show();}
    /** 能不能「减」：移出应用、调小分钟数、删格子、关总开关。只在编辑窗口里为真。 */
    private boolean canReduce(){return DailyQuotaStore.editable(this);}
    private void render(){Bundle position=PageScrollState.capture(this);counters.clear();controls.clear();bars.clear();ScrollView scroll=new ScrollView(this);ui.page(scroll);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(36));scroll.addView(body);
        action(body,T.s("‹  冻结应用"),ALWAYS,this::finish);body.addView(ui.title(T.s("使用限额")));hint(body,T.s("一个格子放一个应用，就是它单独的一份额度；放多个应用，就是它们共用同一份额度。应用放进冻结时段后，只累计所绑时段内的用量，额度用完才冻结；未绑定时段则全天累计。")+DayBoundary.label(this)+T.s("重置，改格子不清空已用时间。加到格子里的操作随时都能做（加应用、加分钟数）；移出应用、调小分钟数、删格子只在每天 22:00–23:00 开放。学习联动与禁忌惩罚继续生效。"));
        window=text("",13,ui.accent());body.addView(window);
        DailyQuotaData d=DailyQuotaStore.snapshot(this);CheckBox enabled=new CheckBox(this);enabled.setText(T.s("启用每日限额"));enabled.setTextColor(ui.text());enabled.setChecked(d.enabled());body.addView(enabled);enabled.setOnClickListener(v->{if(!canReduce()&&!enabled.isChecked()){enabled.setChecked(true);toast(T.s("关闭限额要等每天 22:00–23:00"));return;}JSONObject next=MemoryData.copy(d.config);DailyQuotaData.put(next,"enabled",enabled.isChecked());save(next);});
        action(body,T.s("使用情况访问权限"),ALWAYS,()->startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        for(int i=0;i<d.groups().length();i++){JSONObject g=d.groups().optJSONObject(i);LinearLayout box=ui.card();body.addView(box,ui.gap(12));box.addView(ui.title(g.optString("name")));JSONArray apps=g.optJSONArray("apps");StringBuilder labels=new StringBuilder();for(int j=0;j<apps.length();j++){if(j>0)labels.append(T.s("、"));String pkg=apps.optString(j);Rule rule=RuleStore.all(this).get(pkg);labels.append(name(pkg)).append(rule==null||FreezeSlotStore.unassigned(rule)?T.s("（全天）"):T.s("（所绑时段）"));}hint(box,(apps.length()>1?T.s("共用 · "):"")+labels);
            TextView counter=text("",16,ui.text());box.addView(counter);counters.put(counter,g.optString("id"));UsageQuotaProgress bar=new UsageQuotaProgress(this);LinearLayout.LayoutParams barLp=new LinearLayout.LayoutParams(-1,ui.dp(8));barLp.topMargin=ui.dp(8);barLp.bottomMargin=ui.dp(14);box.addView(bar,barLp);bars.put(counter,bar);action(box,T.s("修改应用和时长"),ALWAYS,()->edit(g,new LinkedHashSet<>(),null));
            action(box,T.s("移除格子"),WINDOW_ONLY,()->ThemeStore.dialog(this).setTitle(T.s("移除这个限额格子？")).setMessage(T.s("当天已用时间会保留。重新添加相同应用会继续累计；原有冻结规则仍然生效。")).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("移除"),(a,b)->remove(g.optString("id"))).show());
        }
        if(d.groups().length()==0)hint(body,T.s("尚未设置每日限额。一个格子可以只放一个应用，也可以放多个应用共用一份。"));
        action(body,T.s("＋ 添加限额格子"),ALWAYS,()->edit(null,new LinkedHashSet<>(),null));hint(body,T.s("守护运行时约每5秒检查额度。后台、锁屏、灭屏不计时；额度耗尽会立刻冻结对应格子的应用，热爱奖励不抵扣。未授予使用情况访问权限时额度照常保存，但暂不计时、不冻结。"));
        setContentView(FloatingNav.wrap(this,scroll,2));FloatingNav.fullscreen(this);PageScrollState.restore(this,position);refreshLabels();
    }
    private void refreshLabels(){if(window==null)return;Calendar now=GuardClock.now(this);boolean allowed=DailyQuotaData.editable(now);window.setText(allowed?T.s("随时可修改 · 当天已用时间保留"):T.s("现在只能加 · 移除或减少要等 ")+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(DailyQuotaData.nextEdit(now))));window.setTextColor(allowed?ui.accent():ui.red());for(View control:controls){control.setEnabled(allowed);control.setAlpha(allowed?1f:.45f);}
        DailyQuotaData data=DailyQuotaStore.snapshot(this);for(Map.Entry<TextView,String> row:counters.entrySet())for(int i=0;i<data.groups().length();i++){JSONObject group=data.groups().optJSONObject(i);if(row.getValue().equals(group.optString("id"))){row.getKey().setText(DailyQuotaStore.description(this,data,group));bars.get(row.getKey()).bind(data.used(group),group.optInt("minutes")*60000L,DailyQuotaStore.readable(this,data),data.enabled());}}}
    private void save(JSONObject config){try{DailyQuotaStore.save(this,config);toast(T.s("每日限额已保存"));render();worker.execute(()->{try{Guard.apply(this);}catch(RuntimeException ignored){}});}catch(RuntimeException error){toast(error.getMessage());render();}}
    private JSONArray without(Set<String> ids){JSONArray out=new JSONArray();JSONArray groups=DailyQuotaStore.snapshot(this).groups();for(int i=0;i<groups.length();i++)if(!ids.contains(groups.optJSONObject(i).optString("id")))out.put(groups.optJSONObject(i));return out;}
    private void remove(String id){JSONObject next=MemoryData.copy(DailyQuotaStore.snapshot(this).config);JSONArray groups=without(Collections.singleton(id));DailyQuotaData.put(next,"groups",groups);if(groups.length()==0)DailyQuotaData.put(next,"enabled",false);save(next);}
    private static String shortName(String value){return value.substring(0,Math.min(80,value.length()));}
    private void edit(JSONObject original,Set<String> replace,String pkg){
        final boolean growOnly=!canReduce();Set<String> chosen=new LinkedHashSet<>();if(original!=null){JSONArray a=original.optJSONArray("apps");for(int i=0;i<a.length();i++)chosen.add(a.optString(i));}if(pkg!=null)chosen.add(pkg);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(ui.dp(24),ui.dp(8),ui.dp(24),0);EditText title=new EditText(this);title.setSingleLine(true);title.setHint(T.s("格子名称（可选）"));title.setText(original==null?"":original.optString("name"));box.addView(title);EditText minutes=new EditText(this);minutes.setInputType(2);minutes.setHint(T.s("每天1–1440分钟"));minutes.setText(""+(original==null?120:original.optInt("minutes")));box.addView(minutes);
        TextView picked=text("",14,ui.text2());box.addView(picked);Runnable describe=()->{StringBuilder s=new StringBuilder();for(String p:chosen){if(s.length()>0)s.append(T.s("、"));s.append(name(p));}picked.setText(s.length()==0?T.s("尚未选择应用"):s.toString());};describe.run();action(box,T.s("选择应用"),ALWAYS,()->{List<String> apps=new ArrayList<>(catalog);for(String p:chosen)if(!apps.contains(p))apps.add(p);if(growOnly)for(String p:chosen)apps.remove(p);if(apps.isEmpty()){toast(growOnly?T.s("所有应用都已经在这个格子里了"):T.s("应用列表正在加载，请稍后重试"));return;}boolean[] on=new boolean[apps.size()];String[] labels=new String[apps.size()];for(int i=0;i<apps.size();i++){on[i]=!growOnly&&chosen.contains(apps.get(i));labels[i]=name(apps.get(i));}ThemeStore.dialog(this).setTitle(T.s("选择限额应用")).setMultiChoiceItems(labels,on,(a,b,checked)->on[b]=checked).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("确定"),(a,b)->{if(!growOnly)chosen.clear();for(int i=0;i<on.length;i++)if(on[i])chosen.add(apps.get(i));describe.run();}).show();});hint(box,growOnly?T.s("现在只能加：勾上就进这个格子。想把应用拿出去，等每天 22:00–23:00。"):T.s("勾选多个应用，它们就共用这一份额度，不必再合并；只勾一个就是它单独的额度。新增默认120分钟；每个应用只在自身绑定的时段内计时，未绑定则全天计时。"));
        ScrollView content=new ScrollView(this);content.addView(box);AlertDialog dialog=ThemeStore.dialog(this).setTitle(original==null?T.s("添加限额格子"):T.s("修改限额格子")).setView(content).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("保存"),null).create();
        dialog.setOnShowListener(a->dialog.getButton(-1).setOnClickListener(v->{try{int limit=Integer.parseInt(minutes.getText().toString().trim());if(growOnly&&original!=null&&limit<original.optInt("minutes"))throw new IllegalArgumentException(T.s("现在只能加分钟数，调小要等每天 22:00–23:00"));if(chosen.contains(getPackageName()))throw new IllegalArgumentException(T.s("不能限制守卫自身"));String id=original==null?UUID.randomUUID().toString():original.optString("id"),label=title.getText().toString().trim();if(label.isEmpty())label=chosen.size()==1?shortName(name(chosen.iterator().next())):T.s("共用额度");JSONObject group=new JSONObject().put("id",id).put("name",label).put("minutes",limit).put("apps",new JSONArray(chosen));Set<String> ids=new HashSet<>(replace);ids.add(id);JSONArray groups=without(ids);groups.put(group);JSONObject next=MemoryData.copy(DailyQuotaStore.snapshot(this).config);DailyQuotaData.put(next,"groups",groups);DailyQuotaData.validate(next);DailyQuotaStore.save(this,next);dialog.dismiss();render();worker.execute(()->{try{Guard.apply(this);}catch(RuntimeException ignored){}});}catch(RuntimeException|JSONException error){toast(error.getMessage());}}));dialog.show();
    }
}
