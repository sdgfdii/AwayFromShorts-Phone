package com.afs.guard;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从已安装的可启动应用里勾选包名。
 *
 * 两种用法：
 *   默认（不传 extra）—— 选「要冻结的应用」，预选来自 {@link RuleStore}，
 *     保存时会把没装但已在列表里的包原样留住（默认三件套常缺两个）。
 *   传 {@code EXTRA_PRESELECT} —— 通用选择器：按传进来的列表预选，
 *     只把勾选结果原样返回，不碰 RuleStore。白名单、钥匙应用、断网列表都走这条。
 *
 * 界面这一版是重做的：原来是一个裸 ListView 加系统 CheckBox，和别的页面完全不像一套东西。
 * 现在有搜索、已选置顶、自绘圆形勾选、底部固定的保存条 —— 几百个应用里找一只不再靠瞎滚。
 */
public class AppPickerActivity extends ThemedActivity {

    /** ArrayList&lt;String&gt; 预选包名；传了就进「通用选择器」模式。 */
    public static final String EXTRA_PRESELECT = "preselect";
    /** 通用模式下的提示语（可选）。 */
    public static final String EXTRA_HINT = "hint";

    private boolean raw;
    private ArrayList<String> preset;

    private static final class Entry {
        String label;
        String pkg;
        Drawable icon;
        boolean checked;
        String lower;
    }

    private final List<Entry> items = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private Adapter adapter;
    private TextView countLine, emptyLine, saveButton;
    /** 已选但没安装的包（默认三件套里常常缺两个），保存时要原样留住 */
    private int hiddenKept;
    private String query = "";
    private Ui look;

    @Override
    protected void onResume() {
        super.onResume();
        if (look != null && look.dark != Ui.isDark(this)) recreate();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        look = new Ui(this);
        raw = getIntent() != null && getIntent().hasExtra(EXTRA_PRESELECT);
        preset = raw ? getIntent().getStringArrayListExtra(EXTRA_PRESELECT) : null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        look.page(root);

        root.addView(header());
        root.addView(searchBox());
        root.addView(listView(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(footer());

        setContentView(root);
        load();
    }

    /* ==================== 顶部 ==================== */

    private View header() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(look.dp(20), look.dp(20), look.dp(20), look.dp(4));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("‹  " + T.s("返回"));
        back.setTextSize(14);
        back.setTextColor(look.accent());
        back.setPadding(0, look.dp(6), look.dp(12), look.dp(6));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView all = new TextView(this);
        all.setText(T.s("全选"));
        all.setTextSize(13);
        all.setTextColor(look.accent());
        all.setPadding(look.dp(10), look.dp(6), look.dp(10), look.dp(6));
        all.setOnClickListener(v -> selectAll(true));
        bar.addView(all);

        TextView none = new TextView(this);
        none.setText(T.s("清空"));
        none.setTextSize(13);
        none.setTextColor(look.text2());
        none.setPadding(look.dp(10), look.dp(6), 0, look.dp(6));
        none.setOnClickListener(v -> selectAll(false));
        bar.addView(none);
        box.addView(bar);

        TextView title = new TextView(this);
        title.setText(raw ? T.s("选择应用") : T.s("选择要冻结的应用"));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(look.text());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = look.dp(10);
        box.addView(title, tlp);

        countLine = new TextView(this);
        countLine.setTextSize(12);
        countLine.setTextColor(look.text3());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = look.dp(6);
        box.addView(countLine, clp);
        return box;
    }

    private View searchBox() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(T.s("搜索应用名或包名"));
        input.setTextSize(14);
        input.setTextColor(look.text());
        input.setHintTextColor(look.text3());
        input.setBackground(look.round(look.surface(), 12f, look.line(), 1f));
        input.setPadding(look.dp(14), look.dp(10), look.dp(14), look.dp(10));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }

