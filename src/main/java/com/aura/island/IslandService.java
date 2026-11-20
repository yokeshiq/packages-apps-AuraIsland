package com.aura.island;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.provider.Settings;

import java.util.List;

/**
 * Aura_A1 (v83): 灵动岛胶囊服务。
 *
 * 媒体会话播放中 → 胶囊（曲名+应用图标，点按展开/打开应用）。
 * 充电 → 胶囊只显示 5 秒（出现即开始计时，到点自动隐藏；再次插电重新计），
 * 不可展开。无事件隐藏。
 */
public class IslandService extends Service {
    private static final String TAG = "AuraIsland";
    private static final String CHANNEL = "island";
    /** SystemUI 广播的状态栏面板状态（面板展开时隐藏灵动岛）。 */
    private static final String SHADE_ACTION = "com.aura.island.SHADE_STATE";
    /** 充电胶囊显示时长。 */
    private static final long CHARGE_SHOW_MS = 5_000;

    private static final long SMS_CODE_SHOW_MS = 20_000;
    private String mSmsCode;
    private String mSmsSender;
    private long mSmsCodeUntilMs;
    private boolean mSmsReceiverRegistered;

    private static final long NOTIF_SHOW_MS = 5_500;
    private String mNotifPkg;
    private String mNotifSender;
    private String mNotifText;
    private android.app.PendingIntent mNotifIntent;
    private long mNotifUntilMs;

    private void clearAppNotif() {
        mNotifPkg = null;
        mNotifSender = null;
        mNotifText = null;
        mNotifIntent = null;
        mNotifUntilMs = 0;
    }

    private void openAppNotif() {
        final android.app.PendingIntent pi = mNotifIntent;
        final String pkg = mNotifPkg;
        clearAppNotif();
        if (mView != null) mView.hideAnimated();
        if (pi != null) {
            try {
                final android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                opts.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pi.send(this, 0, null, null, null, null, opts.toBundle());
                return;
            } catch (Throwable ignored) {
            }
        }
        if (pkg != null) {
            try {
                final Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // Keep the parser independent of Android for regression tests. Never log SMS content.
    static final class SmsCodeParser {
        private static final java.util.regex.Pattern KEYWORD = java.util.regex.Pattern.compile(
                "验证码|校验码|验证代码|动态密码|动态码|一次性密码|verification\\s*code|"
                + "security\\s*code|one[- ]time\\s*(?:password|code)|\\botp\\b|your\\s*code|login\\s*code",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile(
                "(?<![0-9A-Za-z])([0-9]{4,8})(?![0-9A-Za-z])");

        static String extract(String body) {
            if (body == null || body.length() > 4096) return null;
            java.util.regex.Matcher keyword = KEYWORD.matcher(body);
            int bestDistance = 81;
            String best = null;
            while (keyword.find()) {
                java.util.regex.Matcher number = DIGITS.matcher(body);
                while (number.find()) {
                    // Dates and times are not verification codes.
                    char before = number.start() > 0 ? body.charAt(number.start() - 1) : ' ';
                    char after = number.end() < body.length() ? body.charAt(number.end()) : ' ';
                    if (before == '-' || before == '/' || before == ':'
                            || after == '-' || after == '/' || after == ':' || after == '年') {
                        continue;
                    }
                    int distance = number.start() >= keyword.end()
                            ? number.start() - keyword.end() : keyword.start() - number.end();
                    distance = Math.max(0, distance) + (number.end() <= keyword.start() ? 4 : 0);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = number.group(1);
                    }
                }
            }
            return best;
        }
    }

    private final BroadcastReceiver mSmsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (getSwitch("aura_island_sms") == 0 || getSwitch("aura_island_on") == 0
                    || !android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION
                            .equals(intent.getAction())) return;
            try {
                final android.telephony.SmsMessage[] messages =
                        android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent);
                if (messages == null || messages.length == 0) return;
                StringBuilder body = new StringBuilder();
                String sender = messages[0] == null ? null : messages[0].getOriginatingAddress();
                for (android.telephony.SmsMessage message : messages) {
                    if (message == null || !java.util.Objects.equals(sender,
                            message.getOriginatingAddress())) return;
                    if (message.getMessageBody() != null) body.append(message.getMessageBody());
                }
                String code = SmsCodeParser.extract(body.toString());
                if (code == null) return;
                mSmsCode = code;
                mSmsSender = sender;
                mSmsCodeUntilMs = android.os.SystemClock.elapsedRealtime() + SMS_CODE_SHOW_MS;
                refresh();
            } catch (RuntimeException e) {
                // Do not include the message, PDU, sender or code in logs.
                Log.w(TAG, "Could not decode incoming SMS");
            }
        }
    };

    private void clearSmsCode() {
        if (mSmsCode != null && mView != null) mView.hideAnimated();
        mSmsCode = null;
        mSmsSender = null;
        mSmsCodeUntilMs = 0;
    }

