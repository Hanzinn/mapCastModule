package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import java.lang.reflect.Field;
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
    
    // Action 定义
    public static final String ACTION_LOG_UPDATE = "com.xsf.amaphelper.LOG_UPDATE";
    public static final String ACTION_TOGGLE_SNIFF = "com.xsf.amaphelper.TOGGLE_SNIFF";
    public static final String ACTION_SEND_STATUS = "XSF_ACTION_SEND_STATUS";
    public static final String ACTION_SEND_GUIDE = "XSF_ACTION_SEND_GUIDE";

    // 类名
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    
    // 监听高德广播 Action
    private static final String ACTION_STD = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final String ACTION_SDK = "com.autonavi.minimap.SEND_BROADCAST"; 
    private static final String ACTION_CAR = "com.autonavi.amapauto.SEND_BROADCAST";
    
    // 抓包开关
    private static boolean isSniffing = false; 
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        
        // Hook 自身：改变激活状态
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // Hook 目标服务：注入逻辑
        if (!lpparam.packageName.equals(PKG_XSF)) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Application app = (Application) param.thisObject;
                Context context = app.getApplicationContext();
                if (context != null) {
                    logSystem(context, "✅ 模块加载成功 (左右分治版)");
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

                // 1. 抓包开关
                if (ACTION_TOGGLE_SNIFF.equals(action)) {
                    isSniffing = !isSniffing;
                    logSystem(context, isSniffing ? "🟢 抓包开启 (左屏)" : "🔴 抓包停止");
                    return;
                }

                // 2. 执行抓包
                if (isSniffing) {
                    if (action.contains("autonavi") || action.contains("amap")) {
                        logSniff(context, intent); // 显示到左边
                        handleAmapStandardBroadcast(intent, cl, context); // 依然尝试转发
                    }
                }

                // 3. 激活指令
                if (ACTION_SEND_STATUS.equals(action)) {
                    int status = intent.getIntExtra("status", 0);
                    if (status == 13) {
                        logSystem(context, "🚀 发送唤醒序列 (1 -> 27)");
                        sendStatusToBus(cl, 1, ctx(context)); // 先发1
                        new Thread(()->{
                            try{Thread.sleep(300);}catch(Exception e){}
                            sendStatusToBus(cl, 27, ctx(context)); // 后发27
                        }).start();
                    } else {
                        sendStatusToBus(cl, status, ctx(context));
                    }
                }
                
                // 4. 路口/巡航指令
                if (ACTION_SEND_GUIDE.equals(action)) {
                    String type = intent.getStringExtra("type");
                    if ("cruise".equals(type)) {
                         logSystem(context, "🛳️ 发送巡航模拟");
                         sendStatusToBus(cl, 27, ctx(context));
                         sendGuideToBus(cl, "当前道路", "巡航模式", 1, 0, 0, 0, ctx(context));
                    } else {
                         logSystem(context, "🚗 发送路口模拟");
                         sendStatusToBus(cl, 27, ctx(context));
                         sendGuideToBus(cl, "测试路", "成功街", 2, 500, 0, 0, ctx(context));
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TOGGLE_SNIFF);
        filter.addAction(ACTION_SEND_STATUS);
        filter.addAction(ACTION_SEND_GUIDE);
        filter.addAction(ACTION_STD);
        filter.addAction(ACTION_SDK);
        filter.addAction("com.autonavi.minimap.search.SEND_BROADCAST");
        filter.addAction(ACTION_CAR);
        filter.addAction("com.autonavi.amapauto.broadcast.SEND");
        
        context.registerReceiver(receiver, filter);
    }

    private Context ctx(Context c) { return c; }

    // 左屏日志：抓包数据 (type=1)
    private void logSniff(Context ctx, Intent intent) {
        try {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                StringBuilder sb = new StringBuilder();
                String actName = intent.getAction();
                if(actName.contains(".")) actName = actName.substring(actName.lastIndexOf(".")+1);
                
                sb.append("📡 ").append(actName).append("\n");
                Set<String> keys = bundle.keySet();
                for (String key : keys) {
                    Object value = bundle.get(key);
                    sb.append("  ").append(key).append("=").append(value).append("\n");
                }
                sendLogBroadcast(ctx, sb.toString(), 1); 
            }
        } catch (Throwable t) {}
    }

    // 右屏日志：系统回执 (type=0)
    private void logSystem(Context ctx, String content) {
        XposedBridge.log(TAG + ": " + content);
        sendLogBroadcast(ctx, content, 0); 
    }

    private void sendLogBroadcast(Context ctx, String content, int type) {
        try {
            Intent intent = new Intent(ACTION_LOG_UPDATE);
            intent.putExtra("log", content);
            intent.putExtra("type", type);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES); 
            ctx.sendBroadcast(intent);
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
        } catch (Throwable t) { logSystem(ctx, "Status Err: " + t); }
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
        } catch (Throwable t) { logSystem(ctx, "Guide Err: " + t); }
    }
    
    private String getString(Intent i, String k1, String k2) { return (i.getStringExtra(k1) != null) ? i.getStringExtra(k1) : i.getStringExtra(k2); }
    private int getInt(Intent i, String k1, String k2) { return (i.getIntExtra(k1, -1) != -1) ? i.getIntExtra(k1, -1) : i.getIntExtra(k2, 0); }
}
