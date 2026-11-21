package com.autowatch.ad;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

public class AdWatchService extends AccessibilityService {

    static final int IDLE = 0;
    static final int WAIT_ENTRY = 1;
    static final int CLOSE_AD = 2;

    private static final String CHANNEL_ID = "autowatch_ad_status";
    private static final String[] EXIT_HINTS = {"领取成功", "跳过广告", "跳过", "关闭广告", "稍后再说"};
    private static final String[] DIALOG_HINTS = {"领取奖励", "坚持退出", "提前得", "确认领取"};
    private static final String[] AD_PAGE_HINTS = {"开发者", "应用名称", "隐私政策", "应用权限"};
    private static final float ENTRY_X = 0.706f;
    private static final float ENTRY_Y = 0.597f;
    private static final float CLAIM_X = 0.50f;
    private static final float CLAIM_Y = 0.552f;
    private static final float DIALOG_EXIT_X = 0.50f;
    private static final float DIALOG_EXIT_Y = 0.609f;
    private static final float CLOSE_X = 0.905f;
    private static final float CLOSE_Y = 0.0725f;
    private static final float SHEET_X = 0.912f;
    private static final float SHEET_Y = 0.594f;
    private static final long CLOSE_RETRY_MS = 1500L;
    private static final long AD_MIN_WATCH_MS = 50000L;
    private static final long GESTURE_GAP_MS = 3000L;
    private static final int MAX_CLOSE_STEPS = 3;

