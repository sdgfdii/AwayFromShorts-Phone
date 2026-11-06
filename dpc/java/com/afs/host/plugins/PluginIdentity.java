package com.afs.host.plugins;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import com.afs.guard.BuildConfig;

import java.security.MessageDigest;

/** Package identity, signer pinning and install generation inputs. */
public final class PluginIdentity {

    private PluginIdentity() {}

    public static PluginFingerprint read(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            int flags = PackageManager.GET_PERMISSIONS;
            flags |= Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo info = pm.getPackageInfo(packageName, flags);
            String signer = signerSha256(pm, info);
            if (signer == null) return null;
            int uid = info.applicationInfo == null ? -1 : info.applicationInfo.uid;
            return new PluginFingerprint(packageName, uid, signer, info.versionCode,
                    info.lastUpdateTime, info.firstInstallTime, info.sharedUserId == null ? "" : info.sharedUserId);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    public static String signerSha256(PackageManager pm, PackageInfo info) {
        Signature[] signers;
        if (Build.VERSION.SDK_INT >= 28) {
            SigningInfo signing = info.signingInfo;
            if (signing == null) return null;
            signers = signing.hasMultipleSigners()
                    ? signing.getApkContentsSigners()
                    : signing.getSigningCertificateHistory();
        } else {
            signers = info.signatures;
        }
        if (signers == null || signers.length != 1) return null;
        return sha256(signers[0].toByteArray());
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(bytes);
            StringBuilder text = new StringBuilder(out.length * 2);
            for (byte b : out) text.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return text.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Production trusts only the pinned release certificate. Test mode adds
     * the host's own current certificate, and only when explicitly built with
     * AFS_PLUGIN_TEST_MODE=1. It never trusts a plugin's self-reported value.
     */
    public static boolean trusted(Context context, String signer) {
        String pinned = BuildConfig.PLUGIN_TRUST_SHA256;
        if (pinned != null && pinned.equalsIgnoreCase(signer)) return true;
        if (!BuildConfig.PLUGIN_TEST_MODE) return false;
        String self = hostSigner(context);
        return self != null && self.equalsIgnoreCase(signer);
    }

    public static String hostSigner(Context context) {
        PluginFingerprint own = read(context, context.getPackageName());
        return own == null ? null : own.signerSha256;
    }
}
