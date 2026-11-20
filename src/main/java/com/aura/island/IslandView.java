package com.aura.island;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 灵动岛胶囊视图（v11）：
 * - 窗口 TYPE_MAGNIFICATION_OVERLAY(2039)：天然可信叠层，高于状态栏（挖孔正中
 *   为系统 ScreenDecor 死区，点左右两段可点）。
 * - 收起态 140×46dp @ y=12：[专辑缩略图 44dp][摄像头空档 52dp][等化器 44dp]
 *   （充电时为 [标题 44dp][空档][电量 44dp]，不可展开，5 秒后服务收回）。
 * - 展开态 328×152dp @ y=108：仿控制中心媒体卡片——左侧方形封面 + 右侧
 *   [应用名 / 歌名 / 歌手 / 进度条 / 上一首·播放暂停·下一首]。
 * - 触摸：父容器消费 DOWN 保证 UP 回流，UP 按坐标统一分发（hitChild 命中
 *   按钮=等效点击；背板=只重置计时；收起态点按=展开；展开态上滑=收回）。
 */
public class IslandView {
    private static final int BG = Color.argb(238, 12, 12, 14);
    private static final int EXPANDED_BG = Color.rgb(12, 12, 14);
    private static final float SWIPE_UP_THRESHOLD_DP = 35f;
    /** 展开卡片 + 传输按钮开关（用户要求重新打开；如需再关闭改回 false）。 */
    private static final boolean FEATURE_EXPAND = true;

    /** 媒体传输控制回调（服务实现）。 */
    public interface TransportCallback {
        void onPlayPause();

        void onPrev();

        void onNext();

        boolean isPlaying();

        boolean onOpenApp(View source, boolean smallWindow);

        void onCopySmsCode();

        void onFillSmsCode();

        default void onOpenNotification() {}

        default void onDismissNotification() {}
    }

    /** 三柱等化器动画（小尺寸）。 */
    private static final class EqView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private float mStepF;
        private boolean mPlaying;
        /** 帧间隔(ms)：默认 66≈15fps；debug.aura.island.tick 可覆盖（调参用）。 */
        private final int mTickMs = readIntProp("debug.aura.island.tick", 66, 16, 200);
        private long mLastT;
        private long mPhaseMs;

        EqView(Context c) {
            super(c);
            mPaint.setColor(0xFFDDDDDD);
        }

        /** 定时器驱动：完全不经过 Choreographer（避免 90Hz 空转）。 */
        private final Runnable mTick = new Runnable() {
            @Override
            public void run() {
                if (!mPlaying) {
                    return;
                }
                final long now = android.os.SystemClock.uptimeMillis();
                if (mLastT == 0) {
                    mLastT = now;
                }
                mPhaseMs = (mPhaseMs + (now - mLastT)) % 800;
                mLastT = now;
                final float t = mPhaseMs / 800f;
                mStepF = t < 0.5f ? t * 2f : (1f - t) * 2f;
                invalidate();
                postDelayed(this, mTickMs);
            }
        };

        void setPlaying(boolean playing) {
            if (mPlaying == playing) {
                return;
            }
            mPlaying = playing;
            if (playing) {
                mLastT = 0;
                post(mTick);
            } else {
                removeCallbacks(mTick);
            }
            invalidate();
        }

        private static int readIntProp(String key, int def, int min, int max) {
            int v = def;
            try {
                final String s = android.os.SystemProperties.get(key, "");
                if (!s.isEmpty()) {
                    v = Integer.parseInt(s.trim());
                }
            } catch (Throwable ignored) {
            }
            return Math.max(min, Math.min(max, v));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth();
            final int h = getHeight();
            // 固定尺寸：柱宽 5px、柱间 4px，总宽 23px，在容器内水平居中
            final float bw = 5f * getResources().getDisplayMetrics().density;
            final float gap = 3f * getResources().getDisplayMetrics().density;
            final float total = bw * 3 + gap * 2;
            final float maxH = Math.min(h, 17f * getResources().getDisplayMetrics().density) * 0.95f;
            final float xBase = (w - total) / 2f;
            for (int i = 0; i < 3; i++) {
                float f = 0.15f + i * 0.05f;
                if (mPlaying) {
                    // 三柱各取 mStepF(0..1 连续往返) 的错相正弦：平滑无跳变
                    final float ph = mStepF * (float) Math.PI;
                    final float hv = 0.5f + 0.5f * (float) Math.sin(ph + i * 1.9f);
                    f = 0.2f + 0.5f * hv;
                }
                final float bh = Math.max(bw, maxH * f);
                final float x0 = xBase + i * (bw + gap);
                mRect.set(x0, (h - bh) / 2f, x0 + bw, (h + bh) / 2f);
                canvas.drawRoundRect(mRect, bw / 2f, bw / 2f, mPaint);
            }
        }

