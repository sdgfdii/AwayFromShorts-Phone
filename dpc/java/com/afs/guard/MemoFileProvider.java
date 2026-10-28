package com.afs.guard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

public final class MemoFileProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private String id(Uri uri){if(uri.getPathSegments().size()!=1||!MemoFiles.validId(uri.getLastPathSegment()))throw new IllegalArgumentException("Invalid attachment");return uri.getLastPathSegment();}
    @Override public String getType(Uri uri){try{return MemoFiles.meta(getContext(),id(uri)).optString("mime","application/octet-stream");}catch(Exception e){return "application/octet-stream";}}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException{
        if(!"r".equals(mode))throw new java.io.FileNotFoundException("Read only");try{return ParcelFileDescriptor.open(MemoFiles.data(getContext(),id(uri)),ParcelFileDescriptor.MODE_READ_ONLY);}catch(Exception e){throw new java.io.FileNotFoundException("Attachment unavailable");}
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){try{
        String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];String id=id(uri);
        for(int i=0;i<columns.length;i++)if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]=MemoFiles.meta(getContext(),id).optString("name","附件");else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=MemoFiles.data(getContext(),id).length();cursor.addRow(values);return cursor;
    }catch(Exception e){return null;}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
}
