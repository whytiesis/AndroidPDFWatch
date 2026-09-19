package cn.paperpdf.reader;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Keeps a separate, recoverable working PDF and edit model for every source document. */
final class DraftStore {
    private final File directory;

    DraftStore(Context context) {
        directory=new File(context.getFilesDir(),"drafts");
        if(!directory.exists()) directory.mkdirs();
    }

    String id(String identity) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder value=new StringBuilder();
            for(int i=0;i<16;i++) value.append(String.format("%02x",digest[i]&0xff));
            return value.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }

    File pdf(String id) { return new File(directory,id+".pdf"); }
    File state(String id) { return new File(directory,id+".json"); }
    boolean exists(String id) { return !id.isEmpty()&&pdf(id).isFile()&&state(id).isFile(); }

    JSONObject read(String id) throws Exception {
        AtomicFile file=new AtomicFile(state(id));
        try(InputStream input=file.openRead();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            copy(input,output); return new JSONObject(output.toString("UTF-8"));
        }
    }

    void write(String id,JSONObject value) throws Exception {
        if(id.isEmpty()) throw new IOException("草稿标识为空");
        if(!directory.exists()&&!directory.mkdirs()) throw new IOException("无法创建草稿目录");
        AtomicFile file=new AtomicFile(state(id)); FileOutputStream output=null;
        try {
            output=file.startWrite(); output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch(Exception e) { if(output!=null) file.failWrite(output); throw e; }
    }

    boolean dirty(String id) {
        if(!exists(id)) return false;
        try {
            JSONObject value=read(id);
            return !value.optString("saved").equals(value.optString("pages"));
        } catch(Exception ignored) { return false; }
    }

    private static void copy(InputStream input,OutputStream output) throws IOException {
        byte[] buffer=new byte[16384]; int count;
        while((count=input.read(buffer))!=-1) output.write(buffer,0,count);
    }
}
