package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
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
    private static final String PKG_SERVICE = "ecarx.naviservice"; // 对应仪表盘/LBSNavi
    private static final String PKG_WIDGET = "com.ecarx.naviwidget"; // 对应桌面小组件
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // 📜 协议定义
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    // 🌟 静态数据仓库 (Xposed中静态变量在同一进程内共享，跨进程不共享，所以两个进程会各自维护一份)
    private static String curRoadName = "等待数据...";
    private static String nextRoadName = "双管齐下V56";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;
    
    // ⚙️ 控制变量
    private static int logCount = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 🌟 策略调整：不管是 Service 还是 Widget，都执行同样的数据注入逻辑！
        if (lpparam.packageName.equals(PKG_SERVICE) || lpparam.packageName.equals(PKG_WIDGET)) {
            initUniversalHook(lpparam);
        }
    }

    // =============================================================
    // 通用 Hook 逻辑：适用于 Service 和 Widget 两个进程
    // =============================================================
    private void initUniversalHook(XC_LoadPackage.LoadPackageParam lpparam) {
        String procName = lpparam.packageName.contains("service") ? "[LBSNavi]" : "[Widget]";

        // 1. 注册广播 (深度扫描 + 数据提取)
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    registerDeepScanner(context, procName);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Hook App onCreate Failed in " + procName);
        }

        // 2. 温柔劫持 API (给两个进程都喂饭)
        hookEcarxOpenApiGentle(lpparam, procName);
    }

    private void registerDeepScanner(Context context, String procName) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        String action = intent.getAction();
                        
                        if (AMAP_ACTION.equals(action)) {
                            Bundle bundle = intent.getExtras();
                            if (bundle != null) {
                                // 🔍 两个进程都打印日志，看看谁收到了
                                if (logCount++ % 20 == 0) {
                                    XposedBridge.log("🔍 " + procName + " 收到高德广播");
                                }

                                // 🔄 提取数据
                                extractData(bundle);
                                
                                // ⚡ 唤醒！(谁收到谁就喊一嗓子)
                                sendInternalWakeUp(ctx, procName);
                                
                                // 💡 反馈到 UI
                                if (logCount % 10 == 0) {
                                    sendAppLog(ctx, "⚡ " + procName + " 捕获: " + curRoadName);
                                    // 区分进程报告状态
                                    if (procName.contains("Widget")) sendAppLog(ctx, "STATUS_WIDGET_READY");
                                    if (procName.contains("LBSNavi")) sendAppLog(ctx, "STATUS_HOOK_READY (Active)");
                                }
                            }
                        }
                        else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                            if (procName.contains("Widget")) sendAppLog(ctx, "STATUS_WIDGET_READY");
                            if (procName.contains("LBSNavi")) sendAppLog(ctx, "STATUS_HOOK_READY (Echo)");
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
            XposedBridge.log("NaviHook: Scanner Registered in " + procName);
            
        } catch (Throwable t) {}
    }

    private void extractData(Bundle b) {
        // 路名
        String road = b.getString("CUR_ROAD_NAME");
        if (road == null) road = b.getString("cur_road_name");
        if (road == null) road = b.getString("ROAD_NAME");
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
        
        routeRemainDis = b.getInt("ROUTE_REMAIN_DIS", b.getInt("route_remain_dis", 0));
        routeRemainTime = b.getInt("ROUTE_REMAIN_TIME", b.getInt("route_remain_time", 0));
    }

    private void sendInternalWakeUp(Context ctx, String procName) {
        try {
            // 🌟 锁定 Vendor 2
            int targetVendor = 2;

            Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
            iStatus.putExtra("status", 1); 
            iStatus.putExtra("is_navi", true);
            iStatus.putExtra("vendor", targetVendor);
            iStatus.setPackage(PKG_WIDGET); // 依然发给 Widget，因为它是显示的排头兵
            ctx.sendBroadcast(iStatus);

            // 如果是 Service 进程，额外发一个给自己的通知（如果有必要）
            // 但通常广播是全局的，只要发出去大家都能收到

            Intent iRefresh = new Intent("ecarx.navi.REFRESH_WIDGET");
            iRefresh.setPackage(PKG_WIDGET);
            ctx.sendBroadcast(iRefresh);
        } catch (Throwable t) {}
    }

    // 🌟 核心修改：温柔 Hook 应用于所有进程
    private void hookEcarxOpenApiGentle(XC_LoadPackage.LoadPackageParam lpparam, String procName) {
        try {
            Class<?> apiClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.EcarxOpenApi", lpparam.classLoader);
            Class<?> cbClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.IAPIGetGuideInfoCallBack", lpparam.classLoader);
            
            XposedHelpers.findAndHookMethod(apiClass, "getGuideInfo", cbClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object callback = param.args[0];
                    if (callback != null) {
                        // 无论是在 LBSNavi 还是 Widget 里，只要有人问，我们就答！
                        XposedBridge.log("NaviHook: " + procName + " 正在请求数据，执行注入...");
                        XposedHelpers.callMethod(callback, "getGuideInfoResult",
                            1, routeRemainDis, routeRemainTime, 0, 0, 0,
                            nextRoadName, nextRoadName, 
                            0.5f, 0, segmentDis, turnIcon, 
                            curRoadName, routeRemainDis, routeRemainTime, 0, 0
                        );
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Hook API Failed in " + procName + ": " + t);
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
