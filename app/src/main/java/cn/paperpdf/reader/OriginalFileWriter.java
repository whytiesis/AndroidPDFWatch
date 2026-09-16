package cn.paperpdf.reader;

import android.content.ContentResolver;
import android.net.Uri;
import java.io.*;
import java.security.*;
import java.util.Arrays;

/** Preserve original bytes before truncating a provider document; verify writes and restore on failure. */
final class OriginalFileWriter {
    interface Target { InputStream read() throws IOException; OutputStream write() throws IOException; }
    static void replace(ContentResolver resolver,Uri uri,File replacement,File backup) throws IOException {
        replace(new Target() {
            public InputStream read() throws IOException { return resolver.openInputStream(uri); }
            public OutputStream write() throws IOException { return resolver.openOutputStream(uri,"wt"); }
        },replacement,backup);
    }
    static void replace(Target target,File replacement,File backup) throws IOException {
        try(InputStream in=target.read(); FileOutputStream out=new FileOutputStream(backup)) { copy(in,out); out.getFD().sync(); }
        byte[] expected; try(InputStream in=new FileInputStream(replacement)) { expected=digest(in); }
        boolean opened=false;
        try {
            OutputStream destination=target.write(); if(destination==null) throw new IOException("文件提供方不支持写入"); opened=true;
            try(OutputStream out=destination;InputStream in=new FileInputStream(replacement)) { copy(in,out); }
            try(InputStream in=target.read()) { if(!Arrays.equals(expected,digest(in))) throw new IOException("写入校验失败"); }
            backup.delete();
        } catch(IOException|SecurityException failure) {
            if(!opened) { backup.delete(); throw new IOException("无法写入原文件，请重新授权或另存为 PDF。",failure); }
            try {
                try(InputStream in=new FileInputStream(backup);OutputStream out=target.write()) { copy(in,out); }
                try(InputStream a=target.read();InputStream b=new FileInputStream(backup)) {
                    if(!Arrays.equals(digest(a),digest(b))) throw new IOException("恢复校验失败");
                }
            } catch(Exception restore) { throw new IOException("保存失败，原文件备份已保留："+backup.getAbsolutePath()+"。请保留应用数据并另存当前文档。",failure); }
            backup.delete(); throw new IOException("保存失败，已恢复原文件。请另存为 PDF。",failure);
        }
    }
    private static void copy(InputStream in,OutputStream out) throws IOException {
        if(in==null||out==null) throw new IOException("文件提供方未返回数据流");
        byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1) out.write(buffer,0,n); out.flush();
    }
    private static byte[] digest(InputStream in) throws IOException {
        if(in==null) throw new IOException("无法校验文件");
        try { MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[65536]; int n;
            while((n=in.read(buffer))!=-1) digest.update(buffer,0,n); return digest.digest();
        } catch(NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