        void stopAnim() {
            mPlaying = false;
            removeCallbacks(mTick);
        }
    }

    /** 细进度条（播放位置）。 */
    private static final class SeekView extends View {
        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private float mFrac;

        SeekView(Context c) {
            super(c);
            mTrack.setColor(0xFF3C3C40);
            mFill.setColor(0xFFEAEAEA);
        }

        void setFrac(float f) {
            mFrac = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int h = getHeight();
            mRect.set(0, (h - h) / 2f, getWidth(), h);
            canvas.drawRoundRect(mRect, h / 2f, h / 2f, mTrack);
            if (mFrac > 0f) {
                mRect.set(0, 0, Math.max(h, getWidth() * mFrac), h);
                canvas.drawRoundRect(mRect, h / 2f, h / 2f, mFill);
            }
        }
    }

    private final Context mContext;
    private final WindowManager mWm;
    private WindowManager.LayoutParams mWindowParams;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private FrameLayout mRoot;
    /** 软件渲染层开关是否已生效（调试用）。 */
    private boolean mSwApplied;
    /** 收起动画进行中：动画结束回调据此决定是否真的移除（媒体恢复时要撤销关闭）。 */
    private boolean mClosing;
    /** 动画时长倍数（录制/调参用）：debug.aura.island.slow，默认 1。 */
    private final int mSlow = readPropInt("debug.aura.island.slow", 1, 1, 30);
    private LinearLayout mCard;
    private GradientDrawable mBg;
    private LinearLayout mHeader;
    private LinearLayout mBody;
    private LinearLayout mSmsBody;
    private TextView mSmsSource;
    private TextView mSmsCodeText;
    private LinearLayout mNotifBody;
    private ImageView mNotifIcon;
    private TextView mNotifSender;
    private TextView mNotifContent;
    private View mHandle;
    private CameraMorphView mMorphView;
    private LinearLayout mTextCol;
    private TextView mTitle;
    private TextView mSub;
    private View mSpacer;
    private LinearLayout mRightBox;
    private TextView mRightText;
    private LinearLayout mTransport;
    private ImageView mBtnPrev;
    private ImageView mBtnPlay;
    private ImageView mBtnNext;
    private ImageView mThumbIcon;
    private EqView mEq;
    private ImageView mArt;
    private TextView mAppName;
    private TextView mTitleBig;
    private TextView mArtist;
    private SeekView mSeek;
    private boolean mShown;
    private boolean mExpanded;
    private boolean mAnimating;
    private boolean mCollapseAfterAnimation;
    private boolean mPlaying;
    private boolean mLaunching;
    private String mPkg;
    private int mMode; // 0 none, 1 media, 2 charging, 3 SMS verification code
    private ValueAnimator mAnim;
    private ValueAnimator mAnimY;
    private TransportCallback mTransportCb;
    /** 上次展示指纹，相同则跳过一切视图操作。 */
    private String mLastFingerprint;
    private int mLastProgressPct = -1;

    private final float mDensity;
    private final IslandCameraConfig mCameraConfig;
    private final IslandGeometry mGeometry;

    private int dp(float value) { return Math.round(value * mDensity); }
    private float cameraPivotX() {
        return mGeometry.cameraX - mGeometry.window.left
                - ((LayoutParams) mCard.getLayoutParams()).leftMargin;
    }
    private float cameraPivotY() {
        return mGeometry.cameraY - mGeometry.window.top
                - ((LayoutParams) mCard.getLayoutParams()).topMargin;
    }

    public IslandView(Context context, TransportCallback cb) {
        mContext = context;
        mTransportCb = cb;
        mWm = context.getSystemService(WindowManager.class);
        mDensity = context.getResources().getDisplayMetrics().density;
        mCameraConfig = new IslandCameraConfig(context);
        mGeometry = mCameraConfig.geometry;
    }

    private WindowManager.LayoutParams lp(boolean expanded) {
        // 精确窗口尺寸（wrap 在 NO_LIMITS 下会被撑大导致全屏合成、CPU 居高不下）
        // A single bounded surface includes the capsule, camera and expanded card.
        // Reuse it across transitions instead of creating a new ViewRoot each phase.
        final int winW = mGeometry.window.width;
        final int winH = mGeometry.window.height;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                winW, winH,
                2039, // TYPE_MAGNIFICATION_OVERLAY：天然可信叠层，高于状态栏
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = mCameraConfig.screenLeft + mGeometry.window.left;
        lp.y = mCameraConfig.screenTop + mGeometry.window.top;
        return lp;
    }

    private TextView makeText(float sizeSp, boolean bold, int color) {
        final TextView t = new TextView(mContext);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setSingleLine(true);
        return t;
    }

    /** Flat transport glyphs without the beveled framework drawable edges. */
    private static final class TransportIcon extends android.graphics.drawable.Drawable {
        private final int mIcon;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path mPath = new android.graphics.Path();

        TransportIcon(int icon) {
            mIcon = icon;
            mPaint.setColor(Color.WHITE);
        }

        @Override
        public void draw(Canvas canvas) {
            final android.graphics.Rect bounds = getBounds();
            if (bounds.isEmpty()) {
                return;
            }
            final int save = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 48f, bounds.height() / 48f);
            if (mIcon == android.R.drawable.ic_media_pause) {
                canvas.drawRoundRect(16f, 12f, 21f, 36f, 1f, 1f, mPaint);
                canvas.drawRoundRect(27f, 12f, 32f, 36f, 1f, 1f, mPaint);
            } else {
                mPath.reset();
                if (mIcon == android.R.drawable.ic_media_previous) {
                    canvas.drawRoundRect(13f, 12f, 17f, 36f, 1f, 1f, mPaint);
                    mPath.moveTo(17f, 24f);
                    mPath.lineTo(34f, 12f);
                    mPath.lineTo(34f, 36f);
                } else if (mIcon == android.R.drawable.ic_media_next) {
                    canvas.drawRoundRect(31f, 12f, 35f, 36f, 1f, 1f, mPaint);
                    mPath.moveTo(31f, 24f);
                    mPath.lineTo(14f, 12f);
                    mPath.lineTo(14f, 36f);
                } else {
                    mPath.moveTo(19f, 12f);
                    mPath.lineTo(36f, 24f);
                    mPath.lineTo(19f, 36f);
                }
                mPath.close();
                canvas.drawPath(mPath, mPaint);
            }
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            mPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() { return 48; }

        @Override
        public int getIntrinsicHeight() { return 48; }
    }

    private ImageView makeBtn(int drawable, View.OnClickListener onClick) {
        final ImageView b = new ImageView(mContext);
        b.setImageDrawable(new TransportIcon(drawable));
        b.setColorFilter(Color.WHITE);
        b.setPadding(dp(2), dp(2), dp(2), dp(2));
        // 终极方案：子 View 最深处自消费 UP 并直接执行——不依赖 click 分发链
        b.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                android.util.Log.d("AuraIsland", "button UP fired: " + v);
                // 任何按钮交互都重置自动收起计时（否则用户操作时卡片会自己收起）
                mHandler.removeCallbacks(mAutoCollapse);
                mHandler.postDelayed(mAutoCollapse, 15000);
                onClick.onClick(v);
            }
            return true;
        });
        b.setClickable(true);
        return b;
    }

    private void build() {
        if (mRoot != null) {
            return;
        }
        mRoot = new FrameLayout(mContext) {
            private final android.view.ViewTreeObserver.OnComputeInternalInsetsListener mInsets =
                    info -> {
                        info.setTouchableInsets(android.view.ViewTreeObserver.InternalInsetsInfo
                                .TOUCHABLE_INSETS_REGION);
                        info.touchableRegion.setEmpty();
                        if (mShown && !mAnimating && !mClosing && mCard != null
                                && mCard.getVisibility() == View.VISIBLE) {
                            // Window geometry is fixed for smooth drawing. Input only covers
                            // the visible card, including its current transform.
                            final RectF bounds = new RectF(0, 0,
                                    mCard.getWidth(), mCard.getHeight());
                            mCard.getMatrix().mapRect(bounds);
                            bounds.offset(mCard.getLeft(), mCard.getTop());
                            info.touchableRegion.set((int) Math.floor(bounds.left),
                                    (int) Math.floor(bounds.top), (int) Math.ceil(bounds.right),
                                    (int) Math.ceil(bounds.bottom));
                        }
                    };

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                // A detached ViewRoot destroys its observer. Register on every attach.
                getViewTreeObserver().addOnComputeInternalInsetsListener(mInsets);
                requestLayout();
            }

            @Override
            protected void onDetachedFromWindow() {
                if (getViewTreeObserver().isAlive()) {
                    getViewTreeObserver().removeOnComputeInternalInsetsListener(mInsets);
                }
                super.onDetachedFromWindow();
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    if (mShown && mExpanded && !mClosing) {
                        mHandler.removeCallbacks(mAutoCollapse);
                        if (mAnimating) {
                            mCollapseAfterAnimation = true;
                        } else {
                            collapseAnimated();
                        }
                    }
                    // WATCH_OUTSIDE_TOUCH observes DOWN; the target app keeps its gesture.
                    return false;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        mCard = new LinearLayout(mContext) {
            private float mDownX, mDownY;
            private int mSwipe; // 1: open application, -1: collapse

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                final int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    mDownX = event.getX();
                    mDownY = event.getY();
                    mSwipe = 0;
                } else if (action == MotionEvent.ACTION_MOVE && mSwipe == 0
                        && mExpanded && !mAnimating && !mLaunching) {
                    final float dx = event.getX() - mDownX;
                    final float dy = event.getY() - mDownY;
                    if (Math.abs(dy) > (SWIPE_UP_THRESHOLD_DP * mDensity)
                            && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                        mSwipe = dy > 0 ? 1 : -1;
                        // Cancel the pressed child, including transport buttons. A drag
                        // must never execute their ACTION_UP playback command.
                        final MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                    }
                }
                if (mSwipe != 0) {
                    if (action == MotionEvent.ACTION_UP) {
                        final int direction = mSwipe;
                        mSwipe = 0;
                        mHandler.removeCallbacks(mAutoCollapse);
                        if (direction > 0 && mMode == 1) {
                            openMediaApp(true);
                        } else if (mMode == 4) {
                            if (mTransportCb != null) mTransportCb.onDismissNotification();
                            hideAnimated();
                        } else {
                            collapseAnimated();
                        }
                    } else if (action == MotionEvent.ACTION_CANCEL) {
                        mSwipe = 0;
                    }
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        mCard.setOrientation(LinearLayout.VERTICAL);
        mBg = new GradientDrawable();
        mBg.setColor(BG);
        mBg.setCornerRadius(dp(23));
        mCard.setBackground(mBg);
        mCard.setClipToOutline(true); // 子 View（封面等）裁进圆角，不再越出卡片圆角

        // ===== 收起态头部：[缩略图][空档][等化器/电量] =====
        mHeader = new LinearLayout(mContext);
        mHeader.setOrientation(LinearLayout.HORIZONTAL);

        mThumbIcon = new ImageView(mContext);
        mHeader.addView(mThumbIcon);

        mTextCol = new LinearLayout(mContext);
        mTextCol.setOrientation(LinearLayout.VERTICAL);
        mTextCol.setGravity(Gravity.CENTER_VERTICAL);
        mTitle = makeText(11f, true, Color.WHITE);
        mSub = makeText(10f, false, 0xFFB0B0B0);
        mSub.setVisibility(View.GONE);
        mTextCol.addView(mTitle);
        mTextCol.addView(mSub);
        mHeader.addView(mTextCol);

        mSpacer = new View(mContext);
        mHeader.addView(mSpacer);

        mEq = new EqView(mContext);
        mHeader.addView(mEq);

        mRightBox = new LinearLayout(mContext);
        mRightBox.setOrientation(LinearLayout.HORIZONTAL);
        mRightBox.setGravity(Gravity.CENTER);
        mRightText = makeText(12f, true, 0xFFEAEAEA);
        mRightBox.addView(mRightText);
        mHeader.addView(mRightBox);
        mCard.addView(mHeader);

        // ===== 展开态媒体卡片主体：[封面][信息列] =====
        mBody = new LinearLayout(mContext);
        mBody.setOrientation(LinearLayout.HORIZONTAL);
        mBody.setGravity(Gravity.CENTER_VERTICAL);
        mBody.setVisibility(View.GONE);

        mArt = new ImageView(mContext);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(116), dp(116));
        artLp.gravity = Gravity.CENTER_VERTICAL;
        mArt.setLayoutParams(artLp);
        mBody.addView(mArt);

        LinearLayout col = new LinearLayout(mContext);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        colLp.leftMargin = dp(12);
        col.setLayoutParams(colLp);

        mTitleBig = makeText(15f, true, Color.WHITE);
        mTitleBig.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(mTitleBig);
        mArtist = makeText(12f, false, 0xFFB8B8B8);
        mArtist.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(mArtist);

        mSeek = new SeekView(mContext);
        LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        skLp.topMargin = dp(10);
        skLp.rightMargin = dp(4);
        mSeek.setLayoutParams(skLp);
        col.addView(mSeek);

        mTransport = new LinearLayout(mContext);
        mTransport.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        tpLp.topMargin = dp(8);
        mTransport.setLayoutParams(tpLp);
        mBtnPrev = makeBtn(android.R.drawable.ic_media_previous,
                v -> {
                    if (mTransportCb != null) {
                        mTransportCb.onPrev();
                    }
                });
        mBtnPlay = makeBtn(android.R.drawable.ic_media_play, v -> {
            if (mTransportCb != null) {
                mTransportCb.onPlayPause();
            }
        });
        mBtnNext = makeBtn(android.R.drawable.ic_media_next,
                v -> {
                    if (mTransportCb != null) {
                        mTransportCb.onNext();
                    }
                });
        // Share the whole 180dp information-column width: 60x48dp per target.
        mBtnPrev.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        mBtnPlay.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        mBtnNext.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        mTransport.addView(mBtnPrev);
        mTransport.addView(mBtnPlay);
        mTransport.addView(mBtnNext);
        col.addView(mTransport);

        mBody.addView(col);
        mCard.addView(mBody, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        mSmsBody = new LinearLayout(mContext);
        mSmsBody.setOrientation(LinearLayout.VERTICAL);
        mSmsBody.setGravity(Gravity.CENTER_VERTICAL);
        mSmsBody.setPadding(dp(8), 0, dp(8), 0);
        mSmsBody.setVisibility(View.GONE);
        mSmsSource = makeText(12f, false, 0xFFB8B8B8);
        mSmsSource.setEllipsize(TextUtils.TruncateAt.END);
        mSmsBody.addView(mSmsSource);
        mSmsCodeText = makeText(30f, true, Color.WHITE);
        mSmsCodeText.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        mSmsBody.addView(mSmsCodeText);
        TextView copy = makeText(14f, true, Color.WHITE);
        copy.setText(R.string.island_sms_copy);
        copy.setGravity(Gravity.CENTER);
        GradientDrawable copyBg = new GradientDrawable();
        copyBg.setColor(0xFF303035);
        copyBg.setCornerRadius(dp(16));
        copy.setBackground(copyBg);
        copy.setOnClickListener(v -> {
            if (!mAnimating && mMode == 3 && mTransportCb != null) {
                mTransportCb.onCopySmsCode();
            }
        });
        LinearLayout actions = new LinearLayout(mContext);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        copyLp.rightMargin = dp(8);
        actions.addView(copy, copyLp);
        TextView fill = makeText(14f, true, Color.WHITE);
        fill.setText(R.string.island_sms_fill);
        fill.setGravity(Gravity.CENTER);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(0xFF303035);
        fillBg.setCornerRadius(dp(16));
        fill.setBackground(fillBg);
        fill.setOnClickListener(view -> {
            if (!mAnimating && mMode == 3 && mTransportCb != null) {
                mTransportCb.onFillSmsCode();
            }
        });
        actions.addView(fill, new LinearLayout.LayoutParams(0, dp(44), 1f));
        mSmsBody.addView(actions);
        mCard.addView(mSmsBody, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== 通知消息横幅胶囊主体（复刻截图样式） =====
        mNotifBody = new LinearLayout(mContext);
        mNotifBody.setOrientation(LinearLayout.HORIZONTAL);
        mNotifBody.setGravity(Gravity.CENTER_VERTICAL);
        mNotifBody.setPadding(dp(8), dp(2), dp(10), dp(2));
        mNotifBody.setVisibility(View.GONE);

        mNotifIcon = new ImageView(mContext);
        LinearLayout.LayoutParams niLp = new LinearLayout.LayoutParams(dp(50), dp(50));
        niLp.rightMargin = dp(14);
        mNotifIcon.setLayoutParams(niLp);
        mNotifBody.addView(mNotifIcon);

        LinearLayout notifTextCol = new LinearLayout(mContext);
        notifTextCol.setOrientation(LinearLayout.VERTICAL);
        notifTextCol.setGravity(Gravity.CENTER_VERTICAL);
        notifTextCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout topRow = new LinearLayout(mContext);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        mNotifSender = makeText(16f, true, Color.WHITE);
        mNotifSender.setSingleLine(true);
        mNotifSender.setEllipsize(TextUtils.TruncateAt.END);
        topRow.addView(mNotifSender, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView timeLabel = makeText(12f, false, 0xFF8E8E93);
        timeLabel.setText("现在");
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlLp.leftMargin = dp(8);
        topRow.addView(timeLabel, tlLp);
        notifTextCol.addView(topRow);

        mNotifContent = makeText(13f, false, 0xFF9E9E9E);
        mNotifContent.setMaxLines(2);
        mNotifContent.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ncLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ncLp.topMargin = dp(3);
        notifTextCol.addView(mNotifContent, ncLp);

        mNotifBody.addView(notifTextCol);
        mCard.addView(mNotifBody, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // A small grip at the bottom of the expanded music card.
        mHandle = new View(mContext);
        final GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0xFF9C9CA2);
        handleBg.setCornerRadius(dp(2));
        mHandle.setBackground(handleBg);
        final LinearLayout.LayoutParams handleLp =
                new LinearLayout.LayoutParams(dp(38), dp(3));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.topMargin = dp(7);
        mHandle.setVisibility(View.GONE);
        mHandle.setOnClickListener(v -> {
            mHandler.removeCallbacks(mAutoCollapse);
            collapseAnimated();
        });
        mCard.addView(mHandle, handleLp);

        mRoot.addView(mCard);

        // Child buttons keep their click actions; vertical card gestures are
        // tracked above so dragging from a child also cancels its click safely.
        mCard.setOnClickListener(v -> {
            android.util.Log.d("AuraIsland", "card click exp=" + mExpanded + " mode=" + mMode);
            if (mAnimating) {
                return;
            }
            if (mMode == 4) {
                if (mTransportCb != null) mTransportCb.onOpenNotification();
                return;
            }
            if (mExpanded) {
                openMediaApp();
                return;
            }
            if (mMode == 1 || mMode == 3) {
                onTapped();
            }
        });
        applyCollapsedParams();
    }

    /** 收起态：媒体=[缩略图|空档|等化器]；充电=[标题|空档|电量]。 */
    private void applyCollapsedParams() {
        mCollapseAfterAnimation = false;
        mExpanded = false;
        resetCameraMorph();
        mHandle.setVisibility(View.GONE);
        mBg.setColor(BG);
        final LayoutParams cl = (LayoutParams) mCard.getLayoutParams();
        final IslandGeometry.Box pose = mGeometry.collapsed(mMode == 2);
        final IslandGeometry.Slots slots = mGeometry.slots(mMode == 2);
        cl.width = pose.width;
        cl.height = pose.height;
        mCard.setPadding(0, 0, 0, 0);
        mHeader.setLayoutParams(new LinearLayout.LayoutParams(cl.width,
                LinearLayout.LayoutParams.MATCH_PARENT));
        mHeader.setGravity(Gravity.CENTER_VERTICAL);
        mBody.setVisibility(View.GONE);
        mSmsBody.setVisibility(View.GONE);
        if (mNotifBody != null) mNotifBody.setVisibility(View.GONE);
        if (mMode != 3) {
            mSmsCodeText.setText("");
            mSmsSource.setText("");
        }
        mHeader.setVisibility(View.VISIBLE);
        final boolean media = mMode == 1;
        mSub.setVisibility(View.GONE);
        mThumbIcon.setVisibility(media || mMode == 3 ? View.VISIBLE : View.GONE);
        // 无图兜底：左段中央显示歌名首字（叠在深色圆角块上）
        mTitle.setTranslationX(0);
        if (mMode == 2 || mMode == 3) {
            // 充电态：复位媒体兜底的大字号；translationX 直接右移（布局重力
            // 在部分场景不生效，坐标偏移最可靠）
            mTitle.setTextSize(12f);
            mTitle.setTextColor(Color.WHITE);
            mTitle.setTranslationX(0);
        } else {
            mTitle.setTranslationX(0);
        }
        if (media && mTitleBig != null && mTitleBig.getText().length() > 0) {
            mTitle.setText(mTitleBig.getText().subSequence(0, 1));
            mTitle.setTextColor(0xFFDDDDDD);
            mTitle.setTextSize(16f);
        }
        mThumbIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 边距 10→6dp：缩略图从约 40px 放大到约 56px
        final int iconPadding = dp(mMode == 3 ? 8 : 6);
        mThumbIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
        mThumbIcon.setTranslationX(mMode == 3 ? 0 : dp(3.5f)); // 收起态缩略图右移（在上一版基础上再 +1px）
        final int segL = slots.left;
        mThumbIcon.setLayoutParams(new LinearLayout.LayoutParams(segL,
                LinearLayout.LayoutParams.MATCH_PARENT));
        mTextCol.setVisibility(media || mMode == 3 ? View.GONE : View.VISIBLE);
        mTextCol.setLayoutParams(new LinearLayout.LayoutParams(slots.left,
                LinearLayout.LayoutParams.MATCH_PARENT));
        // "充电中"右对齐贴住摄像头空档左缘（居中时收窄段宽位移太小）
        mTextCol.setGravity((mMode == 3 ? Gravity.CENTER_HORIZONTAL : Gravity.END)
                | Gravity.CENTER_VERTICAL);
        mSpacer.setVisibility(View.VISIBLE);
        mSpacer.setLayoutParams(new LinearLayout.LayoutParams(slots.gap,
                LinearLayout.LayoutParams.MATCH_PARENT));
        mEq.setVisibility(media ? View.VISIBLE : View.GONE);
        // weight=1 占据空档右侧的全部剩余宽度，柱体在段内居中（修呼吸版改丢的右移）
        final LinearLayout.LayoutParams eqLp = new LinearLayout.LayoutParams(slots.right,
                Math.min(dp(24), cl.height));
        eqLp.gravity = Gravity.CENTER;
        mEq.setLayoutParams(eqLp);
        mEq.setPlaying(media && mPlaying);
        mRightBox.setVisibility(mMode != 1 ? View.VISIBLE : View.GONE);
        mRightBox.setGravity(Gravity.CENTER);
        mRightBox.setLayoutParams(new LinearLayout.LayoutParams(slots.right,
                LinearLayout.LayoutParams.MATCH_PARENT));
        mRightText.setVisibility(mMode != 1 ? View.VISIBLE : View.GONE);
        mRightText.setTranslationX(0);
        mBg.setCornerRadius(cl.height / 2f);
        mCard.setPivotX(cl.width / 2f);
        mCard.setPivotY(0f);
        mCard.setScaleX(1f);
        mCard.setScaleY(1f);
        mCard.requestLayout();
        relayout(false);
    }

    /** 展开态：仿控制中心媒体卡片（328×152dp）。 */
    private void applyExpandedParams() {
        mExpanded = true;
        resetCameraMorph();
        mHandle.setVisibility(View.VISIBLE);
        mBg.setColor(EXPANDED_BG);
        final LayoutParams cl = (LayoutParams) mCard.getLayoutParams();
        cl.width = mGeometry.expanded.width;
        cl.height = mGeometry.expanded.height;
        mCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        mHeader.setVisibility(View.GONE);
        mBody.setVisibility(mMode == 1 ? View.VISIBLE : View.GONE);
        mSmsBody.setVisibility(mMode == 3 ? View.VISIBLE : View.GONE);
        if (mNotifBody != null) mNotifBody.setVisibility(mMode == 4 ? View.VISIBLE : View.GONE);
        mHandle.setVisibility(mMode == 4 ? View.GONE : View.VISIBLE);
        if (mMode == 4) {
            cl.height = dp(84);
        }
        mBg.setCornerRadius(dp(26));
        mCard.setPivotX(cl.width / 2f);
        mCard.setPivotY(0f);
        mCard.setScaleX(1f);
        mCard.setScaleY(1f);
        mCard.requestLayout();
        relayout(true);
    }

    /**
     * 圆角 Drawable 包装：按 bounds 短边比例裁圆角。
     * 用比例而非固定像素——ImageView 缩放时 drawable 的 bounds 是图片固有尺寸、
     * 再整体缩放绘制，固定像素半径换算到屏幕会随图片尺寸放大/缩小。
     */
    private static final class RoundDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.drawable.Drawable mInner;
        private final float mFraction;
        private final android.graphics.Path mPath = new android.graphics.Path();
        private final android.graphics.RectF mRect = new android.graphics.RectF();

        RoundDrawable(android.graphics.drawable.Drawable inner, float fraction) {
            mInner = inner;
            mFraction = fraction;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            mInner.setBounds(b);
            final int save = canvas.save();
            mRect.set(b);
            mPath.reset();
            final float r = Math.min(b.width(), b.height()) * mFraction;
            mPath.addRoundRect(mRect, r, r, android.graphics.Path.Direction.CW);
            canvas.clipPath(mPath);
            mInner.draw(canvas);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            mInner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            mInner.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return mInner.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return mInner.getIntrinsicHeight();
        }
    }

    private long dur(long ms) {
        return ms * mSlow;
    }

    private static int readPropInt(String key, int def, int min, int max) {
        int v = def;
        try {
            final String s = android.os.SystemProperties.get(key, "");
            if (!s.isEmpty()) {
                v = Integer.parseInt(s.trim());
            }
        } catch (Throwable ignored) {
        }
        return Math.max(min, Math.min(max, v));
    }

    private void relayout(boolean expanded) {
        if (mCard == null) {
            return;
        }
        final LayoutParams cl = (LayoutParams) mCard.getLayoutParams();
        final IslandGeometry.Box pose = expanded ? mGeometry.expanded
                : mGeometry.collapsed(mMode == 2);
        cl.leftMargin = pose.left - mGeometry.window.left;
        cl.topMargin = pose.top - mGeometry.window.top;
        cl.gravity = Gravity.TOP | Gravity.LEFT;
        mCard.setLayoutParams(cl);
    }

    private void relayoutCurrentY() {
        if (!mShown || mWindowParams == null) {
            return;
        }
        try {
            mWm.updateViewLayout(mRoot, mWindowParams);
        } catch (Throwable ignored) {
        }
    }

    /** 缩放动画（顶部铰点）：生长带回弹，收回加速。 */
    private void animateScale(float fromScale, float toScale, long durMs, boolean grow,
            final Runnable onEnd) {
        if (mAnim != null) {
            mAnim.cancel();
        }
        mAnimating = true;
        mRoot.requestLayout();
        mCard.setScaleX(fromScale);
        mCard.setScaleY(fromScale);
        mAnim = ValueAnimator.ofFloat(fromScale, toScale);
        mAnim.setDuration(dur(durMs));
        // The expanded card already fills its maximum bounds. Settle at scale=1
        // without overshooting into the surface edge; capsule spring is retained.
        mAnim.setInterpolator(grow && mExpanded
                ? new android.view.animation.PathInterpolator(0.16f, 0f, 0.2f, 1f)
                : grow ? new OvershootInterpolator(0.9f)
                        : new AccelerateInterpolator(1.2f));
        mAnim.addUpdateListener(anim -> {
            final float s = (float) anim.getAnimatedValue();
            mCard.setScaleX(s);
            mCard.setScaleY(s);
        });
        mAnim.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // cancel() also invokes onAnimationEnd. A cancelled close/expand must
                // not remove or reattach the window, or finish a newer animation.
                if (animation != mAnim) {
                    return;
                }
                mAnim = null;
                mAnimating = false;
                mRoot.requestLayout();
                if (!mCancelled && onEnd != null) {
                    onEnd.run();
                }
                if (!mCancelled && mCollapseAfterAnimation && mExpanded && !mClosing) {
                    mCollapseAfterAnimation = false;
                    mHandler.post(IslandView.this::collapseAnimated);
                }
            }
        });
        mAnim.start();
    }

    /** Bounds morph: the outline narrows before its height contracts. */
    private static final class CameraMorph extends IslandGeometry.Morph {
        CameraMorph(float sl,float sw,float sh,float st,float sr,float tl,float tw,float th,
                float tt,float p) { super(sl,sw,sh,st,sr,tl,tw,th,tt,p); }
    }

    /** One static content texture and a lightweight outline; no child relayout per frame. */
    private final class CameraMorphView extends View {
        final android.graphics.Bitmap snapshot;
        final float contentWidth, contentHeight;
        final int backgroundColor;
        final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint content = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final RectF outline = new RectF();
        final RectF contentRect = new RectF();
        final android.graphics.Path clip = new android.graphics.Path();
        CameraMorph bounds;
        float contentAlpha = 1f;

        CameraMorphView(android.graphics.Bitmap bitmap, float width, float height, int color) {
            super(IslandView.this.mContext);
            snapshot = bitmap;
            contentWidth = width;
            contentHeight = height;
            backgroundColor = color;
            background.setColor(color);
        }

        void setFrame(CameraMorph frame, float alpha) {
            bounds = frame;
            contentAlpha = alpha;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bounds == null) {
                return;
            }
            final float centerX = bounds.left + bounds.width / 2f;
            outline.set(bounds.left, bounds.top,
                    bounds.left + bounds.width, bounds.top + bounds.height);
            background.setAlpha(Math.round(Color.alpha(backgroundColor) * bounds.alpha));
            canvas.drawRoundRect(outline, bounds.radius, bounds.radius, background);
            if (contentAlpha > 0f && bounds.alpha > 0f) {
                // Keep text/artwork at its captured geometry. The gathering outline
                // clips it from both sides while it fades, without reflow or ghost layers.
                final int save = canvas.save();
                clip.reset();
                clip.addRoundRect(outline, bounds.radius, bounds.radius,
                        android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                contentRect.set(centerX - contentWidth / 2f, bounds.top,
                        centerX + contentWidth / 2f, bounds.top + contentHeight);
                content.setAlpha(Math.round(255f * contentAlpha * bounds.alpha));
                canvas.drawBitmap(snapshot, null, contentRect, content);
                canvas.restoreToCount(save);
            }
        }
    }

    private void resetCameraMorph() {
        if (mCard == null) {
            return; // Hidden before the first event, e.g. landscape startup.
        }
        if (mMorphView != null) {
            mRoot.removeView(mMorphView);
            mMorphView = null;
        }
        mCard.setVisibility(View.VISIBLE);
        final LayoutParams cl = (LayoutParams) mCard.getLayoutParams();
        final IslandGeometry.Box pose = mExpanded ? mGeometry.expanded
                : mGeometry.collapsed(mMode == 2);
        cl.leftMargin = pose.left - mGeometry.window.left;
        cl.topMargin = pose.top - mGeometry.window.top;
        cl.gravity = Gravity.TOP | Gravity.LEFT;
        mCard.setAlpha(1f);
        mHeader.setAlpha(1f);
        mBody.setAlpha(1f);
        mHandle.setAlpha(1f);
    }

    /** Move and reshape the outline into the camera, rather than scale in place. */
    private void animateToCamera(long durMs, final Runnable onEnd) {
        if (mAnim != null) {
            mAnim.cancel();
        }
        if (mAnimY != null) {
            mAnimY.cancel();
        }
        final LayoutParams cl = (LayoutParams) mCard.getLayoutParams();
        final CameraMorphView previous = mMorphView;
        final float startWidth = previous != null && previous.bounds != null
                ? previous.bounds.width : cl.width * mCard.getScaleX();
        final float startHeight = previous != null && previous.bounds != null
                ? previous.bounds.height : cl.height * mCard.getScaleY();
        final float startTop = previous != null && previous.bounds != null
                ? previous.bounds.top : cl.topMargin
                        + mCard.getPivotY() * (1f - mCard.getScaleY());
        final float startRadius = previous != null && previous.bounds != null
                ? previous.bounds.radius : mBg.getCornerRadius();
        final float startLeft = previous != null && previous.bounds != null
                ? previous.bounds.left : cl.leftMargin
                        + mCard.getPivotX() * (1f - mCard.getScaleX());
        final float targetWidth = mGeometry.holeWidth, targetHeight = mGeometry.holeHeight;
        final float targetLeft = mGeometry.cameraX - mGeometry.window.left - targetWidth / 2f;
        final float targetTop = mGeometry.cameraY - mGeometry.window.top - targetHeight / 2f;
        final float startContentAlpha = previous != null
                ? previous.contentAlpha : mHeader.getAlpha();
        final android.graphics.Bitmap snapshot;
        if (previous != null) {
            snapshot = previous.snapshot;
            mRoot.removeView(previous);
        } else {
            snapshot = android.graphics.Bitmap.createBitmap(
                    Math.max(1, Math.round(startWidth)),
                    Math.max(1, Math.round(startHeight)), android.graphics.Bitmap.Config.ARGB_8888);
            final Canvas capture = new Canvas(snapshot);
            capture.scale(startWidth / Math.max(1, mCard.getWidth()),
                    startHeight / Math.max(1, mCard.getHeight()));
            // Capture only content; the animated outline draws its own background.
            mCard.setBackground(null);
            mCard.setClipToOutline(false);
            try {
                mCard.draw(capture);
            } finally {
                mCard.setBackground(mBg);
                mCard.setClipToOutline(true);
            }
        }
        mEq.setPlaying(false);
        mCard.setVisibility(View.INVISIBLE);
        mMorphView = new CameraMorphView(snapshot, startWidth, startHeight,
                previous != null ? previous.backgroundColor : mExpanded ? EXPANDED_BG : BG);
        mMorphView.setFrame(new CameraMorph(startLeft, startWidth, startHeight, startTop,
                startRadius, targetLeft, targetWidth, targetHeight, targetTop, 0f), startContentAlpha);
        mRoot.addView(mMorphView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mCard.setScaleX(1f);
        mCard.setScaleY(1f);
        cl.width = Math.max(1, Math.round(startWidth));
        cl.height = Math.max(1, Math.round(startHeight));
        cl.leftMargin = Math.round(startLeft);
        cl.topMargin = Math.round(startTop);
        cl.gravity = Gravity.TOP | Gravity.LEFT;
        mCard.setLayoutParams(cl);

        // The existing fixed surface already contains both positions. Keep its
        // compositor buffer and ViewRoot for the full transition.

        mAnimating = true;
        mRoot.requestLayout();
        mAnim = ValueAnimator.ofFloat(0f, 1f);
        mAnim.setDuration(dur(durMs));
        mAnim.setInterpolator(new android.view.animation.PathInterpolator(
                0.22f, 0f, 0.25f, 1f));
        mAnim.addUpdateListener(anim -> {
            final float p = (float) anim.getAnimatedValue();
            final CameraMorph bounds = new CameraMorph(startLeft, startWidth, startHeight,
                    startTop, startRadius, targetLeft, targetWidth, targetHeight, targetTop, p);
            final float contentAlpha = startContentAlpha * Math.max(0f, 1f - p * 2.5f);
            mMorphView.setFrame(bounds, contentAlpha);
        });
        mAnim.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (animation != mAnim) {
                    return;
                }
                mAnim = null;
                mAnimating = false;
                mRoot.requestLayout();
                if (!mCancelled && onEnd != null) {
                    onEnd.run();
                }
                if (!mCancelled && mCollapseAfterAnimation && mExpanded && !mClosing) {
                    mCollapseAfterAnimation = false;
                    mHandler.post(IslandView.this::collapseAnimated);
                }
            }
        });
        mAnim.start();
    }

    private void animateY(boolean expanded, int toY, long durMs) {
        if (mAnimY != null) {
            mAnimY.cancel();
        }
        if (mWindowParams == null) {
            return;
        }
        final WindowManager.LayoutParams wlp = mWindowParams;
        final int fromY = wlp.y;
        if (fromY == toY) {
            return;
        }
        mAnimY = ValueAnimator.ofInt(fromY, toY);
        mAnimY.setDuration(dur(durMs));
        mAnimY.addUpdateListener(a -> {
            wlp.y = (int) a.getAnimatedValue();
            relayoutCurrentY();
        });
        mAnimY.start();
    }

    /** Expand the destination app from this card while its overlay fades away. */
    private final Runnable mLaunchFallback = this::onAppLaunchAnimationStart;

    private void openMediaApp() { openMediaApp(false); }

    private void openMediaApp(boolean smallWindow) {
        if (!mShown || !mExpanded || mAnimating || mLaunching || mMode != 1
                || mTransportCb == null) {
            return;
        }
        mLaunching = true;
        mHandler.removeCallbacks(mAutoCollapse);
        if (!mTransportCb.onOpenApp(mCard, smallWindow)) {
            mLaunching = false;
            mHandler.postDelayed(mAutoCollapse, 15000);
            return;
        }
        // A foreground task may need no transition. Keep a bounded fallback.
        mHandler.postDelayed(mLaunchFallback, 1200);
    }

    public void onAppLaunchAnimationStart() {
        mHandler.removeCallbacks(mLaunchFallback);
        if (!mShown || !mExpanded || mClosing || !mLaunching) {
            mLaunching = false;
            return;
        }
        if (mAnim != null) {
            mAnim.cancel();
        }
        mAnimating = true;
        mRoot.requestLayout();
        mAnim = ValueAnimator.ofFloat(0f, 1f);
        mAnim.setDuration(dur(180));
        mAnim.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
        mAnim.addUpdateListener(anim -> {
            final float progress = (float) anim.getAnimatedValue();
            mCard.setAlpha(1f - progress);
        });
        mAnim.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (animation != mAnim) {
                    return;
                }
                mAnim = null;
                mAnimating = false;
                mLaunching = false;
                mCard.setAlpha(1f);
                mCard.setScaleX(1f);
                mCard.setScaleY(1f);
                if (!mCancelled && mShown && !mClosing) {
                    applyCollapsedParams();
                    attach(false);
                    animateScale(0.4f, 1f, 180, true, null);
                }
                mRoot.requestLayout();
            }
        });
        mAnim.start();
    }

    /** Wait for the old window's real exit, without collapsing the capsule on a fixed timeout. */
    public void onAppLaunchPreparationPending() {
        mHandler.removeCallbacks(mLaunchFallback);
    }

    public void onAppLaunchPrepared() {
        mHandler.removeCallbacks(mLaunchFallback);
        if (mLaunching) mHandler.postDelayed(mLaunchFallback, 1200);
    }

    public void onAppLaunchPreparationFailed() {
        mHandler.removeCallbacks(mLaunchFallback);
        mLaunching = false;
        if (mShown && mExpanded) mHandler.postDelayed(mAutoCollapse, 15000);
    }

    private void onTapped() {
        if (!FEATURE_EXPAND) {
            return; // 其他功能暂不开放
        }
        if (mAnimating || mMode == 2 || mExpanded) {
            return;
        }
        expandAnimated();
        mHandler.removeCallbacks(mAutoCollapse);
        mHandler.postDelayed(mAutoCollapse, 15000);
    }

    /** 两段式展开：胶囊先收进摄像头，展开卡片再从摄像头里长出来。 */
    private void expandAnimated() {
        if (mAnimating || mExpanded || mMode == 2) {
            return;
        }
        // The capsule outline gathers into the camera before the card appears.
        animateToCamera(240, () -> {
            // Switch content geometry while invisible, within the same surface.
            resetCameraMorph();
            applyExpandedParams();
            attach(false);
            final LayoutParams ecl = (LayoutParams) mCard.getLayoutParams();
            mCard.setPivotX(ecl.width / 2f);
            mCard.setPivotY(0f);          // 顶边中心：顶边不动、不在状态栏里、不被裁切
            mCard.setScaleX(0.12f);
            mCard.setScaleY(0.12f);
            animateScale(0.12f, 1f, 280, true, null);
        });
    }

    /** 两段式收起：展开卡片先收进摄像头，胶囊再从摄像头里长出来。 */
    private void collapseAnimated() {
        if (mAnimating) {
            return;
        }
        if (!mExpanded) {
            applyCollapsedParams();
            return;
        }
        // Move the card up into the actual camera position while reshaping it.
        animateToCamera(320, () -> {
            // Restore the capsule inside the same compositor surface.
            resetCameraMorph();
            applyCollapsedParams();
            attach(false);
            final LayoutParams ccl = (LayoutParams) mCard.getLayoutParams();
            mCard.setPivotX(ccl.width / 2f);
            mCard.setPivotX(cameraPivotX());
            mCard.setPivotY(cameraPivotY());
            mCard.setScaleX(0.05f);
            mCard.setScaleY(0.05f);
            animateScale(0.05f, 1f, 380, true, null);
        });
    }

    private final Runnable mAutoCollapse = this::autoCollapse;

    private void autoCollapse() {
        collapseAnimated();
    }

    /** 显示媒体状态。 */
    /** Screen-space destination used by Launcher3's existing swipe-to-home animation. */
    public android.graphics.Rect getHomeTargetBounds() {
        final IslandGeometry.Box pose = mGeometry.media;
        return new android.graphics.Rect(mCameraConfig.screenLeft + pose.left,
                mCameraConfig.screenTop + pose.top, mCameraConfig.screenLeft + pose.right(),
                mCameraConfig.screenTop + pose.bottom());
    }

    public void showMedia(String title, String artist, android.graphics.drawable.Drawable thumb,
            String pkg, boolean playing) {
        // 指纹含“有无封面”：封面晚到（先无图后有图）时也能刷新视图
        final String fp = "1|" + title + "|" + artist + "|" + playing + "|" + pkg
                + "|" + (thumb != null);
        if (mAnimating && !mClosing) {
            return; // Do not relayout a geometry transition during media callbacks.
        }
        if (fp.equals(mLastFingerprint) && mShown && !mExpanded && !mClosing) {
            return; // 内容未变：不触碰视图树（轮询零开销）
        }
        cancelClosing();
        mLastFingerprint = fp;
        build();
        final boolean wasHidden = !mShown;
        final boolean modeChanged = mMode != 1;
        mMode = 1;
        mPkg = pkg;
        mPlaying = playing;
        String app = pkg;
        try {
            final android.content.pm.ApplicationInfo ai =
                    mContext.getPackageManager().getApplicationInfo(pkg, 0);
            app = mContext.getPackageManager().getApplicationLabel(ai).toString();
        } catch (Throwable ignored) {
        }
        mTitleBig.setText(title == null || title.isEmpty()
                ? mContext.getString(R.string.island_playing) : title);
        mArtist.setText(artist == null || artist.isEmpty() ? app : artist);
        mBtnPlay.setImageDrawable(new TransportIcon(playing
                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play));
        if (thumb != null) {
            mThumbIcon.setColorFilter(null); // 清除无图兜底残留的滤镜，防封面被染白
            mArt.setColorFilter(null);
            // 圆角按比例（短边的 20%）：缩略图屏幕上约 8px、展开封面约 48px
            mThumbIcon.setImageDrawable(new RoundDrawable(thumb, 0.2f));
            mArt.setImageDrawable(new RoundDrawable(thumb, 0.2f));
        } else {
            // 兜底：深色圆角块 + 白色音符（始终可见；旧方案的白色小三角几乎不可见）
            final GradientDrawable ph = new GradientDrawable();
            ph.setColor(0xFF3A3A3E);
            ph.setCornerRadius(dp(8));
            mThumbIcon.setImageDrawable(ph);
            mThumbIcon.setForeground(null);
            mArt.setImageResource(android.R.drawable.ic_media_play);
            mArt.setColorFilter(0xFF666666);
        }
        if (!mExpanded || modeChanged) {
            applyCollapsedParams();
        }
        attach(wasHidden);
    }

    private android.graphics.drawable.Drawable mSmsMonochromeIcon;

    private android.graphics.drawable.Drawable smsMonochromeIcon() {
        if (mSmsMonochromeIcon != null) return mSmsMonochromeIcon;
        try {
            final android.content.res.Resources resources = mContext.getPackageManager()
                    .getResourcesForApplication("com.android.messaging");
            final int id = resources.getIdentifier("ic_launcher_monochrome", "drawable",
                    "com.android.messaging");
            if (id != 0) {
                final android.graphics.drawable.Drawable icon = resources.getDrawable(id, null)
                        .mutate();
                // Messaging's 108-unit launcher viewport has its monochrome artwork in
                // [32, 76]. Remove only the adaptive-icon padding, retaining the native paths.
                final int inset = -Math.round(icon.getIntrinsicWidth() * 32f / 108f);
                mSmsMonochromeIcon = new android.graphics.drawable.InsetDrawable(icon, inset);
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException
                | android.content.res.Resources.NotFoundException ignored) { }
        if (mSmsMonochromeIcon == null) {
            mSmsMonochromeIcon = mContext.getDrawable(android.R.drawable.ic_dialog_email).mutate();
            mSmsMonochromeIcon.setTint(Color.WHITE);
        }
        return mSmsMonochromeIcon;
    }

    public void showAppNotification(String pkg, String sender, String message,
            android.graphics.drawable.Drawable appIcon) {
        if (mAnimating && !mClosing) return;
        final String fp = "4|" + pkg + "|" + sender + "|" + message;
        if (fp.equals(mLastFingerprint) && mShown && !mClosing) return;
        cancelClosing();
        build();
        final boolean wasHidden = !mShown;
        mMode = 4;
        mPkg = pkg;
        mPlaying = false;
        mLastFingerprint = fp;
        mNotifSender.setText(sender);
        mNotifContent.setText(message);
        if (appIcon != null) {
            mNotifIcon.setImageDrawable(new RoundDrawable(appIcon, 0.22f));
        }
        applyExpandedParams();
        attach(wasHidden);
    }

    public void showSmsCode(String code, String sender) {
        if (mAnimating && !mClosing) return;
        final String fp = "3|" + code + "|" + sender;
        if (fp.equals(mLastFingerprint) && mShown && !mClosing) return;
        cancelClosing();
        build();
        boolean wasHidden = !mShown;
        boolean modeChanged = mMode != 3;
        mMode = 3;
        mPkg = null;
        mPlaying = false;
        mLastFingerprint = fp;
        mSmsCodeText.setText(code);
        mSmsSource.setText(TextUtils.isEmpty(sender)
                ? mContext.getString(R.string.island_sms_code)
                : mContext.getString(R.string.island_sms_from, sender));
        mTitle.setText("");
        mRightText.setText(R.string.island_sms_code);
        mThumbIcon.setColorFilter(null);
        mThumbIcon.setForeground(null);
        mThumbIcon.setImageDrawable(smsMonochromeIcon());
        if (!mExpanded || modeChanged) applyCollapsedParams();
        attach(wasHidden);
    }

    /** 更新播放进度（服务轮询调用）。 */
    public void updateProgress(long positionMs, long durationMs) {
        if (mSeek != null) {
            mSeek.setFrac(durationMs > 0 ? positionMs / (float) durationMs : 0f);
        }
    }

    /** 显示充电状态。 */
    public void showCharging(int pct) {
        final String fp = "2|" + pct;
        if (mAnimating && !mClosing) {
            return;
        }
        if (fp.equals(mLastFingerprint) && mShown && !mClosing) {
            return;
        }
        cancelClosing();
        mLastFingerprint = fp;
        build();
        final boolean wasHidden = !mShown;
        final boolean modeChanged = mMode != 2;
        mMode = 2;
        mPkg = null;
        mTitle.setText(mContext.getString(R.string.island_charging));
        mRightText.setText(pct >= 0 ? pct + "%" : "⚡");
        if (!mExpanded || modeChanged) {
            applyCollapsedParams();
        }
        attach(wasHidden);
    }

    /** 无事件：立即隐藏。 */
    /** 取消正在进行的收起动画（媒体恢复/重新显示时调用，避免动画结束误删）。 */
    private void cancelClosing() {
        if (mClosing) {
            mClosing = false;
            if (mAnim != null) {
                mAnim.cancel();
            }
            // Restore content in the same surface when the shade reverses.
            resetCameraMorph();
            mExpanded = false;
        }
    }

    public void hide() {
        mHandler.removeCallbacks(mLaunchFallback);
        mLaunching = false;
        mCollapseAfterAnimation = false;
        mClosing = false;
        if (mAnim != null) {
            mAnim.cancel();
        }
        if (mAnimY != null) {
            mAnimY.cancel();
        }
        mHandler.removeCallbacks(mAutoCollapse);
        mMode = 0;
        if (mSmsCodeText != null) mSmsCodeText.setText("");
        if (mSmsSource != null) mSmsSource.setText("");
        mLastFingerprint = null;
        if (mEq != null) {
            mEq.setPlaying(false);
        }
        removeInternal();
        mExpanded = false;
    }

    /** 无事件：缩进摄像头后隐藏（动画）。 */
    public void hideAnimated() {
        mHandler.removeCallbacks(mLaunchFallback);
        mLaunching = false;
        // Shade start/finish broadcasts, battery callbacks and polling can all
        // request a close. Keep the first close running instead of cancelling it.
        if (!mShown || mClosing) {
            return;
        }
        mHandler.removeCallbacks(mAutoCollapse);
        if (mAnimY != null) {
            mAnimY.cancel();
        }
        mMode = 0;
        if (mEq != null) {
            mEq.setPlaying(false);
        }
        mClosing = true;
        animateToCamera(320, () -> {
            if (mClosing) {
                mClosing = false;
                removeInternal();
            }
        });
    }

    private void removeInternal() {
        if (mShown) {
            try {
                mWm.removeViewImmediate(mRoot);
            } catch (Throwable ignored) {
            }
            mShown = false;
            mWindowParams = null;
        }
        mExpanded = false;
        mLastFingerprint = null;
        if (mSmsCodeText != null) mSmsCodeText.setText("");
        if (mSmsSource != null) mSmsSource.setText("");
        resetCameraMorph();
    }

    private void attach(boolean animate) {
        if (!mShown) {
            try {
                if (animate) {
                    snapToCameraScale();
                }
                mWindowParams = lp(mExpanded); // 展开态重新挂载时要用展开几何
                // 软件渲染层开关（调参用）：debug.aura.island.sw=1 时整窗 CPU 绘制，
                // 绕开 Vulkan 每帧提交/同步开销。
                if (!mSwApplied) {
                    mSwApplied = true;
                    try {
                        if ("1".equals(android.os.SystemProperties.get(
                                "debug.aura.island.sw", ""))) {
                            mRoot.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                mWm.addView(mRoot, mWindowParams);
                mShown = true;
            } catch (Throwable t) {
                android.util.Log.w("AuraIsland", "addView failed", t);
            }
        }
        // 服务轮询每秒都会走到 attach：展开态不能被重置回收起态（否则卡片一闪而过）
        if (!mExpanded) {
            applyCollapsedParams();
        }
        if (animate) {
            // 首次出现：从摄像头中心生长（铰点=屏 48px - 窗口 y 12px = 18dp）
            mCard.setPivotX(cameraPivotX());
            mCard.setPivotY(cameraPivotY());
            animateScale(0.4f, 1f, 380, true, null);
        }
    }

    private void snapToCameraScale() {
        final LayoutParams cl = (LayoutParams) mCard.getLayoutParams();
        cl.width = Math.max(1, Math.round(mGeometry.holeWidth));
        cl.height = Math.max(1, Math.round(mGeometry.holeHeight));
        mCard.setPivotX(cl.width / 2f);
        mCard.setPivotY(0f);
        mCard.setScaleX(1f);
        mCard.setScaleY(1f);
        mCard.requestLayout();
        relayout(false);
    }
}
