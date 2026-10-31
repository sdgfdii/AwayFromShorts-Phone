package com.afs.guard;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 关键词拦截：读当前窗口的文字，在本地比关键词，命中就遮住并退出。
 *
 * 三条自我约束（都会影响误拦和耗电，必须守）：
 *   1. 只在「目标应用」里读屏 —— 其它应用的事件直接返回，不读内容。
 *   2. 节流：最快 800 毫秒看一次，且单次最多遍历 240 个节点 / 6000 字。
 *      短视频一屏几十个节点，认真遍历会明显拖慢滑动手感。
 *   3. 同一个应用 4 秒内只遮一次：连点下一条会连续触发事件，
 *      每次都弹遮罩就是刷屏，第二次起只按返回键把人送出去。
 *
 * 屏幕内容只在内存里比一遍，立刻丢掉 —— 落盘的只有「哪个应用、哪个词、什么时候」。
 */
public class KeywordGuardService extends AccessibilityService {

    private static final long SCAN_GAP_MS = 800L;
    private static final long BLOCK_GAP_MS = 4000L;
    private static final int MAX_NODES = 240;
    private static final int MAX_CHARS = 6000;

    /**
     * 连续读到几次「零个字」就认这个应用不给文字。
     * 取 2 而不是 3：微信这种应用一屏只发一个事件，阈值再高要开好几次才记上。
     * 误判代价很小（只多一句提示），而且一旦读到字立刻撤销。
     */
    private static final int SILENT_SCANS = 2;

    private long lastScanAt;
    private long lastBlockAt;
    private String lastBlockPkg = "";

    /** 每个应用连续读到「零个字」的次数，到 3 次就记为「这个应用不给文字」。 */
    private final java.util.HashMap<String, Integer> emptyScans = new java.util.HashMap<String, Integer>();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(Guard.TAG, "keyword service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;
            int type = event.getEventType();
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                return;
            }
            CharSequence name = event.getPackageName();
            String pkg = name == null ? "" : name.toString();
            if (pkg.isEmpty() || pkg.equals(getPackageName())) return;

            // 两套规则在同一个服务里跑：页面拦截按"应用+凭据"精确命中，关键词按词兜底。
            // 先按包名筛，别的应用连规则都不用比。
            List<PageRule.Rule> pageRules = PageRuleStore.active(this)
                    ? PageRule.forPackage(PageRuleStore.rules(this), pkg)
                    : Collections.<PageRule.Rule>emptyList();
            boolean keywordHere = KeywordStore.active(this)
                    && KeywordRule.inspect(pkg, KeywordStore.targets(this));
            if (pageRules.isEmpty() && !keywordHere) return;

            long now = System.currentTimeMillis();
            if (now - lastScanAt < SCAN_GAP_MS) return;
            lastScanAt = now;

            // 类名规则不用读屏，先判——视频号这类页面窗口类名一进就变。
            String className = event.getClassName() == null ? "" : event.getClassName().toString();
            PageRule.Rule pageHit = PageRule.hitByClass(pageRules, pkg, className);
            if (pageHit != null) {
                blockPage(pkg, pageHit, now);
                return;
            }

