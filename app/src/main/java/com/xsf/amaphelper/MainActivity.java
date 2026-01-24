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
    
    // UI 控件 (移除了 tvWidget)
    private TextView tvLog, tvLsp, tvHook, tvSvc, tvIpc;
    private Button btnAuto, btnV1, btnV4, btnSample, btnPause;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 状态变量
    private boolean isPaused = false;
    private boolean isHighSampling = false; 

    // 伪装方法
    public boolean isModuleActive() { return false; }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log == null) return;
            
            // 状态灯更新
            if (log.contains("STATUS_HOOK_READY")) setStatus(tvHook, "服务Hook: ✅");
            // 注意：STATUS_WIDGET_READY 已被移除，不再处理
            else if (log.contains("STATUS_SERVICE_RUNNING")) setStatus(tvSvc, "运行: ✅");
            else if (log.contains("STATUS_IPC_CONNECTED")) {
                setStatus(tvIpc, "链路IPC: ✅");
                appendLog(">>> 🎉 物理链路已打通！ <<<", true);
            } 
            else {
                // 普通日志：如果暂停了，就不显示
                if (!isPaused) {
                    appendLog("模块: " + log, false);
                }
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

        // 初始化视图
        tvLog = findViewById(R.id.tv_log);
        tvLsp = findViewById(R.id.tv_lsp_status);
        tvHook = findViewById(R.id.tv_hook_status);
        // tvWidget = findViewById(R.id.tv_widget_status); // 已删除
        tvSvc = findViewById(R.id.tv_service_status);
        tvIpc = findViewById(R.id.tv_ipc_status);
        scrollView = findViewById(R.id.scrollView);

        btnAuto = findViewById(R.id.btn_vendor_auto);
        btnV1 = findViewById(R.id.btn_vendor_1);
        btnV4 = findViewById(R.id.btn_vendor_4);
        
        btnSample = findViewById(R.id.btn_sample_rate);
        btnPause = findViewById(R.id.btn_pause_log);

        registerReceiver(receiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));

        // 采样率切换
        btnSample.setOnClickListener(v -> {
            isHighSampling = !isHighSampling;
            double rate = isHighSampling ? 1.0 : 0.1;
            
            Intent i = new Intent("XSF_ACTION_SET_SAMPLE_RATE");
            i.putExtra("rate", rate);
            sendBroadcast(i);
            
            if (isHighSampling) {
                btnSample.setText("采样: 100%");
                btnSample.setBackgroundColor(Color.parseColor("#C62828"));
                appendLog("指令: 开启全量日志 (100%)", true);
            } else {
                btnSample.setText("采样: 10%");
                btnSample.setBackgroundColor(Color.parseColor("#555555"));
                appendLog("指令: 开启低频采样 (10%)", true);
            }
        });

        // 暂停/继续
        btnPause.setOnClickListener(v -> {
            isPaused = !isPaused;
            if (isPaused) {
                btnPause.setText("▶ 继续");
                btnPause.setBackgroundColor(Color.parseColor("#4CAF50"));
                appendLog("--- 日志已暂停采集 ---", true);
            } else {
                btnPause.setText("⏸ 暂停");
                btnPause.setBackgroundColor(Color.parseColor("#FF9800"));
                appendLog("--- 日志恢复采集 ---", true);
            }
        });

        // 功能按钮
        findViewById(R.id.btn_start_service).setOnClickListener(v -> {
            tvSvc.setText("运行: ⏳"); tvSvc.setTextColor(Color.YELLOW);
            tvIpc.setText("链路: ⏳"); tvIpc.setTextColor(Color.YELLOW);
            appendLog("步骤1: 发送冷启动指令...", true);
            sendBroadcast(new Intent("XSF_ACTION_START_SERVICE"));
        });

        findViewById(R.id.btn_force_connect).setOnClickListener(v -> {
            appendLog("步骤2: 手动执行 B 计划...", true);
            sendBroadcast(new Intent("XSF_ACTION_FORCE_CONNECT"));
        });

        findViewById(R.id.btn_activate).setOnClickListener(v -> {
            appendLog("步骤3: 发送激活连招...", true);
            sendStatus(13); 
            updateVendorButtonUI(-1);
            Intent i = new Intent("XSF_ACTION_SET_VENDOR");
            i.putExtra("vendor", -1); 
            sendBroadcast(i);
        });

        btnAuto.setOnClickListener(v -> {
            sendVendorCmd(2); // 默认为 2
            appendLog("指令: 恢复默认 (Vendor 2)", true);
            updateVendorButtonUI(2);
        });

        btnV1.setOnClickListener(v -> {
            sendVendorCmd(1);
            appendLog("指令: 强制锁定 [Vendor 1]", true);
            updateVendorButtonUI(1);
        });

        btnV4.setOnClickListener(v -> {
            sendVendorCmd(4);
            appendLog("指令: 强制锁定 [Vendor 4]", true);
            updateVendorButtonUI(4);
        });

        findViewById(R.id.btn_save_log).setOnClickListener(v -> saveLogToFile());
    }

    private void sendVendorCmd(int vendorId) {
        Intent i = new Intent("XSF_ACTION_SET_VENDOR");
        i.putExtra("vendor", vendorId);
        sendBroadcast(i);
    }

    private void updateVendorButtonUI(int mode) {
        int activeColor = Color.parseColor("#FF4081");
        int normalColor = Color.parseColor("#555555");
        int autoColor = Color.parseColor("#673AB7");

        btnAuto.setBackgroundColor(mode == 2 ? activeColor : autoColor);
        btnV1.setBackgroundColor(mode == 1 ? activeColor : normalColor);
        btnV4.setBackgroundColor(mode == 4 ? activeColor : normalColor);
    }

    private void sendStatus(int s) {
        Intent i = new Intent("XSF_ACTION_SEND_STATUS");
        i.putExtra("status", s);
        sendBroadcast(i);
    }

    private void saveLogToFile() {
        String logContent = tvLog.getText().toString();
        if (logContent.isEmpty()) return;
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AmapHelper_Logs");
        trySaveToDir(dir, logContent);
    }

    private boolean trySaveToDir(File dir, String content) {
        try {
            if (!dir.exists()) dir.mkdirs();
            String fileName = "Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content.getBytes());
            fos.close();
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
            appendLog("✅ 已保存: " + file.getAbsolutePath(), true);
            return true;
        } catch (Exception e) {
            appendLog("❌ 保存失败: " + e.getMessage(), true);
            return false;
        }
    }

    // 防清屏 + 缓冲区保护
    private void appendLog(String m, boolean force) {
        if (force || !isPaused) {
            runOnUiThread(() -> {
                if (tvLog != null) {
                    if (tvLog.length() > 50000) {
                        String current = tvLog.getText().toString();
                        tvLog.setText(current.substring(10000));
                        tvLog.append("\n[系统] 缓冲区自动清理 (保留最新日志)...\n");
                    }
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
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
    }
}