    private void updateSmsReceiver() {
        boolean enabled = getSwitch("aura_island_sms") != 0
                && checkSelfPermission(android.Manifest.permission.RECEIVE_SMS)
                        == PackageManager.PERMISSION_GRANTED;
        if (enabled && !mSmsReceiverRegistered) {
            registerReceiver(mSmsReceiver, new IntentFilter(
                    android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
                    android.Manifest.permission.BROADCAST_SMS, mMain, Context.RECEIVER_EXPORTED);
            mSmsReceiverRegistered = true;
        } else if (!enabled) {
            if (mSmsReceiverRegistered) unregisterReceiver(mSmsReceiver);
            mSmsReceiverRegistered = false;
            clearSmsCode();
        }
    }

    private void copySmsCode() {
        if (mSmsCode == null || getSwitch("aura_island_sms") == 0
                || android.os.SystemClock.elapsedRealtime() >= mSmsCodeUntilMs
                || getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) return;
        android.content.ClipData clip = android.content.ClipData.newPlainText(
                getString(R.string.island_sms_code), mSmsCode);
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(clip);
        clearSmsCode();
        refresh();
    }

    private void fillSmsCode() {
        if (mSmsCode == null || getSwitch("aura_island_sms") == 0
                || android.os.SystemClock.elapsedRealtime() >= mSmsCodeUntilMs
                || getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) return;
        boolean filled = false;
        try {
            filled = IslandPlatformBridge.fillCode(mSmsCode);
        } catch (RuntimeException e) {
            Log.w(TAG, "Focused verification-code fill unavailable");
        }
        if (filled) {
            clearSmsCode();
            refresh();
        } else {
            android.widget.Toast.makeText(this, R.string.island_sms_fill_unavailable,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private IslandView mView;
    private MediaSessionManager mMsm;
    private Handler mMain;
    /** 充电胶囊的隐藏期限（elapsedRealtime）。 */
    private long mChargeShowUntilMs;
    private boolean mChargeExpired;
    /** 当前播放中的会话（传输按钮目标）。 */
    private MediaController mPlaying;
    private boolean mDestroyed;
    private boolean mTaskListenerRegistered;
    private final android.app.TaskStackListener mTaskListener =
            new android.app.TaskStackListener() {
        @Override public void onTaskStackChanged() { scheduleTaskRefresh(); }
        @Override public void onTaskMovedToFront(android.app.ActivityManager.RunningTaskInfo task) {
            scheduleTaskRefresh();
        }
        @Override public void onTaskFocusChanged(int taskId, boolean focused) {
            scheduleTaskRefresh();
        }
    };
    private final Runnable mTaskRefresh = this::refresh;

    private void scheduleTaskRefresh() {
        if (mMain != null && !mDestroyed) {
            mMain.removeCallbacks(mTaskRefresh);
            mMain.post(mTaskRefresh);
        }
    }

    private boolean isMediaAppForeground(String packageName) {
        try {
            final var task = android.app.ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return task != null && task.topActivity != null
                    && packageName.equals(task.topActivity.getPackageName());
        } catch (android.os.RemoteException | RuntimeException e) {
            Log.w(TAG, "foreground task query failed", e);
            return false;
        }
    }
    private android.database.ContentObserver mEnabledObserver;
    private final java.util.Map<MediaController, MediaController.Callback> mSessionCallbacks =
            new java.util.HashMap<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener mMsmListener =
            (controllers) -> attachSessionCallbacks(controllers);
    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
            refresh();
        }
    };

    /** 状态栏面板状态（来自 SystemUI）：面板展开时隐藏灵动岛。 */
    private final BroadcastReceiver mShadeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final boolean open = intent.getBooleanExtra("open", false);
            if (mShadeOpen == open) {
                return; // Expansion start and finish may broadcast the same state.
            }
            mShadeOpen = open;
            refresh();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 轮询周期：媒体回调整链不可靠（ERROR 恢复等场景不触发），1s 轮询兜底。 */
    private static final long POLL_MS = 1000;
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (!mDestroyed && getSwitch("aura_island_on") != 0) {
                mMain.postDelayed(this, POLL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        publishHomeTarget("");
        if (getSwitch("aura_island_on") == 0) {
            stopSelf();
            return;
        }
        startForegroundInternal();
        // 异步 Handler：主线程队列若残留 sync barrier（同步屏障），普通消息会被
        // 无限期挡住（表现为轮询/广播全停但绘制正常），异步消息不受影响。
        mMain = Handler.createAsync(Looper.getMainLooper());
        mEnabledObserver = new android.database.ContentObserver(mMain) {
            @Override
            public void onChange(boolean selfChange, android.net.Uri uri) {
                if (uri != null && uri.equals(Settings.Global.getUriFor(IslandCameraConfig.SETTING))
                        && mView != null) {
                    mView.hide();
                    mView = createIslandView();
                    publishHomeTarget("");
                }
                if (getSwitch("aura_island_on") == 0) {
                    stopSelf();
                } else {
                    updateSmsReceiver();
        IslandNotificationListener.ensureEnabled(this);
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                    new android.content.ComponentName(this, IslandNotificationListener.class));
        } catch (Throwable ignored) {}
        IslandNotificationListener.setCallback(new IslandNotificationListener.Callback() {
            @Override
            public void onAppMessage(String pkg, String sender, String message,
                    android.app.PendingIntent contentIntent) {
                if (mDestroyed || mMain == null) return;
                mMain.post(() -> {
                    if (isMediaAppForeground(pkg)) return;
                    mNotifPkg = pkg;
                    mNotifSender = sender;
                    mNotifText = message;
                    mNotifIntent = contentIntent;
                    mNotifUntilMs = android.os.SystemClock.elapsedRealtime() + NOTIF_SHOW_MS;
                    refresh();
                });
            }

            @Override
            public void onAppMessageRemoved(String pkg) {
                if (mDestroyed || mMain == null) return;
                mMain.post(() -> {
                    if (pkg != null && pkg.equals(mNotifPkg)) {
                        clearAppNotif();
                        refresh();
                    }
                });
            }
        });
        updateBattery(null);
        if (mMsm != null) {
            try {
                mMsm.addOnActiveSessionsChangedListener(mMsmListener, (ComponentName) null,
                        android.os.Handler.createAsync(getMainLooper()));
                attachSessionCallbacks(mMsm.getActiveSessions(null));
            } catch (Throwable t) {
                Log.w(TAG, "media listener failed", t);
            }
        }
        refresh();
        mMain.postDelayed(mPoll, POLL_MS);
    }

    private IslandView createIslandView() {
        return new IslandView(this, new IslandView.TransportCallback() {
            @Override
            public void onPlayPause() {
                final MediaController c = mPlaying;
                if (c == null) {
                    return;
                }
                final PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    c.getTransportControls().pause();
                } else {
                    c.getTransportControls().play();
                }
            }

            @Override
            public void onPrev() {
                final MediaController c = mPlaying;
                if (c != null) {
                    c.getTransportControls().skipToPrevious();
                }
            }

            @Override
            public void onNext() {
                final MediaController c = mPlaying;
                if (c != null) {
                    c.getTransportControls().skipToNext();
                }
            }

            @Override
            public void onCopySmsCode() { copySmsCode(); }

            @Override
            public void onFillSmsCode() { fillSmsCode(); }

            @Override
            public void onOpenNotification() { openAppNotif(); }

            @Override
            public void onDismissNotification() { clearAppNotif(); refresh(); }

            @Override
            public boolean onOpenApp(android.view.View source, boolean smallWindow) {
                return openPlayingApp(source, smallWindow);
            }

            @Override
            public boolean isPlaying() {
                final MediaController c = mPlaying;
                final PlaybackState s = c == null ? null : c.getPlaybackState();
                return s != null && s.getState() == PlaybackState.STATE_PLAYING;
            }
        });
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mView != null) {
            mView.hide();
            mView = createIslandView();
            publishHomeTarget("");
        }
        // Reload metrics, density and cutout before restoring portrait content.
        if (mMain != null) {
            mMain.post(this::refresh);
        }
    }

    /** 当前是否横屏（横屏隐藏胶囊：几何按竖屏校准）。 */
    private boolean isLandscape() {
        try {
            return getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (getSwitch("aura_island_on") == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        refresh();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        publishHomeTarget("");
        if (mMain != null) {
            mMain.removeCallbacksAndMessages(null);
        }
        if (mEnabledObserver != null) {
            getContentResolver().unregisterContentObserver(mEnabledObserver);
        }
        if (mTaskListenerRegistered) {
            try {
                android.app.ActivityTaskManager.getService().unregisterTaskStackListener(mTaskListener);
            } catch (android.os.RemoteException | RuntimeException ignored) { }
            mTaskListenerRegistered = false;
        }
        if (mSmsReceiverRegistered) {
            unregisterReceiver(mSmsReceiver);
            mSmsReceiverRegistered = false;
        }
        IslandNotificationListener.setCallback(null);
        clearAppNotif();
        clearSmsCode();
        clearSessionCallbacks();
        mPlaying = null;
        try {
            unregisterReceiver(mBatteryReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(mShadeReceiver);
        } catch (Throwable ignored) {
        }
        if (mMsm != null) {
            try {
                mMsm.removeOnActiveSessionsChangedListener(mMsmListener);
            } catch (Throwable ignored) {
            }
        }
        if (mView != null) {
            mView.hide();
            mView = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private boolean openPlayingApp(android.view.View source, boolean smallWindow) {
        final MediaController controller = mPlaying;
        if (controller == null) {
            return false;
        }
        final String pkg = controller.getPackageName();
        final int[] location = new int[2];
        source.getLocationOnScreen(location);
        final android.graphics.Rect bounds = new android.graphics.Rect(location[0], location[1],
                location[0] + source.getWidth(), location[1] + source.getHeight());
        smallWindow &= IslandPlatformBridge.smallWindowAvailable();
        if (smallWindow) {
            // The View schedules its normal launch fallback after this callback returns.
            mMain.post(() -> { if (mView != null) mView.onAppLaunchPreparationPending(); });
            prepareSmallWindowReplacement(pkg, () -> {
                if (!mDestroyed && mPlaying != null && pkg.equals(mPlaying.getPackageName())) {
                    if (mView != null) mView.onAppLaunchPrepared();
                    openPlayingAppPrepared(controller, bounds, true);
                } else if (mView != null) {
                    mView.onAppLaunchPreparationFailed();
                }
            });
            return true;
        }
        return openPlayingAppPrepared(controller, bounds, false);
    }

    private void prepareSmallWindowReplacement(String pkg, Runnable ready) {
        final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable failed = () -> {
            if (completed.compareAndSet(false, true) && !mDestroyed) {
                if (mView != null) mView.onAppLaunchPreparationFailed();
                android.widget.Toast.makeText(this, R.string.island_open_failed,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        };
        mMain.postDelayed(failed, 4500);
        sendOrderedBroadcastAsUser(new Intent("com.android.systemui.AURA_PREPARE_SMALL_WINDOW")
                .setPackage("com.android.systemui").addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("user_id", android.os.UserHandle.myUserId()).putExtra("package", pkg),
                android.os.UserHandle.of(android.os.UserHandle.myUserId()), null,
                new android.content.BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        if (getResultCode() != 1) { failed.run(); return; }
                        if (!completed.compareAndSet(false, true)) return;
                        mMain.removeCallbacks(failed);
                        if (!mDestroyed && !getSystemService(android.app.KeyguardManager.class)
                                .isKeyguardLocked()) ready.run();
                    }
                }, mMain, 0, null, null);
    }

    private boolean openPlayingAppPrepared(MediaController controller, android.graphics.Rect bounds,
            boolean smallWindow) {
        final String pkg = controller.getPackageName();
        final MediaLaunchTransition transition = new MediaLaunchTransition(bounds, pkg,
                smallWindow, getResources().getDisplayMetrics().density, () -> {
            if (mView != null) {
                mView.onAppLaunchAnimationStart();
            }
        });
        final android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic()
                .setRemoteTransition(new android.window.RemoteTransition(
                        transition, "AuraIslandMediaLaunch"))
                .setOverrideTaskTransition(true);
        options.setLaunchDisplayId(0);
        options.setLaunchWindowingMode(smallWindow
                ? android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
                : android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN);
        if (smallWindow) {
            final android.view.WindowMetrics metrics = getSystemService(
                    android.view.WindowManager.class).getMaximumWindowMetrics();
            final android.graphics.Rect screen = metrics.getBounds();
            final android.graphics.Insets bars = metrics.getWindowInsets()
                    .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            final float density = getResources().getDisplayMetrics().density;
            final int availableWidth = screen.width() - bars.left - bars.right;
            final int availableHeight = screen.height() - bars.top - bars.bottom;
            // Host rotation must not change the normal app canvas aspect ratio.
            // WM handles the app's requested orientation and fits its uniform scale.
            final int width = Math.min(Math.min(screen.width(), screen.height()), availableWidth);
            final int height = Math.min(Math.max(availableWidth, availableHeight),
                    Math.round(width * 16f / 9f));
            float scale = Math.max(50, Math.min(90, android.os.SystemProperties.getInt(
                    "ro.aura.small_window_scale", 74))) / 100f;
            for (var task : android.app.ActivityTaskManager.getInstance().getTasks(32)) {
                if (task.displayId == 0 && task.userId == android.os.UserHandle.myUserId()
                        && task.topActivity != null
                        && pkg.equals(task.topActivity.getPackageName()) && task.isFreeform()
                        && IslandPlatformBridge.taskScale(task) < 1f && !IslandPlatformBridge.mini(task)) {
                    scale = IslandPlatformBridge.taskScale(task);
                    break;
                }
            }
            // Bounds keep a screen-space origin while their width is logical.
            // Centre the projected width, not the full-sized app canvas.
            final int left = screen.left + bars.left
                    + Math.round((availableWidth - width * scale) / 2f);
            final int top = screen.top + bars.top + Math.round(8f * density);
            options.setLaunchBounds(new android.graphics.Rect(left, top,
                    left + width, top + height));
        }
        options.setPendingIntentBackgroundActivityStartMode(
                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        android.app.PendingIntent launch = controller.getSessionActivity();
        String entry = "session";
        if (launch == null || !launch.isActivity()) {
            launch = null;
            try {
                for (var notification : getSystemService(NotificationManager.class)
                        .getActiveNotifications()) {
                    final android.app.PendingIntent candidate =
                            notification.getNotification().contentIntent;
                    if (pkg.equals(notification.getPackageName()) && candidate != null
                            && candidate.isActivity()) {
                        launch = candidate;
                        entry = "notification";
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "media notification entry unavailable", t);
            }
        }
        if (launch != null) {
            try {
                launch.send(this, 0, null, null, null, null, options.toBundle());
                Log.d(TAG, "open media app package=" + pkg + " entry=" + entry
                        + " animation=remote-card");
                return true;
            } catch (android.app.PendingIntent.CanceledException | RuntimeException e) {
                Log.w(TAG, "media session entry cancelled; use launcher", e);
            }
        }
        try {
            final Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent, options.toBundle());
                Log.d(TAG, "open media app package=" + pkg
                        + " entry=launcher animation=remote-card");
                return true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "open media app failed: " + pkg, e);
        }
        Log.w(TAG, "no usable media app entry package=" + pkg);
        android.widget.Toast.makeText(this, R.string.island_open_failed,
                android.widget.Toast.LENGTH_SHORT).show();
        return false;
    }

    /** Animate the actual app leash, including an existing task coming to the front. */
    private static final class MediaLaunchTransition extends android.window.IRemoteTransition.Stub {
        private final Handler main = Handler.createAsync(Looper.getMainLooper());
        private final android.graphics.Rect source;
        private final String packageName;
        private final boolean smallWindow;
        private final float density;
        private Runnable onStart;
        private android.window.IRemoteTransitionFinishedCallback callback;
        private android.animation.ValueAnimator animator;
        private final java.util.List<android.window.TransitionInfo.Change> targets =
                new java.util.ArrayList<>();
        private android.graphics.Point rootOffset = new android.graphics.Point();
        private boolean finished;
        private android.window.TransitionInfo transitionInfo;

        MediaLaunchTransition(android.graphics.Rect source, String packageName,
                boolean smallWindow, float density, Runnable onStart) {
            this.source = new android.graphics.Rect(source);
            this.packageName = packageName;
            this.smallWindow = smallWindow;
            this.density = density;
            this.onStart = onStart;
        }

        @Override
        public void startAnimation(android.os.IBinder token, android.window.TransitionInfo info,
                android.view.SurfaceControl.Transaction transaction,
                android.window.IRemoteTransitionFinishedCallback finishCallback) {
            main.post(() -> {
                if (finished) {
                    transaction.apply();
                    transaction.close();
                    try { finishCallback.onTransitionFinished(null, null); }
                    catch (android.os.RemoteException ignored) { }
                    info.releaseAllSurfaces();
                    return;
                }
                callback = finishCallback;
                transitionInfo = info;
                try {
                    final int rootIndex = info.findRootIndex(0);
                    if (rootIndex >= 0) {
                        rootOffset = new android.graphics.Point(info.getRoot(rootIndex).getOffset());
                    }
                    int layer = 10000;
                    for (var change : info.getChanges()) {
                        final int mode = change.getMode();
                        final var task = change.getTaskInfo();
                        if ((mode == android.view.WindowManager.TRANSIT_OPEN
                                || mode == android.view.WindowManager.TRANSIT_TO_FRONT
                                || mode == android.view.WindowManager.TRANSIT_CHANGE)
                                && task != null && task.displayId == 0
                                && rootIndex >= 0
                                && task.topActivity != null
                                && packageName.equals(task.topActivity.getPackageName())
                                && android.window.TransitionInfo.isIndependent(change, info)
                                && !change.getEndAbsBounds().isEmpty()) {
                            targets.add(change);
                            transaction.reparent(change.getLeash(),
                                    info.getRoot(rootIndex).getLeash());
                            transaction.setLayer(change.getLeash(), layer--);
                            transaction.show(change.getLeash());
                        }
                    }
                    applyFrame(transaction, 0f);
                    transaction.apply();
                    transaction.close();
                    if (onStart != null) {
                        final Runnable start = onStart;
                        onStart = null;
                        start.run();
                    }
                    Log.i(TAG, "remote card start source=" + source + " targets=" + targets.size()
                            + (targets.isEmpty() ? "" : " end=" + targets.get(0).getEndAbsBounds()));
                    if (targets.isEmpty()) {
                        finish();
                        return;
                    }
                    animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
                    animator.setDuration(420);
                    animator.setInterpolator(new android.view.animation.PathInterpolator(
                            0.16f, 0f, 0.2f, 1f));
                    animator.addUpdateListener(value -> {
                        try (var frame = new android.view.SurfaceControl.Transaction()) {
                            applyFrame(frame, (float) value.getAnimatedValue());
                            frame.apply();
                        } catch (RuntimeException e) {
                            Log.w(TAG, "remote card frame failed", e);
                            animator.cancel();
                        }
                    });
                    animator.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(android.animation.Animator animation) {
                            finish();
                        }
                    });
                    animator.start();
                } catch (RuntimeException e) {
                    Log.w(TAG, "remote card start failed", e);
                    transaction.close();
                    finish();
                }
            });
        }

        private void applyFrame(android.view.SurfaceControl.Transaction transaction, float progress) {
            for (var change : targets) {
                final android.graphics.Rect end = change.getEndAbsBounds();
                final float endScale = targetScale(change);
                final float width = source.width()
                        + (end.width() * endScale - source.width()) * progress;
                final float height = source.height()
                        + (end.height() * endScale - source.height()) * progress;
                final float scale = width / Math.max(1, end.width());
                final float x = source.left + (end.left - source.left) * progress - rootOffset.x;
                final float y = source.top + (end.top - source.top) * progress - rootOffset.y;
                final android.view.SurfaceControl leash = change.getLeash();
                transaction.setMatrix(leash, scale, 0f, 0f, scale);
                transaction.setPosition(leash, x, y);
                transaction.setWindowCrop(leash, end.width(), Math.round(height / scale));
                final float endRadius = endScale < 1f ? 24f * density : 0f;
                transaction.setCornerRadius(leash,
                        (48f * (1f - progress) + endRadius * progress) / scale);
                transaction.setAlpha(leash, Math.min(1f, progress * 6f));
            }
        }

        private float targetScale(android.window.TransitionInfo.Change change) {
            final var task = change.getTaskInfo();
            if (!smallWindow || task == null || !task.isFreeform()) return 1f;
            final float scale = IslandPlatformBridge.transitionScale(change);
            return Float.isFinite(scale) && scale > 0f && scale < 1f ? scale : 1f;
        }

        private void finish() {
            if (finished) return;
            finished = true;
            try (var transaction = new android.view.SurfaceControl.Transaction();
                    var endTransaction = new android.view.SurfaceControl.Transaction()) {
                for (var change : targets) {
                    if (!change.getLeash().isValid()) continue;
                    final android.graphics.Rect end = change.getEndAbsBounds();
                    final float scale = targetScale(change);
                    for (var tx : new android.view.SurfaceControl.Transaction[] {
                            transaction, endTransaction}) {
                        tx.setMatrix(change.getLeash(), scale, 0f, 0f, scale);
                        tx.setPosition(change.getLeash(), end.left - rootOffset.x,
                                end.top - rootOffset.y);
                        tx.setWindowCrop(change.getLeash(), scale < 1f
                                ? new android.graphics.Rect(0, 0, end.width(), end.height()) : null);
                        tx.setCornerRadius(change.getLeash(), scale < 1f ? 24f * density / scale : 0f);
                        tx.setAlpha(change.getLeash(), 1f);
                    }
                }
                transaction.apply();
                if (callback != null) callback.onTransitionFinished(null, endTransaction);
                callback = null;
            } catch (android.os.RemoteException e) {
                Log.w(TAG, "remote card finish callback failed", e);
                callback = null;
            } catch (RuntimeException e) {
                Log.w(TAG, "remote card cleanup failed", e);
            }
            try {
                if (callback != null) callback.onTransitionFinished(null, null);
            } catch (android.os.RemoteException ignored) { }
            targets.clear();
            callback = null;
            onStart = null;
            animator = null;
            if (transitionInfo != null) transitionInfo.releaseAllSurfaces();
            transitionInfo = null;
            Log.i(TAG, "remote card finish");
        }

        @Override
        public void mergeAnimation(android.os.IBinder token, android.window.TransitionInfo info,
                android.view.SurfaceControl.Transaction transaction, android.os.IBinder mergeTarget,
                android.window.IRemoteTransitionFinishedCallback finishCallback) {
            // Let Shell queue unrelated transitions until this animation finishes.
            transaction.close();
        }

        @Override
        public void takeOverAnimation(android.os.IBinder token, android.window.TransitionInfo info,
                android.view.SurfaceControl.Transaction transaction,
                android.window.IRemoteTransitionFinishedCallback finishCallback,
                android.window.WindowAnimationState[] states) {
            startAnimation(token, info, transaction, finishCallback);
        }

        @Override
        public void onTransitionConsumed(android.os.IBinder token, boolean aborted) {
            main.post(() -> {
                if (animator != null) animator.cancel();
                finish();
            });
        }
    }

    private void startForegroundInternal() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        final NotificationChannel ch = new NotificationChannel(CHANNEL,
                getString(R.string.island_channel), NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(ch);
        final Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.island_running))
                .setOngoing(true)
                .build();
        // 必须用开机白名单内的类型：BOOT_COMPLETED 启动前台服务时 mediaPlayback 会被
        // ActiveServices 拒绝；remoteMessaging 在白名单内且权限要求最干净
        startForeground(1, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
    }

    private void clearSessionCallbacks() {
        for (var entry : mSessionCallbacks.entrySet()) {
            try {
                entry.getKey().unregisterCallback(entry.getValue());
            } catch (Throwable ignored) {
            }
        }
        mSessionCallbacks.clear();
    }

    private void attachSessionCallbacks(List<MediaController> controllers) {
        clearSessionCallbacks();
        if (mDestroyed) {
            return;
        }
        if (controllers != null) {
            for (MediaController c : controllers) {
                final MediaController.Callback callback = new MediaController.Callback() {
                    @Override
                    public void onPlaybackStateChanged(PlaybackState state) {
                        if (!mDestroyed) {
                            mMain.post(IslandService.this::refresh);
                        }
                    }
                };
                try {
                    c.registerCallback(callback, mMain);
                    mSessionCallbacks.put(c, callback);
                } catch (Throwable ignored) {
                }
            }
        }
        refresh();
    }

    private void updateBattery(Intent intent) {
        try {
            final Intent i = intent != null
                    ? intent : registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) {
                return;
            }
            final int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            mCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL) ? 1 : 0;
            mBatteryPct = Math.round(i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100f
                    / Math.max(1, i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)));
        } catch (Throwable t) {
            Log.w(TAG, "battery read failed", t);
        }
    }

    /** 状态栏面板（下拉通知/QS）是否展开：展开时灵动岛整体隐藏。 */
    private boolean mShadeOpen = false;
    /** 暂停自动关闭阈值。 */
    private static final long PAUSE_CLOSE_MS = 10_000;
    /** 媒体消失宽限期（避免切歌瞬间的抖动）。 */
    private static final long MEDIA_GONE_GRACE_MS = 1_500;
    /** 进入暂停状态的时间（0=未暂停或已在播）。 */
    private long mPausedSinceMs = 0;
    /** 媒体消失的起始时间（0=有媒体或未开始计时）。 */
    private long mMediaGoneSinceMs = 0;

    int mCharging = -1;
    int mBatteryPct = -1;

    private String mHomeTarget;

    private void publishHomeTarget(String target) {
        if (target.equals(mHomeTarget)) return;
        android.provider.Settings.Global.putString(getContentResolver(),
                "aura_island_home_target", target);
        mHomeTarget = target;
    }

    /** 重算当前该显示什么并更新胶囊。 */
    private void refresh() {
        if (mDestroyed || mView == null) {
            return;
        }
        if (getSwitch("aura_island_on") == 0) {
            stopSelf();
            return;
        }
        String homeTarget = "";
        try {
            if (isLandscape()) {
                // 横屏：直接隐藏（胶囊几何是按竖屏校准的）
                mView.hide();
                return;
            }
            if (mShadeOpen) {
                // 状态栏面板展开：动画收起（不是瞬间消失）
                mView.hideAnimated();
                return;
            }
            final long now = android.os.SystemClock.elapsedRealtime();
            if (mSmsCode != null && (getSwitch("aura_island_sms") == 0
                    || now >= mSmsCodeUntilMs)) clearSmsCode();
            if (mSmsCode != null) {
                if (getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) {
                    mView.hideAnimated();
                } else {
                    mView.showSmsCode(mSmsCode, mSmsSender);
                }
                return;
            }
            if (mNotifPkg != null && (getSwitch("aura_island_notif") == 0
                    || now >= mNotifUntilMs || isMediaAppForeground(mNotifPkg))) {
                clearAppNotif();
            }
            if (mNotifPkg != null) {
                if (getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) {
                    mView.hideAnimated();
                } else {
                    mView.showAppNotification(mNotifPkg, mNotifSender, mNotifText,
                            loadIcon(mNotifPkg));
                }
                return;
            }
            final MediaController playing = findPlaying();
            mPlaying = playing;
            if (playing == null) {
                // 媒体消失：宽限期内维持现状，避免切歌瞬间闪动
                if (mMediaGoneSinceMs == 0) {
                    mMediaGoneSinceMs = now;
                    return;
                }
                if (now - mMediaGoneSinceMs < MEDIA_GONE_GRACE_MS) {
                    return;
                }
            } else {
                mMediaGoneSinceMs = 0;
            }
            if (playing != null && isSessionAlive(playing)
                    && getSwitch("aura_island_media") != 0) {
                final PlaybackState nowState = playing.getPlaybackState();
                final boolean isPlaying = nowState != null
                        && nowState.getState() == PlaybackState.STATE_PLAYING;
                if (!isPlaying) {
                    // 暂停：满 10 秒自动关闭（此后保持隐藏，恢复播放会重新出现）
                    if (mPausedSinceMs == 0) {
                        mPausedSinceMs = now;
                    }
                    if (now - mPausedSinceMs > PAUSE_CLOSE_MS) {
                        mView.hideAnimated();
                        return;
                    }
                } else {
                    mPausedSinceMs = 0;
                }
                final var md = playing.getMetadata();
                final String title = md == null ? "" : str(md.getDescription().getTitle());
                final String sub = md == null ? "" : str(md.getDescription().getSubtitle());
                final PlaybackState ps = playing.getPlaybackState();
                // 专辑缩略图（无图传 null，视图用默认音符图）
                android.graphics.drawable.Drawable thumb = null;
                try {
                    if (md != null) {
                        android.graphics.Bitmap bmp =
                                md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
                        if (bmp == null) {
                            bmp = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
                        }
                        if (bmp != null) {
                            thumb = new android.graphics.drawable.BitmapDrawable(
                                    getResources(), bmp);
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (thumb == null) {
                    // 酷狗/QQ音乐等把真实封面放在通知 largeIcon，会话元数据里没有
                    thumb = loadNotificationArt(playing.getPackageName());
                }
                // Keep the logical home destination while the app is foreground and
                // the overlay is absent. Launcher still needs it for swipe-to-home.
                final android.graphics.Rect homeBounds = mView.getHomeTargetBounds();
                if (isPlaying) {
                    homeTarget = playing.getPackageName() + "|" + homeBounds.left + "|"
                            + homeBounds.top + "|" + homeBounds.right + "|" + homeBounds.bottom;
                }
                if (isMediaAppForeground(playing.getPackageName())) {
                    // Reuse the camera morph; repeated task callbacks must not restart
                    // it. The view clears input during closing and removes the window
                    // at the end, while the logical home destination remains available.
                    mView.hideAnimated();
                    mChargeShowUntilMs = 0;
                    return;
                }
                mView.showMedia(title, sub, thumb, playing.getPackageName(), isPlaying);
                long dur = 0;
                try {
                    if (md != null) {
                        dur = md.getLong(
                                android.media.MediaMetadata.METADATA_KEY_DURATION);
                    }
                } catch (Throwable ignored) {
                }
                mView.updateProgress(ps == null ? 0 : ps.getPosition(), dur);
                mChargeShowUntilMs = 0; // 媒体优先：结束后下一轮重新开 5 秒充电窗口
                return;
            }
            if (mCharging == 1 && getSwitch("aura_island_charge") != 0) {
                if (mChargeShowUntilMs == 0) {
                    // 开一个 5 秒充电显示窗口（媒体分支会把它清 0，因此媒体播完
                    // 后会重新开窗，不会出现“既不显示也不隐藏”的卡死态）
                    mChargeExpired = false;
                    mChargeShowUntilMs = now + CHARGE_SHOW_MS;
                }
                if (!mChargeExpired) {
                    if (now <= mChargeShowUntilMs) {
                        mView.showCharging(mBatteryPct);
                    } else {
                        // 到期只回收一次：hideAnimated 有 mShown 守卫，不能每秒重放
                        mChargeExpired = true;
                        mView.hideAnimated();
                    }
                }
                return;
            }
            mPausedSinceMs = 0;
            mChargeShowUntilMs = 0;
            mChargeExpired = false;
            mView.hideAnimated(); // 无内容：播放收起动画
        } catch (Throwable t) {
            Log.w(TAG, "refresh failed", t);
        } finally {
            publishHomeTarget(homeTarget);
        }
    }

    /** 从媒体通知里取封面（largeIcon / EXTRA_PICTURE）；需 MEDIA_CONTENT_CONTROL。 */
    private android.graphics.drawable.Drawable loadNotificationArt(String pkg) {
        try {
            final android.app.NotificationManager nm =
                    getSystemService(android.app.NotificationManager.class);
            if (nm == null) {
                return null;
            }
            for (android.service.notification.StatusBarNotification sbn
                    : nm.getActiveNotifications()) {
                if (!pkg.equals(sbn.getPackageName())) {
                    continue;
                }
                final android.app.Notification n = sbn.getNotification();
                if (n == null) {
                    continue;
                }
                final android.graphics.drawable.Icon ic = n.getLargeIcon();
                if (ic != null) {
                    final android.graphics.drawable.Drawable d = ic.loadDrawable(this);
                    if (d != null) {
                        return d;
                    }
                }
                if (n.extras != null) {
                    final Object pic = n.extras.get(android.app.Notification.EXTRA_PICTURE);
                    if (pic instanceof android.graphics.Bitmap) {
                        return new android.graphics.drawable.BitmapDrawable(
                                getResources(), (android.graphics.Bitmap) pic);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "notification art failed", t);
        }
        return null;
    }

    /** 会话是否仍然可用（播放结束后 Twelve 会注销会话，旧引用变死）。 */
    private boolean isSessionAlive(MediaController c) {
        try {
            final PlaybackState s = c.getPlaybackState();
            return s != null && s.getState() != PlaybackState.STATE_ERROR
                    && s.getState() != PlaybackState.STATE_STOPPED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读取 Settings.Global 里的灵动岛开关（缺省开启）。 */
    private int getSwitch(String key) {
        try {
            return android.provider.Settings.Global.getInt(getContentResolver(), key, 1);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private MediaController findPlaying() {
        try {
            final List<MediaController> list = mMsm.getActiveSessions(null);
            MediaController buffering = null;
            MediaController paused = null;
            for (MediaController c : list) {
                final PlaybackState s = c.getPlaybackState();
                if (s == null) {
                    continue;
                }
                if (s.getState() == PlaybackState.STATE_PLAYING) {
                    return c;
                }
                if (s.getState() == PlaybackState.STATE_BUFFERING && buffering == null) {
                    buffering = c;
                } else if (s.getState() == PlaybackState.STATE_PAUSED && paused == null) {
                    paused = c;
                }
            }
            return buffering != null ? buffering : paused;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Drawable loadIcon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString();
    }
}
