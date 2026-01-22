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
    
    private TextView tvLog, tvLsp, tvHook, tvWidget, tvSvc, tvIpc;
    private Button btnAuto, btnV1, btnV4, btnSample, btnPause;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 🔥 控制状态
    private boolean isPaused = false;
    private boolean isFullSample = false; // false=10%, true=100%

    public boolean isModuleActive() { return false; }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log == null) return;
            
            // 🟢 状态灯逻辑 (不受暂停影响，始终更新)
            if (log.contains("STATUS_HOOK_READY")) setStatus(tvHook, "服务Hook: ✅");
            else if (log.contains("STATUS_WIDGET_READY")) setStatus(tvWidget, "组件Hook: ✅");
            else if (log.contains("STATUS_SERVICE_RUNNING")) setStatus(tvSvc, "运行: ✅");
            else if (log.contains("STATUS_IPC_CONNECTED")) {
                setStatus(tvIpc, "链路IPC: ✅");
                appendLog(">>> 🎉 物理链路已打通！ <<<");
            } 
            else {
                // 普通日志受暂停控制
                if (!isPaused) {
                    appendLog(log);
                }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }

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
        btnSample = findViewById(R.id.btn_toggle_sample);
        btnPause = findViewById(R.id.btn_toggle_pause);

        registerReceiver(receiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));

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

        findViewById(R.id.btn_activate).setOnClickListener(v -> {
            appendLog("步骤3: 发送激活连招...");
            sendStatus(13); 
            updateVendorButtonUI(-1);
            Intent i = new Intent("XSF_ACTION_SET_VENDOR");
            i.putExtra("vendor", -1); 
            sendBroadcast(i);
        });

        btnAuto.setOnClickListener(v -> {
            sendVendorCmd(-1); 
            appendLog("指令: 恢复默认 (Vendor 2)");
            updateVendorButtonUI(-1);
        });

        btnV1.setOnClickListener(v -> {
            sendVendorCmd(1);
            appendLog("指令: 强制锁定 [Vendor 1]");
            updateVendorButtonUI(1);
        });

        btnV4.setOnClickListener(v -> {
            sendVendorCmd(4);
            appendLog("指令: 强制锁定 [Vendor 4]");
            updateVendorButtonUI(4);
        });
        
        // 🔥 采样率切换逻辑
        btnSample.setOnClickListener(v -> {
            isFullSample = !isFullSample;
            double rate = isFullSample ? 1.0 : 0.1;
            
            // 发送指令给 Hook
            Intent i = new Intent("XSF_ACTION_SET_SAMPLE_RATE");
            i.putExtra("rate", rate);
            sendBroadcast(i);
            
            // 更新按钮UI
            btnSample.setText(isFullSample ? "采样:100%" : "采样:10%");
            btnSample.setBackgroundColor(isFullSample ? Color.RED : Color.parseColor("#FF9800"));
            appendLog("⚙️ 采样率已更改为: " + (isFullSample ? "100% (全量)" : "10% (精简)"));
        });

        // 🔥 暂停/恢复逻辑
        btnPause.setOnClickListener(v -> {
            isPaused = !isPaused;
            btnPause.setText(isPaused ? "恢复" : "暂停");
            btnPause.setBackgroundColor(isPaused ? Color.GREEN : Color.parseColor("#9E9E9E"));
            if (isPaused) {
                appendLog("⏸️ 日志已暂停滚动 (后台仍在记录)");
            } else {
                appendLog("▶️ 日志恢复滚动");
            }
        });

        findViewById(R.id.btn_save_log).setOnClickListener(v -> saveLogToFile());
    }

    private void sendVendorCmd(int vendorId) {
        Intent i = new Intent("XSF_ACTION_SET_VENDOR");
        i.putExtra("vendor", vendorId);
        sendBroadcast(i);
    }

    private void updateVendorButtonUI(int mode) {
        btnAuto.setBackgroundColor(mode == -1 ? Color.parseColor("#FF4081") : Color.parseColor("#673AB7"));
        btnV1.setBackgroundColor(mode == 1 ? Color.parseColor("#FF4081") : Color.parseColor("#555555"));
        btnV4.setBackgroundColor(mode == 4 ? Color.parseColor("#FF4081") : Color.parseColor("#555555"));
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
        boolean success = trySaveToDir(dir, logContent);
        if (!success) {
            File rootDir = new File(Environment.getExternalStorageDirectory(), "AmapHelper_Logs");
            success = trySaveToDir(rootDir, logContent);
        }
        if (!success) {
            Toast.makeText(this, "保存失败，请检查权限", Toast.LENGTH_LONG).show();
            appendLog("❌ 保存失败");
        }
    }

    private boolean trySaveToDir(File dir, String content) {
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            String fileName = "Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content.getBytes());
            fos.close();
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
            appendLog("✅ 日志已保存: " + file.getAbsolutePath());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // 🔥 增强版日志追加：解决自动清屏问题
    private void appendLog(String m) {
        runOnUiThread(() -> {
            if (tvLog != null) {
                tvLog.append("[" + sdf.format(new Date()) + "] " + m + "\n");
                
                // 🛡️ 缓冲区保护：如果字符数超过 50000，删掉最老的 10000 个字符
                // 这样能永远保持最新的日志，而不会因为 OOM 导致清屏
                if (tvLog.getText().length() > 50000) {
                    String fullText = tvLog.getText().toString();
                    int cutIndex = fullText.indexOf("\n", 10000); // 找到换行符，避免截断一半
                    if (cutIndex != -1) {
                        tvLog.setText(fullText.substring(cutIndex + 1));
                    }
                }

                if (scrollView != null && !isPaused) { // 暂停时不自动滚动
                    scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
                }
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
