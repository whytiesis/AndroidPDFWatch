package cn.paperpdf.reader;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public class ShareProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(name==null || !name.matches("[a-zA-Z0-9_-]+\\.pdf")) throw new FileNotFoundException();
        return new File(getContext().getCacheDir(),"shared/"+name);
    }
    @Override public String getType(Uri uri) { return "application/pdf"; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        String[] columns=projection!=null?projection:new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};
        MatrixCursor c=new MatrixCursor(columns);
        try { File f=file(uri); Object[] row=new Object[columns.length];
            for(int i=0;i<columns.length;i++) { if(OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i]="纸阅-已编辑.pdf"; else if(OpenableColumns.SIZE.equals(columns[i])) row[i]=f.length(); }
            c.addRow(row);
        } catch(IOException ignored) { }
        return c;
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
