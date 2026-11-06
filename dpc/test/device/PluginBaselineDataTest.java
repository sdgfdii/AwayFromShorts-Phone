package com.afs.guard;
import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.os.Bundle;
import java.io.*;
import java.util.*;
import org.json.*;
/** Test-only, real nonempty business data and an already-active rule, seeded before migration. */
public final class PluginBaselineDataTest extends Instrumentation {
 public static final String VICTIM="com.afs.plugin.rulevictim";
 public static void requireEmulator(){String hardware=android.os.Build.HARDWARE;if(!"ranchu".equals(hardware)&&!"goldfish".equals(hardware))throw new AssertionError("Synthetic plugin fixtures require a dedicated Android emulator");}
 private static File marker(Context c){return new File(c.getFilesDir(),"plugin-r2-baseline-hashes.json");}
 private static String canonical(Object v)throws Exception{
  if(v instanceof JSONObject){JSONObject o=(JSONObject)v;List<String> keys=new ArrayList<>();for(Iterator<String> it=o.keys();it.hasNext();)keys.add(it.next());Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(o.get(key)));}return out.append('}').toString();}
  if(v instanceof JSONArray){JSONArray a=(JSONArray)v;StringBuilder out=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)out.append(',');out.append(canonical(a.get(i)));}return out.append(']').toString();}
  return v==null||v==JSONObject.NULL?"null":v instanceof String?JSONObject.quote((String)v):String.valueOf(v);
 }
 private static JSONObject hashes(Context c)throws Exception{
  JSONObject out=new JSONObject();for(Map.Entry<String,byte[]> entry:LocalBackup.snapshot(c).entrySet()){
   byte[] bytes=entry.getValue();
   if(entry.getKey().equals("preferences.json")){JSONObject p=new JSONObject(new String(bytes,"UTF-8"));JSONObject quota=p.optJSONObject("afs_daily_quota");if(quota!=null)quota.remove("usage");bytes=canonical(p).getBytes("UTF-8");}
   out.put(entry.getKey(),LocalArchive.hash(bytes));
  }return out;
 }
 public static void seed(Context c)throws Exception{
  requireEmulator();
  if(marker(c).isFile()){verify(c);return;}
  StudyStore.G goal=new StudyStore.G();goal.id="goal-r2-existing";goal.name="R2 existing archived goal";goal.unit="items";goal.total=3;goal.archived=true;StudyStore.upsert(c,goal);
  Calendar day=Calendar.getInstance();day.add(Calendar.DAY_OF_YEAR,-1);String date=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(day.getTime());StudyStore.check(c,goal.id,1,date);
  MemoStore.saveDraft(c,null,"R2 existing note","Existing note must survive plugin lifecycle.",0);
  MemoryStore.addDeck(c,"R2 existing memory","QA");MemoryData memory=MemoryStore.snapshot(c);String deck="";
  for(int i=0;i<memory.decks().length();i++)if("R2 existing memory".equals(memory.decks().optJSONObject(i).optString("name")))deck=memory.decks().optJSONObject(i).optString("id");
  if(deck.isEmpty())throw new AssertionError("memory seed missing");
  MemoryStore.saveCard(c,MemoryData.newCard(deck,"qa","R2 memory question","Independent memory answer","QA",""));MemoryStore.snapshot(c);CardStore.snapshot(c);
  // A real installed, noncritical package remains frozen throughout subsequent plugin operations.
  c.getSharedPreferences("afs_guard",0).edit().putString("rules","{\""+VICTIM+"\":{\"d\":127,\"w\":[],\"g\":false}}").commit();Guard.apply(c);
  DevicePolicyManager d=(DevicePolicyManager)c.getSystemService(Context.DEVICE_POLICY_SERVICE);
  if(!d.isDeviceOwnerApp(c.getPackageName())||!d.isPackageSuspended(new ComponentName(c,AfsAdmin.class),VICTIM))throw new AssertionError("baseline rule not actually enforced");
  JSONObject expected=hashes(c);try(FileOutputStream out=new FileOutputStream(marker(c))){out.write(expected.toString().getBytes("UTF-8"));out.getFD().sync();}verify(c);
 }
 public static int verify(Context c)throws Exception{
  requireEmulator();
  if(!marker(c).isFile())throw new AssertionError("Seed existing business data first on this disposable emulator");
  JSONObject expected;try(InputStream in=new FileInputStream(marker(c))){expected=new JSONObject(new String(LocalArchive.read(in,1024*1024),"UTF-8"));}
  JSONObject actual=hashes(c);if(expected.length()!=actual.length())throw new AssertionError("business archive entry count changed");int checks=1;
  for(Iterator<String> it=expected.keys();it.hasNext();){String name=it.next();if(!expected.getString(name).equals(actual.optString(name))&&!dailyPlanOnly(c,name,expected.getString(name)))throw new AssertionError("existing business data changed: "+name);checks++;}
  boolean goalExists=false;JSONArray savedGoals=new JSONObject(StudyStore.exportJson(c)).getJSONArray("goals");for(int i=0;i<savedGoals.length();i++)if("goal-r2-existing".equals(savedGoals.getJSONObject(i).optString("id")))goalExists=true;
  if(MemoStore.search(c,"R2 existing note").isEmpty()||!goalExists)throw new AssertionError("existing note or ordinary goal disappeared");checks++;
  int ordinary=0;for(StudyStore.Rec r:StudyStore.recs(c)){if(r.goalId.startsWith(MemoryData.GOAL_PREFIX))throw new AssertionError("memory leaked into ordinary check-ins");if(r.goalId.equals("goal-r2-existing"))ordinary++;}
  if(ordinary!=1)throw new AssertionError("ordinary check-in count changed");checks++;
  DevicePolicyManager d=(DevicePolicyManager)c.getSystemService(Context.DEVICE_POLICY_SERVICE);
  if(!d.isPackageSuspended(new ComponentName(c,AfsAdmin.class),VICTIM))throw new AssertionError("existing frozen package was released");checks++;
  return checks;
 }
 // The baseline was seeded before midnight. The unmodified 1.86 host creates the
 // next day's identical, unstudied plan on boot. Accept only that exact addition;
 // all preexisting bytes and all card/deck/log contents must still hash identically.
 private static boolean dailyPlanOnly(Context c,String name,String expected)throws Exception{
  if(!"files/memory-v1.json".equals(name))return false;
  JSONObject db;try(InputStream in=new FileInputStream(new File(c.getFilesDir(),"memory-v1.json"))){db=new JSONObject(new String(LocalArchive.read(in,1024*1024),"UTF-8"));}
  JSONObject plans=db.optJSONObject("plans");if(plans==null||plans.length()!=2)return false;
  String today=MemoryData.date(System.currentTimeMillis());JSONObject added=plans.optJSONObject(today);if(added==null)return false;
  String prior=null;for(Iterator<String> it=plans.keys();it.hasNext();){String key=it.next();if(!today.equals(key))prior=key;}
  Calendar yesterday=Calendar.getInstance();yesterday.add(Calendar.DAY_OF_YEAR,-1);if(!MemoryData.date(yesterday.getTimeInMillis()).equals(prior))return false;
  if(prior==null||!canonical(added).equals(canonical(plans.getJSONObject(prior))))return false;
  for(Iterator<String> it=added.keys();it.hasNext();)if(added.getJSONObject(it.next()).getJSONArray("passed").length()!=0)return false;
  plans.remove(today);return expected.equals(LocalArchive.hash(db.toString().getBytes("UTF-8")));
 }
 private Bundle args;
 // Disposable-emulator cleanup only: an old board UI fixture can initialize its
 // derived slot catalog after its own finally block restores preferences. Never
 // erase an unverified difference or modify a user's device to make a test pass.
 private static void restoreCoreSlotFixture(Context c)throws Exception{
  requireEmulator();
  JSONObject expected;try(InputStream in=new FileInputStream(marker(c))){expected=new JSONObject(new String(LocalArchive.read(in,1024*1024),"UTF-8"));}
  JSONObject actual=hashes(c);if(expected.length()!=actual.length())throw new AssertionError("unexpected archive change during core fixture");
  for(Iterator<String> it=expected.keys();it.hasNext();){String key=it.next();if(!key.equals("preferences.json")&&!expected.getString(key).equals(actual.optString(key))&&!dailyPlanOnly(c,key,expected.getString(key)))throw new AssertionError("unrelated fixture change: "+key);}
  byte[] prefs=LocalBackup.snapshot(c).get("preferences.json");JSONObject clean=new JSONObject(new String(prefs,"UTF-8"));JSONObject quota=clean.optJSONObject("afs_daily_quota");if(quota!=null)quota.remove("usage");
  clean.getJSONObject("afs_guard").remove("freeze_slots");
  if(!expected.getString("preferences.json").equals(LocalArchive.hash(canonical(clean).getBytes("UTF-8"))))throw new AssertionError("slot catalog is not the sole post-fixture difference");
  if(!c.getSharedPreferences("afs_guard",0).edit().remove("freeze_slots").commit())throw new AssertionError("fixture cleanup write failed");
 }
 public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
 public void onStart(){Bundle result=new Bundle();int code=-1;try{Context c=getTargetContext();if("seed".equals(args.getString("mode")))seed(c);if("restore-core-slots".equals(args.getString("mode")))restoreCoreSlotFixture(c);int n=verify(c);result.putString("stream","Plugin baseline data: "+n+" checks passed\n");}catch(Throwable e){code=1;result.putString("stream","FAILED "+android.util.Log.getStackTraceString(e));}finish(code,result);}
}
