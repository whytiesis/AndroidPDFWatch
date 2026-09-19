package cn.paperpdf.reader;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.view.*;
import java.util.*;

public final class PageCanvas extends View {
    interface Listener {
        void mark(PdfEngine.Mark mark); void text(float x,float y); void turn(int delta);
        void erase(int index); void select(int index);
    }
    private Bitmap bitmap;
    private PdfEngine.Sheet sheet;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final ColorMatrixColorFilter nightFilter=new ColorMatrixColorFilter(new float[]{-0.85f,0,0,0,240,0,-0.85f,0,0,240,0,0,-0.85f,0,240,0,0,0,1,0});
    private final RectF rect=new RectF();
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private final Listener listener;
    private float zoom=1,dx,dy,lastX,lastY,startX,startY;
    private int mode,selected=-1;
    private boolean night,multi,lastFitWidth;
    private PdfEngine.Mark active;
    public PageCanvas(Context context,Listener listener) {
        super(context); this.listener=listener; setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        setFocusable(true);
        setContentDescription("PDF 页面。阅读模式轻触隐藏或显示工具栏，双指缩放，左右滑动翻页；编辑模式在页面上标记。");
        scaler=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float old=zoom; zoom=Math.max(1,Math.min(5,zoom*d.getScaleFactor()));
                dx=(dx+getWidth()/2f-d.getFocusX())*zoom/old+d.getFocusX()-getWidth()/2f;
                dy=(dy+getHeight()/2f-d.getFocusY())*zoom/old+d.getFocusY()-getHeight()/2f;
                constrain(); invalidate(); return true;
            }
        });
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if(mode==0&&!multi) performClick(); return true;
            }
            @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                if(mode==0) { zoom=zoom>1.1f?1:2.5f; dx=dy=0; constrain(); invalidate(); } return true;
            }
        });
    }
    public void setPage(Bitmap b,PdfEngine.Sheet s) {
        bitmap=b; sheet=s; active=null; selected=-1; zoom=1; dx=dy=0;
        lastFitWidth=landscape();
        if(lastFitWidth) { dy=Math.max(0,(b.getHeight()*fit(getWidth(),getHeight(),true)-getHeight())/2+12); }
        constrain(); invalidate();
    }
    public void refresh(PdfEngine.Sheet s) { sheet=s; if(selected>=s.marks.size()) selected=-1; invalidate(); }
    public void mode(int m) { mode=m; active=null; if(m!=PdfEngine.SELECT) selected=-1; invalidate(); }
    public void selected(int index) { selected=index; invalidate(); }
    public void night(boolean n) { night=n; invalidate(); }
    private boolean landscape() { return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE; }
    private float fit(int w,int h,boolean widthOnly) {
        float horizontal=Math.max(1,w-24f)/bitmap.getWidth();
        return widthOnly?horizontal:Math.min(horizontal,Math.max(1,h-24f)/bitmap.getHeight());
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        if(bitmap!=null&&oldw>0&&oldh>0) {
            // Preserve the page point at the viewport centre across rotation and toolbar changes.
            float old=fit(oldw,oldh,lastFitWidth)*zoom, next=fit(w,h,landscape())*zoom;
            dx=dx/old*next; dy=dy/old*next;
        }
        lastFitWidth=landscape(); active=null; constrain(); invalidate();
    }
    private void bounds() {
        if(bitmap==null) return;
        float fit=fit(getWidth(),getHeight(),landscape());
        float w=bitmap.getWidth()*fit*zoom,h=bitmap.getHeight()*fit*zoom;
        rect.set((getWidth()-w)/2+dx,(getHeight()-h)/2+dy,(getWidth()+w)/2+dx,(getHeight()+h)/2+dy);
    }
    private void constrain() {
        if(bitmap==null) return;
        float fit=fit(getWidth(),getHeight(),landscape());
        float x=Math.max(0,(bitmap.getWidth()*fit*zoom-getWidth())/2+12);
        float y=Math.max(0,(bitmap.getHeight()*fit*zoom-getHeight())/2+12);
        dx=Math.max(-x,Math.min(x,dx)); dy=Math.max(-y,Math.min(y,dy));
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); c.drawColor(night?0xff202127:0xffE9E8E4);
        if(bitmap==null) return; bounds();
        paint.setStyle(Paint.Style.FILL); paint.setPathEffect(null); paint.setColor(Color.WHITE); paint.setShadowLayer(8,0,3,0x20000000); c.drawRect(rect,paint); paint.clearShadowLayer();
        if(night) paint.setColorFilter(nightFilter);
        c.drawBitmap(bitmap,null,rect,paint); paint.setColorFilter(null);
        c.save(); c.clipRect(rect); c.translate(rect.left,rect.top);
        if(sheet!=null) PdfEngine.paintMarks(c,sheet.marks,rect.width(),rect.height());
        if(active!=null) PdfEngine.paintMarks(c,Collections.singletonList(active),rect.width(),rect.height());
        if(sheet!=null&&selected>=0&&selected<sheet.marks.size()) {
            RectF selectedBounds=PdfEngine.markBounds(sheet.marks.get(selected));
            RectF outline=new RectF(selectedBounds.left*rect.width(),selectedBounds.top*rect.height(),selectedBounds.right*rect.width(),selectedBounds.bottom*rect.height());
            outline.inset(-8,-8); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(0xff6154C7);
            paint.setPathEffect(new DashPathEffect(new float[]{10,7},0)); c.drawRoundRect(outline,8,8,paint); paint.setPathEffect(null);
        }
        c.restore();
    }
    private PointF point(float x,float y) {
        return new PointF(Math.max(0,Math.min(1,(x-rect.left)/rect.width())),Math.max(0,Math.min(1,(y-rect.top)/rect.height())));
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(bitmap==null||!isEnabled()) return true;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN) multi=false;
        if(e.getPointerCount()>1) multi=true;
        bounds(); scaler.onTouchEvent(e); gestures.onTouchEvent(e);
        float x=e.getX(),y=e.getY();
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                multi=false; startX=lastX=x; startY=lastY=y;
                if((mode==PdfEngine.INK || mode==PdfEngine.HIGHLIGHT)&&rect.contains(x,y)) {
                    active=new PdfEngine.Mark(); active.type=mode;
                    active.color=mode==PdfEngine.INK?0xff6253C8:0x66FFD43B;
                    active.width=0.004f; active.points.add(point(x,y));
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN: active=null; multi=true; return true;
            case MotionEvent.ACTION_MOVE:
                if(active!=null&&!multi) {
                    if(mode==PdfEngine.HIGHLIGHT&&active.points.size()>1) active.points.remove(1);
                    active.points.add(point(x,y));
                } else if(!scaler.isInProgress()&&(mode==0||multi)) { dx+=x-lastX; dy+=y-lastY; constrain(); }
                lastX=x; lastY=y; invalidate(); return true;
            case MotionEvent.ACTION_POINTER_UP: lastX=e.getX(e.getActionIndex()==0?1:0); lastY=e.getY(e.getActionIndex()==0?1:0); return true;
            case MotionEvent.ACTION_UP:
                if(active!=null) {
                    if(mode!=PdfEngine.HIGHLIGHT||active.points.size()>1) listener.mark(active);
                    active=null;
                } else if(!multi&&mode==PdfEngine.TEXT&&rect.contains(x,y)&&Math.hypot(x-startX,y-startY)<25) {
                    PointF p=point(x,y); listener.text(p.x,p.y);
                } else if(!multi&&(mode==PdfEngine.ERASER||mode==PdfEngine.SELECT)&&rect.contains(x,y)&&Math.hypot(x-startX,y-startY)<25) {
                    PointF p=point(x,y); int index=sheet==null?-1:PdfEngine.hitMark(sheet.marks,p.x,p.y);
                    if(mode==PdfEngine.ERASER) listener.erase(index); else { selected=index; listener.select(index); }
                } else if(!multi&&mode==0&&zoom<1.1&&Math.abs(x-startX)>90&&Math.abs(x-startX)>Math.abs(y-startY)*1.4) listener.turn(x<startX?1:-1);
                invalidate(); return true;
            case MotionEvent.ACTION_CANCEL: active=null; invalidate(); return true;
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
