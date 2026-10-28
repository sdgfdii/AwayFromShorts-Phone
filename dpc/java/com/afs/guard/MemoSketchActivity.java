package com.afs.guard;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Local vector draft with an explicitly bounded PNG export. Never writes a note itself. */
public final class MemoSketchActivity extends ThemedActivity {
    private Ui ui;
    private Draft draft=new Draft();
    private SketchView sketch;
    private TextView undo,colorButton,widthButton;
    private File draftFile;
    private int ink=Color.BLACK;
    private float width=6;
    private boolean saving;
    public static final int PAGE_WIDTH=1024,PAGE_HEIGHT=1400;

    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);ui=new Ui(this);
        String cache=saved==null?null:saved.getString("sketchDraft");
        draftFile=cache!=null&&cache.matches("memo-sketch-[a-f0-9-]{36}\\.bin")?new File(getCacheDir(),cache):freshDraft();
        if(saved!=null){ink=saved.getInt("sketchInk",Color.BLACK);width=saved.getFloat("sketchWidth",6);if(width<1||width>32)width=6;}
        if(draftFile.isFile()||new File(draftFile.getPath()+".bak").isFile())try(InputStream input=new android.util.AtomicFile(draftFile).openRead()){draft=Draft.read(input);}
        catch(Exception e){draftFile=freshDraft();toast("草稿未能恢复，原缓存已保留");}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(16));ui.page(root);setContentView(root);
        LinearLayout bar=row();root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        bar.addView(button("返回",this::cancel),new LinearLayout.LayoutParams(-2,ui.dp(48)));
        TextView title=label("涂鸦",22,ui.text());title.setGravity(Gravity.CENTER);bar.addView(title,new LinearLayout.LayoutParams(0,ui.dp(48),1));
        bar.addView(button("保存",this::save),new LinearLayout.LayoutParams(-2,ui.dp(48)));
        LinearLayout tools=row();root.addView(tools,new LinearLayout.LayoutParams(-1,ui.dp(52)));
        undo=button("撤销",()->{sketch.finishStroke();if(draft.undo())persist();refresh();});tools.addView(undo,new LinearLayout.LayoutParams(0,-1,1));
        tools.addView(button("清空",()->{sketch.finishStroke();if(draft.strokes.isEmpty())return;ThemeStore.dialog(this).setTitle("清空画布？")
                .setMessage("当前所有笔迹都会移除。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{draft.clear();persist();refresh();}).show();}),new LinearLayout.LayoutParams(0,-1,1));
        colorButton=button("颜色",this::pickColor);tools.addView(colorButton,new LinearLayout.LayoutParams(0,-1,1));
        widthButton=button("粗细",this::pickWidth);tools.addView(widthButton,new LinearLayout.LayoutParams(0,-1,1));
        sketch=new SketchView();LinearLayout.LayoutParams paper=new LinearLayout.LayoutParams(-1,0,1);paper.topMargin=ui.dp(8);root.addView(sketch,paper);
        TextView hint=label("手指绘制 · 白色画纸",12,ui.text2());hint.setGravity(Gravity.CENTER);root.addView(hint,new LinearLayout.LayoutParams(-1,ui.dp(36)));refresh();
    }
    private File freshDraft(){return new File(getCacheDir(),"memo-sketch-"+java.util.UUID.randomUUID()+".bin");}
    private LinearLayout row(){LinearLayout out=new LinearLayout(this);out.setGravity(Gravity.CENTER_VERTICAL);return out;}
    private TextView label(String text,float size,int color){TextView out=new TextView(this);out.setText(text);out.setTextSize(size);out.setTextColor(color);return out;}
    private TextView button(String text,Runnable action){TextView out=label(text,15,ui.accent());out.setGravity(Gravity.CENTER);out.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));out.setContentDescription(text);out.setOnClickListener(v->action.run());return out;}
    private void pickColor(){
        String[] labels={"黑色","红色","蓝色","绿色","紫色","橙色"};int[] colors={Color.BLACK,0xFFD32F2F,0xFF1565C0,0xFF2E7D32,0xFF7B1FA2,0xFFEF6C00};
        ThemeStore.dialog(this).setTitle("画笔颜色").setItems(labels,(d,position)->{sketch.finishStroke();ink=colors[position];refresh();}).setNegativeButton("取消",null).show();
    }
    private void pickWidth(){
        String[] labels={"细 · 3","常规 · 6","粗 · 12","特粗 · 24"};float[] values={3,6,12,24};
        ThemeStore.dialog(this).setTitle("画笔粗细").setItems(labels,(d,position)->{sketch.finishStroke();width=values[position];refresh();}).setNegativeButton("取消",null).show();
    }
    private void refresh(){if(sketch!=null)sketch.invalidate();if(undo!=null){undo.setEnabled(!draft.strokes.isEmpty());undo.setAlpha(undo.isEnabled()?1:.35f);}if(colorButton!=null)colorButton.setTextColor(ink==Color.BLACK?ui.text():ink);if(widthButton!=null)widthButton.setText("粗细 "+(int)width);}
    private void toast(String text){android.widget.Toast.makeText(this,text,android.widget.Toast.LENGTH_LONG).show();}
    private void persist(){
        android.util.AtomicFile file=new android.util.AtomicFile(draftFile);FileOutputStream output=null;
        try{output=file.startWrite();draft.write(output);file.finishWrite(output);}
        catch(Exception e){if(output!=null)file.failWrite(output);toast("草稿缓存失败，请尽快保存图片");}
    }
    @Override protected void onSaveInstanceState(Bundle out){
        if(sketch!=null)sketch.finishStroke();persist();out.putString("sketchDraft",draftFile.getName());out.putInt("sketchInk",ink);out.putFloat("sketchWidth",width);super.onSaveInstanceState(out);
    }
    @Override public void onBackPressed(){cancel();}
    private void cleanDraft(){if(draftFile!=null){draftFile.delete();new File(draftFile.getPath()+".bak").delete();new File(draftFile.getPath()+".new").delete();}}
    private void cancel(){if(saving)return;if(sketch!=null)sketch.finishStroke();
        if(draft.strokes.isEmpty()){cleanDraft();setResult(RESULT_CANCELED);finish();return;}
        ThemeStore.dialog(this).setTitle("放弃这张涂鸦？").setMessage("未保存的笔迹将被丢弃。")
                .setNegativeButton("继续绘制",null).setPositiveButton("放弃",(d,w)->{cleanDraft();setResult(RESULT_CANCELED);finish();}).show();
    }
    private void save(){
        if(saving)return;sketch.finishStroke();if(draft.strokes.isEmpty()){toast("先画一些内容再保存");return;}saving=true;
        File png=null;Bitmap bitmap=null;
        try{
            bitmap=Bitmap.createBitmap(PAGE_WIDTH,PAGE_HEIGHT,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);drawStrokes(canvas,draft.strokes);
            png=File.createTempFile("涂鸦-",".png",getCacheDir());try(OutputStream out=new FileOutputStream(png)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("PNG export failed");}
            String link=MemoFiles.add(this,Uri.fromFile(png));cleanDraft();setResult(RESULT_OK,new Intent().putExtra("link",link));finish();
        }catch(Exception e){toast("保存失败，请保留画布后重试");}
        finally{if(bitmap!=null)bitmap.recycle();if(png!=null)png.delete();saving=false;}
    }
    private static void drawStrokes(Canvas canvas,List<Stroke> strokes){for(Stroke stroke:strokes)drawStroke(canvas,stroke);}
    private static void drawStroke(Canvas canvas,Stroke stroke){
        if(stroke.points.isEmpty())return;Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(stroke.color);paint.setStrokeWidth(stroke.width);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        if(stroke.points.size()==2){paint.setStyle(Paint.Style.FILL);canvas.drawCircle(stroke.points.get(0),stroke.points.get(1),stroke.width/2,paint);return;}
        paint.setStyle(Paint.Style.STROKE);
        if(stroke.path==null||stroke.renderedPoints!=stroke.points.size()){
            stroke.path=new Path();stroke.path.moveTo(stroke.points.get(0),stroke.points.get(1));for(int i=2;i<stroke.points.size();i+=2)stroke.path.lineTo(stroke.points.get(i),stroke.points.get(i+1));stroke.renderedPoints=stroke.points.size();
        }canvas.drawPath(stroke.path,paint);
    }
    private final class SketchView extends View {
        private Stroke active;
        private int pointer=-1;
        SketchView(){super(MemoSketchActivity.this);setContentDescription("涂鸦画布");setBackground(ui.round(ui.surface(),12));}
        private float scale(){return Math.min(getWidth()/(float)PAGE_WIDTH,getHeight()/(float)PAGE_HEIGHT);}
        private float left(){return (getWidth()-PAGE_WIDTH*scale())/2;}
        private float top(){return (getHeight()-PAGE_HEIGHT*scale())/2;}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float scale=scale();if(scale<=0)return;canvas.save();canvas.translate(left(),top());canvas.scale(scale,scale);canvas.clipRect(0,0,PAGE_WIDTH,PAGE_HEIGHT);canvas.drawColor(Color.WHITE);drawStrokes(canvas,draft.strokes);if(active!=null)drawStroke(canvas,active);canvas.restore();}
        private void point(float x,float y){if(active==null)return;float scale=scale();if(scale<=0)return;float px=map(x,left(),scale,PAGE_WIDTH),py=map(y,top(),scale,PAGE_HEIGHT);
            if(active.points.size()/2>=Draft.MAX_STROKE_POINTS||draft.pointCount()+active.points.size()/2>=Draft.MAX_POINTS)return;
            if(active.points.size()>=2&&Math.hypot(active.points.get(active.points.size()-2)-px,active.points.get(active.points.size()-1)-py)<.5)return;
            active.points.add(px);active.points.add(py);invalidate();
        }
        void finishStroke(){if(active!=null){if(!active.points.isEmpty()){draft.add(active);persist();}active=null;pointer=-1;refresh();}}
        @Override public boolean onTouchEvent(MotionEvent event){
            int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN){
                float scale=scale();if(scale<=0||event.getX()<left()||event.getX()>left()+PAGE_WIDTH*scale||event.getY()<top()||event.getY()>top()+PAGE_HEIGHT*scale)return false;
                if(draft.strokes.size()>=Draft.MAX_STROKES||draft.pointCount()>=Draft.MAX_POINTS){toast("笔迹已达上限，请保存后继续新建");return true;}
                active=new Stroke(ink,width);pointer=event.getPointerId(0);point(event.getX(),event.getY());getParent().requestDisallowInterceptTouchEvent(true);return true;
            }
            if(active==null)return false;int index=event.findPointerIndex(pointer);
            if(action==MotionEvent.ACTION_MOVE&&index>=0){for(int h=0;h<event.getHistorySize();h++)point(event.getHistoricalX(index,h),event.getHistoricalY(index,h));point(event.getX(index),event.getY(index));return true;}
            if(action==MotionEvent.ACTION_UP){if(index>=0)point(event.getX(index),event.getY(index));finishStroke();getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;}
            if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_POINTER_UP&&event.getPointerId(event.getActionIndex())==pointer){finishStroke();getParent().requestDisallowInterceptTouchEvent(false);return true;}
            return true;
        }
        @Override public boolean performClick(){return super.performClick();}
    }
    public static float map(float coordinate,float offset,float scale,float max){if(!(scale>0)||Float.isNaN(coordinate))return 0;return Math.max(0,Math.min(max,(coordinate-offset)/scale));}
    public static final class Stroke {
        public final int color;public final float width;public final ArrayList<Float> points=new ArrayList<>();
        private Path path;private int renderedPoints;
        public Stroke(int color,float width){if(!Float.isFinite(width)||width<1||width>32)throw new IllegalArgumentException("Invalid pen width");this.color=color;this.width=width;}
    }
    public static final class Draft {
        public static final int MAX_STROKES=2000,MAX_POINTS=100000,MAX_STROKE_POINTS=10000;
        public final ArrayList<Stroke> strokes=new ArrayList<>();
        public int pointCount(){int count=0;for(Stroke stroke:strokes)count+=stroke.points.size()/2;return count;}
        public void add(Stroke stroke){
            if(stroke.points.isEmpty()||stroke.points.size()%2!=0||stroke.points.size()/2>MAX_STROKE_POINTS||strokes.size()>=MAX_STROKES||pointCount()+stroke.points.size()/2>MAX_POINTS)throw new IllegalArgumentException("Sketch capacity exceeded");
            for(int i=0;i<stroke.points.size();i+=2){float x=stroke.points.get(i),y=stroke.points.get(i+1);if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>PAGE_WIDTH||y<0||y>PAGE_HEIGHT)throw new IllegalArgumentException("Invalid coordinates");}
            strokes.add(stroke);
        }
        public boolean undo(){if(strokes.isEmpty())return false;strokes.remove(strokes.size()-1);return true;}
        public void clear(){strokes.clear();}
        public void write(OutputStream output)throws IOException{
            DataOutputStream out=new DataOutputStream(output);out.writeInt(0x41465331);out.writeInt(strokes.size());for(Stroke stroke:strokes){out.writeInt(stroke.color);out.writeFloat(stroke.width);out.writeInt(stroke.points.size()/2);for(Float point:stroke.points)out.writeFloat(point);}out.flush();
        }
        public static Draft read(InputStream input)throws IOException{
            DataInputStream in=new DataInputStream(input);if(in.readInt()!=0x41465331)throw new IOException("Invalid sketch draft");int count=in.readInt();if(count<0||count>MAX_STROKES)throw new IOException("Invalid stroke count");Draft draft=new Draft();
            for(int i=0;i<count;i++){
                int color=in.readInt();float width=in.readFloat();int points=in.readInt();if(!Float.isFinite(width)||width<1||width>32||points<1||points>MAX_STROKE_POINTS||draft.pointCount()+points>MAX_POINTS)throw new IOException("Invalid stroke");
                Stroke stroke=new Stroke(color,width);for(int p=0;p<points;p++){float x=in.readFloat(),y=in.readFloat();if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>PAGE_WIDTH||y<0||y>PAGE_HEIGHT)throw new IOException("Invalid coordinates");stroke.points.add(x);stroke.points.add(y);}draft.add(stroke);
            }if(in.read()!=-1)throw new IOException("Unexpected sketch data");return draft;
        }
    }
}
