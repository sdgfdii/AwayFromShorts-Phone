package com.afs.plugin.fixture;
import android.app.Service;
import android.content.*;
import android.os.*;
import com.afs.pluginapi.*;
import java.util.concurrent.*;
/** Test-only cross-UID caller; absent from host and real plugin artifacts. */
public final class ProbeService extends Service {
    static volatile int accessResult=-100;
    private IHostStatsBridge bridge;
    private boolean bindDenied;
    private final CountDownLatch connected=new CountDownLatch(1);
    private final IPageClient anchor=new IPageClient.Stub(){};
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){bridge=IHostStatsBridge.Stub.asInterface(b);connected.countDown();}
        public void onServiceDisconnected(ComponentName n){bridge=null;}
    };
    public void onCreate(){super.onCreate();try{bindDenied=!bindService(new Intent(PluginProtocol.HOST_BRIDGE_ACTION).setPackage(PluginProtocol.HOST_PACKAGE),connection,BIND_AUTO_CREATE);}catch(SecurityException denied){bindDenied=true;}if(bindDenied)connected.countDown();}
    public void onDestroy(){try{unbindService(connection);}catch(RuntimeException ignored){}super.onDestroy();}
    private final Binder endpoint=new Binder(){
        protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(data.dataSize()>PluginProtocol.MAX_TRANSACTION)return false;
            try {
                reply.writeNoException();
                if(code==6){reply.writeInt(accessResult);return true;}
                if(code==7){accessResult=-100;reply.writeInt(1);return true;}
                if(code==9){reply.writeInt(bindDenied?1:0);return true;}
                if(code==5){
                    boolean denied=false;String detail="opened; uid="+android.os.Process.myUid();
                    String path=data.readString();if(path==null||path.length()>512)throw new IllegalArgumentException("bad test path");
                    java.io.File own=new java.io.File(getFilesDir(),"private-probe-"+System.nanoTime());byte[] token=java.util.UUID.randomUUID().toString().getBytes("UTF-8");boolean positive=false;
                    try{
                        try(java.io.FileOutputStream out=new java.io.FileOutputStream(own)){out.write(token);}
                        java.io.FileDescriptor fd=android.system.Os.open(own.getAbsolutePath(),android.system.OsConstants.O_RDONLY,0);
                        try{byte[] read=new byte[token.length];int n=android.system.Os.read(fd,read,0,read.length);positive=n==token.length&&java.util.Arrays.equals(token,read);}finally{android.system.Os.close(fd);}
                    }finally{own.delete();}
                    try {android.system.Os.close(android.system.Os.open(path,android.system.OsConstants.O_RDONLY,0));}
                    catch(android.system.ErrnoException expected){denied=expected.errno==android.system.OsConstants.EACCES||expected.errno==android.system.OsConstants.EPERM||expected.errno==android.system.OsConstants.ENOENT;detail="errno="+expected.errno+" uid="+android.os.Process.myUid();}
                    reply.writeInt(denied?1:0);reply.writeString(detail);reply.writeInt(positive?1:0);return true;
                }
                if(!connected.await(5,TimeUnit.SECONDS)||bridge==null)throw new IllegalStateException("bridge unavailable");
                if(code==8){
                    Parcel bad=Parcel.obtain();try{
                        bad.writeInterfaceToken("com.afs.pluginapi.IHostStatsBridge");bad.writeByteArray(new byte[70*1024]);
                        bridge.asBinder().transact(IBinder.FIRST_CALL_TRANSACTION,bad,null,IBinder.FLAG_ONEWAY);
                    }finally{bad.recycle();}
                    reply.writeInt(1);return true;
                }
                final CountDownLatch done=new CountDownLatch(1);
                if(code==1){
                    LaunchTicket ticket=LaunchTicket.CREATOR.createFromParcel(data);
                    final OpenResult[] result=new OpenResult[1];
                    bridge.openSession(ticket,anchor,new IOpenCallback.Stub(){public void onResult(OpenResult r){result[0]=r;done.countDown();}});
                    if(!done.await(8,TimeUnit.SECONDS))throw new IllegalStateException("open timeout");
                    result[0].writeToParcel(reply,0);return true;
                }
                PageLease lease=PageLease.CREATOR.createFromParcel(data);
                long id=data.readLong();
                if(code==10){
                    Parcel bad=Parcel.obtain();try{bad.writeInterfaceToken("com.afs.pluginapi.IHostStatsBridge");bad.writeInt(1);lease.writeToParcel(bad,0);bad.writeInt(0x12345678);bridge.asBinder().transact(IBinder.FIRST_CALL_TRANSACTION+3,bad,null,IBinder.FLAG_ONEWAY);}finally{bad.recycle();}
                    reply.writeInt(1);return true;
                }
                if(code==2){
                    final UsageResult[] result=new UsageResult[1];
                    bridge.loadCurrentUsageSummary(lease,id,new IUsageCallback.Stub(){public void onResult(UsageResult r){result[0]=r;done.countDown();}});
                    if(!done.await(8,TimeUnit.SECONDS))throw new IllegalStateException("load timeout");
                    result[0].writeToParcel(reply,0);return true;
                }
                if(code==3){bridge.cancelRequest(lease,id);reply.writeInt(1);return true;}
                if(code==4){bridge.closeSession(lease);reply.writeInt(1);return true;}
                return false;
            }catch(Exception e){throw new IllegalStateException(e);}
        }
    };
    public IBinder onBind(Intent intent){return endpoint;}
}
