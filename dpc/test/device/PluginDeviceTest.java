package com.afs.host.plugins;
import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import com.afs.pluginapi.*;
import com.afs.guard.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

/** Runs in host via signed test instrumentation; ProbeService calls come from a DIFFERENT UID. */
public final class PluginDeviceTest extends Instrumentation {
    private Bundle args; private int checks; private Context c; private PluginRegistry registry; private HostSessions broker;
    private static final String A="com.afs.plugin.fixture", B="com.afs.plugin.fixturetwo", REAL="com.afs.plugin.usagestats";
    private final ArrayList<ServiceConnection> bindings=new ArrayList<>();
    private void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);android.util.Log.i("AFSPluginQA","PASS "+label);}
    public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
    private Object field(Object obj,String name)throws Exception{Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
    private PluginEntry refresh(String pkg){registry.refreshNow();return registry.findByPackage(pkg);}
    private void enable(String pkg,boolean read,boolean request){
        PluginEntry e=refresh(pkg);check(e!=null&&!e.hasError(),"compatible "+pkg);
        check(registry.confirm(e),"explicit install-bound atomic consent "+pkg);e=refresh(pkg);
        if(!read)check(registry.stateStore().setGrant(e,true,false),"read grant independently removed");
        e=refresh(pkg);if(!request&&e.manifest.capabilities.contains(PluginProtocol.CAP_REQUEST))check(registry.stateStore().setGrant(e,false,false),"request grant independently removed");
        registry.refreshNow();
    }
    private IBinder bind(String pkg)throws Exception{
        CountDownLatch ready=new CountDownLatch(1);IBinder[] out=new IBinder[1];
        ServiceConnection sc=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){out[0]=b;ready.countDown();}public void onServiceDisconnected(ComponentName n){}};
        boolean bound=c.bindService(new Intent().setComponent(new ComponentName(pkg,pkg+".ProbeService")),sc,Context.BIND_AUTO_CREATE);
        check(bound&&ready.await(6,TimeUnit.SECONDS)&&out[0]!=null,"cross UID probe bound "+pkg);bindings.add(sc);return out[0];
    }
    private OpenResult open(IBinder probe,LaunchTicket ticket)throws Exception{Parcel p=Parcel.obtain(),r=Parcel.obtain();try{ticket.writeToParcel(p,0);check(probe.transact(1,p,r,0),"probe open transaction");r.readException();return OpenResult.CREATOR.createFromParcel(r);}finally{p.recycle();r.recycle();}}
    private UsageResult load(IBinder probe,PageLease lease,long id)throws Exception{Parcel p=Parcel.obtain(),r=Parcel.obtain();try{lease.writeToParcel(p,0);p.writeLong(id);check(probe.transact(2,p,r,0),"probe load transaction");r.readException();return UsageResult.CREATOR.createFromParcel(r);}finally{p.recycle();r.recycle();}}
    private void command(IBinder probe,int cmd,PageLease lease)throws Exception{Parcel p=Parcel.obtain(),r=Parcel.obtain();try{lease.writeToParcel(p,0);p.writeLong(99);check(probe.transact(cmd,p,r,0),"probe command "+cmd);}finally{p.recycle();r.recycle();}}
    private LaunchTicket ticket(String pkg){PluginEntry e=refresh(pkg);return broker.issueTicket(e,e.manifest.pages.get(0));}
    private PageLease lease(IBinder probe,String pkg)throws Exception{OpenResult r=open(probe,ticket(pkg));check(r.status==PluginStatus.OK&&r.lease!=null,"session granted to plugin UID");return r.lease;}
    private void close(String pkg)throws Exception{broker.invalidatePackage(pkg,PluginStatus.REVOKED).get(8,TimeUnit.SECONDS);}
    private void guardState(String label)throws Exception{
        android.content.SharedPreferences prefs=c.getSharedPreferences("afs_guard",0);String before=prefs.getString("rules",null);
        DevicePolicyManager d=(DevicePolicyManager)c.getSystemService(Context.DEVICE_POLICY_SERVICE);ComponentName admin=new ComponentName(c,AfsAdmin.class);
        String victim=getContext().getPackageName();
        try{
            check(d.isDeviceOwnerApp(c.getPackageName()),label+" owner retained");
            org.json.JSONObject current=new org.json.JSONObject(before==null?"{}":before);
            current.put(victim,new org.json.JSONObject("{\"d\":127,\"w\":[],\"g\":false}"));prefs.edit().putString("rules",current.toString()).commit();
            Guard.apply(c);check(d.isPackageSuspended(admin,victim),label+" real DPM suspended");
            check(d.isPackageSuspended(admin,PluginBaselineDataTest.VICTIM),label+" pre-existing rule remains frozen");
            current.put(victim,new org.json.JSONObject("{\"d\":127,\"w\":[[0,0]],\"g\":false}"));prefs.edit().putString("rules",current.toString()).commit();
            Guard.apply(c);check(!d.isPackageSuspended(admin,victim),label+" allowed window released");
            check(d.isPackageSuspended(admin,PluginBaselineDataTest.VICTIM),label+" unrelated release does not release existing rule");
        }finally{if(before==null)prefs.edit().remove("rules").commit();else prefs.edit().putString("rules",before).commit();d.setPackagesSuspended(admin,new String[]{victim},false);d.setUninstallBlocked(admin,victim,false);Guard.apply(c);}
    }
    public void onStart(){Bundle result=new Bundle();int code=-1;c=getTargetContext();
        String stage=args.getString("stage","normal");
        try{
            PluginBaselineDataTest.requireEmulator();registry=HostRuntime.get(c).registry();broker=HostRuntime.get(c).sessions();
            checks+=PluginBaselineDataTest.verify(c);
            registry.refreshNow();
            check(c.getApplicationInfo().uid!=c.getPackageManager().getApplicationInfo(A,0).uid,"different OS UIDs");
            PluginEntry real=registry.findByPackage(REAL);check(real!=null,"real external plugin discovered");
            if(stage.equals("aggregation")){
                pcAggregation();guardState(stage);
            }else if(stage.equals("settings-summary")){
                settingsSummaryRefresh();guardState(stage);
            }else if(stage.equals("client-death")){
                pluginDeath();guardState(stage);
            }else if(stage.equals("storage-retry")){
                storageFailure(bind(A),bind(B));guardState(stage);
            }else if(stage.equals("storage-retry-restart")){
                int oldPid=Integer.parseInt(args.getString("old_pid","-1"));check(oldPid>0&&android.os.Process.myPid()!=oldPid,"host process actually restarted after successful persistence retry");
                PluginEntry e=refresh(A);check(!e.state.enabled&&!e.state.readGranted&&!e.state.requestGranted,"new process reads persisted disabled state and both revoked grants");
                check(broker.issueTicket(e,e.manifest.pages.get(0))==null,"disabled plugin receives no new page ticket after restart");
                IBinder probe=bind(A);PageLease fake=new PageLease("old-session",e.pluginId(),A,"fixture",1,e.fingerprint.uid,1,0,Long.MAX_VALUE,1,1,1,1,e.fingerprint.value());
                check(load(probe,fake,501).status!=PluginStatus.OK,"independent plugin cannot recover data access after persistence retry and restart");guardState(stage);
            }else if(stage.equals("disable")){
                for(String pkg:new String[]{A,B,REAL}){registry.stateStore().disable(pkg);close(pkg);}guardState(stage);
            }else if(stage.equals("disabled")){
                check(!real.state.enabled&&registry.enabledPages(PluginProtocol.SLOT_HISTORY_MORE).isEmpty(),"disabled persists after host restart with no entries");verifyMissingPages();guardState(stage);
            }else if(stage.equals("reinstall")){
                PluginEntry e=refresh(A);check(e!=null&&!e.state.enabled&&!e.state.readGranted&&!e.state.requestGranted,"genuine reinstall resets enabled and grants");guardState(stage);
            }else if(stage.equals("upgrade")){
                PluginEntry e=refresh(A);check(e!=null&&e.state.enabled&&e.state.readGranted,"compatible independent upgrade retains explicit grants");
                check(e.fingerprint.versionCode==2,"plugin version2 live");IBinder probe=bind(A);check(load(probe,lease(probe,A),51).status==PluginStatus.OK,"updated plugin IPC works");close(A);guardState(stage);
            }else if(stage.equals("real-upgrade")){
                PluginEntry e=refresh(REAL);check(e.state.enabled&&e.state.readGranted&&e.fingerprint.versionCode==2,"real statistics plugin independently upgraded with grants");
                verifyRealPage();guardState(stage);
            }else if(stage.equals("stale-dialog")){
                PluginEntry displayed=refresh(A);check(!displayed.state.enabled,"old consent snapshot initially disabled");
                installFixture("/data/local/tmp/afs-plugin-v2.apk");refresh(A);
                check(!registry.confirm(displayed),"old confirmation cannot authorize updated installation");
                check(!refresh(A).state.enabled&&!refresh(A).state.readGranted,"stale confirmation leaves new installation disabled");guardState(stage);
            }else if(stage.equals("collision")){
                enable(A,true,false);enable(B,true,false);IBinder probe=bind(A);PageLease l=lease(probe,A);
                installFixture("/data/local/tmp/afs-plugin-collision.apk");registry.refreshNow();
                PluginEntry a=registry.findByPackage(A),b=registry.findByPackage(B);
                check(a.hasError()&&b.hasError()&&!a.state.enabled&&!b.state.enabled,"duplicate IDs disable every conflicting plugin deterministically");
                check(registry.verify(A,a.pluginId(),a.fingerprint.value())==null&&registry.verify(B,b.pluginId(),b.fingerprint.value())==null,"directory errors cannot pass live verification");
                check(load(probe,l,71).status!=PluginStatus.OK,"existing lease loses data access after ID collision");close(A);guardState(stage);
            }else if(stage.equals("untrusted")){
                String pkg="com.afs.plugin.untrusted";PluginEntry e=refresh(pkg);
                check(e!=null&&e.hasError()&&!e.state.enabled,"untrusted signer is diagnosed and cannot enable");
                check(!registry.confirm(e),"untrusted package cannot obtain consent grant");
                IBinder probe=bind(pkg);check(probeNumber(probe,9)==1,"Android signature permission prevents untrusted UID binding to host bridge");guardState(stage);
            }else if(stage.equals("incompatible")){
                PluginEntry e=refresh(A);check(e!=null&&e.hasError()&&!e.state.enabled,"incompatible package diagnosed and disabled");
                check(registry.enabledPages(PluginProtocol.SLOT_HISTORY_MORE).isEmpty(),"incompatible has no live page entry");verifyMissingPages();guardState(stage);
            }else if(stage.equals("prepare-fail")||stage.equals("prepare-timeout")){
                enable(A,true,false);PluginEntry e=refresh(A);
                Activity manager=startActivitySync(new Intent(c,PluginManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));CountDownLatch done=new CountDownLatch(1);String[] message=new String[1];long start=SystemClock.elapsedRealtime();
                runOnMainSync(() -> HostPluginRouter.openPage(manager,e,"fixture",701,m->{message[0]=m;done.countDown();}));
                check(done.await(5,TimeUnit.SECONDS)&&message[0]!=null,"prepare failure returns to usable host");
                if(stage.equals("prepare-timeout"))check(SystemClock.elapsedRealtime()-start<4000,"prepare deadline bounded");
                runOnMainSync(manager::finish);verifyFailureEntry(stage.equals("prepare-timeout"));guardState(stage);
            }else normal();
            PluginBaselineDataTest.requireEmulator();registry=HostRuntime.get(c).registry();broker=HostRuntime.get(c).sessions();
            checks+=PluginBaselineDataTest.verify(c);
            result.putString("stream","Plugin device "+stage+": "+checks+" checks passed; hostPid="+android.os.Process.myPid()+"\n");
        }catch(Throwable e){code=1;result.putString("stream","FAILED "+android.util.Log.getStackTraceString(e));}
        finally{for(ServiceConnection sc:bindings)try{c.unbindService(sc);}catch(Exception ignored){}}
        finish(code,result);
    }
    private void normal()throws Exception{
        sdkBindingLifecycle();
        privacyAndFrames();
        settingsSummaryRefresh();
        check(!refresh(REAL).state.enabled,"real plugin default disabled");
        check(!refresh(A).state.enabled,"fixture initial disabled");
        IBinder a=bind(A),b=bind(B);
        java.io.File hostFile=new java.io.File(c.getApplicationInfo().dataDir,"shared_prefs/afs_plugins_v1.xml");
        check(hostFile.isFile(),"host file exists before cross-UID access test");
        Parcel p=Parcel.obtain(),r=Parcel.obtain();try{p.writeString(hostFile.getAbsolutePath());check(a.transact(5,p,r,0),"private-file probe transaction");r.readException();int denied=r.readInt();String detail=r.readString();check(r.readInt()==1,"external probe can read its own random file through the same Os.open API");check(hostFile.isFile(),"same host private file still exists after external open attempt");check(denied==1&&detail.endsWith("uid="+c.getPackageManager().getApplicationInfo(A,0).uid),"OS denies external private-file read through permissions or app-data mount isolation: "+detail);}finally{p.recycle();r.recycle();}
        readOnlyConsent();metadataCorruption();grantClickRace();malformedAccess(a);enable(A,true,false);enable(B,true,false);
        check(registry.enabledPages(PluginProtocol.SLOT_HISTORY_MORE).size()==2,"generic second-package entry discovered without host update");
        LaunchTicket once=ticket(A);OpenResult first=open(a,once),again=open(a,once);
        check(first.status==PluginStatus.OK&&again.status==PluginStatus.INVALID_TICKET,"ticket single-use across actual Binder");PageLease l=first.lease;
        check(open(a,ticket(A)).status==PluginStatus.BUSY,"one active session per plugin UID");
        check(broker.validateAccess(A,l.leaseId,l.uid).status==PluginStatus.NO_GRANT,"request grant independent from read grant");
        UsageResult summary=load(a,l,11);check(summary.status==PluginStatus.OK&&summary.summary!=null,"read-only grant returns bounded stats without request grant");
        check(summary.summary.phoneTop10.size()<=40&&summary.summary.trend7.size()==7,"summary bounds and full seven days");
        command(a,10,l);check(load(a,l,16).status==PluginStatus.OK,"unknown trailing field rejected before close can revoke a valid session");
        probeNumber(a,8);check(load(a,l,12).status==PluginStatus.OK,"oversized cross-UID parcel rejected before decoding and host remains usable");
        check(load(a,l,0).status==PluginStatus.FAILED,"zero request ID rejected");
        PageLease forged=new PageLease(l.leaseId,"compat-fixture-two",B,l.pageId,l.protocol,c.getPackageManager().getApplicationInfo(B,0).uid,l.hostGeneration,l.issuedElapsedMs,l.expiresElapsedMs,l.installGeneration,l.enabledGeneration,l.readGrantGeneration,l.requestGrantGeneration,l.installFingerprint);
        check(load(b,forged,22).status==PluginStatus.BAD_CALLER,"forged lease cannot read other UID");command(b,4,forged);
        check(load(a,l,23).status==PluginStatus.OK,"forged close cannot revoke victim lease");
        close(A);
        check(load(a,l,24).status!=PluginStatus.OK,"revocation barrier rejects old lease");
        enable(A,false,true);l=lease(a,A);
        check(load(a,l,25).status==PluginStatus.NO_GRANT,"request-only grant cannot read stats");
        check(broker.validateAccess(A,l.leaseId,l.uid).status==PluginStatus.OK,"request-only grant may open host authorization");close(A);
        requestClickRevalidation(a);
        enable(A,true,false);l=lease(a,A);
        livePermissionBoundary(a,l);saturation(a,l);close(A);
        pluginDeath();
        enable(REAL,true,false);PluginEntry e=refresh(REAL);
        verifyRealPage();guardState("enabled");
        for(String pkg:new String[]{A,B,REAL}){registry.stateStore().disable(pkg);close(pkg);}registry.refreshNow();
        check(registry.enabledPages(PluginProtocol.SLOT_HISTORY_MORE).isEmpty(),"disable removes all plugin entries");guardState("disabled before restart");
        // Prepare runner's independent update proof: only A enabled; others remain disabled.
        enable(A,true,false);enable(REAL,true,false);
    }
    private void privacyAndFrames()throws Exception{
        pcAggregation();
        for(int variant=0;variant<6;variant++){
            Parcel truncated=Parcel.obtain();String name=variant==3?"com.afs.pluginapi.IPluginEndpoint":variant==4?"com.afs.pluginapi.IOpenCallback":"com.afs.pluginapi.IHostStatsBridge";int method=variant==2||variant==3?1:0;
            try{truncated.writeInterfaceToken(name);if(variant==1||variant==2)truncated.writeInt(0);if(variant==3)truncated.writeString("lease");if(variant==4){truncated.writeInt(1);truncated.writeInt(PluginStatus.OK);}if(variant==5){truncated.writeInt(0);truncated.writeInt(0);}truncated.setDataPosition(0);check(!WireFrame.valid(name,IBinder.FIRST_CALL_TRANSACTION+method,truncated)&&truncated.dataPosition()==0,"truncated transaction refused before dispatch variant "+variant);}finally{truncated.recycle();}
        }
        Parcel nulls=Parcel.obtain();try{nulls.writeInterfaceToken("com.afs.pluginapi.IHostStatsBridge");nulls.writeInt(0);nulls.writeStrongBinder(null);nulls.writeStrongBinder(null);nulls.setDataPosition(0);check(WireFrame.valid("com.afs.pluginapi.IHostStatsBridge",IBinder.FIRST_CALL_TRANSACTION,nulls),"valid null Binder fields retain compatible framing");}finally{nulls.recycle();}
        String pkg="com.afs.unlabelled.fixture";
        android.content.pm.ApplicationInfo info=new android.content.pm.ApplicationInfo();info.packageName=pkg;
        CharSequence fallback=c.getPackageManager().getApplicationLabel(info);check(pkg.contentEquals(fallback),"actual PackageManager defaults an unlabelled app to its package identifier");
        Method label=com.afs.host.usage.HostUsageStats.class.getDeclaredMethod("displayLabel",CharSequence.class,String.class);label.setAccessible(true);
        List<DisplayUsage> rows=new ArrayList<>();
        for(CharSequence value:new CharSequence[]{null," ",fallback," "+pkg+" ","Friendly application"})rows.add(new DisplayUsage((String)label.invoke(null,value,pkg),1000));
        UsageSummary source=new UsageSummary(0,"UTC","2026-11",false,rows,0,0,Collections.emptyList(),null,null,null,"","",false);
        Parcel p=Parcel.obtain();try{source.writeToParcel(p,0);p.setDataPosition(0);UsageSummary result=UsageSummary.CREATOR.createFromParcel(p);for(int i=0;i<4;i++)check(!result.phoneTop10.get(i).displayName.contains(pkg),"actual outgoing DTO round-trip does not expose missing-label package variant "+i);check(result.phoneTop10.get(4).displayName.equals("Friendly application"),"actual outgoing DTO preserves a normal human label");}finally{p.recycle();}
        p=Parcel.obtain();try{p.writeInterfaceToken("com.afs.pluginapi.IUsageCallback");p.writeInt(1);new UsageResult(1,PluginStatus.OK,source,"").writeToParcel(p,0);p.setDataPosition(0);check(WireFrame.valid("com.afs.pluginapi.IUsageCallback",IBinder.FIRST_CALL_TRANSACTION,p)&&p.dataPosition()==0,"valid callback framing preserves cursor");p.setDataPosition(p.dataSize());p.writeInt(9);p.setDataPosition(0);check(!WireFrame.valid("com.afs.pluginapi.IUsageCallback",IBinder.FIRST_CALL_TRANSACTION,p),"unknown callback trailing field refused before dispatch");}finally{p.recycle();}
        p=Parcel.obtain();try{p.writeInt(PluginStatus.OK);p.writeInt(2);p.writeString("");p.setDataPosition(0);boolean rejected=false;try{OpenResult.CREATOR.createFromParcel(p);}catch(android.os.BadParcelableException expected){rejected=true;}check(rejected,"unknown nullable field marker refused");}finally{p.recycle();}
    }
    private void pcAggregation()throws Exception{
        Constructor<PcStats.Entry> constructor=PcStats.Entry.class.getDeclaredConstructor(String.class,int.class);constructor.setAccessible(true);
        PcPeriodSummary[] periods=new PcPeriodSummary[3];
        for(int period=1;period<=3;period++){List<PcStats.Entry> apps=new ArrayList<>();for(int i=13;i>=1;i--)apps.add(constructor.newInstance("QA tool "+i,i*period));periods[period-1]=com.afs.host.usage.HostUsageStats.pcPeriod(apps);check(periods[period-1].top10.size()==10&&periods[period-1].totalMs==91L*period*60000,"host PC aggregator retains full total while trimming period "+period);}
        UsageSummary source=new UsageSummary(0,"UTC","2026-11",true,Collections.emptyList(),0,0,Collections.emptyList(),periods[0],periods[1],periods[2],"","",false);
        Parcel parcel=Parcel.obtain();try{source.writeToParcel(parcel,0);parcel.setDataPosition(0);UsageSummary decoded=UsageSummary.CREATOR.createFromParcel(parcel);check(decoded.pcDay.totalMs==91L*60000&&decoded.pcMonth.totalMs==182L*60000&&decoded.pcTotal.totalMs==273L*60000,"actual daily monthly and cumulative PC totals survive wire round-trip");}finally{parcel.recycle();}
        check(com.afs.host.usage.HostUsageStats.pcPeriod(null).empty,"PC missing-data empty state remains explicit");
    }
    private void pluginDeath()throws Exception{
        enable(A,true,false);IBinder original=bind(A);PageLease old=lease(original,A);
        check(load(original,old,901).status==PluginStatus.OK,"independent client reads before process death");
        shell("am force-stop "+A);long until=SystemClock.elapsedRealtime()+4000;
        while(original.pingBinder()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
        check(!original.pingBinder(),"independent plugin process actually died (active Binder ping)");
        IBinder replacement=bind(A);check(load(replacement,old,902).status!=PluginStatus.OK,"new client process cannot reuse dead client lease");
        PageLease next=lease(replacement,A);check(!next.leaseId.equals(old.leaseId)&&load(replacement,next,903).status==PluginStatus.OK,"Binder death reclaims old session and allows new client session");close(A);
    }
    private void verifyRealPage()throws Exception {
        PluginEntry e=refresh(REAL);
        Activity manager=startActivitySync(new Intent(c,PluginManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        check(manager.getClass().getName().equals("com.afs.host.plugins.PluginManagerActivity"),"packaged manager class opens");
        runOnMainSync(manager::finish);
        Activity history=startActivitySync(new Intent(c,com.afs.guard.HistoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();android.widget.ScrollView scroll=findScroll(history.getWindow().getDecorView());check(scroll!=null,"history scroll container exists");
        android.view.View[] entry={null};int[] savedY={0};
        runOnMainSync(()->{scroll.setSmoothScrollingEnabled(false);scroll.fullScroll(android.view.View.FOCUS_DOWN);entry[0]=findStatsEntry(history.getWindow().getDecorView());});waitForIdleSync();
        runOnMainSync(()->{savedY[0]=scroll.getScrollY();});check(entry[0]!=null,"registered statistics entry appears in actual history page");
        runOnMainSync(()->entry[0].performClick());
        boolean page=false;long until=SystemClock.elapsedRealtime()+8000;
        while(SystemClock.elapsedRealtime()<until&&!page){AccessibilityNodeInfo node=getUiAutomation().getRootInActiveWindow();page=contains(node,"本月应用排行")||contains(node,"Monthly app ranking")||contains(node,"Month ranking");if(node!=null)node.recycle();SystemClock.sleep(100);}
        check(page,"history user entry opens independent plugin UI with rendered summary");
        android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();check(shot!=null,"rendered independent page screenshot available");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(c.getExternalFilesDir(null),"plugin-r2-statistics.png"))){check(shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"rendered plugin screenshot saved");}finally{shot.recycle();}
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        waitForIdleSync();
        long backUntil=SystemClock.elapsedRealtime()+3000;while(!history.hasWindowFocus()&&SystemClock.elapsedRealtime()<backUntil)SystemClock.sleep(50);
        check(!history.isDestroyed()&&history.hasWindowFocus()&&scroll.getScrollY()==savedY[0],"back returns to same history instance and scroll position: destroyed="+history.isDestroyed()+" focused="+history.hasWindowFocus()+" savedY="+savedY[0]+" actualY="+scroll.getScrollY());
        close(REAL);runOnMainSync(history::finish);
    }
    private android.view.View findButton(android.view.View view,String text){if(view instanceof android.widget.Button&&((android.widget.Button)view).getText().toString().contains(text))return view;if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){android.view.View found=findButton(((android.view.ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    private void verifyMissingPages()throws Exception{
        Activity history=startActivitySync(new Intent(c,HistoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        check(findStatsEntry(history.getWindow().getDecorView())==null&&findButton(history.getWindow().getDecorView(),"compat-fixture")==null,"actual history has no disabled or incompatible plugin entry");runOnMainSync(history::finish);
        Activity route=startActivitySync(new Intent(c,StatsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));boolean missing=false;long until=SystemClock.elapsedRealtime()+5000;
        while(!missing&&SystemClock.elapsedRealtime()<until){AccessibilityNodeInfo node=getUiAutomation().getRootInActiveWindow();missing=contains(node,"未安装或未启用")||contains(node,"not installed");if(node!=null)node.recycle();if(!missing)SystemClock.sleep(50);}
        check(missing&&!route.isFinishing()&&route.hasWindowFocus(),"old statistics intent shows missing-plugin guidance without opening legacy renderer");runOnMainSync(route::finish);
    }
    private void settingsSummaryRefresh()throws Exception{
        for(String pkg:new String[]{A,B,REAL})registry.stateStore().disable(pkg);registry.refreshNow();
        PluginEntry displayed=registry.findByPackage(REAL);
        Activity settings=startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CountDownLatch scanned=new CountDownLatch(1);registry.refreshAsync(scanned::countDown);check(scanned.await(5,TimeUnit.SECONDS),"initial settings directory refresh completed");waitForIdleSync();
        check(((android.widget.TextView)field(settings,"pluginLine")).getText().toString().startsWith("0 / "),"settings initially shows disabled plugin count");
        check(getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME),"settings moved to background through actual Home action");
        long until=SystemClock.elapsedRealtime()+3000;while(settings.hasWindowFocus()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
        check(!settings.hasWindowFocus(),"actual Home action backgrounds settings before grant change");
        check(registry.confirm(displayed),"persist new enabled state while settings is paused");check(!registry.findByPackage(REAL).state.enabled,"directory cache is deliberately older than persisted state");
        runOnMainSync(()->c.startActivity(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        // Android 7 delays app switches briefly after Home; wait for the real resumed window.
        boolean updated=false;until=SystemClock.elapsedRealtime()+15000;
        while(!updated&&SystemClock.elapsedRealtime()<until){updated=settings.hasWindowFocus()&&((android.widget.TextView)field(settings,"pluginLine")).getText().toString().startsWith("1 / ");if(!updated)SystemClock.sleep(50);}
        check(updated&&!settings.isDestroyed(),"settings refresh updates only its plugin summary on actual resume");
        registry.stateStore().disable(REAL);registry.refreshNow();runOnMainSync(settings::finish);
    }
    private void verifyFailureEntry(boolean timeout)throws Exception{
        Activity history=startActivitySync(new Intent(c,HistoryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        android.view.View button=findButton(history.getWindow().getDecorView(),"compat-fixture");check(button!=null,"compatible failing plugin still has its actual registered history entry");
        android.widget.ScrollView scroll=findScroll(history.getWindow().getDecorView());runOnMainSync(()->{scroll.setSmoothScrollingEnabled(false);scroll.fullScroll(android.view.View.FOCUS_DOWN);});
        String expected=timeout?T.s("插件初始化超时"):"fixture";
        android.view.accessibility.AccessibilityEvent event=getUiAutomation().executeAndWaitForEvent(()->runOnMainSync(button::performClick),e->e.getEventType()==android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED&&e.getText().toString().contains(expected),5000);
        check(event!=null,"actual history entry shows initialization failure feedback");if(event!=null)event.recycle();
        check(!history.isDestroyed()&&history.hasWindowFocus(),"initialization failure leaves the original history page usable");runOnMainSync(history::finish);
    }
    private android.widget.ScrollView findScroll(android.view.View view){if(view instanceof android.widget.ScrollView)return (android.widget.ScrollView)view;if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){android.widget.ScrollView found=findScroll(((android.view.ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    private android.view.View findStatsEntry(android.view.View view){if(view instanceof android.widget.Button){String text=((android.widget.Button)view).getText().toString().toLowerCase(java.util.Locale.ROOT);if(text.contains("屏幕使用统计")||text.contains("screen usage")||text.contains("screen time stats"))return view;}if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){android.view.View found=findStatsEntry(((android.view.ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    private boolean contains(AccessibilityNodeInfo n,String text){if(n==null)return false;if(n.getText()!=null&&n.getText().toString().toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT)))return true;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);boolean found=contains(child,text);if(child!=null)child.recycle();if(found)return true;}return false;}
    private String shell(String command)throws Exception{
        ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);
        try(java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] bytes=new byte[1024];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);return out.toString("UTF-8");
        }
    }
    private void installFixture(String path)throws Exception{String out=shell("pm install -r "+path);check(out.contains("Success"),"independent installer completed "+out);}
    private int probeNumber(IBinder probe,int command)throws Exception{
        Parcel p=Parcel.obtain(),r=Parcel.obtain();try{if(!probe.transact(command,p,r,0))throw new AssertionError("probe result transaction");r.readException();return r.readInt();}finally{p.recycle();r.recycle();}
    }
    private void startAuth(int variant,String id){Intent i=new Intent().setComponent(new ComponentName(A,A+".AuthProbeActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);i.putExtra("variant",variant);i.putExtra("leaseId",id);runOnMainSync(()->c.startActivity(i));}
    private int awaitAuth(IBinder probe)throws Exception{long end=SystemClock.elapsedRealtime()+4000;int status=-100;while(SystemClock.elapsedRealtime()<end&&(status=probeNumber(probe,6))==-100)SystemClock.sleep(30);return status;}
    private void malformedAccess(IBinder probe)throws Exception{
        Activity manager=startActivitySync(new Intent(c,PluginManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for(int variant:new int[]{1,2}){probeNumber(probe,7);startAuth(variant,"");check(awaitAuth(probe)==PluginStatus.ACCESS_REVOKED,"malformed auth input rejected without killing host variant "+variant);}
        runOnMainSync(manager::finish);
    }
    private void readOnlyConsent()throws Exception{
        PluginEntry e=refresh(B);check(e.manifest.capabilities.size()==1&&e.manifest.capabilities.contains(PluginProtocol.CAP_READ),"read-only fixture declares no request capability");
        Activity manager=startActivitySync(new Intent(c,PluginManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Method confirm=PluginManagerActivity.class.getDeclaredMethod("confirmEnable",PluginEntry.class);confirm.setAccessible(true);
        runOnMainSync(()->{try{confirm.invoke(manager,e);}catch(Exception ex){throw new RuntimeException(ex);}});
        AccessibilityNodeInfo root=null;long dialogUntil=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<dialogUntil){root=getUiAutomation().getRootInActiveWindow();if(root!=null&&(contains(root,"启用并授权")||contains(root,"Enable and authorize")))break;if(root!=null){root.recycle();root=null;}SystemClock.sleep(100);}
        try{check(root!=null&&(contains(root,"启用并授权")||contains(root,"Enable and authorize"))&&!contains(root,"使用情况访问")&&!contains(root,"usage access")&&!contains(root,"授予两项"),"read-only consent dialog does not advertise undeclared request access; observed="+nodeText(root));}finally{if(root!=null)root.recycle();}
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);waitForIdleSync();runOnMainSync(manager::finish);
        check(registry.confirm(refresh(B)),"read-only declared capability consent succeeds");
        check(refresh(B).state.readGranted&&!refresh(B).state.requestGranted,"read-only consent grants only read");registry.stateStore().disable(B);close(B);refresh(B);
    }
    private String nodeText(AccessibilityNodeInfo node){if(node==null)return "<no matching dialog>";StringBuilder text=new StringBuilder();text.append(node.getPackageName()).append(':').append(node.getText()).append(' ');for(int i=0;i<node.getChildCount()&&text.length()<4096;i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null){text.append(nodeText(child));child.recycle();}}return text.toString();}
    private void metadataCorruption()throws Exception{
        enable(A,true,false);enable(B,true,false);
        android.content.SharedPreferences prefs=c.getSharedPreferences(PluginStateStore.PREFS,0);
        for(String field:new String[]{"first_install","replacing_until"}){
            prefs.edit().putString("p."+A+"."+field,"bad-type").commit();registry.refreshNow();
            check(!registry.findByPackage(A).state.enabled&&registry.findByPackage(B).state.enabled,"corrupt "+field+" disables only affected plugin and scan continues");
            enable(A,true,false);
        }
        registry.stateStore().disable(A);registry.stateStore().disable(B);close(A);close(B);registry.refreshNow();
    }
    private void storageFailure(IBinder a,IBinder b)throws Exception{
        enable(A,true,true);enable(B,true,false);PageLease la=lease(a,A),lb=lease(b,B);
        android.content.SharedPreferences real=c.getSharedPreferences(PluginStateStore.PREFS,0);
        java.util.concurrent.atomic.AtomicBoolean fail=new java.util.concurrent.atomic.AtomicBoolean(true);
        android.content.SharedPreferences proxy=(android.content.SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{android.content.SharedPreferences.class},(self,method,arguments)->{
            if(method.getName().equals("edit")){
                android.content.SharedPreferences.Editor editor=real.edit();
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{android.content.SharedPreferences.Editor.class},(ep,em,ea)->{
                    if(em.getName().equals("commit")&&fail.get())return false;
                    try{Object value=em.invoke(editor,ea);return value instanceof android.content.SharedPreferences.Editor?ep:value;}catch(InvocationTargetException ex){throw ex.getCause();}
                });
            }
            try{return method.invoke(real,arguments);}catch(InvocationTargetException ex){throw ex.getCause();}
        });
        Context wrapped=new android.content.ContextWrapper(c){public Context getApplicationContext(){return this;}public android.content.SharedPreferences getSharedPreferences(String name,int mode){return PluginStateStore.PREFS.equals(name)?proxy:super.getSharedPreferences(name,mode);}};
        PluginStateStore fault=new PluginStateStore(wrapped),original=registry.stateStore();Field sf=PluginRegistry.class.getDeclaredField("store");sf.setAccessible(true);
        Activity manager=startActivitySync(new Intent(c,PluginManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));List<android.view.View> revokers=new ArrayList<>();
        long end=SystemClock.elapsedRealtime()+3000;while(revokers.size()<2&&SystemClock.elapsedRealtime()<end){runOnMainSync(()->{revokers.clear();collectRevokers(manager.getWindow().getDecorView(),revokers);});if(revokers.size()<2)SystemClock.sleep(30);}
        check(revokers.size()>=2,"storage-failure test has actual grant controls");
        try{
            sf.set(registry,fault);check(registry.stateStore()==fault,"test-only fault injection active");
            runOnMainSync(()->revokers.get(0).performClick());
            check(fault.storageFailed(A)&&!fault.load(A).enabled,"commit=false is reported and blocks runtime grant");
            check(load(a,la,411).status!=PluginStatus.OK,"actual cross-UID session cannot read after failed revoke persistence");
            check(load(b,lb,412).status==PluginStatus.OK,"unchanged sibling plugin remains usable without unnecessary writes");
            fail.set(false);check(fault.retryDisable(A),"explicit retry persists disabled state after storage recovers");
            check(!fault.storageFailed(A)&&!real.getBoolean("p."+A+".enabled",true)&&!real.getBoolean("p."+A+".read",true),"current preferences reflect successful retry (restart is verified separately)");
        }finally{fail.set(false);sf.set(registry,original);runOnMainSync(manager::finish);close(A);close(B);original.disable(B);registry.refreshNow();}
    }
    private void collectRevokers(android.view.View view,List<android.view.View> out){
        if(view instanceof android.widget.TextView){String text=((android.widget.TextView)view).getText().toString();if(view.isClickable()&&(text.contains("点击撤销")||text.toLowerCase(java.util.Locale.US).contains("revoke")))out.add(view);}
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)view;for(int i=0;i<g.getChildCount();i++)collectRevokers(g.getChildAt(i),out);}
    }
    private void grantClickRace()throws Exception{
        enable(A,true,true);
        Activity manager=startActivitySync(new Intent(c,PluginManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        List<android.view.View> revokers=new ArrayList<>();long end=SystemClock.elapsedRealtime()+3000;
        while(revokers.size()!=2&&SystemClock.elapsedRealtime()<end){runOnMainSync(()->{revokers.clear();collectRevokers(manager.getWindow().getDecorView(),revokers);});if(revokers.size()!=2)SystemClock.sleep(30);}
        check(revokers.size()==2,"two independent grant controls rendered");
        runOnMainSync(()->{revokers.get(0).performClick();revokers.get(1).performClick();});
        PluginState state=registry.stateStore().load(A);check(!state.readGranted&&!state.requestGranted,"rapid actual UI revokes cannot restore sibling grant");
        runOnMainSync(manager::finish);close(A);
    }
    private AccessibilityNodeInfo findAction(AccessibilityNodeInfo node){
        if(node==null)return null;String text=node.getText()==null?"":node.getText().toString();
        if(node.isClickable()&&(text.contains("进入系统设置")||text.toLowerCase(java.util.Locale.US).contains("system settings")))return AccessibilityNodeInfo.obtain(node);
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i),found=findAction(child);if(child!=null)child.recycle();if(found!=null)return found;}return null;
    }
    private void requestClickRevalidation(IBinder probe)throws Exception{
        enable(A,false,true);PageLease lease=lease(probe,A);
        Activity manager=startActivitySync(new Intent(c,PluginManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));probeNumber(probe,7);startAuth(0,lease.leaseId);
        AccessibilityNodeInfo action=null;long end=SystemClock.elapsedRealtime()+3000;
        while(action==null&&SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();action=findAction(root);if(root!=null)root.recycle();if(action==null)SystemClock.sleep(30);}
        check(action!=null,"result-style caller opens valid host access page with request-only grant");
        check(registry.stateStore().setGrant(refresh(A),false,false),"request permission revoked while access page open");
        try{check(action.performAction(AccessibilityNodeInfo.ACTION_CLICK),"host access action clicked after revoke");}finally{action.recycle();}
        check(awaitAuth(probe)==PluginStatus.ACCESS_REVOKED,"host rechecks grant on click before system settings");
        runOnMainSync(manager::finish);close(A);
    }
    private void sdkBindingLifecycle()throws Exception{
        final class FakeContext extends android.content.ContextWrapper {
            final List<ServiceConnection> attempts=new ArrayList<>(),removed=new ArrayList<>();
            FakeContext(){super(c);}
            public Context getApplicationContext(){return this;}
            public String getPackageName(){return A;}
            public boolean bindService(Intent i,ServiceConnection connection,int flags){attempts.add(connection);return true;}
            public void unbindService(ServiceConnection connection){removed.add(connection);}
        }
        FakeContext fake=new FakeContext();PluginClient client=new PluginClient(fake,"",true);
        LaunchTicket ticket=new LaunchTicket("sdk-test","sdk-test",A,"fixture",1,100,1,0,60000,1,1,1,1,"test");
        CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1);
        IHostStatsBridge.Stub local=new IHostStatsBridge.Stub(){
            public void openSession(LaunchTicket t,IPageClient anchor,IOpenCallback cb)throws RemoteException{cb.onResult(new OpenResult(PluginStatus.UNAVAILABLE,null,"local SDK test"));}
            public void loadCurrentUsageSummary(PageLease l,long id,IUsageCallback cb){}
            public void cancelRequest(PageLease l,long id){}
            public void closeSession(PageLease l){}
        };
        runOnMainSync(()->client.open(ticket,(s,l,m)->first.countDown()));
        ServiceConnection one=fake.attempts.get(0);
        runOnMainSync(()->one.onServiceConnected(new ComponentName("com.afs.guard","com.afs.host.plugins.HostStatsBridgeService"),local));
        check(first.await(2,TimeUnit.SECONDS),"SDK mock first binding completes");
        SystemClock.sleep(1800);
        runOnMainSync(()->{one.onServiceDisconnected(new ComponentName("com.afs.guard","x"));client.open(ticket,(s,l,m)->second.countDown());});
        ServiceConnection two=fake.attempts.get(1);SystemClock.sleep(1400);
        runOnMainSync(()->check(!fake.removed.contains(two),"old SDK binding timeout cannot unbind new attempt"));
        runOnMainSync(()->two.onServiceConnected(new ComponentName("com.afs.guard","com.afs.host.plugins.HostStatsBridgeService"),local));
        check(second.await(2,TimeUnit.SECONDS),"SDK mock reconnection completes");
        runOnMainSync(client::destroy);
        check(java.util.Collections.frequency(fake.removed,one)==1&&java.util.Collections.frequency(fake.removed,two)==1,"SDK releases established and disconnected bindings exactly once");
    }
    private void appOp(String mode)throws Exception{
        ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("appops set com.afs.guard GET_USAGE_STATS "+mode);
        try(java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] bytes=new byte[1024];while(in.read(bytes)!=-1){}}
    }
    private void livePermissionBoundary(IBinder probe,PageLease lease)throws Exception{
        ThreadPoolExecutor workers=(ThreadPoolExecutor)field(broker,"workers"),control=(ThreadPoolExecutor)field(broker,"control");
        CountDownLatch holding=new CountDownLatch(2),runQuery=new CountDownLatch(1),controlHeld=new CountDownLatch(1),deliver=new CountDownLatch(1),answer=new CountDownLatch(1);
        Runnable block=()->{holding.countDown();try{runQuery.await(8,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}};
        workers.execute(block);workers.execute(block);check(holding.await(3,TimeUnit.SECONDS),"query workers held for send-boundary test");
        java.util.concurrent.atomic.AtomicReference<UsageResult> received=new java.util.concurrent.atomic.AtomicReference<>();
        broker.load(lease,lease.uid,301,new IUsageCallback.Stub(){public void onResult(UsageResult r){received.set(r);answer.countDown();}});
        long wait=SystemClock.elapsedRealtime()+2000;while(workers.getQueue().isEmpty()&&SystemClock.elapsedRealtime()<wait)SystemClock.sleep(10);
        check(!workers.getQueue().isEmpty(),"query admitted before permission change");
        control.execute(()->{controlHeld.countDown();try{deliver.await(8,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}});
        check(controlHeld.await(2,TimeUnit.SECONDS),"delivery held independently of collector");runQuery.countDown();
        boolean ready=false;wait=SystemClock.elapsedRealtime()+2500;
        while(!ready&&SystemClock.elapsedRealtime()<wait){for(Object op:(Set<?>)field(broker,"operations"))if(((Long)field(op,"id"))==301L&&(Boolean)field(op,"ready"))ready=true;if(!ready)SystemClock.sleep(10);}
        check(ready,"collection completed before AppOps revoke");
        try{
            appOp("ignore");check(!com.afs.host.usage.HostUsageStats.hasUsageAccess(c),"real OS usage permission revoked");
            deliver.countDown();check(answer.await(4,TimeUnit.SECONDS),"revoked-permission callback resolved");
            check(received.get().status==PluginStatus.PERMISSION_REQUIRED&&received.get().summary==null,"send boundary drops collected data after AppOps revoke");
            check(load(probe,lease,302).status==PluginStatus.PERMISSION_REQUIRED,"cross UID query observes OS permission revoke");
        }finally{deliver.countDown();runQuery.countDown();appOp("allow");}
    }
    private void saturation(IBinder a,PageLease l)throws Exception{
        ThreadPoolExecutor control=(ThreadPoolExecutor)field(broker,"control");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        control.execute(()->{entered.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});check(entered.await(2,TimeUnit.SECONDS),"control held for deterministic race");
        java.util.concurrent.atomic.AtomicInteger callbacks=new java.util.concurrent.atomic.AtomicInteger();
        broker.load(l,l.uid,99,new IUsageCallback.Stub(){public void onResult(UsageResult v){callbacks.incrementAndGet();}});
        broker.cancel(l,l.uid,99); // cancel before loadNow is admitted
        int accepted=0;while(accepted<64){try{control.execute(()->{});accepted++;}catch(RejectedExecutionException full){break;}}
        ThreadPoolExecutor workers=(ThreadPoolExecutor)field(broker,"workers");long scheduled=workers.getTaskCount();
        release.countDown();
        long cancelUntil=SystemClock.elapsedRealtime()+2500;
        while(SystemClock.elapsedRealtime()<cancelUntil&&!((Set<?>)field(broker,"operations")).isEmpty())SystemClock.sleep(10);
        check(callbacks.get()==0,"canceled-before-admission sends no result");
        check(workers.getTaskCount()==scheduled,"canceled-before-admission never schedules collection");
        check(load(a,l,99).status==PluginStatus.OK,"same valid lease and request ID work after cancellation");
        CountDownLatch sending=new CountDownLatch(1),finishSend=new CountDownLatch(1);
        broker.load(l,l.uid,201,new IUsageCallback.Stub(){public void onResult(UsageResult v){sending.countDown();try{finishSend.await(6,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
        check(sending.await(6,TimeUnit.SECONDS),"internal controlled callback entered send");
        CompletableFuture<Void> first=broker.invalidatePackage(A,PluginStatus.REVOKED),second=broker.invalidatePackage(A,PluginStatus.REVOKED);
        check(!first.isDone()&&!second.isDone(),"repeated revocations await ongoing internal send barrier");
        finishSend.countDown();first.get(8,TimeUnit.SECONDS);second.get(8,TimeUnit.SECONDS);
        long until=SystemClock.elapsedRealtime()+2000;
        while(SystemClock.elapsedRealtime()<until&&(!((Set<?>)field(broker,"operations")).isEmpty()||!((Queue<?>)field(broker,"retired")).isEmpty()))SystemClock.sleep(10);
        check(((Set<?>)field(broker,"operations")).isEmpty()&&((Queue<?>)field(broker,"retired")).isEmpty(),"saturated queues drain operations and retired sessions");
        PageLease fresh=lease(a,A);check(load(a,fresh,99).status==PluginStatus.OK,"new lease works after completed revocation");
    }
}




