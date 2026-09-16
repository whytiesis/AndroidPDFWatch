package cn.paperpdf.reader;

import android.test.InstrumentationTestCase;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.net.Uri;
import android.view.*;
import android.widget.TextView;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.encryption.*;
import java.io.*;
import java.util.*;

/** Tests run inside a real Android system so PdfRenderer, fonts and saved files are exercised. */
public class PdfIntegrationTest extends InstrumentationTestCase {
    private Context context;
    @Override protected void setUp() throws Exception {
        super.setUp(); getInstrumentation().waitForIdleSync(); context=getInstrumentation().getTargetContext(); PDFBoxResourceLoader.init(context);
        context.getSharedPreferences("MainActivity",0).edit().clear().commit();
    }
    private File fixture(String name,int pages) throws Exception {
        File f=new File(context.getCacheDir(),name);
        try(PDDocument doc=new PDDocument()) {
            for(int i=0;i<pages;i++) {
                PDPage p=new PDPage(new PDRectangle(400,600));
                p.setCropBox(new PDRectangle(40,60,300,440)); p.setRotation(i*90); doc.addPage(p);
                try(PDPageContentStream s=new PDPageContentStream(doc,p)) {
                    s.beginText(); s.setFont(PDType1Font.HELVETICA,18); s.newLineAtOffset(65,430); s.showText("Page "+(i+1)+" searchable"); s.endText();
                }
            }
            doc.save(f);
        }
        return f;
    }
    public void testExportMarksOnAllRotationsAndCropOffsets() throws Exception {
        File source=fixture("rotations.pdf",4),out=new File(context.getCacheDir(),"annotated.pdf");
        ArrayList<PdfEngine.Sheet> pages=new ArrayList<>();
        for(int i=0;i<4;i++) {
            PdfEngine.Sheet s=new PdfEngine.Sheet(i); PdfEngine.Mark mark=new PdfEngine.Mark();
            mark.type=PdfEngine.INK; mark.color=Color.MAGENTA; mark.width=.06f; mark.points.add(new PointF(.25f,.35f)); s.marks.add(mark);
            PdfEngine.Mark text=new PdfEngine.Mark(); text.type=PdfEngine.TEXT; text.color=Color.BLUE; text.width=.04f; text.text="中文批注\n第二行"; text.points.add(new PointF(.15f,.65f)); s.marks.add(text); pages.add(s);
        }
        PdfEngine.export(source,pages,out); assertTrue(out.length()>source.length());
        try(PdfRenderer renderer=new PdfRenderer(ParcelFileDescriptor.open(out,ParcelFileDescriptor.MODE_READ_ONLY))) {
            assertEquals(4,renderer.getPageCount());
            for(int i=0;i<4;i++) {
                Bitmap b=PdfEngine.render(renderer,new PdfEngine.Sheet(i),900);
                int pixel=b.getPixel(Math.round(b.getWidth()*.25f),Math.round(b.getHeight()*.35f));
                assertTrue("mark rotation "+i+" pixel="+Integer.toHexString(pixel),Color.red(pixel)>180&&Color.blue(pixel)>180&&Color.green(pixel)<90);
                int blue=0; for(int y=(int)(b.getHeight()*.55f);y<b.getHeight()*.8f;y++) for(int x=0;x<b.getWidth();x++) {
                    int c=b.getPixel(x,y); if(Color.blue(c)>150&&Color.red(c)<100&&Color.green(c)<100) blue++;
                }
                assertTrue("Chinese text rendered at rotation "+i,blue>100); b.recycle();
            }
        }
    }
    public void testPageDeleteReorderRotateSearchAndStateRoundTrip() throws Exception {
        File source=fixture("structure.pdf",4),out=new File(context.getCacheDir(),"structure-output.pdf");
        ArrayList<PdfEngine.Sheet> pages=new ArrayList<>(); pages.add(new PdfEngine.Sheet(2)); pages.add(new PdfEngine.Sheet(0));
        PdfEngine.rotate(pages.get(0));
        String state=PdfEngine.encode(pages); assertEquals(state,PdfEngine.encode(PdfEngine.decode(state)));
        assertEquals(Arrays.asList(0),PdfEngine.search(source,pages,"Page 3"));
        PdfEngine.export(source,pages,out);
        try(PDDocument doc=PDDocument.load(out)) { assertEquals(2,doc.getNumberOfPages()); assertEquals(270,doc.getPage(0).getRotation()); }
        ArrayList<PdfEngine.Sheet> exported=new ArrayList<>(); exported.add(new PdfEngine.Sheet(0)); exported.add(new PdfEngine.Sheet(1));
        assertEquals(Arrays.asList(1),PdfEngine.search(out,exported,"Page 1"));
    }
    public void testEncryptionRejectsWrongPasswordAndPreservesPermission() throws Exception {
        File original=fixture("encryption-source.pdf",1),locked=new File(context.getCacheDir(),"locked.pdf");
        try(PDDocument doc=PDDocument.load(original)) {
            AccessPermission p=new AccessPermission(); p.setCanModify(false);
            StandardProtectionPolicy policy=new StandardProtectionPolicy("owner-secret","1234",p); policy.setEncryptionKeyLength(128); doc.protect(policy); doc.save(locked);
        }
        try(PDDocument ignored=PDDocument.load(locked,"wrong")) { fail("wrong password accepted"); }
        catch(InvalidPasswordException expected) { }
        try(PDDocument doc=PDDocument.load(locked,"1234")) { assertEquals(1,doc.getNumberOfPages()); assertFalse(doc.getCurrentAccessPermission().canModify()); }
    }
    public void testChatFolderIndexFindsNestedPdfAndPdfWithGenericMime() {
        Uri tree=Uri.parse("content://cn.paperpdf.reader.testdocs/tree/root");
        ArrayList<PdfFolderIndex.Entry> files=PdfFolderIndex.scan(context.getContentResolver(),tree);
        assertEquals(3,files.size());
        assertEquals("微信账单.pdf",files.get(0).name);
        assertEquals("嵌套文档.pdf",files.get(1).name);
        assertEquals("QQ直接文件.PDF",files.get(2).name);
        assertEquals("qq-pdf",files.get(2).uri.getLastPathSegment());
    }
    private TextView find(View view,String text) {
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(text)) return (TextView)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) { TextView found=find(((ViewGroup)view).getChildAt(i),text); if(found!=null) return found; }
        return null;
    }
    private Object field(Object object,String name) {
        try { java.lang.reflect.Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch(Exception e) { throw new AssertionError(e); }
    }
    private void call(Object object,String name) {
        try { java.lang.reflect.Method method=object.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(object); }
        catch(Exception e) { throw new AssertionError(e); }
    }
    private interface Condition { boolean check(); }
    private void await(Condition condition) throws Exception {
        long until=System.currentTimeMillis()+30000; boolean[] result={false};
        while(System.currentTimeMillis()<until&&!result[0]) { getInstrumentation().runOnMainSync(()->result[0]=condition.check()); if(!result[0]) Thread.sleep(100); }
        assertTrue("condition timed out",result[0]);
    }
    private MainActivity open(File file) throws Exception {
        Intent intent=new Intent(context,MainActivity.class).setAction(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file),"application/pdf")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        MainActivity a=(MainActivity)getInstrumentation().startActivitySync(intent);
        await(()->field(a,"pager")!=null&&!(Boolean)field(a,"busy")); return a;
    }
    private void screenshot(String name) throws Exception {
        getInstrumentation().waitForIdleSync();
        Thread.sleep(650); // Wait for SurfaceFlinger rotation/system-bar animations, beyond layout completion.
        try(ParcelFileDescriptor pipe=getInstrumentation().getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/"+name+".png");
            InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {byte[] buffer=new byte[1024];while(in.read(buffer)!=-1){} }
    }
    private File proseFixture() throws Exception {
        return proseFixture("continuous-wide-margins.pdf");
    }
    private File proseFixture(String name) throws Exception {
        File file=new File(context.getCacheDir(),name);
        try(PDDocument doc=new PDDocument()) {
            for(int i=0;i<8;i++) {
                PDPage p=new PDPage(new PDRectangle(400,600)); doc.addPage(p);
                try(PDPageContentStream stream=new PDPageContentStream(doc,p)) {
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA,18); stream.newLineAtOffset(60,540); stream.showText("Reading page "+(i+1)); stream.endText();
                    for(int y=110;y<500;y+=25) {stream.addRect(60,y,280,2);stream.fill();}
                }
            }
            doc.save(file);
        }
        return file;
    }
    private void touch(View view,int action,float x,float y,long downTime) {
        getInstrumentation().runOnMainSync(()->{
            MotionEvent event=MotionEvent.obtain(downTime,android.os.SystemClock.uptimeMillis(),action,x,y,0);
            view.dispatchTouchEvent(event); event.recycle();
        });
    }
    private void tap(View view) throws Exception {
        long now=android.os.SystemClock.uptimeMillis();
        touch(view,MotionEvent.ACTION_DOWN,view.getWidth()/2f,view.getHeight()/2f,now);
        touch(view,MotionEvent.ACTION_UP,view.getWidth()/2f,view.getHeight()/2f,now);
    }
    public void testReadingTapDoesNotCollideWithZoomDragOrInk() throws Exception {
        MainActivity a=open(proseFixture("tap-gestures.pdf"));
        try {
            await(()->field(a,"displayed")!=null&&((View)field(a,"pageView")).isEnabled());
            View page=(View)field(a,"pageView"); int height=page.getHeight();
            tap(page); await(()->!(Boolean)field(a,"readerChromeVisible")&&page.getHeight()>height);
            assertEquals(View.GONE,((View)field(a,"bar")).getVisibility());
            tap(page); await(()->(Boolean)field(a,"readerChromeVisible"));
            Thread.sleep(400); tap(page); Thread.sleep(70); tap(page); Thread.sleep(450);
            getInstrumentation().runOnMainSync(()->{
                assertTrue("double tap must not hide controls",(Boolean)field(a,"readerChromeVisible"));
                assertTrue("double tap zoom remains available",(Float)field(page,"zoom")>1.1f);
            });
            long down=android.os.SystemClock.uptimeMillis();
            touch(page,MotionEvent.ACTION_DOWN,500,350,down);
            touch(page,MotionEvent.ACTION_MOVE,350,450,down);
            touch(page,MotionEvent.ACTION_UP,350,450,down); Thread.sleep(400);
            getInstrumentation().runOnMainSync(()->{
                assertTrue("drag must not toggle controls",(Boolean)field(a,"readerChromeVisible"));
                find(a.getWindow().getDecorView(),"画笔").performClick();
            });
            tap(page); Thread.sleep(400);
            getInstrumentation().runOnMainSync(()->{
                assertTrue("ink tap must not toggle controls",(Boolean)field(a,"readerChromeVisible"));
                assertEquals(1,((ArrayList<PdfEngine.Sheet>)field(a,"sheets")).get(0).marks.size());
                find(a.getWindow().getDecorView(),"☝ 阅读").performClick();
            });
            tap(page); await(()->!(Boolean)field(a,"readerChromeVisible"));
            getInstrumentation().runOnMainSync(()->{
                a.onBackPressed(); assertTrue((Boolean)field(a,"readerChromeVisible"));
                assertTrue("back first restores controls",(Boolean)field(a,"viewing"));
            });
        } finally { getInstrumentation().runOnMainSync(a::finish); }
    }
    public void testSinglePageLandscapeWidthMarksAndInsets() throws Exception {
        MainActivity a=open(proseFixture("single-landscape.pdf"));
        try {
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
                    context.getPackageManager().getActivityInfo(a.getComponentName(),0).screenOrientation);
            await(()->field(a,"displayed")!=null&&((View)field(a,"pageView")).isEnabled());
            PageCanvas page=(PageCanvas)field(a,"pageView");
            getInstrumentation().runOnMainSync(()->find(a.getWindow().getDecorView(),"画笔").performClick());
            tap(page); Thread.sleep(400);
            String[] model={null}; Object bitmap=field(a,"displayed");
            getInstrumentation().runOnMainSync(()->{
                model[0]=PdfEngine.encode((ArrayList<PdfEngine.Sheet>)field(a,"sheets"));
                find(a.getWindow().getDecorView(),"☝ 阅读").performClick();
                a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            });
            await(()->a.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE&&page.getWidth()>page.getHeight());
            getInstrumentation().waitForIdleSync();
            getInstrumentation().runOnMainSync(()->{
                call(page,"bounds"); RectF rect=(RectF)field(page,"rect");
                assertEquals("landscape fits width",page.getWidth()-24f,rect.width(),2f);
                assertTrue("left edge accessible",rect.left>=0);
                assertTrue("right edge accessible",rect.right<=page.getWidth());
                assertSame("rotation keeps live bitmap",bitmap,field(a,"displayed"));
                assertEquals(model[0],PdfEngine.encode((ArrayList<PdfEngine.Sheet>)field(a,"sheets")));
                assertEquals(View.GONE,((View)field(a,"hint")).getVisibility());
            });
            screenshot("paperpdf-140-landscape-controls");
            tap(page); await(()->!(Boolean)field(a,"readerChromeVisible"));
            screenshot("paperpdf-140-landscape-hidden");
            if(android.os.Build.VERSION.SDK_INT>=30) {
                getInstrumentation().runOnMainSync(()->{
                    View root=(View)field(a,"root");
                    for(boolean left:new boolean[]{true,false}) {
                        WindowInsets insets=new WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(),android.graphics.Insets.of(left?70:0,0,left?0:70,0))
                            .setInsets(WindowInsets.Type.displayCutout(),android.graphics.Insets.of(left?0:90,0,left?90:0,0)).build();
                        root.dispatchApplyWindowInsets(insets);
                        assertEquals(left?70:90,root.getPaddingLeft()); assertEquals(left?90:70,root.getPaddingRight());
                    }
                    root.requestApplyInsets();
                });
            }
            getInstrumentation().runOnMainSync(()->a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            await(()->a.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT&&page.getHeight()>page.getWidth());
            getInstrumentation().runOnMainSync(()->{
                assertFalse("rotation preserves hidden UI",(Boolean)field(a,"readerChromeVisible"));
                assertEquals(model[0],PdfEngine.encode((ArrayList<PdfEngine.Sheet>)field(a,"sheets")));
            });
        } finally { getInstrumentation().runOnMainSync(()->{a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER);a.finish();}); }
    }
    public void testContinuousRotationAndTapPreservePosition() throws Exception {
        context.getSharedPreferences("MainActivity",0).edit().putBoolean("continuous",true).commit();
        MainActivity a=open(proseFixture("continuous-landscape.pdf"));
        try {
            await(()->{ContinuousView v=(ContinuousView)field(a,"continuousView");return v!=null&&v.visiblePagesReady();});
            ContinuousView view=(ContinuousView)field(a,"continuousView");
            getInstrumentation().runOnMainSync(()->{view.zoomAt(2.2f,0,0);view.jumpToPosition(3,.35f);});
            await(()->view.currentPage()==3&&view.visiblePagesReady());
            tap(view); await(()->!(Boolean)field(a,"readerChromeVisible"));
            getInstrumentation().runOnMainSync(()->a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE));
            await(()->a.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE&&view.getWidth()>view.getHeight());
            await(view::visiblePagesReady);
            getInstrumentation().runOnMainSync(()->{
                assertSame(view,field(a,"continuousView")); assertEquals(3,view.currentPage());
                assertEquals(.35f,view.pageFraction(),.025f); assertEquals(2.2f,view.zoomLevel(),.001f);
                assertFalse((Boolean)field(a,"readerChromeVisible"));
            });
            screenshot("paperpdf-140-continuous-landscape");
            tap(view); await(()->(Boolean)field(a,"readerChromeVisible"));
            Thread.sleep(400); tap(view); Thread.sleep(70); tap(view); Thread.sleep(450);
            getInstrumentation().runOnMainSync(()->{
                assertTrue("continuous double tap must not hide controls",(Boolean)field(a,"readerChromeVisible"));
                assertEquals(1f,view.zoomLevel(),.001f);
            });
        } finally { getInstrumentation().runOnMainSync(()->{a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER);a.finish();}); }
    }
    public void testContinuousZoomContentFitAndIndependentResume() throws Exception {
        context.getSharedPreferences("MainActivity",0).edit().putBoolean("continuous",true).commit();
        File file=proseFixture(); MainActivity a=open(file);
        await(()->{ContinuousView v=(ContinuousView)field(a,"continuousView");return v!=null&&v.visiblePagesReady();});
        getInstrumentation().runOnMainSync(()->{
            ContinuousView v=(ContinuousView)field(a,"continuousView"); assertTrue(v.contentWidthFraction(0)<.8f);
            // Dispatch a real two-pointer pinch through the reader's touch handler.
            long now=android.os.SystemClock.uptimeMillis(); MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,300,300,0); v.onTouchEvent(down); down.recycle();
            MotionEvent.PointerProperties[] props={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};
            MotionEvent.PointerCoords[] coords={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};
            for(int j=0;j<2;j++){props[j].id=j;props[j].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[j].y=300;coords[j].pressure=1;coords[j].size=1;}
            for(int step=0;step<16;step++) {
                coords[0].x=400-step*20;coords[1].x=600+step*20;
                int action=step==0?MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT):MotionEvent.ACTION_MOVE;
                MotionEvent e=MotionEvent.obtain(now,now+20+step*20,action,2,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0); v.onTouchEvent(e);e.recycle();
            }
            assertTrue("pinch enlarges continuous reader: "+v.zoomLevel(),v.zoomLevel()>1.3f);
            MotionEvent cancel=MotionEvent.obtain(now,now+250,MotionEvent.ACTION_CANCEL,100,300,0);v.onTouchEvent(cancel);cancel.recycle();
            v.zoomAt(1,0,0); v.jumpToPosition(3,.35f);
        });
        await(()->{ContinuousView v=(ContinuousView)field(a,"continuousView");return v.currentPage()==3&&v.contentWidthFraction(3)<.8f;});
        getInstrumentation().runOnMainSync(a::finish); getInstrumentation().waitForIdleSync();
        MainActivity other=open(fixture("another.pdf",2)); getInstrumentation().runOnMainSync(other::finish); getInstrumentation().waitForIdleSync();
        MainActivity reopened=open(file);
        await(()->{ContinuousView v=(ContinuousView)field(reopened,"continuousView");return v!=null&&v.isReady()&&v.cachedPageCount()>0;});
        getInstrumentation().runOnMainSync(()->{
            ContinuousView v=(ContinuousView)field(reopened,"continuousView"); assertEquals(3,v.currentPage()); assertEquals(.35f,v.pageFraction(),.025f);
            assertTrue("bounded bitmap cache",v.cachedPageCount()<=4);
        });
        screenshot("paperpdf-130-content-fit");
        getInstrumentation().runOnMainSync(()->((ContinuousView)field(reopened,"continuousView")).zoomAt(2.2f,450,450));
        screenshot("paperpdf-130-zoom");
        getInstrumentation().runOnMainSync(()->{
            find(reopened.getWindow().getDecorView(),"⚙").performClick();
            find(reopened.getWindow().getDecorView(),"夜间阅读").performClick(); reopened.onBackPressed();
        });
        await(()->{ContinuousView v=(ContinuousView)field(reopened,"continuousView");return v!=null&&v.isReady()&&v.cachedPageCount()>0;});
        await(()->((ContinuousView)field(reopened,"continuousView")).visiblePagesReady());
        Thread.sleep(150);
        screenshot("paperpdf-130-night-continuous");
        getInstrumentation().runOnMainSync(reopened::finish);
    }
    public void testNightChromeSettingsAlignmentAndSinglePageResume() throws Exception {
        File file=fixture("night.pdf",4); MainActivity a=open(file);
        getInstrumentation().runOnMainSync(()->{
            find(a.getWindow().getDecorView(),"下一页").performClick(); find(a.getWindow().getDecorView(),"⚙").performClick();
            find(a.getWindow().getDecorView(),"夜间阅读").performClick(); a.onBackPressed();
        });
        await(()->field(a,"displayed")!=null);
        getInstrumentation().runOnMainSync(()->{
            assertEquals(0xff202127,a.getWindow().getStatusBarColor());
            View root=(View)field(a,"root"); assertEquals(0xff202127,((android.graphics.drawable.ColorDrawable)root.getBackground()).getColor());
            TextView icon=find(a.getWindow().getDecorView(),"⚙"); assertEquals(0,icon.getPaddingLeft()); assertEquals(0,icon.getPaddingRight());
            assertTrue(icon.getWidth()>icon.getPaint().measureText(icon.getText().toString()));
            assertEquals(0xffEAE8F0,((TextView)field(a,"pager")).getCurrentTextColor());
            assertNotNull(find(a.getWindow().getDecorView(),"2 / 4")); a.finish();
        });
        getInstrumentation().waitForIdleSync(); MainActivity b=open(file);
        getInstrumentation().runOnMainSync(()->{assertNotNull(find(b.getWindow().getDecorView(),"2 / 4"));b.finish();});
    }
    public void testOverwriteOriginalExportsAndReopensWithoutDuplicateMarks() throws Exception {
        File original=fixture("overwrite.pdf",2); long initialLength=original.length(); MainActivity a=open(original);
        getInstrumentation().runOnMainSync(()->{
            ArrayList<PdfEngine.Sheet> model=(ArrayList<PdfEngine.Sheet>)field(a,"sheets");
            PdfEngine.Mark mark=new PdfEngine.Mark();mark.type=PdfEngine.TEXT;mark.color=Color.BLACK;mark.width=.04f;mark.text="Overwrite verification";mark.points.add(new PointF(.1f,.5f));model.get(0).marks.add(mark);
            try {java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("export",boolean.class,boolean.class);m.setAccessible(true);m.invoke(a,false,true);} catch(Exception e){throw new AssertionError(e);}
        });
        await(()->!(Boolean)field(a,"busy")&&field(a,"pendingOverwrite")==null&&((String)field(a,"savedModel")).contains("Overwrite verification"));
        assertTrue("annotation content written to original bytes",original.length()>initialLength);
        try(PDDocument doc=PDDocument.load(original)){assertEquals(2,doc.getNumberOfPages());}
        getInstrumentation().runOnMainSync(a::finish); getInstrumentation().waitForIdleSync(); MainActivity b=open(original);
        getInstrumentation().runOnMainSync(()->{assertEquals(0,((ArrayList<PdfEngine.Sheet>)field(b,"sheets")).get(0).marks.size());b.finish();});
    }
    public void testOverwriteRollsBackPartialWrite() throws Exception {
        File target=fixture("rollback-original.pdf",1),replacement=fixture("rollback-new.pdf",2),backup=new File(context.getCacheDir(),"rollback-backup.pdf");
        OriginalFileWriter.Target provider=new OriginalFileWriter.Target(){
            int writes;
            public InputStream read() throws IOException{return new FileInputStream(target);}
            public OutputStream write() throws IOException{
                FileOutputStream out=new FileOutputStream(target); if(++writes!=1)return out;
                return new FilterOutputStream(out){@Override public void write(byte[] bytes,int off,int len)throws IOException{out.write(bytes,off,Math.min(20,len));throw new IOException("simulated full disk");}};
            }
        };
        try{OriginalFileWriter.replace(provider,replacement,backup);fail("partial write accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("已恢复"));}
        try(PDDocument doc=PDDocument.load(target)){assertEquals(1,doc.getNumberOfPages());} assertFalse(backup.exists());
    }
    public void testContentFitKeepsBlankAndNarrowPagesIntact() {
        Bitmap b=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);
        assertEquals(0f,ContinuousView.horizontalContent(b)[0]); assertEquals(1f,ContinuousView.horizontalContent(b)[1]);
        Canvas c=new Canvas(b); Paint p=new Paint();p.setColor(Color.BLACK);c.drawRect(60,50,340,550,p);
        float[] cropped=ContinuousView.horizontalContent(b); assertTrue(cropped[0]>.13f&&cropped[0]<.16f);assertTrue(cropped[1]>.84f&&cropped[1]<.88f);
        b.eraseColor(Color.WHITE);c.drawRect(195,100,205,200,p);assertEquals(1f,ContinuousView.horizontalContent(b)[1]);b.recycle();
    }
    public void testOverwriteDeniedLeavesOriginalUntouched() throws Exception {
        File original=fixture("denied.pdf",1),replacement=fixture("denied-new.pdf",2),backup=new File(context.getCacheDir(),"denied-backup.pdf");
        long before=original.length();
        OriginalFileWriter.Target target=new OriginalFileWriter.Target(){
            public InputStream read()throws IOException{return new FileInputStream(original);}
            public OutputStream write(){throw new SecurityException("read-only grant");}
        };
        try{OriginalFileWriter.replace(target,replacement,backup);fail("read-only accepted");}catch(IOException expected){}
        assertEquals(before,original.length()); try(PDDocument doc=PDDocument.load(original)){assertEquals(1,doc.getNumberOfPages());}
    }
    public void testAppHomeOpenReadAndPersist() throws Exception {
        Intent intent=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        MainActivity activity=(MainActivity)getInstrumentation().startActivitySync(intent);
        final MainActivity home=activity;
        getInstrumentation().runOnMainSync(()->{
            assertNotNull(find(home.getWindow().getDecorView(),"打开手机中的 PDF"));
            assertNotNull(find(home.getWindow().getDecorView(),"⚙"));
            assertNotNull(find(home.getWindow().getDecorView(),"文件留在设备上"));
            assertNull(find(home.getWindow().getDecorView(),"试读示例文档"));
            find(home.getWindow().getDecorView(),"⚙").performClick();
        });
        getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(()->{
            assertNotNull(find(home.getWindow().getDecorView(),"PDF 文件目录"));
            assertNotNull(find(home.getWindow().getDecorView(),"添加普通目录"));
            assertNotNull(find(home.getWindow().getDecorView(),"添加微信 / QQ 文件夹"));
            assertNotNull(find(home.getWindow().getDecorView(),"连续滚动阅读"));
        });
        getInstrumentation().runOnMainSync(home::finish);
        File pdf=fixture("activity-open.pdf",4);
        Intent open=new Intent(context,MainActivity.class).setAction(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(pdf),"application/pdf").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity=(MainActivity)getInstrumentation().startActivitySync(open);
        final MainActivity viewer=activity;
        long deadline=System.currentTimeMillis()+30000; final boolean[] loaded={false};
        while(System.currentTimeMillis()<deadline&&!loaded[0]) {
            Thread.sleep(250); getInstrumentation().runOnMainSync(()->loaded[0]=find(viewer.getWindow().getDecorView(),"1 / 4")!=null);
        }
        assertTrue("PDF opened",loaded[0]);
        getInstrumentation().runOnMainSync(()->find(viewer.getWindow().getDecorView(),"下一页").performClick());
        getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(()->assertNotNull(find(viewer.getWindow().getDecorView(),"2 / 4")));
        assertTrue(new File(context.getFilesDir(),"session.json").isFile());
        getInstrumentation().runOnMainSync(viewer::finish);
    }
}
