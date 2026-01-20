package com.xsf.amaphelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment; // 🟢 必须引入这个
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
    
    // UI 控件
    private TextView tvLog, tvLsp, tvHook, tvSvc, tvIpc;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 自身激活状态检测 (Xposed 会 Hook 这个方法返回 true)
    public boolean isModuleActive() { return false; }

    // 广播接收器
    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log == null) return;
            
            // 🟢 状态灯逻辑
            if (log.contains("STATUS_HOOK_READY")) {
                setStatus(tvHook, "注入: ✅");
            } 
            else if (log.contains("STATUS_SERVICE_RUNNING")) {
                setStatus(tvSvc, "服务: ✅");
            } 
            else if (log.contains("STATUS_IPC_CONNECTED")) {
                setStatus(tvIpc, "链路IPC: ✅");
                appendLog(">>> 🎉 物理链路已打通！ <<<");
            } 
            // 📝 普通日志
            else {
                appendLog("模块: " + log);
            }
        }
    };

    private void setStatus(TextView tv, String text) {
        tv.setText(text);
        tv.setTextColor(Color.GREEN);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        // 初始化视图
        tvLog = findViewById(R.id.tv_log);
        tvLsp = findViewById(R.id.tv_lsp_status);
        tvHook = findViewById(R.id.tv_hook_status);
        tvSvc = findViewById(R.id.tv_service_status);
        tvIpc = findViewById(R.id.tv_ipc_status);
        scrollView = findViewById(R.id.scrollView);

        // 注册广播
        registerReceiver(receiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));

        // --- 按钮事件绑定 ---

        // 1. 冷启动服务
        findViewById(R.id.btn_start_service).setOnClickListener(v -> {
            tvSvc.setText("服务: ⏳"); tvSvc.setTextColor(Color.YELLOW);
            tvIpc.setText("链路IPC: ⏳"); tvIpc.setTextColor(Color.YELLOW);
            appendLog("步骤1: 发送冷启动指令...");
            sendBroadcast(new Intent("XSF_ACTION_START_SERVICE"));
        });

        // 2. 暴力重连 (B 计划)
        findViewById(R.id.btn_force_connect).setOnClickListener(v -> {
            appendLog("步骤2: 手动执行 B 计划 (Switch + 暴力连接)...");
            sendBroadcast(new Intent("XSF_ACTION_FORCE_CONNECT"));
        });

        // 3. 激活仪表
        findViewById(R.id.btn_activate).setOnClickListener(v -> {
            appendLog("步骤3: 发送激活连招...");
            sendStatus(13); 
        });

        // 巡航控制
        findViewById(R.id.btn_start_cruise).setOnClickListener(v -> {
            appendLog("发送: 巡航模式 (28)");
            sendStatus(28);
        });

        findViewById(R.id.btn_stop_cruise).setOnClickListener(v -> {
            appendLog("发送: 停止 (29)");
            sendStatus(29);
        });

        // 💾 保存日志 (修改了路径)
        findViewById(R.id.btn_save_log).setOnClickListener(v -> {
            saveLogToFile();
        });
    }

    private void sendStatus(int s) {
        Intent i = new Intent("XSF_ACTION_SEND_STATUS");
        i.putExtra("status", s);
        sendBroadcast(i);
    }

    // 保存日志到文件 (路径已修改)
    private void saveLogToFile() {
        String logContent = tvLog.getText().toString();
        if (logContent.isEmpty()) {
            Toast.makeText(this, "日志为空，无需保存", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 📂 修改路径：/sdcard/Download/AmapHelper_Logs/
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dir = new File(downloadDir, "AmapHelper_Logs");
            
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                if (!created) {
                    // 如果 Download 创建失败，回退到根目录
                    dir = new File(Environment.getExternalStorageDirectory(), "AmapHelper_Logs");
                    dir.mkdirs();
                }
            }

            String fileName = "Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            File file = new File(dir, fileName);

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(logContent.getBytes());
            fos.close();

            String msg = "日志已保存:\n" + file.getAbsolutePath();
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            appendLog("✅ " + msg);

        } catch (Exception e) {
            appendLog("❌ 保存失败: " + e.getMessage());
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void appendLog(String m) {
        runOnUiThread(() -> {
            if (tvLog != null) {
                tvLog.append("[" + sdf.format(new Date()) + "] " + m + "\n");
                if (scrollView != null) {
                    scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean active = isModuleActive();
        tvLsp.setText(active ? "LSP: ✅" : "LSP: ❌");
        tvLsp.setTextColor(active ? Color.GREEN : Color.RED);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
    }
}
