package com.afs.guard;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.Calendar;

/** Atomic, local card settings and reward accounting. No ordinary check-in records are modified. */
public final class CardStore {
    private static CardData cache;private static String path;private static File testFile;
    static synchronized void setTestFile(File file){testFile=file;cache=null;path=null;}
    private static AtomicFile file(Context c){return new AtomicFile(testFile!=null?testFile:new File(c.getFilesDir(),"cards-v1.json"));}
    private static CardData data(Context c){
        AtomicFile file=file(c);String current=file.getBaseFile().getAbsolutePath();if(cache!=null&&current.equals(path))return cache;
        if(file.getBaseFile().exists()||new File(file.getBaseFile()+".bak").exists()){
            try(InputStream in=file.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>8*1024*1024)throw new IOException(T.s("卡管理记录超过 8MB"));out.write(b,0,n);}
                cache=new CardData(out.toString("UTF-8"));path=current;return cache;
            }catch(Exception e){cache=null;throw new IllegalStateException(T.s("卡管理读取失败，原文件已保留"),e);}
        }
        long now=System.currentTimeMillis();CardData fresh=new CardData(now);
        String monday=LearningPolicy.weekKey(Calendar.getInstance());
        if(testFile==null&&c.getSharedPreferences("afs_learning_link",0).getBoolean("weekly_done:"+monday,false))fresh.summary(now).migrateWeek(monday);
        commit(c,fresh);return cache;
    }
    private static void commit(Context c,CardData next){
        AtomicFile file=file(c);FileOutputStream out=null;
        try{byte[] bytes=next.db.toString().getBytes("UTF-8");if(bytes.length>8*1024*1024)throw new IOException(T.s("卡管理记录超过 8MB"));out=file.startWrite();out.write(bytes);file.finishWrite(out);cache=next;path=file.getBaseFile().getAbsolutePath();}
        catch(Exception e){if(out!=null)file.failWrite(out);throw new IllegalStateException(T.s("卡管理保存失败，本次操作未确认"),e);}
        StudyGate.invalidate(c);
    }
    public static synchronized CardData snapshot(Context c){return new CardData(data(c).db.toString());}
    public static synchronized void saveLove(Context c,String id,String title,int reward){CardData next=snapshot(c);next.saveLove(id,title,reward);commit(c,next);}
    public static synchronized void archive(Context c,String id,boolean archived){CardData next=snapshot(c);next.archive(id,archived);commit(c,next);}
    public static synchronized boolean claim(Context c,String id){CardData next=snapshot(c);boolean changed=next.claim(id,System.currentTimeMillis());if(changed)commit(c,next);return changed;}
    public static synchronized long rewardUntil(Context c,long now){try{return data(c).rewardUntil(now);}catch(RuntimeException e){return 0;}}
    public static synchronized boolean reconcile(Context c,long now,boolean eligible,long boundary){CardData next=snapshot(c);boolean changed=next.reconcile(now,eligible,boundary);if(changed)commit(c,next);return next.rewardUntil(now)>0;}
    public static synchronized CycleSummary.Period summaryPending(Context c,long now){try{return data(c).summary(now).pending(now);}catch(RuntimeException e){return null;}}
    public static synchronized java.util.List<CycleSummary.Period> summariesPending(Context c,long now){return data(c).summary(now).allPending(now);}
    public static synchronized boolean summaryHolding(Context c,long now){CycleSummary.Period p=summaryPending(c,now);return p!=null&&p.holds(now);}
    public static synchronized void configureSummary(Context c,String mode,int days,String anchor){CardData next=snapshot(c);next.summary(System.currentTimeMillis()).configure(mode,days,anchor,System.currentTimeMillis());commit(c,next);}
    public static synchronized void configureSummaries(Context c,java.util.Set<String> selected,int days,String anchor){CardData next=snapshot(c);long now=System.currentTimeMillis();next.summary(now).configureAll(selected,days,anchor,now);commit(c,next);}
    public static synchronized boolean completeSummary(Context c,String key,String note){CardData next=snapshot(c);boolean changed=next.summary(System.currentTimeMillis()).complete(key,note,System.currentTimeMillis());if(changed)commit(c,next);return changed;}
}
