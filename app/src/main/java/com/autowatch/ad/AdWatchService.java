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
                || low.contains("adwebview") || low.contains("adcontainer");
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
