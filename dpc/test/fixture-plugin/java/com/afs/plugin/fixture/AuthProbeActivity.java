package com.afs.plugin.fixture;
import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import com.afs.pluginapi.*;
/** Test APK only: result-style external caller for malformed input and revalidation tests. */
public final class AuthProbeActivity extends Activity {
 public void onCreate(Bundle state){super.onCreate(state);
  Intent i=new Intent().setComponent(new ComponentName(PluginProtocol.HOST_PACKAGE,PluginNavigation.HOST_USAGE_ACCESS_ACTIVITY));
  int variant=getIntent().getIntExtra("variant",1);
  if(variant==0)i.setData(new android.net.Uri.Builder().scheme("afs-plugin").authority("usage-access").appendPath(getIntent().getStringExtra("leaseId")).build());
  else if(variant==2)i.setData(new android.net.Uri.Builder().scheme("afs-plugin").authority("usage-access").appendPath(new String(new char[129]).replace('\0','x')).build());
  else {i.putExtra(PluginProtocol.EXTRA_LEASE_ID,new Bundle());i.putExtra(PluginProtocol.EXTRA_LEASE,new Bundle());}
  try{startActivityForResult(i,21);}catch(RuntimeException failure){ProbeService.accessResult=PluginStatus.ACCESS_UNAVAILABLE;finish();}
 }
 protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);
  ProbeService.accessResult=data==null?-200:data.getIntExtra(PluginProtocol.EXTRA_ACCESS_STATUS,-201);finish();
 }
}
