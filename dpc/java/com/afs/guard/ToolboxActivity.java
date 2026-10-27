package com.afs.guard;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 工具箱：首页三张工具卡，三个工具各自是这一页的独立页面（Intent extra "tool" 区分）。
 *
 * 全部离线：计时到点只在界面上写「时间到」，不通知、不响铃、不起前台服务；
 * 抽签选项存本机偏好；文本工具只做统计和整理。
 */
public final class ToolboxActivity extends ThemedActivity {
    private static final int[] PRESET_MINUTES = {1, 5, 10, 25, 45, 60};

    private Ui ui;
    private Handler handler;            // 计时/抽签动画共用；离开页面必须清空

    /* ---------- 计时器 ---------- */
    private boolean countdown = true;   // 默认倒计时 25:00
    private boolean running;
    private boolean finished;
    private long total = 25 * 60L;      // 倒计时总秒数
    private long elapsedMs;
    private long baseAt;                // 开始（或继续）那一刻的 elapsedRealtime
    private TextView clockView, statusView, primary;
    private TextView segCountdown, segStopwatch;
    private LinearLayout presetBox;
    private final List<TextView> presetChips = new ArrayList<TextView>();
    private final List<Long> presetSeconds = new ArrayList<Long>();

    /* ---------- 随机抽签 ---------- */
    private EditText options;
    private TextView resultView, countView;
    private boolean drawing;

