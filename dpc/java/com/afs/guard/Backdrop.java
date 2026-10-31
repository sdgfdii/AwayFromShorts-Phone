package com.afs.guard;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 打卡页的页面背景（亚克力材质）。
 *
 * 「亚克力」说白了就是三层叠在一起：
 *   1. 一张自定义图片（用户从相册挑）
 *   2. 图片被重度模糊 —— 磨砂，认不出原图是什么
 *   3. 上面盖一层半透明深色蒙版，卡片再叠一层半透明面
 *
 * 没有第 2 层就只是"贴了张图"，有��有第 3 层就太花 —— 亚克力的关键是**糊**。
 * 模糊用纯 Java 的 box blur（连做几遍逼近高斯），不引 RenderScript：
 * 那套在 Android 12+ 已经被移除，新系统上会直接崩。
 *
 * 图片一律复制进私有目录再读：ACTION_OPEN_DOCUMENT 给的 Uri 权限随时可能过期，
 * 存一份副本才不会过两天变白屏。
 */
final class Backdrop {

    private static final String SP = "afs_bg";
    private static final String K_SRC = "src";     // "" 无 / "default" 内置 / "custom" 自选
    static final String NONE = "";
    static final String DEFAULT = "default";
    static final String CUSTOM = "custom";

    private Backdrop() {
    }

    /* ==================== 状态 ==================== */

    static String src(Context c) {
        return prefs(c).getString(K_SRC, NONE);
    }

    static void setSrc(Context c, String v) {
        prefs(c).edit().putString(K_SRC, v).apply();
    }

    static boolean has(Context c) {
        return !NONE.equals(src(c));
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 自选图存在哪。 */
    static File file(Context c) {
        return new File(c.getFilesDir(), "backdrop.jpg");
    }

    /* ==================== 导入 ==================== */

    /**
     * 把用户挑的那张图复制进私有目录。
     *
     * @return null 表示成功，否则是给用户看的错误描述
     */
    static String importImage(Context c, Uri uri) {
        try {
            ContentResolver cr = c.getContentResolver();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = cr.openInputStream(uri);
            if (in == null) {
                return T.s("读不到这张图");
            }
            try {
                BitmapFactory.decodeStream(in, null, o);
            } finally {
                close(in);
            }
            int side = Math.max(o.outWidth, o.outHeight);
            if (side <= 0) {
                return T.s("这不是一张图片");
            }
            o = new BitmapFactory.Options();
            o.inSampleSize = sample(side, 2048);   // 存一份够用的，别把原图塞进私有目录
            in = cr.openInputStream(uri);
            if (in == null) {
                return T.s("读不到这张图");
            }
            Bitmap bmp;
            try {
                bmp = BitmapFactory.decodeStream(in, null, o);
            } finally {
                close(in);
            }
            if (bmp == null) {
                return T.s("解不开这张图");
            }
            OutputStream out = new FileOutputStream(file(c));
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
            } finally {
                close(out);
                bmp.recycle();
            }
            setSrc(c, CUSTOM);
            return null;
        } catch (Throwable t) {
            String m = t.getMessage();
            return m == null ? T.s("导入失败") : m;
        }
    }

    static void clear(Context c) {
        File f = file(c);
        if (f.exists()) {
            f.delete();
        }
        setSrc(c, NONE);
    }

