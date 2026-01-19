package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "LSPosed_Navi";
    // 目标包名 (必须完全匹配车机实际包名)
    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // 日志回传广播
    public static final String ACTION_LOG_UPDATE = "com.xsf.amaphelper.LOG_UPDATE";

    // 🔴 修正：去掉了开头的 'L'，这是导致之前无反应的根本原因
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    
    private static final String ACTION_AMAP_STANDARD = "AUTONAVI_STANDARD_BROADCAST_SEND";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        
        // 1. Hook 自己的 APP (保持显示已激活)
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 2. Hook 目标服务
        if (!lpparam.packageName.equals(PKG_XSF)) return;

        XposedBridge.log(TAG + ": 发现目标进程，准备注入: " + lpparam.packageName);

        // Hook Application onCreate 以获取 Context
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Application app = (Application) param.thisObject;
                Context context = app.getApplicationContext();
                
                if (context != null) {
                    // 发送第一条成功日志 (证明 Hook 成功注入)
                    logProxy(context, "✅ Hook 成功注入！包名匹配！");
                    logProxy(context, "正在检测类定义...");
                    
                    // 检查类是否存在 (调试用)
                    checkClassExist(lpparam.classLoader, context, CLS_BUS);
                    
                    // 注册广播接收器
                    registerCombinedReceiver(context, lpparam.classLoader);
                }
            }
        });
    }

    // 辅助检查类是否存在
    private void checkClassExist(ClassLoader cl, Context ctx, String className) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            logProxy(ctx, "✅ 找到类: " + className);
        } catch (Throwable t) {
            logProxy(ctx, "❌ 找不到类: " + className + "\n(严重错误: 请截图发给开发者)");
        }
    }

    private void registerCombinedReceiver(Context context, ClassLoader cl) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                if (ACTION_AMAP_STANDARD.equals(action)) {
                    // 收到高德广播，处理逻辑
                    handleAmapStandardBroadcast(intent, cl, context);
                } 
                else if ("XSF_ACTION_SEND_GUIDE".equals(action)) {
                    logProxy(context, "收到APP指令: 发送路口数据");
                    handleAdbGuide(intent, cl, context);
                } else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                    logProxy(context, "收到APP指令: 切换状态");
                    handleAdbStatus(intent, cl, context);
                } 
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_AMAP_STANDARD);
        filter.addAction("XSF_ACTION_SEND_GUIDE");
        filter.addAction("XSF_ACTION_SEND_STATUS");
        
        context.registerReceiver(receiver, filter);
        logProxy(context, "监听器就绪，等待高德启动...");
    }

    // --- 核心逻辑：处理高德广播 (含强制巡航) ---
    private void handleAmapStandardBroadcast(Intent intent, ClassLoader cl, Context ctx) {
        try {
            int keyType = intent.getIntExtra("KEY_TYPE", 0);
            if (keyType == 0) keyType = intent.getIntExtra("key_type", 0);
            if (keyType == 0) keyType = intent.getIntExtra("EXTRA_TYPE", 0);

            // === 情况 1：正在导航 (有路口信息) ===
            if (keyType == 10001) {
                // 不怎么打印这个日志，免得刷屏太快
                // logProxy(ctx, ">> 更新导航路口信息"); 
                sendStatusToBus(cl, 13, ctx); // 13 = 导航中
                
                String curRoad = getString(intent, "CUR_ROAD_NAME", "cur_road_name");
                String nextRoad = getString(intent, "NEXT_ROAD_NAME", "next_road_name");
                int icon = getInt(intent, "ICON", "icon");
                int distance = getInt(intent, "SEG_REMAIN_DIS", "seg_remain_dis");
                if (distance == 0) distance = getInt(intent, "distance", "distance");
                int routeRemainDis = getInt(intent, "ROUTE_REMAIN_DIS", "route_remain_dis");
                int routeRemainTime = getInt(intent, "ROUTE_REMAIN_TIME", "route_remain_time");

                sendGuideToBus(cl, curRoad, nextRoad, icon, distance, routeRemainDis, routeRemainTime, ctx);
            } 
            
            // === 情况 2：状态变更 (这里改了逻辑！) ===
            else if (keyType == 10019) {
                int state = getInt(intent, "EXTRA_STATE", "extra_state");
                logProxy(ctx, ">> 高德状态变更: " + state);
                
                // 策略：只要高德没死 (State 0 或 2)，就强制显示地图
                if (state == 2 || state == 0) { 
                    logProxy(ctx, ">> 强制保持显示 (伪装巡航模式)");
                    
                    sendStatusToBus(cl, 13, ctx); // 告诉仪表：我在导航，别关屏幕
                    
                    sendGuideToBus(cl, 
                        "地图已连接",   // 当前路名
                        "巡航模式",     // 下一路名
                        1,              // 直行图标
                        0,              // 距离
                        0,              // 剩余距离
                        0,              // 剩余时间
                        ctx
                    );
                } 
                // 只有收到退出信号 (9) 或者后台停止 (1) 才真正关闭
                else if (state == 9 || state == 1) {
                    logProxy(ctx, ">> 高德退出，关闭仪表显示");
                    sendStatusToBus(cl, 29, ctx); // 29 = 停止/退出
                }
            }
        } catch (Throwable t) { 
            logProxy(ctx, "逻辑错误: " + t.getMessage());
        }
    }

    // --- 日志代理 ---
    private void logProxy(Context context, String logContent) {
        XposedBridge.log(TAG + ": " + logContent);
        try {
            Intent intent = new Intent(ACTION_LOG_UPDATE);
            intent.putExtra("log", logContent);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES); 
            context.sendBroadcast(intent);
        } catch (Throwable t) {}
    }

    private String getBundleString(Intent intent) {
        // 省略详细打印，节省性能
        return "KeyType=" + intent.getIntExtra("KEY_TYPE", 0);
    }

    // --- 辅助工具 ---
    private String getString(Intent i, String k1, String k2) {
        String s = i.getStringExtra(k1);
        return (s != null) ? s : i.getStringExtra(k2);
    }
    private int getInt(Intent i, String k1, String k2) {
        int v = i.getIntExtra(k1, -1);
        return (v != -1) ? v : i.getIntExtra(k2, 0);
    }

    // --- 手动测试逻辑 ---
    private void handleAdbGuide(Intent intent, ClassLoader cl, Context ctx) {
        String cur = intent.getStringExtra("curRoad");
        if ("cruise_test".equals(cur)) {
             sendStatusToBus(cl, 13, ctx);
             sendGuideToBus(cl, "当前道路", "巡航中", 1, 1, 1, 60, ctx);
             return;
        }
        String next = intent.getStringExtra("nextRoad");
        int icon = intent.getIntExtra("icon", 1);
        int dist = intent.getIntExtra("distance", 0);
        sendGuideToBus(cl, cur, next, icon, dist, 0, 0, ctx);
    }
    private void handleAdbStatus(Intent intent, ClassLoader cl, Context ctx) {
        int status = intent.getIntExtra("status", 0);
        sendStatusToBus(cl, status, ctx);
    }

    // --- 底层反射发送 ---
    private void sendGuideToBus(ClassLoader cl, String cur, String next, int icon, int dist, int totalDist, int totalTime, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            Class<?> guideClass = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object guideInfo = XposedHelpers.newInstance(guideClass, 1);

            XposedHelpers.callMethod(guideInfo, "setCurRoadName", cur);
            XposedHelpers.callMethod(guideInfo, "setNextRoadName", next);
            XposedHelpers.callMethod(guideInfo, "setTurnId", icon); 
            XposedHelpers.callMethod(guideInfo, "setNextTurnDistance", dist);
            XposedHelpers.callMethod(guideInfo, "setRemainDistance", totalDist);
            XposedHelpers.callMethod(guideInfo, "setRemainTime", totalTime);

            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d0, guideInfo); 
            XposedHelpers.callMethod(busInstance, "a", msg);
        } catch (Throwable t) { 
            logProxy(ctx, "反射错误(Guide): " + t.getMessage());
        }
    }
    private void sendStatusToBus(ClassLoader cl, int status, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            Class<?> statusClass = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            Object statusObj = XposedHelpers.newInstance(statusClass, 1);
            XposedHelpers.callMethod(statusObj, "setStatus", status);
            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d2, statusObj); 
            XposedHelpers.callMethod(busInstance, "a", msg);
        } catch (Throwable t) { 
            logProxy(ctx, "反射错误(Status): " + t.getMessage());
        }
    }
}
