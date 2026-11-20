package com.aura.island;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机/插拔电源时拉起灵动岛服务（SYSTEM_ALERT_WINDOW 应用允许后台拉起）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (android.os.UserHandle.myUserId() != android.os.UserHandle.USER_SYSTEM
                || android.provider.Settings.Global.getInt(context.getContentResolver(),
                        "aura_island_on", 1) == 0) {
            return;
        }
        try {
            context.startService(new Intent(context, IslandService.class));
        } catch (Throwable ignored) {
        }
    }
}
