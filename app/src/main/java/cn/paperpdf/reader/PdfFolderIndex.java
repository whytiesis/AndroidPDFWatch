package cn.paperpdf.reader;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Lists PDFs inside a directory tree explicitly granted by the Android file picker. */
public final class PdfFolderIndex {
    private static final int MAX_ENTRIES = 20000;
    private static final int MAX_PDFS = 5000;
    private static final int MAX_DEPTH = 16;

    public static final class Entry {
        public final Uri uri;
        public final String name;
        public final long modified;
        Entry(Uri uri, String name, long modified) {
            this.uri=uri; this.name=name; this.modified=modified;
        }
    }
    private static final class Directory {
        final String id;
        final int depth;
        Directory(String id,int depth) { this.id=id; this.depth=depth; }
    }
    private PdfFolderIndex() { }

    public static ArrayList<Entry> scan(ContentResolver resolver, Uri tree) {
        ArrayList<Entry> pdfs=new ArrayList<>();
        ArrayDeque<Directory> directories=new ArrayDeque<>();
        directories.add(new Directory(DocumentsContract.getTreeDocumentId(tree),0));
        int visited=0;
        while(!directories.isEmpty()&&visited<MAX_ENTRIES&&pdfs.size()<MAX_PDFS) {
            Directory directory=directories.removeFirst();
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,directory.id);
            String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED};
            try(Cursor cursor=resolver.query(children,columns,null,null,null)) {
                if(cursor==null) continue;
                while(cursor.moveToNext()&&visited++<MAX_ENTRIES&&pdfs.size()<MAX_PDFS) {
                    String id=cursor.getString(0),name=cursor.getString(1),mime=cursor.getString(2);
                    if(id==null||name==null) continue;
                    if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if(directory.depth<MAX_DEPTH) directories.addLast(new Directory(id,directory.depth+1));
                    } else if("application/pdf".equalsIgnoreCase(mime)||name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                        pdfs.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(tree,id),name,cursor.isNull(3)?0:cursor.getLong(3)));
                    }
                }
            } catch(SecurityException e) { throw e; }
            catch(Exception e) { /* A removed or unreadable child folder does not hide other folders. */ }
        }
        Collections.sort(pdfs,new Comparator<Entry>() {
            @Override public int compare(Entry first,Entry second) {
                int newest=Long.compare(second.modified,first.modified);
                return newest!=0?newest:first.name.compareTo(second.name);
            }
        });
        return pdfs;
    }
}
