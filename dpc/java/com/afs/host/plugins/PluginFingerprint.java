package com.afs.host.plugins;

/** Immutable package identity used to invalidate stale sessions and grants. */
public final class PluginFingerprint {
    public final String packageName;
    public final int uid;
    public final String signerSha256;
    public final int versionCode;
    public final long lastUpdateTime;
    public final long firstInstallTime;
    public final String sharedUserId;

    public PluginFingerprint(String packageName, int uid, String signerSha256, int versionCode,
                             long lastUpdateTime, String sharedUserId) {
        this(packageName,uid,signerSha256,versionCode,lastUpdateTime,0L,sharedUserId);
    }
    public PluginFingerprint(String packageName,int uid,String signerSha256,int versionCode,
                             long lastUpdateTime,long firstInstallTime,String sharedUserId) {
        this.packageName = packageName == null ? "" : packageName;
        this.uid = uid;
        this.signerSha256 = signerSha256 == null ? "" : signerSha256;
        this.versionCode = versionCode;
        this.lastUpdateTime = lastUpdateTime;
        this.firstInstallTime = firstInstallTime;
        this.sharedUserId = sharedUserId == null ? "" : sharedUserId;
    }

    public String value() {
        String canonical=packageName+"|"+uid+"|"+signerSha256+"|"+versionCode+"|"+lastUpdateTime+"|"+firstInstallTime;
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            char[] out=new char[64];String hex="0123456789abcdef";
            for(int i=0;i<digest.length;i++){int b=digest[i]&255;out[2*i]=hex.charAt(b>>>4);out[2*i+1]=hex.charAt(b&15);}
            return new String(out);
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    public boolean sameInstall(PluginFingerprint other) {
        return other != null
                && packageName.equals(other.packageName)
                && uid == other.uid
                && signerSha256.equals(other.signerSha256)
                && versionCode == other.versionCode
                && lastUpdateTime == other.lastUpdateTime
                && firstInstallTime == other.firstInstallTime;
    }
}
