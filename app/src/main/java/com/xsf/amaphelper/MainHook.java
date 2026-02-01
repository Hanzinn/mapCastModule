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
    private static final String BINDER_DESCRIPTOR = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";

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
            // 所有逻辑移入 onCreate，确保先检查版本，避免误伤 7.5
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    ClassLoader cl = ctx.getClassLoader();

                    // 1. 版本检测：是否有 7.5 特征类
                    boolean isLegacy75 = false;
                    try {
                        cl.loadClass("com.AutoHelper");
                        isLegacy75 = true;
                    } catch (ClassNotFoundException e) {
                        isLegacy75 = false;
                    }

                    // 广播通知系统
                    final boolean finalIsLegacy = isLegacy75;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sendVersionBroadcast(ctx, finalIsLegacy), 3000);
                    sendVersionBroadcast(ctx, finalIsLegacy);

                    if (isLegacy75) {
                        XposedBridge.log("NaviHook: [Map] ⚠️ 识别为 7.5，安全退出。");
                        return; // ⛔ 7.5 退出，防闪屏
                    }

                    // --- 9.1 逻辑 ---
                    XposedBridge.log("NaviHook: [Map] ⚡ 识别为 9.1，启动 V231 逻辑。");

                    // 2. DPI Hook
                    hookSurfaceDimensions(cl);

                    // 3. 植入 Binder
                    try {
                        XposedHelpers.findAndHookMethod(TARGET_SERVICE, cl, "onBind", Intent.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                                param.setResult(new TrojanBinder(cl));
                            }
                        });
                    } catch (Throwable t) {
                        XposedBridge.log("NaviHook: [Map] ❌ Hook onBind 失败: " + t);
                    }
                }
            });
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
    // 📡 系统侧：手动绑定 (保持 V229)
    // =============================================================
    private static void initAs91() {
        if (sysContext == null || isSystemReady) return;
        isSystemReady = true;
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            performManualBind();
            startStatusHeartbeat(false);
        } catch (Throwable t) {}
    }

    private static void performManualBind() {
        if (sysContext == null) return;
        sysHandler.postDelayed(() -> {
            try {
                ClassLoader cl = sysContext.getClassLoader();
                Class<?> hClass = XposedHelpers.findClass("ecarx.naviservice.map.amap.h", cl);
                Object managerInstance = XposedHelpers.getStaticObjectField(hClass, "e");
                
                if (managerInstance == null) {
                    XposedBridge.log("NaviHook: [Sys] Manager 未就绪，重试...");
                    sysHandler.postDelayed(() -> performManualBind(), 3000);
                    return;
                }

                Object connectionObj = null;
                try {
                    connectionObj = XposedHelpers.getObjectField(managerInstance, "f");
                } catch (Throwable t) {
                    for (Field f : hClass.getDeclaredFields()) {
                        f.setAccessible(true);
                        if (ServiceConnection.class.isAssignableFrom(f.getType())) {
                            connectionObj = f.get(managerInstance);
                            break;
                        }
                    }
                }

                if (connectionObj == null) {
                    XposedBridge.log("NaviHook: [Sys] ❌ 无法获取连接器对象");
                    return;
                }

                XposedBridge.log("NaviHook: [Sys] 🚀 手动调用 bindService...");
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                sysContext.bindService(intent, (ServiceConnection) connectionObj, Context.BIND_AUTO_CREATE);
                triggerWakeUp();

            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Sys] Bind 异常: " + t);
            }
        }, 2000);
    }

    // =============================================================
    // 🦄 V231 TrojanBinder (Inject Fix)
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
                if (code == 1598968902) {
                    if (reply != null) reply.writeString(BINDER_DESCRIPTOR);
                    return true;
                }

                if (code == 1) { // addSurface
                    XposedBridge.log("NaviHook: [Binder] 🔥 收到 Code 1 (addSurface)");
                    if (isSurfaceActive) {
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    Surface surface = tryExtendedBruteForce(data);
                    if (surface != null && surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ✅✅✅ 成功挖到 Surface！");
                        final Surface s = surface;
                        uiHandler.post(() -> injectNativeEngine(s));
                        isSurfaceActive = true;
                    } else {
                        XposedBridge.log("NaviHook: [Binder] ❌ Surface 解析失败");
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (code == 4) { // Handshake
                    try { data.readStrongBinder(); } catch (Throwable t) {}
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (code == 5) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (code == 2) { 
                    isSurfaceActive = false;
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
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

        // 🔥🔥🔥 V231 核心修复：调用正确的方法
        private void injectNativeEngine(Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                
                // 1. 调用 nativeSurfaceCreated (displayId=1 代表仪表)
                Method mCreate = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                mCreate.invoke(null, 1, 2, surface);
                XposedBridge.log("NaviHook: [Map] ✅ Native Created 调用成功");

                // 2. 🔥 修正：调用 nativesurfaceChanged (注意大小写，注意参数)
                // 签名: (int displayId, Surface surface, int format, int width, int height)
                try {
                    // 注意：方法名是 nativesurfaceChanged (surface小写)，不是 nativeSurfaceChanged
                    // 参数：displayId=1, format=0, w=1920, h=720
                    Method mChange = XposedHelpers.findMethodExact(cls, "nativesurfaceChanged", int.class, Surface.class, int.class, int.class, int.class);
                    mChange.invoke(null, 1, surface, 0, 1920, 720);
                    XposedBridge.log("NaviHook: [Map] ✅✅✅ nativesurfaceChanged 调用成功！(1920x720)");
                } catch (Throwable t) {
                    XposedBridge.log("NaviHook: [Map] ❌ nativesurfaceChanged 调用失败: " + t);
                    // 打印所有方法帮助排查 (如果还是失败)
                    for(Method m : cls.getDeclaredMethods()) {
                        if (m.getName().toLowerCase().contains("change")) {
                            XposedBridge.log("NaviHook: Found: " + m.getName());
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
    // 辅助工具
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
                    // ... (保持之前的 PM 欺骗逻辑，略去以节省篇幅，功能不变) ...
                    // 务必保留这部分逻辑，确保 PM 欺骗生效
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