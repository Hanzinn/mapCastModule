package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
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
    private static final String BINDER_DESCRIPTOR = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";

    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static Timer statusHeartbeat;
    private static boolean isSystemReady = false;
    
    // 标记是否为 7.5 (系统进程用)
    private static boolean isMap75_InSys = false;

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
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    ClassLoader cl = ctx.getClassLoader();

                    // 1. 版本检测
                    boolean isLegacy75 = false;
                    try {
                        cl.loadClass("com.AutoHelper");
                        isLegacy75 = true;
                    } catch (ClassNotFoundException e) {
                        isLegacy75 = false;
                    }

                    // 广播通知系统 (极其重要！)
                    final boolean finalIsLegacy = isLegacy75;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sendVersionBroadcast(ctx, finalIsLegacy), 3000);
                    sendVersionBroadcast(ctx, finalIsLegacy);

                    // 🛑 7.5 止步：彻底不执行任何 Hook，防止闪屏
                    if (isLegacy75) {
                        XposedBridge.log("NaviHook: [Map] ⚠️ 识别为 7.5，模块休眠。");
                        return;
                    }

                    // --- 9.1 逻辑 ---
                    XposedBridge.log("NaviHook: [Map] ⚡ 识别为 9.1，启动 V232。");
                    hookSurfaceDimensions(cl);

                    try {
                        XposedHelpers.findAndHookMethod(TARGET_SERVICE, cl, "onBind", Intent.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                                param.setResult(new TrojanBinder(cl));
                            }
                        });
                    } catch (Throwable t) {}
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
                    
                    // 预先检查已安装的高德版本 (用于 PM 欺骗判断)
                    checkMapVersionInSystem(sysContext);
                    
                    registerVersionReceiver();
                    
                    // 兜底
                    sysHandler.postDelayed(() -> {
                        if (!isSystemReady && !isMap75_InSys) {
                            XposedBridge.log("NaviHook: [Sys] ⚠️ 超时强制连接");
                            initAs91();
                        }
                    }, 12000);
                }
            });

            // 🔥 1. 智能 PM 欺骗 (7.5 不骗，9.1 才骗) -> 解决闪屏
            hookPackageManager(lpparam.classLoader);
            
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}
        }
    }

    // =============================================================
    // 📡 系统侧核心逻辑
    // =============================================================
    
    // 在系统侧预检查高德版本
    private static void checkMapVersionInSystem(Context ctx) {
        try {
            PackageInfo pInfo = ctx.getPackageManager().getPackageInfo(PKG_MAP, 0);
            // 简单判断：7.x 版本认为是 7.5
            if (pInfo.versionName != null && pInfo.versionName.startsWith("7")) {
                isMap75_InSys = true;
                XposedBridge.log("NaviHook: [Sys] 预检发现高德 7.5，禁用 PM 欺骗。");
            } else {
                isMap75_InSys = false;
                XposedBridge.log("NaviHook: [Sys] 预检发现高德 9.x+，启用 PM 欺骗。");
            }
        } catch (Throwable t) {}
    }

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
                    sysHandler.postDelayed(() -> performManualBind(), 3000);
                    return;
                }

                Object connectionObj = null;
                try {
                    connectionObj = XposedHelpers.getObjectField(managerInstance, "f");
                } catch (Throwable t) {}

                if (connectionObj == null) return;

                XposedBridge.log("NaviHook: [Sys] 🚀 发起 bindService...");
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                sysContext.bindService(intent, (ServiceConnection) connectionObj, Context.BIND_AUTO_CREATE);
                
                // 🔥 V232 核心：发送完整激活序列 (模仿 V182)
                triggerActivationSequence();

            } catch (Throwable t) {}
        }, 2000);
    }

    // 🔥 完整的激活序列 (Status 1 -> 3 -> 16)
    // 这是诱导系统发送 Code 1 的关键！
    private static void triggerActivationSequence() {
        if (dashboardMgr == null) return;
        sysHandler.post(() -> {
            try {
                XposedBridge.log("NaviHook: [Sys] ⚡ 执行激活序列 (1 -> 3 -> 16)...");
                // 1. 初始化
                sendMapStatus(1);
                Thread.sleep(50);
                
                // 2. 前台
                sendMapStatus(3);
                Thread.sleep(50);
                
                // 3. 切换布局 + 导航中
                sendMapSwitch(3); // CRUISE_TO_GUIDE
                sendMapStatus(16); // NAVI_STATUS
                
                XposedBridge.log("NaviHook: [Sys] ✅ 激活序列发送完毕");
            } catch (Throwable t) {}
        });
    }

    // =============================================================
    // 🦄 TrojanBinder (V232)
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

                // Code 1: addSurface
                if (code == 1) {
                    XposedBridge.log("NaviHook: [Binder] 🔥🔥🔥 收到 Code 1 (addSurface)！");
                    if (isSurfaceActive) {
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    Surface surface = tryExtendedBruteForce(data);
                    if (surface != null && surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ✅ Surface 有效，注入！");
                        final Surface s = surface;
                        uiHandler.post(() -> injectNativeEngine(s));
                        isSurfaceActive = true;
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (code == 4) { // Handshake
                    try { data.readStrongBinder(); } catch (Throwable t) {}
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
                
                if (code == 2) {
                    isSurfaceActive = false;
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
                
                // 1. Created
                Method mCreate = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                mCreate.invoke(null, 1, 2, surface);
                XposedBridge.log("NaviHook: [Map] ✅ Created");

                // 2. Changed (Correct Method Name)
                // 这里的关键：方法名是 nativesurfaceChanged (小写s)，且参数是 5 个
                try {
                    Method mChange = XposedHelpers.findMethodExact(cls, "nativesurfaceChanged", int.class, Surface.class, int.class, int.class, int.class);
                    // 参数: displayId=1, surface, format=0, w=1920, h=720
                    mChange.invoke(null, 1, surface, 0, 1920, 720);
                    XposedBridge.log("NaviHook: [Map] ✅ Changed (1920x720)");
                } catch (NoSuchMethodException e) {
                    XposedBridge.log("NaviHook: [Map] ⚠️ nativesurfaceChanged 未找到，尝试 Redraw...");
                    // 兜底: nativeSurfaceRedrawNeeded (有些版本可能叫这个)
                    try {
                        Method mRedraw = XposedHelpers.findMethodExact(cls, "nativeSurfaceRedrawNeeded", int.class, int.class, Surface.class);
                        mRedraw.invoke(null, 1, 2, surface);
                    } catch (Throwable t2) {}
                }

            } catch (Throwable t) { 
                XposedBridge.log("NaviHook: [Map] 注入异常: " + t);
                isSurfaceActive = false; 
            }
        }
    }

    // =============================================================
    // 工具方法
    // =============================================================
    
    // PM 欺骗：增加版本判断，如果是 7.5 绝对不骗
    private static void hookPackageManager(ClassLoader cl) {
        // 如果系统检测到是 7.5，直接不进行 Hook
        if (isMap75_InSys) return;

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
                if (is75) {
                    isMap75_InSys = true; // 标记为 7.5
                } else {
                    isMap75_InSys = false;
                    initAs91();
                }
            }
        }, filter);
    }

    private static void sendMapStatus(int status) {
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
            XposedHelpers.setIntField(st, "status", status);
            XposedHelpers.callMethod(dashboardMgr, "a", st);
        } catch (Throwable t) {}
    }

    private static void sendMapSwitch(int state) {
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", state);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);
        } catch (Throwable t) {}
    }

    private static void startStatusHeartbeat(boolean isLoop) {
        if (statusHeartbeat != null) statusHeartbeat.cancel();
        statusHeartbeat = new Timer();
        statusHeartbeat.schedule(new TimerTask() {
            @Override
            public void run() {
                sendMapStatus(16);
            }
        }, 1000, isLoop ? 3000 : 9999999);
    }
}