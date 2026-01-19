package com.xsf.amaphelper;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    
    private boolean isSniffing = false; // 记录抓包状态

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- 按钮1：抓包开关 ---
        Button btnSniff = findViewById(R.id.btn_sniff);
        btnSniff.setOnClickListener(v -> {
            isSniffing = !isSniffing;
            // 发送切换抓包的广播
            Intent intent = new Intent("com.xsf.amaphelper.TOGGLE_SNIFF");
            sendBroadcast(intent);
            
            // 变色提示
            if (isSniffing) {
                btnSniff.setText("🛑 抓包中... (点击停止)");
                btnSniff.setBackgroundColor(Color.RED);
            } else {
                btnSniff.setText("📡 开启高德抓包 (关)");
                btnSniff.setBackgroundColor(Color.parseColor("#673AB7"));
            }
        });

        // --- 按钮2：激活测试 ---
        Button btnActivate = findViewById(R.id.btn_activate);
        btnActivate.setOnClickListener(v -> {
            // 发送状态指令 (Status 13 代表激活测试)
            Intent intent = new Intent("XSF_ACTION_SEND_STATUS");
            intent.putExtra("status", 13);
            sendBroadcast(intent);
        });

        // --- 按钮3：路口测试 ---
        Button btnGuide = findViewById(R.id.btn_guide);
        btnGuide.setOnClickListener(v -> {
            // 发送路口指令
            Intent intent = new Intent("XSF_ACTION_SEND_GUIDE");
            intent.putExtra("curRoad", "测试路");
            intent.putExtra("nextRoad", "成功街");
            intent.putExtra("distance", 500);
            sendBroadcast(intent);
        });
    }
}
