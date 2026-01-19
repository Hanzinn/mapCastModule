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
    
    private boolean isSniffing = false;
    private TextView tvLogSniff, tvLogSys, tvLspStatus, tvHookStatus;
    private ScrollView scrollSniff, scrollSys;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public boolean isModuleActive() { return false; }

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String content = intent.getStringExtra("log");
            final int type = intent.getIntExtra("type", 0);
            if (content == null) return;

            runOnUiThread(() -> {
                if (content.contains("模块加载成功")) {
                    tvHookStatus.setText("Hook服务: 已连接 ✅");
                    tvHookStatus.setTextColor(Color.GREEN);
                }
                String time = sdf.format(new Date());
                String logLine = "[" + time + "] " + content + "\n\n";
                if (type == 1) {
                    tvLogSniff.append(logLine);
                    scrollSniff.post(() -> scrollSniff.fullScroll(ScrollView.FOCUS_DOWN));
                } else {
                    tvLogSys.append(logLine);
                    scrollSys.post(() -> scrollSys.fullScroll(ScrollView.FOCUS_DOWN));
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        
        checkPermission();

        tvLspStatus = findViewById(R.id.tv_lsp_status);
        tvHookStatus = findViewById(R.id.tv_hook_status);
        tvLogSniff = findViewById(R.id.tv_log_sniff);
        tvLogSys = findViewById(R.id.tv_log_sys);
        scrollSniff = findViewById(R.id.scroll_sniff);
        scrollSys = findViewById(R.id.scroll_sys);
        
        tvLogSniff.setText("--- 等待抓包数据 ---\n");
        tvLogSys.setText("--- 等待指令回执 ---\n");

        registerReceiver(logReceiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));
        refreshStatus();

        // 按钮逻辑
        final Button btnSniff = findViewById(R.id.btn_sniff_toggle);
        btnSniff.setOnClickListener(v -> {
            isSniffing = !isSniffing;
            sendBroadcast(new Intent("com.xsf.amaphelper.TOGGLE_SNIFF"));
            btnSniff.setText(isSniffing ? "🛑 停止抓包" : "开启抓包");
            btnSniff.setBackgroundColor(isSniffing ? Color.RED : Color.parseColor("#673AB7"));
        });

        findViewById(R.id.btn_sniff_save).setOnClickListener(v -> saveToDownload(tvLogSniff.getText().toString(), "Sniff_"));
        findViewById(R.id.btn_sniff_clear).setOnClickListener(v -> tvLogSniff.setText(""));

        findViewById(R.id.btn_activate).setOnClickListener(v -> {
            Intent i = new Intent("XSF_ACTION_SEND_STATUS");
            i.putExtra("status", 13);
            sendBroadcast(i);
        });

        findViewById(R.id.btn_guide).setOnClickListener(v -> {
            Intent i = new Intent("XSF_ACTION_SEND_GUIDE");
            i.putExtra("type", "turn");
            sendBroadcast(i);
        });

        findViewById(R.id.btn_cruise).setOnClickListener(v -> {
            Intent i = new Intent("XSF_ACTION_SEND_GUIDE");
            i.putExtra("type", "cruise");
            sendBroadcast(i);
        });

        findViewById(R.id.btn_sys_save).setOnClickListener(v -> saveToDownload(tvLogSys.getText().toString(), "Sys_"));
        findViewById(R.id.btn_sys_clear).setOnClickListener(v -> tvLogSys.setText(""));
    }

    private void refreshStatus() {
        boolean active = isModuleActive();
        tvLspStatus.setText(active ? "LSPosed: 已激活 ✅" : "LSPosed: 未激活 ❌");
        tvLspStatus.setTextColor(active ? Color.GREEN : Color.RED);
    }

    private void saveToDownload(String content, String prefix) {
        if (content.isEmpty()) return;
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            String name = prefix + new SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content.getBytes());
            fos.close();
            Toast.makeText(this, "已存至Download: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show(); }
    }

    private void checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
            }
        }
    }

    @Override protected void onResume() { super.onResume(); refreshStatus(); }
    @Override protected void onDestroy() { super.onDestroy(); try { unregisterReceiver(logReceiver); } catch (Exception e) {} }
}
