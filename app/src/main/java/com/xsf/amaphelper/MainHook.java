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

    // ⬇️⬇️⬇️ 最终确定的正确名单 ⬇️⬇️⬇️
    // 1. 总线：d.e (路口测试不报错，说明它是对的)
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    
    // 2. 信封：bz (路口测试不报错，说明它是对的)
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz";
    
    // 3. 实体类：MapStatusInfo (最早的日志证明它存在)
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
                    logProxy(context, "✅ Hook 注入成功! PID=" + android.os.Process.myPid());
                    // 再次确认类是否存在
                    checkClass(lpparam.classLoader, context, CLS_BUS);
                    checkClass(lpparam.classLoader, context, CLS_WRAPPER);
                    checkClass(lpparam.classLoader, context, CLS_STATUS_INFO);
                    registerCombinedReceiver(context, lpparam.classLoader);
                }
            }
        });
    }

    private void checkClass(ClassLoader cl, Context ctx, String name) {
        try {
            XposedHelpers.findClass(name, cl);
            logProxy(ctx, "✅ 找到类: " + name);
        } catch (Throwable t) {
            logProxy(ctx, "⚠️ 未找到类: " + name);
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
        logProxy(context, "监听就绪 (d.e | bz | MapStatusInfo)...");
    }

    private void handleAmapStandardBroadcast(Intent intent, ClassLoader cl, Context ctx) {
        try {
            int keyType = intent.getIntExtra("KEY_TYPE", 0);
            if (keyType == 0) keyType = intent.getIntExtra("key_type", 0);
            if (keyType == 0) keyType = intent.getIntExtra("EXTRA_TYPE", 0);

            if (keyType == 10001) {
                sendStatusToBus(cl, 13, ctx); 
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
                if (state == 2 || state == 0) { 
                    sendStatusToBus(cl, 13, ctx); 
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
    // 🔴 智能状态发送 (修复 Status 报错) 🔴
    // =======================================================
    
    private void sendStatusToBus(ClassLoader cl, int status, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            
            // 1. 获取 MapStatusInfo (肯定存在)
            Class<?> statusClass = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            Object statusObj = null;
            
            // 2. 尝试实例化 (有的版本需要参数，有的不需要)
            try { statusObj = XposedHelpers.newInstance(statusClass); }
            catch (Throwable t) { statusObj = XposedHelpers.newInstance(statusClass, 1); }

            // 3. 🔴 智能赋值：找到类里唯一的 int 变量
            // 你的 smali 显示它有 setStatus 方法，但反射说没有
            // 那我们就直接找 int 变量，不管它叫 a 还是 status
            Field field = XposedHelpers.findFirstFieldByExactType(statusClass, int.class);
            if (field != null) {
                field.setAccessible(true);
                field.setInt(statusObj, status);
            } else {
                logProxy(ctx, "❌ 严重异常: MapStatusInfo 里居然没有 int 变量？");
                return;
            }
            
            // 4. 打包发送 (使用 bz)
            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d2, statusObj); 
            XposedHelpers.callMethod(busInstance, "a", msg);
            
            logProxy(ctx, "✅ 状态发送成功! (Status=" + status + ")");
        } catch (Throwable t) { 
            logProxy(ctx, "Status Error: " + t.toString()); 
        }
    }

    private void sendGuideToBus(ClassLoader cl, String cur, String next, int icon, int dist, int totalDist, int totalTime, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            
            Class<?> guideClass = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object guideInfo = XposedHelpers.newInstance(guideClass, 1);
            
            // 这里用变量名赋值，因为之前的测试证明路口信息没报错
            XposedHelpers.setObjectField(guideInfo, "curRoadName", cur);
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", next);
            XposedHelpers.setIntField(guideInfo, "turnId", icon); 
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", dist);
            XposedHelpers.setIntField(guideInfo, "remainDistance", totalDist);
            XposedHelpers.setIntField(guideInfo, "remainTime", totalTime);
            
            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d0, guideInfo); 
            
            XposedHelpers.callMethod(busInstance, "a", msg);
        } catch (Throwable t) { 
            // 这里的错误会被打印出来，但之前日志没有，说明这里已经通了
            logProxy(ctx, "Guide Error: " + t.toString()); 
        }
    }
    
    private void handleAdbGuide(Intent intent, ClassLoader cl, Context ctx) {
        String cur = intent.getStringExtra("curRoad");
        if ("cruise_test".equals(cur)) { sendStatusToBus(cl, 13, ctx); sendGuideToBus(cl, "当前道路", "巡航中", 1, 1, 1, 60, ctx); return; }
        sendGuideToBus(cl, cur, intent.getStringExtra("nextRoad"), intent.getIntExtra("icon", 1), intent.getIntExtra("distance", 0), 0, 0, ctx);
    }
    private void handleAdbStatus(Intent intent, ClassLoader cl, Context ctx) { sendStatusToBus(cl, intent.getIntExtra("status", 0), ctx); }
}
