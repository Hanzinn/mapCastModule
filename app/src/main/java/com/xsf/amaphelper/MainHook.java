package com.xsf.amaphelper;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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

    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static Object amapSurfaceMgr; // 对应 ecarx.naviservice.map.amap.h
    private static Timer statusHeartbeat;

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
            // 1. Hook 分辨率 (宽1920, 高720) - 保持不变
            hookSurfaceDimensions(lpparam.classLoader);

            // 2. 版本广播 - 保持不变
            boolean isLegacy75 = XposedHelpers.findClassIfExists("com.AutoHelper", lpparam.classLoader) != null;
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sendVersionBroadcast(ctx, isLegacy75), 3000);
                    sendVersionBroadcast(ctx, isLegacy75);
                }
            });

            // 3. 9.1 植入 TrojanBinder (V224: 使用修正后的协议)
            if (!isLegacy75) {
                XposedBridge.log("NaviHook: [Map] ⚡ 识别为 9.1，植入 V224 (完美版)。");
                try {
                    XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            // 返回我们的特洛伊木马，等待 Code 1
                            param.setResult(new TrojanBinder(lpparam.classLoader));
                        }
                    });
                } catch (Throwable t) {}
            }
        }

        // =============================================================
        // 🚗 战场 B：车机系统进程 (使用 V222 的优雅调用逻辑)
        // =============================================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    
                    // 注册广播接收器，等待 Map 启动
                    registerVersionReceiver();
                    
                    // 兜底：10秒未收到广播则强制启动
                    sysHandler.postDelayed(() -> {
                        if (amapSurfaceMgr == null) {
                            XposedBridge.log("NaviHook: [Sys] ⚠️ 等待超时，强制执行连接逻辑");
                            initAs91();
                        }
                    }, 10000);
                }
            });

            hookPackageManager(lpparam.classLoader);
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}
        }
    }

    // =============================================================
    // 📡 V224 系统侧核心：通过 Manager 优雅连接
    // =============================================================
    
    private void initAs91() {
        // 1. 获取 DashboardMgr (用于发状态/心跳)
        initDashboardMgr();
        
        // 2. 获取 AmapSurfaceMgr 并调用 bindWidgetService
        initAndTriggerAmapSurfaceMgr();
        
        // 3. 启动心跳维持状态
        startStatusHeartbeat(false);
    }

    private void initAndTriggerAmapSurfaceMgr() {
        if (sysContext == null) return;
        sysHandler.postDelayed(() -> {
            try {
                // 加载目标类：ecarx.naviservice.map.amap.h
                ClassLoader cl = sysContext.getClassLoader();
                Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.map.amap.h", cl);
                
                // 获取单例：static volatile e
                amapSurfaceMgr = XposedHelpers.getStaticObjectField(mgrClass, "e");
                
                if (amapSurfaceMgr != null) {
                    XposedBridge.log("NaviHook: [Sys] ✅ 获取 AmapSurfaceAidlManager 成功");
                    
                    // 🔥 核心：调用 bindWidgetService()
                    // 这会触发系统原生逻辑：Context.bindService -> ServiceConnection -> onServiceConnected
                    // 从而连接到我们 Map 端的 TrojanBinder
                    try {
                        XposedHelpers.callMethod(amapSurfaceMgr, "bindWidgetService");
                        XposedBridge.log("NaviHook: [Sys] 🚀 已调用 bindWidgetService()，系统正尝试连接...");
                        
                        // 同时触发一次 Dashboard 唤醒，确保状态同步
                        triggerWakeUp();
                        
                    } catch (Throwable t) {
                        XposedBridge.log("NaviHook: [Sys] ❌ 调用 bindWidgetService 失败: " + t);
                    }
                } else {
                    XposedBridge.log("NaviHook: [Sys] ⚠️ Manager 单例为空，稍后重试...");
                    sysHandler.postDelayed(this::initAndTriggerAmapSurfaceMgr, 3000);
                }
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Sys] ❌ 获取 Manager 异常: " + t);
            }
        }, 2000);
    }

    // =============================================================
    // 🦄 V224 Map 端核心：正确的 Binder 协议
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
                // 根据 IAutoSimilarWidgetViewService$Stub.smali：
                // Code 1 = addSurface (注入!)
                // Code 2 = removedSurface (忽略!)
                // Code 3 = isMapRunning
                // Code 4 = setWidgetStateControl
                
                int dataSize = data.dataSize();
                
                if (code == 1) { // addSurface
                    XposedBridge.log("NaviHook: [Binder] 🔥 收到 Code 1 (addSurface) | Size=" + dataSize);
                    
                    if (isSurfaceActive) {
                        if (reply != null) reply.writeNoException();
                        return true;
                    }

                    // 暴力解析 Surface (Offset 0-128)
                    Surface surface = tryExtendedBruteForce(data);
                    
                    if (surface != null && surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ✅✅✅ Surface 解析成功！注入引擎...");
                        final Surface s = surface;
                        uiHandler.post(() -> injectNativeEngine(s));
                        isSurfaceActive = true;
                    } else {
                        XposedBridge.log("NaviHook: [Binder] ❌ Surface 解析失败");
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (code == 2) { // removedSurface
                    XposedBridge.log("NaviHook: [Binder] 收到 Code 2 (removedSurface) -> 重置");
                    isSurfaceActive = false;
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                if (code == 3) { // isMapRunning
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(1); // 返回 true
                    }
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
                
                // 1. nativeSurfaceCreated
                Method mCreate = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                mCreate.invoke(null, 1, 2, surface);
                XposedBridge.log("NaviHook: [Map] ✅ Native Created 调用成功");

                // 2. nativeSurfaceRedrawNeeded
                try {
                    Method mRedraw = XposedHelpers.findMethodExact(cls, "nativeSurfaceRedrawNeeded", int.class, int.class, Surface.class);
                    mRedraw.invoke(null, 1, 2, surface);
                    XposedBridge.log("NaviHook: [Map] ✅ Redraw (3参数) 调用成功");
                } catch (Throwable t) { 
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals("nativeSurfaceRedrawNeeded")) {
                            m.setAccessible(true);
                            if (m.getParameterCount() == 0) m.invoke(null);
                            else if (m.getParameterCount() == 2) m.invoke(null, 1, 2);
                            XposedBridge.log("NaviHook: [Map] ✅ Redraw (兜底) 调用成功");
                            break;
                        }
                    }
                }

            } catch (Throwable t) { 
                XposedBridge.log("NaviHook: [Map] ❌ 注入异常: " + t);
                isSurfaceActive = false; 
            }
        }
    }

    // =============================================================
    // 辅助代码 (分辨率Hook, 广播, PM欺骗) - 保持不变
    // =============================================================
    
    private void hookSurfaceDimensions(ClassLoader cl) {
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

    private void hookPackageManager(ClassLoader cl) {
        // 保持之前的 PM 欺骗代码
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

    private void sendVersionBroadcast(Context ctx, boolean is75) {
        try {
            Intent intent = new Intent(ACTION_VERSION_CHECK);
            intent.setPackage(PKG_SERVICE);
            intent.putExtra("is_75", is75);
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {}
    }

    private void registerVersionReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_VERSION_CHECK);
        sysContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean is75 = intent.getBooleanExtra("is_75", false);
                XposedBridge.log("NaviHook: [Sys] 📩 收到广播: " + (is75 ? "7.5" : "9.1"));
                if (is75) initAs75(); else initAs91();
            }
        }, filter);
    }

    private void initAs75() {
        initDashboardMgr();
        startStatusHeartbeat(true);
    }
    
    private boolean initDashboardMgr() {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", sysContext.getClassLoader());
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            return dashboardMgr != null;
        } catch (Throwable t) { return false; }
    }

    private void startStatusHeartbeat(boolean isLoop) {
        if (statusHeartbeat != null) statusHeartbeat.cancel();
        statusHeartbeat = new Timer();
        statusHeartbeat.schedule(new TimerTask() {
            @Override
            public void run() {
                triggerWakeUp();
            }
        }, 1000, isLoop ? 3000 : 9999999);
    }
}