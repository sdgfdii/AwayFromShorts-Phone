package com.afs.host.plugins;

import com.afs.pluginapi.PluginProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure metadata validation. Android reads the XML resource and maps it into
 * {@link Input}; this class performs every semantic and size check without
 * touching Android classes, so it is covered by offline tests.
 */
public final class PluginManifestParser {

    public static final class Page {
        public final String id;
        public final String slot;
        public final String activity;
        public final int order;

        public Page(String id, String slot, String activity, int order) {
            this.id = id;
            this.slot = slot;
            this.activity = activity;
            this.order = order;
        }
    }

    public static final class ActivityMeta {
        public final boolean exported;
        public final String permission;

        public ActivityMeta(boolean exported, String permission) {
            this.exported = exported;
            this.permission = permission;
        }
    }

    public static final class Input {
        public String packageName = "";
        public int apkVersionCode;
        public int metadataBytes;
        public boolean serviceExported;
        public String servicePermission = "";
        public String sharedUserId = "";
        public String id = "";
        public int protocolMin;
        public int protocolMax;
        public int declaredVersionCode;
        public List<String> capabilities = new ArrayList<>();
        public List<String> pageSpecs = new ArrayList<>();
        public List<String> requestedPermissions = new ArrayList<>();
        public Map<String, ActivityMeta> activities = Collections.emptyMap();
    }

    public static final class Manifest {
        public final String packageName;
        public final String pluginId;
        public final int protocolMin;
        public final int protocolMax;
        public final int versionCode;
        public final List<String> capabilities;
        public final List<Page> pages;

        Manifest(String packageName, String pluginId, int protocolMin, int protocolMax,
                 int versionCode, List<String> capabilities, List<Page> pages) {
            this.packageName = packageName;
            this.pluginId = pluginId;
            this.protocolMin = protocolMin;
            this.protocolMax = protocolMax;
            this.versionCode = versionCode;
            this.capabilities = Collections.unmodifiableList(capabilities);
            this.pages = Collections.unmodifiableList(pages);
        }
    }

    public static final class Result {
        public final Manifest manifest;
        public final String error;
        public final boolean incompatible;

        Result(Manifest manifest, String error, boolean incompatible) {
            this.manifest = manifest;
            this.error = error;
            this.incompatible = incompatible;
        }
    }

    private static final Set<String> KNOWN_CAPABILITIES = new HashSet<>();
    static {
        KNOWN_CAPABILITIES.add(PluginProtocol.CAP_READ);
        KNOWN_CAPABILITIES.add(PluginProtocol.CAP_REQUEST);
    }

    private PluginManifestParser() {}

    public static Result parse(Input in) {
        if (in == null) return fail("BAD_METADATA", false);
        if (in.metadataBytes <= 0) return fail("MISSING_METADATA", false);
        if (in.metadataBytes > PluginProtocol.MAX_DISCOVERY_BYTES) return fail("METADATA_TOO_LARGE", false);
        if (!in.serviceExported) return fail("SERVICE_NOT_EXPORTED", false);
        if (!PluginProtocol.PERMISSION.equals(in.servicePermission)) return fail("SERVICE_PERMISSION", false);
        if (in.sharedUserId != null && !in.sharedUserId.isEmpty()) return fail("SHARED_UID", false);
        if (!validId(in.id)) return fail("BAD_PLUGIN_ID", false);
        if (in.protocolMin <= 0 || in.protocolMax < in.protocolMin) return fail("BAD_PROTOCOL", false);
        if (PluginProtocol.PROTOCOL < in.protocolMin || PluginProtocol.PROTOCOL > in.protocolMax) {
            return fail("INCOMPATIBLE", true);
        }
        if (in.declaredVersionCode <= 0 || in.declaredVersionCode != in.apkVersionCode) {
            return fail("VERSION_MISMATCH", false);
        }

        Set<String> permissions = new HashSet<>();
        for (String permission : in.requestedPermissions) {
            if (permission == null) continue;
            if (!PluginProtocol.PERMISSION.equals(permission)) return fail("PERMISSION_EXCESS", false);
            permissions.add(permission);
        }
        if (!permissions.contains(PluginProtocol.PERMISSION)) return fail("MISSING_PERMISSION", false);

        ArrayList<String> caps = new ArrayList<>();
        for (String capability : in.capabilities) {
            if (capability == null || !KNOWN_CAPABILITIES.contains(capability)) {
                return fail("UNKNOWN_CAPABILITY", false);
            }
            if (caps.contains(capability)) return fail("DUPLICATE_CAPABILITY", false);
            caps.add(capability);
        }
        if (caps.isEmpty() || !caps.contains(PluginProtocol.CAP_READ)) return fail("MISSING_CAPABILITY", false);

        if (in.pageSpecs.size() < 1 || in.pageSpecs.size() > PluginProtocol.MAX_PAGES) {
            return fail("PAGE_COUNT", false);
        }
        ArrayList<Page> pages = new ArrayList<>();
        Set<String> pageIds = new HashSet<>();
        for (String spec : in.pageSpecs) {
            String[] parts = spec == null ? new String[0] : spec.split("\\|", -1);
            if (parts.length != 4) return fail("BAD_PAGE", false);
            String slot = parts[0], pageId = parts[1], component = parts[2];
            int order;
            try { order = Integer.parseInt(parts[3]); }
            catch (NumberFormatException e) { return fail("BAD_PAGE_ORDER", false); }
            if (!PluginProtocol.SLOT_HISTORY_MORE.equals(slot)) return fail("UNKNOWN_SLOT", false);
            if (!validId(pageId) || pageIds.contains(pageId)) return fail("BAD_PAGE_ID", false);
            if (order < 0 || order > 1000) return fail("BAD_PAGE_ORDER", false);
            String packageName = in.packageName;
            if (!component.startsWith(packageName + ".")) return fail("CROSS_PACKAGE_COMPONENT", false);
            ActivityMeta meta = in.activities.get(component);
            if (meta == null) return fail("MISSING_ACTIVITY", false);
            if (!meta.exported) return fail("ACTIVITY_NOT_EXPORTED", false);
            if (!PluginProtocol.PERMISSION.equals(meta.permission)) return fail("ACTIVITY_PERMISSION", false);
            pageIds.add(pageId);
            pages.add(new Page(pageId, slot, component, order));
        }

        return new Result(new Manifest(in.packageName, in.id, in.protocolMin, in.protocolMax,
                in.declaredVersionCode, caps, pages), null, false);
    }

    private static boolean validId(String value) {
        if (value == null || value.length() == 0 || value.length() > 64) return false;
        String lower = value.toLowerCase(Locale.US);
        if (!lower.equals(value)) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-';
            if (!ok) return false;
            if (i == 0 && !(ch >= 'a' && ch <= 'z')) return false;
        }
        return true;
    }

    private static Result fail(String error, boolean incompatible) {
        return new Result(null, error, incompatible);
    }
}
