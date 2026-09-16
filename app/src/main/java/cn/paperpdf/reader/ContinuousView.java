package cn.paperpdf.reader;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.*;
import android.view.*;
import android.widget.OverScroller;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Virtualized vertical reader: geometry for all pages, bitmaps only near the viewport. */
public final class ContinuousView extends View {
    public interface Listener { void pageChanged(int page); }
    private final PdfRenderer renderer;
    private final ArrayList<PdfEngine.Sheet> sheets;
    private final ExecutorService worker;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final float[] ratios,lefts,rights,tops,heights;
    private final LinkedHashMap<Integer,Bitmap> cache=new LinkedHashMap<>(8,.75f,true);
    private final Set<Integer> pending=new HashSet<>(),failed=new HashSet<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final ColorMatrixColorFilter nightFilter=new ColorMatrixColorFilter(new float[]{-.85f,0,0,0,240,0,-.85f,0,0,240,0,0,-.85f,0,240,0,0,0,1,0});
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private final OverScroller fling;
    private final boolean night,fitContent;
    private volatile boolean disposed;
    private boolean ready,multi;
    private float zoom=1,offsetX,offsetY,totalHeight,lastX,lastY;
    private int restorePage;
    private float restoreFraction;
    private final Runnable report=this::reportPosition;
    private void reportPosition() { if(!disposed&&ready&&listener!=null) listener.pageChanged(currentPage()); }

