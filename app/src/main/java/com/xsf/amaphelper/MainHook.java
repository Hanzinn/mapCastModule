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
    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    public static final String ACTION_LOG_UPDATE = "com.xsf.amaphelper.LOG_UPDATE";
    public static final String ACTION_PING = "com.xsf.amaphelper.PING";
    public static final String ACTION_PONG = "com.xsf.amaphelper.PONG";

    // ⬇️⬇️⬇️ 最终确认的混淆类名 ⬇️⬇️⬇️
    // 总线类
    private static final String CLS_BUS = "ecarx.naviservice.b.b";
    // 信封类 (Wrapper)
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.ck";
    
    // 实体类
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo"; // 这个没混淆
    // 🔴 修正：状态类混淆名为 j
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.j";
    
    private static final String ACTION_AMAP_STANDARD = "AUTONAVI_STANDARD_BROADCAST_SEND";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_XSF)) return;

        XposedBridge.log(TAG + ": 发现目标进程: " + lpparam.packageName);

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Application app = (Application) param.thisObject;
                Context context = app.getApplicationContext();
                if (context != null) {
                    logProxy(context, "✅ Hook 注入成功! (PID: " + android.os.Process.myPid() + ")");
                    
                    // 验证类是否存在
                    checkClassExist(lpparam.classLoader, context, CLS_BUS);
                    checkClassExist(lpparam.classLoader, context, CLS_STATUS_INFO);
                    
                    registerCombinedReceiver(context, lpparam.classLoader);
                }
            }
        });
    }

    private void checkClassExist(ClassLoader cl, Context ctx, String className) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            logProxy(ctx, "✅ 找到类: " + className);
        } catch (Throwable t) {
            logProxy(ctx, "❌ 找不到类: " + className);
        }
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

                if (ACTION_AMAP_STANDARD.equals(action)) {
                    handleAmapStandardBroadcast(intent, cl, context);
                } 
                else if ("XSF_ACTION_SEND_GUIDE".equals(action)) {
                    logProxy(context, "收到APP指令: 路口测试");
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
        filter.addAction(ACTION_PING);
        
        context.registerReceiver(receiver, filter);
        logProxy(context, "监听就绪，等待高德数据...");
    }

    private void handleAmapStandardBroadcast(Intent intent, ClassLoader cl, Context ctx) {
        try {
            int keyType = intent.getIntExtra("KEY_TYPE", 0);
            if (keyType == 0) keyType = intent.getIntExtra("key_type", 0);
            if (keyType == 0) keyType = intent.getIntExtra("EXTRA_TYPE", 0);

            if (keyType == 10001) {
                sendStatusToBus(cl, 13, ctx); 
                String curRoad = getString(intent, "CUR_ROAD_NAME", "cur_road_name");
                String nextRoad = getString(intent, "NEXT_ROAD_NAME", "next_road_name");
                int icon = getInt(intent, "ICON", "icon");
                int distance = getInt(intent, "SEG_REMAIN_DIS", "seg_remain_dis");
                if (distance == 0) distance = getInt(intent, "distance", "distance");
                int routeRemainDis = getInt(intent, "ROUTE_REMAIN_DIS", "route_remain_dis");
                int routeRemainTime = getInt(intent, "ROUTE_REMAIN_TIME", "route_remain_time");
                sendGuideToBus(cl, curRoad, nextRoad, icon, distance, routeRemainDis, routeRemainTime, ctx);
            } 
            else if (keyType == 10019) {
                int state = getInt(intent, "EXTRA_STATE", "extra_state");
                // 强制显示地图 (State 0 或 2)
                if (state == 2 || state == 0) { 
                    sendStatusToBus(cl, 13, ctx); 
                    sendGuideToBus(cl, "地图已连接", "巡航模式", 1, 0, 0, 0, ctx);
                } else if (state == 9 || state == 1) {
                    logProxy(ctx, ">> 结束显示");
                    sendStatusToBus(cl, 29, ctx); 
                }
            }
        } catch (Throwable t) { logProxy(ctx, "Err: " + t.getMessage()); }
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
    // 🔴 发送核心区 (精准反射版) 🔴
    // =======================================================

    private void sendGuideToBus(ClassLoader cl, String cur, String next, int icon, int dist, int totalDist, int totalTime, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            
            // 导航信息类 (MapGuideInfo)
            Class<?> guideClass = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object guideInfo = XposedHelpers.newInstance(guideClass, 1);

            // 直接赋值给变量 (变量名未混淆)
            XposedHelpers.setObjectField(guideInfo, "curRoadName", cur);
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", next);
            XposedHelpers.setIntField(guideInfo, "turnId", icon); 
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", dist);
            XposedHelpers.setIntField(guideInfo, "remainDistance", totalDist);
            XposedHelpers.setIntField(guideInfo, "remainTime", totalTime);

            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            // ck 的构造函数是 (int, Object)
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d0, guideInfo); 
            
            XposedHelpers.callMethod(busInstance, "a", msg);
        } catch (Throwable t) { 
            logProxy(ctx, "Guide Error: " + t.toString()); 
        }
    }
    
    private void handleAdbGuide(Intent intent, ClassLoader cl, Context ctx) {
        String cur = intent.getStringExtra("curRoad");
        if ("cruise_test".equals(cur)) { sendStatusToBus(cl, 13, ctx); sendGuideToBus(cl, "当前道路", "巡航中", 1, 1, 1, 60, ctx); return; }
        sendGuideToBus(cl, cur, intent.getStringExtra("nextRoad"), intent.getIntExtra("icon", 1), intent.getIntExtra("distance", 0), 0, 0, ctx);
    }
    private void handleAdbStatus(Intent intent, ClassLoader cl, Context ctx) { sendStatusToBus(cl, intent.getIntExtra("status", 0), ctx); }

    private void sendStatusToBus(ClassLoader cl, int status, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            
            // 🔴 状态类 (混淆名: j)
            Class<?> statusClass = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            Object statusObj = XposedHelpers.newInstance(statusClass);

            // 🔴 字段赋值 (混淆字段名: a)
            // 之前报错是因为我们找 status，实际它叫 a
            XposedHelpers.setIntField(statusObj, "a", status);
            
            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            // 构造消息 0x7d2
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d2, statusObj); 
            
            XposedHelpers.callMethod(busInstance, "a", msg);
            
            logProxy(ctx, "✅ 状态发送成功 (Status=" + status + ")");
        } catch (Throwable t) { 
            logProxy(ctx, "Status Error: " + t.toString()); 
        }
    }
}
