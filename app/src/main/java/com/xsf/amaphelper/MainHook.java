package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle; // 确保导入 Bundle
import android.os.Handler;
import android.os.Looper;
import java.util.Set; // 导入 Set
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

    // 🌟 修正1：根据你的PDF，改用老版协议 Action
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    // 🌟 修正2：根据你的测试，锁定 Vendor 2
    private static final int TARGET_VENDOR = 2;

    private static String curRoadName = "等待高德V51数据...";
    private static String nextRoadName = "协议适配中...";
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
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                registerWidgetReceiver(context); 
                
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
                    
                    // 🌟 修正：监听 AUTONAVI_STANDARD_BROADCAST_SEND
                    if (AMAP_ACTION.equals(action)) {
                        try {
                            // 1. 协议过滤：PDF说 KeyType 10001 是导航信息
                            int keyType = intent.getIntExtra("KEY_TYPE", 0);
                            
                            // 🔍 数据探针：只要收到广播，把所有 Key 都打印出来，方便我们分析
                            if (widgetAutoCount % 20 == 0) {
                                dumpIntentExtras(ctx, intent);
                            }

                            if (keyType == 10001) {
                                // 2. 尝试提取数据 (兼容大小写)
                                // 路名
                                String road = intent.getStringExtra("CUR_ROAD_NAME");
                                if (road == null) road = intent.getStringExtra("cur_road_name"); // 备用小写
                                if (road != null) curRoadName = road;

                                String nextRoad = intent.getStringExtra("NEXT_ROAD_NAME");
                                if (nextRoad == null) nextRoad = intent.getStringExtra("next_road_name");
                                if (nextRoad != null) nextRoadName = nextRoad;

                                // 图标 & 距离
                                turnIcon = intent.getIntExtra("ICON", intent.getIntExtra("icon", 2));
                                segmentDis = intent.getIntExtra("SEG_REMAIN_DIS", intent.getIntExtra("seg_remain_dis", 0));
                                routeRemainDis = intent.getIntExtra("ROUTE_REMAIN_DIS", intent.getIntExtra("route_remain_dis", 0));
                                routeRemainTime = intent.getIntExtra("ROUTE_REMAIN_TIME", intent.getIntExtra("route_remain_time", 0));

                                // 3. 唤醒组件
                                sendInternalWakeUp(ctx);
                                
                                // 4. 反馈日志
                                if (widgetAutoCount % 5 == 0) {
                                    sendAppLog(ctx, "⚡ [Widget] 捕获数据(V2): " + curRoadName);
                                }
                            }
                        } catch (Exception e) {
                            XposedBridge.log("NaviHook Decode Err: " + e);
                        }
                    }
                    else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                        widgetManualVendor = intent.getIntExtra("vendor", -1);
                    }
                    else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                         sendAppLog(ctx, "STATUS_WIDGET_READY");
                    }
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION); // 使用新 Action
            filter.addAction("XSF_ACTION_SET_VENDOR");
            filter.addAction("XSF_ACTION_SEND_STATUS");
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    // 🔍 探针工具：打印所有广播参数
    private void dumpIntentExtras(Context ctx, Intent intent) {
        try {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("收到广播 Keys: ");
                Set<String> keys = bundle.keySet();
                for (String key : keys) {
                    sb.append(key).append("=").append(bundle.get(key)).append("; ");
                }
                XposedBridge.log(sb.toString()); // 打印到 LSP 日志
                // 也可以发给 App 显示（如果太长可能会被截断）
                // sendAppLog(ctx, "🔍 侦测: " + sb.toString().substring(0, Math.min(sb.length(), 100)));
            }
        } catch (Throwable t) {}
    }

    private void sendInternalWakeUp(Context ctx) {
        int targetVendor;
        if (widgetManualVendor != -1) {
            targetVendor = widgetManualVendor;
        } else {
            // 🌟 修正：只用 2，或者 2 和 1 (既然你说 2 能亮，重点测 2)
            targetVendor = (widgetAutoCount++ % 5 == 0) ? 1 : 2; // 80% 概率发 2
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
        // 抢跑防御
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject instanceof Service) {
                    // 可以在这里提前做点什么，但为了稳定，主要还是靠 Application
                }
            }
        });

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                registerServiceReceiver(context);
                // 延时自检，确保 App 启动后能看到灯亮
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    sendAppLog(context, "STATUS_HOOK_READY (DelayCheck)");
                }, 8000); // 延时加长到 8秒，给你更多时间打开 App
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
            sendAppLog(ctx, "💓 V51 协议修正版启动 (Target V2)...");
            int count = 0;
            while (isServiceHeartbeatRunning) {
                try {
                    if (count % 5 == 0) keepAliveAndGreen(ctx);
                    
                    int currentVendor;
                    if (serviceManualVendor != -1) {
                        currentVendor = serviceManualVendor;
                    } else {
                        // 🌟 修正：重点测试 Vendor 2
                        currentVendor = (count % 5 == 0) ? 1 : 2; // 80% 概率发 2
                        sendAppLog(ctx, "🔄 Service 轮询: " + currentVendor);
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
