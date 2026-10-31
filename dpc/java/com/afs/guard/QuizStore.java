package com.afs.guard;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Separate from check-ins and freezing rules. Commit disk first; never reset an unreadable bank. */
public final class QuizStore {
    public static final int IMPORT_LIMIT=4*1024*1024;
    private static QuizData cache;
    private static String path;
    /** 设备回归专用：指向隔离文件，生产环境始终为 null。 */
    private static File testFile;
    static synchronized void setTestFile(File file){testFile=file;cache=null;path=null;}
    private static AtomicFile file(Context c) {return new AtomicFile(testFile!=null?testFile:new File(c.getFilesDir(),"quiz-v1.json"));}
    public static synchronized QuizData snapshot(Context c) {
        String p=c.getFilesDir().getAbsolutePath();
        if(cache==null||!p.equals(path)) {
            AtomicFile f=file(c);
            try {
                if(f.getBaseFile().exists()||new File(f.getBaseFile()+".bak").exists()) {
                    try(InputStream in=f.openRead()){cache=new QuizData(read(in,16*1024*1024));}
                } else {
                    try(InputStream in=c.getAssets().open("quiz/starter.json")) {
                        cache=new QuizData().prepareImport(read(in,IMPORT_LIMIT),T.s("高数"),T.s("入门示例")).candidate;
                    }
                }
                path=p;
            } catch(Exception e) {cache=null;throw new IllegalStateException(T.s("题库读取失败，原文件已保留：")+e.getMessage(),e);}
        }
        return new QuizData(cache.db.toString());
    }
    public static synchronized void commit(Context c,QuizData candidate) {
        byte[] bytes=candidate.db.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>16*1024*1024)throw new IllegalArgumentException(T.s("题库数据超过 16MB，请先导出整理"));
        AtomicFile f=file(c);FileOutputStream out=null;
        try {
            out=f.startWrite();out.write(bytes);f.finishWrite(out);
            cache=new QuizData(candidate.db.toString());path=c.getFilesDir().getAbsolutePath();
        }catch(Exception e){if(out!=null)f.failWrite(out);throw new IllegalStateException(T.s("保存失败，本次操作尚未记录"),e);}
    }
    public static String read(InputStream in,int max) throws IOException {
        if(in==null)throw new IOException(T.s("无法打开文件"));
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException(T.s("文件过大，导入文件最多 4MB"));out.write(b,0,n);}
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();}
        catch(CharacterCodingException e){throw new IOException(T.s("请将题库保存为 UTF-8 编码"),e);}
    }
}
