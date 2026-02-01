package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.view.Surface;
import java.lang.reflect.Method;
import java.util.Timer;
import java.util.TimerTask;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_MAP = "com.autonavi.amapauto";
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    private static final String TARGET_SERVICE = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    private static final String ACTION_VERSION_CHECK = "com.xsf.amaphelper.VERSION_CHECK";

    // 静态变量，保持你 V225 的优秀结构
    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static Object amapSurfaceMgr; 
    private static Timer statusHeartbeat;
    private static boolean isSystemReady = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // =============================================================
        // 🏰 战场 A：高德地图进程
        // =============================================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            // 1. Hook 分辨率
            hookSurfaceDimensions(lpparam.classLoader);

            // 2. 版本广播 (7.5/9.1 识别)
            boolean isLegacy75 = XposedHelpers.findClassIfExists("com.AutoHelper", lpparam.classLoader) != null;
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sendVersionBroadcast(ctx, isLegacy75), 3000);
                    sendVersionBroadcast(ctx, isLegacy75);
                }
            });

            // 3. 9.1 植入 TrojanBinder (Map 端核心)
            if (!isLegacy75) {
                XposedBridge.log("NaviHook: [Map] ⚡ 识别为 9.1，植入 V226 Binder。");
                try {
                    XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            param.setResult(new TrojanBinder(lpparam.classLoader));
                        }
                    });
                } catch (Throwable t) {}
            }
        }

        // =============================================================
        // 🚗 战场 B：车机系统进程
        // =============================================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    
                    registerVersionReceiver();
                    
                    // 兜底：10秒没动静就强制启动
                    sysHandler.postDelayed(() -> {
                        if (!isSystemReady) {
                            XposedBridge.log("NaviHook: [Sys] ⚠️ 等待超时，强制 9.1 模式");
                            initAs91();
                        }
                    }, 10000);
                }
            });

            // 破解 Vendor 校验
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}
            
            // 移除了 hookBindService，因为它会注入假 Binder 导致黑屏
            hookPackageManager(lpparam.classLoader);
        }
    }

    // =============================================================
    // 📡 核心逻辑 (V224 "官方连接法" + 静态结构)
    // =============================================================
    
    private static void initAs91() {
        if (sysContext == null) return;
        try {
            // 1. 初始化 DashboardMgr (用于发状态)
            ClassLoader cl = sysContext.getClassLoader();
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            
            // 2. 初始化 AmapSurfaceMgr 并触发连接
            initAndTriggerAmapSurfaceMgr();
            
            // 3. 启动心跳
            startStatusHeartbeat(false);
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] InitAs91 Error: " + t);
        }
    }

    private static void initAndTriggerAmapSurfaceMgr() {
        if (sysContext == null) return;
        sysHandler.postDelayed(() -> {
            try {
                ClassLoader cl = sysContext.getClassLoader();
                Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.map.amap.h", cl);
                // 获取 AmapSurfaceAidlManager 单例
                amapSurfaceMgr = XposedHelpers.getStaticObjectField(mgrClass, "e");
                
                if (amapSurfaceMgr != null) {
                    XposedBridge.log("NaviHook: [Sys] ✅ 获取 AmapSurfaceAidlManager 成功");
                    
                    // 🔥 这里的关键：调用官方的连接方法 bindWidgetService
                    // 这会让系统建立真正的 Binder 连接，而不是我们伪造的
                    try {
                        XposedHelpers.callMethod(amapSurfaceMgr, "bindWidgetService");
                        XposedBridge.log("NaviHook: [Sys] 🚀 调用 bindWidgetService() 成功，系统正前往连接...");
                        
                        // 配合一次唤醒
                        triggerWakeUp();
                        
                    } catch (Throwable t) {
                        XposedBridge.log("NaviHook: [Sys] ❌ 调用 bindWidgetService 失败，尝试盲扫...");
                        tryScanAndBind(mgrClass, amapSurfaceMgr);
                    }
                } else {
                    XposedBridge.log("NaviHook: [Sys] ⚠️ Manager 为空，3秒后重试...");
                    sysHandler.postDelayed(() -> initAndTriggerAmapSurfaceMgr(), 3000);
                }
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Sys] ❌ 获取 Manager 异常: " + t);
            }
        }, 2000);
    }

    // 备用：如果 bindWidgetService 名字被混淆，盲试所有无参 void 方法
    private static void tryScanAndBind(Class<?> clazz, Object instance) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == void.class) {
                String name = m.getName();
                if (name.equals("wait") || name.equals("notify") || name.equals("notifyAll")) continue;
                
                XposedBridge.log("NaviHook: [Sys] 🔄 盲试调用: " + name);
                try {
                    m.setAccessible(true);
                    m.invoke(instance);
                } catch (Exception e) {}
            }
        }
        triggerWakeUp();
    }

    private static void triggerWakeUp() {
        if (dashboardMgr == null || sysContext == null) return;
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", 3);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);
            
            Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
            XposedHelpers.setIntField(st, "status", 16);
            XposedHelpers.callMethod(dashboardMgr, "a", st);
            XposedBridge.log("NaviHook: [Sys] ⚡ 唤醒指令已发送");
        } catch (Throwable t) {}
    }

    private static void startStatusHeartbeat(boolean isLoop) {
        if (statusHeartbeat != null) statusHeartbeat.cancel();
        statusHeartbeat = new Timer();
        statusHeartbeat.schedule(new TimerTask() {
            @Override
            public void run() {
                triggerWakeUp();
            }
        }, 1000, isLoop ? 3000 : 9999999);
    }

    // =============================================================
    // 🦄 TrojanBinder (Map进程) - 针对 9.1 的完美协议
    // =============================================================
    public static class TrojanBinder extends Binder {
        private ClassLoader classLoader;
        private boolean isSurfaceActive = false;
        private Handler uiHandler;

        public TrojanBinder(ClassLoader cl) {
            this.classLoader = cl;
            this.uiHandler = new Handler(Looper.getMainLooper());
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                // Code 1: 9.1 的 addSurface 指令
                if (code == 1) {
                    XposedBridge.log("NaviHook: [Binder] 🔥 收到 Code 1 (addSurface)");
                    
                    if (isSurfaceActive) {
                        if (reply != null) reply.writeNoException();
                        return true;
                    }

                    // 暴力解析
                    Surface surface = tryExtendedBruteForce(data);
                    
                    if (surface != null && surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ✅✅✅ 挖到 Surface! 注入!");
                        final Surface s = surface;
                        uiHandler.post(() -> injectNativeEngine(s));
                        isSurfaceActive = true;
                    } else {
                        XposedBridge.log("NaviHook: [Binder] ❌ Surface 解析失败");
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // Code 2: removeSurface
                if (code == 2) { 
                    XposedBridge.log("NaviHook: [Binder] 收到 Code 2 (Reset)");
                    isSurfaceActive = false;
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // Code 3: isMapRunning (必须回 true 保持连接)
                if (code == 3) {
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(1); // true
                    }
                    return true;
                }
                
                // Code 4: 某些版本的 addSurface
                if (code == 4) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }

            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Binder] Error: " + t);
            }
            return true;
        }

        private Surface tryExtendedBruteForce(Parcel data) {
            int originalPos = data.dataPosition();
            for (int offset = 0; offset <= 128; offset += 4) {
                if (offset >= data.dataSize()) break;
                try {
                    data.setDataPosition(offset);
                    Surface s = Surface.CREATOR.createFromParcel(data);
                    if (s != null && s.isValid()) return s;
                } catch (Throwable e) {}
            }
            data.setDataPosition(originalPos);
            return null;
        }

        private void injectNativeEngine(Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                
                Method mCreate = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                mCreate.invoke(null, 1, 2, surface);
                XposedBridge.log("NaviHook: [Map] ✅ Created 调用成功");

                try {
                    Method mRedraw = XposedHelpers.findMethodExact(cls, "nativeSurfaceRedrawNeeded", int.class, int.class, Surface.class);
                    mRedraw.invoke(null, 1, 2, surface);
                    XposedBridge.log("NaviHook: [Map] ✅ Redraw 调用成功");
                } catch (Throwable t) { 
                    // 兜底 Redraw
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals("nativeSurfaceRedrawNeeded")) {
                            m.setAccessible(true);
                            if (m.getParameterCount() == 0) m.invoke(null);
                            else if (m.getParameterCount() == 2) m.invoke(null, 1, 2);
                        }
                    }
                }
            } catch (Throwable t) { 
                isSurfaceActive = false; 
            }
        }
    }

    // =============================================================
    // 辅助工具 (分辨率Hook, PM欺骗, 广播)
    // =============================================================
    private static void hookSurfaceDimensions(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", cl);
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals("getMapSurfaceWidth")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam param) { return 1920; }
                    });
                }
                if (m.getName().equals("getMapSurfaceHeight")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam param) { return 720; }
                    });
                }
                if (m.getName().equals("getMapSurfaceDpi")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam param) { return 240; }
                    });
                }
            }
        } catch (Throwable t) {}
    }

    private static void hookPackageManager(ClassLoader cl) {
        XC_MethodHook spoofHook = new XC_MethodHook() {
            @SuppressWarnings("unchecked")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null && TARGET_SERVICE.equals(intent.getComponent().getClassName())) {
                    Object result = param.getResult();
                    boolean isEmpty = false;
                    if (result == null) isEmpty = true;
                    else if (result instanceof java.util.List) isEmpty = ((java.util.List) result).isEmpty();
                    
                    if (isEmpty) {
                        android.content.pm.ResolveInfo info = new android.content.pm.ResolveInfo();
                        info.serviceInfo = new android.content.pm.ServiceInfo();
                        info.serviceInfo.packageName = PKG_MAP;
                        info.serviceInfo.name = TARGET_SERVICE;
                        info.serviceInfo.exported = true;
                        info.serviceInfo.applicationInfo = new android.content.pm.ApplicationInfo();
                        info.serviceInfo.applicationInfo.packageName = PKG_MAP;
                        
                        if (result instanceof java.util.List) {
                            java.util.List list = new java.util.ArrayList();
                            list.add(info);
                            param.setResult(list);
                        } else {
                            param.setResult(info);
                        }
                    }
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", cl, "queryIntentServices", Intent.class, int.class, spoofHook);
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", cl, "resolveService", Intent.class, int.class, spoofHook);
        } catch (Throwable t) {}
    }

    private static void sendVersionBroadcast(Context ctx, boolean is75) {
        try {
            Intent intent = new Intent(ACTION_VERSION_CHECK);
            intent.setPackage(PKG_SERVICE);
            intent.putExtra("is_75", is75);
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {}
    }

    private static void registerVersionReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_VERSION_CHECK);
        sysContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean is75 = intent.getBooleanExtra("is_75", false);
                XposedBridge.log("NaviHook: [Sys] 📩 收到广播: " + (is75 ? "7.5" : "9.1"));
                if (is75) {
                    // 7.5 逻辑 (V226 选择静默，因为 7.5 本身就能跑)
                } else {
                    initAs91();
                }
                isSystemReady = true;
            }
        }, filter);
    }
}