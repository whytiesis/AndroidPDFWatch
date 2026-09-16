package cn.paperpdf.reader;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.FileNotFoundException;

/** Minimal documents provider fixture for recursive folder indexing tests. */
public class TestTreeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private void add(MatrixCursor rows,String id,String name,String mime,long modified) {
        rows.addRow(new Object[]{id,name,mime,modified});
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        MatrixCursor rows=new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED});
        java.util.List<String> parts=uri.getPathSegments();
        String folder=parts.size()>1&&"children".equals(parts.get(parts.size()-1))?parts.get(parts.size()-2):uri.getLastPathSegment();
        if("root".equals(folder)) {
            add(rows,"chat","WeChat",DocumentsContract.Document.MIME_TYPE_DIR,0);
            add(rows,"bad","not a document.txt","text/plain",0);
            add(rows,"qq-pdf","QQ直接文件.PDF","application/octet-stream",50);
        } else if("chat".equals(folder)) {
            add(rows,"nested","inbox",DocumentsContract.Document.MIME_TYPE_DIR,0);
            add(rows,"wechat-pdf","微信账单.pdf","application/pdf",100);
        } else if("nested".equals(folder)) add(rows,"deep-pdf","嵌套文档.pdf","application/pdf",75);
        return rows;
    }
    @Override public String getType(Uri uri) { return "application/pdf"; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException { throw new FileNotFoundException(); }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String where,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String where,String[] args) { throw new UnsupportedOperationException(); }
}
