package com.afs.guard;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import org.json.JSONObject;

/** Private, bounded attachment copies. Body links reference random IDs, never source paths. */
public final class MemoFiles {
    private MemoFiles(){}
    public static File root(Context c){File root=new File(c.getFilesDir(),"memo-attachments");if(!root.exists()&&!root.mkdirs())throw new IllegalStateException(T.s("附件目录不可用"));return root;}
    public static boolean validId(String id){return id!=null&&id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
    public static File data(Context c,String id)throws IOException{if(!validId(id))throw new IOException(T.s("附件编号无效"));File data=new File(new File(root(c),id),"data");if(!data.isFile())throw new IOException(T.s("附件不存在"));return data;}
    public static JSONObject meta(Context c,String id)throws Exception{
        data(c,id);File file=new File(new File(root(c),id),"meta.json");if(file.length()>8192)throw new IOException(T.s("附件元数据异常"));
        try(InputStream in=new FileInputStream(file)){return new JSONObject(read(in,8192));}
    }
    static String read(InputStream in,int max)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException(T.s("文件过大"));out.write(b,0,n);}return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}
    public static String add(Context c,Uri uri)throws Exception{
        boolean local="file".equals(uri.getScheme());String name=local?uri.getLastPathSegment():T.s("附件"),mime=local?java.net.URLConnection.guessContentTypeFromName(name):c.getContentResolver().getType(uri);
        if(!local)try(android.database.Cursor cursor=c.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))name=cursor.getString(0);
        }
        name=name.replaceAll("[\\p{Cntrl}\\[\\]()]","_");if(name.length()>100)name=name.substring(0,100);
        long used=0;File[] old=root(c).listFiles();if(old!=null)for(File item:old)if(validId(item.getName()))used+=new File(item,"data").length();
        String id=java.util.UUID.randomUUID().toString();File folder=new File(root(c),id);if(!folder.mkdir())throw new IOException(T.s("无法保存附件"));boolean success=false;
        try(InputStream input=c.getContentResolver().openInputStream(uri);OutputStream output=new FileOutputStream(new File(folder,"data"))){
            if(input==null)throw new IOException(T.s("无法读取附件"));byte[] b=new byte[8192];int n;long size=0;
            while((n=input.read(b))!=-1){size+=n;if(size>20L*1024*1024||used+size>100L*1024*1024)throw new IOException(T.s("单个附件最多20MB，附件总计最多100MB"));output.write(b,0,n);}
            JSONObject meta=new JSONObject().put("name",name).put("mime",mime==null?"application/octet-stream":mime);
            try(OutputStream m=new FileOutputStream(new File(folder,"meta.json"))){m.write(meta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            success=true;return T.s("[附件：")+name+"](afs-file:"+id+")";
        }finally{if(!success){new File(folder,"data").delete();new File(folder,"meta.json").delete();folder.delete();}}
    }
    public static void open(android.app.Activity a,String id){try{
        String mime=meta(a,id).optString("mime","application/octet-stream");
        Uri uri=Uri.parse("content://"+a.getPackageName()+".memo.files/"+id);
        a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }catch(Exception e){android.widget.Toast.makeText(a,T.s("无法打开附件，请检查文件是否存在或安装对应应用"),1).show();}}
}
