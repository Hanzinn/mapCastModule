package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.Set; // 必须导入
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_WIDGET = "com.ecarx.naviwidget";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    // 数据仓库
    private static String curRoadName = "等待高德...";
    private static String nextRoadName = "V59透视版";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;

    private static boolean isServiceHeartbeatRunning = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 🌟 策略分离：Service 全力干活，Widget 只负责旁听
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initServiceHook(lpparam);
        } else if (lpparam.packageName.equals(PKG_WIDGET)) {
            initWidgetPassiveMode(lpparam);
        }
    }

    // =============================================================
    // PART 1: Widget 进程 (完全被动模式 - 防崩)
    // =============================================================
    private void initWidgetPassiveMode(XC_LoadPackage.LoadPackageParam lpparam) {
        // 🚨 绝对不 Hook 任何 API，只注册广播看日志
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    registerReceiver(context, "Widget");
                    
                    // 3秒后报活
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                         reportStatus(context, "Widget", "BOOT");
                    }, 3000);
                }
            });
        } catch (Throwable t) {}
    }

    // =============================================================
    // PART 2: Service 进程 (核心工作区)
    // =============================================================
    private void initServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    registerReceiver(context, "LBSNavi");
                    
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                         reportStatus(context, "LBSNavi", "BOOT");
                    }, 3000);
                }
            });
        } catch (Throwable t) {}

        // 💉 API 注入只在 Service 里做
        hookApi(lpparam);
    }

    // =============================================================
    // 通用逻辑
    // =============================================================
    private void registerReceiver(Context context, String procName) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        String action = intent.getAction();
                        
                        if (AMAP_ACTION.equals(action)) {
                            // 链路打通，点灯
                            if (!isServiceHeartbeatRunning && procName.equals("LBSNavi")) {
                                startServiceHeartbeat(ctx);
                            }
                            reportStatus(ctx, procName, "LIVE");

                            Bundle b = intent.getExtras();
                            if (b != null) {
                                // 🌟 核心：强制拆解 Bundle 打印 Key-Value
                                // 只有这样我们才知道 7.5/9.1 到底发了什么
                                if (Math.random() < 0.2) { // 20%采样打印
                                    printBundleDetails(b, procName);
                                }
                                
                                extractData(b);
                                sendInternalWakeUp(ctx);
                            }
                        }
                        else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                            reportStatus(ctx, procName, "ECHO");
                        }
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            if (procName.equals("LBSNavi")) keepAliveAndGreen(ctx);
                            reportStatus(ctx, procName, "FORCE");
                        }
                    } catch (Throwable t) {}
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION);
            filter.addAction("XSF_ACTION_SEND_STATUS");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    // 🌟 透视 Bundle 内容
    private void printBundleDetails(Bundle b, String tag) {
        StringBuilder sb = new StringBuilder("🔍 [" + tag + "] 解密: ");
        try {
            Set<String> keys = b.keySet(); // 这行代码会强制 unparcel
            for (String key : keys) {
                Object value = b.get(key);
                sb.append(key).append("=").append(value).append("; ");
            }
            XposedBridge.log(sb.toString());
        } catch (Exception e) {
            XposedBridge.log("🔍 [" + tag + "] 解析失败: " + e);
        }
    }

    private void extractData(Bundle b) {
        // 广撒网提取
        String road = b.getString("CUR_ROAD_NAME");
        if (road == null) road = b.getString("cur_road_name");
        if (road == null) road = b.getString("ROAD_NAME"); 
        if (road != null) curRoadName = road;

        String next = b.getString("NEXT_ROAD_NAME");
        if (next == null) next = b.getString("next_road_name");
        if (next != null) nextRoadName = next;

        segmentDis = getIntFromBundle(b, "SEG_REMAIN_DIS", "seg_remain_dis", "DISTANCE");
        turnIcon = getIntFromBundle(b, "ICON", "icon", null);
        routeRemainDis = getIntFromBundle(b, "ROUTE_REMAIN_DIS", "route_remain_dis", null);
        routeRemainTime = getIntFromBundle(b, "ROUTE_REMAIN_TIME", "route_remain_time", null);
    }
    
    private int getIntFromBundle(Bundle b, String k1, String k2, String k3) {
        int v = b.getInt(k1, -1);
        if (v == -1) v = b.getInt(k2, -1);
        if (v == -1 && k3 != null) v = b.getInt(k3, -1);
        return (v == -1) ? 0 : v;
    }

    private void sendInternalWakeUp(Context ctx) {
        // 唤醒指令
        Intent iRefresh = new Intent("ecarx.navi.REFRESH_WIDGET");
        iRefresh.setPackage(PKG_WIDGET);
        ctx.sendBroadcast(iRefresh);
        
        Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
        iStatus.putExtra("status", 1); 
        iStatus.putExtra("is_navi", true);
        iStatus.putExtra("vendor", 2);
        iStatus.setPackage(PKG_WIDGET); 
        ctx.sendBroadcast(iStatus);
    }

    private void hookApi(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> apiClass = XposedHelpers.findClassIfExists("com.neusoft.nts.ecarxnavsdk.EcarxOpenApi", lpparam.classLoader);
            if (apiClass == null) return;
            
            Class<?> cbClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.IAPIGetGuideInfoCallBack", lpparam.classLoader);
            
            XposedHelpers.findAndHookMethod(apiClass, "getGuideInfo", cbClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object callback = param.args[0];
                    if (callback != null) {
                        String safeNext = (nextRoadName == null) ? "" : nextRoadName;
                        String safeCur = (curRoadName == null) ? "" : curRoadName;
                        
                        XposedHelpers.callMethod(callback, "getGuideInfoResult",
                            1, routeRemainDis, routeRemainTime, 0, 0, 0,
                            safeNext, safeNext, 
                            0.5f, 0, segmentDis, turnIcon, 
                            safeCur, routeRemainDis, routeRemainTime, 0, 0
                        );
                    }
                }
            });
        } catch (Throwable t) {}
    }
    
    private void startServiceHeartbeat(Context ctx) {
        isServiceHeartbeatRunning = true;
        new Thread(() -> {
            while (isServiceHeartbeatRunning) {
                try {
                    keepAliveAndGreen(ctx);
                    Thread.sleep(5000);
                } catch (Exception e) { break; }
            }
        }).start();
    }

    private void keepAliveAndGreen(Context ctx) {
        try {
            Class<?> q = XposedHelpers.findClassIfExists("q", ctx.getClassLoader());
            if (q == null) return;
            Object mgr = XposedHelpers.getStaticObjectField(q, "a");
            if (mgr == null) {
                Class<?> l = XposedHelpers.findClassIfExists("l", ctx.getClassLoader());
                if (l != null) {
                    mgr = XposedHelpers.newInstance(l);
                    XposedHelpers.setStaticObjectField(q, "a", mgr);
                }
            }
            if (mgr != null) {
                Object conn = XposedHelpers.getObjectField(mgr, "i");
                if (conn != null) {
                    XposedHelpers.callMethod(conn, "onServiceConnected", new ComponentName("f","f"), null);
                }
            }
        } catch (Throwable t) {}
    }

    private void reportStatus(Context ctx, String procName, String type) {
        if (procName.equals("Widget")) sendAppLog(ctx, "STATUS_WIDGET_READY");
        if (procName.equals("LBSNavi")) {
            sendAppLog(ctx, "STATUS_HOOK_READY");
            sendAppLog(ctx, "STATUS_SERVICE_RUNNING");
            if (type.equals("FORCE")) sendAppLog(ctx, "STATUS_IPC_CONNECTED");
        }
    }

    private void sendAppLog(Context ctx, String log) {
        try {
            Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
            i.setPackage(PKG_SELF);
            i.putExtra("log", log);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {}
    }
}
