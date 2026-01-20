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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    
    private TextView tvLog, tvLspStatus, tvHookStatus;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public boolean isModuleActive() { return false; }

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String content = intent.getStringExtra("log");
            if (content == null) return;
            if (content.contains("模块加载成功")) {
                runOnUiThread(() -> {
                    tvHookStatus.setText("服务: 已连接 ✅");
                    tvHookStatus.setTextColor(Color.GREEN);
                });
            }
            logLocal("模块: " + content);
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
        scrollView = findViewById(R.id.scrollView);

        registerReceiver(logReceiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));

        // 🟢 按钮1：全量轰炸测试
        findViewById(R.id.btn_super_test).setOnClickListener(v -> {
            logLocal("🔥 启动全量轰炸测试：1 -> 25 -> 13 -> 27");
            Intent i = new Intent("XSF_ACTION_SUPER_TEST");
            sendBroadcast(i);
        });

        // 🟢 按钮2：路口测试
        findViewById(R.id.btn_guide_test).setOnClickListener(v -> {
            logLocal("🚗 尝试发送模拟路口...");
            Intent i = new Intent("XSF_ACTION_SEND_GUIDE");
            sendBroadcast(i);
        });

        findViewById(R.id.btn_clear_log).setOnClickListener(v -> tvLog.setText(""));
        findViewById(R.id.btn_save_log).setOnClickListener(v -> saveToDownload());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
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
            String name = "XSF_SuperTest_" + new SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(path, name);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(tvLog.getText().toString().getBytes());
            fos.close();
            Toast.makeText(this, "✅ 存至 Download/" + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onResume() {
        super.onResume();
        boolean active = isModuleActive();
        tvLspStatus.setText(active ? "LSPosed: 已激活 ✅" : "LSPosed: 未激活 ❌");
        tvLspStatus.setTextColor(active ? Color.GREEN : Color.RED);
    }
}