    private static void close(java.io.Closeable x) {
        try {
            if (x != null) {
                x.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static int sample(int side, int max) {
        int s = 1;
        while (side / s > max) {
            s *= 2;
        }
        return s;
    }

    /* ==================== 渲染 ==================== */

    /**
     * 生成背景位图：铺满 w×h 的模糊图。
     *
     * @return null 表示没有背景（或解不开），调用方按"没有背景"处理
     */
    static Bitmap build(Context c, int w, int h) {
        String s = src(c);
        Bitmap raw = null;
        try {
            if (DEFAULT.equals(s)) {
                raw = defaultGradient(w, h);
            } else if (CUSTOM.equals(s)) {
                File f = file(c);
                if (!f.exists()) {
                    setSrc(c, NONE);
                    return null;
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample(Math.max(w, h), 1024);
                raw = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            }
        } catch (Throwable ignored) {
        }
        if (raw == null) {
            return null;
        }
        Bitmap scaled = cover(raw, w, h);
        if (scaled != raw) {
            raw.recycle();
        }
        // 磨砂：缩到 1/4 再糊，糊完放大回去 —— 肉眼看是一整片柔光，认不出原图
        Bitmap small = Bitmap.createScaledBitmap(scaled, Math.max(1, w / 4), Math.max(1, h / 4), true);
        if (small != scaled) {
            scaled.recycle();
        }
        Bitmap blurred = blur(small, 6, 3);
        if (blurred != small) {
            small.recycle();
        }
        Bitmap full = Bitmap.createScaledBitmap(blurred, w, h, true);
        if (full != blurred) {
            blurred.recycle();
        }
        return full;
    }

    /** 内置底：一束从左上透出来的冷光。程序画，xml 的 gradient 解不成位图。 */
    private static Bitmap defaultGradient(int w, int h) {
        if (w <= 0 || h <= 0) {
            return null;
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setShader(new android.graphics.RadialGradient(
                w * 0.32f, h * 0.20f, Math.max(w, h) * 0.9f,
                new int[]{0xFF2B4C9B, 0xFF16203F, 0xFF0B0C10},
                null, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return bmp;
    }

    /** 居中裁剪铺满（等价 ImageView 的 CENTER_CROP）。 */
    private static Bitmap cover(Bitmap src, int w, int h) {
        if (w <= 0 || h <= 0) {
            return src;
        }
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int nw = Math.max(1, Math.round(src.getWidth() * s));
        int nh = Math.max(1, Math.round(src.getHeight() * s));
        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        if (scaled == src) {
            return src;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        int left = (nw - w) / 2;
        int top = (nh - h) / 2;
        c.drawBitmap(scaled, left, top, null);
        scaled.recycle();
        return out;
    }

    /**
     * box blur 连做几遍，逼近高斯。
     *
     * 一遍 box 的边缘是折的，做三遍就够柔和了；纯 int 运算，没有 RenderScript 那么挑系统。
     */
    static Bitmap blur(Bitmap src, int radius, int passes) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0 || radius <= 0) {
            return src;
        }
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[px.length];
        for (int p = 0; p < passes; p++) {
            boxH(px, tmp, w, h, radius);
            boxV(tmp, px, w, h, radius);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static void boxH(int[] src, int[] dst, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[row + clamp(i, 0, w - 1)];
                sumA += (c >>> 24) & 0xFF;
                sumR += (c >> 16) & 0xFF;
                sumG += (c >> 8) & 0xFF;
                sumB += c & 0xFF;
            }
            int count = 2 * r + 1;
            for (int x = 0; x < w; x++) {
                dst[row + x] = (int) (((sumA / count) << 24) | ((sumR / count) << 16)
                        | ((sumG / count) << 8) | (sumB / count));
                int outc = src[row + clamp(x - r, 0, w - 1)];
                int inc = src[row + clamp(x + r + 1, 0, w - 1)];
                sumA += ((inc >>> 24) & 0xFF) - ((outc >>> 24) & 0xFF);
                sumR += ((inc >> 16) & 0xFF) - ((outc >> 16) & 0xFF);
                sumG += ((inc >> 8) & 0xFF) - ((outc >> 8) & 0xFF);
                sumB += (inc & 0xFF) - (outc & 0xFF);
            }
        }
    }

    private static void boxV(int[] src, int[] dst, int w, int h, int r) {
        for (int x = 0; x < w; x++) {
            long sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[clamp(i, 0, h - 1) * w + x];
                sumA += (c >>> 24) & 0xFF;
                sumR += (c >> 16) & 0xFF;
                sumG += (c >> 8) & 0xFF;
                sumB += c & 0xFF;
            }
            int count = 2 * r + 1;
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = (int) (((sumA / count) << 24) | ((sumR / count) << 16)
                        | ((sumG / count) << 8) | (sumB / count));
                int outc = src[clamp(y - r, 0, h - 1) * w + x];
                int inc = src[clamp(y + r + 1, 0, h - 1) * w + x];
                sumA += ((inc >>> 24) & 0xFF) - ((outc >>> 24) & 0xFF);
                sumR += ((inc >> 16) & 0xFF) - ((outc >> 16) & 0xFF);
                sumG += ((inc >> 8) & 0xFF) - ((outc >> 8) & 0xFF);
                sumB += (inc & 0xFF) - (outc & 0xFF);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
