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
    
    private TextView tvLog, tvLsp, tvHook, tvSvc, tvIpc;
    private Button btnV0, btnV4, btnV5, btnV10, btnStatus1, btnStatus16;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private boolean isPaused = false;

    public boolean isModuleActive() { return false; }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log == null) return;
            
            if (log.contains("STATUS_HOOK_READY")) setStatus(tvHook, "服务Hook: ✅");
            else if (log.contains("STATUS_SERVICE_RUNNING")) setStatus(tvSvc, "运行: ✅");
            else if (log.contains("STATUS_IPC_CONNECTED")) setStatus(tvIpc, "链路: ✅");
            else {
                if (!isPaused) appendLog("模块: " + log, false);
            }
        }
    };

    private void setStatus(TextView tv, String text) {
        if (tv != null) {
            tv.setText(text);
            tv.setTextColor(Color.GREEN);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }

        tvLog = findViewById(R.id.tv_log);
        tvLsp = findViewById(R.id.tv_lsp_status);
        tvHook = findViewById(R.id.tv_hook_status);
        tvSvc = findViewById(R.id.tv_service_status);
        tvIpc = findViewById(R.id.tv_ipc_status);
        scrollView = findViewById(R.id.scrollView);

        btnV0 = findViewById(R.id.btn_vendor_0);
        btnV4 = findViewById(R.id.btn_vendor_4);
        btnV5 = findViewById(R.id.btn_vendor_5);
        btnV10 = findViewById(R.id.btn_vendor_10);
        
        btnStatus1 = findViewById(R.id.btn_status_1);
        btnStatus16 = findViewById(R.id.btn_status_16);

        registerReceiver(receiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));

        // 核心按钮：激活测试
        findViewById(R.id.btn_activate_test).setOnClickListener(v -> {
            appendLog("🔥 [激活测试] 正在建立连接...", true);
            sendBroadcast(new Intent("XSF_ACTION_FORCE_CONNECT"));
        });

        // Vendor 切换
        btnV0.setOnClickListener(v -> setVendor(0));
        btnV4.setOnClickListener(v -> setVendor(4));
        btnV5.setOnClickListener(v -> setVendor(5));
        btnV10.setOnClickListener(v -> setVendor(10));

        // Status 切换
        btnStatus1.setOnClickListener(v -> setStatusVal(1));
        btnStatus16.setOnClickListener(v -> setStatusVal(16));

        findViewById(R.id.btn_save_log).setOnClickListener(v -> saveLogToFile());
        
        // 初始化 UI 状态
        updateVendorUI(0);
        updateStatusUI(1);
    }

    private void setVendor(int v) {
        Intent i = new Intent("XSF_ACTION_SET_VENDOR");
        i.putExtra("vendor", v);
        sendBroadcast(i);
        updateVendorUI(v);
        appendLog("指令: 切换 Vendor -> V" + v, true);
    }

    private void setStatusVal(int s) {
        Intent i = new Intent("XSF_ACTION_SET_STATUS");
        i.putExtra("status", s);
        sendBroadcast(i);
        updateStatusUI(s);
        appendLog("指令: 切换 State -> S" + s, true);
    }

    private void updateVendorUI(int v) {
        int active = Color.parseColor("#FF4081");
        int normal = Color.parseColor("#555555");
        btnV0.setBackgroundColor(v == 0 ? active : normal);
        btnV4.setBackgroundColor(v == 4 ? active : normal);
        btnV5.setBackgroundColor(v == 5 ? active : normal);
        btnV10.setBackgroundColor(v == 10 ? active : normal);
    }

    private void updateStatusUI(int s) {
        int active = Color.parseColor("#4CAF50");
        int normal = Color.parseColor("#555555");
        btnStatus1.setBackgroundColor(s == 1 ? active : normal);
        btnStatus16.setBackgroundColor(s == 16 ? active : normal);
    }

    private void saveLogToFile() {
        String logContent = tvLog.getText().toString();
        if (logContent.isEmpty()) return;
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AmapHelper_Logs");
        try {
            if (!dir.exists()) dir.mkdirs();
            String fileName = "Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(logContent.getBytes());
            fos.close();
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
            appendLog("✅ 已保存: " + file.getAbsolutePath(), true);
        } catch (Exception e) {
            appendLog("❌ 保存失败: " + e.getMessage(), true);
        }
    }

    private void appendLog(String m, boolean force) {
        if (force || !isPaused) {
            runOnUiThread(() -> {
                if (tvLog != null) {
                    if (tvLog.length() > 50000) tvLog.setText("");
                    tvLog.append("[" + sdf.format(new Date()) + "] " + m + "\n");
                    if (scrollView != null) scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean active = isModuleActive();
        tvLsp.setText(active ? "LSP: ✅" : "LSP: ❌");
        tvLsp.setTextColor(active ? Color.GREEN : Color.RED);
        sendBroadcast(new Intent("XSF_ACTION_SEND_STATUS"));
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
    }
}

