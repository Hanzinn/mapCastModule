package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.IBinder; 
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_WIDGET = "com.ecarx.naviwidget"; // 🎯 新增目标
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // --- Service 混淆类 ---
    private static final String CLS_PROTOCOL_FACTORY = "j"; 
    private static final String CLS_PROTOCOL_MGR = "g"; 
    private static final String CLS_WIDGET_MGR_HOLDER = "q"; 
    private static final String CLS_WIDGET_MGR = "l"; 
    private static final String CLS_WIDGET_CONNECTION = "o";
    private static final String CLS_VERSION_UTIL = "y"; 
    
    private static final String CLS_SERVICE = "ecarx.naviservice.service.NaviService";
    private static final String CLS_NEUSOFT_SDK = "ecarx.naviservice.map.d.a"; 

    // --- Widget 混淆类 ---
    // 注意：这里的包名是 view，不是 widget
    private static final String CLS_MAP_TEXTURE_VIEW = "com.ecarx.naviwidget.view.MapTextureView";

    private static Context mServiceContext = null;
    private static boolean isHeartbeatRunning = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 0. 自身激活检测
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 1. 处理 NaviService (逻辑大脑)
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        // 2. 处理 NaviWidget (显示终端) - 🌟 V29 新增
        if (lpparam.packageName.equals(PKG_WIDGET)) {
            initNaviWidgetHook(lpparam);
        }
    }

    // ===========================
    // 📺 NaviWidget 端 Hook 逻辑 (直接操作 UI)
    // ===========================
    private void initNaviWidgetHook(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("NaviHook: 注入 NaviWidget 成功");
        
        try {
            Class<?> mtvClass = XposedHelpers.findClass(CLS_MAP_TEXTURE_VIEW, lpparam.classLoader);
            
            // 劫持 setSurfaceStatus(boolean)
            XposedHelpers.findAndHookMethod(mtvClass, "setSurfaceStatus", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    boolean original = (boolean) param.args[0];
                    // 强制改为 true，告诉 View 打开 Surface
                    param.args[0] = true;
                    // 强制设置静态标记 c (mIsAddSurface) 为 true
                    try { XposedHelpers.setStaticBooleanField(mtvClass, "c", true); } catch(Throwable t){}
                    
                    XposedBridge.log("NaviHook: 拦截 setSurfaceStatus(" + original + ") -> 强制改为 true");
                }
            });
            
            // 可选：Hook onAttachedToWindow 确保初始化
            XposedHelpers.findAndHookMethod(mtvClass, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("NaviHook: MapTextureView 已附加到窗口");
                    // 这里可以尝试主动调用一次 private void a(int) 
                    // 但由于混淆名不确定，暂时通过 setSurfaceStatus 触发
                }
            });

        } catch (Throwable t) {
            XposedBridge.log("NaviHook Widget Error: " + t.getMessage());
        }
    }

    // ===========================
    // 🧠 NaviService 端 Hook 逻辑 (发送指令)
    // ===========================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. 注入反馈
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context appCtx = (Context) param.thisObject;
                sendAppLog(appCtx, "STATUS_HOOK_READY (V29-Surface)");
                registerReceiver(appCtx, lpparam.classLoader);
            }
        });

        // 2. Context 捕获
        try {
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mServiceContext = (Context) param.thisObject;
                    sendAppLog(mServiceContext, "STATUS_SERVICE_RUNNING (Resumed)");
                    if (!isHeartbeatRunning) {
                        handleStatusAction(lpparam.classLoader, mServiceContext, 13);
                    }
                }
            });
        } catch (Throwable t) {}

        // 3. 生存补丁
        try { XposedHelpers.findAndHookMethod(CLS_VERSION_UTIL, lpparam.classLoader, "b", String.class, XC_MethodReplacement.returnConstant(70500)); } catch (Throwable t) {}

        // 4. 心脏起搏
        try {
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_FACTORY, lpparam.classLoader, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object inst = param.getResult();
                    if (inst != null) XposedHelpers.setBooleanField(inst, "c", true);
                }
            });
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, lpparam.classLoader, "f", XC_MethodReplacement.returnConstant(true));
        } catch (Throwable t) {}
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if ("XSF_ACTION_START_SERVICE".equals(action)) {
                        sendAppLog(ctx, "Service运行中 (V29)");
                    } 
                    else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                        // V29: 手动触发一次强启广播
                        sendSurfaceBroadcast(ctx);
                        sendWidgetUpdateBroadcast(ctx, "V29手动测试", 100);
                    }
                    else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                        handleStatusAction(cl, ctx, intent.getIntExtra("status", 0));
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction("XSF_ACTION_START_SERVICE");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            filter.addAction("XSF_ACTION_SEND_STATUS");
            context.getApplicationContext().registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    // 📺 V29 关键: 发送 Surface 强启广播
    private void sendSurfaceBroadcast(Context ctx) {
        try {
            Context target = (mServiceContext != null) ? mServiceContext : ctx;
            
            // 目标: 触发 e.smali 中的 onMapSurfaceViewChanged 或 onLauncherStatusChange
            Intent intent = new Intent("ecarx.navi.SURFACE_CHANGED");
            // 穷举参数
            intent.putExtra("isShow", true);
            intent.putExtra("status", true);
            intent.putExtra("visible", true);
            
            target.sendBroadcast(intent);
            sendAppLog(ctx, "📺 Surface广播已发");
        } catch (Throwable t) {}
    }

    // 📡 V29 关键: 发送 Widget 协议广播 (TBT数据)
    private void sendWidgetUpdateBroadcast(Context ctx, String roadName, int distance) {
        try {
            Context target = (mServiceContext != null) ? mServiceContext : ctx;
            
            // 发送 UPDATE_GUIDEINFO (根据 Manifest)
            Intent iGuide = new Intent("ecarx.navi.UPDATE_GUIDEINFO");
            iGuide.putExtra("road_name", roadName);
            iGuide.putExtra("next_road_name", roadName);
            iGuide.putExtra("distance", distance);
            iGuide.putExtra("icon", 2); // 左转
            iGuide.putExtra("guide_type", 1); // Start
            target.sendBroadcast(iGuide);
            
            // 发送 UPDATE_STATUS
            Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
            iStatus.putExtra("status", 1); 
            iStatus.putExtra("is_navi", true);
            target.sendBroadcast(iStatus);

            sendAppLog(ctx, "📡 Widget TBT广播已发");
        } catch (Throwable t) {}
    }

    // 焦点抢占 (V22)
    private void grabNaviFocus(Context ctx) {
        try {
            Context target = (mServiceContext != null) ? mServiceContext : ctx;
            Intent i1 = new Intent("ecarx.intent.action.NAVI_STATE_CHANGE");
            i1.putExtra("NAVI_STATE", 1); 
            target.sendBroadcast(i1);
            Intent i2 = new Intent("com.ecarx.intent.action.NAVI_FOCUS_GAIN");
            i2.putExtra("packageName", "com.autonavi.amapauto");
            target.sendBroadcast(i2);
        } catch (Throwable t) {}
    }

    // 东软内核注入 (V28)
    private void injectNeusoftData(ClassLoader cl, Context ctx) {
        try {
            Class<?> neuClass = XposedHelpers.findClass(CLS_NEUSOFT_SDK, cl);
            Object neuInst = XposedHelpers.callStaticMethod(neuClass, "a");
            if (neuInst == null) neuInst = XposedHelpers.newInstance(neuClass);
            if (neuInst != null) {
                try { XposedHelpers.callMethod(neuInst, "a", ctx); } catch(Throwable t){}
                // 尝试各种可能的初始化/发送方法
                try { XposedHelpers.callMethod(neuInst, "a", "V29东软数据"); } catch(Throwable t){}
            }
        } catch (Throwable t) {}
    }

    // 💓 V29 混合心跳
    private void startV29Heartbeat(ClassLoader cl, Context ctx) {
        if (isHeartbeatRunning) return;
        isHeartbeatRunning = true;
        
        new Thread(() -> {
            sendAppLog(ctx, "💓 V29 图层强启心跳启动...");
            int count = 0;
            while (isHeartbeatRunning) { 
                try {
                    // 1. 刷新 Surface 状态 (开屏幕)
                    sendSurfaceBroadcast(ctx);
                    
                    // 2. 发送 Widget 数据 (给画面)
                    sendWidgetUpdateBroadcast(ctx, "V29成功", 666);
                    
                    // 3. 东软内核注入 (保底)
                    injectNeusoftData(cl, ctx);
                    
                    // 4. 焦点补发
                    if (count % 3 == 0) grabNaviFocus(ctx);

                    Thread.sleep(1500); 
                    count++;
                } catch (Exception e) { break; }
            }
            isHeartbeatRunning = false;
            sendAppLog(ctx, "💔 心跳停止");
        }).start();
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        new Thread(()->{
            if (status == 13) {
                grabNaviFocus(ctx);
                try{Thread.sleep(500);}catch(Exception e){}

                sendAppLog(ctx, ">>> 启动 V29 Surface 强启 <<<");

                // 启动心跳
                startV29Heartbeat(cl, ctx);
                
                sendAppLog(ctx, "✅ 激活指令(Surface+Widget)已广播");
                
            } else if (status == 29) {
                isHeartbeatRunning = false;
                Intent iStop = new Intent("ecarx.navi.STOP_NAVI");
                ctx.sendBroadcast(iStop);
            }
        }).start();
    }

    private void sendAppLog(Context ctx, String log) {
        try {
            Context c = (ctx != null) ? ctx : android.app.AndroidAppHelper.currentApplication();
            if (c != null) {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.putExtra("log", log);
                c.sendBroadcast(i);
            }
        } catch (Throwable t) {}
    }
}
