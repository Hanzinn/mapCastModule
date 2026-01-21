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
    
    private static Context mServiceContext = null;
    private static boolean isHeartbeatRunning = false;
    private static boolean isReceiverRegistered = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 1. 处理 NaviService (广播源)
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        // 2. 处理 NaviWidget (显示端)
        if (lpparam.packageName.equals(PKG_WIDGET)) {
            initNaviWidgetHook(lpparam);
        }
    }

    // ===========================
    // 🧠 NaviService 端 Hook
    // ===========================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 抢跑注入 (AttachBaseContext)
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if ((param.thisObject instanceof Service) || (param.thisObject instanceof Application)) {
                    mServiceContext = (Context) param.thisObject;
                    sendAppLog(mServiceContext, "STATUS_HOOK_READY (V32-Final)");
                    ensureReceiverRegistered(mServiceContext);
                    
                    // 物理层维持 (Matrix)
                    keepAliveAndGreen(lpparam.classLoader, mServiceContext);
                    
                    // 自动恢复
                    if (!isHeartbeatRunning) {
                        handleStatusAction(lpparam.classLoader, mServiceContext, 13);
                    }
                }
            }
        });
        
        // 版本欺骗
        try { XposedHelpers.findAndHookMethod("ecarx.naviservice.d.y", lpparam.classLoader, "b", String.class, XC_MethodReplacement.returnConstant(70500)); } catch (Throwable t) {}
    }

    // ===========================
    // 📺 NaviWidget 端 Hook (双重锁定)
    // ===========================
    private void initNaviWidgetHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> mtvClass = XposedHelpers.findClass("com.ecarx.naviwidget.view.MapTextureView", lpparam.classLoader);
            
            // 1. 拦截 setSurfaceStatus 方法
            XposedHelpers.findAndHookMethod(mtvClass, "setSurfaceStatus", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[0] = true; // 强制传参 true
                    
                    // 2. 🌟 强制修改静态字段 c (mIsAddSurface)
                    // 这是 V32 的核心改进：直接修改内存中的开关
                    try { 
                        XposedHelpers.setStaticBooleanField(mtvClass, "c", true); 
                    } catch(Throwable t) {
                        XposedBridge.log("NaviHook: 静态字段 c 修改失败: " + t);
                    }
                    XposedBridge.log("NaviHook: MapTextureView 双重锁定已执行");
                }
            });
            
            // 监听 Activity 启动
            XposedHelpers.findAndHookMethod("com.ecarx.naviwidget.DisplayInfoActivity", lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sendAppLog((Context) param.thisObject, "📺 仪表 Activity 启动 (V32)");
                }
            });
            
        } catch (Throwable t) {
            XposedBridge.log("NaviHook Widget Error: " + t.getMessage());
        }
    }

    // 注册监听器
    private void ensureReceiverRegistered(Context ctx) {
        if (isReceiverRegistered) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        String action = intent.getAction();
                        if ("XSF_ACTION_START_SERVICE".equals(action)) {
                            launchNaviComponents(context);
                        } 
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            // 手动触发一次 V32 协议广播
                            sendV32ProtocolBroadcasts(context, 0);
                        }
                        else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                            // 这里我们不再传递 classloader，因为广播发送不需要反射
                            // 心跳逻辑直接使用 context
                            startV32Heartbeat(context);
                        }
                    } catch (Throwable t) {}
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction("XSF_ACTION_START_SERVICE");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            filter.addAction("XSF_ACTION_SEND_STATUS");
            ctx.getApplicationContext().registerReceiver(receiver, filter);
            isReceiverRegistered = true;
        } catch (Throwable t) {}
    }

    // 🚀 V32 核心: 17参数全对齐广播
    private void sendV32ProtocolBroadcasts(Context ctx, int count) {
        try {
            // 1. 先发状态机预热：告诉仪表盘“路径已规划”
            Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
            iStatus.putExtra("status", 1); // 1 = 导航中
            iStatus.putExtra("is_navi", true);
            iStatus.putExtra("vendor", 1); // 🌟 V32: 锁定 Vendor 1 (系统原生)
            ctx.sendBroadcast(iStatus);

            // 2. 发送强制刷新指令
            ctx.sendBroadcast(new Intent("ecarx.navi.REFRESH_WIDGET"));

            // 3. 🌟 发送 17 参数全满的引导信息 (核心!)
            Intent iGuide = new Intent("ecarx.navi.UPDATE_GUIDEINFO");
            // 基础信息
            iGuide.putExtra("road_name", "V32协议对齐");
            iGuide.putExtra("next_road_name", "成功之路");
            iGuide.putExtra("distance", 500 + (count % 10));
            iGuide.putExtra("icon", 2); // 左转
            iGuide.putExtra("guide_type", 1); 
            
            // 🌟 V32 补全字段 (根据 IAPIGetGuideInfoCallBack.smali)
            iGuide.putExtra("type", 1);
            iGuide.putExtra("route_remain_dis", 1000);
            iGuide.putExtra("route_remain_time", 600);
            iGuide.putExtra("camera_dist", 0);
            iGuide.putExtra("camera_type", 0);
            iGuide.putExtra("camera_speed", 0);
            iGuide.putExtra("nav_type", 0);
            // 🌟 浮点数进度 (IAPIGetGuideInfoCallBack 第9个参数是 F)
            iGuide.putExtra("progress", 0.5f); 
            
            // 冗余字段 (防止 key 名称不同)
            iGuide.putExtra("total_dist", 1000);
            iGuide.putExtra("total_time", 600);
            iGuide.putExtra("cur_road_name", "V32协议对齐");
            
            ctx.sendBroadcast(iGuide);

            // 4. Surface 强启 (双重保障)
            Intent iSurface = new Intent("ecarx.navi.SURFACE_CHANGED");
            iSurface.putExtra("isShow", true);
            ctx.sendBroadcast(iSurface);

            sendAppLog(ctx, "📡 V32协议广播已发 (17参数)");
        } catch (Throwable t) {
            sendAppLog(ctx, "广播异常: " + t.getMessage());
        }
    }

    // 启动组件
    private void launchNaviComponents(Context ctx) {
        try {
            Intent sIntent = new Intent();
            sIntent.setComponent(new ComponentName("com.ecarx.naviwidget", "com.ecarx.naviwidget.service.NaviWidgetService"));
            sIntent.setAction("com.ecarx.intent.action.NAVI_WIDGET");
            ctx.startService(sIntent);

            Intent aIntent = new Intent();
            aIntent.setComponent(new ComponentName("com.ecarx.naviwidget", "com.ecarx.naviwidget.DisplayInfoActivity"));
            aIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(aIntent);
        } catch (Throwable t) {}
    }

    // Matrix Lite (维持绿灯)
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
            }
        } catch (Throwable t) {}
    }

    // 💓 V32 心跳
    private void startV32Heartbeat(Context ctx) {
        if (isHeartbeatRunning) return;
        isHeartbeatRunning = true;
        
        new Thread(() -> {
            sendAppLog(ctx, "💓 V32 协议对齐心跳已启动...");
            int count = 0;
            while (isHeartbeatRunning) { 
                try {
                    // 发送全量协议广播
                    sendV32ProtocolBroadcasts(ctx, count);
                    
                    // 补发焦点
                    Intent iFocus = new Intent("com.ecarx.intent.action.NAVI_FOCUS_GAIN");
                    iFocus.putExtra("packageName", "com.autonavi.amapauto");
                    ctx.sendBroadcast(iFocus);

                    // 尝试拉起组件 (每10秒)
                    if (count % 5 == 0) launchNaviComponents(ctx);

                    Thread.sleep(1500); 
                    count++;
                } catch (Exception e) { break; }
            }
            isHeartbeatRunning = false;
            sendAppLog(ctx, "💔 心跳停止");
        }).start();
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        if (status == 13) {
            keepAliveAndGreen(cl, ctx);
            sendAppLog(ctx, ">>> 启动 V32 协议对齐 <<<");
            launchNaviComponents(ctx);
            startV32Heartbeat(ctx);
        } else if (status == 29) {
            isHeartbeatRunning = false;
            ctx.sendBroadcast(new Intent("ecarx.navi.STOP_NAVI"));
        }
    }

    private void sendAppLog(Context ctx, String log) {
        try {
            Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
            i.putExtra("log", log);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {}
    }
}
