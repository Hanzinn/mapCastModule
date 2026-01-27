package com.xsf.amaphelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
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
    
    private TextView tvLog, tvLsp, tvHook, tvIpc;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    
    // 状态标志位
    private boolean isHookActive = false;
    private boolean isIpcConnected = false;

    // Xposed 注入后会替换此方法
    public boolean isModuleActive() { return false; }

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log != null) {
                appendLog(log);
                
                // 根据日志内容自动更新状态灯
                if (log.contains("连接请求")) {
                    isHookActive = true;
                    updateStatus();
                }
                if (log.contains("虚拟Binder已创建") || log.contains("窗口已创建")) {
                    isIpcConnected = true;
                    updateStatus();
                }
                if (log.contains("投屏已关闭")) {
                    isIpcConnected = false;
                    updateStatus();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化控件
        tvLog = findViewById(R.id.tv_log);
        tvLsp = findViewById(R.id.tv_status_lsp);
        tvHook = findViewById(R.id.tv_status_hook);
        tvIpc = findViewById(R.id.tv_status_ipc);
        scrollView = findViewById(R.id.scroll_log);

        // 按钮监听
        findViewById(R.id.btn_start).setOnClickListener(v -> {
            appendLog(">>> 指令: 开启投屏 (创建悬浮窗 + 劫持服务)");
            sendBroadcast(new Intent("XSF_ACTION_START_CAST"));
        });

        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            appendLog(">>> 指令: 关闭投屏");
            sendBroadcast(new Intent("XSF_ACTION_STOP_CAST"));
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> saveLog());

        findViewById(R.id.btn_kill).setOnClickListener(v -> {
            sendBroadcast(new Intent("XSF_ACTION_STOP_CAST"));
            Toast.makeText(this, "正在清理...", Toast.LENGTH_SHORT).show();
            // 延时杀进程
            new Handler().postDelayed(() -> {
                finishAffinity();
                System.exit(0);
            }, 800);
        });

        // 注册广播
        IntentFilter filter = new IntentFilter("com.xsf.amaphelper.LOG_UPDATE");
        registerReceiver(logReceiver, filter);
        
        appendLog("V136 初始化完成");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean lsp = isModuleActive();
        
        // 1. LSP 灯
        tvLsp.setText(lsp ? "LSP: 已激活" : "LSP: 未激活");
        tvLsp.setTextColor(lsp ? Color.GREEN : Color.RED);
        
        // 2. Hook 灯
        tvHook.setText(isHookActive ? "服务Hook: 运行中" : "服务Hook: 等待");
        tvHook.setTextColor(isHookActive ? Color.GREEN : Color.YELLOW);
        
        // 3. IPC 灯
        tvIpc.setText(isIpcConnected ? "链路IPC: 已连接" : "链路IPC: 断开");
        tvIpc.setTextColor(isIpcConnected ? Color.GREEN : Color.RED);
    }

    private void appendLog(String msg) {
        runOnUiThread(() -> {
            if (tvLog != null) {
                if (tvLog.length() > 20000) tvLog.setText("");
                tvLog.append("[" + sdf.format(new Date()) + "] " + msg + "\n");
                if (scrollView != null) scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }

    private void saveLog() {
        try {
            File dir = new File(getExternalFilesDir(null), "logs");
            if (!dir.exists()) dir.mkdirs();
            String name = "Log_" + System.currentTimeMillis() + ".txt";
            File file = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(tvLog.getText().toString().getBytes());
            fos.close();
            Toast.makeText(this, "日志已保存", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            appendLog("保存失败: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(logReceiver); } catch (Exception e) {}
    }
}