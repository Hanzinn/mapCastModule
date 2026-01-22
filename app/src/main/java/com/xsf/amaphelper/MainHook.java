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

    // 🌟 数据仓库
    private static String curRoadName = "等待高德...";
    private static String nextRoadName = "";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;

    // 🎮 控制变量
    private static boolean isServiceHeartbeatRunning = false;
    // 🌟 默认 Vendor 为 2，但可以被修改
    private static int currentVendor = 2; 

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (lpparam.packageName.equals(PKG_SERVICE) || lpparam.packageName.equals(PKG_WIDGET)) {
            initSafeHook(lpparam);
        }
    }

    private void initSafeHook(XC_LoadPackage.LoadPackageParam lpparam) {
        final String procName = lpparam.packageName.contains("service") ? "LBSNavi" : "Widget";

        // 1. 注册广播 (接收 UI 指令 + 高德数据)
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    registerReceiver(context, procName);
                    
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                         reportStatus(context, procName, "BOOT");
                    }, 3000);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Failed to hook onCreate in " + procName);
        }

        // 2. 防御性 API Hook
        hookApiDefensive(lpparam, procName);
    }

    private void registerReceiver(Context context, String procName) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        String action = intent.getAction();
                        
                        // A. 收到高德数据 -> 更新数据 -> 唤醒
                        if (AMAP_ACTION.equals(action)) {
                            if (!isServiceHeartbeatRunning && procName.equals("LBSNavi")) {
                                startServiceHeartbeat(ctx);
                            }
                            reportStatus(ctx, procName, "LIVE");

                            Bundle b = intent.getExtras();
                            if (b != null) {
                                extractData(b);
                                // 收到数据时，使用当前的 currentVendor 唤醒
                                if (procName.equals("Widget")) {
                                    sendInternalWakeUp(ctx);
                                }
                            }
                        }
                        // B. 收到 UI 锁定指令 -> 修改 currentVendor
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                            int newVendor = intent.getIntExtra("vendor", -1);
                            // -1 代表重置为默认(2)，其他值代表锁定(1或4)
                            currentVendor = (newVendor == -1) ? 2 : newVendor;
                            
                            XposedBridge.log("NaviHook: [" + procName + "] Vendor 已更新为: " + currentVendor);
                            
                            // 收到指令后，立即用新 Vendor 刷新一次
                            sendInternalWakeUp(ctx);
                        }
                        // C. 收到状态回显 / 激活指令
                        else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                            reportStatus(ctx, procName, "ECHO");
                            // 点击“激活仪表”时，也强制刷新一次
                            sendInternalWakeUp(ctx);
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
            filter.addAction("XSF_ACTION_SET_VENDOR"); // 监听锁定指令
            filter.addAction("XSF_ACTION_SEND_STATUS");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    private void extractData(Bundle b) {
        try {
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
        } catch (Exception e) {}
    }
    
    private int getIntFromBundle(Bundle b, String k1, String k2, String k3) {
        int v = b.getInt(k1, -1);
        if (v == -1) v = b.getInt(k2, -1);
        if (v == -1 && k3 != null) v = b.getInt(k3, -1);
        return (v == -1) ? 0 : v;
    }

    private void sendInternalWakeUp(Context ctx) {
        // 刷新 Widget
        Intent iRefresh = new Intent("ecarx.navi.REFRESH_WIDGET");
        iRefresh.setPackage(PKG_WIDGET);
        ctx.sendBroadcast(iRefresh);
        
        // 发送状态 (使用动态的 currentVendor)
        Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
        iStatus.putExtra("status", 1); 
        iStatus.putExtra("is_navi", true);
        iStatus.putExtra("vendor", currentVendor); // 🌟 这里现在是动态的了！
        iStatus.setPackage(PKG_WIDGET); 
        ctx.sendBroadcast(iStatus);
    }
    
    // 心跳逻辑 (仅 LBSNavi)
    private void startServiceHeartbeat(Context ctx) {
        isServiceHeartbeatRunning = true;
        new Thread(() -> {
            while (isServiceHeartbeatRunning) {
                try {
                    keepAliveAndGreen(ctx);
                    
                    // 心跳包也带上当前的 Vendor，防止状态掉线
                    Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
                    iStatus.putExtra("status", 1); 
                    iStatus.putExtra("is_navi", true);
                    iStatus.putExtra("vendor", currentVendor);
                    iStatus.setPackage(PKG_WIDGET);
                    ctx.sendBroadcast(iStatus);

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

    // Hook 逻辑保持不变 (防御性)
    private void hookApiDefensive(XC_LoadPackage.LoadPackageParam lpparam, String procName) {
        try {
            Class<?> apiClass = XposedHelpers.findClassIfExists("com.neusoft.nts.ecarxnavsdk.EcarxOpenApi", lpparam.classLoader);
            if (apiClass == null) return;
            Class<?> cbClass = XposedHelpers.findClassIfExists("com.neusoft.nts.ecarxnavsdk.IAPIGetGuideInfoCallBack", lpparam.classLoader);
            if (cbClass == null) return;

            XposedHelpers.findAndHookMethod(apiClass, "getGuideInfo", cbClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
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
                    } catch (Throwable t) {}
                }
            });
        } catch (Throwable t) {}
    }
}
