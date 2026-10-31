package com.afs.guard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded display work only. Guard decisions and enforcement never consume these results. */
final class MainDisplayCount {
    interface Counter { int count(Context app); }
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(()->{
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();
    },"afs-home-count"));
    private static Request pending,active;
    private static boolean running;
    private static final class Request {
        final WeakReference<MainActivity> owner;final Context app;final long token,sequence;final Counter counter;
        volatile boolean canceled;
        Request(MainActivity page,long token,long sequence,Counter counter){owner=new WeakReference<>(page);app=page.getApplicationContext();this.token=token;this.sequence=sequence;this.counter=counter;}
    }
    static synchronized void request(MainActivity page,long token,long sequence,Counter counter){
        pending=new Request(page,token,sequence,counter);
        if(!running){running=true;worker.execute(MainDisplayCount::drain);}
    }
    static synchronized void cancel(MainActivity page){
        if(pending!=null&&pending.owner.get()==page){pending.canceled=true;pending=null;}
        if(active!=null&&active.owner.get()==page)active.canceled=true;
    }
    private static void drain(){
        for(;;){
            final Request request;
            synchronized(MainDisplayCount.class){request=pending;pending=null;active=request;if(request==null){running=false;return;}}
            if(request.canceled||request.owner.get()==null)continue;
            Integer count=null;
            try{int result=request.counter.count(request.app);if(result>=0)count=result;}catch(RuntimeException error){/* Retain prior display; retry on the existing refresh schedule. */}
            final Integer result=count;
            main.post(()->{MainActivity page=request.owner.get();if(!request.canceled&&page!=null)page.displayCountResult(request.token,request.sequence,result);});
        }
    }
}
