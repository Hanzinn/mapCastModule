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
    
    private TextView tvLog, tvLspStatus, tvServiceStatus;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public boolean isModuleActive() { return false; }

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String content = intent.getStringExtra("log");
            if (content == null) return;
            
            // 🟢 核心反馈：收到服务启动成功的信号
            if (content.contains("NAVI_SERVICE_RUNNING")) {
                tvServiceStatus.setText("目标服务: 已运行 ✅");
                tvServiceStatus.setTextColor(Color.GREEN);
                logLocal("收到反馈：NaviService 正在运行！");
            } else {
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
        tvServiceStatus = findViewById(R.id.tv_service_status);
        scrollView = findViewById(R.id.scrollView);

        registerReceiver(logReceiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));

        // 按钮1：启动服务
        findViewById(R.id.btn_start_service).setOnClickListener(v -> {
            logLocal("发送启动指令... (请等待右上角变绿)");
            sendBroadcast(new Intent("XSF_ACTION_START_SERVICE"));
        });

        // 按钮2：常规激活 (13/25)
        findViewById(R.id.btn_activate).setOnClickListener(v -> {
            if (!isServiceRunningCheck()) return;
            logLocal("尝试: 常规激活 (发送 13 和 25)");
            Intent i = new Intent("XSF_ACTION_SEND_STATUS");
            i.putExtra("status", 13); // 这里的逻辑在模块里处理，会同时发13和25
            sendBroadcast(i);
        });

        // 按钮3：官方巡航 (28)
        findViewById(R.id.btn_start_cruise).setOnClickListener(v -> {
            if (!isServiceRunningCheck()) return;
            logLocal("尝试: 官方巡航 (发送 28)");
            Intent i = new Intent("XSF_ACTION_SEND_STATUS");
            i.putExtra("status", 28);
            sendBroadcast(i);
        });

        // 停止
        findViewById(R.id.btn_stop_cruise).setOnClickListener(v -> {
            logLocal("尝试: 停止 (发送 29)");
            Intent i = new Intent("XSF_ACTION_SEND_STATUS");
            i.putExtra("status", 29);
            sendBroadcast(i);
        });

        findViewById(R.id.btn_save_log).setOnClickListener(v -> saveToDownload());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
        }
    }

    private boolean isServiceRunningCheck() {
        // 只是一个简单的UI提示，不强制拦截，防止误判
        if (tvServiceStatus.getText().toString().contains("未运行")) {
            Toast.makeText(this, "建议先点击步骤1启动服务", Toast.LENGTH_SHORT).show();
        }
        return true;
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
            String name = "XSF_FullTest_" + new SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(path, name);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(tvLog.getText().toString().getBytes());
            fos.close();
            Toast.makeText(this, "✅ 已存至 Download/" + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onResume() {
        super.onResume();
        boolean active = isModuleActive();
        tvLspStatus.setText(active ? "LSPosed: 已激活 ✅" : "LSPosed: 未激活 ❌");
        tvLspStatus.setTextColor(active ? Color.GREEN : Color.RED);
    }
}