    public ContinuousView(Context context,PdfRenderer renderer,List<PdfEngine.Sheet> model,
                          ExecutorService worker,boolean night,boolean fitContent,int page,float fraction,Listener listener) {
        super(context); this.renderer=renderer; this.sheets=new ArrayList<>(model); this.worker=worker;
        this.night=night; this.fitContent=fitContent; this.listener=listener; restorePage=page; restoreFraction=fraction;
        int count=sheets.size(); ratios=new float[count]; lefts=new float[count]; rights=new float[count]; tops=new float[count]; heights=new float[count];
        Arrays.fill(ratios,842f/595); Arrays.fill(rights,1); setFocusable(true);
        setContentDescription("PDF 连续阅读。轻触隐藏或显示工具栏，上下滚动，双指缩放，放大后拖动，双击放大或恢复宽度。");
        fling=new OverScroller(context);
        scaler=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { fling.forceFinished(true); return true; }
            @Override public boolean onScale(ScaleGestureDetector d) { zoomAt(zoom*d.getScaleFactor(),d.getFocusX(),d.getFocusY()); return true; }
        });
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { fling.forceFinished(true); return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if(!multi&&!disposed) performClick(); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) { zoomAt(zoom>1.1f?1:2.2f,e.getX(),e.getY()); return true; }
            @Override public boolean onFling(MotionEvent a,MotionEvent b,float vx,float vy) {
                if(multi) return false;
                fling.fling((int)offsetX,(int)offsetY,(int)-vx,(int)-vy,0,(int)maxX(),0,(int)maxY()); postInvalidateOnAnimation(); return true;
            }
        });
        worker.execute(()->{
            float[] measured=new float[count];
            for(int i=0;i<count&&!disposed;i++) {
                try(PdfRenderer.Page p=renderer.openPage(sheets.get(i).original)) {
                    measured[i]=sheets.get(i).rotation%180==0?p.getHeight()/(float)p.getWidth():p.getWidth()/(float)p.getHeight();
                } catch(Exception e) { measured[i]=842f/595; }
            }
            main.post(()->{ if(disposed) return; System.arraycopy(measured,0,ratios,0,count); ready=true; layoutPages(); restorePosition(); invalidate(); });
        });
    }
    private float margin() { return getResources().getDisplayMetrics().density*2; }
    private float width() { return Math.max(1,getWidth()-2*margin()); }
    private void layoutPages() {
        float top=0;
        for(int i=0;i<sheets.size();i++) { tops[i]=top; heights[i]=width()*ratios[i]/(rights[i]-lefts[i]); top+=heights[i]+margin()*3; }
        totalHeight=top;
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        int page=currentPage(); float fraction=pageFraction(); fling.forceFinished(true); layoutPages();
        if(oldw>0) {
            offsetX=(offsetX+oldw/2f)*w/oldw-w/2f;
            offsetY=(tops[page]+fraction*heights[page])*zoom; constrain(); scheduleReport();
        }
        else restorePosition();
    }
    private void restorePosition() {
        if(getWidth()==0||!ready||sheets.isEmpty()) return;
        int p=Math.max(0,Math.min(sheets.size()-1,restorePage));
        offsetY=(tops[p]+restoreFraction*heights[p])*zoom; constrain(); scheduleReport();
    }
    public void jumpToPage(int page) { jumpToPosition(page,0); }
    public void jumpToPosition(int page,float fraction) {
        restorePage=Math.max(0,Math.min(sheets.size()-1,page)); restoreFraction=Math.max(0,Math.min(.999f,fraction));
        fling.forceFinished(true); restorePosition(); invalidate();
    }
    public int currentPage() {
        if(!ready) return Math.max(0,Math.min(sheets.size()-1,restorePage));
        float y=offsetY/zoom+margin(); int page=0;
        for(int i=1;i<tops.length;i++) { if(tops[i]>y) break; page=i; } return page;
    }
    public float pageFraction() { int p=currentPage(); return !ready?restoreFraction:Math.max(0,Math.min(.999f,(offsetY/zoom-tops[p])/Math.max(1,heights[p]))); }
    public float zoomLevel() { return zoom; }
    public int pageCount() { return sheets.size(); }
    public int cachedPageCount() { return cache.size(); }
    public float contentWidthFraction(int page) { return rights[page]-lefts[page]; }
    public boolean isReady() { return ready; }
    public boolean visiblePagesReady() {
        if(!ready||getHeight()==0) return false;
        for(int i=0;i<tops.length;i++) if(tops[i]+heights[i]>=offsetY/zoom&&tops[i]<=(offsetY+getHeight())/zoom&&!cache.containsKey(i)) return false;
        return true;
    }
    public void zoomAt(float value,float x,float y) {
        float old=zoom; zoom=Math.max(1,Math.min(5,value));
        offsetX=(offsetX+x)*zoom/old-x; offsetY=(offsetY+y)*zoom/old-y; constrain(); scheduleReport(); invalidate();
    }
    private float maxX() { return Math.max(0,getWidth()*(zoom-1)); }
    private float maxY() { return Math.max(0,totalHeight*zoom-getHeight()); }
    private void constrain() { offsetX=Math.max(0,Math.min(maxX(),offsetX)); offsetY=Math.max(0,Math.min(maxY(),offsetY)); }
    private void scheduleReport() { main.removeCallbacks(report); main.postDelayed(report,120); }
    @Override public void computeScroll() {
        if(fling.computeScrollOffset()) { offsetX=fling.getCurrX(); offsetY=fling.getCurrY(); constrain(); scheduleReport(); postInvalidateOnAnimation(); }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(!isEnabled()||disposed) return true;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN) { multi=false; lastX=event.getX(); lastY=event.getY(); }
        if(event.getPointerCount()>1) multi=true;
        scaler.onTouchEvent(event); gestures.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE) {
            if(!scaler.isInProgress()&&event.getPointerCount()==1) { offsetX+=lastX-event.getX(); offsetY+=lastY-event.getY(); constrain(); scheduleReport(); invalidate(); }
            lastX=event.getX(); lastY=event.getY();
        } else if(event.getActionMasked()==MotionEvent.ACTION_POINTER_UP) {
            int i=event.getActionIndex()==0?1:0; lastX=event.getX(i); lastY=event.getY(i);
        } else if(event.getActionMasked()==MotionEvent.ACTION_UP) { scheduleReport(); }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(night?0xff17181D:0xffE9E8E4); if(disposed||sheets.isEmpty()) return;
        canvas.save(); canvas.translate(-offsetX,-offsetY); canvas.scale(zoom,zoom);
        for(int i=0;i<sheets.size();i++) {
            if(tops[i]+heights[i]<offsetY/zoom||tops[i]>(offsetY+getHeight())/zoom) continue;
            RectF rect=new RectF(margin(),tops[i],getWidth()-margin(),tops[i]+heights[i]);
            paint.setColor(night?0xff181818:Color.WHITE); canvas.drawRect(rect,paint);
            Bitmap bitmap=cache.get(i);
            if(bitmap!=null) {
                Rect src=new Rect(Math.round(lefts[i]*bitmap.getWidth()),0,Math.round(rights[i]*bitmap.getWidth()),bitmap.getHeight());
                paint.setColorFilter(night?nightFilter:null); canvas.drawBitmap(bitmap,src,rect,paint); paint.setColorFilter(null);
                canvas.save(); canvas.clipRect(rect);
                float fullWidth=rect.width()/(rights[i]-lefts[i]); canvas.translate(rect.left-lefts[i]*fullWidth,rect.top);
                PdfEngine.paintMarks(canvas,sheets.get(i).marks,fullWidth,rect.height()); canvas.restore();
            } else {
                paint.setColor(night?0xffBCB9C8:0xff85828E); paint.setTextSize(14*getResources().getDisplayMetrics().scaledDensity); paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(failed.contains(i)?"此页加载失败":"正在加载第 "+(i+1)+" 页",getWidth()/2f,rect.top+45,paint); paint.setTextAlign(Paint.Align.LEFT);
                if(ready) requestPage(i);
            }
        }
        canvas.restore();
    }
    private void requestPage(int i) {
        if(disposed||pending.contains(i)||failed.contains(i)||pending.size()>=2) return;
        pending.add(i);
        worker.execute(()->{
            if(disposed) return;
            try {
                Bitmap bitmap=PdfEngine.render(renderer,sheets.get(i),2000);
                float[] crop=fitContent?horizontalContent(bitmap):new float[]{0,1};
                // Never hide user annotations beyond the detected printed content.
                for(PdfEngine.Mark mark:sheets.get(i).marks) { if(mark.type==PdfEngine.TEXT) {crop[0]=0;crop[1]=1;break;}
                    for(PointF p:mark.points) { crop[0]=Math.max(0,Math.min(crop[0],p.x-mark.width)); crop[1]=Math.min(1,Math.max(crop[1],p.x+mark.width)); } }
                main.post(()->{
                    pending.remove(i); if(disposed) { bitmap.recycle(); return; }
                    int anchor=currentPage(); float fraction=pageFraction();
                    lefts[i]=crop[0]; rights[i]=crop[1]; layoutPages(); offsetY=(tops[anchor]+fraction*heights[anchor])*zoom; constrain();
                    cache.put(i,bitmap);
                    while(cache.size()>4) { Iterator<Map.Entry<Integer,Bitmap>> it=cache.entrySet().iterator(); Map.Entry<Integer,Bitmap> entry=it.next(); entry.getValue().recycle(); it.remove(); }
                    scheduleReport(); invalidate();
                });
            } catch(Exception|OutOfMemoryError e) { main.post(()->{ pending.remove(i); failed.add(i); if(!disposed) invalidate(); }); }
        });
    }
    /** Detect horizontal white margins only. Keep all vertical content and fall back on blank/narrow pages. */
    static float[] horizontalContent(Bitmap bitmap) {
        int w=bitmap.getWidth(),h=bitmap.getHeight(),min=w,max=-1;
        int[] row=new int[w]; int stride=Math.max(1,h/1200);
        for(int y=0;y<h;y+=stride) {
            bitmap.getPixels(row,0,w,0,y,w,1);
            for(int x=0;x<w;x++) { int c=row[x]; if(Color.red(c)<232||Color.green(c)<232||Color.blue(c)<232) { min=Math.min(min,x); max=Math.max(max,x); } }
        }
        if(max<min||max-min<w*.3f) return new float[]{0,1};
        float padding=.012f; return new float[]{Math.max(0,min/(float)w-padding),Math.min(1,(max+1)/(float)w+padding)};
    }
    public void dispose() {
        disposed=true; main.removeCallbacks(report); fling.forceFinished(true);
        for(Bitmap bitmap:cache.values()) bitmap.recycle(); cache.clear();
    }
    @Override protected void onDetachedFromWindow() { dispose(); super.onDetachedFromWindow(); }
}