            public void afterTextChanged(Editable e) {
            }
        });
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(look.dp(20), look.dp(8), look.dp(20), look.dp(8));
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private View listView() {
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setBackground(look.round(look.surface(), 16f));
        list.setPadding(look.dp(12), look.dp(6), look.dp(12), look.dp(6));
        list.setVerticalScrollBarEnabled(true);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            Entry e = shown.get(pos);
            e.checked = !e.checked;
            // 勾完把已选的顶上去，选中的东西永远在上半屏
            Collections.sort(items, order());
            applyFilter();
        });
        return list;
    }

    private View footer() {
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setPadding(look.dp(20), look.dp(8), look.dp(20), look.dp(16));

        emptyLine = new TextView(this);
        emptyLine.setTextSize(11);
        emptyLine.setTextColor(look.text3());
        emptyLine.setPadding(look.dp(4), 0, look.dp(4), look.dp(8));
        foot.addView(emptyLine);

        saveButton = new TextView(this);
        saveButton.setText(T.s("保存"));
        saveButton.setTextSize(16);
        saveButton.setTypeface(Typeface.DEFAULT_BOLD);
        saveButton.setGravity(Gravity.CENTER);
        saveButton.setTextColor(look.onAccent());
        saveButton.setBackground(look.round(look.accent(), look.button(16)));
        saveButton.setOnClickListener(v -> save());
        foot.addView(saveButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, look.dp(52)));
        return foot;
    }

    /* ==================== 数据 ==================== */

    private void load() {
        new Thread(() -> {
            final List<Entry> found = new ArrayList<>();
            try {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
                Set<String> seen = new HashSet<>();
                Set<String> picked = raw
                        ? new HashSet<String>(preset == null ? new ArrayList<String>() : preset)
                        : RuleStore.packages(AppPickerActivity.this);
                for (ResolveInfo ri : ris) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                    Entry e = new Entry();
                    e.pkg = pkg;
                    e.label = String.valueOf(ri.loadLabel(pm));
                    e.lower = (e.label + " " + pkg).toLowerCase(Locale.ROOT);
                    try {
                        e.icon = ri.loadIcon(pm);
                    } catch (Throwable ignored) {
                    }
                    e.checked = picked.contains(pkg);
                    found.add(e);
                }
            } catch (Throwable ignored) {
            }
            Collections.sort(found, order());
            runOnUiThread(() -> {
                items.clear();
                items.addAll(found);
                Set<String> names = new HashSet<>();
                for (Entry e : found) names.add(e.pkg);
                int hidden = 0;
                if (!raw) {
                    for (String p : RuleStore.packages(AppPickerActivity.this)) {
                        if (!names.contains(p)) hidden++;
                    }
                }
                hiddenKept = hidden;
                applyFilter();
            });
        }, "afs-app-list").start();
    }

    /** 已选的排前面，其余按名称；这样刚勾过的东西不会滚出视野。 */
    private Comparator<Entry> order() {
        final Collator co = Collator.getInstance(Locale.CHINA);
        return (a, b) -> {
            if (a.checked != b.checked) return a.checked ? -1 : 1;
            return co.compare(a.label, b.label);
        };
    }

    private void applyFilter() {
        shown.clear();
        for (Entry e : items) {
            if (query.isEmpty() || e.lower.contains(query)) shown.add(e);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateCounts();
    }

    private void updateCounts() {
        int picked = 0;
        for (Entry e : items) if (e.checked) picked++;
        int total = items.size();
        String custom = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_HINT);
        if (countLine != null) {
            countLine.setText(T.s("已选 ") + (picked + hiddenKept) + T.s(" 个 · 共 ") + total + T.s(" 个应用"));
        }
        if (saveButton != null) {
            saveButton.setText(picked + hiddenKept > 0
                    ? T.s("保存（已选 ") + (picked + hiddenKept) + T.s(" 个）")
                    : T.s("保存"));
        }
        if (emptyLine != null) {
            if (custom != null && !custom.isEmpty()) {
                emptyLine.setText(custom);
            } else if (shown.isEmpty()) {
                emptyLine.setText(T.s("没有匹配的应用"));
            } else if (!raw) {
                emptyLine.setText(T.s("屏蔽时段内这些应用会被系统冻结，点不开"));
            } else {
                emptyLine.setText(T.s("选中的应用会按这一项自己的规则执行"));
            }
        }
    }

    private void selectAll(boolean on) {
        for (Entry e : shown) e.checked = on;
        Collections.sort(items, order());
        applyFilter();
    }

    private void save() {
        Set<String> out = new HashSet<>();
        if (!raw) {
            Set<String> installable = new HashSet<>();
            for (Entry e : items) installable.add(e.pkg);
            for (String p : RuleStore.packages(this)) {
                if (!installable.contains(p)) out.add(p);
            }
        }
        for (Entry e : items) if (e.checked) out.add(e.pkg);
        // 由 MainActivity 决定"哪些是新增、哪些是移除"——
        // 移除要按每个应用自己的锁定状态判，不能在这儿一刀切
        Intent data = new Intent();
        data.putStringArrayListExtra("picked", new ArrayList<String>(out));
        setResult(RESULT_OK, data);
        finish();
    }

    /* ==================== 行 ==================== */

    private final class Adapter extends BaseAdapter {
        public int getCount() {
            return shown.size();
        }

        public Object getItem(int i) {
            return shown.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            TextView name, pkg, tick;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
                icon = (ImageView) row.findViewById(101);
                name = (TextView) row.findViewById(102);
                pkg = (TextView) row.findViewById(103);
                tick = (TextView) row.findViewById(104);
            } else {
                row = new LinearLayout(AppPickerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(look.dp(8), look.dp(9), look.dp(8), look.dp(9));

                icon = new ImageView(AppPickerActivity.this);
                icon.setId(101);
                row.addView(icon, new LinearLayout.LayoutParams(look.dp(42), look.dp(42)));

                LinearLayout middle = new LinearLayout(AppPickerActivity.this);
                middle.setOrientation(LinearLayout.VERTICAL);
                middle.setPadding(look.dp(13), 0, look.dp(10), 0);

                name = new TextView(AppPickerActivity.this);
                name.setId(102);
                name.setTextSize(15);
                name.setTextColor(look.text());
                name.setSingleLine(false);
                name.setEllipsize(null);
                name.setMaxLines(2);
                middle.addView(name);

                pkg = new TextView(AppPickerActivity.this);
                pkg.setId(103);
                pkg.setTextSize(10);
                pkg.setTextColor(look.text3());
                pkg.setSingleLine(true);
                pkg.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                pkg.setPadding(0, look.dp(2), 0, 0);
                middle.addView(pkg);

                row.addView(middle, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                tick = new TextView(AppPickerActivity.this);
                tick.setId(104);
                tick.setTextSize(13);
                tick.setGravity(Gravity.CENTER);
                row.addView(tick, new LinearLayout.LayoutParams(look.dp(26), look.dp(26)));
            }

            Entry e = shown.get(pos);
            name.setText(e.label);
            pkg.setText(e.pkg);
            icon.setImageDrawable(e.icon);
            if (e.checked) {
                tick.setText("✓");
                tick.setTextColor(look.onAccent());
                tick.setTypeface(Typeface.DEFAULT_BOLD);
                tick.setBackground(look.round(look.accent(), 99f));
            } else {
                tick.setText("");
                tick.setBackground(look.round(0x00000000, 99f, look.line(), 1.2f));
            }
            return row;
        }
    }
}
