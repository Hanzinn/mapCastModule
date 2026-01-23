package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName; // 🌟 核心修复：补全缺失的导入
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.Set; 
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
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
    private static String nextRoadName = "";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;

    private static boolean isServiceHeartbeatRunning = false;
    private static int currentVendor = 2; 
    private static double logSamplingRate = 0.1; 

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

    // 压制 findAndHookMethod 可能产生的警告
    @SuppressWarnings("deprecation")
    private void initSafeHook(XC_LoadPackage.LoadPackageParam lpparam) {
        final String procName = lpparam.packageName.contains("service") ? "LBSNavi" : "Widget";

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
        } catch (Throwable t) {}

        // 只有 LBSNavi 才执行 API Hook (全反射安全模式)
        if (procName.equals("LBSNavi")) {
            hookApiByReflection(lpparam, procName);
        }
    }

    // 🌟 压制 Log 警告，且保留 final 修饰符
    @SuppressWarnings("deprecation")
    private void registerReceiver(final Context context, final String procName) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        String action = intent.getAction();
                        
                        if (AMAP_ACTION.equals(action)) {
                            // 降噪: 过滤 10065 GPS 包
                            int keyType = intent.getIntExtra("KEY_TYPE", 0);
                            if (keyType == 10065) return; 

                            if (!isServiceHeartbeatRunning && procName.equals("LBSNavi")) {
                                startServiceHeartbeat(ctx);
                            }
                            reportStatus(ctx, procName, "LIVE");

                            Bundle b = intent.getExtras();
                            if (b != null) {
                                // 🌟 核心修复：直接调用 keySet 触发解包
                                b.keySet(); 

                                // 日志探针 (仅在 Widget 进程打印)
                                if (procName.equals("Widget") && Math.random() < logSamplingRate) {
                                    StringBuilder sb = new StringBuilder("🔍 有效包: ");
                                    for (String key : b.keySet()) {
                                        // 只打印关键字段
                                        if (key.contains("ROAD") || key.contains("ICON") || key.contains("TYPE")) {
                                            Object val = b.get(key);
                                            sb.append(key).append("=").append(val).append("; ");
                                        }
                                    }
                                    if (sb.length() > 8) XposedBridge.log(sb.toString());
                                }

                                extractData(b);
                                sendInternalWakeUp(ctx);
                            }
                        }
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                            int newVendor = intent.getIntExtra("vendor", -1);
                            currentVendor = (newVendor == -1) ? 2 : newVendor;
                            
                            // 强制注入测试数据
                            curRoadName = "测试道路 V" + currentVendor;
                            nextRoadName = "前方左转";
                            turnIcon = 4; 
                            segmentDis = 500;
                            
                            XposedBridge.log("NaviHook: [" + procName + "] 强制注入测试数据");
                            sendInternalWakeUp(ctx);
                        }
                        else if ("XSF_ACTION_SET_SAMPLE_RATE".equals(action)) {
                            logSamplingRate = intent.getDoubleExtra("rate", 0.1);
                        }
                        else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                            reportStatus(ctx, procName, "ECHO");
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
            filter.addAction("XSF_ACTION_SET_VENDOR");
            filter.addAction("XSF_ACTION_SET_SAMPLE_RATE");
            filter.addAction("XSF_ACTION_SEND_STATUS");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    private void extractData(Bundle b) {
        try {
            if (b.containsKey("CUR_ROAD_NAME")) curRoadName = b.getString("CUR_ROAD_NAME");
            else if (b.containsKey("cur_road_name")) curRoadName = b.getString("cur_road_name");
            else if (b.containsKey("ROAD_NAME")) curRoadName = b.getString("ROAD_NAME");
            
            if (curRoadName == null && b.containsKey("NEXT_NEXT_ROAD_NAME")) {
                String nn = b.getString("NEXT_NEXT_ROAD_NAME");
                if (nn != null) curRoadName = nn;
            }

            if (b.containsKey("NEXT_ROAD_NAME")) nextRoadName = b.getString("NEXT_ROAD_NAME");
            else if (b.containsKey("next_road_name")) nextRoadName = b.getString("next_road_name");

            segmentDis = getIntFromBundle(b, "SEG_REMAIN_DIS", "seg_remain_dis", "DISTANCE");
            turnIcon = getIntFromBundle(b, "ICON", "icon", null);
            
            if (turnIcon == 0 && b.containsKey("NAV_ICON")) turnIcon = b.getInt("NAV_ICON");

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
        Intent iRefresh = new Intent("ecarx.navi.REFRESH_WIDGET");
        iRefresh.setPackage(PKG_WIDGET);
        ctx.sendBroadcast(iRefresh);
        
        Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
        iStatus.putExtra("status", 1); 
        iStatus.putExtra("is_navi", true);
        iStatus.putExtra("vendor", currentVendor);
        iStatus.setPackage(PKG_WIDGET); 
        ctx.sendBroadcast(iStatus);
    }
    
    private void startServiceHeartbeat(Context ctx) {
        isServiceHeartbeatRunning = true;
        new Thread(() -> {
            while (isServiceHeartbeatRunning) {
                try {
                    keepAliveAndGreen(ctx);
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

    // 压制 Log 警告
    @SuppressWarnings("deprecation")
    private void hookApiByReflection(XC_LoadPackage.LoadPackageParam lpparam, String procName) {
        try {
            Class<?> apiClass = XposedHelpers.findClassIfExists("com.neusoft.nts.ecarxnavsdk.EcarxOpenApi", lpparam.classLoader);
            if (apiClass == null) return; // 安静退出，不打印 Log 以减少噪音
            
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
                    } catch (Throwable t) {
                        XposedBridge.log("NaviHook: 注入异常: " + t);
                    }
                }
            });
            XposedBridge.log("NaviHook: [" + procName + "] 安全反射 Hook 已挂载");
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Hook 初始化失败: " + t);
        }
    }
}
