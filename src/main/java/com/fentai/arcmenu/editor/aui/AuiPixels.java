package com.fentai.arcmenu.editor.aui;

import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.system.MemoryUtil;

/** Pixel-format and bulk-transfer differences stay outside the embedded engine. */
final class AuiPixels {
    private AuiPixels(){}
    static int read(NativeImage image,int x,int y){return image.getPixel(x,y);}
    static void write(NativeImage image,int x,int y,int color){image.setPixel(x,y,color);}
    static int[] pixels(NativeImage image){return image.getPixels();}
    static void upload(NativeImage image,int[] argb){
        int[] pixels=new int[argb.length];for(int i=0;i<argb.length;i++)pixels[i]=swap(argb[i]);
        MemoryUtil.memIntBuffer(image.getPointer(),pixels.length).put(pixels);
    }
    private static int swap(int value){return (value&0xff00ff00)|((value&255)<<16)|((value>>>16)&255);}
}
