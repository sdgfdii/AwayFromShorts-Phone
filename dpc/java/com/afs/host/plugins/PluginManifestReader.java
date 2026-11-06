package com.afs.host.plugins;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Build;

import com.afs.pluginapi.PluginProtocol;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads a plugin's bounded XML declaration and maps it into the pure parser. */
public final class PluginManifestReader {

    public static final class Read {
        public final PluginManifestParser.Result result;
        public final ComponentName service;
        public final PluginFingerprint fingerprint;

        Read(PluginManifestParser.Result result, ComponentName service, PluginFingerprint fingerprint) {
            this.result = result;
            this.service = service;
            this.fingerprint = fingerprint;
        }
    }

    private PluginManifestReader() {}

    public static Read read(Context context, ServiceInfo serviceInfo) {
        if (serviceInfo == null || serviceInfo.packageName == null || serviceInfo.name == null) {
            return error("MISSING_SERVICE", null);
        }
        PackageManager pm = context.getPackageManager();
        ComponentName service = new ComponentName(serviceInfo.packageName, serviceInfo.name);
        PluginFingerprint fingerprint = PluginIdentity.read(context, serviceInfo.packageName);
        if (fingerprint == null) return error("MISSING_PACKAGE", null);
        if (!PluginIdentity.trusted(context, fingerprint.signerSha256)) {
            return error("UNTRUSTED_SIGNER", service);
        }

        PluginManifestParser.Input in = new PluginManifestParser.Input();
        in.packageName = serviceInfo.packageName;
        in.apkVersionCode = fingerprint.versionCode;
        in.serviceExported = serviceInfo.exported;
        in.servicePermission = serviceInfo.permission == null ? "" : serviceInfo.permission;
        in.sharedUserId = fingerprint.sharedUserId;

        PackageInfo info;
        try {
            info = pm.getPackageInfo(serviceInfo.packageName, PackageManager.GET_PERMISSIONS);
        } catch (PackageManager.NameNotFoundException e) {
            return error("MISSING_PACKAGE", service);
        }
        if (info.requestedPermissions != null) {
            for (String permission : info.requestedPermissions) in.requestedPermissions.add(permission);
        }

        int resource = serviceInfo.metaData == null
                ? 0 : serviceInfo.metaData.getInt(PluginProtocol.META_MANIFEST, 0);
        if (resource == 0) return error("MISSING_METADATA", service);

        byte[] raw;
        Resources resources;
        try {
            resources = pm.getResourcesForApplication(serviceInfo.applicationInfo);
            raw = readBounded(resources.openRawResource(resource), PluginProtocol.MAX_DISCOVERY_BYTES);
        } catch (Exception e) {
            return error("UNREADABLE_METADATA", service);
        }
        if (raw == null) {
            PluginManifestParser.Input tooLarge = new PluginManifestParser.Input();
            tooLarge.packageName = in.packageName;
            tooLarge.metadataBytes = PluginProtocol.MAX_DISCOVERY_BYTES + 1;
            return new Read(PluginManifestParser.parse(tooLarge), service, fingerprint);
        }
        in.metadataBytes = raw.length;

        try {
            // XML resources are compiled to binary XML; parse the resource,
            // not a UTF-8 string of the raw bytes. The 8KiB check above still
            // applies to the decompressed resource payload.
            parseXml(resources.getXml(resource), in, pm);
        } catch (Exception e) {
            return error("BAD_METADATA", service);
        }
        return new Read(PluginManifestParser.parse(in), service, fingerprint);
    }

    private static void parseXml(XmlPullParser parser, PluginManifestParser.Input in,
                                 PackageManager pm) throws Exception {
        int event = parser.getEventType();
        boolean rootSeen = false;
        Map<String, PluginManifestParser.ActivityMeta> activities = new HashMap<>();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if (!rootSeen && "plugin".equals(tag)) {
                    rootSeen = true;
                    in.id = attribute(parser, "id");
                    in.protocolMin = integer(attribute(parser, "protocolMin"), 0);
                    in.protocolMax = integer(attribute(parser, "protocolMax"), 0);
                    in.declaredVersionCode = integer(attribute(parser, "versionCode"), 0);
                } else if ("capability".equals(tag)) {
                    in.capabilities.add(attribute(parser, "name"));
                } else if ("page".equals(tag)) {
                    String activity = resolveActivity(in.packageName, attribute(parser, "activity"));
                    in.pageSpecs.add(attribute(parser, "slot") + "|"
                            + attribute(parser, "id") + "|" + activity + "|"
                            + attribute(parser, "order"));
                    if (!activities.containsKey(activity)) {
                        activities.put(activity, activityMeta(pm, in.packageName, activity));
                    }
                }
            }
            event = parser.next();
        }
        if (!rootSeen) throw new IllegalArgumentException("no plugin root");
        in.activities = activities;
    }

    private static PluginManifestParser.ActivityMeta activityMeta(PackageManager pm,
                                                                   String packageName,
                                                                   String activity) {
        try {
            ActivityInfo info = pm.getActivityInfo(new ComponentName(packageName, activity), 0);
            return new PluginManifestParser.ActivityMeta(info.exported, info.permission);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    private static String resolveActivity(String packageName, String name) {
        if (name == null) return "";
        if (name.startsWith(".")) return packageName + name;
        if (name.indexOf('.') < 0) return packageName + "." + name;
        return name;
    }

    private static String attribute(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static byte[] readBounded(InputStream input, int max) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0, read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > max) return null;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            try { input.close(); } catch (Exception ignored) { }
        }
    }

    private static Read error(String error, ComponentName service) {
        PluginManifestParser.Input in = new PluginManifestParser.Input();
        in.metadataBytes = 1;
        PluginManifestParser.Result result = new PluginManifestParser.Result(null, error, false);
        return new Read(result, service, null);
    }
}
