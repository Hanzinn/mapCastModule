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

    // ⬇️ 之前测试证明完全正确的类名 ⬇️
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    
    // 🔴 必须改回 MapStatusInfo，日志证明 j 是不存在的！
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
                    logProxy(context, "✅ Hook 注入成功 (Status修正版)");
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
                    return;
                }
                if (ACTION_AMAP_STANDARD.equals(action)) handleAmapStandardBroadcast(intent, cl, context);
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
        try {
            int keyType = intent.getIntExtra("KEY_TYPE", 0);
            if (keyType == 0) keyType = intent.getIntExtra("key_type", 0);
            if (keyType == 0) keyType = intent.getIntExtra("EXTRA_TYPE", 0);

            if (keyType == 10001) {
                // 收到路口信息，发 27 维持状态
                sendStatusToBus(cl, 27, ctx); 
                String cur = getString(intent, "CUR_ROAD_NAME", "cur_road_name");
                String next = getString(intent, "NEXT_ROAD_NAME", "next_road_name");
                int icon = getInt(intent, "ICON", "icon");
                int dist = getInt(intent, "SEG_REMAIN_DIS", "seg_remain_dis");
                if (dist == 0) dist = getInt(intent, "distance", "distance");
                int tDist = getInt(intent, "ROUTE_REMAIN_DIS", "route_remain_dis");
                int tTime = getInt(intent, "ROUTE_REMAIN_TIME", "route_remain_time");
                sendGuideToBus(cl, cur, next, icon, dist, tDist, tTime, ctx);
            } else if (keyType == 10019) {
                int state = getInt(intent, "EXTRA_STATE", "extra_state");
                // 强制显示 (State 0, 2 -> Status 27)
                if (state == 2 || state == 0) { 
                    sendStatusToBus(cl, 27, ctx); 
                    sendGuideToBus(cl, "地图已连接", "巡航模式", 1, 0, 0, 0, ctx);
                } else if (state == 9 || state == 1) {
                    sendStatusToBus(cl, 29, ctx); 
                }
            }
        } catch (Throwable t) { logProxy(ctx, "LogicErr: " + t.getMessage()); }
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

    // =======================================================
    // 🔴 状态发送逻辑 (已修正类名) 🔴
    // =======================================================
    
    private void sendStatusToBus(ClassLoader cl, int status, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            
            // 使用 MapStatusInfo
            Class<?> statusClass = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            Object statusObj = null;
            
            // 构造函数：优先尝试带参数 2，失败则用无参
            try { statusObj = XposedHelpers.newInstance(statusClass, 2); }
            catch (Throwable t) { statusObj = XposedHelpers.newInstance(statusClass); }

            // 智能赋值 (查找类里唯一的 int 变量)
            Field field = XposedHelpers.findFirstFieldByExactType(statusClass, int.class);
            if (field != null) {
                field.setAccessible(true);
                field.setInt(statusObj, status);
            } else {
                logProxy(ctx, "❌ 严重: StatusInfo里没有int变量");
                return;
            }
            
            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d2, statusObj); 
            XposedHelpers.callMethod(busInstance, "a", msg);
            
            // 成功日志
            // logProxy(ctx, "✅ 状态发送: " + status); 
        } catch (Throwable t) { 
            logProxy(ctx, "Status Error: " + t.toString()); 
        }
    }

    private void sendGuideToBus(ClassLoader cl, String cur, String next, int icon, int dist, int totalDist, int totalTime, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            Class<?> guideClass = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            
            Object guideInfo = null;
            try { guideInfo = XposedHelpers.newInstance(guideClass, 2); }
            catch (Throwable t) { guideInfo = XposedHelpers.newInstance(guideClass); }

            // 赋值
            XposedHelpers.setObjectField(guideInfo, "curRoadName", cur);
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", next);
            XposedHelpers.setIntField(guideInfo, "turnId", icon); 
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", dist);
            XposedHelpers.setIntField(guideInfo, "remainDistance", totalDist);
            XposedHelpers.setIntField(guideInfo, "remainTime", totalTime);
            
            // 填充防空字段
            trySetInt(guideInfo, "guideType", 0);
            trySetInt(guideInfo, "roadType", 1);
            
            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d0, guideInfo); 
            XposedHelpers.callMethod(busInstance, "a", msg);
            
            // 日志确认
            logProxy(ctx, "✅ 路口: " + cur + " -> " + next);
        } catch (Throwable t) { 
            logProxy(ctx, "Guide Error: " + t.toString()); 
        }
    }
    
    private void trySetInt(Object obj, String fieldName, int val) { try { XposedHelpers.setIntField(obj, fieldName, val); } catch (Throwable t) {} }
    
    // --- 手动测试响应 ---
    private void handleAdbGuide(Intent intent, ClassLoader cl, Context ctx) {
        String cur = intent.getStringExtra("curRoad");
        
        // 点击“模拟巡航”按钮
        if ("cruise_test".equals(cur)) { 
            // 🔴 唤醒序列：先发1(Start)，再发27(Navi)
            sendStatusToBus(cl, 1, ctx);
            try { Thread.sleep(200); } catch (InterruptedException e) {}
            sendStatusToBus(cl, 27, ctx);
            
            sendGuideToBus(cl, "当前道路", "巡航中", 1, 1, 1, 60, ctx);
            logProxy(ctx, "🚀 唤醒指令已发送 (1 -> 27)");
            return;
        }
        
        sendGuideToBus(cl, cur, intent.getStringExtra("nextRoad"), intent.getIntExtra("icon", 1), intent.getIntExtra("distance", 0), 0, 0, ctx);
    }
    
    private void handleAdbStatus(Intent intent, ClassLoader cl, Context ctx) { 
        int status = intent.getIntExtra("status", 0);
        // 点击“激活导航”按钮 (默认发13)，拦截改为唤醒序列
        if (status == 13) {
            sendStatusToBus(cl, 1, ctx);
            try { Thread.sleep(200); } catch (InterruptedException e) {}
            sendStatusToBus(cl, 27, ctx);
            logProxy(ctx, "🚀 激活指令已优化 (1 -> 27)");
        } else {
            sendStatusToBus(cl, status, ctx); 
        }
    }
}
