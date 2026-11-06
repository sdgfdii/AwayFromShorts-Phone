package com.afs.pluginapi;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import java.security.MessageDigest;

/** Host package and signing-certificate verification used by a plugin. */
public final class HostTrust {

    private HostTrust() {}

    public static int uidOf(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /**
     * Test mode is an explicit build-time switch and still requires the fixed
     * host package. Production builds must match the pinned certificate.
     */
    public static boolean verify(Context context, String packageName, String expectedSha256,
                                 boolean testMode) {
        if (context == null || packageName == null) return false;
        if (!PluginProtocol.HOST_PACKAGE.equals(packageName)) return false;
        String actual = signerSha256(context, packageName);
        if (actual == null) return false;
        if (expectedSha256 != null && expectedSha256.equalsIgnoreCase(actual)) return true;
        return testMode;
    }

    public static String signerSha256(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo signing = info.signingInfo;
                if (signing == null) return null;
                Signature[] signers = signing.hasMultipleSigners()
                        ? signing.getApkContentsSigners()
                        : signing.getSigningCertificateHistory();
                if (signers == null || signers.length != 1) return null;
                return sha256(signers[0].toByteArray());
            }
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            Signature[] signers = info.signatures;
            if (signers == null || signers.length != 1) return null;
            return sha256(signers[0].toByteArray());
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
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
}