            // 要文字的规则（页面文字 / 关键词）才读一次树，两边共用这一份。
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            // 事件是上一个应用发的、窗口已经切走时，两者对不上：拿别人的字去比就是误判。
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg != null && !pkg.contentEquals(rootPkg)) return;
            List<String> blocks = new ArrayList<String>();
            collect(root, blocks, 0, new int[]{0});
            trackSilent(pkg, pageRules, blocks);

            pageHit = PageRule.hitByText(pageRules, pkg, blocks);
            if (pageHit != null) {
                blockPage(pkg, pageHit, now);
                return;
            }

            if (!keywordHere) return;
            String hit = KeywordRule.matchIn(blocks, KeywordStore.keywords(this));
            if (hit == null) return;
            block(pkg, hit, now);
        } catch (Throwable error) {
            // 无障碍回调里抛异常会被系统直接解绑服务，任何情况都必须吞掉
            Log.w(Guard.TAG, "keyword scan failed: " + error.getClass().getSimpleName());
        }
    }

    /**
     * 记下「这个应用一屏一个字都读不到」。
     *
     * 微信就是这么干的：application 窗口的 root 子节点数是 0（实测 2026-10-31），
     * 所以文字规则对它永远不会命中。连续 {@link #SILENT_SCANS} 次都空才记；
     * 一旦读到字就把标记撤掉 —— 应用改版、系统升级都可能让文字重新可读。
     */
    private void trackSilent(String pkg, List<PageRule.Rule> pageRules, List<String> blocks) {
        boolean needsText = false;
        for (PageRule.Rule rule : pageRules) {
            if (!rule.byClass()) {
                needsText = true;
                break;
            }
        }
        if (!needsText) return;
        if (!blocks.isEmpty()) {
            emptyScans.remove(pkg);
            PageRuleStore.clearSilent(this, pkg);
            return;
        }
        Integer old = emptyScans.get(pkg);
        int next = (old == null ? 0 : old) + 1;
        emptyScans.put(pkg, next);
        if (next >= SILENT_SCANS) PageRuleStore.markSilent(this, pkg);
    }

    /** 收集文字；节点数和字数都有上限，超了立刻停。 */
    private void collect(AccessibilityNodeInfo node, List<String> out, int depth, int[] counted) {
        if (node == null || depth > 12 || counted[0] >= MAX_NODES) return;
        counted[0]++;
        append(out, node.getText());
        append(out, node.getContentDescription());
        int children = node.getChildCount();
        for (int i = 0; i < children && counted[0] < MAX_NODES; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Throwable ignored) {
            }
            if (child != null) collect(child, out, depth + 1, counted);
        }
    }

    private void append(List<String> out, CharSequence text) {
        if (text == null) return;
        String value = text.toString().trim();
        if (value.isEmpty()) return;
        int total = 0;
        for (String item : out) total += item.length();
        if (total >= MAX_CHARS) return;
        if (value.length() > 400) value = value.substring(0, 400);
        out.add(value);
    }

    private void block(String pkg, String keyword, long now) {
        KeywordStore.recordHit(this, pkg, keyword, now);
        boolean storm = pkg.equals(lastBlockPkg) && now - lastBlockAt < BLOCK_GAP_MS;
        lastBlockPkg = pkg;
        lastBlockAt = now;
        if (storm) {
            performGlobalAction(GLOBAL_ACTION_BACK);      // 已经遮过：直接把这一条划走
            return;
        }
        Intent intent = new Intent(this, KeywordBlockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(KeywordBlockActivity.EXTRA_PACKAGE, pkg)
                .putExtra(KeywordBlockActivity.EXTRA_KEYWORD, keyword);
        try {
            startActivity(intent);
        } catch (Throwable error) {
            // 后台起 Activity 被系统拒了：至少把人送回桌面
            Log.w(Guard.TAG, "keyword mask failed: " + error.getClass().getSimpleName());
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    /** 页面规则命中：遮罩文案走另一套（说的是"这个页面"，不是"这个词"）。 */
    private void blockPage(String pkg, PageRule.Rule rule, long now) {
        PageRuleStore.recordHit(this, pkg, rule, now);
        boolean storm = pkg.equals(lastBlockPkg) && now - lastBlockAt < BLOCK_GAP_MS;
        lastBlockPkg = pkg;
        lastBlockAt = now;
        if (storm) {
            performGlobalAction(GLOBAL_ACTION_BACK);      // 已经在拦了：再退一层，别刷屏
            return;
        }
        Intent intent = new Intent(this, KeywordBlockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(KeywordBlockActivity.EXTRA_PACKAGE, pkg)
                .putExtra(KeywordBlockActivity.EXTRA_KEYWORD, rule.value)
                .putExtra(KeywordBlockActivity.EXTRA_PAGE, true);
        try {
            startActivity(intent);
        } catch (Throwable error) {
            Log.w(Guard.TAG, "page mask failed: " + error.getClass().getSimpleName());
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
