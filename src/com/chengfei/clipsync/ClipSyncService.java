package com.chengfei.clipsync;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 剪贴板监听与同步服务（无界面）。
 *
 * Android 10+ 限制后台应用读剪贴板，本服务按三层自适应获取：
 *   1. 直读：部分定制固件（多数墨水屏阅读器）不限制后台读取，直接 getPrimaryClip() 即可；
 *   2. 时间戳路径：getPrimaryClipDescription() 携带复制时间戳（Android 11 后台可读），
 *      时间戳变化即判定发生了一次复制，再尝试直读；
 *   3. 深读：仅当发生新复制且直读被拒时，短暂（约 0.4~1.2s）添加一个 1x1 像素透明
 *      无障碍悬浮窗获取窗口焦点（FLAG_ALT_FOCUSABLE_IM 不弹输入法，FLAG_NOT_TOUCHABLE
 *      不拦截触摸），焦点在手时直读必然放行，读完立即移除，焦点自动回到阅读应用。
 *
 * 事件驱动、无轮询、无通知、无 Activity。去重靠「剪贴板时间戳 + 内容 SHA-1」双保险并落盘，
 * 服务重启后不会重复上传。
 */
public class ClipSyncService extends AccessibilityService {

    private static final String TAG = "ClipSync";
    private static final long PROBE_THROTTLE_MS = 500;      // 事件探测最小间隔
    private static final long SELECTION_QUIET_MS = 1200;    // 判定"选词已结束"的静默期
    private static final long MIN_DEEP_INTERVAL_MS = 4000;  // 选词触发的深读最小间隔
    private static final long TS_DEEP_DELAY_MS = 400;       // 新复制后直读失败，延迟再深读
    private static final long DEEP_RETRY_DELAY_MS = 350;    // 深读重试间隔（等系统分配焦点）
    private static final int DEEP_MAX_TRIES = 3;
    private static final int MAX_CLIP_CHARS = 1_000_000;    // 超大剪贴板直接放弃，保护内存

    private ClipboardManager mClip;
    private WindowManager mWm;
    private Handler mMain;
    private Uploader mUploader;

    private long mLastTs;                    // 已处理过的剪贴板时间戳（落盘）
    private String mLastUploadedText = "";   // 去重：最近一次上传的全文（内存）
    private String mLastUploadedHash = "";   // 去重：内容 SHA-1（落盘，跨重启）
    private boolean mDescPathWorks;          // 一旦读到过剪贴板描述，即认定时间戳路径可用

    private long mLastProbeAt;
    private long mLastSelectionAt = -1;
    private boolean mSelectionCheckArmed;
    private boolean mTsPending;
    private boolean mProbeLogged;
    private long mLastLongPressArm;

    private View mStealer;                   // 1x1 焦点悬浮窗
    private boolean mDeepBusy;
    private int mDeepTries;
    private long mLastDeepAt;

