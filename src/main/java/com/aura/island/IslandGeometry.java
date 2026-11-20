/* SPDX-License-Identifier: Apache-2.0 */
package com.aura.island;

/** Shared screen-space geometry. Pure Java so ports can test without Android. */
public final class IslandGeometry {
    public static final class Spec {
        public float holeWidthDp=18, holeHeightDp=18, gapDp=40;
        public float mediaWidthDp=128, chargeWidthDp=164, collapsedHeightDp=44;
        public float offsetXDp=0, offsetYDp=4, expandedGapDp=6;
        public float expandedWidthDp=328, expandedHeightDp=152, edgeDp=4;
    }
    public static final class Box {
        public final int left,top,width,height;
        Box(int l,int t,int w,int h) { left=l;top=t;width=w;height=h; }
        public int right() { return left+width; }
        public int bottom() { return top+height; }
    }
    public static final class Slots {
        public final int left,gap,right;
        Slots(int l,int g,int r) { left=l;gap=g;right=r; }
    }
    public final Box media,charge,expanded,window;
    public final float cameraX,cameraY,holeWidth,holeHeight,gapWidth;

    private IslandGeometry(Box m,Box c,Box e,Box w,float x,float y,float hw,float hh,float gap) {
        media=m;charge=c;expanded=e;window=w;
        cameraX=x;cameraY=y;holeWidth=hw;holeHeight=hh;gapWidth=gap;
    }
    private static float safe(float v,float fallback,float lo,float hi) {
        return Math.max(lo,Math.min(hi,Float.isFinite(v)?v:fallback));
    }
    private static int clip(int v,int lo,int hi) { return Math.max(lo,Math.min(Math.max(lo,hi),v)); }
    public static IslandGeometry resolve(int sw,int sh,float density,float cx,float cy,
            float exclusionBottom,Spec s) {
        if(sw<1||sh<1||s==null) throw new IllegalArgumentException("Invalid display/spec");
        float d=safe(density,1,.5f,8);
        float hw=Math.min(sw,safe(s.holeWidthDp,18,1,160)*d);
        float hh=Math.min(sh,safe(s.holeHeightDp,18,1,120)*d);
        cx=safe(cx,sw/2f,hw/2f,sw-hw/2f);
        cy=safe(cy,24*d,hh/2f,sh-hh/2f);
        int edge=Math.min(sw/4,Math.round(safe(s.edgeDp,4,0,32)*d));
        int available=Math.max(1,sw-2*edge);
        int ew=Math.min(available,Math.round(safe(s.expandedWidthDp,328,220,600)*d));
        int eh=Math.min(sh,Math.round(safe(s.expandedHeightDp,152,152,400)*d));
        int mh=Math.min(sh,Math.round(safe(s.collapsedHeightDp,44,24,96)*d));
        int mw=Math.min(ew,Math.round(safe(s.mediaWidthDp,128,72,400)*d));
        int cw=Math.min(ew,Math.round(safe(s.chargeWidthDp,164,72,440)*d));
        float visualX=cx+safe(s.offsetXDp,0,-120,120)*d;
        int mt=clip(Math.round(cy+safe(s.offsetYDp,4,-80,80)*d-mh/2f),0,sh-mh);
        int et=clip(Math.round(safe(exclusionBottom,cy+24*d,0,sh)
                +safe(s.expandedGapDp,6,0,100)*d),0,sh-eh);
        Box m=new Box(clip(Math.round(visualX-mw/2f),edge,sw-edge-mw),mt,mw,mh);
        Box c=new Box(clip(Math.round(visualX-cw/2f),edge,sw-edge-cw),mt,cw,mh);
        Box e=new Box(clip(Math.round(visualX-ew/2f),edge,sw-edge-ew),et,ew,eh);
        int l=Math.min(Math.min(m.left,c.left),Math.min(e.left,(int)Math.floor(cx-hw/2f)));
        int t=Math.min(mt,Math.min(et,(int)Math.floor(cy-hh/2f)));
        int r=Math.max(Math.max(m.right(),c.right()),Math.max(e.right(),(int)Math.ceil(cx+hw/2f)));
        int b=Math.max(Math.max(m.bottom(),e.bottom()),(int)Math.ceil(cy+hh/2f));
        Box w=new Box(clip(l,0,sw-1),clip(t,0,sh-1),Math.min(sw,r)-l,Math.min(sh,b)-t);
        return new IslandGeometry(m,c,e,w,cx,cy,hw,hh,
                Math.max(hw,safe(s.gapDp,40,1,200)*d));
    }
    public Box collapsed(boolean charging) { return charging?charge:media; }
    public Slots slots(boolean charging) {
        Box b=collapsed(charging);
        int l=clip(Math.round(cameraX-gapWidth/2f)-b.left,0,b.width);
        int r=clip(Math.round(cameraX+gapWidth/2f)-b.left,l,b.width);
        return new Slots(l,r-l,b.width-r);
    }
    /** Outline narrows first, then shortens; final target is the calibrated lens rectangle. */
    public static class Morph {
        public final float left,width,height,top,radius,alpha;
        public Morph(float sl,float sw,float sh,float st,float sr,float tl,float tw,float th,
                float tt,float progress) {
            float p=Math.max(0,Math.min(1,progress));
            float hp=Math.max(0,(p-.18f)/.82f);
            left=sl+(tl-sl)*p;width=sw+(tw-sw)*p;
            height=sh+(th-sh)*hp;top=st+(tt-st)*p;
            radius=Math.min(Math.min(width,height)/2f,sr+(Math.min(tw,th)/2f-sr)*hp);
            alpha=Math.max(0,1-Math.max(0,(p-.88f)/.12f));
        }
    }
}
