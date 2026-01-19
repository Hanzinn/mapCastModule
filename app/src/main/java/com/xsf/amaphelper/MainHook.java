package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import java.lang.reflect.Field; // ✅ 已修复编译报错
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
    
    // 定义三种指令 Action，与 APP 端对应
    public static final String ACTION_LOG_UPDATE = "com.xsf.amaphelper.LOG_UPDATE";
    public static final String ACTION_TOGGLE_SNIFF = "com.xsf.amaphelper.TOGGLE_SNIFF"; // 按钮1
    public static final String ACTION_SEND_STATUS = "XSF_ACTION_SEND_STATUS";           // 按钮2
    public static final String ACTION_SEND_GUIDE = "XSF_ACTION_SEND_GUIDE";             // 按钮3

    // 类名配置
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    
    // 高德广播 Action
    private static final String ACTION_STD = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final String ACTION_SDK = "com.autonavi.minimap.SEND_BROADCAST"; 
    private static final String ACTION_CAR = "com.autonavi.amapauto.SEND_BROADCAST";
    
    // 🚩 抓包开关 (默认为关)
    private static boolean isSniffing = false; 
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        
        // 1. 自身 APP：只做激活显示
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 2. 目标服务：核心逻辑
        if (!lpparam.packageName.equals(PKG_XSF)) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Application app = (Application) param.thisObject;
                Context context = app.getApplicationContext();
                if (context != null) {
                    logProxy(context, "✅ 模块加载完毕 (三按钮分控版)");
                    registerCombinedReceiver(context, lpparam.classLoader);
                }
            }
        });
    }

    private void registerCombinedReceiver(Context context, ClassLoader cl) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                // ==========================================
                // 🕹️ 功能 1：抓包开关 (对应按钮 1)
                // ==========================================
                if (ACTION_TOGGLE_SNIFF.equals(action)) {
                    isSniffing = !isSniffing;
                    logProxy(context, isSniffing ? "🟢 [抓包开启] 请切到高德地图..." : "🔴 [抓包停止]");
                    return;
                }

                // ==========================================
                // 🕵️‍♂️ 功能 1.5：执行抓包 (监听高德)
                // ==========================================
                if (isSniffing) {
                    if (action.contains("autonavi") || action.contains("amap")) {
                        logAllExtras(context, intent); // 打印数据
                        // 抓包时也尝试转发，看看效果
                        handleAmapStandardBroadcast(intent, cl, context); 
                    }
                }

                // ==========================================
                // 🚀 功能 2：激活测试 (对应按钮 2)
                // ==========================================
                if (ACTION_SEND_STATUS.equals(action)) {
                    int status = intent.getIntExtra("status", 0);
                    if (status == 13) {
                        logProxy(context, "🚀 发送唤醒序列 (25 -> 27)");
                        sendStatusToBus(cl, 25, ctx(context)); // 先 Start
                        new Thread(()->{
                            try{Thread.sleep(300);}catch(Exception e){}
                            sendStatusToBus(cl, 27, ctx(context)); // 再 Navi
                        }).start();
                    } else {
                        sendStatusToBus(cl, status, ctx(context));
                    }
                }
                
                // ==========================================
                // 🚗 功能 3：路口测试 (对应按钮 3)
                // ==========================================
                if (ACTION_SEND_GUIDE.equals(action)) {
                    logProxy(context, "🚗 发送模拟路口: 测试路 -> 成功街");
                    // 发送路口前，保险起见也发一个 Navi 状态
                    sendStatusToBus(cl, 27, ctx(context));
                    sendGuideToBus(cl, "测试路", "成功街", 1, 500, 0, 0, ctx(context));
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        // 注册 APP 的三个按钮指令
        filter.addAction(ACTION_TOGGLE_SNIFF);
        filter.addAction(ACTION_SEND_STATUS);
        filter.addAction(ACTION_SEND_GUIDE);

        // 注册高德的广播 (用于抓包)
        filter.addAction(ACTION_STD);
        filter.addAction(ACTION_SDK);
        filter.addAction("com.autonavi.minimap.search.SEND_BROADCAST");
        filter.addAction(ACTION_CAR);
        filter.addAction("com.autonavi.amapauto.broadcast.SEND");
        
        context.registerReceiver(receiver, filter);
    }

    // 辅助 context 获取
    private Context ctx(Context c) { return c; }

    // 🖨️ 抓包打印核心
    private void logAllExtras(Context ctx, Intent intent) {
        try {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                StringBuilder sb = new StringBuilder();
                String actName = intent.getAction();
                if(actName.contains(".")) actName = actName.substring(actName.lastIndexOf(".")+1);
                
                sb.append("\n📡 [").append(actName).append("]\n");
                
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
                    sendStatusToBus(cl, 25, ctx); 
                    new Thread(()->{
                        try{Thread.sleep(200);}catch(Exception e){}
                        sendStatusToBus(cl, 27, ctx);
                    }).start();
                } else if (state == 9 || state == 1) {
                    sendStatusToBus(cl, 26, ctx);
                }
            }
        } catch (Throwable t) {}
    }

    // 发送状态
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

    // 发送路口
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
