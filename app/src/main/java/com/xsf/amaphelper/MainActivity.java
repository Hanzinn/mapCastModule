package com.xsf.amaphelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
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
    private TextView tvLog, tvLsp, tvHook, tvWidget, tvSvc, tvIpc; // 新增 tvWidget
    private Button btnAuto, btnV1, btnV4;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public boolean isModuleActive() { return false; }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log == null) return;
            
            // 🟢 状态灯逻辑升级
            if (log.contains("STATUS_HOOK_READY")) {
                setStatus(tvHook, "服务Hook: ✅");
            } 
            // 🔥 新增组件状态监听
            else if (log.contains("STATUS_WIDGET_READY")) {
                setStatus(tvWidget, "组件Hook: ✅");
                // 收到组件存活信号，不用弹日志，避免刷屏，灯亮即可
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

        // 初始化视图
        tvLog = findViewById(R.id.tv_log);
        tvLsp = findViewById(R.id.tv_lsp_status);
        tvHook = findViewById(R.id.tv_hook_status);
        tvWidget = findViewById(R.id.tv_widget_status); // 新增
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

        findViewById(R.id.btn_activate).setOnClickListener(v -> {
            appendLog("步骤3: 发送激活连招...");
            sendStatus(13); 
            updateVendorButtonUI(-1);
        });

        btnAuto.setOnClickListener(v -> {
            sendVendorCmd(-1);
            appendLog("指令: 切换为 [自动轮询 1/2/4]");
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

        btnAuto.setBackgroundColor(mode == -1 ? activeColor : autoColor);
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
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dir = new File(downloadDir, "AmapHelper_Logs");
            if (!dir.exists()) dir.mkdirs();
            String fileName = "Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(logContent.getBytes());
            fos.close();
            Toast.makeText(this, "日志已保存", Toast.LENGTH_SHORT).show();
            appendLog("✅ 日志已保存: " + file.getAbsolutePath());
        } catch (Exception e) {
            appendLog("❌ 保存失败: " + e.getMessage());
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
