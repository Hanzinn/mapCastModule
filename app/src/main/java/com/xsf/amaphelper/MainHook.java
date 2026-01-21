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
    
    // 🔑 必须带权限
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

        // 1. 处理 NaviService (逻辑大脑)
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        // 2. 处理 NaviWidget (显示终端)
        if (lpparam.packageName.equals(PKG_WIDGET)) {
            initNaviWidgetHook(lpparam);
        }
    }

    // ===========================
    // 🧠 NaviService 端 Hook
    // ===========================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 🌟 抢跑注入 + 显式日志
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject instanceof Service) {
                    mServiceContext = (Context) param.thisObject;
                    ensureReceiverRegistered(mServiceContext, lpparam.classLoader);
                    
                    // 🌟 关键：发送显式日志，点亮 App 灯
                    sendAppLog(mServiceContext, "STATUS_HOOK_READY (V35-Final)");
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
        
        // 辅助：生存补丁
        try { XposedHelpers.findAndHookMethod("ecarx.naviservice.d.y", lpparam.classLoader, "b", String.class, XC_MethodReplacement.returnConstant(70500)); } catch (Throwable t) {}
    }

    // ===========================
    // 📺 NaviWidget 端 Hook (焊死开关)
    // ===========================
    private void initNaviWidgetHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 强制开启 MapTextureView
            Class<?> mtvClass = XposedHelpers.findClass("com.ecarx.naviwidget.view.MapTextureView", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(mtvClass, "setSurfaceStatus", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[0] = true; // 强制 true
                    try { XposedHelpers.setStaticBooleanField(mtvClass, "c", true); } catch(Throwable t){}
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

    // 🤝 V35 核心：身份轮询广播 (Vendor 1 & 4)
    private void sendV35Protocol(Context ctx, int count) {
        try {
            // 🌟 轮询机制：偶数发 Vendor 1 (系统)，奇数发 Vendor 4 (高德)
            int currentVendor = (count % 2 == 0) ? 1 : 4;

            // 1. 状态机激活 (Status=1, Route=0)
            Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
            iStatus.putExtra("status", 1);
            iStatus.putExtra("is_navi", true);
            iStatus.putExtra("vendor", currentVendor); // 动态身份
            iStatus.putExtra("route_state", 0); 
            ctx.sendBroadcast(iStatus, PERMISSION_NAVI);

            // 2. 17参数全量引导 (针对 509G 优化)
            Intent iGuide = new Intent("ecarx.navi.UPDATE_GUIDEINFO");
            iGuide.putExtra("road_name", "V35轮询-V" + currentVendor);
            iGuide.putExtra("next_road_name", "V35成功");
            iGuide.putExtra("distance", 500 + (count % 10)); // 动态距离
            iGuide.putExtra("icon", 2);
            iGuide.putExtra("progress", 0.5f);
            iGuide.putExtra("vendor", currentVendor);
            
            // 补充字段 (防漏)
            iGuide.putExtra("total_dist", 2000);
            iGuide.putExtra("total_time", 1200);
            iGuide.putExtra("guide_type", 1);
            iGuide.putExtra("nav_type", 0);
            iGuide.putExtra("type", 1);
            
            ctx.sendBroadcast(iGuide, PERMISSION_NAVI);

            // 3. Surface 强启
            Intent iSurface = new Intent("ecarx.navi.SURFACE_CHANGED");
            iSurface.putExtra("isShow", true);
            ctx.sendBroadcast(iSurface, PERMISSION_NAVI);
            
            // 4. 强制刷新 Widget (双保险)
            ctx.sendBroadcast(new Intent("ecarx.navi.REFRESH_WIDGET"), PERMISSION_NAVI);
            
            // 5. 维持 App UI 状态 (每3秒发一次)
            if (count % 2 == 0) updateAppUIStatus(ctx, 13);

        } catch (Throwable t) {}
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        if (isHeartbeatRunning) return;
        isHeartbeatRunning = true;
        
        new Thread(() -> {
            sendAppLog(ctx, "💓 V35 身份轮询引擎启动...");
            int count = 0;
            while (isHeartbeatRunning) {
                try {
                    // 物理维持
                    keepAliveAndGreen(cl, ctx);
                    
                    // 协议发送
                    sendV35Protocol(ctx, count);
                    
                    Thread.sleep(1500);
                    count++;
                } catch (Exception e) { break; }
            }
        }).start();
    }

    // 🚑 Matrix Lite (物理层)
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

    // 🌟 关键修复：向 App 发送显式状态更新广播
    private void updateAppUIStatus(Context ctx, int status) {
        try {
            Intent i = new Intent("com.xsf.amaphelper.STATUS_UPDATE"); // 确保 App 端 Manifest 注册了这个 Action
            i.setPackage(PKG_SELF); // 显式指定包名
            i.putExtra("status", status);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {}
    }

    // 🌟 关键修复：发送显式日志广播
    private void sendAppLog(Context ctx, String log) {
        if (ctx == null) return;
        try {
            Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
            i.setPackage(PKG_SELF); // 显式指定包名
            i.putExtra("log", log);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {}
    }
}