    private final ClipboardManager.OnPrimaryClipChangedListener mClipListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    probe();
                }
            };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mClip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        mWm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mMain = new Handler(Looper.getMainLooper());
        mUploader = new Uploader(this);

        long[] ts = new long[1];
        String[] hash = new String[1];
        Uploader.readState(this, ts, hash);
        mLastTs = ts[0];
        mLastUploadedHash = hash[0];

        try {
            mClip.addPrimaryClipChangedListener(mClipListener);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "service connected, lastTs=" + mLastTs);
        mUploader.flushPending();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        final int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            mLastSelectionAt = SystemClock.elapsedRealtime();
            if (!mSelectionCheckArmed) {
                mSelectionCheckArmed = true;
                mMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        onSelectionQuiet();
                    }
                }, SELECTION_QUIET_MS);
            }
        } else if (type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            // 划线动作的第一步是长按；时间戳路径不可用的固件上，长按后做两次深读，
            // 覆盖"长按选词→点复制"的整个过程
            if (!mDescPathWorks) scheduleLongPressDeepRead();
        }
        probe();
    }

    private void scheduleLongPressDeepRead() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastLongPressArm < 8000) return;
        mLastLongPressArm = now;
        Log.i(TAG, "long press detected, schedule deep reads");
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                deepReadCapture(false);
            }
        }, 2500);
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                deepReadCapture(false);
            }
        }, 8000);
    }

    /** 选词静默结束：仅当时间戳路径不可用时，才退化为"选词结束后深读一次" */
    private void onSelectionQuiet() {
        mSelectionCheckArmed = false;
        long now = SystemClock.elapsedRealtime();
        if (mLastSelectionAt >= 0 && now - mLastSelectionAt < SELECTION_QUIET_MS) {
            mSelectionCheckArmed = true;
            mMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    onSelectionQuiet();
                }
            }, SELECTION_QUIET_MS);
            return;
        }
        if (mDescPathWorks) return;
        if (now - mLastDeepAt < MIN_DEEP_INTERVAL_MS) return;
        deepReadCapture(false);
    }

    /** 廉价探测：剪贴板描述时间戳判断是否有新复制 + 免费直读 */
    private void probe() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastProbeAt < PROBE_THROTTLE_MS) return;
        mLastProbeAt = now;

        long ts = -1;
        try {
            ClipDescription d = mClip.getPrimaryClipDescription();
            if (d != null) ts = d.getTimestamp();
        } catch (Throwable ignored) {
        }
        if (!mProbeLogged) {
            mProbeLogged = true;
            Log.i(TAG, "probe start: descReadable=" + (ts >= 0));
        }
        if (ts >= 0) {
            mDescPathWorks = true;
            if (ts > mLastTs) {
                mLastTs = ts;
                saveState();
                mTsPending = true;
                capture(false);
                mMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mTsPending) capture(true);
                    }
                }, TS_DEEP_DELAY_MS);
            }
        }

        CharSequence t = readDirect();
        if (t != null) considerUpload(t);
    }

    private void capture(boolean allowDeep) {
        CharSequence t = readDirect();
        if (t != null) {
            considerUpload(t);
            mTsPending = false;
        } else if (allowDeep) {
            deepReadCapture(true);
        }
    }

    private void deepReadCapture(boolean tsDriven) {
        if (mDeepBusy || mStealer != null) return;
        if (!mUploader.deepReadEnabled()) {
            mTsPending = false;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - mLastDeepAt < MIN_DEEP_INTERVAL_MS && !tsDriven) return;
        mLastDeepAt = now;

        CharSequence direct = readDirect();
        if (direct != null) {
            considerUpload(direct);
            mTsPending = false;
            return;
        }

        mDeepBusy = true;
        Log.i(TAG, "deep read: attempt overlay focus");
        addStealer();
    }

    private void addStealer() {
        View v = new View(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1);
        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        lp.format = PixelFormat.TRANSPARENT;
        lp.gravity = Gravity.TOP | Gravity.START;
        // 可获取窗口焦点，但触摸穿透、不弹输入法
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        try {
            mWm.addView(v, lp);
        } catch (Throwable t) {
            Log.i(TAG, "overlay add failed: " + t);
            mDeepBusy = false;
            mTsPending = false;
            return;
        }
        mStealer = v;
        mDeepTries = 0;
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                deepTry();
            }
        }, DEEP_RETRY_DELAY_MS);
    }

    private void deepTry() {
        if (mStealer == null) {
            mDeepBusy = false;
            return;
        }
        CharSequence t = readDirect();
        if (t != null) {
            considerUpload(t);
            mTsPending = false;
            removeStealer();
            mDeepBusy = false;
            return;
        }
        mDeepTries++;
        if (mDeepTries >= DEEP_MAX_TRIES) {
            Log.i(TAG, "deep read failed (focus not granted?)");
            removeStealer();
            mDeepBusy = false;
            mTsPending = false;
            return;
        }
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                deepTry();
            }
        }, DEEP_RETRY_DELAY_MS);
    }

    private void removeStealer() {
        if (mStealer == null) return;
        try {
            mWm.removeView(mStealer);
        } catch (Throwable ignored) {
        }
        mStealer = null;
    }

    private CharSequence readDirect() {
        try {
            ClipData cd = mClip.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            return cd.getItemAt(0).getText();
        } catch (Throwable t) {
            return null;
        }
    }

    private void considerUpload(CharSequence cs) {
        if (cs == null) return;
        String text = cs.toString();
        int n = text.length();
        if (n == 0 || n > MAX_CLIP_CHARS) return;
        if (text.equals(mLastUploadedText)) return;
        String h = Uploader.sha1hex(text);
        if (h.equals(mLastUploadedHash)) return;
        mLastUploadedText = text;
        mLastUploadedHash = h;
        saveState();
        Log.i(TAG, "new clip captured: " + n + " chars");
        mUploader.enqueue(text);
    }

    private void saveState() {
        Uploader.writeState(this, mLastTs, mLastUploadedHash);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        removeStealer();
        if (mMain != null) {
            try {
                mMain.removeCallbacksAndMessages(null);
            } catch (Throwable ignored) {
            }
        }
        if (mUploader != null) mUploader.shutdown();
        if (mClip != null) {
            try {
                mClip.removePrimaryClipChangedListener(mClipListener);
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }
}
