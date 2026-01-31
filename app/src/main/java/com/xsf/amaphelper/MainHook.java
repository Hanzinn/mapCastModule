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
            // 1. 分辨率 Hook
            hookSurfaceDimensions(lpparam.classLoader);

            // 2. 版本检测广播
            boolean isLegacy75 = XposedHelpers.findClassIfExists("com.AutoHelper", lpparam.classLoader) != null;
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sendVersionBroadcast(ctx, isLegacy75), 3000);
                    sendVersionBroadcast(ctx, isLegacy75);
                }
            });

            // 3. 9.1 植入 TrojanBinder
            if (!isLegacy75) {
                XposedBridge.log("NaviHook: [Map] ⚡ 识别为 9.1，植入 V218 Binder。");
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
                    
                    sysHandler.postDelayed(() -> {
                        if (!isSystemReady) {
                            XposedBridge.log("NaviHook: [Sys] ⚠️ 等待超时，强制 9.1 模式");
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
    // 📡 注入逻辑 (V218 核心：全字段扫描 + 暴力反射)
    // =============================================================
    private void bindToMapService() {
        if (sysContext == null) return;
        sysHandler.post(() -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                sysContext.bindService(intent, new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        XposedBridge.log("NaviHook: [Sys] ✅ 物理连接建立，启动全域扫描注入...");
                        injectToDashboard(service);
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {}
                }, Context.BIND_AUTO_CREATE);
            } catch (Throwable t) {}
        });
    }

    private void injectToDashboard(IBinder binder) {
        try {
            if (dashboardMgr == null) {
                XposedBridge.log("NaviHook: [Sys] ❌ dashboardMgr 为空");
                return;
            }

            Class<?> mgrClass = dashboardMgr.getClass();
            XposedBridge.log("NaviHook: [Sys] 扫描 " + mgrClass.getName() + " 的字段...");

            boolean injected = false;

            // 🔥 核心：遍历 dashboardMgr 的所有字段 (a, b, c, d, e, f, g, h...)
            for (Field field : mgrClass.getDeclaredFields()) {
                field.setAccessible(true); // 强制访问私有字段
                Object fieldObj = null;
                try {
                    fieldObj = field.get(dashboardMgr);
                } catch (Exception e) { continue; }

                if (fieldObj == null) continue;

                // 对每个字段对象，遍历它的所有方法 (getDeclaredMethods 获取所有权限的方法)
                Class<?> fieldClass = fieldObj.getClass();
                for (Method m : fieldClass.getDeclaredMethods()) {
                    
                    // 检查参数特征：(ComponentName, IBinder)
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 2 && 
                        params[0] == ComponentName.class && 
                        params[1] == IBinder.class) {
                        
                        // 找到了！不管它叫 onServiceConnected 还是 a 还是 b
                        try {
                            m.setAccessible(true); // 强制访问私有/匿名方法
                            m.invoke(fieldObj, new ComponentName(PKG_MAP, TARGET_SERVICE), binder);
                            
                            XposedBridge.log("NaviHook: [Sys] ✅✅✅ 注入成功！");
                            XposedBridge.log("NaviHook: [Sys] 目标字段: " + field.getName() + " (" + fieldClass.getName() + ")");
                            XposedBridge.log("NaviHook: [Sys] 目标方法: " + m.getName());
                            
                            injected = true;
                            triggerWakeUp();
                            return; // 成功后立即退出
                        } catch (Exception e) {
                            XposedBridge.log("NaviHook: [Sys] ⚠️ 找到匹配方法但调用失败: " + e);
                        }
                    }
                }
            }

            if (!injected) {
                XposedBridge.log("NaviHook: [Sys] ❌ 扫描结束，未找到任何接受 (ComponentName, IBinder) 的方法");
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
            XposedBridge.log("NaviHook: [Sys] ⚡ 唤醒指令已发送");
        } catch (Throwable t) {}
    }

    // =============================================================
    // 🦄 V218 TrojanBinder (保持 V214 的成功逻辑)
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
                if (code == 4) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (dataSize > 200 && (code == 2 || code == 1 || code == 43)) {
                    if (isSurfaceActive && code == 1) { 
                         if (reply != null) reply.writeNoException();
                         return true;
                    }

                    XposedBridge.log("NaviHook: [Binder] 收到大包 Code " + code + "，解析 Surface...");
                    Surface surface = tryExtendedBruteForce(data);
                    
                    if (surface != null && surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ✅ 挖到 Surface!");
                        final Surface s = surface;
                        uiHandler.post(() -> injectNativeEngine(s));
                        isSurfaceActive = true;
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                if (code == 2 && dataSize < 100) { 
                    isSurfaceActive = false;
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                if (code == 20 || code == 1) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }
            } catch (Throwable t) {}
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
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals("nativeSurfaceRedrawNeeded")) {
                            m.setAccessible(true);
                            if (m.getParameterCount() == 0) m.invoke(null);
                            else if (m.getParameterCount() == 2) m.invoke(null, 1, 2);
                        }
                    }
                }

            } catch (Throwable t) { 
                XposedBridge.log("NaviHook: [Map] ❌ 注入异常: " + t);
                isSurfaceActive = false; 
            }
        }
    }

    private void hookSurfaceDimensions(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", cl);
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals("getMapSurfaceWidth")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(XC_MethodHook.MethodHookParam param) { return 1920; }
                    });
                }
                if (m.getName().equals("getMapSurfaceHeight")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(XC_MethodHook.MethodHookParam param) { return 720; }
                    });
                }
                if (m.getName().equals("getMapSurfaceDpi")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(XC_MethodHook.MethodHookParam param) { return 240; }
                    });
                }
            }
        } catch (Throwable t) {}
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
        if (!initDashboardMgr()) {
            sysHandler.postDelayed(this::initAs91, 2000);
            return;
        }
        bindToMapService();
        startStatusHeartbeat(false);
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