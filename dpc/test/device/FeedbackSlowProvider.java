package com.afs.guard;
import android.content.*;import android.database.Cursor;import android.net.Uri;import android.os.*;import java.io.*;
/** Test APK only: a delayed pipe makes page destruction deterministic before restore queue finishes. */
public final class FeedbackSlowProvider extends ContentProvider {
 private volatile byte[] archive;
 public boolean onCreate(){return true;}
 public Bundle call(String method,String arg,Bundle extras){archive=extras.getByteArray("archive");return new Bundle();}
 public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
  try{ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();byte[] bytes=archive;new Thread(()->{SystemClock.sleep(700);try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){out.write(bytes);}catch(IOException ignored){}},"qa-slow-pipe").start();return pipe[0];}catch(IOException e){throw new FileNotFoundException(e.toString());}
 }
 public String getType(Uri u){return "application/zip";}public Cursor query(Uri u,String[] p,String s,String[] a,String sort){return null;}public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){return 0;}public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
