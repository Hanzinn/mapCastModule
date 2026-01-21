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
    
    // 🔑 必需权限 (Manifest 提取)
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

        // 1. 处理 NaviService (大脑)
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        // 2. 处理 NaviWidget (显示器)
        if (lpparam.packageName.equals(PKG_WIDGET)) {
            initNaviWidgetHook(lpparam);
        }
    }

    // ===========================
    // 🧠 NaviService 端 Hook
    // ===========================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 🌟 抢跑注入 (attachBaseContext)
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject instanceof Service) {
                    mServiceContext = (Context) param.thisObject;
                    sendAppLog(mServiceContext, "STATUS_HOOK_READY (V34-Explicit)"); // 显式日志测试
                    ensureReceiverRegistered(mServiceContext, lpparam.classLoader);
                    
                    // 自动恢复心跳
                    if (!isHeartbeatRunning) {
                        handleStatusAction(lpparam.classLoader, mServiceContext, 13);
                    }
                }
            }
        });

        // 稳健初始化
        try {
            XposedHelpers.findAndHookMethod("ecarx.naviservice.service.NaviService", lpparam.classLoader, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mServiceContext = (Context) param.thisObject;
                    sendAppLog(mServiceContext, "STATUS_SERVICE_RUNNING (V34)");
                    ensureReceiverRegistered(mServiceContext, lpparam.classLoader);
                }
            });
        } catch (Throwable t) {}
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
            
            // 监听 Activity 启动
            XposedHelpers.findAndHookMethod("com.ecarx.naviwidget.DisplayInfoActivity", lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context ctx = (Context) param.thisObject;
                    sendAppLog(ctx, "📺 仪表 Activity 已启动");
                    // 启动后立即握手
                    sendHandshakeBroadcasts(ctx);
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
                        if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            // 手动 B 计划：强制握手 + 点灯
                            keepAliveAndGreen(cl, context);
                            sendHandshakeBroadcasts(context);
                        } else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                            handleStatusAction(cl, context, intent.getIntExtra("status", 0));
                        }
                    } catch (Throwable t) {}
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction("XSF_ACTION_START_SERVICE");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            filter.addAction("XSF_ACTION_SEND_STATUS");
            ctx.registerReceiver(receiver, filter);
            isReceiverRegistered = true;
            sendAppLog(ctx, "✅ V34 监听器就绪");
        } catch (Throwable t) {}
    }

    // 🚑 Matrix Lite (物理绿灯)
    private void keepAliveAndGreen(ClassLoader cl, Context ctx) {
        try {
            Class<?> qClass = XposedHelpers.findClass("q", cl);
            Object mgr = XposedHelpers.getStaticObjectField(qClass, "a");
            if (mgr == null) {
                mgr = XposedHelpers.newInstance(XposedHelpers.findClass("l", cl));
                XposedHelpers.setStaticObjectField(qClass, "a", mgr);
            }
            Object conn = XposedHelpers.getObjectField(mgr, "i");
            if (conn != null) {
                XposedHelpers.callMethod(conn, "onServiceConnected", new ComponentName("fake", "fake"), new Binder());
                sendAppLog(ctx, "⚡ IPC 绿灯 (Matrix)");
            }
        } catch (Throwable t) {}
    }

    // 🤝 关键：Neusoft 握手协议 (Status 1 + Route 0)
    private void sendHandshakeBroadcasts(Context ctx) {
        try {
            // 1. 告诉仪表：地图已运行，且在导航中 (Status=1)
            Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
            iStatus.putExtra("status", 1); 
            iStatus.putExtra("is_navi", true);
            iStatus.putExtra("vendor", 1); // 伪装成 Vendor 1
            ctx.sendBroadcast(iStatus, PERMISSION_NAVI);

            // 2. 告诉仪表：路径规划成功 (RouteState=0) - 依据 smali 分析
            Intent iRoute = new Intent("ecarx.navi.UPDATE_STATUS"); // 通常复用这个 Action
            iRoute.putExtra("route_state", 0); 
            ctx.sendBroadcast(iRoute, PERMISSION_NAVI);
            
            // 3. 强制刷新 Widget
            ctx.sendBroadcast(new Intent("ecarx.navi.REFRESH_WIDGET"), PERMISSION_NAVI);
            
            // 4. Surface 强启
            Intent iSurface = new Intent("ecarx.navi.SURFACE_CHANGED");
            iSurface.putExtra("isShow", true);
            ctx.sendBroadcast(iSurface, PERMISSION_NAVI);

            sendAppLog(ctx, "🤝 握手信号已发送 (Status=1, Route=0)");
        } catch (Throwable t) {}
    }

    // 📡 V34 核心: 显式心跳 (带 17 参数)
    private void sendV34Heartbeat(Context ctx, int count) {
        try {
            Intent iGuide = new Intent("ecarx.navi.UPDATE_GUIDEINFO");
            // 基础字段
            iGuide.putExtra("road_name", "V34握手成功");
            iGuide.putExtra("next_road_name", "V34握手成功");
            iGuide.putExtra("distance", 500 + (count % 10));
            iGuide.putExtra("icon", 2); 
            iGuide.putExtra("guide_type", 1);
            
            // 17 参数补全 (对应 Smali)
            iGuide.putExtra("type", 1);
            iGuide.putExtra("route_remain_dis", 2000);
            iGuide.putExtra("route_remain_time", 1200);
            iGuide.putExtra("progress", 0.3f); // 浮点进度
            iGuide.putExtra("total_dist", 2000);
            iGuide.putExtra("total_time", 1200);
            
            // 显式权限发送
            ctx.sendBroadcast(iGuide, PERMISSION_NAVI);
            
        } catch (Throwable t) {}
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        if (isHeartbeatRunning) return;
        isHeartbeatRunning = true;
        
        new Thread(() -> {
            // 先尝试点亮绿灯
            keepAliveAndGreen(cl, ctx);
            // 发送握手
            sendHandshakeBroadcasts(ctx);
            
            sendAppLog(ctx, "💓 V34 显式心跳已启动...");
            
            int count = 0;
            while (isHeartbeatRunning) { 
                try {
                    // 发送全量数据
                    sendV34Heartbeat(ctx, count);
                    
                    // 补发焦点
                    Intent iFocus = new Intent("com.ecarx.intent.action.NAVI_FOCUS_GAIN");
                    iFocus.putExtra("packageName", "com.autonavi.amapauto");
                    ctx.sendBroadcast(iFocus, PERMISSION_NAVI);

                    // 周期性补发握手 (防止状态丢失)
                    if (count % 5 == 0) sendHandshakeBroadcasts(ctx);

                    Thread.sleep(1500); 
                    count++;
                } catch (Exception e) { break; }
            }
            isHeartbeatRunning = false;
            sendAppLog(ctx, "💔 引擎停止");
        }).start();
    }

    // 🌟 V34 修复：显式日志广播
    private void sendAppLog(Context ctx, String log) {
        if (ctx == null) return;
        try {
            Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
            i.setPackage("com.xsf.amaphelper"); // 🟢 显式指定包名，绕过 Android 9 限制
            i.putExtra("log", log);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {}
    }
}
