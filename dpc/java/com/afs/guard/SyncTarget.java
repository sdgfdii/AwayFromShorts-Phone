package com.afs.guard;

import java.util.Locale;

/** Validated GitHub Gist destination. This object never contains a credential. */
final class SyncTarget {
    final String owner;
    final String gistId;

    private SyncTarget(String owner, String gistId) {
        this.owner = owner;
        this.gistId = gistId;
    }

    static SyncTarget create(String owner, String gistId) {
        String user = owner == null ? "" : owner.trim();
        String id = gistId == null ? "" : gistId.trim().toLowerCase(Locale.US);
        // GitHub account names are ASCII, up to 39 characters, and cannot
        // start/end with a hyphen or contain consecutive hyphens.
        if (!user.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
                || user.contains("--")) {
            throw new IllegalArgumentException(T.s("GitHub 用户名无效"));
        }
        if (!id.matches("[0-9a-f]{5,64}")) {
            throw new IllegalArgumentException(T.s("Gist ID 无效"));
        }
        return new SyncTarget(user, id);
    }

    String apiUrl() {
        return "https://api.github.com/gists/" + gistId;
    }

    String rawUrl(String filename) {
        if (filename == null || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(T.s("同步文件名无效"));
        }
        return "https://gist.githubusercontent.com/" + owner + "/" + gistId + "/raw/" + filename;
    }
}
