package com.afs.guard;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 运行状态卡的完整执行明细。 */
public class StatusActivity extends ThemedActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui ui = new Ui(this);
        ScrollView scroll = new ScrollView(this);
        ui.page(scroll);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32));
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));

        TextView back = ui.sub(T.s("‹ 返回"));
        back.setTextSize(16);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        body.addView(back);
        TextView title = ui.title(T.s("运行状态"));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = ui.dp(20);
        body.addView(title, titleLp);

        android.content.SharedPreferences prefs = getSharedPreferences("afs_status", MODE_PRIVATE);
        long at = prefs.getLong("last_at", 0L);
        TextView time = ui.sub(at == 0L ? T.s("尚未执行") : T.s("最近一次应用 · ")
                + new SimpleDateFormat(T.s("M月d日 HH:mm:ss"), Locale.CHINA).format(new Date(at)));
        body.addView(time);

        LinearLayout card = ui.card();
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = ui.dp(18);
        body.addView(card, cardLp);
        TextView details = ui.sub(prefs.getString("last_result", "尚无运行明细"));
        details.setTextSize(13);
        details.setLineSpacing(ui.dp(5), 1f);
        card.addView(details);
        setContentView(scroll);
    }
}