    private static AdWatchService sInstance;
    private static String sLastDump = "尚未采集";
    private static String sCurClass = "?";
    private static long sLastDumpAt = 0L;
    private static String sLog = "";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollOnce();
        }
    };

    private Prefs mPrefs;
    private int mState = IDLE;
    private int mEntryAttempts = 0;
    private int mIdleRounds = 0;
    private int mCloseSteps = 0;
    private long mStateEnteredAt = 0L;
    private long mRunStartedAt = 0L;
    private long mPauseUntil = 0L;
    private boolean mAdSeen = false;
    private boolean mStopping = false;
    private int mResetCount = 0;
    private boolean mGesturePending = false;
    private long mLastGestureAt = 0L;
    private long mClaimTappedAt = 0L;
    private int mNewAdCount = 0;
    private int mGreenTaps = 0;
    private int mShotIdleLog = 0;
    private boolean mShotPending = false;
    private boolean mShotFailed = false;
    private long mLastShotAt = 0L;

    static String getLastDump() {
        return sLastDump;
    }

    static String getLog() {
        return sLog.isEmpty() ? "暂无动作记录" : sLog;
    }

    private static void log(String msg) {
        String stamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date());
        String line = stamp + " " + msg;
        sLog = line + "\n" + sLog;
        if (sLog.length() > 1500) {
            sLog = sLog.substring(0, 1500);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        mPrefs = new Prefs(getApplicationContext());
        mPrefs.ensureFirstRun();
        mRunStartedAt = System.currentTimeMillis();
        log("服务已连接");
        enterState(IDLE);
        scheduleNext(0L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || mStopping || mPrefs == null) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !Config.TARGET_PACKAGE.equals(pkg.toString())) {
            return;
        }
        CharSequence cls = event.getClassName();
        if (cls != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            sCurClass = cls.toString();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        mHandler.removeCallbacks(mPollRunnable);
        super.onDestroy();
    }

    private void enterState(int state) {
        mState = state;
        mStateEnteredAt = System.currentTimeMillis();
        if (state == CLOSE_AD) {
            mCloseSteps = 0;
            mClaimTappedAt = 0L;
            mNewAdCount = 0;
            mGreenTaps = 0;
            mShotIdleLog = 0;
            mLastCountdownAt = 0L;
        }
    }

    private void scheduleNext(long delayMs) {
        mHandler.removeCallbacks(mPollRunnable);
        mHandler.postDelayed(mPollRunnable, delayMs);
    }

    private void pollOnce() {
        if (mStopping || mPrefs == null) {
            return;
        }
        enforceGlobalTimeout();
        if (mStopping) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        if (!isTargetForeground(root)) {
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        step(root);
    }

    private void step(AccessibilityNodeInfo root) {
        if (mState != CLOSE_AD && isLivePage()) {
            if (!mGesturePending && System.currentTimeMillis() - mLastGestureAt >= 3000L) {
                boolean ok = tapRatio(0.93f, 0.072f);
                log("当前页面是直播间，先点叉退出直播间 手势=" + ok);
            }
            scheduleNext(1200L);
            return;
        }
        if (mState == WAIT_ENTRY) {
            runWaitEntry(root);
        } else if (mState == CLOSE_AD) {
            runCloseAd(root);
        } else {
            runIdle(root);
        }
    }

    private boolean isLivePage() {
        String low = String.valueOf(sCurClass).toLowerCase();
        return low.contains("liveplayer") || low.contains("live.player")
                || low.contains("lunalive") || low.contains("defaultlive");
    }

    private boolean isTargetForeground(AccessibilityNodeInfo root) {
        return Config.TARGET_PACKAGE.equals(String.valueOf(root.getPackageName()));
    }

    private void runIdle(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now < mPauseUntil) {
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        mAdSeen = false;
        collectDump(root);
        if (isAdPage(root)) {
            log("当前就是广告页，开始关闭");
            mAdSeen = true;
            enterState(CLOSE_AD);
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        if (dialogVisible(root)) {
            if (tapClaim(root)) {
                log("发现领取奖励弹窗，已点击");
                mAdSeen = true;
                enterState(CLOSE_AD);
                scheduleNext(Config.POLL_INTERVAL_MS);
                return;
            }
        }
        AccessibilityNodeInfo entry = findEntryNode(root);
        if (entry != null && clickNode(entry)) {
            log("按文字找到「免费听」按钮，点击它 " + brief(entry));
            enterState(WAIT_ENTRY);
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        if (Build.VERSION.SDK_INT >= 30 && !mOcrFailed) {
            if (!mShotPending && System.currentTimeMillis() - mLastShotAt >= 2400L) {
                mShotMode = 1;
                lookAtScreen();
            }
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        mIdleRounds += 1;
        if (mIdleRounds >= 2) {
            mIdleRounds = 0;
            if (tapRatio(ENTRY_X, ENTRY_Y)) {
                log("节点没找到，按坐标点免费听按钮");
                enterState(WAIT_ENTRY);
                scheduleNext(Config.POLL_INTERVAL_MS);
                return;
            }
        }
        scheduleNext(Config.POLL_INTERVAL_MS);
    }

    private AccessibilityNodeInfo findEntryNode(AccessibilityNodeInfo root) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<AccessibilityNodeInfo>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard < 400) {
            AccessibilityNodeInfo n = queue.poll();
            guard += 1;
            if (n == null) {
                continue;
            }
            CharSequence text = n.getText();
            CharSequence desc = n.getContentDescription();
            String s = (text == null ? "" : text.toString()) + "|" + (desc == null ? "" : desc.toString());
            if (s.contains("免费听") && !s.contains("提前") && !s.contains("再看")) {
                android.graphics.Rect r = new android.graphics.Rect();
                n.getBoundsInScreen(r);
                float cy = (r.top + r.bottom) * 0.5f / h;
                float cx = (r.left + r.right) * 0.5f / w;
                if (cx > 0.5f && cy >= 0.35f && cy <= 0.88f && (r.right - r.left) < 520) {
                    AccessibilityNodeInfo clickable = nearestClickable(n);
                    return clickable != null ? clickable : n;
                }
            }
            for (int i = 0; i < n.getChildCount(); i += 1) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    private boolean lookLikeEntry(AccessibilityNodeInfo n) {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            int h = getResources().getDisplayMetrics().heightPixels;
            float cy = (r.top + r.bottom) * 0.5f / h;
            int w = r.right - r.left;
            if (cy < 0.50f || cy > 0.64f) {
                if (System.currentTimeMillis() - mEntryLogAt >= 8000L) {
                    mEntryLogAt = System.currentTimeMillis();
                    log("这个按钮位置不对（中心 y=" + ((int) (cy * 100)) + "%），改用截图认字");
                }
                return false;
            }
            if (w > 460) {
                if (System.currentTimeMillis() - mEntryLogAt >= 8000L) {
                    mEntryLogAt = System.currentTimeMillis();
                    log("这个按钮太宽（" + w + "px），改用截图认字");
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void runWaitEntry(AccessibilityNodeInfo root) {
        long elapsed = elapsedInState();
        if (dialogVisible(root)) {
            if (tapClaim(root)) {
                log("弹出领取奖励，已点击");
                mAdSeen = true;
                enterState(CLOSE_AD);
                scheduleNext(Config.POLL_INTERVAL_MS);
                return;
            }
        }
        if (isAdPage(root) && elapsed >= 2500L) {
            log("广告页已出现");
            mAdSeen = true;
            enterState(CLOSE_AD);
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        if (elapsed >= 10000L) {
            mEntryAttempts += 1;
            if (mEntryAttempts >= 4) {
                log("入口连续没反应，重启汽水音乐 class=" + sCurClass);
                forceResetTarget();
                return;
            }
            if (mEntryAttempts % 2 == 1) {
                tapRatio(SHEET_X, SHEET_Y);
                log("入口没反应，先关掉畅听卡片再试");
                mPauseUntil = System.currentTimeMillis() + 1200L;
            } else {
                log("等了 10 秒没进广告页，重新尝试 class=" + sCurClass);
                mPauseUntil = System.currentTimeMillis() + 2000L;
            }
            enterState(IDLE);
        }
        scheduleNext(Config.POLL_INTERVAL_MS);
    }

    private void runCloseAd(AccessibilityNodeInfo root) {
    if (!isAdPage(root) && !dialogVisible(root)) {
        if (elapsedInState() < 1200L) {
            scheduleNext(300L);
            return;
        }
        log("已回到歌曲页，本轮结束");
        mNewAdCount = 0;
        mClaimTappedAt = 0L;
        mGreenTaps = 0;
        if (mAdSeen) {
            finishClaim();
            if (tapRatio(SHEET_X, SHEET_Y)) {
                log("关掉畅听卡片");
            }
        }
        mPauseUntil = System.currentTimeMillis() + 4000L;
        enterState(IDLE);
        scheduleNext(Config.POLL_INTERVAL_MS);
        return;
    }
    if (dialogVisible(root)) {
        if (tapClaim(root)) {
            log("先点领取奖励再关闭");
            mStateEnteredAt = System.currentTimeMillis();
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
    }
    if (mShotIdleLog == 0) {
        mShotIdleLog = 1;
        log("广告页已出现，开始认字（v31）");
    }
    if (Build.VERSION.SDK_INT >= 30 && !mOcrFailed) {
        if (!mShotPending && System.currentTimeMillis() - mLastShotAt >= 1300L) {
            mShotMode = 0;
            lookAtScreen();
        }
        scheduleNext(800L);
        return;
    }
    runCloseAdBlind(root);
}

private void runCloseAdBlind(AccessibilityNodeInfo root) {
    if (mGesturePending || System.currentTimeMillis() - mLastGestureAt < GESTURE_GAP_MS) {
        scheduleNext(1000L);
        return;
    }
    mCloseSteps += 1;
    if (mCloseSteps == 2) {
        boolean ok = tapRatio(0.93f, 0.072f);
        log("先点最右上角叉（退出直播间/关闭）手势=" + ok);
    } else if (mCloseSteps == 3) {
        boolean ok = tapRatio(CLAIM_X, CLAIM_Y);
        log("点中间绿色「领取奖励」手势=" + ok);
    } else if (mCloseSteps == 4) {
        boolean ok = tapRatio(CLOSE_X, CLOSE_Y);
        log("点「领取成功」旁的叉 手势=" + ok);
    } else if (mCloseSteps == 5) {
        boolean ok = longPressRatio(CLOSE_X, CLOSE_Y);
        log("长按右上角关闭 手势=" + ok);
    } else if (mCloseSteps == 6) {
        boolean ok = tapRatio(0.878f, 0.945f);
        log("点右下角小叉 手势=" + ok);
    } else {
        log("广告页还是关不掉，先把汽水音乐拉回歌曲页");
        mAdSeen = false;
        mCloseSteps = 0;
        mClaimTappedAt = 0L;
        mNewAdCount = 0;
        mGreenTaps = 0;
        mPauseUntil = System.currentTimeMillis() + 8000L;
        enterState(IDLE);
        launchTargetClear();
        scheduleNext(Config.POLL_INTERVAL_MS);
        return;
    }
        scheduleNext(GESTURE_GAP_MS);
}

private void lookAtScreen() {
    if (mShotPending) {
        return;
    }
    mShotPending = true;
    mLastShotAt = System.currentTimeMillis();
    try {
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
            @Override
            public void onSuccess(AccessibilityService.ScreenshotResult result) {
                mShotPending = false;
                try {
                    android.graphics.Bitmap hb = android.graphics.Bitmap.wrapHardwareBuffer(
                            result.getHardwareBuffer(), result.getColorSpace());
                    if (hb != null) {
                        android.graphics.Bitmap soft = hb.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                        if (soft != null) {
                            runOcr(soft, mShotMode);
                        }
                    }
                } catch (Throwable t) {
                    log("截图解析失败 " + t);
                }
                try {
                    result.getHardwareBuffer().close();
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onFailure(int errorCode) {
                mShotPending = false;
                mShotFailed = true;
                mOcrFailed = true;
                log("截图失败 code=" + errorCode + "，改用盲点");
            }
        });
    } catch (Throwable t) {
        mShotPending = false;
        mShotFailed = true;
        mOcrFailed = true;
        log("截图不可用 " + t);
    }
}

private com.google.mlkit.vision.text.TextRecognizer mOcr = null;
private boolean mOcrBusy = false;
private boolean mOcrFailed = false;
private long mOcrLogAt = 0L;
private long mLastCountdownAt = 0L;
private int mShotMode = 0;
private long mEntryLogAt = 0L;
private int mIdleOcrMiss = 0;
private int mEntryAlt = 0;
private long mOcrStartAt = 0L;

private com.google.mlkit.vision.text.TextRecognizer ocr() {
    if (mOcr == null) {
        mOcr = com.google.mlkit.vision.text.TextRecognition.getClient(
                new com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build());
    }
    return mOcr;
}

private void runOcr(android.graphics.Bitmap bmp, final int mode) {
    if (mOcrBusy && System.currentTimeMillis() - mOcrStartAt > 12000L) {
        mOcrBusy = false;
        log("上一次文字识别超时，重新开始");
    }
    if (mOcrBusy) {
        return;
    }
    mOcrBusy = true;
    mOcrStartAt = System.currentTimeMillis();
    try {
        com.google.mlkit.vision.common.InputImage img =
                com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0);
        ocr().process(img).addOnSuccessListener(
                new com.google.android.gms.tasks.OnSuccessListener<com.google.mlkit.vision.text.Text>() {
            @Override
            public void onSuccess(com.google.mlkit.vision.text.Text result) {
                mOcrBusy = false;
                String s = result.getText();
                if (s == null) {
                    s = "";
                }
                safeRecycle(bmp);
                if (mode == 1) {
                    actByEntry(result);
                } else {
                    actByText(s.replace(" ", "").replace("\n", ""));
                }
            }
        }).addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
            @Override
            public void onFailure(Exception e) {
                mOcrBusy = false;
                mOcrFailed = true;
                safeRecycle(bmp);
                log("文字识别失败 " + e);
            }
        });
    } catch (Throwable t) {
        mOcrBusy = false;
        mOcrFailed = true;
        safeRecycle(bmp);
        log("文字识别不可用 " + t);
    }
}

private void safeRecycle(android.graphics.Bitmap bmp) {
    try {
        if (bmp != null && !bmp.isRecycled()) {
            bmp.recycle();
        }
    } catch (Throwable ignored) {
    }
}

private String brief(String s) {
    if (s == null) {
        return "";
    }
    return s.length() > 16 ? s.substring(0, 16) : s;
}

private void actByEntry(com.google.mlkit.vision.text.Text result) {
    if (mGesturePending || System.currentTimeMillis() - mLastGestureAt < GESTURE_GAP_MS) {
        return;
    }
    if (mState != IDLE) {
        return;
    }
    int w = getResources().getDisplayMetrics().widthPixels;
    int h = getResources().getDisplayMetrics().heightPixels;
    for (com.google.mlkit.vision.text.Text.TextBlock block : result.getTextBlocks()) {
        String s = block.getText();
        if (s == null) {
            continue;
        }
        s = s.replace(" ", "").replace("\n", "");
        if (!s.contains("免费听") || s.contains("提前") || s.contains("再看") || s.contains("金币")) {
            continue;
        }
        android.graphics.Rect r = block.getBoundingBox();
        if (r == null) {
            continue;
        }
        float cy = (r.top + r.bottom) * 0.5f / h;
        float cx = (r.left + r.right) * 0.5f / w;
        if (cy < 0.15f || cy > 0.86f) {
            continue;
        }
        if (cx < 0.50f) {
            continue;
        }
        boolean ok = tapRatio(cx, cy);
        log("截到「" + brief(s) + "」按钮，点它（" + ((int) (cx * 100)) + "%,"
                + ((int) (cy * 100)) + "%）手势=" + ok);
        mIdleOcrMiss = 0;
        if (ok) {
            mIdleRounds = 0;
            enterState(WAIT_ENTRY);
        }
        return;
    }
    mIdleOcrMiss += 1;
    int miss = mIdleOcrMiss;
    if (miss == 2 || miss == 5 || miss == 8) {
        float x = (mEntryAlt % 2 == 0) ? ENTRY_X : 0.767f;
        float y = (mEntryAlt % 2 == 0) ? ENTRY_Y : 0.807f;
        mEntryAlt += 1;
        boolean ok = tapRatio(x, y);
        log("没认出按钮，按备用坐标点（" + ((int) (x * 100)) + "%,"
                + ((int) (y * 100)) + "%）手势=" + ok);
        if (ok) {
            enterState(WAIT_ENTRY);
        }
    }
}

private void actByText(String s) {
    long el = elapsedInState();
    if (mGesturePending || System.currentTimeMillis() - mLastGestureAt < GESTURE_GAP_MS) {
        return;
    }
    boolean countdown = s.contains("秒后可领取") || s.contains("秒后领取") || s.contains("后可领取");
    boolean paused = s.contains("继续观看") || s.contains("继续解锁") || s.contains("坚持退出");
    boolean finished = s.contains("领取成功");
    boolean claim = !countdown && (finished || s.contains("领取奖励"));
    int marks = 0;
    if (s.contains("版本号")) {
        marks += 1;
    }
    if (s.contains("开发者")) {
        marks += 1;
    }
    if (s.contains("隐私")) {
        marks += 1;
    }
    if (s.contains("举报")) {
        marks += 1;
    }
    if (s.contains("权限")) {
        marks += 1;
    }
    boolean landing = marks >= 2 && !countdown;
    boolean quiet = System.currentTimeMillis() - mOcrLogAt < 6000L;
    long sinceCountdown = System.currentTimeMillis() - mLastCountdownAt;

    if (paused) {
        if (mGreenTaps >= 6) {
            log("「继续观看」点了很多次还没恢复，先关掉");
            closeLadder();
            return;
        }
        mGreenTaps += 1;
        mCloseSteps = 0;
        boolean ok = tapRatio(CLAIM_X, CLAIM_Y);
        log("识别到「继续观看」，点一次恢复播放 第" + mGreenTaps + "次 手势=" + ok);
        return;
    }

    if (countdown) {
        mLastCountdownAt = System.currentTimeMillis();
        mGreenTaps = 0;
        mCloseSteps = 0;
        if (el < AD_MIN_WATCH_MS + 100000L) {
            if (!quiet) {
                mOcrLogAt = System.currentTimeMillis();
                log("倒计时还在走（" + brief(s) + "），继续等，不点屏幕");
            }
            return;
        }
        closeLadder();
        return;
    }

    if (claim && el >= 45000L && sinceCountdown > 12000L) {
        mGreenTaps = 0;
        if (mCloseSteps < 3) {
            mCloseSteps += 1;
            boolean ok = tapRatio(CLAIM_X, CLAIM_Y);
            log("广告播完了，识别到「领取成功/领取奖励」，点绿色领取 第" + mCloseSteps + "次 手势=" + ok);
            return;
        }
        boolean ok = tapRatio(CLOSE_X, CLOSE_Y);
        log("领取点了几次，点「领取成功」旁的叉 手势=" + ok);
        return;
    }

    if (landing && el >= 35000L && sinceCountdown > 6000L) {
        mGreenTaps = 0;
        int step = (mCloseSteps % 2) + 1;
        mCloseSteps = mCloseSteps + 1;
        boolean ok;
        if (step == 1) {
            ok = tapRatio(0.878f, 0.945f);
            log("识别到广告落地页，点底部小叉 手势=" + ok);
        } else {
            ok = tapRatio(CLOSE_X, CLOSE_Y);
            log("落地页还在，点右上角叉 手势=" + ok);
        }
        return;
    }

    if (el < AD_MIN_WATCH_MS + 100000L) {
        if (!quiet) {
            mOcrLogAt = System.currentTimeMillis();
            log("只看不点，等广告播完（" + brief(s) + "）");
        }
        return;
    }
    closeLadder();
}

private void closeLadder() {
    mCloseSteps += 1;
    if (mCloseSteps <= 2) {
        boolean ok = tapRatio(0.905f, 0.0703f);
        log("点「领取成功」旁的叉 手势=" + ok);
    } else if (mCloseSteps <= 4) {
        boolean ok = tapRatio(0.878f, 0.945f);
        log("点右下角小叉 手势=" + ok);
    } else if (mCloseSteps <= 6) {
        boolean ok = longPressRatio(0.905f, 0.0703f);
        log("长按右上角关闭 手势=" + ok);
    } else {
        log("实在关不掉，先把汽水音乐拉回歌曲页");
        mAdSeen = false;
        mCloseSteps = 0;
        mClaimTappedAt = 0L;
        mNewAdCount = 0;
        mGreenTaps = 0;
        mPauseUntil = System.currentTimeMillis() + 8000L;
        enterState(IDLE);
        launchTargetClear();
    }
}


private void forceResetTarget() {
        mResetCount += 1;
        if (mResetCount >= 3) {
            mResetCount = 0;
            mEntryAttempts = 0;
            mCloseSteps = 0;
            log("连续重启仍没进广告页，暂停一会儿，请把汽水音乐回到歌曲页");
            mPauseUntil = System.currentTimeMillis() + 60000L;
            enterState(IDLE);
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        mAdSeen = false;
        mCloseSteps = 0;
        mEntryAttempts = 0;
        mPauseUntil = System.currentTimeMillis() + 8000L;
        enterState(IDLE);
        if (mResetCount == 1) {
            log("先把汽水音乐拉回主界面，清掉广告页");
            launchTargetClear();
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        performGlobalAction(GLOBAL_ACTION_HOME);
        log("切到后台再结束进程");
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        am.killBackgroundProcesses(Config.TARGET_PACKAGE);
                        log("已结束汽水音乐后台进程");
                    }
                } catch (Throwable t) {
                    log("结束进程失败 " + t);
                }
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        launchTargetClear();
                    }
                }, 1200L);
            }
        }, 1500L);
        scheduleNext(Config.POLL_INTERVAL_MS);
    }

    private void launchTargetClear() {
        try {
            Intent it = getPackageManager().getLaunchIntentForPackage(Config.TARGET_PACKAGE);
            if (it == null) {
                log("重启失败：找不到汽水音乐");
                return;
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(it);
            log("已重新打开汽水音乐并清掉上层页面");
        } catch (Throwable t) {
            log("重启失败 " + t);
        }
    }

    private void launchTarget() {
        try {
            Intent it = getPackageManager().getLaunchIntentForPackage(Config.TARGET_PACKAGE);
            if (it == null) {
                log("重启失败：找不到汽水音乐");
                return;
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(it);
            log("已重新打开汽水音乐");
        } catch (Throwable t) {
            log("重启失败 " + t);
        }
    }

    private void finishClaim() {
        mPrefs.incrementCount();
        mResetCount = 0;
        log("本轮领取完成，累计 " + mPrefs.getCount() + " 次");
        if (System.currentTimeMillis() - mPrefs.getFirstRunMs() >= Config.THIRTY_DAYS_MS) {
            mStopping = true;
            mHandler.removeCallbacks(mPollRunnable);
            showCompletionNotice();
            stopSelf();
        }
    }

    private void enforceGlobalTimeout() {
        long total = System.currentTimeMillis() - mRunStartedAt;
        long budget = Math.max(Config.THIRTY_DAYS_MS, Config.ENTRY_WAIT_MS + Config.AD_WAIT_MS
                + Config.CLAIM1_WAIT_MS + Config.CLAIM2_WAIT_MS);
        if (total >= budget) {
            mStopping = true;
            mHandler.removeCallbacks(mPollRunnable);
            stopSelf();
        }
    }

    private long elapsedInState() {
        return System.currentTimeMillis() - mStateEnteredAt;
    }

    private boolean tapClaim(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo reward = findClickable(root, DIALOG_HINTS);
        if (reward != null && clickNode(reward)) {
            return true;
        }
        AccessibilityNodeInfo reward2 = findClickable(root, Config.CLAIM_REWARD_KEYWORDS);
        if (reward2 != null && clickNode(reward2)) {
            return true;
        }
        return tapRatio(CLAIM_X, CLAIM_Y);
    }

    private boolean isAdPage(AccessibilityNodeInfo root) {
        if (hasAnyText(root, AD_PAGE_HINTS, 0)) {
            return true;
        }
        String low = String.valueOf(sCurClass).toLowerCase();
        return low.contains("adactivity") || low.contains("ads.") || low.contains(".ad.")
                || low.contains("adwebview") || low.contains("adcontainer")
                || low.contains("excitingvideo") || low.contains("videoactivity")
                || low.contains("rewardvideo") || low.contains("ss.android");
    }

    private boolean dialogVisible(AccessibilityNodeInfo root) {
        return hasAnyText(root, DIALOG_HINTS, 0);
    }

    private boolean hasAnyText(AccessibilityNodeInfo node, String[] keywords, int depth) {
        if (node == null || keywords == null || depth > 14) {
            return false;
        }
        String text = textOf(node.getText());
        String desc = textOf(node.getContentDescription());
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isEmpty()
                    && (text.contains(keyword) || desc.contains(keyword))) {
                return true;
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i += 1) {
            if (hasAnyText(node.getChild(i), keywords, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo root, String[] keywords) {
        return findClickable(root, keywords, 0);
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo root, String[] keywords, int depth) {
        if (root == null || keywords == null || depth > 12) {
            return null;
        }
        if (isMatching(root, keywords) && isOnScreen(root)) {
            AccessibilityNodeInfo clickable = nearestClickable(root);
            if (clickable != null) {
                return clickable;
            }
            return root;
        }
        int count = root.getChildCount();
        for (int i = 0; i < count; i += 1) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo hit = findClickable(child, keywords, depth + 1);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private boolean isOnScreen(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() <= 0 || rect.height() <= 0) {
            return false;
        }
        int h = screenHeight();
        return rect.centerY() > 0 && rect.centerY() < h && rect.centerX() > 0 && rect.centerX() < screenWidth();
    }

    private AccessibilityNodeInfo nearestClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int hops = 0;
        while (current != null && hops <= 6) {
            if (current.isClickable()) {
                return current;
            }
            current = current.getParent();
            hops += 1;
        }
        return null;
    }

    private boolean isMatching(AccessibilityNodeInfo node, String[] keywords) {
        String text = textOf(node.getText());
        String desc = textOf(node.getContentDescription());
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isEmpty() && (text.contains(keyword) || desc.contains(keyword))) {
                return true;
            }
        }
        return false;
    }

    private static String textOf(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        if (rect.width() <= 0 || rect.height() <= 0) {
            return false;
        }
        return tapPoint(rect.centerX(), rect.centerY());
    }

    private boolean tapRatio(float xRatio, float yRatio) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        int width = screenWidth();
        int height = screenHeight();
        return tapPoint(Math.round(width * xRatio), Math.round(height * yRatio));
    }

    private int screenWidth() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(dm);
        }
        if (dm.widthPixels <= 0) {
            dm = getResources().getDisplayMetrics();
        }
        return dm.widthPixels;
    }

    private int screenHeight() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(dm);
        }
        if (dm.heightPixels <= 0) {
            dm = getResources().getDisplayMetrics();
        }
        return dm.heightPixels;
    }

    private boolean tapPoint(int x, int y) {
        return pressPoint(x, y, 150L);
    }

    private boolean longPressRatio(float xRatio, float yRatio) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        return pressPoint(Math.round(screenWidth() * xRatio), Math.round(screenHeight() * yRatio), 800L);
    }

    private boolean pressPoint(final int x, final int y, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        if (mGesturePending) {
            return false;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, durationMs);
        GestureDescription description = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        mGesturePending = true;
        mLastGestureAt = System.currentTimeMillis();
        boolean ok = dispatchGesture(description, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gesture) {
                mGesturePending = false;
                log("手势完成 (" + x + "," + y + ")");
            }

            @Override
            public void onCancelled(GestureDescription gesture) {
                mGesturePending = false;
                log("手势被系统取消 (" + x + "," + y + ")");
            }
        }, null);
        if (!ok) {
            mGesturePending = false;
        }
        return ok;
    }

    private String brief(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        String text = textOf(node.getText());
        if (text.isEmpty()) {
            text = textOf(node.getContentDescription());
        }
        return "[" + text + " " + rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom + "]";
    }

    private void collectDump(AccessibilityNodeInfo root) {
        if (System.currentTimeMillis() - sLastDumpAt < 1500L) {
            return;
        }
        sLastDumpAt = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("pkg=").append(textOf(root.getPackageName()))
                .append(" screen=").append(screenWidth()).append("x").append(screenHeight()).append("\n");
        collect(root, sb, 0);
        sLastDump = sb.length() == 0 ? "空节点树" : sb.toString();
    }

    private void collect(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || sb.length() > 12000 || depth > 22) {
            return;
        }
        String text = textOf(node.getText());
        String desc = textOf(node.getContentDescription());
        if (!text.isEmpty() || !desc.isEmpty()) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            sb.append(textOf(node.getClassName()))
                    .append(" c=").append(node.isClickable() ? 1 : 0)
                    .append(" t=").append(text)
                    .append(" d=").append(desc)
                    .append(" b=").append(rect.left).append(",").append(rect.top)
                    .append(",").append(rect.right).append(",").append(rect.bottom)
                    .append("\n");
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i += 1) {
            collect(node.getChild(i), sb, depth + 1);
        }
    }

    private void showCompletionNotice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createChannel();
                Notification notification = new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("AutoWatch AD")
                        .setContentText("30 天周期已完成")
                        .setAutoCancel(true)
                        .build();
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.notify(1, notification);
                    return;
                }
            }
            Toast.makeText(getApplicationContext(), "30 天周期已完成", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            Toast.makeText(getApplicationContext(), "30 天周期已完成", Toast.LENGTH_LONG).show();
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "AutoWatch AD", NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }
    }
}
