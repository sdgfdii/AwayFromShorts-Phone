package com.afs.guard;

import android.content.Context;
import android.net.Uri;
import android.graphics.*;
import android.media.ExifInterface;
import java.io.*;
import java.util.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;

/** File processing runs only on a worker, reads chosen URIs, and writes private temporary files. */
public final class ToolFiles {
    private ToolFiles(){}
    static final long MAX_BYTES=40L*1024*1024;
    public static File copyInput(Context c,Uri uri) throws IOException {
        File f=File.createTempFile("tool-source-",".bin",c.getCacheDir());
        try(InputStream in=c.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(f)){
            if(in==null)throw new IOException("无法读取文件");copy(in,out,MAX_BYTES);return f;
        }catch(IOException|RuntimeException e){f.delete();throw e;}
    }
    public static void copy(InputStream in,OutputStream out,long limit)throws IOException{byte[] b=new byte[32768];long n=0;int k;while((k=in.read(b))!=-1){n+=k;if(n>limit)throw new IOException("文件超过处理上限");out.write(b,0,k);}}
    public static File pdf(Context c,List<Uri> uris,boolean merge,String range)throws IOException{
        if(uris.isEmpty()||uris.size()>20||(!merge&&uris.size()!=1))throw new IOException("合并请选择 2–20 个 PDF，提取请选择 1 个 PDF");
        if(merge&&uris.size()<2)throw new IOException("合并至少需要两个 PDF");
        PDFBoxResourceLoader.init(c.getApplicationContext());
        File result=File.createTempFile("tool-result-",".pdf",c.getCacheDir());boolean success=false;long bytes=0;
        MemoryUsageSetting memory=MemoryUsageSetting.setupTempFileOnly(160L*1024*1024);memory.setTempDir(c.getCacheDir());
        try(PDDocument output=new PDDocument(memory)){
            PDFMergerUtility merger=new PDFMergerUtility();int total=0;
            for(Uri uri:uris){File input=copyInput(c,uri);try{
                bytes+=input.length();if(bytes>80L*1024*1024)throw new IOException("所选文件合计不能超过 80 MB");
                try(PDDocument source=PDDocument.load(input,memory)){
                    if(source.isEncrypted())throw new IOException("暂不支持加密 PDF，请先提供未加密副本");
                    total+=source.getNumberOfPages();if(total>500)throw new IOException("最多处理 500 页");
                    if(merge)merger.appendDocument(output,source);
                    else {int[] selected=ToolMath.pages(range,source.getNumberOfPages());for(int p:selected){PDPage page=source.getPage(p);PDPage imported=output.importPage(page);imported.setResources(page.getResources());}
                        // Save while the source remains open: imported resource streams belong to it.
                        output.save(result);
                    }
                }
            }finally{input.delete();}}
            if(merge)output.save(result);success=true;return result;
        }catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e){throw new IOException("暂不支持加密 PDF",e);}
        finally{if(!success)result.delete();}
    }
    public static File image(Context c,Uri uri,int edge,int quality,String format)throws IOException{
        if(edge<64||edge>4096||quality<1||quality>100)throw new IOException("最长边为 64–4096，质量为 1–100");
        File input=copyInput(c,uri),result=null;Bitmap bitmap=null;
        try{
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;BitmapFactory.decodeFile(input.getPath(),opts);
            if(opts.outWidth<=0||opts.outHeight<=0)throw new IOException("无法识别图片");
            int sample=1;while((long)(opts.outWidth/sample)*(opts.outHeight/sample)>16000000L||Math.max(opts.outWidth,opts.outHeight)/sample>edge*2)sample*=2;
            opts.inJustDecodeBounds=false;opts.inSampleSize=sample;opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
            bitmap=BitmapFactory.decodeFile(input.getPath(),opts);if(bitmap==null)throw new IOException("图片解码失败");
            int orientation=1;try{orientation=new ExifInterface(input.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
            Matrix matrix=new Matrix();switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;case 8:matrix.setRotate(-90);break;default:break;}
            if(!matrix.isIdentity()){Bitmap next=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(next!=bitmap){bitmap.recycle();bitmap=next;}}
            double scale=Math.min(1d,(double)edge/Math.max(bitmap.getWidth(),bitmap.getHeight()));
            if(scale<1){Bitmap next=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)Math.round(bitmap.getWidth()*scale)),Math.max(1,(int)Math.round(bitmap.getHeight()*scale)),true);if(next!=bitmap){bitmap.recycle();bitmap=next;}}
            Bitmap.CompressFormat type="png".equals(format)?Bitmap.CompressFormat.PNG:"webp".equals(format)?Bitmap.CompressFormat.WEBP:Bitmap.CompressFormat.JPEG;
            if(type==Bitmap.CompressFormat.JPEG&&bitmap.hasAlpha()){Bitmap solid=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(solid);canvas.drawColor(Color.WHITE);canvas.drawBitmap(bitmap,0,0,null);bitmap.recycle();bitmap=solid;}
            result=File.createTempFile("tool-result-","."+format,c.getCacheDir());
            try(OutputStream out=new FileOutputStream(result)){if(!bitmap.compress(type,quality,out))throw new IOException("图片保存失败");}
            return result;
        }catch(IOException|RuntimeException e){if(result!=null)result.delete();throw e;}
        finally{input.delete();if(bitmap!=null)bitmap.recycle();}
    }
}
