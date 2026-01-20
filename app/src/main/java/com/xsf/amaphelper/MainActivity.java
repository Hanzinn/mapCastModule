package com.xsf.amaphelper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Window;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView tvLog, tvLsp, tvHook, tvSvc;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public boolean isModuleActive() { return false; }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log == null) return;
            if (log.contains("STATUS_HOOK_READY")) { tvHook.setText("注入: ✅"); tvHook.setTextColor(Color.GREEN); }
            else if (log.contains("STATUS_SERVICE_RUNNING")) { tvSvc.setText("服务: ✅"); tvSvc.setTextColor(Color.GREEN); }
            else { appendLog("模块: " + log); }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        tvLsp = findViewById(R.id.tv_lsp_status);
        tvHook = findViewById(R.id.tv_hook_status);
        tvSvc = findViewById(R.id.tv_service_status);
        scrollView = findViewById(R.id.scrollView);

        registerReceiver(receiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));

        // 🟢 检查点：确保 findViewById 的 ID 与 XML 完全一致
        findViewById(R.id.btn_start_service).setOnClickListener(v -> {
            appendLog("手动操作: 点击冷启动服务"); // 先写日志 
            sendBroadcast(new Intent("XSF_ACTION_START_SERVICE"));
        });

        findViewById(R.id.btn_activate).setOnClickListener(v -> {
            appendLog("手动操作: 点击激活仪表");
            Intent i = new Intent("XSF_ACTION_SEND_STATUS");
            i.putExtra("status", 13);
            sendBroadcast(i);
        });

        findViewById(R.id.btn_start_cruise).setOnClickListener(v -> {
            appendLog("手动操作: 点击开启巡航");
            Intent i = new Intent("XSF_ACTION_SEND_STATUS");
            i.putExtra("status", 28);
            sendBroadcast(i);
        });

        findViewById(R.id.btn_stop_cruise).setOnClickListener(v -> {
            appendLog("手动操作: 点击停止巡航");
            Intent i = new Intent("XSF_ACTION_SEND_STATUS");
            i.putExtra("status", 29);
            sendBroadcast(i);
        });
    }

    private void appendLog(String m) {
        runOnUiThread(() -> {
            tvLog.append("[" + sdf.format(new Date()) + "] " + m + "\n");
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override protected void onResume() {
        super.onResume();
        boolean a = isModuleActive();
        tvLsp.setText(a ? "LSP: ✅" : "LSP: ❌");
        tvLsp.setTextColor(a ? Color.GREEN : Color.RED);
    }
}
