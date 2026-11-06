package com.afs.host.plugins;
/** Pure install identity tests. Real session ordering is tested on Android. */
public final class PluginSessionTest {
    private static int checks;
    private static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args){
        PluginFingerprint base=new PluginFingerprint("com.afs.plugin.fixture",10001,"abc",1,200L,100L,"");
        PluginFingerprint same=new PluginFingerprint("com.afs.plugin.fixture",10001,"abc",1,200L,100L,"");
        PluginFingerprint reinstall=new PluginFingerprint("com.afs.plugin.fixture",10001,"abc",1,200L,150L,"");
        PluginFingerprint update=new PluginFingerprint("com.afs.plugin.fixture",10001,"abc",2,300L,100L,"");
        PluginFingerprint uid=new PluginFingerprint("com.afs.plugin.fixture",10002,"abc",1,200L,100L,"");
        ok(base.sameInstall(same)&&base.value().equals(same.value()),"same installed package identity stable");
        ok(!base.sameInstall(reinstall)&&!base.value().equals(reinstall.value()),"reinstall invalidates even when UID and version reused");
        ok(!base.sameInstall(update)&&!base.value().equals(update.value()),"upgrade invalidates old sessions");
        ok(base.firstInstallTime==update.firstInstallTime,"compatible update retains original install epoch");
        ok(!base.sameInstall(uid)&&!base.value().equals(uid.value()),"different UID invalidates identity");
        ok(base.value().matches("[0-9a-f]{64}"),"fingerprint fits IPC opaque-id bound");
        String longName="com."+new String(new char[120]).replace('\0','x');
        PluginFingerprint longPackage=new PluginFingerprint(longName,Integer.MAX_VALUE,new String(new char[64]).replace('\0','a'),Integer.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,"");
        ok(longPackage.value().length()==64,"long legal identity cannot be silently truncated in ticket/lease");
        System.out.println("Plugin identity: "+checks+" checks passed");
    }
}
