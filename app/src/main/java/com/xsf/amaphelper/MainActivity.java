package com.xsf.amaphelper;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Window;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    
    // UI 控件
    private TextView tvLog, tvLsp, tvHook, tvWidget, tvSvc, tvIpc;
    private Button btnAuto, btnV1, btnV4;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 伪装方法，会被 Hook 覆盖
    public boolean isModuleActive() { return false; }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log == null) return;
            
            // 🟢 状态灯逻辑
            if (log.contains("STATUS_HOOK_READY")) {
                setStatus(tvHook, "服务Hook: ✅");
            } 
            else if (log.contains("STATUS_WIDGET_READY")) {
                setStatus(tvWidget, "组件Hook: ✅");
            }
            else if (log.contains("STATUS_SERVICE_RUNNING")) {
                setStatus(tvSvc, "运行: ✅");
            } 
            else if (log.contains("STATUS_IPC_CONNECTED")) {
                setStatus(tvIpc, "链路IPC: ✅");
                appendLog(">>> 🎉 物理链路已打通！ <<<");
            } 
            else {
                appendLog("模块: " + log);
            }
        }
    };

    private void setStatus(TextView tv, String text) {
        tv.setText(text);
        tv.setTextColor(Color.GREEN);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        // 🌟 1. 启动时主动申请存储权限 (修复保存失败的关键)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }

        // 初始化视图
        tvLog = findViewById(R.id.tv_log);
        tvLsp = findViewById(R.id.tv_lsp_status);
        tvHook = findViewById(R.id.tv_hook_status);
        tvWidget = findViewById(R.id.tv_widget_status);
        tvSvc = findViewById(R.id.tv_service_status);
        tvIpc = findViewById(R.id.tv_ipc_status);
        scrollView = findViewById(R.id.scrollView);

        btnAuto = findViewById(R.id.btn_vendor_auto);
        btnV1 = findViewById(R.id.btn_vendor_1);
        btnV4 = findViewById(R.id.btn_vendor_4);

        registerReceiver(receiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));

        // 按钮事件
        findViewById(R.id.btn_start_service).setOnClickListener(v -> {
            tvSvc.setText("运行: ⏳"); tvSvc.setTextColor(Color.YELLOW);
            tvIpc.setText("链路: ⏳"); tvIpc.setTextColor(Color.YELLOW);
            appendLog("步骤1: 发送冷启动指令...");
            sendBroadcast(new Intent("XSF_ACTION_START_SERVICE"));
        });

        findViewById(R.id.btn_force_connect).setOnClickListener(v -> {
            appendLog("步骤2: 手动执行 B 计划...");
            sendBroadcast(new Intent("XSF_ACTION_FORCE_CONNECT"));
        });

        // 🌟 适配 V61 逻辑：激活时重置为默认
        findViewById(R.id.btn_activate).setOnClickListener(v -> {
            appendLog("步骤3: 发送激活连招...");
            sendStatus(13); 
            // 切回默认/Vendor2 UI状态
            updateVendorButtonUI(-1);
            // 发送重置指令 (-1 在 Hook 里会被转为 2)
            Intent i = new Intent("XSF_ACTION_SET_VENDOR");
            i.putExtra("vendor", -1); 
            sendBroadcast(i);
        });

        // 恢复默认 (Vendor 2)
        btnAuto.setOnClickListener(v -> {
            sendVendorCmd(-1); 
            appendLog("指令: 恢复默认 (Vendor 2)");
            updateVendorButtonUI(-1);
        });

        // 锁定 V1
        btnV1.setOnClickListener(v -> {
            sendVendorCmd(1);
            appendLog("指令: 强制锁定 [Vendor 1]");
            updateVendorButtonUI(1);
        });

        // 锁定 V4
        btnV4.setOnClickListener(v -> {
            sendVendorCmd(4);
            appendLog("指令: 强制锁定 [Vendor 4]");
            updateVendorButtonUI(4);
        });

        // 🌟 修复后的保存按钮
        findViewById(R.id.btn_save_log).setOnClickListener(v -> saveLogToFile());
    }

    private void sendVendorCmd(int vendorId) {
        Intent i = new Intent("XSF_ACTION_SET_VENDOR");
        i.putExtra("vendor", vendorId);
        sendBroadcast(i);
    }

    private void updateVendorButtonUI(int mode) {
        int activeColor = Color.parseColor("#FF4081"); // 激活色 (粉红)
        int normalColor = Color.parseColor("#555555"); // 普通色 (灰)
        int autoColor = Color.parseColor("#673AB7");   // 自动色 (紫)

        btnAuto.setBackgroundColor(mode == -1 ? activeColor : autoColor);
        btnV1.setBackgroundColor(mode == 1 ? activeColor : normalColor);
        btnV4.setBackgroundColor(mode == 4 ? activeColor : normalColor);
    }

    private void sendStatus(int s) {
        Intent i = new Intent("XSF_ACTION_SEND_STATUS");
        i.putExtra("status", s);
        sendBroadcast(i);
    }

    // 🌟 增强版日志保存逻辑
    private void saveLogToFile() {
        String logContent = tvLog.getText().toString();
        if (logContent.isEmpty()) {
            Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show();
            return;
        }

        // 方案A: 存到 Download/AmapHelper_Logs 目录
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AmapHelper_Logs");
        boolean success = trySaveToDir(dir, logContent);

        // 方案B: 如果方案A失败，尝试存到 SD 卡根目录
        if (!success) {
            File rootDir = new File(Environment.getExternalStorageDirectory(), "AmapHelper_Logs");
            success = trySaveToDir(rootDir, logContent);
        }

        if (!success) {
            appendLog("❌ 保存失败：请检查存储权限");
            Toast.makeText(this, "保存失败，请看屏幕日志", Toast.LENGTH_LONG).show();
        }
    }

    private boolean trySaveToDir(File dir, String content) {
        try {
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    return false; // 创建目录失败
                }
            }
            String fileName = "Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(dir, fileName);
            
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content.getBytes());
            fos.close();
            
            Toast.makeText(this, "保存成功！", Toast.LENGTH_SHORT).show();
            appendLog("✅ 日志已保存: " + file.getAbsolutePath());
            return true;
        } catch (Exception e) {
            appendLog("⚠️ 尝试写入 " + dir.getName() + " 失败: " + e.getMessage());
            return false;
        }
    }

    private void appendLog(String m) {
        runOnUiThread(() -> {
            if (tvLog != null) {
                tvLog.append("[" + sdf.format(new Date()) + "] " + m + "\n");
                if (scrollView != null) scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean active = isModuleActive();
        tvLsp.setText(active ? "LSP: ✅" : "LSP: ❌");
        tvLsp.setTextColor(active ? Color.GREEN : Color.RED);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
    }
}
