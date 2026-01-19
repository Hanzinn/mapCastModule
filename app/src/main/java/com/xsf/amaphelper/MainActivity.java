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

    // 🔴 关键方法：Xposed Hook 此方法来改变状态
    public boolean isModuleActive() {
        return false;
    }

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String content = intent.getStringExtra("log");
            int type = intent.getIntExtra("type", 0);
            
            // 收到 Hook 成功消息自动更新状态
            if (content != null && content.contains("模块加载成功")) {
                updateHookStatus(true);
            }

            String time = sdf.format(new Date());
            String finalLog = "[" + time + "] " + content + "\n\n";

            if (type == 1) {
                if (tvLogSniff != null) {
                    tvLogSniff.append(finalLog);
                    if (scrollSniff != null) scrollSniff.post(() -> scrollSniff.fullScroll(ScrollView.FOCUS_DOWN));
                }
            } else {
                if (tvLogSys != null) {
                    tvLogSys.append(finalLog);
                    if (scrollSys != null) scrollSys.post(() -> scrollSys.fullScroll(ScrollView.FOCUS_DOWN));
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 去掉标题栏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        
        checkPermission(); // 申请存储权限

        // 绑定视图
        tvLspStatus = findViewById(R.id.tv_lsp_status);
        tvHookStatus = findViewById(R.id.tv_hook_status);
        tvLogSniff = findViewById(R.id.tv_log_sniff);
        tvLogSys = findViewById(R.id.tv_log_sys);
        scrollSniff = findViewById(R.id.scroll_sniff);
        scrollSys = findViewById(R.id.scroll_sys);
        
        registerReceiver(logReceiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));
        refreshStatus();

        // === 左侧逻辑 ===
        Button btnSniff = findViewById(R.id.btn_sniff_toggle);
        btnSniff.setOnClickListener(v -> {
            isSniffing = !isSniffing;
            sendBroadcast(new Intent("com.xsf.amaphelper.TOGGLE_SNIFF"));
            
            if (isSniffing) {
                btnSniff.setText("🟢 抓包中 (点击停止)");
                btnSniff.setBackgroundColor(Color.RED);
            } else {
                btnSniff.setText("🔴 开启抓包 (关)");
                btnSniff.setBackgroundColor(Color.parseColor("#673AB7"));
            }
        });

        findViewById(R.id.btn_sniff_save).setOnClickListener(v -> saveLogToDownload(tvLogSniff.getText().toString(), "Sniff_"));
        findViewById(R.id.btn_sniff_clear).setOnClickListener(v -> tvLogSniff.setText(""));

        // === 右侧逻辑 ===
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

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (isModuleActive()) {
            tvLspStatus.setText("LSPosed: 已激活 ✅");
            tvLspStatus.setTextColor(Color.GREEN);
        } else {
            tvLspStatus.setText("LSPosed: 未激活 ❌");
            tvLspStatus.setTextColor(Color.RED);
        }
    }
    
    private void updateHookStatus(boolean success) {
        if (success) {
            tvHookStatus.setText("Hook服务: 已连接 ✅");
            tvHookStatus.setTextColor(Color.GREEN);
        }
    }

    private void saveLogToDownload(String content, String prefix) {
        if (content == null || content.isEmpty()) {
            Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
                return;
            }
        }
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) downloadDir.mkdirs();
            SimpleDateFormat timeFormat = new SimpleDateFormat("MMdd_HHmmss", Locale.getDefault());
            String fileName = prefix + timeFormat.format(new Date()) + ".txt";
            File file = new File(downloadDir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content.getBytes());
            fos.close();
            Toast.makeText(this, "✅ 已保存到 Download: " + fileName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] permissions = {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
            if (checkSelfPermission(permissions[0]) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permissions, 100);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(logReceiver); } catch (Exception e) {}
    }
}
