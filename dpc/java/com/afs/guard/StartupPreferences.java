package com.afs.guard;

import android.content.Context;

/** User preference for the in-app guard reveal shown after the platform launch window. */
final class StartupPreferences {
    private static final String KEY = "startup_animation";

    static boolean enabled(Context context) {
        return context.getSharedPreferences(Ui.SP, Context.MODE_PRIVATE)
                .getBoolean(KEY, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(Ui.SP, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY, enabled)
                .apply();
    }

    private StartupPreferences() {}
}
