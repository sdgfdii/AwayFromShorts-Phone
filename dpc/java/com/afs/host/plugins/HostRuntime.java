package com.afs.host.plugins;

import android.content.Context;

/** Process-wide plugin runtime. All leases/tickets are memory-only. */
public final class HostRuntime {
    private static HostRuntime instance;

    private final Context context;
    private final PluginRegistry registry;
    private final HostSessions sessions;

    private HostRuntime(Context context) {
        this.context = context.getApplicationContext();
        this.registry = new PluginRegistry(this.context);
        this.sessions = new HostSessions(this.context, registry);
    }

    public static synchronized HostRuntime get(Context context) {
        if (instance == null) instance = new HostRuntime(context);
        return instance;
    }

    public static synchronized HostRuntime current() {
        return instance;
    }

    public Context context() { return context; }

    public PluginRegistry registry() { return registry; }

    public HostSessions sessions() { return sessions; }
}
