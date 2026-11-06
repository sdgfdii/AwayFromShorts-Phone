package com.afs.pluginapi;

/** Fixed protocol-1 names and hard limits. No values are read from a plugin. */
public final class PluginProtocol {
    public static final int PROTOCOL = 1;

    public static final String HOST_PACKAGE = "com.afs.guard";
    public static final String HOST_BRIDGE_ACTION = "com.afs.guard.HOST_STATS_V1";
    public static final String PLUGIN_ACTION = "com.afs.guard.PLUGIN_V1";
    public static final String PERMISSION = "com.afs.guard.permission.PLUGIN_STATS_V1";
    public static final String META_MANIFEST = "com.afs.guard.plugin.manifest";

    public static final String EXTRA_TICKET = "com.afs.guard.extra.LAUNCH_TICKET";
    public static final String EXTRA_LEASE = "com.afs.guard.extra.PAGE_LEASE";
    public static final String EXTRA_LEASE_ID = "com.afs.guard.extra.PAGE_LEASE_ID";
    public static final String EXTRA_ACCESS_STATUS = "com.afs.guard.extra.ACCESS_STATUS";
    public static final String EXTRA_THEME_BG = "com.afs.guard.extra.THEME_BG";
    public static final String EXTRA_THEME_SURFACE = "com.afs.guard.extra.THEME_SURFACE";
    public static final String EXTRA_THEME_TEXT = "com.afs.guard.extra.THEME_TEXT";
    public static final String EXTRA_THEME_TEXT2 = "com.afs.guard.extra.THEME_TEXT2";
    public static final String EXTRA_THEME_TRACK = "com.afs.guard.extra.THEME_TRACK";
    public static final String EXTRA_THEME_ACCENT = "com.afs.guard.extra.THEME_ACCENT";
    public static final String EXTRA_THEME_RED = "com.afs.guard.extra.THEME_RED";
    public static final String EXTRA_THEME_GREEN = "com.afs.guard.extra.THEME_GREEN";
    public static final String EXTRA_DARK = "com.afs.guard.extra.DARK";
    public static final String EXTRA_LOCALE = "com.afs.guard.extra.LOCALE";
    public static final String EXTRA_FONT_SCALE = "com.afs.guard.extra.FONT_SCALE";
    public static final String EXTRA_CUSTOM_FONT_SCALE = "com.afs.guard.extra.CUSTOM_FONT_SCALE";
    public static final String EXTRA_FOLLOW_SYSTEM = "com.afs.guard.extra.FOLLOW_SYSTEM";
    public static final String EXTRA_LIGHT_COLORS = "com.afs.guard.extra.LIGHT_COLORS";
    public static final String EXTRA_DARK_COLORS = "com.afs.guard.extra.DARK_COLORS";
    public static final String EXTRA_REDUCE_MOTION = "com.afs.guard.extra.REDUCE_MOTION";

    public static final String SLOT_HISTORY_MORE = "HISTORY_MORE";
    public static final String CAP_READ = "usage.summary.read";
    public static final String CAP_REQUEST = "usage.access.request";

    public static final int MAX_DISCOVERY_BYTES = 8 * 1024;
    public static final int MAX_PAGES = 4;
    public static final int MAX_RANK = 40;
    public static final int MAX_TREND = 7;
    public static final int MAX_PC_TOP = 10;
    public static final int MAX_LABEL = 256;
    public static final int MAX_ID = 128;
    public static final int MAX_MESSAGE = 200;
    public static final int MAX_TRANSACTION = 64 * 1024;

    public static final long TICKET_TTL_MS = 60_000L;
    public static final long LEASE_TTL_MS = 15 * 60_000L;
    public static final long PREPARE_TIMEOUT_MS = 2_000L;
    public static final long QUERY_BUDGET_MS = 5_000L;
    public static final int WORKER_THREADS = 2;
    public static final int QUEUE_SLOTS = 4;

    private PluginProtocol() {}
}
