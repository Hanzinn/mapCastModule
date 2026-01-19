package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import java.lang.reflect.Field;

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
    public static final String ACTION_PING = "com.xsf.amaphelper.PING";
    public static final String ACTION_PONG = "com.xsf.amaphelper.PONG";

    // ⬇️ 已验证正确的类名
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    
    private static final String ACTION_AMAP_STANDARD = "AUTONAVI_STANDARD_BROADCAST_SEND";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_XSF)) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Application app = (Application) param.thisObject;
                Context context = app.getApplicationContext();
                if (context != null) {
                    logProxy(context, "✅ Hook 注入成功 (轰炸测试版)");
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
                if (ACTION_PING.equals(action)) {
                    Intent pong = new Intent(ACTION_PONG);
                    pong.putExtra("pid", android.os.Process.myPid());
                    pong.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    context.sendBroadcast(pong);
                }
                else if (ACTION_AMAP_STANDARD.equals(action)) handleAmapStandardBroadcast(intent, cl, context);
                else if ("XSF_ACTION_SEND_GUIDE".equals(action)) handleAdbGuide(intent, cl, context);
                else if ("XSF_ACTION_SEND_STATUS".equals(action)) handleAdbStatus(intent, cl, context);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_AMAP_STANDARD);
        filter.addAction("XSF_ACTION_SEND_GUIDE");
        filter.addAction("XSF_ACTION_SEND_STATUS");
        filter.addAction(ACTION_PING);
        context.registerReceiver(receiver, filter);
        logProxy(context, "监听就绪...");
    }

    private void handleAmapStandardBroadcast(Intent intent, ClassLoader cl, Context ctx) {
        // 保持静默，以免自动逻辑干扰手动测试
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

    // =======================================================
    // 🔴 核心发送逻辑 🔴
    // =======================================================
    
    // 参数 type: 构造函数传几 (0, 1, 2)
    // 参数 status: 状态码 (13, 27, 1...)
    private void sendStatusToBus(ClassLoader cl, int type, int status, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            Class<?> statusClass = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            
            // 尝试不同的构造参数
            Object statusObj = null;
            try { statusObj = XposedHelpers.newInstance(statusClass, type); }
            catch (Throwable t) { statusObj = XposedHelpers.newInstance(statusClass); }

            Field field = XposedHelpers.findFirstFieldByExactType(statusClass, int.class);
            if (field != null) {
                field.setAccessible(true);
                field.setInt(statusObj, status);
            }
            
            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d2, statusObj); 
            XposedHelpers.callMethod(busInstance, "a", msg);
            
            logProxy(ctx, "👉 尝试: 构造(" + type + ") + 状态(" + status + ")");
        } catch (Throwable t) { 
            logProxy(ctx, "Status Error: " + t.toString()); 
        }
    }

    private void sendGuideToBus(ClassLoader cl, String cur, String next, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            Class<?> guideClass = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            
            // 尝试构造参数 2
            Object guideInfo = null;
            try { guideInfo = XposedHelpers.newInstance(guideClass, 2); }
            catch (Throwable t) { guideInfo = XposedHelpers.newInstance(guideClass); }

            // 基础信息
            XposedHelpers.setObjectField(guideInfo, "curRoadName", cur);
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", next);
            XposedHelpers.setIntField(guideInfo, "turnId", 2); // 左转图标
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", 500);
            
            // 关键填充
            trySetInt(guideInfo, "guideType", 0);
            trySetInt(guideInfo, "roadType", 1);
            
            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d0, guideInfo); 
            XposedHelpers.callMethod(busInstance, "a", msg);
        } catch (Throwable t) { logProxy(ctx, "Guide Error: " + t.toString()); }
    }
    
    private void trySetInt(Object obj, String fieldName, int val) { try { XposedHelpers.setIntField(obj, fieldName, val); } catch (Throwable t) {} }
    
    // --- 暴力测试 ---
    private void handleAdbStatus(Intent intent, ClassLoader cl, Context ctx) { 
        // 点击“激活导航”时触发
        logProxy(ctx, "🔥 开始暴力轰炸测试...");
        
        new Thread(() -> {
            // 1. 尝试常见的构造参数 1 (部分老车型)
            sendStatusToBus(cl, 1, 1, ctx);  // Start
            try { Thread.sleep(300); } catch(Exception e){}
            sendStatusToBus(cl, 1, 13, ctx); // Navi (老)
            
            try { Thread.sleep(500); } catch(Exception e){}
            
            // 2. 尝试构造参数 2 (新车型)
            sendStatusToBus(cl, 2, 1, ctx);  // Start
            try { Thread.sleep(300); } catch(Exception e){}
            sendStatusToBus(cl, 2, 27, ctx); // Navi (新)
            
            try { Thread.sleep(500); } catch(Exception e){}
            
            // 3. 尝试其他可能的状态码
            sendStatusToBus(cl, 2, 10, ctx); 
            sendStatusToBus(cl, 2, 25, ctx); 

            logProxy(ctx, "🔥 轰炸结束，请观察仪表盘是否有反应");
        }).start();
    }
    
    private void handleAdbGuide(Intent intent, ClassLoader cl, Context ctx) {
        // 点击“路口测试”或“模拟巡航”
        logProxy(ctx, "🚗 发送路口数据...");
        sendGuideToBus(cl, "测试道路", "成功大道", ctx);
    }
}
