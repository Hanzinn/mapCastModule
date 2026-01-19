package com.xsf.amaphelper;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "LSPosed_Navi";
    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    public static final String ACTION_LOG_UPDATE = "com.xsf.amaphelper.LOG_UPDATE";
    // 专门定义的抓包开关广播
    public static final String ACTION_TOGGLE_SNIFF = "com.xsf.amaphelper.TOGGLE_SNIFF";

    // ⬇️ 配置区
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    
    // ⬇️ 广播 Action
    private static final String ACTION_STD = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final String ACTION_SDK = "com.autonavi.minimap.SEND_BROADCAST"; 
    private static final String ACTION_CAR = "com.autonavi.amapauto.SEND_BROADCAST";
    
    // 🚩 抓包开关 (默认为 false，需手动开启)
    private static boolean isSniffing = false; 
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        
        // -----------------------------------------------------------
        // 1. 如果是助手 APP 自己：不仅激活模块，还要注入一个按钮！
        // -----------------------------------------------------------
        if (lpparam.packageName.equals(PKG_SELF)) {
            // 激活显示
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            
            // 💉 UI 注入：在 onCreate 后插入按钮
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "onCreate", Bundle.class, new de.robv.android.xposed.XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    injectSniffButton(activity);
                }
            });
            return;
        }

        // -----------------------------------------------------------
        // 2. 如果是目标车机服务：执行 Hook 逻辑
        // -----------------------------------------------------------
        if (!lpparam.packageName.equals(PKG_XSF)) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Application app = (Application) param.thisObject;
                Context context = app.getApplicationContext();
                if (context != null) {
                    logProxy(context, "✅ 模块加载完毕 (请点击界面顶部的新按钮开始抓包)");
                    registerCombinedReceiver(context, lpparam.classLoader);
                }
            }
        });
    }

    // 💉 黑科技：动态注入按钮
    private void injectSniffButton(Activity activity) {
        try {
            // 创建一个按钮
            Button btn = new Button(activity);
            btn.setText("📡 点击开始抓包");
            btn.setBackgroundColor(Color.parseColor("#FF6200EE")); // 紫色背景
            btn.setTextColor(Color.WHITE);
            btn.setPadding(20, 20, 20, 20);
            
            // 点击事件：发送广播切换抓包状态
            btn.setOnClickListener(v -> {
                Intent intent = new Intent(ACTION_TOGGLE_SNIFF);
                // 这是一个从 APP 发给 System 的广播，为了稳妥，虽然 APP 没权限发给 System，
                // 但因为我们同时 Hook 了 Service 的接收器，用普通广播即可通讯
                activity.sendBroadcast(intent);
                
                // 简单的视觉反馈 (实际状态由日志决定)
                if (btn.getText().toString().contains("开始")) {
                    btn.setText("🛑 抓包中 (点击停止)");
                    btn.setBackgroundColor(Color.RED);
                } else {
                    btn.setText("📡 点击开始抓包");
                    btn.setBackgroundColor(Color.parseColor("#FF6200EE"));
                }
            });

            // 添加到界面顶层 (使用 FrameLayout 参数，通常 DecorView 支持)
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; // 居中顶部
            params.topMargin = 100; // 稍微往下一点，避开状态栏

            activity.addContentView(btn, params);
            
        } catch (Throwable t) {
            Toast.makeText(activity, "按钮注入失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void registerCombinedReceiver(Context context, ClassLoader cl) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                // 1. 抓包开关控制
                if (ACTION_TOGGLE_SNIFF.equals(action)) {
                    isSniffing = !isSniffing;
                    logProxy(context, isSniffing ? "🟢 [抓包已开启] 请切换到高德地图操作..." : "🔴 [抓包已停止]");
                    return;
                }

                // 2. 抓包逻辑 (核心)
                if (isSniffing) {
                    if (action.contains("autonavi") || action.contains("amap")) {
                        logAllExtras(context, intent); // 打印数据
                        handleAmapStandardBroadcast(intent, cl, context); // 转发尝试点亮
                    }
                }

                // 3. 原有功能恢复 (激活导航)
                if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                    int status = intent.getIntExtra("status", 0);
                    if (status == 13) {
                        logProxy(context, "🚀 执行唤醒序列 (1 -> 27)");
                        sendStatusToBus(cl, 25, ctx(context)); // 先Start (25)
                        new Thread(()->{
                            try{Thread.sleep(200);}catch(Exception e){}
                            sendStatusToBus(cl, 27, ctx(context)); // 再Navi (27)
                        }).start();
                    } else {
                        sendStatusToBus(cl, status, ctx(context));
                    }
                }
                
                // 4. 原有功能恢复 (模拟巡航)
                if ("XSF_ACTION_SEND_GUIDE".equals(action)) {
                    logProxy(context, "🚗 发送模拟路口");
                    sendGuideToBus(cl, "测试路", "成功街", 1, 500, 0, 0, ctx(context));
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TOGGLE_SNIFF); // 监听新按钮
        filter.addAction(ACTION_STD);
        filter.addAction(ACTION_SDK);
        filter.addAction("com.autonavi.minimap.search.SEND_BROADCAST");
        filter.addAction(ACTION_CAR);
        filter.addAction("com.autonavi.amapauto.broadcast.SEND");
        filter.addAction("XSF_ACTION_SEND_GUIDE");
        filter.addAction("XSF_ACTION_SEND_STATUS");
        
        context.registerReceiver(receiver, filter);
    }

    // 辅助 context 获取
    private Context ctx(Context c) { return c; }

    // 🖨️ 抓包打印
    private void logAllExtras(Context ctx, Intent intent) {
        try {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                StringBuilder sb = new StringBuilder();
                String actName = intent.getAction();
                if(actName.contains(".")) actName = actName.substring(actName.lastIndexOf(".")+1);
                
                sb.append("\n📦 [").append(actName).append("]\n");
                
                Set<String> keys = bundle.keySet();
                for (String key : keys) {
                    Object value = bundle.get(key);
                    sb.append("   🔹 ").append(key).append(" = ").append(value).append("\n");
                }
                logProxy(ctx, sb.toString());
            }
        } catch (Throwable t) {}
    }

    // 转发逻辑 (bz + 25->27)
    private void handleAmapStandardBroadcast(Intent intent, ClassLoader cl, Context ctx) {
        // 保持之前的逻辑不变...
        // 简单写一下核心，确保编译通过
        try {
            int keyType = intent.getIntExtra("KEY_TYPE", 0);
            if (keyType == 0) keyType = intent.getIntExtra("key_type", 0);
            if (keyType == 0) keyType = intent.getIntExtra("EXTRA_TYPE", 0);

            if (keyType == 10001) {
                sendStatusToBus(cl, 27, ctx); 
                String cur = getString(intent, "CUR_ROAD_NAME", "cur_road_name");
                String next = getString(intent, "NEXT_ROAD_NAME", "next_road_name");
                int icon = getInt(intent, "ICON", "icon");
                int dist = getInt(intent, "SEG_REMAIN_DIS", "seg_remain_dis");
                if (dist == 0) dist = getInt(intent, "distance", "distance");
                sendGuideToBus(cl, cur, next, icon, dist, 0, 0, ctx);
            } else if (keyType == 10019) {
                int state = getInt(intent, "EXTRA_STATE", "extra_state");
                if (state == 2 || state == 8 || state == 12) {
                    sendStatusToBus(cl, 25, ctx); // 25
                    new Thread(()->{
                        try{Thread.sleep(200);}catch(Exception e){}
                        sendStatusToBus(cl, 27, ctx); // 27
                    }).start();
                } else if (state == 9 || state == 1) {
                    sendStatusToBus(cl, 26, ctx);
                }
            }
        } catch (Throwable t) {}
    }

    // 发送逻辑 (bz + 轮询参数)
    private void sendStatusToBus(ClassLoader cl, int status, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            Class<?> statusClass = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);

            int[] types = {2, 0, 1}; 
            for (int type : types) {
                Object statusObj;
                try { statusObj = XposedHelpers.newInstance(statusClass, type); }
                catch (Throwable t) { if(type==0) statusObj = XposedHelpers.newInstance(statusClass); else continue; }
                Field field = XposedHelpers.findFirstFieldByExactType(statusClass, int.class);
                if (field != null) { field.setAccessible(true); field.setInt(statusObj, status); }
                Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d2, statusObj); 
                XposedHelpers.callMethod(busInstance, "a", msg);
            }
        } catch (Throwable t) {}
    }

    private void sendGuideToBus(ClassLoader cl, String cur, String next, int icon, int dist, int totalDist, int totalTime, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            Class<?> guideClass = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);

            int[] types = {2, 0, 1};
            for (int type : types) {
                Object guideInfo;
                try { guideInfo = XposedHelpers.newInstance(guideClass, type); }
                catch (Throwable t) { if(type==0) guideInfo = XposedHelpers.newInstance(guideClass); else continue; }

                XposedHelpers.setObjectField(guideInfo, "curRoadName", cur);
                XposedHelpers.setObjectField(guideInfo, "nextRoadName", next);
                XposedHelpers.setIntField(guideInfo, "turnId", icon); 
                XposedHelpers.setIntField(guideInfo, "nextTurnDistance", dist);
                try { XposedHelpers.setIntField(guideInfo, "guideType", 0); } catch(Throwable t){}
                try { XposedHelpers.setIntField(guideInfo, "roadType", 1); } catch(Throwable t){}
                
                Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d0, guideInfo); 
                XposedHelpers.callMethod(busInstance, "a", msg);
            }
        } catch (Throwable t) {}
    }
    
    private void handleAdbGuide(Intent intent, ClassLoader cl, Context ctx) {
        sendGuideToBus(cl, "嗅探测试", "监听中...", 1, 0, 0, 0, ctx);
    }
    private void handleAdbStatus(Intent intent, ClassLoader cl, Context ctx) {
        sendStatusToBus(cl, intent.getIntExtra("status", 0), ctx);
    }
    private void logProxy(Context context, String logContent) {
        XposedBridge.log(TAG + ": " + logContent);
        try {
            Intent intent = new Intent(ACTION_LOG_UPDATE);
            intent.putExtra("log", logContent);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES); 
            context.sendBroadcast(intent);
        } catch (Throwable t) {}
    }
    private String getString(Intent i, String k1, String k2) { return (i.getStringExtra(k1) != null) ? i.getStringExtra(k1) : i.getStringExtra(k2); }
    private int getInt(Intent i, String k1, String k2) { return (i.getIntExtra(k1, -1) != -1) ? i.getIntExtra(k1, -1) : i.getIntExtra(k2, 0); }
}
