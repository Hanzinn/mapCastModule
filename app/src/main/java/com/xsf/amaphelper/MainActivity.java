package com.xsf.amaphelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    
    private TextView tvLog, tvStatusLsp, tvStatusHook;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private boolean isHooked = false;

    // Xposed 会 Hook 这个方法返回 true
    public boolean isModuleActive() { return false; }

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log != null) {
                appendLog(log);
                if (log.contains("Hook成功") || log.contains("劫持")) {
                    isHooked = true;
                    updateStatus();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        tvStatusLsp = findViewById(R.id.tv_status_lsp);
        tvStatusHook = findViewById(R.id.tv_status_hook);
        scrollView = findViewById(R.id.scroll_log);

        // 按钮事件
        findViewById(R.id.btn_start).setOnClickListener(v -> {
            appendLog(">>> 发送指令: 开启投屏");
            sendBroadcast(new Intent("XSF_ACTION_START_CAST"));
        });

        findViewById(R.id.btn_stop).setOnClickListener(v -> {
            appendLog(">>> 发送指令: 关闭投屏");
            sendBroadcast(new Intent("XSF_ACTION_STOP_CAST"));
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> saveLog());

        findViewById(R.id.btn_kill).setOnClickListener(v -> {
            sendBroadcast(new Intent("XSF_ACTION_STOP_CAST"));
            Toast.makeText(this, "正在清理退出...", Toast.LENGTH_SHORT).show();
            new Handler().postDelayed(() -> {
                finishAffinity();
                System.exit(0);
            }, 500);
        });

        // 注册日志广播
        IntentFilter filter = new IntentFilter("com.xsf.amaphelper.LOG_UPDATE");
        registerReceiver(logReceiver, filter);
        
        // 初始日志
        appendLog("App启动完成，等待操作...");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean active = isModuleActive();
        tvStatusLsp.setText(active ? "LSP: 已激活" : "LSP: 未激活");
        tvStatusLsp.setTextColor(active ? Color.GREEN : Color.RED);
        
        tvStatusHook.setText(isHooked ? "Hook: 运行中" : "Hook: 等待服务");
        tvStatusHook.setTextColor(isHooked ? Color.GREEN : Color.YELLOW);
    }

    private void appendLog(String msg) {
        runOnUiThread(() -> {
            if (tvLog != null) {
                if (tvLog.length() > 20000) tvLog.setText(""); // 防止卡顿
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
            Toast.makeText(this, "保存成功: " + file.getName(), Toast.LENGTH_LONG).show();
            appendLog("日志已保存到: " + file.getAbsolutePath());
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