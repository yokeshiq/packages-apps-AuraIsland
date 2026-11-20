package com.aura.island;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.HashSet;
import java.util.Set;

public class IslandNotificationListener extends NotificationListenerService {

    public interface Callback {
        void onAppMessage(String pkg, String sender, String message, PendingIntent contentIntent);
        void onAppMessageRemoved(String pkg);
    }

    private static volatile Callback sCallback;

    public static void setCallback(Callback cb) {
        sCallback = cb;
    }

    public static void ensureEnabled(Context context) {
        try {
            final ComponentName cn = new ComponentName(context, IslandNotificationListener.class);
            final android.app.NotificationManager nm =
                    context.getSystemService(android.app.NotificationManager.class);
            if (nm != null && !nm.isNotificationListenerAccessGranted(cn)) {
                nm.setNotificationListenerAccessGranted(cn, true, false);
            }
        } catch (Throwable t) {
            android.util.Log.w("AuraIsland", "grant notification listener failed", t);
        }
    }

    public static boolean isPackageAllowed(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) return false;
        if (Settings.Global.getInt(context.getContentResolver(), "aura_island_on", 1) == 0) return false;
        if (Settings.Global.getInt(context.getContentResolver(), "aura_island_notif", 1) == 0) return false;
        final String raw = Settings.Global.getString(
                context.getContentResolver(), "aura_island_notif_apps");
        if (TextUtils.isEmpty(raw)) return false;
        for (String item : raw.split(",")) {
            if (pkg.equals(item.trim())) return true;
        }
        return false;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.isOngoing()) return;
        final Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        final String pkg = sbn.getPackageName();
        android.util.Log.i("AuraIsland", "notif posted pkg=" + pkg + " allowed=" + isPackageAllowed(this, pkg));
        if (!isPackageAllowed(this, pkg)) return;

        final Bundle extras = n.extras;
        if (extras == null) return;

        String title = charSeq(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = charSeq(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (TextUtils.isEmpty(text)) {
            text = charSeq(extras.getCharSequence(Notification.EXTRA_TEXT));
        }

        // 兼容微信 / QQ MessagingStyle 联系人与消息提取
        if (n.isStyle(Notification.MessagingStyle.class)) {
            final Parcelable[] msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (msgs != null && msgs.length > 0) {
                var parsed = Notification.MessagingStyle.Message.getMessagesFromBundleArray(msgs);
                if (!parsed.isEmpty()) {
                    var last = parsed.get(parsed.size() - 1);
                    if (last.getSenderPerson() != null && !TextUtils.isEmpty(last.getSenderPerson().getName())) {
                        title = last.getSenderPerson().getName().toString();
                    }
                    if (!TextUtils.isEmpty(last.getText())) {
                        text = last.getText().toString();
                    }
                }
            }
        }

        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return;
        if (TextUtils.isEmpty(title)) {
            try {
                title = getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(pkg, 0)).toString();
            } catch (Throwable ignored) {
                title = pkg;
            }
        }
        if (TextUtils.isEmpty(text)) text = "发来一条新消息";

        final Callback cb = sCallback;
        android.util.Log.i("AuraIsland", "show island msg pkg=" + pkg + " title=" + title + " cb=" + (cb != null));
        if (cb != null) {
            cb.onAppMessage(pkg, title, text, n.contentIntent);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && sCallback != null) {
            sCallback.onAppMessageRemoved(sbn.getPackageName());
        }
    }

    private static String charSeq(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
