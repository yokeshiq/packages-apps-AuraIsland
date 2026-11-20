/* SPDX-License-Identifier: Apache-2.0 */
package com.aura.island;

import android.content.Context;
import android.graphics.Rect;
import android.provider.Settings;
import android.view.DisplayCutout;
import android.view.WindowManager;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;

/** Device overlays supply defaults; a single atomic Global JSON supplies optional calibration. */
public final class IslandCameraConfig {
    public static final String SETTING="aura_island_geometry";
    public static final class Key {
        public final String name,label;
        final int resource;final boolean dimen;final float min,max;
        Key(String n,String l,int r,boolean d,float lo,float hi) {
            name=n;label=l;resource=r;dimen=d;min=lo;max=hi;
        }
    }
    public static final Key[] KEYS={
        new Key("camera_x_percent","挖孔横向位置（屏幕宽度 %）",R.integer.island_camera_x_percent,false,0,100),
        new Key("camera_y_dp","挖孔中心距屏幕顶部（dp）",R.dimen.island_camera_y,true,0,160),
        new Key("cutout_index","挖孔选择序号（-1 自动）",R.integer.island_camera_cutout_index,false,-1,8),
        new Key("hole_width_dp","摄像头收起终点宽度（dp）",R.dimen.island_camera_hole_width,true,1,160),
        new Key("hole_height_dp","摄像头收起终点高度（dp）",R.dimen.island_camera_hole_height,true,1,120),
        new Key("exclusion_height_dp","手动挖孔避让区高度（dp）",R.dimen.island_camera_exclusion_height,true,1,200),
        new Key("gap_dp","胶囊摄像头空档宽度（dp）",R.dimen.island_camera_gap,true,1,200),
        new Key("offset_x_dp","胶囊水平偏移（dp）",R.dimen.island_capsule_offset_x,true,-120,120),
        new Key("offset_y_dp","胶囊中心相对摄像头下移（dp）",R.dimen.island_capsule_offset_y,true,-80,80),
        new Key("capsule_height_dp","收起胶囊高度（dp）",R.dimen.island_capsule_height,true,24,96),
        new Key("media_width_dp","音乐／验证码胶囊宽度（dp）",R.dimen.island_capsule_media_width,true,72,400),
        new Key("charge_width_dp","充电胶囊宽度（dp）",R.dimen.island_capsule_charge_width,true,72,440),
        new Key("expanded_gap_dp","展开卡片距避让区底部（dp）",R.dimen.island_expanded_gap,true,0,100),
        new Key("expanded_width_dp","展开卡片宽度（dp）",R.dimen.island_expanded_width,true,220,600),
        new Key("expanded_height_dp","展开卡片高度（dp）",R.dimen.island_expanded_height,true,152,400),
        new Key("edge_dp","卡片距屏幕左右边缘（dp）",R.dimen.island_screen_edge,true,0,32)
    };
    public final IslandGeometry geometry;
    public final float density;
    public final int screenLeft,screenTop;
    public IslandCameraConfig(Context context) {
        density=context.getResources().getDisplayMetrics().density;
        android.view.WindowMetrics metrics=context.getSystemService(WindowManager.class).getMaximumWindowMetrics();
        Rect screen=metrics.getBounds();screenLeft=screen.left;screenTop=screen.top;
        JSONObject values=read(context);
        float x=screen.width()*value(context,values,"camera_x_percent")/100f;
        float y=value(context,values,"camera_y_dp")*density;
        float exclusionBottom=y+value(context,values,"exclusion_height_dp")*density/2f;
        DisplayCutout cutout=metrics.getWindowInsets().getDisplayCutout();
        if(auto(context,values)&&cutout!=null) {
            ArrayList<Rect> holes=new ArrayList<>();
            for(Rect r:cutout.getBoundingRects()) if(!r.isEmpty()&&r.top-screen.top<screen.height()/4) holes.add(new Rect(r));
            holes.sort(Comparator.comparingInt(r->r.left));
            int index=Math.round(value(context,values,"cutout_index"));
            if(index<0||index>=holes.size()) {
                index=-1;float nearest=Float.MAX_VALUE;
                for(int i=0;i<holes.size();i++) {
                    float distance=Math.abs(holes.get(i).exactCenterX()-screen.exactCenterX());
                    if(distance<nearest) {nearest=distance;index=i;}
                }
            }
            if(index>=0) {
                Rect r=holes.get(index);x=r.exactCenterX()-screen.left;
                y=r.exactCenterY()-screen.top;exclusionBottom=r.bottom-screen.top;
            }
        }
        IslandGeometry.Spec s=new IslandGeometry.Spec();
        s.holeWidthDp=value(context,values,"hole_width_dp");s.holeHeightDp=value(context,values,"hole_height_dp");
        s.gapDp=value(context,values,"gap_dp");s.offsetXDp=value(context,values,"offset_x_dp");s.offsetYDp=value(context,values,"offset_y_dp");
        s.mediaWidthDp=value(context,values,"media_width_dp");s.chargeWidthDp=value(context,values,"charge_width_dp");
        s.collapsedHeightDp=value(context,values,"capsule_height_dp");s.expandedGapDp=value(context,values,"expanded_gap_dp");
        s.expandedWidthDp=value(context,values,"expanded_width_dp");s.expandedHeightDp=value(context,values,"expanded_height_dp");s.edgeDp=value(context,values,"edge_dp");
        geometry=IslandGeometry.resolve(screen.width(),screen.height(),density,x,y,exclusionBottom,s);
    }
    public static JSONObject read(Context c) {
        try {String s=Settings.Global.getString(c.getContentResolver(),SETTING);return s==null?new JSONObject():new JSONObject(s);}
        catch(org.json.JSONException e) {return new JSONObject();}
    }
    public static boolean auto(Context c,JSONObject values) {return values.optBoolean("auto_cutout",c.getResources().getBoolean(R.bool.island_camera_auto));}
    public static float value(Context c,JSONObject values,String name) {
        for(Key k:KEYS) if(k.name.equals(name)) {
            float def=k.dimen?c.getResources().getDimension(k.resource)/c.getResources().getDisplayMetrics().density:c.getResources().getInteger(k.resource);
            float v=(float)values.optDouble(name,def);
            return Math.max(k.min,Math.min(k.max,Float.isFinite(v)?v:def));
        }
        throw new IllegalArgumentException(name);
    }
}
