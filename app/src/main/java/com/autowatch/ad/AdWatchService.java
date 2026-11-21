package com.autowatch.ad;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

public class AdWatchService extends AccessibilityService {

    static final int IDLE = 0;
    static final int SCAN_ENTRY = 1;
    static final int WAIT_AD = 2;
    static final int CLAIM = 3;
    static final int CLOSE = 4;

    private static final String CHANNEL_ID = "autowatch_ad_status";
    private static final String[] EXIT_HINTS = {"领取成功", "跳过广告", "跳过", "关闭广告", "稍后再说"};
    private static final int MAX_CLOSE_ATTEMPTS = 3;
    private static final long CLOSE_RETRY_MS = 1200L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollOnce();
        }
    };

    private Prefs mPrefs;
    private int mState = IDLE;
    private int mIdleRounds = 0;
    private int mClaimStep = 0;
    private long mStateEnteredAt = 0L;
    private long mRunStartedAt = 0L;
    private boolean mStopping = false;
    private int mCloseAttempts = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mPrefs = new Prefs(getApplicationContext());
        mPrefs.ensureFirstRun();
        mRunStartedAt = System.currentTimeMillis();
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
        if (mState == IDLE) {
            runIdleRound(root);
        } else {
            dispatchState(root);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mPollRunnable);
        super.onDestroy();
    }

    private void enterState(int state) {
        mState = state;
        mStateEnteredAt = System.currentTimeMillis();
        if (state == CLAIM) {
            mClaimStep = 1;
        }
        if (state == CLOSE) {
            mCloseAttempts = 0;
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
        if (mState == IDLE) {
            runIdleRound(root);
        } else {
            dispatchState(root);
        }
    }

    private boolean isTargetForeground(AccessibilityNodeInfo root) {
        return Config.TARGET_PACKAGE.equals(String.valueOf(root.getPackageName()));
    }

    private void dispatchState(AccessibilityNodeInfo root) {
        switch (mState) {
            case SCAN_ENTRY:
                runScanEntry(root);
                break;
            case WAIT_AD:
                runWaitAd(root);
                break;
            case CLAIM:
                runClaim(root);
                break;
            case CLOSE:
                runClose(root);
                break;
            default:
                runIdleRound(root);
                break;
        }
    }

    private void runIdleRound(AccessibilityNodeInfo root) {
        if (findClickable(root, Config.ENTRY_KEYWORDS) != null) {
            mIdleRounds = 0;
            enterState(SCAN_ENTRY);
            scheduleNext(0L);
            return;
        }
        mIdleRounds += 1;
        if (mIdleRounds >= Config.MAX_IDLE_ROUNDS) {
            mIdleRounds = 0;
        }
        scheduleNext(Config.POLL_INTERVAL_MS);
    }

    private void runScanEntry(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo entry = findClickable(root, Config.ENTRY_KEYWORDS);
        if (entry != null && clickNode(entry)) {
            enterState(WAIT_AD);
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        if (elapsedInState() >= Config.ENTRY_WAIT_MS) {
            enterState(IDLE);
        }
        scheduleNext(Config.POLL_INTERVAL_MS);
    }

    private void runWaitAd(AccessibilityNodeInfo root) {
        if (findClickable(root, Config.CLAIM_SUCCESS_KEYWORDS) != null) {
            enterState(CLAIM);
            scheduleNext(0L);
            return;
        }
        if (elapsedInState() >= Config.AD_WAIT_MS) {
            enterState(IDLE);
        }
        scheduleNext(Config.POLL_INTERVAL_MS);
    }

    private void runClaim(AccessibilityNodeInfo root) {
        if (mClaimStep == 1) {
            AccessibilityNodeInfo success = findClickable(root, Config.CLAIM_SUCCESS_KEYWORDS);
            if (success != null && clickNode(success)) {
                mClaimStep = 2;
                mStateEnteredAt = System.currentTimeMillis();
            }
        } else {
            AccessibilityNodeInfo reward = findClickable(root, Config.CLAIM_REWARD_KEYWORDS);
            if (reward != null && clickNode(reward)) {
                enterState(CLOSE);
                scheduleNext(Config.POLL_INTERVAL_MS);
                return;
            }
        }
        long timeout = mClaimStep == 1 ? Config.CLAIM1_WAIT_MS : Config.CLAIM2_WAIT_MS;
        if (elapsedInState() >= timeout) {
            enterState(CLOSE);
        }
        scheduleNext(Config.POLL_INTERVAL_MS);
    }

    private void runClose(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo close = findClickable(root, Config.CLOSE_KEYWORDS);
        if (close == null) {
            close = findClickable(root, EXIT_HINTS);
        }
        if (close != null && clickNode(close)) {
            mCloseAttempts += 1;
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK);
            mCloseAttempts += 1;
        }
        if (mCloseAttempts >= MAX_CLOSE_ATTEMPTS || elapsedInState() >= CLOSE_RETRY_MS * MAX_CLOSE_ATTEMPTS) {
            finishClaim();
            enterState(IDLE);
            scheduleNext(Config.POLL_INTERVAL_MS);
            return;
        }
        scheduleNext(CLOSE_RETRY_MS);
    }

    private void finishClaim() {
        mPrefs.incrementCount();
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
        if (total >= budget || (mState != IDLE && elapsedInState() > Config.ENTRY_WAIT_MS)) {
            enterState(IDLE);
            mIdleRounds = 0;
            scheduleNext(Config.POLL_INTERVAL_MS);
        }
    }

    private long elapsedInState() {
        return System.currentTimeMillis() - mStateEnteredAt;
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo root, String[] keywords) {
        return findClickable(root, keywords, 0);
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo root, String[] keywords, int depth) {
        if (root == null || keywords == null || depth > 12) {
            return null;
        }
        if (isMatching(root, keywords) && root.isClickable()) {
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
        return node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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