    /* ---------- 文本工具 ---------- */
    private EditText textInput;
    private TextView statsView;

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running) return;
            elapsedMs = SystemClock.elapsedRealtime() - baseAt;
            if (countdown && elapsedMs >= total * 1000L) {
                elapsedMs = total * 1000L;
                running = false;
                finished = true;
                renderTimer();
                return;      // 到点就停：不再排下一次
            }
            renderTimer();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        handler = new Handler(Looper.getMainLooper());
        String tool = getIntent().getStringExtra("tool");
        if ("timer".equals(tool)) timerPage(state);
        else if ("draw".equals(tool)) drawPage(state);
        else if ("text".equals(tool)) textPage(state);
        else homePage();
    }

    /** 转屏/重建不丢用户输入；计时器恢复成「已暂停」，不在后台偷跑。 */
    @Override protected void onSaveInstanceState(Bundle out) {
        if (textInput != null) out.putString("text", textInput.getText().toString());
        if (options != null) out.putString("options", options.getText().toString());
        if (clockView != null) {
            out.putBoolean("countdown", countdown);
            out.putLong("total", total);
            out.putLong("elapsed", running ? SystemClock.elapsedRealtime() - baseAt : elapsedMs);
            out.putBoolean("finished", finished);
        }
        super.onSaveInstanceState(out);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);   // 计时、闪烁动画一律停掉
        if (clockView != null && running) {
            elapsedMs = SystemClock.elapsedRealtime() - baseAt;
            running = false;
            renderTimer();
        }
        drawing = false;
        if (options != null) Toolbox.saveItems(this, Toolbox.splitItems(options.getText().toString()));
    }

    @Override protected void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /* ==================== 首页 ==================== */

    private void homePage() {
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        setContentView(root);
        root.addView(header("工具箱", null, ui.text2(), null));
        TextView sub = text("离线小工具 · 计算 / 文档 / 图片", 12, ui.text2());
        root.addView(sub, subLp());
        // 三张卡在 140% 文字大小下也可能顶出屏幕，首页整体可滚
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout cards = vertical();
        cards.setPadding(0, 0, 0, ui.dp(28));
        scroll.addView(cards);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        toolCard(cards, "◷", Ui.TILE_RUST, "计时器", "倒计时 / 正计时，到点提示", "timer");
        toolCard(cards, "⚄", Ui.TILE_PURPLE, "随机抽签", "一行一个，抽一个", "draw");
        toolCard(cards, "Aa", Ui.TILE_TEAL, "文本工具", "字数 / 大小写 / 去重 / 排序", "text");
        toolCard(cards, "=", Ui.TILE_RUST, "科学计算器", "函数 / 角度与弧度 / 历史", "calc");
        toolCard(cards, "⇄", Ui.TILE_TEAL, "工科单位换算", "长度 / 压力 / 功率等十类", "units");
        toolCard(cards, "31", Ui.TILE_PURPLE, "日期计算", "日期差 / 加减天数", "dates");
        toolCard(cards, "PDF", Ui.TILE_RUST, "PDF 合并与提取", "合并文件 / 提取指定页", "pdf");
        toolCard(cards, "▧", Ui.TILE_TEAL, "图片处理", "压缩 / 缩放 / 格式转换", "image");
    }

    private LinearLayout.LayoutParams subLp() {
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(16));
        return slp;
    }

    private void toolCard(LinearLayout parent, String glyph, int tile, String title, String hint, final String tool) {
        LinearLayout card = ui.card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setContentDescription(title);
        TextView icon = ui.circle(glyph, tile, 44f, 20);
        ThemeStore.excludeFont(icon);
        card.addView(icon);
        LinearLayout words = vertical();
        TextView name = text(title, 17, ui.text());
        name.setTypeface(Typeface.DEFAULT_BOLD);
        words.addView(name);
        words.addView(text(hint, 12, ui.text2()));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, -2, 1);
        wlp.leftMargin = ui.dp(14);
        card.addView(words, wlp);
        card.addView(text("›", 24, ui.text2()));
        card.setOnClickListener(v -> startActivity(new Intent(this,
            ("timer".equals(tool)||"draw".equals(tool)||"text".equals(tool))?ToolboxActivity.class:ToolkitActivity.class).putExtra("tool", tool)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        parent.addView(card, lp);
    }

    /* ==================== 计时器 ==================== */

    private void timerPage(Bundle state) {
        if (state != null) {
            countdown = state.getBoolean("countdown", countdown);
            total = state.getLong("total", total);
            elapsedMs = state.getLong("elapsed", 0L);
            finished = state.getBoolean("finished", false);
        }
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        setContentView(root);
        root.addView(header("计时器", "重置", ui.accent(), this::resetTimer));

        LinearLayout content = vertical();
        content.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(28));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout clockWrap = vertical();
        clockWrap.setGravity(Gravity.CENTER);
        clockView = SwissTypography.role(text("25:00", 64, ui.text()), SwissTypography.Role.KEEP);
        clockView.setTypeface(Typeface.DEFAULT_BOLD);
        clockView.setGravity(Gravity.CENTER);
        clockView.setSingleLine(true);
        clockWrap.addView(clockView, new LinearLayout.LayoutParams(-1, -2));
        statusView = text("", 12, ui.text2());
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-1, -2);
        stlp.topMargin = ui.dp(10);
        clockWrap.addView(statusView, stlp);
        content.addView(clockWrap, new LinearLayout.LayoutParams(-1, 0, 1));

        content.addView(segmented());
        presetBox = vertical();
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = ui.dp(14);
        content.addView(presetBox, plp);
        buildPresets();

        primary = SwissTypography.role(text("开始", 16, ui.onAccent()), SwissTypography.Role.KEEP);
        primary.setTypeface(Typeface.DEFAULT_BOLD);
        primary.setGravity(Gravity.CENTER);
        primary.setBackground(ui.round(ui.accent(), 16));
        primary.setOnClickListener(v -> toggleTimer());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, ui.dp(52));
        blp.topMargin = ui.dp(18);
        content.addView(primary, blp);
        renderTimer();
    }

    private LinearLayout segmented() {
        LinearLayout box = row();
        box.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
        box.setBackground(ui.round(ui.surface(), 99));
        segCountdown = segment("倒计时");
        segStopwatch = segment("正计时");
        segCountdown.setOnClickListener(v -> setTimerMode(true));
        segStopwatch.setOnClickListener(v -> setTimerMode(false));
        box.addView(segCountdown, new LinearLayout.LayoutParams(0, ui.dp(44), 1f));
        box.addView(segStopwatch, new LinearLayout.LayoutParams(0, ui.dp(44), 1f));
        return box;
    }

    private TextView segment(String label) {
        TextView item = text(label, 15, ui.text2());
        item.setGravity(Gravity.CENTER);
        return item;
    }

    private void buildPresets() {
        for (int start = 0; start < PRESET_MINUTES.length; start += 3) {
            LinearLayout line = row();
            if (start > 0) {
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
                llp.topMargin = ui.dp(8);
                presetBox.addView(line, llp);
            } else {
                presetBox.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
            int end = Math.min(start + 3, PRESET_MINUTES.length);
            for (int index = start; index < end; index++) {
                final long seconds = PRESET_MINUTES[index] * 60L;
                TextView chip = ui.chip(PRESET_MINUTES[index] + " 分钟", ui.text(), ui.surface2());
                chip.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ui.dp(36), 1f);
                if (index > start) clp.leftMargin = ui.dp(8);
                chip.setOnClickListener(v -> pickPreset(seconds));
                line.addView(chip, clp);
                presetChips.add(chip);
                presetSeconds.add(seconds);
            }
        }
    }

    private void pickPreset(long seconds) {
        if (!countdown) setTimerMode(true);
        total = seconds;
        resetTimer();
    }

    private void setTimerMode(boolean countdownMode) {
        if (countdown == countdownMode) return;
        countdown = countdownMode;
        running = false;
        finished = false;
        elapsedMs = 0;
        handler.removeCallbacksAndMessages(null);
        if (presetBox != null) presetBox.setVisibility(countdown ? View.VISIBLE : View.GONE);
        renderTimer();
    }

    private void toggleTimer() {
        if (finished) { resetTimer(); return; }      // 时间到 → 重新开始
        if (running) { pauseTimer(); return; }
        startTimer();
    }

    private void startTimer() {
        running = true;
        finished = false;
        baseAt = SystemClock.elapsedRealtime() - elapsedMs;
        handler.removeCallbacks(tick);
        handler.post(tick);
        renderTimer();
    }

    private void pauseTimer() {
        if (running) elapsedMs = SystemClock.elapsedRealtime() - baseAt;
        running = false;
        handler.removeCallbacks(tick);
        renderTimer();
    }

    private void resetTimer() {
        running = false;
        finished = false;
        elapsedMs = 0;
        if (handler != null) handler.removeCallbacks(tick);
        renderTimer();
    }

    private void renderTimer() {
        if (clockView == null) return;
        long display = countdown ? Toolbox.remain(total, elapsedMs / 1000L) : elapsedMs / 1000L;
        String clockText = Toolbox.clock(display);
        clockView.setText(clockText);
        // 140% 文字大小下「1:00:00」会顶出内容宽度，带小时时收小一号
        clockView.setTextSize(clockText.length() > 5 ? 48 : 64);
        statusView.setText(statusText());
        primary.setText(finished ? "重新开始" : running ? "暂停" : elapsedMs > 0 ? "继续" : "开始");
        styleSegment(segCountdown, countdown);
        styleSegment(segStopwatch, !countdown);
        // 正计时模式不显示倒计时预设；转屏恢复也要按当前模式收起来
        if (presetBox != null) {
            presetBox.setVisibility(countdown ? View.VISIBLE : View.GONE);
        }
        for (int index = 0; index < presetChips.size(); index++) {
            boolean selected = countdown && total == presetSeconds.get(index);
            presetChips.get(index).setTextColor(selected ? ui.onAccent() : ui.text());
            presetChips.get(index).setBackground(ui.round(selected ? ui.accent() : ui.surface2(), 99));
        }
    }

    private String statusText() {
        if (finished) return "时间到";
        if (running) return countdown ? "倒计时中" : "正计时中";
        if (elapsedMs > 0) return "已暂停";
        return countdown ? "倒计时 · " + Toolbox.clock(total) : "正计时 · 从 0 开始";
    }

    private void styleSegment(TextView item, boolean selected) {
        if (item == null) return;
        item.setTextColor(selected ? ui.onAccent() : ui.text2());
        item.setBackground(selected ? ui.round(ui.accent(), 99) : null);
    }

    /* ==================== 随机抽签 ==================== */

    private void drawPage(Bundle state) {
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        setContentView(root);
        root.addView(header("随机抽签", "抽一个", ui.accent(), this::drawNow));

        LinearLayout content = vertical();
        content.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(28));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        options = new EditText(this);
        options.setHint("一行一个选项");
        options.setGravity(Gravity.TOP | Gravity.START);
        options.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        options.setTextColor(ui.text());
        options.setHintTextColor(ui.text3());
        options.setBackground(ui.round(ui.surface(), 16));
        options.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        options.setLineSpacing(ui.dp(4), 1f);
        options.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Toolbox.MAX_TEXT)});
        String initialText = null;
        if (state != null && state.containsKey("options")) {
            initialText = state.getString("options", "");
        }
        if (initialText == null) {
            StringBuilder initial = new StringBuilder();
            for (String item : Toolbox.loadItems(this)) {
                if (initial.length() > 0) initial.append('\n');
                initial.append(item);
            }
            initialText = initial.toString();
        }
        options.setText(initialText);
        options.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { updateDrawState(); }
            public void afterTextChanged(Editable e) { }
        });
        content.addView(options, new LinearLayout.LayoutParams(-1, ui.dp(180)));

        TextView drawButton = SwissTypography.role(text("抽一个", 16, ui.onAccent()), SwissTypography.Role.KEEP);
        drawButton.setTypeface(Typeface.DEFAULT_BOLD);
        drawButton.setGravity(Gravity.CENTER);
        drawButton.setBackground(ui.round(ui.accent(), 16));
        drawButton.setOnClickListener(v -> drawNow());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, ui.dp(48));
        dlp.topMargin = ui.dp(14);
        content.addView(drawButton, dlp);

        LinearLayout resultWrap = vertical();
        resultWrap.setGravity(Gravity.CENTER);
        resultView = SwissTypography.role(text("", 40, ui.accent()), SwissTypography.Role.KEEP);
        resultView.setTypeface(Typeface.DEFAULT_BOLD);
        resultView.setGravity(Gravity.CENTER);
        resultView.setMaxLines(3);
        resultView.setEllipsize(TextUtils.TruncateAt.END);
        resultWrap.addView(resultView, new LinearLayout.LayoutParams(-1, -2));
        countView = text("", 12, ui.text2());
        countView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = ui.dp(10);
        resultWrap.addView(countView, clp);
        content.addView(resultWrap, new LinearLayout.LayoutParams(-1, 0, 1));
        updateDrawState();
    }

    private void updateDrawState() {
        if (resultView == null || options == null) return;
        int size = Toolbox.splitItems(options.getText().toString()).size();
        countView.setText("共 " + size + " 个选项");
        if (size == 0 && !drawing) placeholder();
    }

    private void placeholder() {
        resultView.setText("先写几个选项");
        resultView.setTextSize(20);
        resultView.setTextColor(ui.text2());
    }

    private void showResult(String value) {
        resultView.setText(value);
        resultView.setTextSize(40);
        resultView.setTextColor(ui.accent());
    }

    /** 抽一个：先快速闪 3~5 次换字，最后定格；闪烁期间不重复触发。 */
    private void drawNow() {
        if (drawing || options == null) return;
        final List<String> items = Toolbox.splitItems(options.getText().toString());
        countView.setText("共 " + items.size() + " 个选项");
        if (items.isEmpty()) { placeholder(); return; }
        drawing = true;
        final Random random = new Random();
        flashStep(items, random, 3 + random.nextInt(3));
    }

    private void flashStep(final List<String> items, final Random random, final int remaining) {
        if (remaining <= 0) {
            int index = Toolbox.pickIndex(items.size(), random);
            if (index >= 0) showResult(items.get(index));
            drawing = false;
            return;
        }
        int index = Toolbox.pickIndex(items.size(), random);
        if (index >= 0) {
            resultView.setText(items.get(index));
            resultView.setTextSize(40);
            resultView.setTextColor(ui.text());
        }
        handler.postDelayed(() -> flashStep(items, random, remaining - 1), 60);
    }

    /* ==================== 文本工具 ==================== */

    private void textPage(Bundle state) {
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        setContentView(root);
        root.addView(header("文本工具", "清空", ui.text2(), this::clearText));

        LinearLayout content = vertical();
        content.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(24));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        textInput = new EditText(this);
        textInput.setHint("粘贴或输入文本");
        textInput.setGravity(Gravity.TOP | Gravity.START);
        textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        textInput.setTextColor(ui.text());
        textInput.setHintTextColor(ui.text3());
        textInput.setBackground(ui.round(ui.surface(), 16));
        textInput.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        textInput.setLineSpacing(ui.dp(4), 1f);
        textInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Toolbox.MAX_TEXT)});
        textInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { updateStats(); }
            public void afterTextChanged(Editable e) { }
        });
        content.addView(textInput, new LinearLayout.LayoutParams(-1, 0, 1));
        if (state != null) {
            textInput.setText(state.getString("text", ""));
        }

        statsView = text("0 字 · 0 行", 13, ui.text2());
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-1, -2);
        stlp.topMargin = ui.dp(10);
        content.addView(statsView, stlp);

        LinearLayout first = row();
        LinearLayout second = row();
        LinearLayout.LayoutParams firstLp = new LinearLayout.LayoutParams(-1, -2);
        firstLp.topMargin = ui.dp(12);
        content.addView(first, firstLp);
        LinearLayout.LayoutParams secondLp = new LinearLayout.LayoutParams(-1, -2);
        secondLp.topMargin = ui.dp(10);
        content.addView(second, secondLp);
        addToolButton(first, "转大写", 0, v -> applyText(Toolbox.upper(textInput.getText().toString())));
        addToolButton(first, "转小写", 1, v -> applyText(Toolbox.lower(textInput.getText().toString())));
        addToolButton(second, "按行去重", 0, v -> applyText(Toolbox.dedupeLines(textInput.getText().toString())));
        addToolButton(second, "按行排序", 1, v -> applyText(Toolbox.sortLines(textInput.getText().toString())));
        updateStats();
    }

    private void addToolButton(LinearLayout parent, String label, int column, View.OnClickListener onClick) {
        TextView button = text(label, 15, ui.text());
        button.setGravity(Gravity.CENTER);
        button.setBackground(ui.round(ui.surface2(), 14));
        button.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ui.dp(48), 1f);
        if (column > 0) lp.leftMargin = ui.dp(10);
        parent.addView(button, lp);
    }

    private void applyText(String next) {
        if (textInput == null) return;
        textInput.setText(next == null ? "" : next);
        textInput.setSelection(textInput.getText().length());
        updateStats();
    }

    private void clearText() {
        applyText("");
    }

    private void updateStats() {
        if (statsView == null || textInput == null) return;
        String value = textInput.getText().toString();
        statsView.setText(Toolbox.charCount(value) + " 字 · " + Toolbox.lineCount(value) + " 行");
    }

    /* ==================== 小工具 ==================== */

    private LinearLayout header(String title, String action, int actionColor, Runnable onAction) {
        LinearLayout bar = row();
        bar.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(12));
        View back = new ThemeGlyphView(this, "返回", ui.text());
        back.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        back.setBackground(ui.round(ui.surface(), 99));
        back.setContentDescription("返回");
        back.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        TextView heading = SwissTypography.role(text(title, 28, ui.text()), SwissTypography.Role.TITLE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, -2, 1);
        hlp.leftMargin = ui.dp(12);
        bar.addView(heading, hlp);
        if (action != null) {
            TextView button = SwissTypography.role(text(action, 15, actionColor), SwissTypography.Role.BODY);
            button.setGravity(Gravity.CENTER);
            button.setPadding(ui.dp(12), ui.dp(10), ui.dp(4), ui.dp(10));
            button.setOnClickListener(v -> onAction.run());
            bar.addView(button);
        }
        return bar;
    }

    private LinearLayout column() {
        LinearLayout box = vertical();
        ui.page(box);   // 高德风格下带淡蓝页眉渐变；经典风格就是纯底色
        return box;
    }

    private LinearLayout vertical() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
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
}
