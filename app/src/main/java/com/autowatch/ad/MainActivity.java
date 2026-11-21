package com.autowatch.ad;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private static final String ACCESSIBILITY_SERVICE_CLASS = "com.autowatch.ad.AdWatchService";
    private static final long ONE_DAY_MS = Config.THIRTY_DAYS_MS / 30L;

    private Prefs prefs;
    private TextView statusText;
    private TextView remainingDaysText;
    private TextView dumpText;
    private boolean targetLaunched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        prefs.ensureFirstRun();

        statusText = findViewById(R.id.tvStatus);
        remainingDaysText = findViewById(R.id.tvRemainingDays);
        dumpText = findViewById(R.id.tvDump);
        Button openAccessibilityButton = findViewById(R.id.btnOpenAccessibility);
        Button startButton = findViewById(R.id.btnStart);
        Button dumpButton = findViewById(R.id.btnDump);

        refreshAccessibilityStatus();
        refreshDiagnostics();

        openAccessibilityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchTargetAppOnce();
            }
        });

        dumpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dumpText != null) {
                    dumpText.setText(AdWatchService.getLastDump());
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessibilityStatus();
        refreshDiagnostics();
    }

    private boolean isServiceRegistered() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) {
            return false;
        }
        List<AccessibilityServiceInfo> list = am.getInstalledAccessibilityServiceList();
        if (list == null) {
            return false;
        }
        for (AccessibilityServiceInfo info : list) {
            ResolveInfo ri = info.getResolveInfo();
            if (ri == null || ri.serviceInfo == null) {
                continue;
            }
            String pkg = ri.serviceInfo.packageName;
            String name = ri.serviceInfo.name;
            if (getPackageName().equals(pkg) && name != null
                    && (ACCESSIBILITY_SERVICE_CLASS.equals(name) || name.endsWith("AdWatchService"))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        for (String item : enabled.split(":")) {
            String service = item.trim();
            if (TextUtils.isEmpty(service)) {
                continue;
            }
            int slash = service.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            String pkg = service.substring(0, slash);
            String cls = service.substring(slash + 1);
            if (!getPackageName().equals(pkg)) {
                continue;
            }
            if (cls.startsWith(".")) {
                cls = pkg + cls;
            }
            if (ACCESSIBILITY_SERVICE_CLASS.equals(cls)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTargetInstalled() {
        try {
            getPackageManager().getPackageInfo(Config.TARGET_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshAccessibilityStatus() {
        if (statusText == null) {
            return;
        }
        statusText.setText(isAccessibilityServiceEnabled()
                ? "无障碍服务已开启"
                : "无障碍服务未开启，请先开启");
    }

    private void refreshDiagnostics() {
        if (remainingDaysText == null) {
            return;
        }
        String registered = isServiceRegistered() ? "已注册" : "未注册";
        String enabled = isAccessibilityServiceEnabled() ? "已开启" : "未开启";
        String target = isTargetInstalled() ? "已安装" : "未安装";
        remainingDaysText.setText("剩余天数：" + remainingDays() + " 天\nv4 - 系统识别：" + registered + " - 服务：" + enabled + " - 汽水音乐：" + target);
    }

    private void openAccessibilitySettings() {
        boolean registered = isServiceRegistered();
        if (registered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Intent detail = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                detail.putExtra(Intent.EXTRA_COMPONENT_NAME,
                        new ComponentName(this, AdWatchService.class).flattenToString());
                detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(detail);
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, registered ? "打开无障碍设置" : "系统未注册本服务，已打开无障碍总列表", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private long remainingDays() {
        long firstRunMs = prefs.getFirstRunMs();
        if (firstRunMs <= 0L) {
            prefs.ensureFirstRun();
            firstRunMs = prefs.getFirstRunMs();
        }
        long remainMs = firstRunMs + Config.THIRTY_DAYS_MS - System.currentTimeMillis();
        if (remainMs <= 0L) {
            return 0L;
        }
        return (remainMs + ONE_DAY_MS - 1L) / ONE_DAY_MS;
    }

    private void launchTargetAppOnce() {
        if (targetLaunched) {
            return;
        }
        PackageManager pm = getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(Config.TARGET_PACKAGE);
        if (launchIntent == null) {
            Toast.makeText(this, "未找到汽水音乐", Toast.LENGTH_SHORT).show();
            return;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launchIntent);
        targetLaunched = true;
    }
}
