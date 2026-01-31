package com.xsf.amaphelper;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.view.Surface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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

    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static Timer statusHeartbeat;
    private static int lastSentStatus = -1;
    private static volatile boolean isEnvChecked = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // =============================================================
        // 🏰 战场 A：高德地图进程（版本检测修复）
        // =============================================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            // 🔥 方案：Hook Application.onCreate 获取版本信息（此时类已加载）
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    determineVersionAndHook(ctx, lpparam.classLoader);
                }
            });
        }

        // =============================================================
        // 🚗 战场 B：车机系统进程
        // =============================================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    registerStopReceiver();
                    sysHandler.postDelayed(() -> initSystemEnvironment(lpparam.classLoader), 5000);
                }
            });

            hookPackageManager(lpparam.classLoader);

            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}
        }
    }

    // 🔥 修复：延迟到 Application.onCreate 后再检测版本
    private void determineVersionAndHook(Context ctx, ClassLoader cl) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(PKG_MAP, 0);
            String versionName = info.versionName;
            long versionCode = info.getLongVersionCode();
            
            XposedBridge.log("NaviHook: [Map] App version: " + versionName + " (" + versionCode + ")");
            
            // 7.5 判断：版本号以 7.5 或 7. 开头
            boolean isLegacy75 = versionName != null && (versionName.startsWith("7.5") || versionName.startsWith("7."));
            
            // 备选：如果版本号判断失败，再试类检测（此时类可能已加载）
            if (!isLegacy75) {
                isLegacy75 = XposedHelpers.findClassIfExists("com.AutoHelper", cl) != null;
            }
            
            if (isLegacy75) {
                XposedBridge.log("NaviHook: [Map] ✅ 确认为 7.5，不植入 TrojanBinder");
            } else {
                XposedBridge.log("NaviHook: [Map] ⚡ 确认为 9.1，植入 TrojanBinder");
                try {
                    XposedHelpers.findAndHookMethod(TARGET_SERVICE, cl, "onBind", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(new TrojanBinder(cl));
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log("NaviHook: [Map] Hook 失败: " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Map] 版本检测失败: " + t);
        }
    }

    // =============================================================
    // 🦄 特洛伊 Binder（修复 hexDump，去掉 readBytes）
    // =============================================================
    public static class TrojanBinder extends Binder {
        private ClassLoader classLoader;
        private boolean isSurfaceActive = false;
        private Handler uiHandler;
        private IBinder systemProvider = null;

        public TrojanBinder(ClassLoader cl) {
            this.classLoader = cl;
            this.uiHandler = new Handler(Looper.getMainLooper());
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                int dataSize = data.dataSize();
                
                // 🔥 修复：不用 readBytes，手动读取前16字节
                StringBuilder hexDump = new StringBuilder();
                int startPos = data.dataPosition();
                for (int i = 0; i < 8 && i < dataSize; i++) { // 读8个int（32字节）或更少
                    try {
                        int val = data.readInt();
                        hexDump.append(String.format("%08X ", val));
                    } catch (Exception e) {
                        break;
                    }
                }
                data.setDataPosition(startPos);
                
                XposedBridge.log(String.format(
                    "NaviHook: [Binder] Code=%d Size=%d Data=%s", 
                    code, dataSize, hexDump.toString()
                ));

                // Code 4: 握手
                if (code == 4) {
                    XposedBridge.log("NaviHook: [Binder] 🎯 Code 4 = Handshake");
                    try {
                        data.setDataPosition(0);
                        systemProvider = data.readStrongBinder();
                    } catch (Exception e) {}
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // Code 1: 投屏或心跳
                if (code == 1) {
                    if (dataSize > 100 && !isSurfaceActive) {
                        XposedBridge.log("NaviHook: [Binder] 🎯 Code 1 = AddSurface (large packet)");
                        
                        data.setDataPosition(0);
                        Surface surface = null;
                        
                        try {
                            int hasSurface = data.readInt();
                            if (hasSurface != 0) {
                                surface = Surface.CREATOR.createFromParcel(data);
                            }
                        } catch (Exception e) {
                            try {
                                data.setDataPosition(0);
                                surface = Surface.CREATOR.createFromParcel(data);
                            } catch (Exception e2) {}
                        }

                        if (surface != null && surface.isValid()) {
                            XposedBridge.log("NaviHook: [Binder] ✅ Surface valid!");
                            final Surface s = surface;
                            uiHandler.post(() -> injectNativeEngine(s));
                            isSurfaceActive = true;
                        }
                    } else {
                        XposedBridge.log("NaviHook: [Binder] 💓 Code 1 = Heartbeat (small)");
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // Code 2: 重置
                if (code == 2) {
                    XposedBridge.log("NaviHook: [Binder] 🎯 Code 2 = Reset");
                    isSurfaceActive = false;
                    systemProvider = null;
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (code == 20) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Binder] Error: " + t);
            }
            return true;
        }

        private void injectNativeEngine(Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                m.invoke(null, 1, 2, surface);
                XposedBridge.log("NaviHook: [Map] ✅ Engine injected");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] ❌ Inject failed: " + t);
                isSurfaceActive = false;
            }
        }
    }

    // 其他方法保持不变...
    private void hookPackageManager(ClassLoader cl) {
        XC_MethodHook spoofHook = new XC_MethodHook() {
            @SuppressWarnings("unchecked")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null && TARGET_SERVICE.equals(intent.getComponent().getClassName())) {
                    List<ResolveInfo> result = null;
                    if (param.getResult() instanceof List) {
                        result = (List<ResolveInfo>) param.getResult();
                    } else {
                        ResolveInfo single = (ResolveInfo) param.getResult();
                        if (single == null) result = new ArrayList<>();
                        else return;
                    }
                    
                    if (result == null) result = new ArrayList<>();

                    if (result.isEmpty()) {
                        XposedBridge.log("NaviHook: [PM] Spoofing service");
                        ResolveInfo info = new ResolveInfo();
                        info.serviceInfo = new ServiceInfo();
                        info.serviceInfo.packageName = PKG_MAP;
                        info.serviceInfo.name = TARGET_SERVICE;
                        info.serviceInfo.exported = true;
                        info.serviceInfo.applicationInfo = new ApplicationInfo();
                        info.serviceInfo.applicationInfo.packageName = PKG_MAP;
                        
                        if (param.getResult() instanceof List) {
                            result.add(info);
                            param.setResult(result);
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

    private void initSystemEnvironment(ClassLoader cl) {
        if (isEnvChecked) return;
        isEnvChecked = true;
        
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            
            Object conn = null;
            try { conn = XposedHelpers.getObjectField(dashboardMgr, "f"); } catch (Throwable t) {}
            
            if (conn != null) {
                XposedBridge.log("NaviHook: [Sys] ✅ 7.5 mode (conn exists)");
                startStatusHeartbeat(true);
            } else {
                XposedBridge.log("NaviHook: [Sys] ⚡ 9.1 mode (no conn)");
                bindToMapService();
                startStatusHeartbeat(false);
            }
        } catch (Throwable t) {}
    }

    private void bindToMapService() {
        if (sysContext == null) return;
        sysHandler.post(() -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                sysContext.bindService(intent, new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        XposedBridge.log("NaviHook: [Sys] 9.1 bound");
                        injectToDashboard(service);
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {}
                }, Context.BIND_AUTO_CREATE);
            } catch (Throwable t) {}
        });
    }

    private void injectToDashboard(IBinder binder) {
        try {
            Object internalConn = XposedHelpers.getObjectField(dashboardMgr, "f");
            if (internalConn != null) {
                Method onConnected = internalConn.getClass().getMethod("onServiceConnected", ComponentName.class, IBinder.class);
                onConnected.invoke(internalConn, new ComponentName(PKG_MAP, TARGET_SERVICE), binder);
                triggerMapSwitch();
            }
        } catch (Throwable t) {}
    }

    private void triggerMapSwitch() {
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", 3);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);
            
            Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
            XposedHelpers.setIntField(st, "status", 16);
            XposedBridge.log("NaviHook: [Sys] Activated");
        } catch (Throwable t) {}
    }

    private void startStatusHeartbeat(boolean isLoop) {
        stopStatusHeartbeat();
        
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
    
    private void stopStatusHeartbeat() {
        if (statusHeartbeat != null) {
            statusHeartbeat.cancel();
            statusHeartbeat = null;
        }
    }
    
    private void registerStopReceiver() {
        try {
            sysContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    stopStatusHeartbeat();
                }
            }, new IntentFilter("com.xsf.amaphelper.STOP_HEARTBEAT"));
        } catch (Throwable t) {}
    }
}
