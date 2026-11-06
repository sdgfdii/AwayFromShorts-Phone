package com.afs.host.plugins;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.afs.guard.T;
import com.afs.guard.ThemedActivity;
import com.afs.guard.Ui;
import com.afs.host.usage.HostUsageStats;
import com.afs.pluginapi.PageLease;
import com.afs.pluginapi.PluginProtocol;
import com.afs.pluginapi.PluginStatus;

/**
 * Host-owned grant page. A plugin may only reach it with a real
 * startActivityForResult; the caller is resolved from getCallingPackage()
 * and re-checked on every click, never trusted from the Intent.
 */
public final class HostUsageAccessActivity extends ThemedActivity {
    private static final int REQUEST_SYSTEM = 41;

    private Ui ui;
    private String leaseId;
    private String callerPackage;
    private boolean recheckOnClick;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        Intent intent = getIntent();
        callerPackage = getCallingPackage();
        if(callerPackage==null||callerPackage.isEmpty()){finishWith(PluginStatus.ACCESS_REVOKED);return;}
        try {
            android.net.Uri data=intent==null?null:intent.getData();
            if(data==null||data.toString().length()>256||!"afs-plugin".equals(data.getScheme())||!"usage-access".equals(data.getEncodedAuthority())
                    ||data.getEncodedQuery()!=null||data.getEncodedFragment()!=null||data.getPathSegments().size()!=1){finishWith(PluginStatus.ACCESS_REVOKED);return;}
            leaseId=data.getPathSegments().get(0); // no untrusted extras/Parcelable decoding
        } catch(RuntimeException malformed) {finishWith(PluginStatus.ACCESS_REVOKED);return;}
        if(leaseId==null||leaseId.isEmpty()||leaseId.length()>PluginProtocol.MAX_ID){finishWith(PluginStatus.ACCESS_REVOKED);return;}
        build();
        if (!validate()) {
            finishWith(PluginStatus.ACCESS_REVOKED);
        }
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ui.bg());
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(22), ui.dp(28), ui.dp(22), ui.dp(32));
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText(T.s("使用情况访问"));
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ui.text());
        body.addView(title);

        TextView explain = new TextView(this);
        explain.setText(T.s("统计插件只显示应用名称和时长。读取系统使用情况需要你在系统设置里亲手开启「使用情况访问」；设备所有者也不能代替这一步。"));
        explain.setTextSize(13);
        explain.setTextColor(ui.text2());
        explain.setLineSpacing(ui.dp(4), 1f);
        explain.setPadding(0, ui.dp(10), 0, ui.dp(14));
        body.addView(explain);

        TextView scope = new TextView(this);
        scope.setText(T.s("本页只验证已启用插件的临时会话；不会把系统设置或宿主数据交给插件。"));
        scope.setTextSize(12);
        scope.setTextColor(ui.text3());
        scope.setPadding(0, 0, 0, ui.dp(18));
        body.addView(scope);

        Button go = new Button(this);
        go.setText(T.s("进入系统设置"));
        go.setAllCaps(false);
        go.setTextColor(0xFFFFFFFF);
        go.setBackgroundColor(ui.accent());
        go.setLayoutParams(new LinearLayout.LayoutParams(-1, ui.dp(46)));
        go.setOnClickListener(v -> {
            if (!validate()) {
                finishWith(PluginStatus.ACCESS_REVOKED);
                return;
            }
            try {
                startActivityForResult(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), REQUEST_SYSTEM);
            } catch (RuntimeException unavailable) {
                finishWith(PluginStatus.ACCESS_UNAVAILABLE);
            }
        });
        body.addView(go);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(ui.text3());
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, ui.dp(14), 0, 0);
        body.addView(status);
    }

    private boolean validate() {
        if (callerPackage == null || callerPackage.isEmpty() || leaseId == null) return false;
        int uid;
        try {
            uid = getPackageManager().getPackageUid(callerPackage, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        HostSessions.Access access = HostRuntime.get(this).sessions()
                .validateAccess(callerPackage, leaseId, uid);
        return access.status == PluginStatus.OK;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SYSTEM) return;
        if (!validate()) {
            finishWith(PluginStatus.ACCESS_REVOKED);
            return;
        }
        finishWith(HostUsageStats.hasUsageAccess(this)
                ? PluginStatus.ACCESS_GRANTED : PluginStatus.ACCESS_NOT_GRANTED);
    }

    private void finishWith(int status) {
        Intent result = new Intent();
        result.putExtra(PluginProtocol.EXTRA_ACCESS_STATUS, status);
        setResult(RESULT_OK, result);
        finish();
    }
}
