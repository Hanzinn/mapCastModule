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
    private TextView tvLogSniff, tvLogSys;
    private TextView tvLspStatus, tvHookStatus;
    private ScrollView scrollSniff, scrollSys;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public boolean isModuleActive() { return false; }

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String content = intent.getStringExtra("log");
            int type = intent.getIntExtra("type", 0);
            if (content == null) return;
            
            if (content.contains("模块加载成功")) updateHookStatus(true);

            String time = sdf.format(new Date());
            final String finalLog = "[" + time + "] " + content + "\n\n";

            runOnUiThread(() -> {
                if (type == 1) {
                    tvLogSniff.append(finalLog);
                    scrollSniff.post(() -> scrollSniff.fullScroll(ScrollView.FOCUS_DOWN));
                } else {
                    tvLogSys.append(finalLog);
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
        
        // 🚀 初始化检查：如果下面两行没显示，说明 ScrollView 没能撑开显示
        tvLogSniff.setText("--- 等待抓包日志 ---\n");
        tvLogSys.setText("--- 等待系统日志 ---\n");

        registerReceiver(logReceiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));
        refreshStatus();

        findViewById(R.id.btn_sniff_toggle).setOnClickListener(v -> {
            isSniffing = !isSniffing;
            sendBroadcast(new Intent("com.xsf.amaphelper.TOGGLE_SNIFF"));
            Button btn = (Button) v;
            btn.setText(isSniffing ? "停止抓包" : "开启抓包");
            btn.setBackgroundColor(isSniffing ? Color.RED : Color.parseColor("#673AB7"));
        });

        findViewById(R.id.btn_sniff_save).setOnClickListener(v -> saveLogToDownload(tvLogSniff.getText().toString(), "Sniff_"));
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
        
        findViewById(R.id.btn_sys_save).setOnClickListener(v -> saveLogToDownload(tvLogSys.getText().toString(), "Sys_"));
        findViewById(R.id.btn_sys_clear).setOnClickListener(v -> tvLogSys.setText(""));
    }

    @Override protected void onResume() { super.onResume(); refreshStatus(); }

    private void refreshStatus() {
        boolean active = isModuleActive();
        tvLspStatus.setText("LSPosed: " + (active ? "已激活 ✅" : "未激活 ❌"));
        tvLspStatus.setTextColor(active ? Color.GREEN : Color.RED);
    }
    
    private void updateHookStatus(boolean success) {
        tvHookStatus.setText("Hook服务: 已连接 ✅");
        tvHookStatus.setTextColor(Color.GREEN);
    }

    private void saveLogToDownload(String content, String prefix) {
        if (content == null || content.isEmpty()) return;
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            String fileName = prefix + new SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(downloadDir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content.getBytes());
            fos.close();
            Toast.makeText(this, "保存成功: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "失败: " + e.getMessage(), Toast.LENGTH_SHORT).show(); }
    }

    private void checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
            }
        }
    }

    @Override protected void onDestroy() { 
        super.onDestroy(); 
        try { unregisterReceiver(logReceiver); } catch (Exception e) {} 
    }
}
