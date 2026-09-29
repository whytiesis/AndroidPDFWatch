package cn.paperpdf.reader;

import android.test.InstrumentationTestCase;
import android.content.*;
import android.net.Uri;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class WordIntegrationTest extends InstrumentationTestCase {
    private Context context;
    @Override protected void setUp() throws Exception {super.setUp();context=getInstrumentation().getTargetContext();context.getSharedPreferences("MainActivity",0).edit().clear().commit();}
    private void entry(ZipOutputStream zip,String name,byte[] value) throws Exception {zip.putNextEntry(new ZipEntry(name));zip.write(value);zip.closeEntry();}
    private void entry(ZipOutputStream zip,String name,String value) throws Exception {entry(zip,name,value.getBytes("UTF-8"));}
    private File fixture(String name) throws Exception {
        File file=new File(context.getCacheDir(),name);
        String picture="<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"1270000\" cy=\"635000\"/><wp:docPr id=\"1\" name=\"sample\"/><a:graphic><a:graphicData><a:blip r:embed=\"pic1\"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
        String doc="<w:document xmlns:w=\""+WordEngine.W+"\" xmlns:r=\""+WordEngine.R+"\" xmlns:wp=\""+WordEngine.WP+"\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><w:body>"+
            "<w:p><w:r><w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr><w:t>纸阅 Word 阅读与修改</w:t></w:r></w:p>"+
            "<w:p><w:r><w:t>这是可修改的中文正文。English body for search.</w:t></w:r></w:p>"+
            "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格单元格 A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>数值 123</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"+picture+
            "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p><w:p><w:r><w:t>Second page 第二页正文</w:t></w:r></w:p>"+
            "<w:sectPr><w:pgSz w:w=\"11900\" w:h=\"16840\"/></w:sectPr></w:body></w:document>";
        Bitmap bitmap=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);ByteArrayOutputStream png=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,png);bitmap.recycle();
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))) {
            entry(zip,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            entry(zip,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"r1\" Type=\""+WordEngine.R+"/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            entry(zip,"word/document.xml",doc);
            entry(zip,"word/_rels/document.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"pic1\" Type=\""+WordEngine.R+"/image\" Target=\"media/picture.png\"/></Relationships>");
            entry(zip,"word/media/picture.png",png.toByteArray());entry(zip,"word/header1.xml","<w:hdr xmlns:w=\""+WordEngine.W+"\"><w:p><w:r><w:t>保留的页眉</w:t></w:r></w:p></w:hdr>");
        }return file;
    }
    private ArrayList<PdfEngine.Sheet> sheets(int count) {ArrayList<PdfEngine.Sheet> pages=new ArrayList<>();for(int i=0;i<count;i++) pages.add(new PdfEngine.Sheet(i));return pages;}
    public void testIndependentDocxProducerRoundTrip() throws Exception {
        File original=new File(context.getCacheDir(),"word-reference.docx");try(InputStream in=getInstrumentation().getContext().getAssets().open("word-reference.docx");OutputStream out=new FileOutputStream(original)) {byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1) out.write(b,0,n);}
        WordEngine word=WordEngine.open(original);word.applyEdits(new JSONObject().put("1","独立生成文档的中文修改结果").toString());int count=word.render(new File(context.getCacheDir(),"word-reference-preview.pdf"));
        assertEquals(2,count);assertEquals(Arrays.asList(1),word.search(sheets(count),"知识与记录"));
        File output=new File(context.getCacheDir(),"word-reference-edited.docx");word.export(output,sheets(count));assertEquals("独立生成文档的中文修改结果",WordEngine.open(output).text(1));assertTrue(Arrays.equals(part(original,"word/header1.xml"),part(output,"word/header1.xml")));
    }
    private byte[] part(File f,String path) throws Exception {try(ZipFile zip=new ZipFile(f);InputStream in=zip.getInputStream(zip.getEntry(path));ByteArrayOutputStream out=new ByteArrayOutputStream()) {byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1) out.write(b,0,n);return out.toByteArray();}}
    public void testDocxChineseTableImagePaginationAndSearch() throws Exception {
        WordEngine word=WordEngine.open(fixture("render-word.docx"));File pdf=new File(context.getCacheDir(),"render-word.pdf");int count=word.render(pdf);assertEquals(2,count);
        assertEquals(Arrays.asList(0),word.search(sheets(count),"表格单元格"));assertEquals(Arrays.asList(1),word.search(sheets(count),"第二页"));
        try(PdfRenderer renderer=new PdfRenderer(ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY))) {
            Bitmap bitmap=PdfEngine.render(renderer,new PdfEngine.Sheet(0),595);int red=0,dark=0;
            for(int y=0;y<bitmap.getHeight();y++) for(int x=0;x<bitmap.getWidth();x++) {int c=bitmap.getPixel(x,y);if(Color.red(c)>200&&Color.green(c)<70&&Color.blue(c)<70) red++;if(Color.red(c)<90&&Color.green(c)<90) dark++;}
            assertTrue("embedded image must be rendered",red>3000);assertTrue("text and table must be rendered",dark>700);bitmap.recycle();
        }
    }
    public void testWordBodyRoundTripPreservesAttachmentsAndEditableText() throws Exception {
        File original=fixture("body-source.docx");WordEngine word=WordEngine.open(original);word.applyEdits(new JSONObject().put("1","修改后的中文正文\n新增的一行 & < >").put("2","已修改表格").toString());
        int count=word.render(new File(context.getCacheDir(),"body.pdf"));File output=new File(context.getCacheDir(),"body-edited.docx");word.export(output,sheets(count));
        WordEngine reopened=WordEngine.open(output);assertEquals("修改后的中文正文\n新增的一行 & < >",reopened.text(1));assertEquals("已修改表格",reopened.text(2));
        assertTrue(Arrays.equals(part(original,"word/media/picture.png"),part(output,"word/media/picture.png")));assertTrue(Arrays.equals(part(original,"word/header1.xml"),part(output,"word/header1.xml")));
        assertEquals(2,reopened.render(new File(context.getCacheDir(),"reopened.pdf")));
    }
    public void testWordAnnotationSaveReopenAndNoDuplicateOverlay() throws Exception {
        WordEngine word=WordEngine.open(fixture("annotations.docx"));int count=word.render(new File(context.getCacheDir(),"annotations.pdf"));ArrayList<PdfEngine.Sheet> pages=sheets(count);
        PdfEngine.Mark m=new PdfEngine.Mark();m.type=PdfEngine.TEXT;m.text="中文批注";m.width=.035f;m.color=Color.BLUE;m.points.add(new PointF(.3f,.4f));pages.get(0).marks.add(m);
        File out=new File(context.getCacheDir(),"annotations-export.docx");word.export(out,pages);assertTrue(part(out,"word/media/paper-mark-0.png").length>100);
        WordEngine again=WordEngine.open(out);assertEquals(PdfEngine.encode(pages),again.annotations());assertEquals(2,again.render(new File(context.getCacheDir(),"annotations-again.pdf")));
        File twice=new File(context.getCacheDir(),"annotations-twice.docx");again.export(twice,PdfEngine.decode(again.annotations()));
        String document=new String(part(twice,"word/document.xml"),"UTF-8");assertEquals(1,document.split("PaperAnnotation0",-1).length-1);assertEquals("中文批注",PdfEngine.decode(WordEngine.open(twice).annotations()).get(0).marks.get(0).text);
    }
    public void testLegacyDocReadsAndConvertsToEditableDocx() throws Exception {
        File doc=new File(context.getCacheDir(),"legacy.doc");try(InputStream in=getInstrumentation().getContext().getAssets().open("poi-simple.doc");OutputStream out=new FileOutputStream(doc)) {byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1) out.write(b,0,n);}
        WordEngine word=WordEngine.open(doc);assertTrue(word.convertedLegacy);assertTrue(word.paragraphCount()>0);word.applyEdits(new JSONObject().put("0","旧 DOC 修改为中文 DOCX").toString());
        int count=word.render(new File(context.getCacheDir(),"legacy.pdf"));File converted=new File(context.getCacheDir(),"legacy.docx");word.export(converted,sheets(count));assertEquals("旧 DOC 修改为中文 DOCX",WordEngine.open(converted).text(0));
    }
    public void testMalformedPackageAndExternalEntitiesRejected() throws Exception {
        File invalid=new File(context.getCacheDir(),"not-word.docx");try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(invalid))) {entry(zip,"other.xml","<x/>");}
        try {WordEngine.open(invalid);fail("invalid package accepted");}catch(IOException expected) { }
        try {WordEngine.xml("<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///etc/passwd'>]><x>&y;</x>".getBytes("UTF-8"));fail("external entity accepted");}catch(IOException expected) { }
    }
    public void testLegacyDocTableAndImageSurviveConversion() throws Exception {
        for(String fixture:new String[]{"PngPicture.doc","simple-table.doc"}) {
            File input=new File(context.getCacheDir(),fixture);try(InputStream in=getInstrumentation().getContext().getAssets().open("poi-"+fixture);OutputStream out=new FileOutputStream(input)) {byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1) out.write(b,0,n);}
            WordEngine word=WordEngine.open(input);int count=word.render(new File(context.getCacheDir(),fixture+".pdf"));assertTrue(count>0);
            File output=new File(context.getCacheDir(),fixture+".docx");word.export(output,sheets(count));String xml=new String(part(output,"word/document.xml"),"UTF-8");
            if(fixture.contains("Picture")) {assertTrue(xml.contains("LegacyPicture"));try(ZipFile zip=new ZipFile(output)) {assertTrue(Collections.list(zip.entries()).stream().anyMatch(e->e.getName().startsWith("word/media/")));}}
            else assertTrue(xml.contains("w:tbl"));
        }
    }
    public void testWordPageTransformsExportToPdfWithoutChangingDocx() throws Exception {
        File original=fixture("transforms.docx"),pdf=new File(context.getCacheDir(),"transforms.pdf");WordEngine word=WordEngine.open(original);word.render(pdf);ArrayList<PdfEngine.Sheet> pages=sheets(2);Collections.swap(pages,0,1);PdfEngine.rotate(pages.get(0));pages.remove(1);
        File export=new File(context.getCacheDir(),"transforms-out.pdf");PdfEngine.export(pdf,pages,export);
        try(PdfRenderer renderer=new PdfRenderer(ParcelFileDescriptor.open(export,ParcelFileDescriptor.MODE_READ_ONLY))) {assertEquals(1,renderer.getPageCount());}
        try {word.export(new File(context.getCacheDir(),"bad-transform.docx"),pages);fail("silently lost page changes");}catch(IOException expected) { }
    }
    public void testWordFolderMimeAndExtensionRecognition() {
        assertTrue(DocumentFormats.supported("报告.DOCX","application/octet-stream"));assertTrue(DocumentFormats.supported("旧报告.doc","application/octet-stream"));assertTrue(DocumentFormats.supported("无后缀",DocumentFormats.DOCX));assertFalse(DocumentFormats.supported("archive.zip","application/zip"));
        ArrayList<PdfFolderIndex.Entry> found=PdfFolderIndex.scan(context.getContentResolver(),Uri.parse("content://cn.paperpdf.reader.testdocs/tree/word-root"));assertEquals(3,found.size());
    }
    private Object field(Object obj,String name) {try {java.lang.reflect.Field f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(obj);}catch(Exception e) {throw new AssertionError(e);}}
    private void invoke(Object obj,String name,Class<?>[] types,Object... args) {try {java.lang.reflect.Method m=obj.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(obj,args);}catch(Exception e) {throw new AssertionError(e);}}
    private interface Ready {boolean check();}
    private void await(Ready ready) throws Exception {for(int i=0;i<200;i++) {getInstrumentation().waitForIdleSync();if(ready.check()) return;Thread.sleep(100);}fail("Timed out waiting for Word UI");}
    private TextView find(View view,String text) {if(view instanceof TextView&&((TextView)view).getText().toString().contains(text)) return (TextView)view;if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {TextView result=find(((ViewGroup)view).getChildAt(i),text);if(result!=null) return result;}return null;}
    public void testActivityBodyUndoRedoDraftRestoreAndOverwrite() throws Exception {
        File original=fixture("activity-"+System.nanoTime()+".docx");Intent intent=new Intent(context,MainActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(original)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        MainActivity activity=(MainActivity)getInstrumentation().startActivitySync(intent);
        try {
            await(()->field(activity,"word")!=null&&!(Boolean)field(activity,"busy")&&find(activity.getWindow().getDecorView(),"编辑正文")!=null);
            String checkpoint;try {java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("editSnapshot");m.setAccessible(true);checkpoint=(String)m.invoke(activity);}catch(Exception e) {throw new AssertionError(e);}
            String edits=new JSONObject().put("1","手机中改写的正文").toString();
            getInstrumentation().runOnMainSync(()->invoke(activity,"rebuildWord",new Class<?>[]{String.class,String.class,String.class},edits,null,checkpoint));
            await(()->!(Boolean)field(activity,"busy")&&((WordEngine)field(activity,"word")).text(1).equals("手机中改写的正文"));
            String id=(String)field(activity,"draftId");assertTrue(new DraftStore(context).dirty(id));
            getInstrumentation().runOnMainSync(()->invoke(activity,"undo",new Class<?>[]{}));await(()->!(Boolean)field(activity,"busy")&&((WordEngine)field(activity,"word")).text(1).contains("English body"));
            getInstrumentation().runOnMainSync(()->invoke(activity,"redo",new Class<?>[]{}));await(()->!(Boolean)field(activity,"busy")&&((WordEngine)field(activity,"word")).text(1).equals("手机中改写的正文"));
            getInstrumentation().runOnMainSync(()->invoke(activity,"restoreDraft",new Class<?>[]{String.class},id));await(()->!(Boolean)field(activity,"busy")&&((WordEngine)field(activity,"word")).text(1).equals("手机中改写的正文"));
            getInstrumentation().runOnMainSync(()->invoke(activity,"exportWord",new Class<?>[]{boolean.class,boolean.class},false,true));await(()->!(Boolean)field(activity,"busy")&&"已写入原文件".equals(field(activity,"saveStatus")));
            assertEquals("手机中改写的正文",WordEngine.open(original).text(1));assertFalse(new DraftStore(context).dirty(id));
        } finally {getInstrumentation().runOnMainSync(activity::finish);}
    }
    public void testWordShareProviderMimeAndNames() throws Exception {
        File directory=new File(context.getCacheDir(),"shared");directory.mkdirs();File source=fixture("share-source.docx"),shared=new File(directory,"paper-word.docx");try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(shared)) {byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1) out.write(b,0,n);}
        Uri uri=Uri.parse("content://cn.paperpdf.reader.share/paper-word.docx");assertEquals(DocumentFormats.DOCX,context.getContentResolver().getType(uri));try(InputStream in=context.getContentResolver().openInputStream(uri)) {assertEquals('P',in.read());}
    }
    private android.view.accessibility.AccessibilityNodeInfo nodeText(String text) {
        android.view.accessibility.AccessibilityNodeInfo root=getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root==null) return null;
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(text);return nodes.isEmpty()?null:nodes.get(0);
    }
    private void clickText(String text) throws Exception {await(()->nodeText(text)!=null);android.view.accessibility.AccessibilityNodeInfo node=nodeText(text);while(node!=null&&!node.isClickable()) node=node.getParent();assertNotNull(node);assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));}
    private android.view.accessibility.AccessibilityNodeInfo inputNode(android.view.accessibility.AccessibilityNodeInfo node) {
        if(node==null) return null;if(node.isEditable()) return node;for(int i=0;i<node.getChildCount();i++) {android.view.accessibility.AccessibilityNodeInfo result=inputNode(node.getChild(i));if(result!=null) return result;}return null;
    }
    private void screenshot(String name) throws Exception {Bitmap b=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);try(OutputStream out=new FileOutputStream(new File(context.getCacheDir(),name))) {b.compress(Bitmap.CompressFormat.PNG,100,out);}finally {b.recycle();}}
    public void testBodyEditorThroughVisibleUiAndWordContinuousReading() throws Exception {
        File original=fixture("ui-"+System.nanoTime()+".docx");Intent intent=new Intent(context,MainActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(original)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        MainActivity activity=(MainActivity)getInstrumentation().startActivitySync(intent);
        try {
            await(()->field(activity,"word")!=null&&!(Boolean)field(activity,"busy")&&field(activity,"displayed")!=null);screenshot("word-160-reader.png");
            getInstrumentation().runOnMainSync(()->find(activity.getWindow().getDecorView(),"编辑正文").performClick());clickText("这是可修改的中文正文");
            await(()->inputNode(getInstrumentation().getUiAutomation().getRootInActiveWindow())!=null);
            android.os.Bundle args=new android.os.Bundle();args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"通过手机正文编辑器修改成功。\n新增内容仍是 Word 文字。");
            assertTrue(inputNode(getInstrumentation().getUiAutomation().getRootInActiveWindow()).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,args));screenshot("word-160-body-editor.png");clickText("应用修改");
            await(()->!(Boolean)field(activity,"busy")&&((WordEngine)field(activity,"word")).text(1).startsWith("通过手机"));
            getInstrumentation().runOnMainSync(()->invoke(activity,"more",new Class<?>[]{}));clickText("切换连续滚动阅读");
            await(()->field(activity,"continuousView")!=null&&((ContinuousView)field(activity,"continuousView")).visiblePagesReady());screenshot("word-160-continuous.png");
            assertNotNull(find(activity.getWindow().getDecorView(),"编辑本页"));
        } finally {getInstrumentation().runOnMainSync(activity::finish);}
    }
}
