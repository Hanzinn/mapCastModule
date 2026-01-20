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
    private TextView tvLog, tvLsp, tvHook, tvSvc, tvIpc;
    private ScrollView scrollView;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public boolean isModuleActive() { return false; }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log == null) return;
            
            if (log.contains("STATUS_HOOK_READY")) setStatus(tvHook, "注入: ✅");
            else if (log.contains("STATUS_SERVICE_RUNNING")) setStatus(tvSvc, "服务: ✅");
            else if (log.contains("STATUS_IPC_CONNECTED")) {
                setStatus(tvIpc, "链路IPC: ✅");
                appendLog(">>> 底层链路已打通，可以发送数据 <<<");
            }
            else appendLog("模块: " + log);
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

        tvLog = findViewById(R.id.tv_log);
        tvLsp = findViewById(R.id.tv_lsp_status);
        tvHook = findViewById(R.id.tv_hook_status);
        tvSvc = findViewById(R.id.tv_service_status);
        tvIpc = findViewById(R.id.tv_ipc_status);
        scrollView = findViewById(R.id.scrollView);

        registerReceiver(receiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));

        findViewById(R.id.btn_start_service).setOnClickListener(v -> {
            appendLog("手动操作: 开始全套冷启动流程...");
            sendBroadcast(new Intent("XSF_ACTION_START_SERVICE"));
        });

        findViewById(R.id.btn_activate).setOnClickListener(v -> sendStatus(13));
        findViewById(R.id.btn_start_cruise).setOnClickListener(v -> sendStatus(28));
        findViewById(R.id.btn_stop_cruise).setOnClickListener(v -> sendStatus(29));
    }

    private void sendStatus(int s) {
        appendLog("手动发送: Status " + s);
        Intent i = new Intent("XSF_ACTION_SEND_STATUS");
        i.putExtra("status", s);
        sendBroadcast(i);
    }

    private void appendLog(String m) {
        runOnUiThread(() -> {
            if (tvLog != null) {
                tvLog.append("[" + sdf.format(new Date()) + "] " + m + "\n");
                if (scrollView != null) scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        boolean a = isModuleActive();
        tvLsp.setText(a ? "LSP: ✅" : "LSP: ❌");
        tvLsp.setTextColor(a ? Color.GREEN : Color.RED);
    }
}
