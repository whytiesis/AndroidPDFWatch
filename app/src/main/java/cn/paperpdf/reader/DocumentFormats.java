package cn.paperpdf.reader;

import java.io.*;
import java.util.Locale;

final class DocumentFormats {
    static final String DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String DOC="application/msword";
    static final String[] TYPES={"application/pdf","application/x-pdf",DOC,DOCX};
    static boolean supported(String name,String mime) {
        String n=name.toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf")||n.endsWith(".docx")||n.endsWith(".doc")||"application/pdf".equals(mime)||"application/x-pdf".equals(mime)||DOC.equals(mime)||DOCX.equals(mime);
    }
    static String detect(File file) throws IOException {
        byte[] b=new byte[8]; try(InputStream in=new FileInputStream(file)) { if(in.read(b)<5) throw new IOException("文件为空或已损坏"); }
        if(b[0]=='%'&&b[1]=='P'&&b[2]=='D'&&b[3]=='F') return "pdf";
        if(b[0]=='P'&&b[1]=='K') return "docx";
        if((b[0]&255)==0xd0&&(b[1]&255)==0xcf&&(b[2]&255)==0x11&&(b[3]&255)==0xe0) return "doc";
        throw new IOException("不支持此文件内容。请选择 PDF、DOCX 或 Word 97–2003 DOC 文档。");
    }
}
