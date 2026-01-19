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

    private TextView tvLog, tvStatus, tvLogStatus, tvTargetStatus;
    private Button btnToggleLog;
    private StringBuilder logBuffer = new StringBuilder();
    private boolean isRecording = false;

    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            // 1. 处理日志
            if (isRecording && "com.xsf.amaphelper.LOG_UPDATE".equals(action)) {
                appendLog(intent.getStringExtra("log"));
            }
            
            // 2. 处理握手回应 (PONG)
            else if ("com.xsf.amaphelper.PONG".equals(action)) {
                int pid = intent.getIntExtra("pid", 0);
                tvTargetStatus.setText("仪表服务：已连接 🟢 (PID:" + pid + ")");
                tvTargetStatus.setTextColor(Color.GREEN);
                if(isRecording) appendLog("✅ 检测到目标服务在线！PID=" + pid);
                else Toast.makeText(MainActivity.this, "连接成功！目标在线！", Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        checkPermission();
        
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.xsf.amaphelper.LOG_UPDATE");
        filter.addAction("com.xsf.amaphelper.PONG"); // 监听回应
        registerReceiver(logReceiver, filter);

        updateModuleStatus();
        
        // 启动时自动检测一次
        checkConnection();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvTargetStatus = findViewById(R.id.tv_target_status); // 新增的状态显示
        tvLogStatus = findViewById(R.id.tv_log_status);
        tvLog = findViewById(R.id.tv_log);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        btnToggleLog = findViewById(R.id.btn_toggle_log);

        btnToggleLog.setOnClickListener(v -> toggleLogging());
        findViewById(R.id.btn_save_log).setOnClickListener(v -> saveLogToDownload());
        
        // 新增：手动检测连接按钮
        findViewById(R.id.btn_check_conn).setOnClickListener(v -> checkConnection());

        findViewById(R.id.btn_test_start).setOnClickListener(v -> {
            sendCmd("XSF_ACTION_SEND_STATUS", "status", 13);
            if(isRecording) appendLog("手动发送: 激活导航 (Status 13)");
        });
        findViewById(R.id.btn_test_guide).setOnClickListener(v -> {
            sendCmdGuide();
            if(isRecording) appendLog("手动发送: 路口测试信息");
        });
        findViewById(R.id.btn_test_cruise).setOnClickListener(v -> {
             Intent intent = new Intent("XSF_ACTION_SEND_GUIDE");
             intent.putExtra("curRoad", "cruise_test");
             sendBroadcast(intent);
             if(isRecording) appendLog("手动发送: 模拟巡航模式");
        });
    }

    // 发送 PING 指令
    private void checkConnection() {
        tvTargetStatus.setText("仪表服务：正在检测... 🟡");
        tvTargetStatus.setTextColor(Color.YELLOW);
        
        Intent intent = new Intent("com.xsf.amaphelper.PING");
        sendBroadcast(intent);
        
        // 如果1秒后没变绿，说明没连上（这里不做复杂逻辑，靠用户自己看变没变绿）
    }

    private void toggleLogging() {
        isRecording = !isRecording;
        if (isRecording) {
            logBuffer.setLength(0); 
            tvLog.setText("");      
            appendLog("=== 开始抓取日志 ===");
            btnToggleLog.setText("停止抓取");
            btnToggleLog.setBackgroundColor(Color.RED); 
            tvLogStatus.setText("状态：正在记录... (请操作高德)");
        } else {
            appendLog("=== 日志抓取结束 ===");
            btnToggleLog.setText("开始抓取日志");
            btnToggleLog.setBackgroundColor(Color.parseColor("#4CAF50")); 
            tvLogStatus.setText("状态：已停止");
        }
    }

    private void saveLogToDownload() {
        if (logBuffer.length() == 0) {
            toast("日志为空");
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
            toast("保存成功: " + fileName);
            tvLog.append("\n[系统] 已保存到: " + fileName);
        } catch (Exception e) { toast("保存失败"); }
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
            tvStatus.setText("模块自身状态：已加载 ✅");
            tvStatus.setTextColor(Color.GREEN);
        } else {
            tvStatus.setText("模块自身状态：未激活 ❌ (请重启LSPosed)");
            tvStatus.setTextColor(Color.RED);
        }
    }
    
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
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
    @Override
    protected void onDestroy() { super.onDestroy(); unregisterReceiver(logReceiver); }
}
