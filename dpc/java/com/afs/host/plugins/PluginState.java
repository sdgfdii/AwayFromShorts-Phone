package com.afs.host.plugins;

/** Immutable persisted plugin state. Business stores are never stored here. */
public final class PluginState {
    public final String packageName;
    public final boolean enabled;
    public final boolean readGranted;
    public final boolean requestGranted;
    public final String confirmedCapabilities;
    public final String fingerprint;
    public final String signerSha256;
    public final int versionCode;
    public final long installGeneration;
    public final long enabledGeneration;
    public final long readGeneration;
    public final long requestGeneration;
    public final String failure;
    public final boolean needsConfirmation;

    public PluginState(String packageName, boolean enabled, boolean readGranted,
                       boolean requestGranted, String confirmedCapabilities, String fingerprint,
                       String signerSha256, int versionCode, long installGeneration,
                       long enabledGeneration, long readGeneration, long requestGeneration,
                       String failure, boolean needsConfirmation) {
        this.packageName = packageName == null ? "" : packageName;
        this.enabled = enabled;
        this.readGranted = readGranted;
        this.requestGranted = requestGranted;
        this.confirmedCapabilities = confirmedCapabilities == null ? "" : confirmedCapabilities;
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.signerSha256 = signerSha256 == null ? "" : signerSha256;
        this.versionCode = versionCode;
        this.installGeneration = installGeneration;
        this.enabledGeneration = enabledGeneration;
        this.readGeneration = readGeneration;
        this.requestGeneration = requestGeneration;
        this.failure = failure == null ? "" : failure;
        this.needsConfirmation = needsConfirmation;
    }

    public boolean readAllowed() { return enabled && readGranted; }
    public boolean requestAllowed() { return enabled && requestGranted; }
}
