package com.xsf.amaphelper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tvStatus = findViewById(R.id.tv_status);
        
        if (isModuleActive()) {
            tvStatus.setText("模块状态：已激活 ✅");
            tvStatus.setTextColor(Color.GREEN);
        } else {
            tvStatus.setText("模块状态：未激活 ❌\n(请在LSPosed勾选并重启)");
            tvStatus.setTextColor(Color.RED);
        }

        findViewById(R.id.btn_test_start).setOnClickListener(v -> {
            Intent intent = new Intent("XSF_ACTION_SEND_STATUS");
            intent.putExtra("status", 13); 
            sendBroadcast(intent);
            toast("指令已发送：激活导航");
        });

        findViewById(R.id.btn_test_guide).setOnClickListener(v -> {
            Intent intent = new Intent("XSF_ACTION_SEND_GUIDE");
            intent.putExtra("curRoad", "测试路");
            intent.putExtra("nextRoad", "成功大道");
            intent.putExtra("icon", 2); 
            intent.putExtra("distance", 500);
            sendBroadcast(intent);
            toast("指令已发送：500米后左转");
        });

        findViewById(R.id.btn_test_cruise).setOnClickListener(v -> {
            Intent intent = new Intent("XSF_ACTION_SEND_GUIDE");
            intent.putExtra("curRoad", "cruise_test"); 
            sendBroadcast(intent);
            toast("指令已发送：模拟巡航数据");
        });
    }

    private boolean isModuleActive() { return false; }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
}
