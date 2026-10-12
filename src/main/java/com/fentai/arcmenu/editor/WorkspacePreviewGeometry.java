package com.fentai.arcmenu.editor;

/** Native-pixel preview crops never enlarge a low-resolution project or read outside the framebuffer. */
final class WorkspacePreviewGeometry {
    record Crop(int x,int y,int width,int height,int outputWidth,int outputHeight,int offsetX,int offsetY) {}
    static Crop crop(int framebufferWidth,int framebufferHeight,EditorLayout.ScreenRect plane){
        if(framebufferWidth<1280||framebufferHeight<720)throw new IllegalArgumentException("A native render resolution of at least 1280 by 720 is required for publication");
        if(!Double.isFinite(plane.x())||!Double.isFinite(plane.y())||!Double.isFinite(plane.width())||!Double.isFinite(plane.height())||plane.width()<=0||plane.height()<=0)throw new IllegalArgumentException("Invalid project preview geometry");
        int x=Math.max(0,(int)Math.floor(plane.x())),y=Math.max(0,(int)Math.floor(plane.y()));
        int right=Math.min(framebufferWidth,(int)Math.ceil(plane.x()+plane.width())),bottom=Math.min(framebufferHeight,(int)Math.ceil(plane.y()+plane.height()));
        if(right<=x||bottom<=y)throw new IllegalArgumentException("The project is outside the camera aperture");
        int width=right-x,height=bottom-y;double scale=Math.min(1,Math.min(1280.0/width,720.0/height));
        int outputWidth=Math.max(1,(int)Math.round(width*scale)),outputHeight=Math.max(1,(int)Math.round(height*scale));
        return new Crop(x,y,width,height,outputWidth,outputHeight,(1280-outputWidth)/2,(720-outputHeight)/2);
    }
}
