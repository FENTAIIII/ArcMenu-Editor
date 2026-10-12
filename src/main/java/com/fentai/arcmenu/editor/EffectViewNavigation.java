package com.fentai.arcmenu.editor;

/** Bounded zoom and hand-tool pan over the captured world frame. */
public final class EffectViewNavigation {
    private EffectViewNavigation() {}
    public static EditorLayout.Rect crop(EditorLayout.Rect base,int sourceWidth,int sourceHeight,double zoom,double panX,double panY) {
        if(sourceWidth<1||sourceHeight<1||!Double.isFinite(zoom)||zoom<1||!Double.isFinite(panX)||!Double.isFinite(panY)) throw new IllegalArgumentException("Invalid Effect view navigation");
        int w=Math.max(1,Math.min(sourceWidth,(int)Math.round(base.width()/zoom))),h=Math.max(1,Math.min(sourceHeight,(int)Math.round(base.height()/zoom)));
        int x=(int)Math.round(base.x()+base.width()/2.0-w/2.0+panX*base.width()),y=(int)Math.round(base.y()+base.height()/2.0-h/2.0+panY*base.height());
        return new EditorLayout.Rect(Math.max(0,Math.min(sourceWidth-w,x)),Math.max(0,Math.min(sourceHeight-h,y)),w,h);
    }
}
