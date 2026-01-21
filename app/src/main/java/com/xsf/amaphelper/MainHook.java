package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder; 
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
    
    // 东软 SDK 接口
    private static final String CLS_OPEN_API = "com.neusoft.nts.ecarxnavsdk.EcarxOpenApi";
    private static final String CLS_CALLBACK_GUIDE = "com.neusoft.nts.ecarxnavsdk.IAPIGetGuideInfoCallBack";
    
    private static final String PERMISSION_NAVI = "ecarx.oem.permission.OPENAPI_NAVI_PERMISSION";

    private static Context mServiceContext = null;
    private static boolean isHeartbeatRunning = false;
    private static boolean isReceiverRegistered = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 1. Hook NaviService (宿主 & 握手发射源)
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        // 2. Hook NaviWidget (显示端)
        // 🔴 关键修正：不再Hook MapTextureView的静态变量，防止崩溃！
        if (lpparam.packageName.equals(PKG_WIDGET)) {
            // 只做简单的 Activity 监听，不做危险操作
            initNaviWidgetSafeHook(lpparam);
        }
        
        // 3. 🌟 全局劫持 EcarxOpenApi (无论在哪个进程)
        // 这是让仪表盘获取数据的唯一途径（因为它主动拉取）
        hookEcarxOpenApi(lpparam);
    }

    // ===========================
    // 🗡️ 核心: API 劫持 (数据源头欺骗)
    // ===========================
    private void hookEcarxOpenApi(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> apiClass = XposedHelpers.findClass(CLS_OPEN_API, lpparam.classLoader);
            
            // 拦截 getGuideInfo
            XposedHelpers.findAndHookMethod(apiClass, "getGuideInfo", CLS_CALLBACK_GUIDE, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 只要心跳在跳，就劫持。不要犹豫。
                    if (!isHeartbeatRunning) return; 

                    XposedBridge.log("NaviHook: 拦截到 getGuideInfo，开始注入 V36 数据...");
                    
                    Object callback = param.args[0];
                    if (callback != null) {
                        // 17参数全量注入 (参考 Smali)
                        XposedHelpers.callMethod(callback, "getGuideInfoResult",
                            1, // type (1=Turn)
                            1000, // route_remain_dis
                            600, // route_remain_time
                            0, // camera_dist
                            0, // camera_type
                            0, // camera_speed
                            "V36安全版", // road_name
                            "V36安全版", // next_road_name
                            0.5f, // progress
                            0, // nav_type
                            500, // distance
                            2, // icon (左转)
                            "当前路名V36", // cur_road_name
                            1000, // total_dist
                            600, // total_time
                            0, // unknown
                            0 // unknown
                        );
                        // 阻止原方法，防止覆盖
                        param.setResult(true);
                    }
                }
            });
        } catch (Throwable t) {}
    }

    // ===========================
    // 🧠 NaviService Hook (维稳)
    // ===========================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 抢跑注入 (不死鸟)
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject instanceof Service) {
                    mServiceContext = (Context) param.thisObject;
                    ensureReceiverRegistered(mServiceContext, lpparam.classLoader);
                    
                    // 发送双重日志，确保 App 能收到
                    sendAppLog(mServiceContext, "STATUS_HOOK_READY (V36-Safe)");
                    updateAppUIStatus(mServiceContext, 13);
                    
                    // 尝试点亮 Matrix
                    keepAliveAndGreen(lpparam.classLoader, mServiceContext);
                    
                    // 自动恢复心跳
                    if (!isHeartbeatRunning) {
                        handleStatusAction(lpparam.classLoader, mServiceContext, 13);
                    }
                }
            }
        });
        
        // 生存补丁
        try { XposedHelpers.findAndHookMethod("ecarx.naviservice.d.y", lpparam.classLoader, "b", String.class, XC_MethodReplacement.returnConstant(70500)); } catch (Throwable t) {}
    }

    // ===========================
    // 📺 NaviWidget 安全 Hook
    // ===========================
    private void initNaviWidgetSafeHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 仅仅监听 Activity 启动，不做任何导致崩溃的操作
            XposedHelpers.findAndHookMethod("com.ecarx.naviwidget.DisplayInfoActivity", lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context ctx = (Context) param.thisObject;
                    sendAppLog(ctx, "📺 仪表 Activity 启动 (V36)");
                    // Activity 启动时，发送一波握手信号
                    sendHandshakeBroadcasts(ctx, 1); 
                }
            });
        } catch (Throwable t) {}
    }

    private void ensureReceiverRegistered(Context ctx, ClassLoader cl) {
        if (isReceiverRegistered) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        String action = intent.getAction();
                        if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                            handleStatusAction(cl, context, intent.getIntExtra("status", 0));
                        }
                    } catch (Throwable t) {}
                }
            };
            ctx.registerReceiver(receiver, new IntentFilter("XSF_ACTION_SEND_STATUS"));
            isReceiverRegistered = true;
        } catch (Throwable t) {}
    }

    // 🤝 握手信号 (V35 逻辑保留)
    private void sendHandshakeBroadcasts(Context ctx, int vendor) {
        try {
            // 1. 状态机激活 (Status=1, Route=0)
            Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
            iStatus.putExtra("status", 1);
            iStatus.putExtra("is_navi", true);
            iStatus.putExtra("vendor", vendor);
            iStatus.putExtra("route_state", 0); 
            ctx.sendBroadcast(iStatus, PERMISSION_NAVI);

            // 2. 强制刷新 Widget (触发它去调用 getGuideInfo)
            ctx.sendBroadcast(new Intent("ecarx.navi.REFRESH_WIDGET"), PERMISSION_NAVI);
            
            // 3. Surface 信号 (只发广播，不改代码)
            Intent iSurface = new Intent("ecarx.navi.SURFACE_CHANGED");
            iSurface.putExtra("isShow", true);
            ctx.sendBroadcast(iSurface, PERMISSION_NAVI);

        } catch (Throwable t) {}
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        if (isHeartbeatRunning) return;
        isHeartbeatRunning = true;
        
        new Thread(() -> {
            sendAppLog(ctx, "💓 V36 安全劫持引擎启动...");
            int count = 0;
            while (isHeartbeatRunning) {
                try {
                    // 物理维持
                    if (count % 5 == 0) keepAliveAndGreen(cl, ctx);
                    
                    // 🌟 轮询握手 (Vendor 1 & 4)
                    // 我们不发 GUIDEINFO 广播了，因为我们已经劫持了 API
                    // 我们只需要发握手信号，诱导仪表盘去调用 API
                    int currentVendor = (count % 2 == 0) ? 1 : 4;
                    sendHandshakeBroadcasts(ctx, currentVendor);
                    
                    // 补发焦点
                    Intent iFocus = new Intent("com.ecarx.intent.action.NAVI_FOCUS_GAIN");
                    iFocus.putExtra("packageName", "com.autonavi.amapauto");
                    ctx.sendBroadcast(iFocus, PERMISSION_NAVI);

                    Thread.sleep(1500); 
                    count++;
                } catch (Exception e) { break; }
            }
        }).start();
    }

    private void keepAliveAndGreen(ClassLoader cl, Context ctx) {
        try {
            Class<?> q = XposedHelpers.findClass("q", cl);
            Object mgr = XposedHelpers.getStaticObjectField(q, "a");
            if (mgr == null) {
                mgr = XposedHelpers.newInstance(XposedHelpers.findClass("l", cl));
                XposedHelpers.setStaticObjectField(q, "a", mgr);
            }
            Object conn = XposedHelpers.getObjectField(mgr, "i");
            if (conn != null) {
                XposedHelpers.callMethod(conn, "onServiceConnected", new ComponentName("f","f"), new Binder());
            }
        } catch (Throwable t) {}
    }

    // 🌟 双重广播：解决 App 灯不亮问题
    private void updateAppUIStatus(Context ctx, int status) {
        try {
            Intent i = new Intent("com.xsf.amaphelper.STATUS_UPDATE");
            i.setPackage(PKG_SELF); // 显式
            i.putExtra("status", status);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {}
        try {
            Intent i = new Intent("com.xsf.amaphelper.STATUS_UPDATE"); // 隐式备用
            i.putExtra("status", status);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {}
    }

    private void sendAppLog(Context ctx, String log) {
        if (ctx == null) return;
        try {
            Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
            i.setPackage(PKG_SELF);
            i.putExtra("log", log);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {}
        try {
            Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
            i.putExtra("log", log);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {}
    }
}
