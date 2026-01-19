package com.xsf.amaphelper;

import android.app.Activity; // ✅ 改为原生 Activity
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// ✅ 这里继承 Activity 而不是 AppCompatActivity
public class MainActivity extends Activity {
    
    private boolean isSniffing = false;
    private TextView tvLogSniff, tvLogSys;
    private ScrollView scrollSniff, scrollSys;
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 接收模块发来的日志
    private BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String content = intent.getStringExtra("log");
            int type = intent.getIntExtra("type", 0); // 0=系统日志(右), 1=抓包日志(左)
            
            String time = sdf.format(new Date());
            String finalLog = "[" + time + "] " + content + "\n\n";

            if (type == 1) {
                // 左屏：抓包
                if (tvLogSniff != null) {
                    tvLogSniff.append(finalLog);
                    if (scrollSniff != null) scrollSniff.post(() -> scrollSniff.fullScroll(ScrollView.FOCUS_DOWN));
                }
            } else {
                // 右屏：系统
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
        setContentView(R.layout.activity_main);

        // 初始化视图
        tvLogSniff = findViewById(R.id.tv_log_sniff);
        tvLogSys = findViewById(R.id.tv_log_sys);
        scrollSniff = findViewById(R.id.scroll_sniff);
        scrollSys = findViewById(R.id.scroll_sys);
        
        // 注册日志接收器
        registerReceiver(logReceiver, new IntentFilter("com.xsf.amaphelper.LOG_UPDATE"));

        // 按钮1：抓包开关
        Button btnSniff = findViewById(R.id.btn_sniff);
        btnSniff.setOnClickListener(v -> {
            isSniffing = !isSniffing;
            sendBroadcast(new Intent("com.xsf.amaphelper.TOGGLE_SNIFF"));
            
            if (isSniffing) {
                btnSniff.setText("🛑 停止抓包");
                btnSniff.setBackgroundColor(Color.RED);
            } else {
                btnSniff.setText("📡 开启抓包 (关)");
                btnSniff.setBackgroundColor(Color.parseColor("#673AB7"));
            }
        });

        // 按钮2：清空
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            if (tvLogSniff != null) tvLogSniff.setText("");
            if (tvLogSys != null) tvLogSys.setText("");
        });

        // 按钮3：激活
        findViewById(R.id.btn_activate).setOnClickListener(v -> {
            Intent i = new Intent("XSF_ACTION_SEND_STATUS");
            i.putExtra("status", 13);
            sendBroadcast(i);
        });

        // 按钮4：模拟路口
        findViewById(R.id.btn_guide).setOnClickListener(v -> {
            sendBroadcast(new Intent("XSF_ACTION_SEND_GUIDE"));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(logReceiver);
        } catch (Exception e) {
            // 忽略未注册的异常
        }
    }
}
