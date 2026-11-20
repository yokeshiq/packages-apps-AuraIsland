/* SPDX-License-Identifier: Apache-2.0 */
package com.aura.island;

/** Optional Aura framework extensions; absent extensions never block plain AOSP compilation. */
final class IslandPlatformBridge {
    private static final java.lang.reflect.Field SCALE=field("auraSmallWindowScale");
    private static final java.lang.reflect.Field MINI=field("auraSmallWindowMini");
    private static final java.lang.reflect.Method END_SCALE=method(android.window.TransitionInfo.Change.class,"getAuraSmallWindowEndScale");
    private static final java.lang.reflect.Method FILL=method(com.android.internal.view.IInputMethodManager.class,"fillSmsVerificationCode",String.class);
    private static java.lang.reflect.Field field(String name) {
        try {return android.app.TaskInfo.class.getField(name);}
        catch(ReflectiveOperationException|RuntimeException e) {return null;}
    }
    private static java.lang.reflect.Method method(Class<?> type,String name,Class<?>... args) {
        try {return type.getMethod(name,args);}
        catch(ReflectiveOperationException|RuntimeException e) {return null;}
    }
    private IslandPlatformBridge() {}
    static boolean smallWindowAvailable() {
        if(!android.os.SystemProperties.getBoolean("ro.aura.small_window",false)) return false;
        return SCALE!=null&&MINI!=null&&END_SCALE!=null;
    }
    static float taskScale(android.app.TaskInfo task) {
        try {
            if(SCALE==null)return 1f;
            float scale=SCALE.getFloat(task);
            return Float.isFinite(scale)&&scale>0&&scale<1?scale:1f;
        } catch(ReflectiveOperationException|RuntimeException e) {return 1f;}
    }
    static boolean mini(android.app.TaskInfo task) {
        try {return MINI!=null&&MINI.getBoolean(task);}
        catch(ReflectiveOperationException|RuntimeException e) {return false;}
    }
    static float transitionScale(android.window.TransitionInfo.Change change) {
        try {
            if(END_SCALE==null)return 1f;
            float scale=((Number)END_SCALE.invoke(change)).floatValue();
            return Float.isFinite(scale)&&scale>0&&scale<1?scale:1f;
        } catch(ReflectiveOperationException|RuntimeException e) {return 1f;}
    }
    static boolean fillCode(String code) {
        if(FILL==null)return false;
        Object input=com.android.internal.view.IInputMethodManager.Stub.asInterface(android.os.ServiceManager.getService("input_method"));
        if(input==null)return false;
        try {
            return Boolean.TRUE.equals(FILL.invoke(input,code));
        } catch(ReflectiveOperationException|RuntimeException e) {return false;}
    }
}
