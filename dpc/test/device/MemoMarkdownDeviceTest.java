package com.afs.guard;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;import java.lang.reflect.*;
public final class MemoMarkdownDeviceTest extends Instrumentation {
    Activity host;int checks;
    void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
    Object field(String name)throws Exception{Field f=MemoActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(host);}
    void call(String name,Class<?>[] types,Object... values){runOnMainSync(()->{try{Method m=MemoActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(host,values);}catch(Exception e){throw new RuntimeException(e);}});}
    View find(View v,String label){if(v instanceof TextView&&label.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}return null;}
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void onStart(){Bundle out=new Bundle();int status=0;try{
        check(Build.MODEL.toLowerCase().contains("sdk"),"emulator only");host=startActivitySync(new Intent(getTargetContext(),MemoActivity.class).putExtra("new",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(600);
        View button=find(host.getWindow().getDecorView(),"Markdown");check(button instanceof MemoToolButton,"Markdown has its own vector toolbar button");runOnMainSync(button::performClick);Thread.sleep(300);
        android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();try(java.io.OutputStream file=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"memo-markdown.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file);}shot.recycle();
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);Thread.sleep(200);
        EditText body=(EditText)field("bodyInput");String[] expected={"# sample","## sample","### sample","sample","> sample","- sample","1. sample","- [ ] sample","  sample","sample"};
        for(int i=0;i<10;i++){String initial=i==3?"### sample":i==9?"  sample":"sample";runOnMainSync(()->{body.setText(initial);body.setSelection(0,body.length());});call("markdownAction",new Class<?>[]{int.class,int.class,int.class},i,0,initial.length());check(expected[i].equals(body.getText().toString()),"format option "+i+" writes correct Markdown");}
        call("travel",new Class<?>[]{boolean.class},false);check("  sample".equals(body.getText().toString()),"format operation can be undone");call("travel",new Class<?>[]{boolean.class},true);check("sample".equals(body.getText().toString()),"format operation can be redone");
        out.putString("stream","Memo markdown device: "+checks+" checks passed\n");status=-1;
    }catch(Throwable e){out.putString("stream",android.util.Log.getStackTraceString(e));}finally{if(host!=null)runOnMainSync(host::finish);finish(status,out);}}
}
