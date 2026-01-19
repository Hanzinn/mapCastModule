package com.xsf.amaphelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView tvLog, tvStatus, tvLogStatus;
    private Button btnToggleLog;
    private StringBuilder logBuffer = new StringBuilder();
    
    // 是否正在记录
    private boolean isRecording = false;

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 只有当开启记录时，才处理广播
            if (isRecording && "com.xsf.amaphelper.LOG_UPDATE".equals(intent.getAction())) {
                String msg = intent.getStringExtra("log");
                appendLog(msg);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermission();
        
        // 注册广播监听
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.xsf.amaphelper.LOG_UPDATE");
        registerReceiver(logReceiver, filter);

        updateModuleStatus();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvLogStatus = findViewById(R.id.tv_log_status);
        tvLog = findViewById(R.id.tv_log);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        
        btnToggleLog = findViewById(R.id.btn_toggle_log);

        // 按钮：开始/停止抓取
        btnToggleLog.setOnClickListener(v -> toggleLogging());

        // 按钮：保存到 Download
        findViewById(R.id.btn_save_log).setOnClickListener(v -> saveLogToDownload());

        // --- 这里修改了显示逻辑，让日志更人话 ---
        
        // 测试按钮 1：激活导航
        findViewById(R.id.btn_test_start).setOnClickListener(v -> {
            sendCmd("XSF_ACTION_SEND_STATUS", "status", 13);
            if(isRecording) appendLog("手动发送: 激活导航"); // 现在显示这个中文了
        });
        
        // 测试按钮 2：发送路口
        findViewById(R.id.btn_test_guide).setOnClickListener(v -> {
            sendCmdGuide();
            if(isRecording) appendLog("手动发送: 路口测试信息");
        });
        
        // 测试按钮 3：模拟巡航
        findViewById(R.id.btn_test_cruise).setOnClickListener(v -> {
             Intent intent = new Intent("XSF_ACTION_SEND_GUIDE");
             intent.putExtra("curRoad", "cruise_test");
             sendBroadcast(intent);
             if(isRecording) appendLog("手动发送: 模拟巡航模式");
        });
    }

    // 切换抓取状态
    private void toggleLogging() {
        isRecording = !isRecording;
        if (isRecording) {
            logBuffer.setLength(0); // 清空旧缓存
            tvLog.setText("");      // 清空屏幕
            appendLog("=== 开始抓取日志 ===");
            
            btnToggleLog.setText("停止抓取");
            btnToggleLog.setBackgroundColor(Color.RED); 
            tvLogStatus.setText("当前状态：正在记录中... (请操作高德)");
            tvLogStatus.setTextColor(Color.RED);
        } else {
            appendLog("=== 日志抓取结束 ===");
            btnToggleLog.setText("开始抓取日志");
            btnToggleLog.setBackgroundColor(Color.parseColor("#4CAF50")); 
            tvLogStatus.setText("当前状态：已停止 (点击右侧按钮保存)");
            tvLogStatus.setTextColor(Color.GRAY);
        }
    }

    // 保存日志到 Download 目录
    private void saveLogToDownload() {
        if (logBuffer.length() == 0) {
            toast("日志为空，请先开始抓取！");
            return;
        }
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) downloadDir.mkdirs();

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "XSF_Log_" + timeStamp + ".txt";
            File file = new File(downloadDir, fileName);

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(logBuffer.toString().getBytes());
            fos.close();

            toast("保存成功！路径:\n" + file.getAbsolutePath());
            tvLog.append("\n[系统] 日志已保存到: " + fileName);
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void appendLog(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + time + "] " + msg + "\n";
        
        logBuffer.append(line);
        
        runOnUiThread(() -> {
            if (tvLog.getText().length() > 8000) tvLog.setText(""); 
            tvLog.append(line);
            int scrollAmount = tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
            if (scrollAmount > 0) tvLog.scrollTo(0, scrollAmount);
        });
    }

    private void checkPermission() {
        if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
        }
    }

    private void updateModuleStatus() {
        if (isModuleActive()) {
            tvStatus.setText("模块状态：已激活 ✅");
            tvStatus.setTextColor(Color.GREEN);
        } else {
            tvStatus.setText("模块状态：未激活 ❌ (请在LSPosed勾选并重启)");
            tvStatus.setTextColor(Color.RED);
        }
    }
    
    // 发送指令辅助方法 (改回纯发送，不带日志)
    private void sendCmd(String action, String key, int val) {
        Intent intent = new Intent(action);
        intent.putExtra(key, val);
        sendBroadcast(intent);
    }

    private void sendCmdGuide() {
        Intent intent = new Intent("XSF_ACTION_SEND_GUIDE");
        intent.putExtra("curRoad", "测试路");
        intent.putExtra("nextRoad", "成功大道");
        intent.putExtra("icon", 2);
        intent.putExtra("distance", 500);
        sendBroadcast(intent);
    }
    
    private boolean isModuleActive() { return false; }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(logReceiver);
    }
}
