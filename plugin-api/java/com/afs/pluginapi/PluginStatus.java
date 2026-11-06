package com.afs.pluginapi;

/**
 * Protocol-1 status codes shared by host and plugins.
 *
 * These are deliberately small, fixed integers. Both sides reject unknown
 * status values instead of guessing.
 */
public final class PluginStatus {
    public static final int OK = 0;
    public static final int INVALID_TICKET = 1;
    public static final int BAD_CALLER = 2;
    public static final int INCOMPATIBLE = 3;
    public static final int DISABLED = 4;
    public static final int EXPIRED = 5;
    public static final int BUSY = 6;
    public static final int PERMISSION_REQUIRED = 7;
    public static final int REVOKED = 8;
    public static final int TIMEOUT = 9;
    public static final int UNAVAILABLE = 10;
    public static final int FAILED = 11;
    public static final int NO_GRANT = 12;

    /* HostUsageAccessActivity result values, returned as an Activity result. */
    public static final int ACCESS_GRANTED = 1;
    public static final int ACCESS_NOT_GRANTED = 2;
    public static final int ACCESS_REVOKED = 3;
    public static final int ACCESS_UNAVAILABLE = 4;

    private PluginStatus() {}

    public static String name(int status) {
        switch (status) {
            case OK: return "OK";
            case INVALID_TICKET: return "INVALID_TICKET";
            case BAD_CALLER: return "BAD_CALLER";
            case INCOMPATIBLE: return "INCOMPATIBLE";
            case DISABLED: return "DISABLED";
            case EXPIRED: return "EXPIRED";
            case BUSY: return "BUSY";
            case PERMISSION_REQUIRED: return "PERMISSION_REQUIRED";
            case REVOKED: return "REVOKED";
            case TIMEOUT: return "TIMEOUT";
            case UNAVAILABLE: return "UNAVAILABLE";
            case FAILED: return "FAILED";
            case NO_GRANT: return "NO_GRANT";
            default: return "UNKNOWN_" + status;
        }
    }
}
