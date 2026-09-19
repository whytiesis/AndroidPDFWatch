package cn.paperpdf.reader;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.util.Matrix;
import org.json.*;
import java.io.*;
import java.util.*;

/** Original PDF stays immutable. Every export is rebuilt from the editable page model. */
public final class PdfEngine {
    public static final int INK = 1, HIGHLIGHT = 2, TEXT = 3, ERASER = 4, SELECT = 5;
    public static class Mark {
        int type, color;
        float width;
        String text = "";
        ArrayList<PointF> points = new ArrayList<>();
        JSONObject json() throws JSONException {
            JSONObject j = new JSONObject().put("type",type).put("color",color).put("width",width).put("text",text);
            JSONArray a = new JSONArray();
            for (PointF p : points) a.put(new JSONArray().put(p.x).put(p.y));
            return j.put("points",a);
        }
        static Mark from(JSONObject j) throws JSONException {
            Mark m = new Mark(); m.type=j.getInt("type"); m.color=j.getInt("color");
            m.width=(float)j.getDouble("width"); m.text=j.optString("text");
            JSONArray a=j.getJSONArray("points");
            for(int i=0;i<a.length();i++) m.points.add(new PointF((float)a.getJSONArray(i).getDouble(0),(float)a.getJSONArray(i).getDouble(1)));
            return m;
        }
    }
    public static class Sheet {
        int original, rotation;
        ArrayList<Mark> marks = new ArrayList<>();
        Sheet(int original) { this.original=original; }
        JSONObject json() throws JSONException {
            JSONArray a=new JSONArray(); for(Mark m:marks) a.put(m.json());
            return new JSONObject().put("original",original).put("rotation",rotation).put("marks",a);
        }
    }
    public static String encode(List<Sheet> sheets) {
        try { JSONArray a=new JSONArray(); for(Sheet s:sheets) a.put(s.json()); return a.toString(); }
        catch(JSONException e) { throw new IllegalStateException(e); }
    }
    public static ArrayList<Sheet> decode(String json) throws JSONException {
        ArrayList<Sheet> list=new ArrayList<>(); JSONArray a=new JSONArray(json);
        for(int i=0;i<a.length();i++) {
            JSONObject j=a.getJSONObject(i); Sheet s=new Sheet(j.getInt("original")); s.rotation=j.getInt("rotation");
            JSONArray m=j.getJSONArray("marks"); for(int k=0;k<m.length();k++) s.marks.add(Mark.from(m.getJSONObject(k)));
            list.add(s);
        }
        return list;
    }
    public static void paintMarks(Canvas c, List<Mark> marks, float width, float height) {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        for(Mark m:marks) {
            if(m.points.isEmpty()) continue;
            p.setColor(m.color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(m.width*width);
            PointF first=m.points.get(0);
            if(m.type==TEXT) {
                p.setStyle(Paint.Style.FILL); p.setTextSize(m.width*width); p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
                float y=first.y*height;
                for(String line:m.text.split("\n",-1)) { c.drawText(line,first.x*width,y,p); y+=p.getFontSpacing(); }
            } else if(m.type==HIGHLIGHT) {
                p.setStyle(Paint.Style.FILL); PointF end=m.points.get(m.points.size()-1);
                c.drawRect(Math.min(first.x,end.x)*width,Math.min(first.y,end.y)*height,
                        Math.max(first.x,end.x)*width,Math.max(first.y,end.y)*height,p);
            } else {
                Path path=new Path(); path.moveTo(first.x*width,first.y*height);
                if(m.points.size()==1) { p.setStyle(Paint.Style.FILL); c.drawCircle(first.x*width,first.y*height,m.width*width/2,p); }
                else { for(int i=1;i<m.points.size();i++) { PointF point=m.points.get(i); path.lineTo(point.x*width,point.y*height); } c.drawPath(path,p); }
            }
        }
    }
    static RectF markBounds(Mark mark) {
        if(mark.points.isEmpty()) return new RectF();
        PointF first=mark.points.get(0);
        if(mark.type==TEXT) {
            String[] lines=mark.text.split("\n",-1); int longest=1;
            for(String line:lines) longest=Math.max(longest,line.length());
            float width=Math.max(.025f,longest*mark.width*.56f), height=Math.max(mark.width,lines.length*mark.width*1.18f);
            return new RectF(first.x,first.y-mark.width,Math.min(1,first.x+width),Math.min(1,first.y-mark.width+height));
        }
        float left=first.x,right=first.x,top=first.y,bottom=first.y;
        for(PointF point:mark.points) { left=Math.min(left,point.x); right=Math.max(right,point.x); top=Math.min(top,point.y); bottom=Math.max(bottom,point.y); }
        float padding=Math.max(.012f,mark.width*1.8f);
        return new RectF(Math.max(0,left-padding),Math.max(0,top-padding),Math.min(1,right+padding),Math.min(1,bottom+padding));
    }
    static int hitMark(List<Mark> marks,float x,float y) {
        for(int i=marks.size()-1;i>=0;i--) {
            Mark mark=marks.get(i); RectF bounds=markBounds(mark);
            float padding=mark.type==TEXT?.012f:.018f;
            bounds.inset(-padding,-padding);
            if(mark.type==HIGHLIGHT||mark.type==TEXT) { if(bounds.contains(x,y)) return i; continue; }
            if(mark.points.size()==1) {
                PointF point=mark.points.get(0); if(Math.hypot(x-point.x,y-point.y)<=Math.max(.02f,mark.width*2.5f)) return i;
            } else {
                for(int n=1;n<mark.points.size();n++) if(distance(x,y,mark.points.get(n-1),mark.points.get(n))<=Math.max(.018f,mark.width*2.5f)) return i;
            }
        }
        return -1;
    }
    private static float distance(float x,float y,PointF start,PointF end) {
        float dx=end.x-start.x,dy=end.y-start.y,length=dx*dx+dy*dy;
        if(length==0) return (float)Math.hypot(x-start.x,y-start.y);
        float t=Math.max(0,Math.min(1,((x-start.x)*dx+(y-start.y)*dy)/length));
        return (float)Math.hypot(x-(start.x+t*dx),y-(start.y+t*dy));
    }
    public static Bitmap render(PdfRenderer renderer, Sheet sheet, int requestedWidth) {
        try(PdfRenderer.Page page=renderer.openPage(sheet.original)) {
            boolean swapped=sheet.rotation%180!=0;
            int visibleWidth=swapped?page.getHeight():page.getWidth();
            int visibleHeight=swapped?page.getWidth():page.getHeight();
            float scale=Math.min(Math.min(requestedWidth/(float)visibleWidth,2400f/Math.max(visibleWidth,visibleHeight)),
                    (float)Math.sqrt(4500000d/(visibleWidth*(double)visibleHeight)));
            int w=Math.max(1,Math.round(page.getWidth()*scale)), h=Math.max(1,Math.round(page.getHeight()*scale));
            Bitmap raw=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); raw.eraseColor(Color.WHITE);
            page.render(raw,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            if(sheet.rotation==0) return raw;
            android.graphics.Matrix transform=new android.graphics.Matrix(); transform.postRotate(sheet.rotation);
            Bitmap rotated=Bitmap.createBitmap(raw,0,0,w,h,transform,true); if(rotated!=raw) raw.recycle(); return rotated;
        }
    }
    public static void rotate(Sheet s) {
        s.rotation=(s.rotation+90)%360;
        // Text remains readable after rotation; positions and pen strokes follow the page.
        for(Mark m:s.marks) for(PointF p:m.points) { float x=p.x; p.x=1-p.y; p.y=x; }
    }
    public static void export(File source, List<Sheet> sheets, File destination) throws IOException {
        try(PDDocument doc=PDDocument.load(source); PDDocument out=new PDDocument()) {
            if(!doc.getCurrentAccessPermission().canModify()) throw new IOException("此 PDF 不允许修改");
            for(Sheet s:sheets) {
                PDPage page=out.importPage(doc.getPage(s.original));
                PDRectangle crop=doc.getPage(s.original).getCropBox();
                page.setCropBox(crop); page.setMediaBox(doc.getPage(s.original).getMediaBox());
                int rotation=((doc.getPage(s.original).getRotation()+s.rotation)%360+360)%360; page.setRotation(rotation);
                if(s.marks.isEmpty()) continue;
                float w=crop.getWidth(),h=crop.getHeight(),x=crop.getLowerLeftX(),y=crop.getLowerLeftY();
                float dw=rotation%180==0?w:h, dh=rotation%180==0?h:w;
                float scale=Math.min(2f,2400f/Math.max(dw,dh));
                Bitmap layer=Bitmap.createBitmap(Math.max(1,Math.round(dw*scale)),Math.max(1,Math.round(dh*scale)),Bitmap.Config.ARGB_8888);
                paintMarks(new Canvas(layer),s.marks,layer.getWidth(),layer.getHeight());
                Matrix matrix;
                switch(rotation) {
                    case 90: matrix=new Matrix(0,h,-w,0,x+w,y); break;
                    case 180: matrix=new Matrix(-w,0,0,-h,x+w,y+h); break;
                    case 270: matrix=new Matrix(0,-h,w,0,x,y+h); break;
                    default: matrix=new Matrix(w,0,0,h,x,y);
                }
                try(PDPageContentStream stream=new PDPageContentStream(out,page,PDPageContentStream.AppendMode.APPEND,true,true)) {
                    stream.drawImage(LosslessFactory.createFromImage(out,layer),matrix);
                } finally { layer.recycle(); }
            }
            out.getDocumentInformation().setProducer("Paper PDF / 纸阅 PDF");
            out.save(destination);
        }
    }
    public static ArrayList<Integer> search(File source,List<Sheet> sheets,String query) throws IOException {
        ArrayList<Integer> matches=new ArrayList<>();
        try(PDDocument doc=PDDocument.load(source)) {
            if(!doc.getCurrentAccessPermission().canExtractContent()) throw new IOException("此文件不允许提取文字");
            PDFTextStripper stripper=new PDFTextStripper();
            String needle=query.toLowerCase(Locale.ROOT);
            for(int i=0;i<sheets.size();i++) {
                stripper.setStartPage(sheets.get(i).original+1); stripper.setEndPage(sheets.get(i).original+1);
                boolean found=stripper.getText(doc).toLowerCase(Locale.ROOT).contains(needle);
                for(Mark m:sheets.get(i).marks) if(m.text.toLowerCase(Locale.ROOT).contains(needle)) found=true;
                if(found) matches.add(i);
            }
        }
        return matches;
    }
}
