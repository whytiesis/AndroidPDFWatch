package cn.paperpdf.reader;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.text.*;
import android.text.style.*;
import org.json.*;
import org.w3c.dom.*;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Offline Word working copy. Original OOXML parts are retained; only edited paragraphs change. */
final class WordEngine {
    static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String R="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String WP="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String STATE="word/paper-reader.json";
    private final LinkedHashMap<String,byte[]> parts=new LinkedHashMap<>();
    private Document document;
    private final ArrayList<Element> paragraphs=new ArrayList<>();
    private final HashMap<String,String> relationships=new HashMap<>();
    private final HashMap<String,Element> styles=new HashMap<>();
    private final ArrayList<ArrayList<Integer>> pageParagraphs=new ArrayList<>();
    private final ArrayList<StringBuilder> pageText=new ArrayList<>();
    private JSONObject edits=new JSONObject();
    private String annotationState="";
    boolean convertedLegacy, restricted;
    int pageWidth=595,pageHeight=842;
    private int marginLeft=50,marginRight=50,marginTop=50,marginBottom=50;
    private PdfDocument pdf;
    private PdfDocument.Page page;
    private float y;
    private static final int MAX_TOTAL=64*1024*1024, MAX_PART=24*1024*1024;

    static WordEngine open(File input) throws Exception {
        if(input.length()>64L*1024*1024) throw new IOException("Word 文档超过 64 MB，请先拆分文档。");
        WordEngine engine=new WordEngine();
        if("doc".equals(DocumentFormats.detect(input))) engine.importDoc(input);
        else engine.readZip(input);
        engine.parse(); return engine;
    }
    private void readZip(File input) throws Exception {
        int total=0,count=0;
        try(ZipInputStream zip=new ZipInputStream(new FileInputStream(input))) {
            ZipEntry entry; byte[] buffer=new byte[16384];
            while((entry=zip.getNextEntry())!=null) {
                if(++count>10000) throw new IOException("Word 包含过多附件");
                if(entry.isDirectory()) continue;
                if(parts.containsKey(entry.getName())) throw new IOException("Word 包中存在重复内容");
                ByteArrayOutputStream out=new ByteArrayOutputStream(); int n;
                while((n=zip.read(buffer))!=-1) {
                    total+=n; if(total>MAX_TOTAL||out.size()+n>MAX_PART) throw new IOException("Word 解压后过大，请先拆分文档");
                    out.write(buffer,0,n);
                }
                parts.put(entry.getName(),out.toByteArray());
            }
        }
        if(!parts.containsKey("word/document.xml")) throw new IOException("这不是有效的 DOCX 文档，或文档已加密");
    }
    static Document xml(byte[] data) throws Exception {
        // Reject DTDs before parsing as Android parsers do not all expose JAXP access controls.
        String probe=new String(data,StandardCharsets.UTF_8).replace("\u0000","").toUpperCase(Locale.ROOT);
        if(probe.contains("<!DOCTYPE")||probe.contains("<!ENTITY")) throw new IOException("文档 XML 包含不支持的外部实体");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true); factory.setExpandEntityReferences(false);
        DocumentBuilder builder=factory.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)->{throw new org.xml.sax.SAXException("禁止外部实体");});
        return builder.parse(new ByteArrayInputStream(data));
    }
    static byte[] bytes(Document doc) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); Transformer transformer=TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8"); transformer.transform(new DOMSource(doc),new StreamResult(out)); return out.toByteArray();
    }
    private void parse() throws Exception {
        document=xml(parts.get("word/document.xml"));
        if(document.getElementsByTagNameNS(W,"body").getLength()==0) throw new IOException("暂不支持此 Word XML 格式");
        // Own exported overlays are rebuilt from the editable model, never rasterized into the body twice.
        ArrayList<Node> own=new ArrayList<>(); NodeList props=document.getElementsByTagNameNS(WP,"docPr");
        for(int i=0;i<props.getLength();i++) if(((Element)props.item(i)).getAttribute("name").startsWith("PaperAnnotation")) {
            Node n=props.item(i); while(n!=null&&!(W.equals(n.getNamespaceURI())&&"r".equals(n.getLocalName()))) n=n.getParentNode();
            if(n!=null) own.add(n);
        }
        for(Node n:own) n.getParentNode().removeChild(n);
        if(parts.containsKey(STATE)) annotationState=new JSONObject(new String(parts.get(STATE),StandardCharsets.UTF_8)).optString("pages");
        NodeList ps=document.getElementsByTagNameNS(W,"p");
        if(ps.getLength()>20000) throw new IOException("Word 段落过多，请先拆分文档");
        for(int i=0;i<ps.getLength();i++) paragraphs.add((Element)ps.item(i));
        if(parts.containsKey("word/_rels/document.xml.rels")) {
            NodeList rels=xml(parts.get("word/_rels/document.xml.rels")).getDocumentElement().getChildNodes();
            for(int i=0;i<rels.getLength();i++) if(rels.item(i) instanceof Element) {
                Element e=(Element)rels.item(i);
                if(!"External".equals(e.getAttribute("TargetMode"))) relationships.put(e.getAttribute("Id"),e.getAttribute("Target"));
            }
        }
        if(parts.containsKey("word/styles.xml")) {
            NodeList list=xml(parts.get("word/styles.xml")).getElementsByTagNameNS(W,"style");
            for(int i=0;i<list.getLength();i++) { Element e=(Element)list.item(i); styles.put(e.getAttributeNS(W,"styleId"),e); }
        }
        if(parts.containsKey("word/settings.xml")) {
            NodeList locks=xml(parts.get("word/settings.xml")).getElementsByTagNameNS(W,"documentProtection");
            if(locks.getLength()>0) { String v=((Element)locks.item(0)).getAttributeNS(W,"enforcement"); restricted="1".equals(v)||"true".equals(v)||"on".equals(v); }
        }
        Element size=first(document.getDocumentElement(),"pgSz"),margins=first(document.getDocumentElement(),"pgMar");
        pageWidth=bounded(attrInt(size,"w",11900)/20,300,1600); pageHeight=bounded(attrInt(size,"h",16840)/20,300,2000);
        if(margins!=null) {
            marginLeft=bounded(attrInt(margins,"left",1000)/20,15,pageWidth/3); marginRight=bounded(attrInt(margins,"right",1000)/20,15,pageWidth/3);
            marginTop=bounded(attrInt(margins,"top",1000)/20,15,pageHeight/3); marginBottom=bounded(attrInt(margins,"bottom",1000)/20,15,pageHeight/3);
        }
    }
    static int bounded(int n,int min,int max) { return Math.max(min,Math.min(max,n)); }
    static Element first(Element parent,String local) {
        if(parent==null) return null; NodeList list=parent.getElementsByTagNameNS(W,local); return list.getLength()==0?null:(Element)list.item(0);
    }
    static int attrInt(Element e,String key,int fallback) { try {return Integer.parseInt(e.getAttributeNS(W,key));} catch(Exception ignored) {return fallback;} }
    private static boolean on(Element parent,String key) {
        Element e=first(parent,key); if(e==null) return false;
        String value=e.getAttributeNS(W,"val"); return !"0".equals(value)&&!"false".equals(value)&&!"off".equals(value);
    }
    int paragraphCount() { return paragraphs.size(); }
    String text(int index) { return paragraphText(paragraphs.get(index)); }
    static String paragraphText(Element p) {
        StringBuilder out=new StringBuilder(); collectText(p,out); return out.toString();
    }
    private static void collectText(Node node,StringBuilder out) {
        if(W.equals(node.getNamespaceURI())) {
            if("t".equals(node.getLocalName())) { out.append(node.getTextContent()); return; }
            if("tab".equals(node.getLocalName())) { out.append('\t'); return; }
            if("br".equals(node.getLocalName())||"cr".equals(node.getLocalName())) { out.append('\n'); return; }
            if("del".equals(node.getLocalName())) return;
        }
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling()) collectText(child,out);
    }
    String editState() { return edits.toString(); }
    String annotations() { return annotationState; }
    void applyEdits(String value) throws Exception {
        JSONObject changes=new JSONObject(value); Iterator<String> keys=changes.keys();
        while(keys.hasNext()) {
            String key=keys.next(); int index=Integer.parseInt(key);
            if(index<0||index>=paragraphs.size()) throw new IOException("Word 草稿段落不匹配");
            replaceText(paragraphs.get(index),changes.getString(key));
        }
        edits=changes;
    }
    private void replaceText(Element p,String value) {
        ArrayList<Node> remove=new ArrayList<>();
        for(String tag:new String[]{"t","br","cr","tab"}) { NodeList nodes=p.getElementsByTagNameNS(W,tag); for(int i=0;i<nodes.getLength();i++) remove.add(nodes.item(i)); }
        Element run=first(p,"r"); if(run==null) {run=document.createElementNS(W,"w:r");p.appendChild(run);}
        for(Node node:remove) node.getParentNode().removeChild(node);
        String[] lines=value.split("\n",-1);
        for(int i=0;i<lines.length;i++) {
            if(i>0) run.appendChild(document.createElementNS(W,"w:br"));
            Element text=document.createElementNS(W,"w:t");text.setAttributeNS("http://www.w3.org/XML/1998/namespace","xml:space","preserve"); text.setTextContent(lines[i]);run.appendChild(text);
        }
    }
    ArrayList<Integer> paragraphsOnPage(int pageIndex) { return new ArrayList<>(pageParagraphs.get(Math.max(0,Math.min(pageIndex,pageParagraphs.size()-1)))); }
    ArrayList<Integer> search(List<PdfEngine.Sheet> sheets,String query) {
        ArrayList<Integer> hits=new ArrayList<>(); String needle=query.toLowerCase(Locale.ROOT);
        for(int i=0;i<sheets.size();i++) {
            PdfEngine.Sheet sheet=sheets.get(i); boolean found=pageText.get(sheet.original).toString().toLowerCase(Locale.ROOT).contains(needle);
            for(PdfEngine.Mark mark:sheet.marks) if(mark.text.toLowerCase(Locale.ROOT).contains(needle)) found=true;
            if(found) hits.add(i);
        }
        return hits;
    }
    int render(File target) throws Exception {
        pageParagraphs.clear();pageText.clear();pdf=new PdfDocument();page=null;
        try {
            newPage(); Element body=first(document.getDocumentElement(),"body");
            renderChildren(body);
            pdf.finishPage(page);page=null;
            try(OutputStream out=new FileOutputStream(target)) {pdf.writeTo(out);}
            return pageParagraphs.size();
        } finally {if(page!=null) pdf.finishPage(page);pdf.close();pdf=null;page=null;}
    }
    private void newPage() throws IOException {
        if(page!=null) pdf.finishPage(page);
        if(pageParagraphs.size()>=1000) {page=null;throw new IOException("Word 排版超过 1000 页，请拆分文档");}
        page=pdf.startPage(new PdfDocument.PageInfo.Builder(pageWidth,pageHeight,pageParagraphs.size()+1).create());
        page.getCanvas().drawColor(Color.WHITE);pageParagraphs.add(new ArrayList<>());pageText.add(new StringBuilder());y=marginTop;
    }
    private void renderChildren(Element parent) throws Exception {
        for(Node node=parent.getFirstChild();node!=null;node=node.getNextSibling()) {
            if(!(node instanceof Element)) continue;Element e=(Element)node;
            if("p".equals(e.getLocalName())) renderParagraph(e,marginLeft,pageWidth-marginLeft-marginRight);
            else if("tbl".equals(e.getLocalName())) renderTable(e);
            else if(!"sectPr".equals(e.getLocalName())) renderChildren(e);
        }
    }
    private SpannableStringBuilder styled(Element p) {
        SpannableStringBuilder result=new SpannableStringBuilder(); Element pStyle=first(p,"pStyle");
        Element style=pStyle==null?null:styles.get(pStyle.getAttributeNS(W,"val"));
        NodeList runs=p.getElementsByTagNameNS(W,"r");
        for(int i=0;i<runs.getLength();i++) {
            Element run=(Element)runs.item(i); if(hasAncestor(run,"del",p)) continue;
            String text=paragraphText(run);if(text.isEmpty()) continue;
            int start=result.length();result.append(text);Element properties=first(run,"rPr"), defaults=first(style,"rPr");
            int size=attrInt(first(properties,"sz"),"val",attrInt(first(defaults,"sz"),"val",24));
            result.setSpan(new AbsoluteSizeSpan(bounded(size/2,6,72)),start,result.length(),0);
            int face=(on(properties,"b")||on(defaults,"b")?Typeface.BOLD:0)|(on(properties,"i")||on(defaults,"i")?Typeface.ITALIC:0);
            if(face!=0) result.setSpan(new StyleSpan(face),start,result.length(),0);
            if(on(properties,"u")) result.setSpan(new UnderlineSpan(),start,result.length(),0);
            Element color=first(properties,"color");
            if(color!=null) try {result.setSpan(new ForegroundColorSpan(Color.parseColor("#"+color.getAttributeNS(W,"val"))),start,result.length(),0);} catch(IllegalArgumentException ignored) { }
        }
        if(first(p,"numPr")!=null) result.insert(0,"•  ");
        return result;
    }
    private boolean hasAncestor(Node n,String tag,Node stop) {
        for(Node a=n.getParentNode();a!=null&&a!=stop;a=a.getParentNode()) if(tag.equals(a.getLocalName())) return true;return false;
    }
    private StaticLayout layout(Element p,int width) {
        TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(12);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        Element alignment=first(p,"jc");String value=alignment==null?"":alignment.getAttributeNS(W,"val");
        Layout.Alignment align="center".equals(value)?Layout.Alignment.ALIGN_CENTER:("right".equals(value)?Layout.Alignment.ALIGN_OPPOSITE:Layout.Alignment.ALIGN_NORMAL);
        SpannableStringBuilder text=styled(p);if(text.length()==0) text.append(" ");
        return StaticLayout.Builder.obtain(text,0,text.length(),paint,Math.max(30,width)).setAlignment(align).setIncludePad(false).setLineSpacing(3,1).build();
    }
    private void register(Element p,String text) {
        int index=paragraphs.indexOf(p); ArrayList<Integer> ids=pageParagraphs.get(pageParagraphs.size()-1);
        if(index>=0&&!ids.contains(index)) ids.add(index);pageText.get(pageText.size()-1).append(text).append('\n');
    }
    private void renderParagraph(Element p,int x,int width) throws Exception {
        if(on(first(p,"pPr"),"pageBreakBefore")&&y>marginTop) newPage();
        StaticLayout text=layout(p,width);
        for(int line=0;line<text.getLineCount();) {
            if(y+text.getLineBottom(line)-text.getLineTop(line)>pageHeight-marginBottom) newPage();
            int end=line+1; while(end<text.getLineCount()&&y+text.getLineBottom(end)-text.getLineTop(line)<=pageHeight-marginBottom) end++;
            int top=text.getLineTop(line),height=text.getLineBottom(end-1)-top;
            Canvas canvas=page.getCanvas();canvas.save();canvas.clipRect(x,y,x+width,y+height);canvas.translate(x,y-top);text.draw(canvas);canvas.restore();
            register(p,text.getText().subSequence(text.getLineStart(line),text.getLineEnd(end-1)).toString());y+=height;line=end;
            if(line<text.getLineCount()) newPage();
        }
        renderPictures(p,x,width);y+=6;
        NodeList breaks=p.getElementsByTagNameNS(W,"br");
        for(int i=0;i<breaks.getLength();i++) if("page".equals(((Element)breaks.item(i)).getAttributeNS(W,"type"))) {newPage();break;}
    }
    private void renderPictures(Element p,int x,int width) throws Exception {
        NodeList blips=p.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main","blip");
        for(int i=0;i<blips.getLength();i++) {
            String rel=((Element)blips.item(i)).getAttributeNS(R,"embed"),path=relationships.get(rel);if(path==null) continue;
            byte[] data=parts.get(path.startsWith("/")?path.substring(1):"word/"+path.replace("./",""));
            if(data==null) continue;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);
            if(options.outWidth<1||options.outHeight<1) {drawUnsupportedImage(x);continue;}
            int sample=1;while(options.outWidth/sample>1600||options.outHeight/sample>1600) sample*=2;
            options.inJustDecodeBounds=false;options.inSampleSize=sample;Bitmap bitmap=BitmapFactory.decodeByteArray(data,0,data.length,options);if(bitmap==null) continue;
            try {
                Element extent=null;Node ancestor=blips.item(i);
                while(ancestor!=null&&ancestor!=p) {if(ancestor instanceof Element&&WP.equals(ancestor.getNamespaceURI())) {NodeList ext=((Element)ancestor).getElementsByTagNameNS(WP,"extent");if(ext.getLength()>0) {extent=(Element)ext.item(0);break;}}ancestor=ancestor.getParentNode();}
                float desired=bitmap.getWidth();if(extent!=null) try {desired=Long.parseLong(extent.getAttribute("cx"))/12700f;} catch(Exception ignored) { }
                float w=Math.min(width,desired),h=w*bitmap.getHeight()/bitmap.getWidth();
                float max=pageHeight-marginTop-marginBottom-20;if(h>max) {w*=max/h;h=max;}
                if(y+h>pageHeight-marginBottom) newPage();
                page.getCanvas().drawBitmap(bitmap,null,new RectF(x,y,x+w,y+h),new Paint(Paint.FILTER_BITMAP_FLAG));register(p,"");y+=h+6;
            } finally {bitmap.recycle();}
        }
    }
    private void drawUnsupportedImage(int x) { Paint paint=new Paint();paint.setTextSize(10);paint.setColor(Color.GRAY);page.getCanvas().drawText("[图片格式暂不支持预览]",x,y+12,paint);y+=18; }
    private void renderTable(Element table) throws Exception {
        NodeList rows=table.getChildNodes();int width=pageWidth-marginLeft-marginRight;
        for(int r=0;r<rows.getLength();r++) {
            if(!(rows.item(r) instanceof Element)||!"tr".equals(rows.item(r).getLocalName())) continue;
            ArrayList<Element> cells=new ArrayList<>();for(Node n=rows.item(r).getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element&&"tc".equals(n.getLocalName())) cells.add((Element)n);
            if(cells.isEmpty()) continue;int cellWidth=width/cells.size();float height=12;
            for(Element cell:cells) {float h=12;NodeList ps=cell.getElementsByTagNameNS(W,"p");for(int k=0;k<ps.getLength();k++) h+=layout((Element)ps.item(k),cellWidth-12).getHeight()+6;height=Math.max(height,h);}
            // Large rows and image cells flow vertically, avoiding clipping or dropping content.
            if(height>pageHeight-marginTop-marginBottom||table.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main","blip").getLength()>0) {
                for(Element cell:cells) renderChildren(cell);continue;
            }
            if(y+height>pageHeight-marginBottom) newPage();float rowY=y;
            for(int c=0;c<cells.size();c++) {
                int x=marginLeft+c*cellWidth;Paint border=new Paint();border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(.6f);border.setColor(0xffaaaaaa);
                page.getCanvas().drawRect(x,rowY,x+cellWidth,rowY+height,border);float cellY=rowY+6;
                NodeList ps=cells.get(c).getElementsByTagNameNS(W,"p");
                for(int k=0;k<ps.getLength();k++) {Element p=(Element)ps.item(k);StaticLayout text=layout(p,cellWidth-12);Canvas canvas=page.getCanvas();canvas.save();canvas.translate(x+6,cellY);text.draw(canvas);canvas.restore();register(p,text.getText().toString());cellY+=text.getHeight()+6;}
            }
            y=rowY+height+4;
        }
    }
    void saveBase(File target) throws Exception { writePackage(target,parts); }
    void export(File target,List<PdfEngine.Sheet> sheets) throws Exception {
        if(restricted) throw new IOException("此 Word 文档已限制编辑");
        if(sheets.size()!=pageParagraphs.size()) throw new IOException("删除页面后的版本请保存为 PDF；Word 正文可单独保存");
        for(int i=0;i<sheets.size();i++) if(sheets.get(i).original!=i||sheets.get(i).rotation!=0) throw new IOException("页面旋转或调序后的版本请保存为 PDF；Word 正文可单独保存");
        LinkedHashMap<String,byte[]> output=new LinkedHashMap<>(parts);
        Document out=xml(bytes(document));NodeList ps=out.getElementsByTagNameNS(W,"p");
        Document rels=parts.containsKey("word/_rels/document.xml.rels")?xml(parts.get("word/_rels/document.xml.rels")):xml("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>".getBytes(StandardCharsets.UTF_8));
        // Replace previous Paper annotations, keeping unrelated document assets untouched.
        ArrayList<Node> oldRels=new ArrayList<>();for(Node n=rels.getDocumentElement().getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element&&((Element)n).getAttribute("Id").startsWith("PaperMark")) oldRels.add(n);
        for(Node n:oldRels) n.getParentNode().removeChild(n);
        for(int i=0;i<sheets.size();i++) {
            if(sheets.get(i).marks.isEmpty()) continue;
            if(pageParagraphs.get(i).isEmpty()) throw new IOException("此页没有可锚定的 Word 段落，请将批注版本保存为 PDF");
            for(int earlier=0;earlier<i;earlier++) if(pageParagraphs.get(earlier).contains(pageParagraphs.get(i).get(0)))
                throw new IOException("本页正文段落跨越多页，Word 批注无法准确定位。请将批注版本另存为 PDF。");
            Bitmap bitmap=Bitmap.createBitmap(pageWidth*2,pageHeight*2,Bitmap.Config.ARGB_8888);
            try {PdfEngine.paintMarks(new Canvas(bitmap),sheets.get(i).marks,bitmap.getWidth(),bitmap.getHeight());ByteArrayOutputStream image=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,image);output.put("word/media/paper-mark-"+i+".png",image.toByteArray());} finally {bitmap.recycle();}
            String id="PaperMark"+i;Element rel=rels.createElementNS(rels.getDocumentElement().getNamespaceURI(),"Relationship");rel.setAttribute("Id",id);rel.setAttribute("Type",R+"/image");rel.setAttribute("Target","media/paper-mark-"+i+".png");rels.getDocumentElement().appendChild(rel);
            Node drawing=xml(overlay(id,i,pageWidth,pageHeight).getBytes(StandardCharsets.UTF_8)).getDocumentElement();
            ps.item(pageParagraphs.get(i).get(0)).appendChild(out.importNode(drawing,true));
        }
        output.put("word/document.xml",bytes(out));output.put("word/_rels/document.xml.rels",bytes(rels));
        output.put(STATE,new JSONObject().put("pages",PdfEngine.encode(sheets)).toString().getBytes(StandardCharsets.UTF_8));
        Document types=xml(output.get("[Content_Types].xml"));ensureType(types,"png","image/png");ensureType(types,"json","application/json");output.put("[Content_Types].xml",bytes(types));
        writePackage(target,output);
        // Parse the exported package before it may replace an original.
        WordEngine.open(target);
    }
    private static void ensureType(Document doc,String extension,String mime) {
        NodeList list=doc.getDocumentElement().getChildNodes();for(int i=0;i<list.getLength();i++) if(list.item(i) instanceof Element&&extension.equals(((Element)list.item(i)).getAttribute("Extension"))) return;
        Element e=doc.createElementNS(doc.getDocumentElement().getNamespaceURI(),"Default");e.setAttribute("Extension",extension);e.setAttribute("ContentType",mime);doc.getDocumentElement().appendChild(e);
    }
    private static String overlay(String id,int index,int width,int height) {
        long w=width*12700L,h=height*12700L;
        return "<w:r xmlns:w=\""+W+"\" xmlns:r=\""+R+"\" xmlns:wp=\""+WP+"\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"251659264\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>0</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>0</wp:posOffset></wp:positionV><wp:extent cx=\""+w+"\" cy=\""+h+"\"/><wp:wrapNone/><wp:docPr id=\""+(100000+index)+"\" name=\"PaperAnnotation"+index+"\"/><wp:cNvGraphicFramePr/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"批注\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\""+id+"\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\""+w+"\" cy=\""+h+"\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
    }
    private static void writePackage(File target,Map<String,byte[]> values) throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(target))) {for(Map.Entry<String,byte[]> e:values.entrySet()) {zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}
    }
    private void importDoc(File input) throws Exception {
        convertedLegacy=true;StringBuilder body=new StringBuilder(),rels=new StringBuilder();
        try(InputStream in=new FileInputStream(input);HWPFDocument doc=new HWPFDocument(in)) {
            if(doc.getDocProperties().isFProtEnabled()) restricted=true;
            Range range=doc.getRange();if(range.numParagraphs()>20000) throw new IOException("Word 段落过多");
            for(int i=0;i<range.numParagraphs();i++) {
                Paragraph paragraph=range.getParagraph(i);
                if(paragraph.isInTable()) {
                    Table table=range.getTable(paragraph);body.append("<w:tbl>");
                    for(int r=0;r<table.numRows();r++) {TableRow row=table.getRow(r);body.append("<w:tr>");
                        for(int c=0;c<row.numCells();c++) {TableCell cell=row.getCell(c);body.append("<w:tc>");for(int p=0;p<cell.numParagraphs();p++) body.append(docParagraph(doc,cell.getParagraph(p),rels));body.append("</w:tc>");}
                        body.append("</w:tr>");
                    }
                    body.append("</w:tbl>");i+=table.numParagraphs()-1;
                } else {
                    body.append(docParagraph(doc,paragraph,rels));
                }
            }
        } catch(org.apache.poi.EncryptedDocumentException e) {throw new IOException("此 DOC 已加密，请先在 Word 中解密并另存为 DOCX",e);}
        parts.put("word/document.xml",("<w:document xmlns:w=\""+W+"\" xmlns:r=\""+R+"\"><w:body>"+body+"<w:sectPr/></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
        parts.put("word/_rels/document.xml.rels",("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+rels+"</Relationships>").getBytes(StandardCharsets.UTF_8));
        parts.put("[Content_Types].xml",("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/><Default Extension=\"gif\" ContentType=\"image/gif\"/><Default Extension=\"bmp\" ContentType=\"image/bmp\"/><Default Extension=\"emf\" ContentType=\"image/x-emf\"/><Default Extension=\"wmf\" ContentType=\"image/x-wmf\"/><Default Extension=\"pict\" ContentType=\"image/x-pict\"/><Default Extension=\"tiff\" ContentType=\"image/tiff\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>").getBytes(StandardCharsets.UTF_8));
        parts.put("_rels/.rels",("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\""+R+"/officeDocument\" Target=\"word/document.xml\"/></Relationships>").getBytes(StandardCharsets.UTF_8));
    }
    private String docParagraph(HWPFDocument doc,Paragraph paragraph,StringBuilder rels) {
        StringBuilder out=new StringBuilder("<w:p>");
        for(int j=0;j<paragraph.numCharacterRuns();j++) {
            CharacterRun run=paragraph.getCharacterRun(j);
            if(doc.getPicturesTable().hasPicture(run)) {
                org.apache.poi.hwpf.usermodel.Picture picture=doc.getPicturesTable().extractPicture(run,true);String id="legacyPic"+parts.size(),extension=picture.suggestFileExtension();
                out.append(legacyDrawing(picture.getContent(),extension,picture.getDxaGoal()/20,picture.getDyaGoal()/20,rels));
            } else if(doc.getPicturesTable().hasEscherPicture(run)) {
                OfficeDrawing shape=doc.getOfficeDrawingsMain().getOfficeDrawingAt(run.getStartOffset());
                if(shape!=null&&shape.getPictureData()!=null) {
                    byte[] data=shape.getPictureData();BitmapFactory.Options probe=new BitmapFactory.Options();probe.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,probe);
                    String extension="image/png".equals(probe.outMimeType)?"png":"image/jpeg".equals(probe.outMimeType)?"jpg":"image/gif".equals(probe.outMimeType)?"gif":"bmp";
                    out.append(legacyDrawing(data,extension,(shape.getRectangleRight()-shape.getRectangleLeft())/20,(shape.getRectangleBottom()-shape.getRectangleTop())/20,rels));
                }
            }
            String value=run.text().replace("\r","").replace("\u0007","").replace("\u0001","");
            out.append("<w:r><w:rPr><w:sz w:val=\"").append(run.getFontSize()).append("\"/>");if(run.isBold()) out.append("<w:b/>");if(run.isItalic()) out.append("<w:i/>");out.append("</w:rPr>");
            String[] lines=value.split("[\u000b\u000c\n]",-1);for(int k=0;k<lines.length;k++) {if(k>0) out.append("<w:br/>");out.append("<w:t xml:space=\"preserve\">").append(escape(lines[k])).append("</w:t>");}
            out.append("</w:r>");
        }
        return out.append("</w:p>").toString();
    }
    private String legacyDrawing(byte[] data,String extension,int width,int height,StringBuilder rels) {
        String id="legacyPic"+parts.size();if(extension==null||extension.isEmpty()) extension="bin";
        String target="media/"+id+"."+extension;parts.put("word/"+target,data);
        rels.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(R).append("/image\" Target=\"").append(target).append("\"/>");
        return overlay(id,parts.size(),Math.max(30,width),Math.max(30,height)).replaceAll("<wp:anchor[^>]*>","<wp:inline>").replace("</wp:anchor>","</wp:inline>")
            .replaceAll("<wp:simplePos[^>]*/>","").replaceAll("<wp:positionH.*?</wp:positionH>","").replaceAll("<wp:positionV.*?</wp:positionV>","").replace("<wp:wrapNone/>","").replace("PaperAnnotation","LegacyPicture");
    }
    private static String escape(String value) {return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]","");}
}
