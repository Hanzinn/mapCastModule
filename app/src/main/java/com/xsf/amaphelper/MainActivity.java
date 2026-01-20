package com.xsf.amaphelper;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Window;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    
    // 三个状态文本
    private TextView tvLog, tvLspStatus, tvHookStatus, tvServiceStatus;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 自身激活检测
    public boolean isModuleActive() { return false; }

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String content = intent.getStringExtra("log");
            if (content == null) return;
            
            // 🟡 状态更新逻辑
            if (content.contains("HOOK_READY")) {
                // 只要模块加载进去了，这个就会亮 (原来那个勾)
                tvHookStatus.setText("注入: 已挂载 ✅");
                tvHookStatus.setTextColor(Color.GREEN);
            } 
            else if (content.contains("SERVICE_RUNNING")) {
                // 只有服务真正跑起来，这个才会亮
                tvServiceStatus.setText("服务: 运行中 ✅");
                tvServiceStatus.setTextColor(Color.GREEN);
                logLocal(">>> 目标服务已响应，可以发送指令了 <<<");
            }

            // 过滤掉暗号，只显示有意义的日志
            if (!content.startsWith("STATUS_")) {
                logLocal("模块: " + content);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        tvLspStatus = findViewById(R.id.tv_lsp_status);
        tvHookStatus = findViewById(R.id.tv_hook_status);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        scrollView = findViewById(R.id.scrollView);

        registerReceiver(logReceiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));

        // 按钮逻辑
        findViewById(R.id.btn_start_service).setOnClickListener(v -> {
            logLocal("正在请求冷启动服务...");
            sendBroadcast(new Intent("XSF_ACTION_START_SERVICE"));
        });

        findViewById(R.id.btn_activate).setOnClickListener(v -> {
            logLocal("发送: 常规激活 (13/25)");
            Intent i = new Intent("XSF_ACTION_SEND_STATUS");
            i.putExtra("status", 13);
            sendBroadcast(i);
        });

        findViewById(R.id.btn_start_cruise).setOnClickListener(v -> {
            logLocal("发送: 开启巡航 (28)");
            Intent i = new Intent("XSF_ACTION_SEND_STATUS");
            i.putExtra("status", 28);
            sendBroadcast(i);
        });

        findViewById(R.id.btn_stop_cruise).setOnClickListener(v -> {
            logLocal("发送: 停止巡航 (29)");
            Intent i = new Intent("XSF_ACTION_SEND_STATUS");
            i.putExtra("status", 29);
            sendBroadcast(i);
        });

        findViewById(R.id.btn_save_log).setOnClickListener(v -> saveToDownload());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
        }
    }

    private void logLocal(String msg) {
        runOnUiThread(() -> {
            tvLog.append("[" + sdf.format(new Date()) + "] " + msg + "\n");
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void saveToDownload() {
        try {
            File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            String name = "XSF_TestLog_" + new SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(path, name);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(tvLog.getText().toString().getBytes());
            fos.close();
            Toast.makeText(this, "✅ 已保存: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onResume() {
        super.onResume();
        boolean active = isModuleActive();
        tvLspStatus.setText(active ? "LSP: 已激活 ✅" : "LSP: 未激活 ❌");
        tvLspStatus.setTextColor(active ? Color.GREEN : Color.RED);
    }
}
