package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
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
    
    // 📜 协议定义 (PDF标准)
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    // 🌟 数据仓库
    private static String curRoadName = "等待数据...";
    private static String nextRoadName = "系统待机";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;
    
    // ⚙️ 控制变量
    private static int widgetLogCount = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 1. Service 进程 (只负责点灯和保活)
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        // 2. Widget 进程 (显示端 - 核心修改)
        if (lpparam.packageName.equals(PKG_WIDGET)) {
            initNaviWidgetBridgeHook(lpparam);
        }
    }

    // =============================================================
    // PART 1: Widget 进程 (温柔Hook + 深度扫描)
    // =============================================================
    private void initNaviWidgetBridgeHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. 注册广播 (只用 Application，最稳)
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    registerDeepScanner(context);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Widget Hook Error: " + t);
        }

        // 2. 温柔劫持 API (改为 afterHookedMethod)
        hookEcarxOpenApiGentle(lpparam);
    }

    private void registerDeepScanner(Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        String action = intent.getAction();
                        
                        if (AMAP_ACTION.equals(action)) {
                            Bundle bundle = intent.getExtras();
                            if (bundle != null) {
                                // 🔍 深度探针：打印所有 Key
                                if (widgetLogCount++ % 20 == 0) {
                                    StringBuilder sb = new StringBuilder("🔍 高德探针: ");
                                    for (String key : bundle.keySet()) {
                                        sb.append(key).append("=").append(bundle.get(key)).append("; ");
                                    }
                                    XposedBridge.log(sb.toString()); // 输出到 LSP 日志
                                }

                                // 🔄 自动识别并提取数据 (大小写通吃)
                                extractData(bundle);
                                
                                // ⚡ 唤醒组件
                                sendInternalWakeUp(ctx);
                                
                                // 💡 反馈
                                if (widgetLogCount % 10 == 0) {
                                    sendAppLog(ctx, "⚡ [数据] " + curRoadName);
                                    sendAppLog(ctx, "STATUS_WIDGET_READY");
                                }
                            }
                        }
                        // 响应 App 的状态查询
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
            XposedBridge.log("NaviHook: Deep Scanner Registered (V55)");
            
        } catch (Throwable t) {}
    }

    // 智能提取数据 (不依赖固定 Key，尝试所有可能)
    private void extractData(Bundle b) {
        // 路名
        String road = b.getString("CUR_ROAD_NAME");
        if (road == null) road = b.getString("cur_road_name");
        if (road == null) road = b.getString("ROAD_NAME"); // 巡航模式
        if (road != null) curRoadName = road;

        String next = b.getString("NEXT_ROAD_NAME");
        if (next == null) next = b.getString("next_road_name");
        if (next != null) nextRoadName = next;

        // 距离
        int dist = b.getInt("SEG_REMAIN_DIS", 0);
        if (dist == 0) dist = b.getInt("seg_remain_dis", 0);
        if (dist == 0) dist = b.getInt("DISTANCE", 0);
        segmentDis = dist;

        // 图标
        int icon = b.getInt("ICON", -1);
        if (icon == -1) icon = b.getInt("icon", 2);
        if (icon != -1) turnIcon = icon;
        
        // 剩余信息
        routeRemainDis = b.getInt("ROUTE_REMAIN_DIS", b.getInt("route_remain_dis", 0));
        routeRemainTime = b.getInt("ROUTE_REMAIN_TIME", b.getInt("route_remain_time", 0));
    }

    private void sendInternalWakeUp(Context ctx) {
        try {
            // 🌟 锁定 Vendor 2
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

    // 🌟 核心修改：温柔 Hook (afterHookedMethod)
    private void hookEcarxOpenApiGentle(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> apiClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.EcarxOpenApi", lpparam.classLoader);
            Class<?> cbClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.IAPIGetGuideInfoCallBack", lpparam.classLoader);
            
            XposedHelpers.findAndHookMethod(apiClass, "getGuideInfo", cbClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // 🌟 重点：等原方法执行完，我们再追加一次回调更新
                    Object callback = param.args[0];
                    if (callback != null) {
                        XposedBridge.log("NaviHook: 原方法执行完毕，注入数据...");
                        XposedHelpers.callMethod(callback, "getGuideInfoResult",
                            1, routeRemainDis, routeRemainTime, 0, 0, 0,
                            nextRoadName, nextRoadName, 
                            0.5f, 0, segmentDis, turnIcon, 
                            curRoadName, routeRemainDis, routeRemainTime, 0, 0
                        );
                        // 不修改 setResult，保证原流程通畅
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Gentle Hook Error: " + t);
        }
    }

    // =============================================================
    // PART 2: Service 进程 (状态回显)
    // =============================================================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 双重保险 Hook
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
             @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable { if (param.thisObject instanceof Service) {} }
        });

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                registerServiceReceiver(context);
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
                    sendAppLog(ctx, "STATUS_HOOK_READY (Echo)");     
                    sendAppLog(ctx, "STATUS_SERVICE_RUNNING (Echo)");
                    keepAliveAndGreen(ctx); // 保持绿灯
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
