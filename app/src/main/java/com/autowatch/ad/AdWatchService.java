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
    private static final int MAX_CLOSE_STEPS = 5;

    private static AdWatchService sInstance;
    private static String sLastDump = "尚未采集";
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
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        step(root);
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
        if (mState == WAIT_ENTRY) {
            runWaitEntry(root);
        } else if (mState == CLOSE_AD) {
            runCloseAd(root);
        } else {
            runIdle(root);
        }
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
        AccessibilityNodeInfo entry = findClickable(root, Config.ENTRY_KEYWORDS);
        if (entry != null && clickNode(entry)) {
            log("点击入口按钮 " + brief(entry));
            enterState(WAIT_ENTRY);
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
            if (mEntryAttempts % 2 == 1) {
                tapRatio(SHEET_X, SHEET_Y);
                log("入口没反应，先关掉畅听卡片再试");
                mPauseUntil = System.currentTimeMillis() + 1200L;
            } else {
                log("等了 10 秒没进广告页，重新尝试");
                mPauseUntil = System.currentTimeMillis() + 2000L;
            }
            enterState(IDLE);
        }
        scheduleNext(Config.POLL_INTERVAL_MS);
    }

    private void runCloseAd(AccessibilityNodeInfo root) {
        if (!isAdPage(root) && !dialogVisible(root)) {
            log("已回到歌曲页，本轮结束");
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
        if (elapsedInState() < CLOSE_RETRY_MS) {
            scheduleNext(200L);
            return;
        }
        mStateEnteredAt = System.currentTimeMillis();
        mCloseSteps += 1;
        if (mCloseSteps > MAX_CLOSE_STEPS) {
            log("广告页关不掉，改用重启汽水音乐");
            forceResetTarget();
            return;
        }
        AccessibilityNodeInfo close = findClickable(root, Config.CLOSE_KEYWORDS);
        if (close == null) {
            close = findClickable(root, EXIT_HINTS);
        }
        if (close != null && clickNode(close)) {
            log("点击关闭节点 " + brief(close));
        } else if (mCloseSteps <= 4) {
            if (mCloseSteps <= 2) {
                boolean ok = tapRatio(CLOSE_X, CLOSE_Y);
                log("点右上角关闭 第" + mCloseSteps + "次 手势=" + ok);
            } else {
                boolean ok = tapRatio(CLAIM_X, CLAIM_Y);
                log("点中间大按钮（继续观看/领取奖励）第" + mCloseSteps + "次 手势=" + ok);
            }
        } else {
            log("最后再按一次返回键");
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
        scheduleNext(CLOSE_RETRY_MS);
    }

    private void forceResetTarget() {
        performGlobalAction(GLOBAL_ACTION_HOME);
        mAdSeen = false;
        mCloseSteps = 0;
        mPauseUntil = System.currentTimeMillis() + 8000L;
        enterState(IDLE);
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
                        launchTarget();
                    }
                }, 1200L);
            }
        }, 1500L);
        scheduleNext(Config.POLL_INTERVAL_MS);
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
        return hasAnyText(root, AD_PAGE_HINTS, 0);
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
        if (isMatching(root, keywords)) {
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 150L);
        GestureDescription description = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        return dispatchGesture(description, null, null);
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
