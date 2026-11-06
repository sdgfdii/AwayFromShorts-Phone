package com.afs.host.plugins;

import com.afs.pluginapi.PluginProtocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Offline metadata validation: illegal declarations, version and permissions. */
public final class PluginManifestTest {
    private static int checks;

    private static PluginManifestParser.Input valid() {
        PluginManifestParser.Input in = new PluginManifestParser.Input();
        in.packageName = "com.afs.plugin.usagestats";
        in.apkVersionCode = 7;
        in.metadataBytes = 400;
        in.serviceExported = true;
        in.servicePermission = PluginProtocol.PERMISSION;
        in.sharedUserId = "";
        in.id = "usage-stats";
        in.protocolMin = 1;
        in.protocolMax = 1;
        in.declaredVersionCode = 7;
        in.capabilities.add(PluginProtocol.CAP_READ);
        in.capabilities.add(PluginProtocol.CAP_REQUEST);
        in.pageSpecs.add("HISTORY_MORE|screen|com.afs.plugin.usagestats.UsageStatsActivity|10");
        in.requestedPermissions.add(PluginProtocol.PERMISSION);
        Map<String, PluginManifestParser.ActivityMeta> activities = new HashMap<>();
        activities.put("com.afs.plugin.usagestats.UsageStatsActivity",
                new PluginManifestParser.ActivityMeta(true, PluginProtocol.PERMISSION));
        in.activities = activities;
        return in;
    }

    private static void ok(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static void error(PluginManifestParser.Input in, String expected) {
        PluginManifestParser.Result result = PluginManifestParser.parse(in);
        checks++;
        if (result.manifest != null || !expected.equals(result.error)) {
            throw new AssertionError("expected " + expected + " got " + result.error);
        }
    }

    public static void main(String[] args) {
        PluginManifestParser.Result good = PluginManifestParser.parse(valid());
        ok(good.manifest != null && good.manifest.pages.size() == 1, "valid declaration");
        ok(good.manifest.capabilities.size() == 2, "both capabilities");
        ok(good.manifest.pages.get(0).activity.endsWith("UsageStatsActivity"), "activity resolved");

        PluginManifestParser.Input in = valid();
        in.metadataBytes = 0;
        error(in, "MISSING_METADATA");

        in = valid();
        in.metadataBytes = PluginProtocol.MAX_DISCOVERY_BYTES + 1;
        error(in, "METADATA_TOO_LARGE");

        in = valid();
        in.declaredVersionCode = 8;
        error(in, "VERSION_MISMATCH");

        in = valid();
        in.protocolMin = 2;
        in.protocolMax = 3;
        PluginManifestParser.Result incompatible = PluginManifestParser.parse(in);
        ok(incompatible.manifest == null && incompatible.incompatible
                && "INCOMPATIBLE".equals(incompatible.error), "protocol incompatibility");

        in = valid();
        in.requestedPermissions.add("android.permission.INTERNET");
        error(in, "PERMISSION_EXCESS");

        in = valid();
        in.requestedPermissions.remove(PluginProtocol.PERMISSION);
        error(in, "MISSING_PERMISSION");

        in = valid();
        in.capabilities.add("usage.raw.events");
        error(in, "UNKNOWN_CAPABILITY");

        in = valid();
        in.capabilities.remove(PluginProtocol.CAP_READ);
        error(in, "MISSING_CAPABILITY");

        in = valid();
        in.pageSpecs.set(0, "HOME|screen|com.afs.plugin.usagestats.UsageStatsActivity|10");
        error(in, "UNKNOWN_SLOT");

        in = valid();
        in.pageSpecs.set(0, "HISTORY_MORE|screen|com.other.EvilActivity|10");
        error(in, "CROSS_PACKAGE_COMPONENT");

        in = valid();
        in.activities.put("com.afs.plugin.usagestats.UsageStatsActivity",
                new PluginManifestParser.ActivityMeta(false, PluginProtocol.PERMISSION));
        error(in, "ACTIVITY_NOT_EXPORTED");

        in = valid();
        in.activities.put("com.afs.plugin.usagestats.UsageStatsActivity",
                new PluginManifestParser.ActivityMeta(true, null));
        error(in, "ACTIVITY_PERMISSION");

        in = valid();
        in.sharedUserId = "com.afs.shared";
        error(in, "SHARED_UID");

        in = valid();
        in.serviceExported = false;
        error(in, "SERVICE_NOT_EXPORTED");

        in = valid();
        in.servicePermission = "android.permission.INTERNET";
        error(in, "SERVICE_PERMISSION");

        in = valid();
        in.pageSpecs.add("HISTORY_MORE|screen|com.afs.plugin.usagestats.UsageStatsActivity|11");
        error(in, "BAD_PAGE_ID");

        in = valid();
        in.pageSpecs = new ArrayList<>();
        error(in, "PAGE_COUNT");

        in = valid();
        in.id = "Usage-Stats";
        error(in, "BAD_PLUGIN_ID");

        System.out.println("Plugin manifest: " + checks + " checks passed");
    }
}
