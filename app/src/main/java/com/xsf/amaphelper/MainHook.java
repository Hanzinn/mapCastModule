package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private static final String PERMISSION_NAVI = "ecarx.oem.permission.OPENAPI_NAVI_PERMISSION";

    // 🌟 轮询池 (1, 2, 4)
    private static final int[] POLLING_VENDORS = {1, 2, 4};

    private static String curRoadName = "等待高德数据...";
    private static String nextRoadName = "系统搜索中...";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;
    
    private static int serviceManualVendor = -1; 
    private static boolean isServiceHeartbeatRunning = false;
    private static int widgetManualVendor = -1;  
    private static int widgetAutoCount = 0;      

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
        // 只保留最稳的 Application 入口
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                registerWidgetReceiver(context); 
                
                // 🌟 新增：延时发送组件存活信号 (点亮界面的“组件Hook”灯)
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    sendAppLog(context, "STATUS_WIDGET_READY");
                }, 3000);
            }
        });

        hookEcarxOpenApiWithRealData(lpparam);
    }

    private void registerWidgetReceiver(Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    
                    if ("com.autonavi.amapauto.navigation.info".equals(action)) {
                        if (intent.getIntExtra("TYPE", 0) == 10001) {
                            curRoadName = intent.getStringExtra("CUR_ROAD_NAME");
                            nextRoadName = intent.getStringExtra("NEXT_ROAD_NAME");
                            turnIcon = intent.getIntExtra("ICON", 2);
                            segmentDis = intent.getIntExtra("DISTANCE", 0);
                            routeRemainDis = intent.getIntExtra("ROUTE_REMAIN_DIS", 0);
                            routeRemainTime = intent.getIntExtra("ROUTE_REMAIN_TIME", 0);
                            
                            sendInternalWakeUp(ctx);
                            
                            if (widgetAutoCount % 10 == 0) {
                                sendAppLog(ctx, "⚡ [Widget] 捕获数据: " + curRoadName);
                            }
                        }
                    }
                    else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                        widgetManualVendor = intent.getIntExtra("vendor", -1);
                    }
                    // 收到回显指令，也要报告存活
                    else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                         sendAppLog(ctx, "STATUS_WIDGET_READY");
                    }
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.autonavi.amapauto.navigation.info");
            filter.addAction("XSF_ACTION_SET_VENDOR");
            filter.addAction("XSF_ACTION_SEND_STATUS"); // 增加回显监听
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    private void sendInternalWakeUp(Context ctx) {
        int targetVendor;
        if (widgetManualVendor != -1) {
            targetVendor = widgetManualVendor;
        } else {
            // 🌟 升级：按照 1->2->4 循环
            targetVendor = POLLING_VENDORS[widgetAutoCount++ % 3];
        }

        Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
        iStatus.putExtra("status", 1); 
        iStatus.putExtra("is_navi", true);
        iStatus.putExtra("vendor", targetVendor);
        iStatus.putExtra("route_state", 0);
        iStatus.setPackage(PKG_WIDGET); 
        ctx.sendBroadcast(iStatus);

        Intent iRefresh = new Intent("ecarx.navi.REFRESH_WIDGET");
        iRefresh.setPackage(PKG_WIDGET);
        ctx.sendBroadcast(iRefresh);
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
    // PART 2: Service 进程 (控制中心)
    // =============================================================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                // 抢跑位：尽早注入
                if (param.thisObject instanceof Service) {}
            }
        });

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                registerServiceReceiver(context);
                // 延时自检
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    sendAppLog(context, "STATUS_HOOK_READY (DelayCheck)");
                }, 5000);
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
                else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                    serviceManualVendor = intent.getIntExtra("vendor", -1);
                    sendAppLog(ctx, "🔒 Service 锁定: " + serviceManualVendor);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("XSF_ACTION_SEND_STATUS");
        filter.addAction("XSF_ACTION_FORCE_CONNECT");
        filter.addAction("XSF_ACTION_SET_VENDOR");
        context.registerReceiver(receiver, filter);
    }

    private void startServiceHeartbeat(Context ctx) {
        isServiceHeartbeatRunning = true;
        new Thread(() -> {
            sendAppLog(ctx, "💓 V49 引擎启动...");
            int count = 0;
            while (isServiceHeartbeatRunning) {
                try {
                    if (count % 5 == 0) keepAliveAndGreen(ctx);
                    
                    int currentVendor;
                    if (serviceManualVendor != -1) {
                        currentVendor = serviceManualVendor;
                    } else {
                        // 🌟 升级：按照 1->2->4 循环
                        currentVendor = POLLING_VENDORS[count % 3];
                        sendAppLog(ctx, "🔄 轮询尝试: " + currentVendor);
                    }

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
