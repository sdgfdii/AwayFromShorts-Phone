package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Disposable-emulator fixture only. Never seed personal phone memory. */
public final class MemoryMomoDeviceTest extends Instrumentation {
    int checks;MemoryActivity host;volatile MemoryActivity resumedHost;Context c;String deck;File file;byte[] original,backup;boolean captured;AppearanceConfig appearance;boolean reduceExists,reduceOriginal;
    void check(boolean value,String label){checks++;android.util.Log.i("AFSMemoryQA",(value?"PASS ":"FAIL ")+label);if(!value)throw new AssertionError(label);}
    Object get(Object o,String field)throws Exception{Field f=o.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(o);}
    void set(Object o,String field,Object value)throws Exception{Field f=o.getClass().getDeclaredField(field);f.setAccessible(true);f.set(o,value);}
    void call(String name)throws Exception{Method m=MemoryActivity.class.getDeclaredMethod(name);m.setAccessible(true);runOnMainSync(()->{try{m.invoke(host);}catch(Exception e){throw new RuntimeException(e);}});}
    void cards()throws Exception{Method m=MemoryActivity.class.getDeclaredMethod("cards",boolean.class);m.setAccessible(true);runOnMainSync(()->{try{m.invoke(host,false);}catch(Exception e){throw new RuntimeException(e);}});}
    String content(View v){StringBuilder s=new StringBuilder();if(v instanceof TextView)s.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)s.append(content(g.getChildAt(i)));}return s.toString();}
    String screen()throws Exception{String[] out={""};runOnMainSync(()->{try{out[0]=content((View)get(host,"body"));}catch(Exception e){throw new RuntimeException(e);}});return out[0];}
    void waitText(String text)throws Exception{long end=SystemClock.elapsedRealtime()+12000;while(SystemClock.elapsedRealtime()<end){if(screen().contains(text))return;Thread.sleep(40);}throw new AssertionError("Missing UI text: "+text+"\n"+screen());}
    TextView find(View v,String text){if(v instanceof TextView&&text.equals(((TextView)v).getText().toString()))return (TextView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView found=find(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
    View tag(String tag)throws Exception{View[] out={null};runOnMainSync(()->{try{out[0]=((View)get(host,"body")).findViewWithTag(tag);}catch(Exception ex){throw new RuntimeException(ex);}});return out[0];}
    void click(String tag)throws Exception{View v=tag(tag);check(v!=null,"control exists: "+tag);runOnMainSync(v::performClick);waitForIdleSync();}
    int logs(){return MemoryStore.readOnlySnapshot(c).db.optJSONArray("logs").length();}
    boolean shown(View v){boolean[] out={false};runOnMainSync(()->out[0]=v!=null&&v.isShown());return out[0];}
    boolean hasSwipe(View v){if(v instanceof SwipeCardView)return true;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(hasSwipe(((ViewGroup)v).getChildAt(i)))return true;return false;}
    void seed(MemoryData data)throws Exception{write(file,data.db.toString().getBytes("UTF-8"));resetCache();call("home");waitForIdleSync();}
    void screenshot(String name)throws Exception{waitForIdleSync();Thread.sleep(250);android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),name))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}shot.recycle();}
    void swipeCard()throws Exception{
        int[] point=new int[2];int[] size=new int[2];runOnMainSync(()->{try{View v=(View)get(host,"activeStudyCard");v.getLocationOnScreen(point);size[0]=v.getWidth();size[1]=v.getHeight();}catch(Exception ex){throw new RuntimeException(ex);}});
        float x=point[0]+size[0]*.5f,y=point[1]+size[1]*.6f;long down=SystemClock.uptimeMillis();
        for(int i=0;i<=8;i++){int action=i==0?MotionEvent.ACTION_DOWN:i==8?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
            MotionEvent event=MotionEvent.obtain(down,down+i*25,action,x,y-i*size[1]*.05f,0);sendPointerSync(event);event.recycle();}
        waitForIdleSync();
    }
    void buttonFlow()throws Exception{
        MemoryData data=fixture(1);JSONObject card=data.cards().optJSONObject(0);String id=card.optString("id");
        seed(data);waitText(T.s("先回忆，再显示答案并选择记忆程度"));int initial=logs();byte[] before=read(file);
        check(!screen().contains(T.f("待复习 %d · 待新学 %d · 已处理 %d",1,0,0))&&!screen().contains(T.s("入队 ")),"study card omits load rows");
        check(shown(tag("memory-speaker"))&&!screen().contains(T.s("英音"))&&!screen().contains(T.s("美音")),"single icon replaces accent text buttons");
        TextView word=(TextView)tag("memory-word");
        check(word.getTextSize()>=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,36,host.getResources().getDisplayMetrics())-.5f,"word keeps enlarged size after global typography");
        boolean[] foundSwipe={false};runOnMainSync(()->{try{foundSwipe[0]=hasSwipe((View)get(host,"body"));}catch(Exception ex){throw new RuntimeException(ex);}});
        check(!foundSwipe[0],"study has no swipe wrapper");
        swipeCard();check(initial==logs()&&Arrays.equals(before,read(file)),"vertical drag cannot submit or reveal");
        runOnMainSync(()->{try{((View)get(host,"activeStudyCard")).performClick();}catch(Exception ex){throw new RuntimeException(ex);}});
        check(initial==logs()&&!(Boolean)get(host,"revealed"),"tapping card never reveals or grades");
        click("memory-show-answer");check(initial==logs()&&Arrays.equals(before,read(file)),"reveal is read-only");
        TextView meaning=(TextView)tag("memory-meaning");
        check(meaning.getTextSize()>=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,26,host.getResources().getDisplayMetrics())-.5f,"meaning uses enlarged size");
        check((Boolean)get(host,"revealed")&&!(Boolean)get(host,"graded"),"answer awaits explicit choice");
        recreateLive();waitText(T.s("先回忆，再显示答案并选择记忆程度"));
        check((Boolean)get(host,"revealed")&&initial==logs(),"revealed state survives recreation without grading");
        View stale=tag("memory-rate-2");runOnMainSync(()->{stale.performClick();stale.performClick();});waitForIdleSync();
        MemoryData after=MemoryStore.readOnlySnapshot(c);
        check(logs()==initial+1&&after.db.optJSONArray("logs").optJSONObject(initial).optInt("rating")==2,"double vague click submits exactly once");
        check(after.card(id).optInt("state")==MemoryScheduler.RELEARNING&&!after.db.optJSONArray("logs").optJSONObject(initial).optBoolean("credit"),"vague schedules relearning without target credit");
        waitText(T.s("本轮回忆已结束，稍后继续巩固"));
        recreateLive();waitText(T.s("本轮回忆已结束，稍后继续巩固"));check(logs()==initial+1,"waiting restart preserves pending attempt");
        // Advance only synthetic card due on the owned emulator, never the system clock.
        MemoryData.put(after.card(id),"due",System.currentTimeMillis()-1000);seed(after);
        click("memory-show-answer");click("memory-rate-3");waitText(T.s("本日闪卡已完成"));
        check(MemoryFlashcards.progress(MemoryStore.readOnlySnapshot(c),System.currentTimeMillis()).done==1,"remembered confirmation finishes card");
        // Input matching is a hint, never an implicit rating.
        data=fixture(0);JSONObject input=MemoryData.newCard(deck,"input","Type fixture","correct","","");data.saveCard(input);seed(data);
        runOnMainSync(()->{try{((EditText)get(host,"typed")).setText("correct");}catch(Exception ex){throw new RuntimeException(ex);}});
        click("memory-show-answer");check(logs()==0&&screen().contains(T.s("答案匹配，请选择记忆程度")),"matching input still requires explicit rating");
        click("memory-rate-1");waitText(T.s("本轮回忆已结束，稍后继续巩固"));
        check(logs()==1&&MemoryStore.readOnlySnapshot(c).cards().optJSONObject(0).optInt("lapses")==1,"forget button records lapse and keeps queue");
        // Don't know commits before revealing, then offers a non-scoring Next action.
        data=fixture(1);seed(data);waitText(T.s("先回忆，再显示答案并选择记忆程度"));
        screenshot("memory-layout-front.png");
        View oldUnknown=tag("memory-unknown");check(shown(oldUnknown)&&!shown(tag("memory-rate-1")),"don't know is offered only before reveal");
        runOnMainSync(()->{oldUnknown.performClick();oldUnknown.performClick();});waitForIdleSync();
        MemoryData unknownData=MemoryStore.readOnlySnapshot(c);String unknownId=unknownData.cards().optJSONObject(0).optString("id");
        check(logs()==1&&unknownData.db.optJSONArray("logs").optJSONObject(0).optInt("rating")==1,"double don't-know click records one forgetting rating");
        check((Boolean)get(host,"revealed")&&(Boolean)get(host,"graded")&&shown(tag("memory-next-card"))&&!shown(tag("memory-rate-3")),"committed answer offers Next without another rating");
        screenshot("memory-layout-unknown.png");
        recreateLive();waitText(T.s("已记为遗忘 · 下次复习："));
        check(logs()==1&&unknownId.equals(((JSONObject)get(host,"current")).optString("id"))&&shown(tag("memory-next-card")),"committed answer survives recreation without resubmitting or skipping");
        View oldNext=tag("memory-next-card");runOnMainSync(()->{oldNext.performClick();oldNext.performClick();oldUnknown.performClick();});waitForIdleSync();
        waitText(T.s("本轮回忆已结束，稍后继续巩固"));check(logs()==1,"Next and stale controls cannot grade again");
        // Return to a long-answer card for layout/screenshot verification.
        data=fixture(1);StringBuilder longAnswer=new StringBuilder();for(int i=0;i<100;i++)longAnswer.append("Long answer line ").append(i).append('\n');
        MemoryData.put(data.cards().optJSONObject(0),"answer",longAnswer.toString());seed(data);click("memory-show-answer");
    }
    byte[] read(File f)throws Exception{if(!f.exists())return null;try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    void write(File f,byte[] b)throws Exception{if(b==null){if(f.exists()&&!f.delete())throw new IOException("delete fixture");}else try(OutputStream out=new FileOutputStream(f)){out.write(b);}}
    void resetCache()throws Exception{Field f=MemoryStore.class.getDeclaredField("cache");f.setAccessible(true);f.set(null,null);}
    MemoryData fixture(int count){MemoryData m=new MemoryData();deck=m.activeDecks().optJSONObject(0).optString("id");long now=System.currentTimeMillis();
        for(int i=0;i<count;i++){JSONObject card=MemoryData.newCard(deck,"qa",String.format(Locale.US,"Fixture %03d",i),"Fixture answer","","");
            if(i%2==0){MemoryData.put(card,"reps",5);MemoryData.put(card,"lapses",i%5);MemoryData.put(card,"state",MemoryScheduler.REVIEW);MemoryData.put(card,"last",now-3*MemoryScheduler.DAY);MemoryData.put(card,"due",now-1);MemoryData.put(card,"stability",new double[]{9,10,30,60,90}[i%5]);MemoryData.put(card,"difficulty",5);}
            if(count>300)m.cards().put(card);else m.saveCard(card);
        }return m;
    }
    @Override public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity instanceof MemoryActivity)resumedHost=(MemoryActivity)activity;}
    void recreateLive()throws Exception{
MemoryActivity previous=host;runOnMainSync(previous::recreate);
            long recreateUntil=SystemClock.elapsedRealtime()+8000;
            do{Thread.sleep(150);waitForIdleSync();}while(SystemClock.elapsedRealtime()<recreateUntil&&(resumedHost==null||resumedHost==previous||resumedHost.isDestroyed()));
            host=resumedHost;check(host!=null&&host!=previous&&!host.isDestroyed(),"activity recreated");
    }
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();int code=-1;c=getTargetContext();file=new File(c.getFilesDir(),"memory-v1.json");File bak=new File(file+".bak");
        try{
            check("1".equals(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk")?"1":"0"),"emulator build confirmed");
            appearance=ThemeStore.load(c);android.content.SharedPreferences uiPrefs=c.getSharedPreferences(Ui.SP,0);reduceExists=uiPrefs.contains("reduce_motion");reduceOriginal=uiPrefs.getBoolean("reduce_motion",false);original=read(file);backup=read(bak);captured=true;MemoryData m=fixture(300);write(file,m.db.toString().getBytes("UTF-8"));write(bak,null);resetCache();
            byte[] before=read(file);long revision=MemoryStore.revision();MemoryData snapshot=MemoryStore.readOnlySnapshot(c);
            check(Arrays.equals(before,read(file))&&revision==MemoryStore.revision(),"readOnlySnapshot leaves disk/revision unchanged");
            check(!MemoryFlashcards.inspectLoad(snapshot,System.currentTimeMillis()).sessionExists,"snapshot did not initialize flash queue");
            Intent intent=new Intent(c,MemoryActivity.class).putExtra(CardManagementActivity.EXTRA_MANAGE,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host=(MemoryActivity)startActivitySync(intent);waitForIdleSync();
            // The legacy management entry initializes plans. Reset fixture before testing new read paths.
            write(file,m.db.toString().getBytes("UTF-8"));resetCache();before=read(file);revision=MemoryStore.revision();
            String checkins=c.getSharedPreferences("afs_checkin",0).getAll().toString();String rules=c.getSharedPreferences("afs_guard",0).getString("rules",null);
            call("stats");waitText(T.s("FSRS稳定性分布"));
            check(Arrays.equals(before,read(file))&&MemoryStore.revision()==revision,"statistics UI does not create plans or save");
            check(screen().contains(T.s("所选范围还没有评分记录。")),"empty rating range explained");
            set(host,"statsPeriod",2);call("stats");waitText(T.s("FSRS稳定性分布"));check(((Integer)get(host,"statsPeriod"))==2,"monthly mode rendered");
            set(host,"deck",deck);cards();waitText(T.f("共 %d 张 · 第 %d 页",300,1));
            check(Arrays.equals(before,read(file)),"card browse remains read-only");
            TextView[] buttons={null};runOnMainSync(()->{try{buttons[0]=find((View)get(host,"body"),T.s("下一页"));}catch(Exception ex){throw new RuntimeException(ex);}});TextView next=buttons[0];check(next!=null,"pagination next button exists");runOnMainSync(next::performClick);waitText(T.f("共 %d 张 · 第 %d 页",300,2));
            check(((Integer)get(host,"cardOffset"))==50,"second page selected");
            recreateLive();waitText(T.f("共 %d 张 · 第 %d 页",300,2));check(((Integer)get(host,"cardOffset"))==50,"pagination survives recreation and initial Spinner callback");
            set(host,"cardFilter",MemoryCardQuery.Filter.DIFFICULT);set(host,"cardOffset",0);cards();waitText(T.f("共 %d 张 · 第 %d 页",60,1));
            set(host,"cardFilter",MemoryCardQuery.Filter.TODAY);cards();waitText(T.s("今日队列尚未生成；进入记忆学习时再生成。"));
            check(Arrays.equals(before,read(file))&&MemoryStore.revision()==revision,"filter/rotation did not generate learning queue");
            call("tools");check(tag("memory-load-settings")!=null&&screen().contains(T.f("待复习 %d · 待新学 %d · 已处理 %d",0,0,0)),"load breakdown moved into memory settings");
            // Deliberately retain the original management Intent: restored page mode must win.
            set(host,"managementMode",false);call("home");waitText(T.s("先回忆，再显示答案并选择记忆程度"));
            check(tag("memory-load-settings")==null,"settings load panel is absent from study page");
            byte[] learningBefore=read(file);Thread.sleep(150);check(Arrays.equals(learningBefore,read(file)),"preview completion causes no extra writes");
            check(checkins.equals(c.getSharedPreferences("afs_checkin",0).getAll().toString()),"ordinary check-in state unchanged");check(Objects.equals(rules,c.getSharedPreferences("afs_guard",0).getString("rules",null)),"freeze rules unchanged");
            buttonFlow();
            android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"memory-momo-preview.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
            AppearanceConfig enlarged=appearance.copy();enlarged.mode="dark";enlarged.customScale=true;enlarged.scale=1.4f;ThemeStore.save(c,enlarged);
            c.getSharedPreferences(Ui.SP,0).edit().putBoolean("reduce_motion",true).commit();
            recreateLive();waitText(T.s("先回忆，再显示答案并选择记忆程度"));
            check(((Ui)get(host,"ui")).dark,"dark theme applied");
            check(host.getResources().getConfiguration().fontScale>=1.39f,"large font applied");
            TextView previewView=(TextView)tag("memory-rate-3");View swipeView=(View)get(host,"activeStudyCard");
            int[] previewPoint=new int[2],swipePoint=new int[2];runOnMainSync(()->{previewView.getLocationOnScreen(previewPoint);swipeView.getLocationOnScreen(swipePoint);});
            check(previewView.isShown()&&previewPoint[1]>=swipePoint[1]&&previewPoint[1]+previewView.getHeight()<=swipePoint[1]+swipeView.getHeight(),"rating controls visible with long answer, enlarged font and reduced motion");
            View vague=tag("memory-rate-2"),forget=tag("memory-rate-1");
            boolean[] reachable={false};runOnMainSync(()->{android.graphics.Rect visible=new android.graphics.Rect();reachable[0]=vague.isShown()&&forget.isShown()&&vague.getGlobalVisibleRect(visible)&&visible.height()==vague.getHeight()&&forget.getGlobalVisibleRect(visible)&&visible.height()==forget.getHeight();});
            check(reachable[0],"all three buttons fit the small-screen viewport");screenshot("memory-buttons-large.png");
            Thread.sleep(500);int[] dockTop={Integer.MAX_VALUE},buttonBottom={0},layout={0,0,0,0};
            runOnMainSync(()->{try{
                Method findWindow=LiveNavWindow.class.getDeclaredMethod("find",Activity.class);findWindow.setAccessible(true);Object window=findWindow.invoke(null,host);
                if(window==null)throw new AssertionError("active study dock must stay registered after recreation");
                View dock=(View)get(window,"nav");int[] dockPoint=new int[2];dock.getLocationOnScreen(dockPoint);dockTop[0]=dockPoint[1];
                int[] point=new int[2];forget.getLocationOnScreen(point);buttonBottom[0]=point[1]+forget.getHeight();
                View body=(View)get(host,"body");View surface=(View)get(host,"activeStudyCard");layout[0]=body.getHeight();layout[1]=body.getPaddingBottom();layout[2]=surface.getHeight();layout[3]=surface.getMinimumHeight();
            }catch(Exception ex){throw new RuntimeException(ex);}});
            check(buttonBottom[0]<=dockTop[0]-new Ui(host).dp(8),"rating buttons above live dock: bottom="+buttonBottom[0]+" dock="+dockTop[0]+" layout="+Arrays.toString(layout));
            screenshot("memory-buttons-large-settled.png");android.util.Log.i("AFSMemoryQA","settled button bottom="+buttonBottom[0]+" dock="+dockTop[0]+" layout="+Arrays.toString(layout));
            enlarged.mode="light";ThemeStore.save(c,enlarged);recreateLive();waitText(T.s("先回忆，再显示答案并选择记忆程度"));check(!((Ui)get(host,"ui")).dark,"light theme applied");
            MemoryData large=fixture(30000);long heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();long pss=android.os.Debug.getPss();long start=SystemClock.elapsedRealtime();
            java.util.List<String> ids=MemoryCardQuery.page(large,MemoryCardQuery.Filter.ALL,Collections.emptySet(),System.currentTimeMillis(),0,50);
            MemoryStats.periods(large,System.currentTimeMillis(),MemoryStats.Period.MONTH,12,Collections.emptySet());
            long elapsed=SystemClock.elapsedRealtime()-start;check(ids.size()==50,"30000-card device projection remains paginated");
            result.putString("stream","Memory Momo device: "+checks+" checks passed\n30000-card query+period aggregation: "+elapsed+" ms (device model calculation, not frame time)\nJava heap with fixture: "+heap/1048576+" MiB; total process PSS: "+pss/1024+" MiB (test process, not idle app)\n");
        }catch(Throwable e){code=1;result.putString("stream","FAILED "+android.util.Log.getStackTraceString(e));}
        finally{try{if(host!=null)runOnMainSync(host::finish);if(captured){write(file,original);write(bak,backup);resetCache();ThemeStore.save(c,appearance);android.content.SharedPreferences.Editor restoreUi=c.getSharedPreferences(Ui.SP,0).edit();if(reduceExists)restoreUi.putBoolean("reduce_motion",reduceOriginal);else restoreUi.remove("reduce_motion");restoreUi.commit();}}catch(Throwable cleanup){code=1;result.putString("stream",result.getString("stream","")+"\nCLEANUP FAILED "+cleanup);}}
        finish(code,result);
    }
}
