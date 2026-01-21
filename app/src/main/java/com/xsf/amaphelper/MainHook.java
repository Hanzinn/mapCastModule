package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

    // 🌟 真实数据仓库
    private static String curRoadName = "等待高德数据...";
    private static String nextRoadName = "系统轮询中...";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;
    
    // 🎮 双端控制变量 (配合 V45 UI 的锁定按钮)
    private static int serviceManualVendor = -1; // Service进程的锁
    private static boolean isServiceHeartbeatRunning = false;
    
    private static int widgetManualVendor = -1;  // Widget进程的锁
    private static int widgetAutoCount = 0;      // Widget进程的自动计数器

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 0. 自身激活检测
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 1. Service 进程 (控制中心：负责亮灯、物理连接、发控制广播)
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        // 2. Widget 进程 (显示端：负责接收高德数据、内部唤醒、数据注入)
        if (lpparam.packageName.equals(PKG_WIDGET)) {
            initNaviWidgetBridgeHook(lpparam);
        }
    }

    // =============================================================
    // PART 1: Widget 进程 (数据桥接 + 同步锁)
    // =============================================================
    private void initNaviWidgetBridgeHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // A. 在 Application 启动时注册广播接收器
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                registerWidgetReceiver(context); // 注册数据+控制监听
            }
        });

        // B. 劫持 API，投喂真实数据
        hookEcarxOpenApiWithRealData(lpparam);
    }

    private void registerWidgetReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                
                // A. 处理高德数据 (来自高德地图车机版)
                if ("com.autonavi.amapauto.navigation.info".equals(action)) {
                    if (intent.getIntExtra("TYPE", 0) == 10001) {
                        // 1. 提取数据
                        curRoadName = intent.getStringExtra("CUR_ROAD_NAME");
                        nextRoadName = intent.getStringExtra("NEXT_ROAD_NAME");
                        turnIcon = intent.getIntExtra("ICON", 2);
                        segmentDis = intent.getIntExtra("DISTANCE", 0);
                        routeRemainDis = intent.getIntExtra("ROUTE_REMAIN_DIS", 0);
                        routeRemainTime = intent.getIntExtra("ROUTE_REMAIN_TIME", 0);
                        
                        // 2. 收到数据，立即触发内部唤醒 (带 Vendor 逻辑)
                        sendInternalWakeUp(ctx);
                        
                        // 3. 记录日志 (每5次记录一次，避免刷屏)
                        if (widgetAutoCount % 5 == 0) {
                             sendAppLog(ctx, "⚡ [Widget] 捕获数据: " + curRoadName);
                        }
                    }
                }
                // B. 处理控制指令 (来自 V45 UI 的锁定按钮)
                else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                    widgetManualVendor = intent.getIntExtra("vendor", -1);
                    XposedBridge.log("NaviHook: [Widget] 同步锁定 Vendor: " + widgetManualVendor);
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.autonavi.amapauto.navigation.info"); // 高德数据
        filter.addAction("XSF_ACTION_SET_VENDOR");                 // 控制指令
        context.registerReceiver(receiver, filter);
    }

    private void sendInternalWakeUp(Context ctx) {
        // 🌟 智能 Vendor 选择逻辑
        int targetVendor;
        if (widgetManualVendor != -1) {
            // 如果 UI 上锁定了，使用锁定值 (解决冲突的终极方案)
            targetVendor = widgetManualVendor;
        } else {
            // 如果未锁定 (自动模式)，在 1 和 4 之间轮询，增加命中率
            targetVendor = (widgetAutoCount++ % 2 == 0) ? 1 : 4;
        }

        // 1. 伪造状态：告诉组件 "导航正在进行中"
        Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
        iStatus.putExtra("status", 1); 
        iStatus.putExtra("is_navi", true);
        iStatus.putExtra("vendor", targetVendor); // 动态 ID
        iStatus.putExtra("route_state", 0);
        iStatus.setPackage(PKG_WIDGET); // 只发给自己
        ctx.sendBroadcast(iStatus);

        // 2. 强制刷新：逼迫组件调用 getGuideInfo
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
                        // 注入静态变量里的真实数据
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
        // 最稳的入口：Application.onCreate
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                sendAppLog(context, "STATUS_HOOK_READY (V45-Ready)");
                registerServiceReceiver(context);
            }
        });
        
        // 生存补丁 (防止部分机型崩溃)
        try { XposedHelpers.findAndHookMethod("ecarx.naviservice.d.y", lpparam.classLoader, "b", String.class, XC_MethodReplacement.returnConstant(70500)); } catch (Throwable t) {}
    }

    private void registerServiceReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                
                // 收到激活指令 (来自 UI 的 "3. 激活仪表")
                if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                    if (!isServiceHeartbeatRunning) startServiceHeartbeat(ctx);
                } 
                // 收到重连指令
                else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                    keepAliveAndGreen(ctx);
                    sendAppLog(ctx, "⚡ 强制重连 IPC...");
                }
                // 收到 Vendor 锁定指令 (来自 UI 的 "锁 V1/V4")
                else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                    serviceManualVendor = intent.getIntExtra("vendor", -1);
                    sendAppLog(ctx, "🔒 Service 锁定 Vendor: " + serviceManualVendor);
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
            sendAppLog(ctx, "💓 V45 引擎启动 (监听高德中)...");
            int count = 0;
            while (isServiceHeartbeatRunning) {
                try {
                    // 1. 物理维持 (每5次循环一次)
                    if (count % 5 == 0) keepAliveAndGreen(ctx);
                    
                    // 2. Service 端的轮询/锁定逻辑
                    int currentVendor;
                    if (serviceManualVendor != -1) {
                        currentVendor = serviceManualVendor; // 锁定模式
                    } else {
                        // 自动模式：1 -> 4 -> 2
                        int mod = count % 3;
                        if (mod == 0) currentVendor = 1;
                        else if (mod == 1) currentVendor = 4;
                        else currentVendor = 2;
                        
                        // 只有在没数据且自动轮询时才打日志，避免刷屏
                        if (count % 3 == 0) sendAppLog(ctx, "🔄 轮询中...当前尝试: " + currentVendor);
                    }

                    // 3. 发送外部握手 (辅助唤醒)
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

    // 维持 IPC 连接 (让灯变绿)
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

    // 发送日志回显到 App 界面
    private void sendAppLog(Context ctx, String log) {
        try {
            Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
            i.setPackage(PKG_SELF);
            i.putExtra("log", log);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {}
    }
}
