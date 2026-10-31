package com.afs.guard;
import android.app.Instrumentation;
import android.os.Bundle;
import android.content.Context;
import android.net.Uri;
import android.graphics.*;
import android.app.Activity;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;

public final class ToolkitDeviceTest extends Instrumentation {
    int checks; final List<File> cleanup=new ArrayList<>();
    void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    TextView find(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().equals(text))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    EditText edit(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText found=edit(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
    void pages(Context c){android.content.SharedPreferences prefs=c.getSharedPreferences("ToolkitActivity",0);String original=prefs.getString("history",null);try{String[] modes={"calc","units","dates","pdf","image"};String[] titles={"科学计算器","工科单位换算","日期计算","PDF 合并与提取","图片处理"};for(int i=0;i<modes.length;i++){
        final Activity a=startActivitySync(new Intent().setClassName(c,"com.afs.guard.ToolkitActivity").putExtra("tool",modes[i]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();final String title=titles[i];final boolean calc=i==0;
        runOnMainSync(()->{View root=a.getWindow().getDecorView();check(find(root,title)!=null,"page "+title);if(calc){edit(root).setText("2+3*4");find(root,"计算").performClick();check(find(root,"14")!=null,"calculator UI result");}a.finish();});waitForIdleSync();
    }}finally{android.content.SharedPreferences.Editor edit=prefs.edit();if(original==null)edit.remove("history");else edit.putString("history",original);edit.commit();}}
    File temp(Context c,String suffix)throws Exception{File f=File.createTempFile("toolkit-qa-",suffix,c.getCacheDir());cleanup.add(f);return f;}
    File pdf(Context c,String text,int pages)throws Exception{File f=temp(c,".pdf");try(PDDocument doc=new PDDocument()){for(int i=0;i<pages;i++){PDPage page=new PDPage();doc.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(40,700);stream.showText(text+(i+1));stream.endText();}}doc.save(f);}return f;}
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context c=getTargetContext();PDFBoxResourceLoader.init(c);File a=pdf(c,"Alpha",2),b=pdf(c,"Beta",1);
        File merged=ToolFiles.pdf(c,Arrays.asList(Uri.fromFile(a),Uri.fromFile(b)),true,"");cleanup.add(merged);
        try(PDDocument doc=PDDocument.load(merged)){String text=new PDFTextStripper().getText(doc);check(doc.getNumberOfPages()==3,"merge page count");check(text.indexOf("Alpha1")<text.indexOf("Beta1"),"merge order");check(text.contains("Alpha2"),"selectable text retained");}
        File extracted=ToolFiles.pdf(c,Arrays.asList(Uri.fromFile(a)),false,"2");cleanup.add(extracted);
        try(PDDocument doc=PDDocument.load(extracted)){String text=new PDFTextStripper().getText(doc);check(doc.getNumberOfPages()==1,"extract count");check(text.contains("Alpha2")&&!text.contains("Alpha1"),"extract correct text");}
        boolean invalid=false;try{ToolFiles.pdf(c,Arrays.asList(Uri.fromFile(a)),false,"3");}catch(IllegalArgumentException e){invalid=true;}check(invalid,"invalid range rejected");
        File source=temp(c,".png");Bitmap image=Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(source)){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
        for(String format:new String[]{"jpg","png","webp"}){File converted=ToolFiles.image(c,Uri.fromFile(source),200,80,format);cleanup.add(converted);Bitmap decoded=BitmapFactory.decodeFile(converted.getPath());check(decoded!=null&&decoded.getWidth()==200&&decoded.getHeight()==100,"image size "+format);if("jpg".equals(format))check(Color.red(decoded.getPixel(50,50))>240,"transparent JPEG white");decoded.recycle();}
        check(source.isFile()&&a.isFile()&&b.isFile(),"originals retained");
        pages(c);
        result.putString("stream","Toolkit device: "+checks+" checks passed\n");finish(-1,result);
    }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));finish(0,result);}finally{for(File f:cleanup)f.delete();}}
}
