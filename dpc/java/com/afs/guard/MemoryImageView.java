package com.afs.guard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;

/** Image-fit coordinates are normalized, so masks survive screen size/orientation changes. */
public final class MemoryImageView extends View {
    private Bitmap bitmap;
    private final String imagePath;
    private int generation,sourceWidth,sourceHeight;
    private boolean failed,needsLoad=true;
    private LoadTask task;
    private static final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.concurrent.ThreadPoolExecutor decoder=new java.util.concurrent.ThreadPoolExecutor(1,1,15,java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.ArrayBlockingQueue<Runnable>(4),r->{Thread t=new Thread(r,"afs-memory-image");t.setDaemon(true);return t;});
    private final Runnable retry=this::loadImage;
    private void cancelLoad(){generation++;removeCallbacks(retry);if(task!=null){task.canceled=true;decoder.remove(task);task=null;}}
    private void loadImage(){
        if(!isAttachedToWindow()||getWindowVisibility()!=VISIBLE||getWidth()<=0||getHeight()<=0)return;
        cancelLoad();failed=false;LoadTask next=new LoadTask(this,imagePath,generation,getWidth(),getHeight());task=next;
        try{decoder.execute(next);}catch(java.util.concurrent.RejectedExecutionException busy){task=null;postDelayed(retry,150);}
    }
    private static final class LoadTask implements Runnable {
        final java.lang.ref.WeakReference<MemoryImageView> owner;final String path;final int token,width,height;volatile boolean canceled;
        LoadTask(MemoryImageView view,String path,int token,int width,int height){owner=new java.lang.ref.WeakReference<>(view);this.path=path;this.token=token;this.width=width;this.height=height;}
        public void run(){
            Bitmap loaded=null;int originalWidth=0,originalHeight=0;
            try{if(canceled)return;BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,o);
                originalWidth=o.outWidth;originalHeight=o.outHeight;
                if(originalWidth>0&&originalHeight>0&&!canceled){float fit=Math.min(1f,Math.min(width/(float)originalWidth,height/(float)originalHeight));
                    o.inJustDecodeBounds=false;o.inSampleSize=1;while(originalWidth/(o.inSampleSize*2)>=originalWidth*fit&&originalHeight/(o.inSampleSize*2)>=originalHeight*fit)o.inSampleSize*=2;
                    loaded=BitmapFactory.decodeFile(path,o);}
            }catch(RuntimeException|OutOfMemoryError error){}
            final Bitmap result=loaded;final int w=originalWidth,h=originalHeight;
            main.post(()->{MemoryImageView view=owner.get();if(canceled||view==null||view.generation!=token||!view.isAttachedToWindow()){
                    if(result!=null)result.recycle();return;}
                view.task=null;view.needsLoad=false;view.bitmap=result;view.sourceWidth=w;view.sourceHeight=h;view.failed=result==null;view.invalidate();});
        }
    }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private JSONArray masks;
    private final boolean editing;
    private boolean revealed, dragging;
    private float startX, startY, endX, endY;
    public MemoryImageView(Context c, String name, JSONArray areas, boolean edit) {
        super(c); editing = edit; masks = areas==null?new JSONArray():areas;
        imagePath=MemoryStore.media(c,name).getAbsolutePath();
        setContentDescription(edit ? T.s("图片遮挡编辑器，拖动框选需要隐藏的标注") : T.s("知识图片，显示答案后揭开标注"));

    }
    public JSONArray masks() { return masks; }
    public void reveal() { revealed = true; invalidate(); }
    public void undo() {
        JSONArray keep = new JSONArray(); for (int i = 0; i < masks.length() - 1; i++) keep.put(masks.opt(i));
        masks = keep; invalidate();
    }
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bitmap == null) { paint.setColor(failed?0xFFFF7777:0xFF777777); paint.setTextSize(36); c.drawText(failed?T.s("图片无法读取，请重新选择"):T.s("正在读取图片…"), 8, 60, paint); return; }
        float scale = Math.min((float) getWidth() / sourceWidth, (float) getHeight() / sourceHeight);
        float w = sourceWidth * scale, h = sourceHeight * scale;
        bounds.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        paint.setAlpha(255); c.drawBitmap(bitmap, null, bounds, paint);
        if (revealed) return;
        paint.setColor(editing ? 0xDD2F6FED : 0xFF2F6FED);
        for (int i = 0; i < masks.length(); i++) {
            JSONArray r = masks.optJSONArray(i); if (r == null) continue;
            float x = bounds.left + (float) r.optDouble(0) * bounds.width();
            float y = bounds.top + (float) r.optDouble(1) * bounds.height();
            c.drawRect(x, y, x + (float) r.optDouble(2) * bounds.width(), y + (float) r.optDouble(3) * bounds.height(), paint);
        }
        if (dragging) c.drawRect(Math.min(startX, endX), Math.min(startY, endY), Math.max(startX, endX), Math.max(startY, endY), paint);
    }
    public boolean onTouchEvent(MotionEvent e) {
        if (!editing || bitmap == null) return false;
        float x = Math.max(bounds.left, Math.min(bounds.right, e.getX()));
        float y = Math.max(bounds.top, Math.min(bounds.bottom, e.getY()));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!bounds.contains(e.getX(), e.getY())) return false;
                startX = endX = x; startY = endY = y; dragging = true;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true); invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                endX = x; endY = y; invalidate(); return true;
            case MotionEvent.ACTION_UP:
                endX = x; endY = y;
                if (dragging && Math.abs(endX - startX) > 6 && Math.abs(endY - startY) > 6 && masks.length() < 100) {
                    JSONArray r = new JSONArray();
                    r.put((Object) Double.valueOf((Math.min(startX, endX) - bounds.left) / bounds.width()));
                    r.put((Object) Double.valueOf((Math.min(startY, endY) - bounds.top) / bounds.height()));
                    r.put((Object) Double.valueOf(Math.abs(endX - startX) / bounds.width()));
                    r.put((Object) Double.valueOf(Math.abs(endY - startY) / bounds.height())); masks.put(r);
                }
                dragging = false; if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false); invalidate(); performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false; if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false); invalidate(); return true;
            default: return true;
        }
    }
    public boolean performClick() { super.performClick(); return true; }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(bitmap==null)loadImage();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);needsLoad=true;dragging=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);loadImage();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE){if(bitmap==null||needsLoad)loadImage();}else cancelLoad();}
    @Override protected void onDetachedFromWindow(){cancelLoad();needsLoad=true;dragging=false;bitmap=null;bounds.setEmpty();super.onDetachedFromWindow();}
}
