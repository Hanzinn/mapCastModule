package com.xsf.amaphelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
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
    
    // 🟢 3个指示灯变量
    private TextView tvLog, tvLsp, tvHook, tvIpc;
    
    private Button btnV0, btnV4, btnV5, btnV10, btnForceConnect, btnClose, btnSaveLog;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public boolean isModuleActive() { return false; }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log == null) return;
            
            // 🟢 精简后的点灯逻辑
            // 1. 只要服务跑起来了，就说明Hook成功了 -> 点亮中间的灯
            if (log.contains("STATUS_SERVICE_RUNNING")) {
                setStatus(tvHook, "服务Hook: ✅");
            }
            // 2. 只要抓到对象了，说明链路通了 -> 点亮右边的灯
            else if (log.contains("STATUS_IPC_CONNECTED")) {
                setStatus(tvIpc, "IPC链路: ✅");
            }
            
            appendLog(log);
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
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        scrollView = findViewById(R.id.scrollView);
        
        tvLsp = findViewById(R.id.tv_lsp_status);
        tvHook = findViewById(R.id.tv_hook_status);
        tvIpc = findViewById(R.id.tv_ipc_status); // 删除了 tvSvc

        btnV0 = findViewById(R.id.btn_v0);
        btnV4 = findViewById(R.id.btn_v4);
        btnV5 = findViewById(R.id.btn_v5);
        btnV10 = findViewById(R.id.btn_v10);
        
        btnForceConnect = findViewById(R.id.btn_force_connect);
        btnSaveLog = findViewById(R.id.btn_save_log);
        btnClose = findViewById(R.id.btn_close);

        btnV0.setOnClickListener(v -> sendVendor(0));
        btnV4.setOnClickListener(v -> sendVendor(4));
        btnV5.setOnClickListener(v -> sendVendor(5));
        btnV10.setOnClickListener(v -> sendVendor(10));

        btnForceConnect.setOnClickListener(v -> {
            sendBroadcast(new Intent("XSF_ACTION_FORCE_CONNECT"));
            appendLog(">>> 发送强制连接指令");
        });

        btnSaveLog.setOnClickListener(v -> saveLogToFile());
        
        btnClose.setOnClickListener(v -> {
            appendLog(">>> 正在停止投屏并退出...");
            sendBroadcast(new Intent("XSF_ACTION_STOP"));
            new android.os.Handler().postDelayed(() -> {
                finish();
                System.exit(0);
            }, 500);
        });

        IntentFilter filter = new IntentFilter("com.xsf.amaphelper.LOG_UPDATE");
        registerReceiver(receiver, filter);
        
        sendBroadcast(new Intent("XSF_ACTION_SEND_STATUS"));
    }

    private void sendVendor(int v) {
        Intent i = new Intent("XSF_ACTION_SET_VENDOR");
        i.putExtra("vendor", v);
        sendBroadcast(i);
        appendLog(">>> 切换 Vendor: " + v);
    }

    private void saveLogToFile() {
        String logContent = tvLog.getText().toString();
        File dir = new File(getExternalFilesDir(null), "logs");
        try {
            if (!dir.exists()) dir.mkdirs();
            String fileName = "Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(logContent.getBytes());
            fos.close();
            Toast.makeText(this, "已保存到: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            appendLog("✅ 日志已保存", true);
        } catch (Exception e) {
            appendLog("❌ 保存失败: " + e.getMessage(), true);
        }
    }

    private void appendLog(String m) { appendLog(m, false); }
    
    private void appendLog(String m, boolean force) {
        runOnUiThread(() -> {
            if (tvLog != null) {
                if (tvLog.length() > 50000) tvLog.setText("");
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
        sendBroadcast(new Intent("XSF_ACTION_SEND_STATUS"));
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(receiver); } catch (Exception e) {}
    }
}