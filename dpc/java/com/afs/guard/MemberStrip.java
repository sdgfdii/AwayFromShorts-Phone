package com.afs.guard;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 额度卡里的成员图标行：按可用宽度自动换行，全部成员都摆出来。
 *
 * 每个图标是真正的子 View（不是画上去的），所以能单独长按、单独发起拖拽 ——
 * 把一只应用从一格拖到另一格，就是“迁移”。
 */
final class MemberStrip extends ViewGroup {
    private final int size;
    private final int gap;

    MemberStrip(Context c, int sizePx, int gapPx) {
        super(c);
        size = Math.max(1, sizePx);
        gap = Math.max(0, gapPx);
    }

    private int columns(int width) {
        if (width <= 0) return 1;
        return Math.max(1, (width + gap) / (size + gap));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? Math.max(size, 3 * (size + gap) - gap)
                : MeasureSpec.getSize(widthSpec);
        int perRow = columns(width);
        int count = getChildCount();
        int rows = count == 0 ? 0 : (count + perRow - 1) / perRow;
        int childSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
        for (int i = 0; i < count; i++) getChildAt(i).measure(childSpec, childSpec);
        setMeasuredDimension(width, rows == 0 ? 0 : rows * size + (rows - 1) * gap);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int perRow = columns(getWidth());
        for (int i = 0; i < getChildCount(); i++) {
            int row = i / perRow, column = i % perRow;
            int x = column * (size + gap), y = row * (size + gap);
            getChildAt(i).layout(x, y, x + size, y + size);
        }
    }

    @Override public ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new ViewGroup.LayoutParams(size, size);
    }
}
