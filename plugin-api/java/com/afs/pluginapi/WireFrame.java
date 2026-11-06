package com.afs.pluginapi;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/** Protocol-1 framing check before a generated AIDL stub can invoke business code.
 * Old Android-compatible AIDL does not generate enforceNoDataAvail(). No newer
 * Android API or generator patch is required: inspect bounded arguments, then
 * restore the cursor for the generated decoder. This does not invoke callbacks.
 */
public final class WireFrame {
    private WireFrame() {}
    private static <T> void typed(Parcel p,Parcelable.Creator<T> creator) {
        if(ParcelSafe.readCount(p,1,"parcel presence")==1)creator.createFromParcel(p);
    }
    public static boolean valid(String name,int code,Parcel data) {
        if(code<IBinder.FIRST_CALL_TRANSACTION||code>IBinder.LAST_CALL_TRANSACTION)return true;
        if(data==null||data.dataSize()>PluginProtocol.MAX_TRANSACTION||data.hasFileDescriptors())return false;
        int position=data.dataPosition();
        try {
            data.enforceInterface(name);
            int method=code-IBinder.FIRST_CALL_TRANSACTION;
            if(name.equals("com.afs.pluginapi.IHostStatsBridge")) {
                if(method==0){typed(data,LaunchTicket.CREATOR);ParcelSafe.readBinder(data);ParcelSafe.readBinder(data);}
                else if(method==1){typed(data,PageLease.CREATOR);ParcelSafe.readLong(data);ParcelSafe.readBinder(data);}
                else if(method==2){typed(data,PageLease.CREATOR);ParcelSafe.readLong(data);}
                else if(method==3){typed(data,PageLease.CREATOR);}
                else return false;
            } else if(name.equals("com.afs.pluginapi.IPluginEndpoint")) {
                if(method==0){typed(data,PageLaunch.CREATOR);ParcelSafe.readBinder(data);}
                else if(method==1){ParcelSafe.read(data,PluginProtocol.MAX_ID,"lease id");ParcelSafe.readInt(data);}
                else return false;
            } else if(method!=0)return false;
            else if(name.equals("com.afs.pluginapi.IOpenCallback"))typed(data,OpenResult.CREATOR);
            else if(name.equals("com.afs.pluginapi.IUsageCallback"))typed(data,UsageResult.CREATOR);
            else if(name.equals("com.afs.pluginapi.IPrepareCallback"))typed(data,PrepareResult.CREATOR);
            else return false;
            return data.dataAvail()==0;
        } catch(RuntimeException malformed) {return false;}
        finally {data.setDataPosition(position);}
    }
}
