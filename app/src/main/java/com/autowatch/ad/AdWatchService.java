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
    private boolean mSawCountdown = false;
    private int mBlindStep = 0;
    private long mLastBlindAt = 0L;
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
            mAdEndAt = 0L;
            mBonusRefused = 0;
            mLastSec = -1;
            mSecSince = 0L;
            mLockedCount = 0;
            mRewardDialogs = 0;
            mHeaderTaps = 0;
            mHeadClaimAt = 0L;
            mPillFound = false;
            mSawCountdown = false;
            mBlindStep = 0;
            mLastBlindAt = 0L;
            mNoHeaderSign = 0;
            mBackTapAt = 0L;
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
        if (dismissSystemDialog()) {
            scheduleNext(1200L);
            return;
        }
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

    private boolean dismissSystemDialog() {
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) {
                return false;
            }
            for (int i = 0; i < wins.size(); i++) {
                android.view.accessibility.AccessibilityWindowInfo win = wins.get(i);
                if (win == null) {
                    continue;
                }
                AccessibilityNodeInfo wr = win.getRoot();
                if (wr == null) {
                    continue;
                }
                CharSequence pk = wr.getPackageName();
                String p = pk == null ? "" : pk.toString();
                if (Config.TARGET_PACKAGE.equals(p)) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                collectNodeText(wr, 4, sb);
                String shallow = sb.toString();
                if (!shallow.contains("想要打开") && !shallow.contains("仅打开一次")
                        && !shallow.contains("始终打开")) {
                    continue;
                }
                AccessibilityNodeInfo cancel = findNodeText(wr, "取消", 8);
                if (cancel == null) {
                    continue;
                }
                if (mGesturePending || System.currentTimeMillis() - mLastGestureAt < 2000L) {
                    return true;
                }
                android.graphics.Rect b = new android.graphics.Rect();
                cancel.getBoundsInScreen(b);
                int wpx = getResources().getDisplayMetrics().widthPixels;
                int hpx = getResources().getDisplayMetrics().heightPixels;
                boolean ok = tapRatio((b.left + b.right) / 2f / wpx, (b.top + b.bottom) / 2f / hpx);
                log("广告要跳到别的应用，点系统弹窗的「取消」手势=" + ok);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void collectNodeText(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || depth < 0) {
            return;
        }
        CharSequence t = n.getText();
        if (t != null) {
            sb.append(t).append('|');
        }
        CharSequence d = n.getContentDescription();
        if (d != null) {
            sb.append(d).append('|');
        }
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) {
            collectNodeText(n.getChild(i), depth - 1, sb);
        }
    }

    private AccessibilityNodeInfo findNodeText(AccessibilityNodeInfo n, String key, int depth) {
        if (n == null || depth < 0) {
            return null;
        }
        CharSequence t = n.getText();
        if (t != null && t.toString().contains(key)) {
            return n;
        }
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo r = findNodeText(n.getChild(i), key, depth - 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private void step(AccessibilityNodeInfo root) {
        try {
            if (findNodeDescOrText(root, "选择要使用的应用", 20) != null
                    || findNodeDescOrText(root, "仅打开一次", 20) != null) {
                long nowS = System.currentTimeMillis();
                if (nowS - mBackTapAt >= 4000L) {
                    mBackTapAt = nowS;
                    boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
                    log("识别到「选择要使用的应用/想要打开」系统弹窗，按返回关掉 手势=" + ok);
                }
                scheduleNext(800L);
                return;
            }
        } catch (Throwable ignored) {
        }
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
        if (mOcrFailed && dialogVisible(root)) {
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

    private AccessibilityNodeInfo findNodeDescOrText(AccessibilityNodeInfo n, String key, int depth) {
        if (n == null || depth <= 0) {
            return null;
        }
        CharSequence d = n.getContentDescription();
        if (d != null && d.toString().contains(key)) {
            return n;
        }
        CharSequence t = n.getText();
        if (t != null && t.toString().contains(key)) {
            return n;
        }
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo r = findNodeDescOrText(n.getChild(i), key, depth - 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private String claimNodeDesc(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo node = findNodeDescOrText(root, "领取成功", 30);
        if (node == null) {
            return null;
        }
        CharSequence d = node.getContentDescription();
        String s = d == null ? "" : d.toString();
        if (s.length() == 0) {
            CharSequence t = node.getText();
            s = t == null ? "" : t.toString();
        }
        if (s.contains("秒") || s.contains("后")) {
            return null;
        }
        return s;
    }

    private boolean clickClaimNode(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo node = findNodeDescOrText(root, "领取成功", 30);
        if (node == null) {
            return false;
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() <= 0 || rect.height() <= 0) {
            return false;
        }
        return tapPoint(rect.left + (int) (rect.width() * 0.45f), rect.centerY());
    }

    private boolean clickClaimNodeNow() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (claimNodeDesc(root) == null) {
                return false;
            }
            return clickClaimNode(root);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasClaimNode() {
        try {
            return claimNodeDesc(getRootInActiveWindow()) != null;
        } catch (Throwable t) {
            return false;
        }
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
        if (mOcrFailed && dialogVisible(root)) {
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
        if (Build.VERSION.SDK_INT >= 30 && !mOcrFailed) {
            if (!mShotPending && System.currentTimeMillis() - mLastShotAt >= 1900L) {
                mShotMode = 0;
                lookAtScreen();
            }
            scheduleNext(900L);
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
    if (isSongPage(root) || (!isAdPage(root) && !dialogVisible(root))) {
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
    if (mOcrFailed && dialogVisible(root)) {
        if (tapClaim(root)) {
            log("先点领取奖励再关闭");
            mStateEnteredAt = System.currentTimeMillis();
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
    }
    if (mShotIdleLog == 0) {
        mShotIdleLog = 1;
        log("广告页已出现，开始认字（v45：先看节点树有没有领取成功按钮）");
    }
    long nowNode = System.currentTimeMillis();
    boolean adOver = mAdEndAt <= 0L || nowNode > mAdEndAt + 1000L;
    if (adOver && nowNode - mLastCountdownAt > 3000L && nowNode - mDialogSeenAt >= 5000L
            && nowNode - mHeadClaimAt >= 5000L) {
        String desc = claimNodeDesc(root);
        if (desc != null) {
            boolean ok = clickClaimNode(root);
            mHeadClaimAt = nowNode;
            mAdEndAt = 0L;
            log("节点树里找到「" + desc + "」按钮，直接点它领取 手势=" + ok);
            scheduleNext(900L);
            return;
        }
    }
    if (Build.VERSION.SDK_INT >= 30 && !mOcrFailed) {
        if (!mShotPending && System.currentTimeMillis() - mLastShotAt >= 1700L) {
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
        boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
        log("按一次返回键退出广告 手势=" + ok);
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
                            if (mShotMode == 0) {
                                mTick += 1;
                                if (mPendingFull != null) {
                                    safeRecycle(mPendingFull);
                                    mPendingFull = null;
                                }
                                mPendingFull = soft;
                                android.graphics.Bitmap head = null;
                                try {
                                    int hh = (int) (soft.getHeight() * 0.13f);
                                    if (hh < 40) {
                                        hh = Math.min(soft.getHeight(), 200);
                                    }
                                    head = android.graphics.Bitmap.createBitmap(soft, 0, 0, soft.getWidth(), hh);
                                } catch (Throwable t) {
                                    head = null;
                                }
                                if (head != null) {
                                    runOcr(head, 3);
                                } else if (mPendingFull != null) {
                                    android.graphics.Bitmap b = mPendingFull;
                                    mPendingFull = null;
                                    runOcr(b, 0);
                                } else {
                                    safeRecycle(soft);
                                }
                            } else {
                                runOcr(soft, mShotMode);
                            }
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
private long mAdEndAt = 0L;
private int mBonusRefused = 0;
private int mLastSec = -1;
private long mSecSince = 0L;
private int mLockedCount = 0;
private int mRewardDialogs = 0;
private int mHeaderTaps = 0;
private long mHeadClaimAt = 0L;
private long mPillAt = 0L;
private int mPillCx = 0;
private int mPillCy = 0;
private boolean mPillFound = false;
private int mTick = 0;
private android.graphics.Bitmap mPendingFull = null;
private long mHeaderLogAt = 0L;
private int mHeaderHit = 0;
private long mDialogSeenAt = 0L;
private int mNoHeaderSign = 0;
private long mBackTapAt = 0L;
private int mBackStep = 0;

private boolean looksLikeLanding(String s) {
    if (s == null || s.contains("秒")) {
        return false;
    }
            return s.contains("立即打开") || s.contains("立即下载") || s.contains("购物车")
            || s.contains("领券") || s.contains("进店") || s.contains("客服")
            || s.contains("加微信") || s.contains("下载") || s.contains("安装")
            || s.contains("版本号") || s.contains("开发者") || s.contains("旗舰店")
            || s.contains("适用于") || s.contains("已售")
            || s.contains("策略") || s.contains("战法") || s.contains("指标")
            || s.contains("掘金") || s.contains("交易日") || s.contains("精选")
            || s.contains("主线") || s.contains("行情") || s.contains("股市")
            || s.contains("保险") || s.contains("投保") || s.contains("理赔")
            || s.contains("保障") || s.contains("免健告") || s.contains("去付款")
            || s.contains("立即安装") || s.contains("应用商店") || s.contains("贷款")
            || s.contains("借款") || s.contains("额度") || s.contains("信用卡")
            || s.contains("红包") || s.contains("抽奖") || s.contains("保障责任");
}

// v51：把保险/贷款/应用商店这类落地页当广告页等死的问题修掉（广告 | 反馈 | 领取成功 ✕），不受广告画面文字干扰
private void actByHeader(com.google.mlkit.vision.text.Text result) {
    String s = result.getText();
    if (s == null) {
        return;
    }
    s = s.replace(" ", "").replace("\n", "");
    long now = System.currentTimeMillis();
    if (mGesturePending || now - mLastGestureAt < GESTURE_GAP_MS) {
        return;
    }
    if (s.contains("领取成功")) {
        mHeaderHit += 1;
        if (now - mDialogSeenAt < 6000L) {
            if (now - mHeaderLogAt >= 5000L) {
                mHeaderLogAt = now;
                log("页头读到「领取成功」，但奖励弹窗正开着，先让给弹窗，不点页头");
            }
            return;
        }
        if (now - mHeadClaimAt >= 7000L) {
            mHeadClaimAt = now;
            boolean ok = false;
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                ok = clickClaimNode(root);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                ok = tapRatio(0.88f, 0.078f);
                log("页头读到「领取成功」，按坐标点它领取 手势=" + ok);
            } else {
                log("页头读到「领取成功」，点节点按钮领取 成功");
            }
        }
        return;
    }
    int sec = parseSeconds(s);
    if (sec >= 2 && sec <= 75
            && (s.contains("领取") || s.contains("顿取") || s.contains("后可领"))) {
        mSawCountdown = true;
        mNoHeaderSign = 0;
        mLastCountdownAt = now;
        long end = now + (long) sec * 1000L;
        if (end > mAdEndAt) {
            mAdEndAt = end;
        }
        if (now - mHeaderLogAt >= 5000L) {
            mHeaderLogAt = now;
            log("页头读到倒计时 " + sec + " 秒，等它归零再点");
        }
        if (sec == mLastSec) {
            if (now - mSecSince >= 9000L) {
                mLockedCount += 1;
                if (mLockedCount >= 3) {
                    log("倒计时一直停在 " + sec + " 秒，广告卡死了，放弃这一轮");
                    mLockedCount = 0;
                    mLastSec = -1;
                    closeLadder();
                    return;
                }
                if (now - mBackTapAt >= 6000L) {
                    mBackTapAt = now;
                    boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
                    log("倒计时停在 " + sec + " 秒不动，广告被暂停了，按一次返回 手势=" + ok);
                }
            }
        } else {
            mLastSec = sec;
            mSecSince = now;
            mLockedCount = 0;
        }
        return;
    }
    mNoHeaderSign += 1;
    if (mNoHeaderSign >= 3) {
        mNoHeaderSign = 0;
        if (mAdEndAt > 0L) {
            mAdEndAt = 0L;
            log("页头连着几次既没读秒也没领取成功，清掉旧的计时");
        }
        if (now - mDialogSeenAt < 6000L) {
            return;
        }
        if (elapsedInState() >= 15000L) {
            if (hasClaimNode()) {
                if (now - mHeaderLogAt >= 5000L) {
                    mHeaderLogAt = now;
                    log("页头读不出字，但节点树里有「领取成功」按钮，先点它领取");
                }
                if (now - mHeadClaimAt >= 5000L) {
                    mHeadClaimAt = now;
                    boolean ok = clickClaimNodeNow();
                    log("按节点点了「领取成功」 手势=" + ok);
                }
                return;
            }
            if (now - mBackTapAt >= 12000L) {
                mBackTapAt = now;
                boolean ok = tapRatio(0.053f, 0.074f);
                log("页头读不到、也没有领取按钮，当落地页处理，点左上角返回箭头 手势=" + ok);
            }
        }
    }
}

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
    if (mode == 0) {
        scanGreenPill(bmp);
    }
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
                } else if (mode == 3) {
                    actByHeader(result);
                    android.graphics.Bitmap full = mPendingFull;
                    mPendingFull = null;
                    if (full != null) {
                        runOcr(full, 0);
                    }
                } else {
                    actByText(result);
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
    if (miss == 3 || miss == 9) {
        log("这还是认不出「免费听」，不乱点屏幕，再等一轮");
    }
    if (miss >= 3 && miss % 3 == 0 && !String.valueOf(sCurClass).contains("excitingvideo")) {
        boolean ok = tapRatio(0.745f, 0.809f);
        log("OCR 认不出入口，按实机量到的「免费听」坐标点一次（75%,81%）手势=" + ok);
        if (ok) {
            enterState(WAIT_ENTRY);
        }
    }
}

private void actByText(com.google.mlkit.vision.text.Text result) {
    String s = result.getText();
    if (s == null) {
        return;
    }
    s = s.replace(" ", "").replace("\n", "");
    long now = System.currentTimeMillis();
    long el = elapsedInState();
    if (s.contains("领取奖励") || s.contains("立即领取") || s.contains("坚持退出")) {
        mDialogSeenAt = now;
    }
    if (s.contains("选择要使用的应用") || s.contains("应用分身")
            || s.contains("想要打开") || s.contains("仅打开一次")) {
        if (now - mBackTapAt >= 4000L) {
            mBackTapAt = now;
            boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
            log("出现「选择要使用的应用」这类系统弹窗，按返回关掉 手势=" + ok);
        }
        return;
    }
    if (mGesturePending || now - mLastGestureAt < GESTURE_GAP_MS) {
        return;
    }
    if (s.contains("温馨提示") && (s.contains("前往抖音") || s.contains("继续查看"))) {
        if (now - mBackTapAt >= 4000L) {
            mBackTapAt = now;
            boolean ok = tapLineOn(result, new String[]{"继续查看", "取消"}, new String[]{"前往"},
                    0.30f, 0.95f, "弹出「温馨提示」，点「继续查看」不要跳抖音");
            if (!ok) {
                ok = performGlobalAction(GLOBAL_ACTION_BACK);
                log("「温馨提示」认不出继续查看，按返回关掉 手势=" + ok);
            }
        }
        return;
    }
    boolean bonusOffer = s.contains("坚持退出")
            && (s.contains("得免费") || s.contains("分钟") || s.contains("再看")
            || s.contains("继续解锁"));
    boolean paused = !bonusOffer && (s.contains("继续观看") || s.contains("继续解锁"));
    int sec = parseSeconds(s);
    boolean claimWord = (s.contains("领取") || s.contains("顿取"))
            && !s.contains("金币") && !s.contains("待领取") && !s.contains("提现");
    boolean countdown = sec >= 0 && claimWord;
    boolean finished = s.contains("领取成功");
    boolean claim = !countdown && (finished || claimWord);
    boolean rewardDialog = (s.contains("领取奖励") || s.contains("立即领取"))
            && (s.contains("提前得") || s.contains("坚持退出"));
    if (rewardDialog) {
        countdown = false;
        claim = false;
    }
    bonusOffer = bonusOffer && !rewardDialog;
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
    boolean landing = marks >= 2 && !countdown
            && (finished || s.contains("提现") || s.contains("我的现金")
            || s.contains("下载") || s.contains("安装") || s.contains("元"));
    boolean quiet = now - mOcrLogAt < 6000L;
    long sinceCountdown = now - mLastCountdownAt;

    if (rewardDialog) {
        mDialogSeenAt = now;
        mGreenTaps = 0;
        mAdEndAt = 0L;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        if (mRewardDialogs >= 2) {
            boolean esc = tapLineOnFrac(result, new String[]{"坚持退出", "退出"},
                    new String[]{"继续", "秒"},
                    0.55f, 0.70f, 0.5f, "奖励弹窗还没消失，点它自己的「坚持退出」退出去");
            if (esc) {
                mRewardDialogs = 0;
                log("已经用「坚持退出」退出奖励弹窗");
                return;
            }
            log("这个奖励弹窗点了 2 次还在，也没认出「坚持退出」，先不动");
            return;
        }
        boolean ok = false;
        if (mPillFound && now - mPillAt < 5000L) {
            ok = tapRatio((float) mPillCx / w, (float) mPillCy / h);
            log("弹窗绿色按钮认出来了（" + mPillCx + "," + mPillCy + "），点「领取奖励」手势=" + ok);
        }
        if (!ok) {
            ok = tapLineOnFrac(result, new String[]{"领取奖励"},
                    new String[]{"秒", "金币", "待领取", "提现", "我的", "坚持"},
                    0.42f, 0.63f, 0.5f, "看到「第N日免费听」奖励弹窗，点绿色「领取奖励」");
        }
        if (ok) {
            mCloseSteps = 0;
            mRewardDialogs += 1;
            log("奖励弹窗点了「领取奖励」，这是第 " + mRewardDialogs + " 次");
            return;
        }
        log("奖励弹窗里没认出绿色「领取奖励」，先不动");
        return;
    }

    if (bonusOffer) {
        mDialogSeenAt = now;
        mGreenTaps = 0;
        mCloseSteps = 0;
        mAdEndAt = 0L;
        mBonusRefused += 1;
        if (mBonusRefused >= 7) {
            log("加时弹窗点了 " + mBonusRefused + " 次都关不掉，放弃这一轮回歌曲页");
            mBonusRefused = 0;
            closeLadder();
            return;
        }
        if (mBonusRefused >= 3) {
            if (now - mBackTapAt >= 5000L) {
                mBackTapAt = now;
                boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
                log("加时弹窗点不掉，改按返回键 第" + mBonusRefused + "次 手势=" + ok);
                return;
            }
        }
        boolean nodeOk = false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo n = findNodeDescOrText(root, "坚持退出", 0);
            if (n == null) {
                n = findNodeDescOrText(root, "退出观看", 0);
            }
            if (n != null) {
                nodeOk = clickNode(n);
                log("加时弹窗里找到「坚持退出」节点，直接点它 手势=" + nodeOk);
            }
        } catch (Throwable ignored) {
        }
        if (nodeOk) {
            return;
        }
        boolean ok = tapLineOn(result, new String[]{"坚持退出", "暂不", "退出观看"},
                new String[]{"继续"}, 0.30f, 0.96f,
                "看到「再看X秒得免费时长」的续看邀请，点「坚持退出」不要加时 第" + mBonusRefused + "次");
        if (!ok) {
            ok = tapRatio(DIALOG_EXIT_X, DIALOG_EXIT_Y);
            log("没认出「坚持退出」的位置，按老坐标点 手势=" + ok);
        }
        return;
    }

    if (paused) {
        if (mGreenTaps >= 6) {
            log("「继续观看」点了很多次还没恢复，先关掉");
            closeLadder();
            return;
        }
        mGreenTaps += 1;
        mCloseSteps = 0;
        mAdEndAt = 0L;
        boolean ok = tapLineOn(result, new String[]{"继续观看", "继续解锁"},
                new String[]{"坚持"}, 0.30f, 0.96f, "识别到「继续观看」，点一次恢复播放 第" + mGreenTaps + "次");
        if (!ok) {
            ok = tapRatio(CLAIM_X, CLAIM_Y);
            log("没认出「继续观看」的位置，按屏幕中心点 手势=" + ok);
        }
        return;
    }

    if (countdown) {
        mLastCountdownAt = now;
        mSawCountdown = true;
        mGreenTaps = 0;
        mCloseSteps = 0;
        if (sec >= 2 && sec <= 120) {
            long end = now + (long) sec * 1000L;
            if (end > mAdEndAt) {
                mAdEndAt = end;
            }
        }
        if (!quiet) {
            mOcrLogAt = now;
            long left = (mAdEndAt - now) / 1000L;
            log("读到倒计时 " + sec + " 秒"
                    + (left > 0 ? "（还有约 " + left + " 秒可领取）" : "") + "，不点屏幕");
        }
        return;
    }

    boolean timeOk;
    if (mAdEndAt > 0L) {
        timeOk = now >= mAdEndAt + 1500L;
    } else {
        timeOk = el >= 45000L && sinceCountdown > 12000L;
    }

    if (claim && timeOk) {
        mGreenTaps = 0;
        boolean ok = false;
        if (mHeaderTaps < 3) {
            ok = clickClaimNodeNow();
            if (!ok) {
                ok = tapLineOnFrac(result, new String[]{"领取成功", "领取奖励"},
                        new String[]{"秒", "金币", "待领取", "提现", "我的"},
                        0.012f, 0.17f, 0.30f, "页头变成「领取成功」，立刻点它领取");
            } else {
                log("页头变成「领取成功」，直接点节点按钮领取");
            }
            if (ok) {
                mHeaderTaps += 1;
                mHeadClaimAt = now;
                return;
            }
        }
        ok = tapLineOn(result, new String[]{"领取奖励", "立即领取"},
                new String[]{"秒", "金币", "待领取", "提现", "我的", "坚持"},
                0.25f, 0.95f, "广告播完，点「领取奖励」按钮");
        if (ok) {
            mCloseSteps = 0;
            return;
        }
        if (mHeaderTaps > 0 && now - mHeadClaimAt < 10000L) {
            log("刚点过页头的「领取成功」，再等它反应");
            return;
        }
        if (hasClaimNode()) {
            mHeadClaimAt = now;
            ok = clickClaimNodeNow();
            log("还没领到，但节点树里「领取成功」按钮还在，再点它一次 手势=" + ok);
            return;
        }
        if (looksLikeLanding(s)) {
            if (now - mBackTapAt >= 6000L) {
                mBackTapAt = now;
                mBackStep += 1;
                boolean bok = (mBackStep % 2 == 1)
                        ? tapRatio(0.093f, 0.081f) : performGlobalAction(GLOBAL_ACTION_BACK);
                log("页头有「领取成功」但这是商品/落地页，不点广告按钮，只退出去 手势=" + bok);
            }
            return;
        }
        log("页头「领取成功」点了几次，还是没奖励按钮，直接关广告页");
        closeLadder();
        return;
    }

    if ((landing || looksLikeLanding(s)) && el >= 6000L && sinceCountdown > 6000L && !hasClaimNode()) {
        mGreenTaps = 0;
        mCloseSteps = mCloseSteps + 1;
        int step = ((mCloseSteps - 1) % 4) + 1;
        boolean ok;
        if (step == 1 || step == 2) {
            ok = performGlobalAction(GLOBAL_ACTION_BACK);
            log("识别到保险/商品/落地页（不是广告视频），按返回键退出去 第" + step + "步 手势=" + ok);
        } else if (step == 3) {
            ok = tapRatio(0.093f, 0.081f);
            log("落地页还在，点它左上角返回箭头 第" + step + "步 手势=" + ok);
        } else {
            ok = performGlobalAction(GLOBAL_ACTION_BACK);
            log("落地页还在，再按一次返回键 第" + step + "步 手势=" + ok);
        }
        return;
    }

    if (mAdEndAt > 0L && now < mAdEndAt + 1500L) {
        if (!quiet) {
            mOcrLogAt = now;
            long left = (mAdEndAt - now) / 1000L;
            log("还没到读到的结束时间，还剩约 " + left + " 秒，继续等（" + brief(s) + "）");
        }
        return;
    }

    if (!mSawCountdown) {
        if (looksLikeLanding(s) || hasClaimNode()) {
            if (hasClaimNode() && now - mHeadClaimAt >= 5000L) {
                mHeadClaimAt = now;
                boolean ok = clickClaimNodeNow();
                log("商品/落地页上还有「领取成功」按钮，点它 手势=" + ok);
                return;
            }
            if (now - mBackTapAt >= 6000L) {
                mBackTapAt = now;
                mBackStep += 1;
                if (mBackStep % 3 == 1) {
                    boolean ok = tapRatio(0.093f, 0.081f);
                    log("商品/落地页：点左上角返回箭头 手势=" + ok);
                } else {
                    boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
                    log("商品/落地页：按返回键退出 手势=" + ok);
                }
                return;
            }
            return;
        }
        if (mBlindStep >= 8) {
            log("一直认不出读秒，也不见领取按钮，放弃这条广告，回歌曲页");
            mCloseSteps = 0;
            closeLadder();
            return;
        }
        if (el >= 25000L && now - mLastBlindAt >= 8000L) {
            mLastBlindAt = now;
            int step = mBlindStep % 4;
            mBlindStep += 1;
            boolean ok;
            if (step == 0) {
                ok = tapRatio(0.88f, 0.078f);
                log("认不出读秒，盲点页头「领取成功」位置 第" + mBlindStep + "次 手势=" + ok);
            } else if (step == 1) {
                ok = tapRatio(CLOSE_X, CLOSE_Y);
                log("认不出读秒，盲点右上角叉 第" + mBlindStep + "次 手势=" + ok);
            } else if (step == 2) {
                ok = tapRatio(0.093f, 0.081f);
                log("认不出读秒，盲点左上返回箭头 第" + mBlindStep + "次 手势=" + ok);
            } else {
                ok = performGlobalAction(GLOBAL_ACTION_BACK);
                log("认不出读秒，按一次返回键 第" + mBlindStep + "次 手势=" + ok);
            }
            return;
        }
    }

    if (el < AD_MIN_WATCH_MS + 40000L) {
        if (!quiet) {
            mOcrLogAt = now;
            log("只看不点，等广告播完（" + brief(s) + "）");
        }
        return;
    }
    closeLadder();
}

private void scanGreenPill(android.graphics.Bitmap bmp) {
    mPillFound = false;
    if (bmp == null) {
        return;
    }
    android.graphics.Bitmap soft = bmp;
    try {
        if (bmp.getConfig() != android.graphics.Bitmap.Config.ARGB_8888) {
            soft = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            if (soft == null) {
                soft = bmp;
            }
        }
    } catch (Throwable ignored) {
        soft = bmp;
    }
    try {
        int w = soft.getWidth();
        int h = soft.getHeight();
        int top = -1;
        int bottom = -1;
        int xLeft = w;
        int xRight = -1;
        for (int y = (int) (h * 0.34f); y < (int) (h * 0.64f); y += 6) {
            int first = -1;
            int last = -1;
            int cnt = 0;
            for (int x = (int) (w * 0.12f); x < (int) (w * 0.90f); x += 6) {
                int c = soft.getPixel(x, y);
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                if (g > 135 && g - r > 30 && g - b > 30) {
                    if (first < 0) {
                        first = x;
                    }
                    last = x;
                    cnt += 1;
                }
            }
            if (cnt > 40 && (last - first) > w * 0.45f) {
                if (top < 0) {
                    top = y;
                }
                bottom = y;
                if (first < xLeft) {
                    xLeft = first;
                }
                if (last > xRight) {
                    xRight = last;
                }
            }
        }
        if (top < 0 || bottom - top < 60 || xRight - xLeft < w * 0.45f) {
            return;
        }
        mPillFound = true;
        mPillCx = (xLeft + xRight) / 2;
        mPillCy = (top + bottom) / 2;
        mPillAt = System.currentTimeMillis();
    } catch (Throwable ignored) {
    }
}

private boolean tapLineOn(com.google.mlkit.vision.text.Text result, String[] keys, String[] bans,
                          float minY, float maxY, String what) {
    return tapLineOnFrac(result, keys, bans, minY, maxY, 0.5f, what);
}

private boolean tapLineOnFrac(com.google.mlkit.vision.text.Text result, String[] keys, String[] bans,
                          float minY, float maxY, float fracX, String what) {
    int w = getResources().getDisplayMetrics().widthPixels;
    int h = getResources().getDisplayMetrics().heightPixels;
    android.graphics.Rect best = null;
    for (com.google.mlkit.vision.text.Text.TextBlock block : result.getTextBlocks()) {
        for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
            String t = line.getText();
            if (t == null) {
                continue;
            }
            t = t.replace(" ", "").replace("\n", "");
            if (t.length() == 0) {
                continue;
            }
            boolean hit = false;
            for (int i = 0; i < keys.length; i++) {
                if (t.contains(keys[i])) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            for (int i = 0; i < bans.length; i++) {
                if (t.contains(bans[i])) {
                    hit = false;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            android.graphics.Rect r = line.getBoundingBox();
            if (r == null) {
                continue;
            }
            float cy = (r.top + r.bottom) * 0.5f / h;
            if (cy < minY || cy > maxY) {
                continue;
            }
            if (best == null || r.width() * r.height() > best.width() * best.height()) {
                best = new android.graphics.Rect(r);
            }
        }
    }
    if (best == null) {
        return false;
    }
    int px = best.left + (int) (best.width() * fracX);
    int py = (best.top + best.bottom) / 2;
    boolean ok = tapRatio((float) px / w, (float) py / h);
    log(what + "（" + px + "," + py + "）手势=" + ok);
    return ok;
}

private int parseSeconds(String s) {
    if (s == null) {
        return -1;
    }
    int idx = s.indexOf("秒");
    if (idx <= 0) {
        return -1;
    }
    int start = idx;
    while (start > 0 && Character.isDigit(s.charAt(start - 1))) {
        start -= 1;
    }
    if (start == idx) {
        return -1;
    }
    try {
        return Integer.parseInt(s.substring(start, idx));
    } catch (Throwable t) {
        return -1;
    }
}

private void closeLadder() {
    mCloseSteps += 1;
    if (mCloseSteps <= 2) {
        boolean ok = tapRatio(0.905f, 0.0703f);
        log("点「领取成功」旁的叉 手势=" + ok);
    } else if (mCloseSteps <= 4) {
        boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
        log("按一次返回键退出广告 手势=" + ok);
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

    private boolean isSongPage(AccessibilityNodeInfo root) {
        return hasAnyText(root, new String[]{"作词", "作曲", "听抖音"}, 0)
                && !hasAnyText(root, new String[]{"领取成功", "后可领取"}, 0);
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
