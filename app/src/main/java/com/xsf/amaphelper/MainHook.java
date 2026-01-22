package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
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
    
    // 📜 严格按照 PDF 协议
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final int KEY_TYPE_NAVI_INFO = 10001;

    // 🌟 数据仓库
    private static String curRoadName = "等待高德广播...";
    private static String nextRoadName = "V52协议适配...";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;
    
    // ⚙️ 控制变量
    private static boolean isServiceHeartbeatRunning = false;
    private static int widgetLogCount = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 0. 自身激活
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 1. Service 进程 (控制中心)
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        // 2. Widget 进程 (显示端)
        if (lpparam.packageName.equals(PKG_WIDGET)) {
            initNaviWidgetBridgeHook(lpparam);
        }
    }

    // =============================================================
    // PART 1: Widget 进程 (防崩设计 + PDF协议适配)
    // =============================================================
    private void initNaviWidgetBridgeHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 🚨 极简 Hook：只用 Application，且去掉所有 Handler 延时，防止崩溃
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    // 直接注册，不做任何多余操作
                    registerWidgetReceiver(context);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Widget Hook Error: " + t);
        }

        // 劫持 API
        hookEcarxOpenApiWithRealData(lpparam);
    }

    private void registerWidgetReceiver(Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        String action = intent.getAction();
                        
                        // ✅ 匹配高德 PDF 协议
                        if (AMAP_ACTION.equals(action)) {
                            int keyType = intent.getIntExtra("KEY_TYPE", 0);
                            
                            // 🔍 调试：只要收到高德广播就打一条日志，看看 KeyType 是多少
                            if (widgetLogCount++ % 20 == 0) {
                                sendAppLog(ctx, "🔍 侦测到高德广播 Type=" + keyType);
                            }

                            if (keyType == KEY_TYPE_NAVI_INFO) {
                                // 1. 解析数据 (PDF 标准字段 + 备用小写字段)
                                String road = intent.getStringExtra("CUR_ROAD_NAME");
                                if (road == null) road = intent.getStringExtra("cur_road_name");
                                if (road != null) curRoadName = road;

                                String next = intent.getStringExtra("NEXT_ROAD_NAME");
                                if (next == null) next = intent.getStringExtra("next_road_name");
                                if (next != null) nextRoadName = next;

                                // PDF 字段: SEG_REMAIN_DIS (下个路口距离)
                                segmentDis = intent.getIntExtra("SEG_REMAIN_DIS", intent.getIntExtra("seg_remain_dis", 0));
                                // 兼容: 以前我们用 DISTANCE，高德有的版本用 SEG_REMAIN_DIS，这里做个双保险
                                if (segmentDis == 0) segmentDis = intent.getIntExtra("DISTANCE", 0);

                                turnIcon = intent.getIntExtra("ICON", intent.getIntExtra("icon", 2));
                                routeRemainDis = intent.getIntExtra("ROUTE_REMAIN_DIS", intent.getIntExtra("route_remain_dis", 0));
                                routeRemainTime = intent.getIntExtra("ROUTE_REMAIN_TIME", intent.getIntExtra("route_remain_time", 0));

                                // 2. 收到数据，立即唤醒组件
                                sendInternalWakeUp(ctx);
                                
                                // 3. 反馈
                                if (widgetLogCount % 5 == 0) {
                                    sendAppLog(ctx, "⚡ [Widget] 捕获数据: " + curRoadName);
                                    // 顺便报个活，点亮“组件Hook”灯
                                    sendAppLog(ctx, "STATUS_WIDGET_READY");
                                }
                            }
                        }
                        // 收到手动 Vendor 设置
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                            // 这里其实不需要做什么，因为我们已经在 sendInternalWakeUp 里死锁 Vendor 2 了
                            // 但为了调试，可以留个日志
                            int v = intent.getIntExtra("vendor", 2);
                            XposedBridge.log("Widget 收到 Vendor: " + v);
                        }
                        // 收到 App 询问状态
                        else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                             sendAppLog(ctx, "STATUS_WIDGET_READY");
                        }

                    } catch (Throwable t) {
                        XposedBridge.log("NaviHook Recv Err: " + t);
                    }
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION); // 修正为 AUTONAVI_STANDARD_BROADCAST_SEND
            filter.addAction("XSF_ACTION_SET_VENDOR");
            filter.addAction("XSF_ACTION_SEND_STATUS");
            context.registerReceiver(receiver, filter);
            XposedBridge.log("NaviHook: Widget Receiver Registered (V52)");
            
        } catch (Throwable t) {}
    }

    private void sendInternalWakeUp(Context ctx) {
        try {
            // 🌟 死锁 Vendor 2 (根据您的测试结果)
            int targetVendor = 2;

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
    // PART 2: Service 进程 (控制中心)
    // =============================================================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 双重 Hook 防止漏网，但主要依赖 Application
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject instanceof Service) {
                    // 仅做标记，不做实质操作
                }
            }
        });

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                registerServiceReceiver(context);
                // 启动即发一次，如果没收到只能靠手动激活
                sendAppLog(context, "STATUS_HOOK_READY (Boot)");
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
                    // 回显：点亮灯
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
            sendAppLog(ctx, "💓 V52 引擎启动 (锁定 V2)...");
            int count = 0;
            while (isServiceHeartbeatRunning) {
                try {
                    if (count % 5 == 0) keepAliveAndGreen(ctx);
                    
                    // 🌟 死锁 Vendor 2
                    int currentVendor = 2;
                    
                    // 降低日志频率，每10秒报一次，证明还活着
                    if (count % 3 == 0) sendAppLog(ctx, "🔒 Service 锁定 V2");

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
