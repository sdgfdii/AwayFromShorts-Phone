package com.afs.guard;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/**
 * 备忘录：一页两种形态。默认是速记列表（置顶优先、搜索、长按管理），
 * 带 id 进来就是编辑页。数据全在本机 {@link MemoStore}，不联网。
 */
public final class MemoActivity extends ThemedActivity {
    private static final int REQ_EDITOR = 41;   // 从编辑页回来要知道「是不是新建了一条」

    private Ui ui;

    /* ---------- 编辑页 ---------- */
    private MemoStore.Note entry;       // 打开编辑页时的原条目；null = 新建
    private String entryId;
    private boolean editor;
    private boolean savedOnce;          // save() 幂等标记
    private EditText titleInput, bodyInput;

    /* ---------- 列表页 ---------- */
    private List<MemoStore.Note> notes;
    private String query = "";
    private boolean searchOpen;
    private boolean bindingSearch;
    private boolean listedOnce;         // 首次 onResume 不重刷，避免打掉恢复的滚动位置
    private boolean resultRefreshed;    // 本轮返回已由 onActivityResult 刷过，onResume 别再刷一次
    private TextView countLabel;
    private EditText search;
    private ScrollView listScroll;
    private LinearLayout listBody;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        String id = getIntent().getStringExtra("id");
        boolean creating = getIntent().getBooleanExtra("new", false);
        MemoStore.Note found = id == null || id.isEmpty() ? null : find(id);
        entry = found == null ? null : shown(found);
        entryId = entry == null ? null : entry.id;
        editor = entry != null || creating;   // 带 new=true 也是编辑页（新建形态）
        if (editor) editorPage(state); else listPage(state);
    }

    @Override protected void onResume() {
        super.onResume();
        if (editor) return;
        // onActivityResult 已经刷过就不要再来一次：两次滚动 post 会互相覆盖（回顶被"保留旧位置"盖掉）
        if (listedOnce && !resultRefreshed) refreshList();
        resultRefreshed = false;
        listedOnce = true;
    }

    @Override public void onBackPressed() {
        if (editor) { saveAndFinish(); return; }   // 返回键也算一次保存
        // 搜索开着时先收起搜索，别让一次返回直接退出整个备忘录
        if (searchOpen) { closeSearch(); return; }
        super.onBackPressed();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_EDITOR || result != RESULT_OK || data == null) return;
        boolean created = data.getBooleanExtra("created", false);
        boolean changed = data.getBooleanExtra("changed", false);
        if (created) {
            // 搜索词还挂着时，刚新建的条目会被过滤掉，看起来像没保存：收起搜索
            if (searchOpen) closeSearch(); else refreshList(true);
            resultRefreshed = true;
        } else if (changed) {
            // 改过的条目会按新时间排到前面，回顶才看得见
            refreshList(true);
            resultRefreshed = true;
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (editor) {
            if (titleInput != null) out.putString("draftTitle", titleInput.getText().toString());
            if (bodyInput != null) out.putString("draftBody", bodyInput.getText().toString());
        } else {
            out.putString("query", query);
            out.putBoolean("search", searchOpen);
            if (listScroll != null) out.putInt("scroll", listScroll.getScrollY());
        }
        super.onSaveInstanceState(out);
    }

    /* ==================== 列表页 ==================== */

    private void listPage(Bundle state) {
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        ui.page(root);   // 高德风格下带淡蓝页眉渐变；经典风格就是纯底色
        setContentView(root);

        LinearLayout bar = row();
        bar.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        root.addView(bar);
        bar.addView(glyphButton("返回", "返回", () -> finish()), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        TextView heading = SwissTypography.role(text("备忘录", 28, ui.text()), SwissTypography.Role.TITLE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, -2, 1);
        hlp.leftMargin = ui.dp(12);
        bar.addView(heading, hlp);
        bar.addView(plusButton(), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
        slp.leftMargin = ui.dp(4);
        bar.addView(glyphButton("搜索", "搜索备忘录", this::toggleSearch), slp);

        countLabel = text("共 0 条", 12, ui.text2());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        root.addView(countLabel, clp);

        search = new EditText(this);
        search.setHint("搜索备忘录");
        search.setSingleLine();
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setTextColor(ui.text());
        search.setHintTextColor(ui.text3());
        search.setBackground(ui.round(ui.surface(), 16));
        search.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        search.setOnEditorActionListener((v, action, event) -> { hideKeyboard(search); return true; });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (bindingSearch) return;
                query = s == null ? "" : s.toString();
                refreshList(true);   // 换了搜索词，结果从头看
            }
            public void afterTextChanged(Editable e) { }
        });
        LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(-1, -2);
        sep.setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        root.addView(search, sep);
        search.setVisibility(View.GONE);

        listScroll = new ScrollView(this);
        listScroll.setClipToPadding(false);
        root.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        listBody = column();
        listBody.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(32));
        listScroll.addView(listBody);

        if (state != null) {
            String savedQuery = state.getString("query", "");
            searchOpen = state.getBoolean("search", false) || !savedQuery.isEmpty();
            if (searchOpen) {
                bindingSearch = true;
                search.setText(savedQuery);
                search.setVisibility(View.VISIBLE);
                bindingSearch = false;
            }
            query = savedQuery;
        }
        refreshList();
        if (state != null) {
            final int y = state.getInt("scroll", 0);
            listScroll.post(() -> listScroll.scrollTo(0, y));
        }
    }

    /** 重新读取并整体重建：列表量小，不做局部更新。默认保留阅读位置。 */
    private void refreshList() {
        refreshList(false);
    }

    /**
     * @param toTop 新建/改过/置顶/搜索后回顶（那些条目在列表前面）；单纯返回列表时保留位置，
     *              否则"点开看一眼再回来"会把用户读到一半的位置打掉。
     */
    private void refreshList(boolean toTop) {
        if (listBody == null || countLabel == null) return;
        int keep = listScroll == null ? 0 : listScroll.getScrollY();
        boolean searching = !query.trim().isEmpty();
        notes = searching ? MemoStore.search(this, query) : MemoStore.all(this);
        listBody.removeAllViews();
        // 空列表时不再单写一行「还没有备忘录」：空状态卡已经说了，重复一遍只是噪音
        countLabel.setText("共 " + notes.size() + " 条");
        countLabel.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
        if (notes.isEmpty()) emptyCard(searching);
        else for (MemoStore.Note note : notes) listBody.addView(card(note), ui.gap(10));
        // 新加的卡片立刻套上主题字体/行距，不等下一轮全局布局（否则会先闪一帧默认字体）
        ThemeStore.typography(listBody);
        // 重建后子布局还没测量：post 一帧再滚，删除让列表变短时不会被旧的 y 卡在半空。
        // 转屏恢复的滚动位置在 listPage 里随后再 post，最终以它为准。
        if (listScroll != null) {
            final int target = toTop ? 0 : keep;
            listScroll.post(() -> listScroll.scrollTo(0, target));
        }
    }

    private void emptyCard(boolean searching) {
        LinearLayout card = ui.card();
        card.setGravity(Gravity.CENTER);
        card.setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(28));
        TextView title = text(searching ? "没有找到备忘录" : "还没有备忘录", 17, ui.text());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));   // 不铺满会把居中变成左对齐
        TextView hint = text(searching ? "换个词试试，再点 ⌕ 收起搜索" : "点右上角 ＋ 写第一条", 12, ui.text2());
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = ui.dp(8);
        card.addView(hint, hlp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ui.dp(12);
        listBody.addView(card, lp);
    }

    private View card(MemoStore.Note raw) {
        final MemoStore.Note note = shown(raw);   // 兜一次 null：展示层不做空判断
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(ui.round(ui.surface(), 18));
        box.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));

        LinearLayout titleRow = row();
        TextView title = text(MemoStore.displayTitle(note), 17, ui.text());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (note.pinned) {
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
            chipLp.leftMargin = ui.dp(8);
            titleRow.addView(ui.chip("置顶", ui.accent(), ui.surface2()), chipLp);
        }
        box.addView(titleRow);

        String summary = MemoStore.excerpt(note.body);
        if (summary != null && !summary.isEmpty()) {
            TextView excerpt = text(summary, 13, ui.text2());
            excerpt.setMaxLines(2);
            excerpt.setEllipsize(TextUtils.TruncateAt.END);
            excerpt.setLineSpacing(ui.dp(3), 1f);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
            elp.topMargin = ui.dp(6);
            box.addView(excerpt, elp);
        }

        final String id = note.id;
        final boolean pinned = note.pinned;
        final String label = MemoStore.displayTitle(note);

        LinearLayout foot = row();
        TextView time = text(MemoStore.formatTime(note.updated, System.currentTimeMillis()), 11, ui.text3());
        foot.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        // 删除用图标而不是文字：卡片右下角一个垃圾桶，点它才弹确认（长按菜单里也保留删除）
        View trash = new ThemeGlyphView(this, "删除", ui.text3());
        trash.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        trash.setContentDescription("删除这条备忘录");
        trash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        trash.setOnClickListener(v -> confirmDelete(id, label, this::refreshList));
        foot.addView(trash, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = ui.dp(4);
        box.addView(foot, flp);

        box.setOnClickListener(v -> startActivityForResult(
                new Intent(this, MemoActivity.class).putExtra("id", id), REQ_EDITOR));
        box.setOnLongClickListener(v -> { menu(v, id, pinned, label); return true; });
        return box;
    }

    private void menu(View anchor, final String id, final boolean pinned, final String label) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(pinned ? "取消置顶" : "置顶").setOnMenuItemClickListener(item -> {
            MemoStore.setPinned(this, id, !pinned);
            refreshList(true);   // 置顶/取消置顶都会挪位置，回顶才看得到结果
            return true;
        });
        menu.getMenu().add("删除").setOnMenuItemClickListener(item -> {
            confirmDelete(id, label, this::refreshList);
            return true;
        });
        menu.show();
    }

    private void confirmDelete(final String id, String label, final Runnable after) {
        ThemeStore.dialog(this)
                .setTitle("删除这条备忘录？")
                .setMessage(label == null || label.isEmpty() ? "无标题" : label)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    MemoStore.remove(this, id);
                    if (after != null) after.run();
                })
                .show();
    }

    private void toggleSearch() {
        if (searchOpen) closeSearch(); else openSearch();
    }

    private void openSearch() {
        searchOpen = true;
        search.setVisibility(View.VISIBLE);
        search.requestFocus();
        search.post(() -> {
            search.setSelection(search.getText().length());
            showKeyboard(search);
        });
    }

    private void closeSearch() {
        if (!searchOpen) return;
        hideKeyboard(search);
        searchOpen = false;
        bindingSearch = true;
        search.setText("");
        bindingSearch = false;
        search.setVisibility(View.GONE);
        query = "";
        refreshList(true);   // 收起搜索回到全量列表，从顶部开始
    }

    /* ==================== 编辑页 ==================== */

    private void editorPage(Bundle state) {
        final boolean creating = entry == null;
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        ui.page(root);
        setContentView(root);

        LinearLayout bar = row();
        bar.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        root.addView(bar);
        bar.addView(glyphButton("返回", "返回", this::saveAndFinish), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        TextView heading = SwissTypography.role(text(creating ? "新建备忘录" : "编辑备忘录", 28, ui.text()), SwissTypography.Role.TITLE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, -2, 1);
        hlp.leftMargin = ui.dp(12);
        bar.addView(heading, hlp);
        // 已有条目：顶栏一个垃圾桶图标，和列表卡片上的删除图标是同一件事
        if (entry != null) {
            View trash = new ThemeGlyphView(this, "删除", ui.text3());
            trash.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
            trash.setContentDescription("删除这条备忘录");
            trash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            trash.setOnClickListener(v -> confirmDelete(entryId, currentLabel(), () -> {
                savedOnce = true;
                setResult(RESULT_OK, new Intent());
                finish();
            }));
            LinearLayout.LayoutParams trashLp = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
            trashLp.rightMargin = ui.dp(4);
            bar.addView(trash, trashLp);
        }
        TextView done = SwissTypography.role(text("完成", 15, ui.accent()), SwissTypography.Role.BODY);
        done.setGravity(Gravity.CENTER);
        done.setPadding(ui.dp(12), ui.dp(10), ui.dp(4), ui.dp(10));
        done.setContentDescription("保存");
        done.setOnClickListener(v -> saveAndFinish());
        bar.addView(done, new LinearLayout.LayoutParams(-2, -2));

        titleInput = new EditText(this);
        titleInput.setSingleLine(true);
        titleInput.setHint("标题");
        titleInput.setTextSize(20);
        titleInput.setTypeface(Typeface.DEFAULT_BOLD);
        titleInput.setTextColor(ui.text());
        titleInput.setHintTextColor(ui.text3());
        titleInput.setBackground(null);
        titleInput.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14));
        // 输入时就守住上限，别等到保存才悄悄截断（超大草稿还会撑爆转屏的 Bundle）
        titleInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MemoStore.MAX_TITLE)});
        SwissTypography.role(titleInput, SwissTypography.Role.KEEP);
        root.addView(titleInput, new LinearLayout.LayoutParams(-1, -2));

        bodyInput = new EditText(this);
        bodyInput.setHint("写点什么…");
        bodyInput.setTextSize(16);
        bodyInput.setGravity(Gravity.TOP | Gravity.START);
        bodyInput.setLineSpacing(ui.dp(6), 1f);
        bodyInput.setTextColor(ui.text());
        bodyInput.setHintTextColor(ui.text3());
        bodyInput.setBackground(null);
        bodyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        bodyInput.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(20));
        bodyInput.setVerticalScrollBarEnabled(true);
        bodyInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MemoStore.MAX_BODY)});
        SwissTypography.role(bodyInput, SwissTypography.Role.KEEP);
        root.addView(bodyInput, new LinearLayout.LayoutParams(-1, 0, 1));

        String title = entry == null ? "" : entry.title;
        String body = entry == null ? "" : entry.body;
        if (state != null) {
            title = state.getString("draftTitle", title);
            body = state.getString("draftBody", body);
        }
        titleInput.setText(title);
        bodyInput.setText(body);
        final int cursor = bodyInput.getText().length();
        bodyInput.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            bodyInput.requestFocus();
            bodyInput.setSelection(Math.min(cursor, bodyInput.getText().length()));
            if (creating) showKeyboard(bodyInput);
        });
    }

    /** 当前输入（未保存）的展示标题：删除确认里给用户看是哪条。 */
    private String currentLabel() {
        String title = titleInput == null ? "" : titleInput.getText().toString();
        String body = bodyInput == null ? "" : bodyInput.getText().toString();
        long updated = entry == null ? 0 : entry.updated;
        return MemoStore.displayTitle(new MemoStore.Note(entryId, title, body, updated, false));
    }

    /** 保存一次并关闭：点「完成」、顶栏返回、系统返回键都走这里，靠 savedOnce 保证幂等。 */
    private void saveAndFinish() {
        if (savedOnce) { finish(); return; }
        savedOnce = true;
        String title = titleInput == null ? "" : titleInput.getText().toString();
        String body = bodyInput == null ? "" : bodyInput.getText().toString();
        boolean created = false;
        boolean changed = false;
        // 全空（含全角空格）：新建不落盘，已有条目不覆盖原数据。
        if (!MemoStore.blank(title, body)) {
            if (entry == null) created = MemoStore.create(this, title, body) != null;
            else changed = MemoStore.update(this, entryId, title, body);
        }
        Intent result = new Intent();
        result.putExtra("created", created);
        result.putExtra("changed", changed);   // 没改内容 → false，列表别回顶、别当"刚保存"
        setResult(RESULT_OK, result);
        finish();
    }

    /* ==================== 小工具 ==================== */

    private MemoStore.Note find(String id) {
        for (MemoStore.Note note : MemoStore.all(this)) if (id.equals(note.id)) return note;
        return null;
    }

    private static MemoStore.Note shown(MemoStore.Note note) {
        return new MemoStore.Note(note.id, note.title == null ? "" : note.title,
                note.body == null ? "" : note.body, note.updated, note.pinned);
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(ui.bg());
        return box;
    }

    private LinearLayout row() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        return box;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    /** 顶栏的圆形图标按钮（返回、搜索）：线条图标不跟随用户的字体。 */
    private View glyphButton(String glyph, String description, Runnable action) {
        View view = new ThemeGlyphView(this, glyph, ui.text());
        view.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        view.setBackground(ui.round(ui.surface(), 99));
        view.setContentDescription(description);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    /** 新建：强调色圆里的「＋」，是这一页唯一的主动作。 */
    private View plusButton() {
        TextView view = new TextView(this);
        ThemeStore.excludeFont(view);
        view.setText("＋");
        view.setTextSize(22);
        view.setTextColor(ui.onAccent());
        view.setGravity(Gravity.CENTER);
        view.setBackground(ui.round(ui.accent(), 99));
        view.setContentDescription("新建备忘录");
        view.setOnClickListener(v -> startActivityForResult(
                new Intent(this, MemoActivity.class).putExtra("new", true), REQ_EDITOR));
        return view;
    }

    private void showKeyboard(View target) {
        target.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard(View target) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
    }
}
