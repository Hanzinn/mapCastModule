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
import java.lang.reflect.Field;
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

    // 静态变量
    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
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
            // 1. 版本检测 (V227 的正确逻辑)
            boolean isLegacy75 = XposedHelpers.findClassIfExists("com.AutoHelper", lpparam.classLoader) != null;
            
            // 注册广播通知系统
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sendVersionBroadcast(ctx, isLegacy75), 3000);
                    sendVersionBroadcast(ctx, isLegacy75);
                }
            });

            // ⛔ 如果是 7.5，直接停止！(防止闪屏)
            if (isLegacy75) {
                XposedBridge.log("NaviHook: [Map] ⚠️ 识别为 7.5，停止介入。");
                return; 
            }

            // --- 以下逻辑仅对 9.1 生效 ---

            // 2. Hook 分辨率 (9.1 必须项)
            XposedBridge.log("NaviHook: [Map] ⚡ 识别为 9.1，执行 DPI Hook 和 Binder 植入 (V229)。");
            hookSurfaceDimensions(lpparam.classLoader);

            // 3. 植入 Binder
            try {
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                        param.setResult(new TrojanBinder(lpparam.classLoader));
                    }
                });
            } catch (Throwable t) {}
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
                    
                    // 兜底策略
                    sysHandler.postDelayed(() -> {
                        if (!isSystemReady) {
                            XposedBridge.log("NaviHook: [Sys] ⚠️ 等待超时，执行手动连接");
                            initAs91();
                        }
                    }, 12000);
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
    // 📡 系统侧：手动绑定 (Manual Bind)
    // =============================================================
    
    private static void initAs91() {
        if (sysContext == null || isSystemReady) return;
        isSystemReady = true;
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            
            // 执行手动绑定
            performManualBind();
            
            // 启动心跳
            startStatusHeartbeat(false);
        } catch (Throwable t) {}
    }

    private static void performManualBind() {
        if (sysContext == null) return;
        sysHandler.postDelayed(() -> {
            try {
                ClassLoader cl = sysContext.getClassLoader();
                // 1. 获取 Manager 类 (h.e)
                Class<?> hClass = XposedHelpers.findClass("ecarx.naviservice.map.amap.h", cl);
                Object managerInstance = XposedHelpers.getStaticObjectField(hClass, "e");
                
                if (managerInstance == null) {
                    XposedBridge.log("NaviHook: [Sys] Manager 未就绪，重试...");
                    sysHandler.postDelayed(() -> performManualBind(), 3000);
                    return;
                }

                // 2. 获取 ServiceConnection (f 字段)
                Object connectionObj = null;
                try {
                    connectionObj = XposedHelpers.getObjectField(managerInstance, "f");
                } catch (Throwable t) {
                    // 如果 f 字段名变了，扫描字段类型
                    for (Field f : hClass.getDeclaredFields()) {
                        f.setAccessible(true);
                        if (ServiceConnection.class.isAssignableFrom(f.getType())) {
                            connectionObj = f.get(managerInstance);
                            XposedBridge.log("NaviHook: [Sys] ✅ 扫描找到 ServiceConnection: " + f.getName());
                            break;
                        }
                    }
                }

                if (connectionObj == null) {
                    XposedBridge.log("NaviHook: [Sys] ❌ 无法在 Manager 中找到连接器对象");
                    return;
                }

                // 3. 🚀 代替系统发起 bindService
                XposedBridge.log("NaviHook: [Sys] 🚀 拿到连接器，手动发起 Bind...");
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                
                boolean result = sysContext.bindService(intent, (ServiceConnection) connectionObj, Context.BIND_AUTO_CREATE);
                XposedBridge.log("NaviHook: [Sys] bindService 结果: " + result);
                
                triggerWakeUp();

            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Sys] Bind 异常: " + t);
            }
        }, 2000);
    }

    // =============================================================
    // 🦄 Map 端：全协议 Binder (V229 核心)
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
                // Code 1: addSurface (Surface, int) -> 🔥 核心注入
                if (code == 1) {
                    XposedBridge.log("NaviHook: [Binder] 🔥 收到 Code 1 (addSurface)");
                    if (isSurfaceActive) {
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    Surface surface = tryExtendedBruteForce(data);
                    if (surface != null && surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ✅✅✅ 挖到 Surface! 注入引擎!");
                        final Surface s = surface;
                        uiHandler.post(() -> injectNativeEngine(s));
                        isSurfaceActive = true;
                    } else {
                        XposedBridge.log("NaviHook: [Binder] ❌ Code 1 解析失败");
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // Code 4: setWidgetStateControl (Handshake) -> 🔥 V229 补回
                // 系统通常先发 Code 4 确认连接，再发 Code 1
                if (code == 4) {
                    XposedBridge.log("NaviHook: [Binder] 收到 Code 4 (Handshake) -> 回复正常");
                    try {
                        // 只需要读出来，不需要做具体处理，让指针走过去即可
                        data.readStrongBinder();
                    } catch (Throwable t) {}
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // Code 5: dispatchTouchEvent -> 🔥 V229 补回
                if (code == 5) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // Code 2: removedSurface -> 重置
                if (code == 2) { 
                    XposedBridge.log("NaviHook: [Binder] 收到 Code 2 (Reset)");
                    isSurfaceActive = false;
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // Code 3: isMapRunning -> true
                if (code == 3) {
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(1); 
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
                Method mCreate = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                mCreate.invoke(null, 1, 2, surface);
                XposedBridge.log("NaviHook: [Map] ✅ Native Created 调用成功");

                try {
                    Method mRedraw = XposedHelpers.findMethodExact(cls, "nativeSurfaceRedrawNeeded", int.class, int.class, Surface.class);
                    mRedraw.invoke(null, 1, 2, surface);
                    XposedBridge.log("NaviHook: [Map] ✅ Redraw 调用成功");
                } catch (Throwable t) { 
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals("nativeSurfaceRedrawNeeded")) {
                            m.setAccessible(true);
                            if (m.getParameterCount() == 0) m.invoke(null);
                            else if (m.getParameterCount() == 2) m.invoke(null, 1, 2);
                        }
                    }
                }
            } catch (Throwable t) { 
                XposedBridge.log("NaviHook: [Map] 注入异常: " + t);
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
                    XposedBridge.log("NaviHook: [Sys] 7.5 模式 -> 保持静默");
                } else {
                    initAs91();
                }
                isSystemReady = true;
            }
        }, filter);
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
}