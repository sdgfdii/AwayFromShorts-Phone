package com.afs.plugin.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Second compatible fixture used only for discovery tests. */
public final class FixtureActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Compatible fixture plugin");
        text.setTextSize(20);
        setContentView(text);
    }
}
