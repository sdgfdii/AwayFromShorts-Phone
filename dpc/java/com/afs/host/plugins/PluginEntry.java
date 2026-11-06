package com.afs.host.plugins;

import android.content.ComponentName;

/** One discovered plugin installation plus its current persisted state. */
public final class PluginEntry {
    public final PluginFingerprint fingerprint;
    public final PluginManifestParser.Manifest manifest;
    public final ComponentName service;
    public final PluginState state;
    public final String error;
    public final boolean compatible;

    public PluginEntry(PluginFingerprint fingerprint, PluginManifestParser.Manifest manifest,
                       ComponentName service, PluginState state, String error, boolean compatible) {
        this.fingerprint = fingerprint;
        this.manifest = manifest;
        this.service = service;
        this.state = state;
        this.error = error;
        this.compatible = compatible;
    }

    public boolean hasError() { return error != null && !error.isEmpty(); }

    public boolean usable() {
        return !hasError() && compatible && manifest != null && state != null && state.enabled;
    }

    public String pluginId() { return manifest == null ? "" : manifest.pluginId; }

    public String packageName() {
        return fingerprint == null ? "" : fingerprint.packageName;
    }

    public PluginManifestParser.Page page(String pageId) {
        if (manifest == null) return null;
        for (PluginManifestParser.Page page : manifest.pages) {
            if (page.id.equals(pageId)) return page;
        }
        return null;
    }
}
