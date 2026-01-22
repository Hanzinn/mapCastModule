package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    
    // 🌟 补回缺失的权限定义
    private static final String PERMISSION_NAVI = "ecarx.oem.permission.OPENAPI_NAVI_PERMISSION";
    
    // 📜 协议定义
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final int KEY_TYPE_NAVI = 10001;  // 导航引导信息
    private static final int KEY_TYPE_CRUISE = 10019; // 巡航/位置信息

    // 🌟 数据仓库
    private static String curRoadName = "等待数据...";
    private static String nextRoadName = "系统待机";
    private static int turnIcon = 2; // 直行/默认
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;
    
    // ⚙️ 控制变量
    private static boolean isServiceHeartbeatRunning = false;
    private static int widgetLogCount = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        if (lpparam.packageName.equals(PKG_WIDGET)) {
            initNaviWidgetBridgeHook(lpparam);
        }
    }

    // =============================================================
    // PART 1: Widget 进程 (显示端)
    // =============================================================
    private void initNaviWidgetBridgeHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    registerWidgetReceiver(context);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Widget Hook Error: " + t);
        }

        hookEcarxOpenApiWithRealData(lpparam);
    }

    private void registerWidgetReceiver(Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        String action = intent.getAction();
                        
                        if (AMAP_ACTION.equals(action)) {
                            int keyType = intent.getIntExtra("KEY_TYPE", 0);
                            
                            // 🌟 核心逻辑：大小通吃，只要有数据就唤醒
                            if (keyType == KEY_TYPE_NAVI) {
                                parseNaviInfo(intent);
                                sendInternalWakeUp(ctx); // 唤醒！
                                logData(ctx, "⚡ [导航] " + curRoadName);
                            } 
                            else if (keyType == KEY_TYPE_CRUISE) {
                                parseCruiseInfo(intent);
                                sendInternalWakeUp(ctx); // 唤醒！
                                logData(ctx, "🛳️ [巡航] " + curRoadName);
                            }
                            else {
                                // 收到其他未知数据，也可以作为心跳
                                if (widgetLogCount % 50 == 0) {
                                    sendAppLog(ctx, "🔍 收到其他广播 Type=" + keyType);
                                }
                            }
                        }
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                            // 预留接口
                        }
                        else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                             sendAppLog(ctx, "STATUS_WIDGET_READY");
                        }

                    } catch (Throwable t) {
                        XposedBridge.log("NaviHook Recv Err: " + t);
                    }
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION);
            filter.addAction("XSF_ACTION_SET_VENDOR");
            filter.addAction("XSF_ACTION_SEND_STATUS");
            context.registerReceiver(receiver, filter);
            
        } catch (Throwable t) {}
    }

    // 解析 10001 (导航模式)
    private void parseNaviInfo(Intent intent) {
        String road = intent.getStringExtra("CUR_ROAD_NAME");
        if (road == null) road = intent.getStringExtra("cur_road_name");
        if (road != null) curRoadName = road;

        String next = intent.getStringExtra("NEXT_ROAD_NAME");
        if (next == null) next = intent.getStringExtra("next_road_name");
        if (next != null) nextRoadName = next;

        segmentDis = intent.getIntExtra("SEG_REMAIN_DIS", intent.getIntExtra("seg_remain_dis", 0));
        // 双保险
        if (segmentDis == 0) segmentDis = intent.getIntExtra("DISTANCE", 0);

        turnIcon = intent.getIntExtra("ICON", intent.getIntExtra("icon", 2));
        routeRemainDis = intent.getIntExtra("ROUTE_REMAIN_DIS", intent.getIntExtra("route_remain_dis", 0));
        routeRemainTime = intent.getIntExtra("ROUTE_REMAIN_TIME", intent.getIntExtra("route_remain_time", 0));
    }

    // 解析 10019 (巡航模式) - 让你不导航也能亮！
    private void parseCruiseInfo(Intent intent) {
        // 巡航模式下，通常只有当前路名
        String road = intent.getStringExtra("ROAD_NAME"); // 10019通常用 ROAD_NAME
        if (road == null) road = intent.getStringExtra("road_name");
        if (road == null) road = intent.getStringExtra("CUR_ROAD_NAME"); // 尝试备用
        
        if (road != null && !road.isEmpty()) {
            curRoadName = road;
        } else {
            curRoadName = "正在定位...";
        }
        
        nextRoadName = "自由巡航中";
        turnIcon = 1; // 直行图标
        segmentDis = 0;
    }

    private void logData(Context ctx, String msg) {
        if (widgetLogCount++ % 10 == 0) { // 降低日志频率
            sendAppLog(ctx, msg);
            sendAppLog(ctx, "STATUS_WIDGET_READY"); // 顺便点亮状态灯
        }
    }

    private void sendInternalWakeUp(Context ctx) {
        try {
            // 🌟 锁定 Vendor 2 (根据你的测试)
            int targetVendor = 2;

            Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
            iStatus.putExtra("status", 1); 
            iStatus.putExtra("is_navi", true);
            iStatus.putExtra("vendor", targetVendor);
            iStatus.putExtra("route_state", 0);
            iStatus.setPackage(PKG_WIDGET); 
            ctx.sendBroadcast(iStatus, PERMISSION_NAVI); // 这里现在有定义了

            Intent iRefresh = new Intent("ecarx.navi.REFRESH_WIDGET");
            iRefresh.setPackage(PKG_WIDGET);
            ctx.sendBroadcast(iRefresh);
        } catch (Throwable t) {}
    }

    private void hookEcarxOpenApiWithRealData(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> apiClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.EcarxOpenApi", lpparam.classLoader);
            Class<?> cbClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.IAPIGetGuideInfoCallBack", lpparam.classLoader);
            
            XposedHelpers.findAndHookMethod(apiClass, "getGuideInfo", cbClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object callback = param.args[0];
                    if (callback != null) {
                        XposedHelpers.callMethod(callback, "getGuideInfoResult",
                            1, routeRemainDis, routeRemainTime, 0, 0, 0,
                            nextRoadName, nextRoadName, 
                            0.5f, 0, segmentDis, turnIcon, 
                            curRoadName, routeRemainDis, routeRemainTime, 0, 0
                        );
                        param.setResult(true);
                    }
                }
            });
        } catch (Throwable t) {}
    }

    // =============================================================
    // PART 2: Service 进程
    // =============================================================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject instanceof Service) {}
            }
        });

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                registerServiceReceiver(context);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    sendAppLog(context, "STATUS_HOOK_READY (DelayCheck)");
                }, 8000);
            }
        });
        
        try { XposedHelpers.findAndHookMethod("ecarx.naviservice.d.y", lpparam.classLoader, "b", String.class, XC_MethodReplacement.returnConstant(70500)); } catch (Throwable t) {}
    }

    private void registerServiceReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                    sendAppLog(ctx, "STATUS_HOOK_READY (Echo)");     
                    sendAppLog(ctx, "STATUS_SERVICE_RUNNING (Echo)");
                    if (!isServiceHeartbeatRunning) startServiceHeartbeat(ctx);
                } 
                else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                    keepAliveAndGreen(ctx);
                    sendAppLog(ctx, "STATUS_IPC_CONNECTED (Force)"); 
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("XSF_ACTION_SEND_STATUS");
        filter.addAction("XSF_ACTION_FORCE_CONNECT");
        context.registerReceiver(receiver, filter);
    }

    private void startServiceHeartbeat(Context ctx) {
        isServiceHeartbeatRunning = true;
        new Thread(() -> {
            sendAppLog(ctx, "💓 V53 全兼容版启动 (V2)...");
            int count = 0;
            while (isServiceHeartbeatRunning) {
                try {
                    if (count % 5 == 0) keepAliveAndGreen(ctx);
                    
                    // 锁定 Vendor 2
                    int currentVendor = 2;
                    if (count % 4 == 0) sendAppLog(ctx, "🔒 Service 维持 V2");

                    Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
                    iStatus.putExtra("status", 1);
                    iStatus.putExtra("is_navi", true);
                    iStatus.putExtra("vendor", currentVendor);
                    iStatus.putExtra("route_state", 0);
                    iStatus.setPackage(PKG_WIDGET);
                    ctx.sendBroadcast(iStatus, PERMISSION_NAVI);

                    Thread.sleep(3000); 
                    count++;
                } catch (Exception e) { break; }
            }
        }).start();
    }

    private void keepAliveAndGreen(Context ctx) {
        try {
            Class<?> q = XposedHelpers.findClass("q", ctx.getClassLoader());
            Object mgr = XposedHelpers.getStaticObjectField(q, "a");
            if (mgr == null) {
                mgr = XposedHelpers.newInstance(XposedHelpers.findClass("l", ctx.getClassLoader()));
                XposedHelpers.setStaticObjectField(q, "a", mgr);
            }
            Object conn = XposedHelpers.getObjectField(mgr, "i");
            if (conn != null) {
                XposedHelpers.callMethod(conn, "onServiceConnected", new ComponentName("f","f"), null);
            }
        } catch (Throwable t) {}
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
