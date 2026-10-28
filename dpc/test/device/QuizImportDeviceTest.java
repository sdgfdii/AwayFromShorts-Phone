package com.afs.guard;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Real device parser and preview smoke; cancel every preview, preserving the user's question bank. */
public class QuizImportDeviceTest extends Instrumentation {
    private int checks;private QuizActivity activity;private final List<File> cleanup=new ArrayList<>();
    private void check(boolean b,String text){checks++;if(!b)throw new AssertionError(text);}
    private void main(Runnable action){Throwable[] failure={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}
    private File file(Context c,String suffix,byte[] bytes)throws Exception{File f=File.createTempFile("quiz-import-qa-",suffix,c.getCacheDir());cleanup.add(f);try(OutputStream out=new FileOutputStream(f)){out.write(bytes);}return f;}
    private boolean waitText(String text,boolean click)throws Exception{
        long end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null){List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(text);for(AccessibilityNodeInfo node:nodes)if(text.equals(node.getText()==null?"":node.getText().toString())){
                if(!click)return true;
                android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);
                if(node.isVisibleToUser()&&!bounds.isEmpty()){
                    long now=android.os.SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0),up=MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0);
                    down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    try{return getUiAutomation().injectInputEvent(down,true)&&getUiAutomation().injectInputEvent(up,true);}finally{down.recycle();up.recycle();}
                }
                while(node!=null&&!node.isClickable())node=node.getParent();if(node!=null)return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);}}
            Thread.sleep(150);
        }return false;
    }
    private TextView find(View v,String text){if(v instanceof TextView&&text.equals(((TextView)v).getText().toString()))return (TextView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView found=find(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private byte[] workbook()throws Exception{
        String[] names={"xl/workbook.xml","xl/_rels/workbook.xml.rels","xl/worksheets/sheet1.xml"};
        String[] xml={"<workbook xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='测试' r:id='r1'/></sheets></workbook>",
            "<Relationships><Relationship Id='r1' Target='worksheets/sheet1.xml'/></Relationships>",
            "<worksheet><sheetData><row><c r='A1' t='inlineStr'><is><t>题干</t></is></c><c r='B1' t='inlineStr'><is><t>答案</t></is></c></row><row><c r='A2' t='inlineStr'><is><t>QA 原生 Excel "+System.nanoTime()+"</t></is></c><c r='B2'><v>42</v></c></row></sheetData></worksheet>"};
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){for(int i=0;i<names.length;i++){zip.putNextEntry(new ZipEntry(names[i]));zip.write(xml[i].getBytes("UTF-8"));zip.closeEntry();}}return out.toByteArray();
    }
    private void submit(Intent result){main(()->activity.onActivityResult(71,Activity.RESULT_OK,result));}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();String before=QuizStore.snapshot(c).db.toString();String subject=QuizStore.snapshot(c).subjects().isEmpty()?"高数":QuizStore.snapshot(c).subjects().get(0);
        byte[] book=workbook();String converted=QuizImport.read(new ByteArrayInputStream(book),"native.xlsx",subject);
        check(new JSONObject(converted).getJSONArray("questions").length()==1,"Android SAX XLSX conversion");
        String csv="题干,答案\nQA 中文 CSV "+System.nanoTime()+",1";
        check(new JSONObject(QuizImport.read(new ByteArrayInputStream(csv.getBytes("GB18030")),"test.csv",subject)).getJSONArray("questions").length()==1,"Android GB18030 conversion");
        activity=(QuizActivity)startActivitySync(new Intent(c,QuizActivity.class).putExtra(CardManagementActivity.EXTRA_MANAGE,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));waitForIdleSync();
        IntentFilter pickerFilter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);pickerFilter.addCategory(Intent.CATEGORY_OPENABLE);pickerFilter.addDataType("*/*");
        final Intent[] picker={null};ActivityMonitor monitor=android.os.Build.VERSION.SDK_INT<26
            ?new ActivityMonitor(pickerFilter,new ActivityResult(Activity.RESULT_CANCELED,null),true)
            :new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())){picker[0]=intent;return new ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};addMonitor(monitor);
        main(()->{TextView button=find(activity.getWindow().getDecorView(),"导入题库");check(button!=null,"import entry exists");button.performClick();});
        check(waitText("选择题库文件（可多选）",true),"native file import menu");
        check(waitText(subject,true),"fallback subject selection");waitForIdleSync();Thread.sleep(300);removeMonitor(monitor);
        if(android.os.Build.VERSION.SDK_INT<26)check(monitor.getHits()>0,"SAF picker intercepted");
        else check(picker[0]!=null&&picker[0].getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false),"SAF multi-file intent");
        File xlsx=file(c,".xlsx",book),text=file(c,".csv",csv.getBytes("GB18030"));
        Intent multi=new Intent();ClipData clip=ClipData.newRawUri("题库",Uri.fromFile(xlsx));clip.addItem(new ClipData.Item(Uri.fromFile(text)));multi.setClipData(clip);submit(multi);
        check(waitText("确认导入预览",false),"XLSX plus CSV preview");check(waitText("导入 2 题",false),"merged count preview");
        Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot!=null){File image=new File(c.getExternalFilesDir(null),"quiz-import-preview.png");try(OutputStream out=new FileOutputStream(image)){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();}
        check(waitText("取消",true),"cancel preview");waitForIdleSync();
        check(before.equals(QuizStore.snapshot(c).db.toString()),"cancel preserves all quiz progress");
        File bad=file(c,".csv","题干,答案\nQA missing answer,".getBytes("UTF-8"));submit(new Intent().setData(Uri.fromFile(bad)));
        check(waitText("暂未完成",false),"invalid file reports error");check(waitText("知道了",true),"dismiss error");
        check(before.equals(QuizStore.snapshot(c).db.toString()),"invalid batch never commits");
        result.putString("stream","Quiz import device: "+checks+" checks passed\n");finish(-1,result);
    }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));finish(0,result);}
    finally{if(activity!=null)main(()->activity.finish());for(File f:cleanup)f.delete();}}
}
