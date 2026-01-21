package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder; // ✅ 修复: 补全 Binder 引用
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement; // ✅ 修复: 补全 XC_MethodReplacement 引用
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_WIDGET = "com.ecarx.naviwidget";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // 权限 (Manifest)
    private static final String PERMISSION_NAVI = "ecarx.oem.permission.OPENAPI_NAVI_PERMISSION";

    private static Context mServiceContext = null;
    // 跨进程变量不共享，仅用于 Service 进程控制心跳
    private static boolean isHeartbeatRunning = false; 
    // ✅ 修复: 补全 isReceiverRegistered 变量声明
    private static boolean isReceiverRegistered = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 1. Hook NaviService (宿主 & 发射源)
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        // 2. Hook NaviWidget (显示端)
        if (lpparam.packageName.equals(PKG_WIDGET)) {
            XposedBridge.log("NaviHook: 已注入 NaviWidget 进程");
            // 🔴 核心修复：直接开启劫持，不依赖 Service 进程的状态
            hookEcarxOpenApi(lpparam);
            // 监听 Activity 启动
            initNaviWidgetActivityHook(lpparam);
        }
    }

    // ===========================
    // 🗡️ API 劫持 (核心数据源 - V38无门槛版)
    // ===========================
    private void hookEcarxOpenApi(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> apiClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.EcarxOpenApi", lpparam.classLoader);
            Class<?> callbackClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.IAPIGetGuideInfoCallBack", lpparam.classLoader);
            
            // 拦截查询接口
            XposedHelpers.findAndHookMethod(apiClass, "getGuideInfo", callbackClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 🔴 V38 关键修改：移除 isHeartbeatRunning 检查！
                    // 只要组件来问，我们无条件注入！
                    
                    XposedBridge.log("NaviHook: [Widget进程] 拦截到 getGuideInfo，正在注入 V38 数据...");
                    
                    Object callback = param.args[0];
                    if (callback != null) {
                        // 17参数全量注入 (参考 Smali)
                        XposedHelpers.callMethod(callback, "getGuideInfoResult",
                            1, // type (1=转向)
                            888, // remain_dis
                            60, // remain_time
                            0, 0, 0, // camera
                            "V38无门槛", // road
                            "V38无门槛", // next_road
                            0.5f, // progress
                            0, // nav_type
                            500, // distance
                            2, // icon (左转)
                            "当前路名V38", 
                            888, 60, 0, 0 // total & unknown
                        );
                        param.setResult(true); // 拦截原调用，防止覆盖
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook API Hook Err: " + t);
        }
    }

    // ===========================
    // 🧠 NaviService Hook (负责发广播通知)
    // ===========================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 抢跑注入 (修复灯灭)
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject instanceof Service) {
                    mServiceContext = (Context) param.thisObject;
                    ensureReceiverRegistered(mServiceContext, lpparam.classLoader);
                    
                    // 显式日志
                    sendAppLog(mServiceContext, "STATUS_HOOK_READY (V38-Fix)");
                    updateAppUIStatus(mServiceContext, 13);
                    
                    // 物理绿灯
                    keepAliveAndGreen(lpparam.classLoader, mServiceContext);
                    
                    if (!isHeartbeatRunning) {
                        handleStatusAction(lpparam.classLoader, mServiceContext, 13);
                    }
                }
            }
        });
        
        try { XposedHelpers.findAndHookMethod("ecarx.naviservice.d.y", lpparam.classLoader, "b", String.class, XC_MethodReplacement.returnConstant(70500)); } catch (Throwable t) {}
    }

    // ===========================
    // 📺 NaviWidget Activity Hook (只为了触发更新)
    // ===========================
    private void initNaviWidgetActivityHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.ecarx.naviwidget.DisplayInfoActivity", lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // Activity 启动时，记录个日志
                    XposedBridge.log("NaviHook: [Widget进程] 仪表 Activity 已启动");
                }
            });
        } catch (Throwable t) {}
    }

    // 🤝 发送广播通知 Widget 更新 (在 Service 进程执行)
    private void sendUpdateBroadcasts(Context ctx, int count) {
        try {
            // 虽然我们劫持了 API，但发广播可以触发 Widget 主动去调用 API
            
            // 1. REFRESH_WIDGET
            Intent iRefresh = new Intent("ecarx.navi.REFRESH_WIDGET");
            iRefresh.setPackage(PKG_WIDGET);
            ctx.sendBroadcast(iRefresh, PERMISSION_NAVI);
            
            // 2. UPDATE_STATUS (让它确信在导航中)
            Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
            iStatus.putExtra("status", 1); 
            iStatus.putExtra("is_navi", true);
            iStatus.putExtra("vendor", (count % 2 == 0) ? 1 : 4); // 轮询身份
            iStatus.setPackage(PKG_WIDGET);
            ctx.sendBroadcast(iStatus, PERMISSION_NAVI);

        } catch (Throwable t) {}
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        if (isHeartbeatRunning) return;
        isHeartbeatRunning = true;
        
        new Thread(() -> {
            sendAppLog(ctx, "💓 V38 无门槛引擎启动...");
            int count = 0;
            while (isHeartbeatRunning) {
                try {
                    // 物理维持
                    if (count % 10 == 0) keepAliveAndGreen(cl, ctx);
                    
                    // 发送广播，刺激 Widget 去调用 getGuideInfo
                    sendUpdateBroadcasts(ctx, count);
                    
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

    // 辅助方法...
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

    private void updateAppUIStatus(Context ctx, int status) {
        try {
            Intent i = new Intent("com.xsf.amaphelper.STATUS_UPDATE");
            i.setPackage(PKG_SELF);
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
    }
}
