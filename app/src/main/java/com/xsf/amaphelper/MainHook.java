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
            // 1. 分辨率 Hook (保持 V212 的成功逻辑)
            hookSurfaceDimensions(lpparam.classLoader);

            // 2. 版本检测广播
            boolean isLegacy75 = XposedHelpers.findClassIfExists("com.AutoHelper", lpparam.classLoader) != null;
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    // 持续广播，确保系统侧能收到
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sendVersionBroadcast(ctx, isLegacy75), 3000);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sendVersionBroadcast(ctx, isLegacy75), 10000);
                    sendVersionBroadcast(ctx, isLegacy75);
                }
            });

            // 3. 9.1 植入 TrojanBinder
            if (!isLegacy75) {
                XposedBridge.log("NaviHook: [Map] ⚡ 识别为 9.1，植入 V213 (显影连接版) Binder。");
                try {
                    XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            XposedBridge.log("NaviHook: [Map] 🚨 收到 Bind 请求，返回 TrojanBinder");
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
                    
                    // 兜底：10秒未收到广播则强制启动
                    sysHandler.postDelayed(() -> {
                        if (!isSystemReady) {
                            XposedBridge.log("NaviHook: [Sys] ⚠️ 等待超时，强制启动 9.1 流程");
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

    // Hook 分辨率 (已验证成功)
    private void hookSurfaceDimensions(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", cl);
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals("getMapSurfaceWidth")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam param) { return 1920; }
                    });
                    XposedBridge.log("NaviHook: [Map] ✅ 成功 Hook 宽度");
                }
                if (m.getName().equals("getMapSurfaceHeight")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam param) { return 720; }
                    });
                    XposedBridge.log("NaviHook: [Map] ✅ 成功 Hook 高度");
                }
                if (m.getName().equals("getMapSurfaceDpi")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam param) { return 240; }
                    });
                    XposedBridge.log("NaviHook: [Map] ✅ 成功 Hook DPI");
                }
            }
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
                if (isSystemReady) return;
                boolean is75 = intent.getBooleanExtra("is_75", false);
                XposedBridge.log("NaviHook: [Sys] 📩 收到广播: " + (is75 ? "7.5" : "9.1"));
                if (is75) initAs75(); else initAs91();
                isSystemReady = true;
            }
        }, filter);
    }

    private void initAs75() {
        initDashboardMgr();
        startStatusHeartbeat(true);
    }

    private void initAs91() {
        // 9.1 必须先初始化 DashboardMgr 才能注入
        if (!initDashboardMgr()) {
            XposedBridge.log("NaviHook: [Sys] DashboardMgr 未就绪，2秒后重试...");
            sysHandler.postDelayed(this::initAs91, 2000);
            return;
        }
        XposedBridge.log("NaviHook: [Sys] DashboardMgr 就绪，执行 Bind...");
        bindToMapService();
        startStatusHeartbeat(false);
    }
    
    private boolean initDashboardMgr() {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", sysContext.getClassLoader());
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            return dashboardMgr != null;
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] Init Mgr 失败: " + t);
            return false;
        }
    }

    // =============================================================
    // 📡 连接与注入逻辑 (V213 增加详细日志)
    // =============================================================
    private void bindToMapService() {
        if (sysContext == null) return;
        sysHandler.post(() -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                XposedBridge.log("NaviHook: [Sys] 正在调用 bindService...");
                
                boolean bound = sysContext.bindService(intent, new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        XposedBridge.log("NaviHook: [Sys] ✅✅✅ onServiceConnected 触发！准备注入...");
                        injectToDashboard(service);
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        XposedBridge.log("NaviHook: [Sys] ❌ 连接断开");
                    }
                }, Context.BIND_AUTO_CREATE);
                
                if (!bound) XposedBridge.log("NaviHook: [Sys] ❌ bindService 返回 false (可能是权限或包名错误)");
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Sys] Bind 异常: " + t);
            }
        });
    }

    private void injectToDashboard(IBinder binder) {
        try {
            if (dashboardMgr == null) {
                XposedBridge.log("NaviHook: [Sys] ❌ 注入失败：dashboardMgr 为空");
                return;
            }
            
            Object internalConn = XposedHelpers.getObjectField(dashboardMgr, "f");
            if (internalConn != null) {
                Method onConnected = internalConn.getClass().getMethod("onServiceConnected", ComponentName.class, IBinder.class);
                onConnected.invoke(internalConn, new ComponentName(PKG_MAP, TARGET_SERVICE), binder);
                XposedBridge.log("NaviHook: [Sys] ✅✅✅ 注入 Dashboard 成功！等待 Code 4...");
                
                // 🔥 注入后立即发送唤醒指令
                triggerWakeUp();
            } else {
                XposedBridge.log("NaviHook: [Sys] ❌ 注入失败：内部 Connection 对象为空");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] ❌ 注入过程崩溃: " + t);
        }
    }

    private void triggerWakeUp() {
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", 3);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);
            
            Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
            XposedHelpers.setIntField(st, "status", 16);
            XposedHelpers.callMethod(dashboardMgr, "a", st);
            XposedBridge.log("NaviHook: [Sys] ⚡ 唤醒指令已发送 (Switch 3 / Status 16)");
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] 唤醒失败: " + t);
        }
    }

    // =============================================================
    // 🦄 V213 TrojanBinder (保持 Surface 逻辑)
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
                int dataSize = data.dataSize();
                
                // 打印每一条指令，方便调试
                XposedBridge.log("NaviHook: [Binder] 收到指令 Code=" + code + " Size=" + dataSize);

                if (code == 4) { 
                    XposedBridge.log("NaviHook: [Binder] 🤝 收到握手包 (Code 4)");
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (dataSize > 200 && (code == 2 || code == 1 || code == 43)) {
                    if (isSurfaceActive && code == 1) { 
                         if (reply != null) reply.writeNoException();
                         return true;
                    }

                    XposedBridge.log("NaviHook: [Binder] 🔍 解析 Surface 包...");
                    Surface surface = tryExtendedBruteForce(data);
                    
                    if (surface != null && surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ✅ 挖到 Surface!");
                        final Surface s = surface;
                        uiHandler.post(() -> injectNativeEngine(s));
                        isSurfaceActive = true;
                    } else {
                        XposedBridge.log("NaviHook: [Binder] ❌ Surface 解析失败");
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                if (code == 2 && dataSize < 100) { 
                    isSurfaceActive = false;
                    XposedBridge.log("NaviHook: [Binder] 收到 Reset (Code 2)");
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                if (code == 20 || code == 1) {
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

                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals("nativeSurfaceRedrawNeeded")) {
                        m.setAccessible(true);
                        if (m.getParameterCount() == 2) m.invoke(null, 1, 2);
                        else if (m.getParameterCount() == 0) m.invoke(null);
                        XposedBridge.log("NaviHook: [Map] ✅ Redraw 触发");
                        break;
                    }
                }
            } catch (Throwable t) { 
                XposedBridge.log("NaviHook: [Map] ❌ 注入异常: " + t);
                isSurfaceActive = false; 
            }
        }
    }

    private void hookPackageManager(ClassLoader cl) {
        XC_MethodHook spoofHook = new XC_MethodHook() {
            @SuppressWarnings("unchecked")
            @Override
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null && TARGET_SERVICE.equals(intent.getComponent().getClassName())) {
                    Object resultObj = param.getResult();
                    boolean isEmpty = false;
                    if (resultObj == null) isEmpty = true;
                    else if (resultObj instanceof java.util.List) isEmpty = ((java.util.List) resultObj).isEmpty();
                    
                    if (isEmpty) {
                        android.content.pm.ResolveInfo info = new android.content.pm.ResolveInfo();
                        info.serviceInfo = new android.content.pm.ServiceInfo();
                        info.serviceInfo.packageName = PKG_MAP;
                        info.serviceInfo.name = TARGET_SERVICE;
                        info.serviceInfo.exported = true;
                        info.serviceInfo.applicationInfo = new android.content.pm.ApplicationInfo();
                        info.serviceInfo.applicationInfo.packageName = PKG_MAP;
                        
                        if (resultObj instanceof java.util.List) {
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

    private void startStatusHeartbeat(boolean isLoop) {
        if (statusHeartbeat != null) statusHeartbeat.cancel();
        statusHeartbeat = new Timer();
        statusHeartbeat.schedule(new TimerTask() {
            @Override
            public void run() {
                if (sysContext == null || dashboardMgr == null) {
                    this.cancel();
                    return;
                }
                try {
                    ClassLoader cl = sysContext.getClassLoader();
                    Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
                    XposedHelpers.setIntField(sw, "mSwitchState", 3);
                    XposedHelpers.callMethod(dashboardMgr, "a", sw);

                    Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
                    XposedHelpers.setIntField(st, "status", 16);
                    XposedHelpers.callMethod(dashboardMgr, "a", st);
                } catch (Throwable t) {}
            }
        }, 1000, isLoop ? 3000 : 9999999);
    }
}