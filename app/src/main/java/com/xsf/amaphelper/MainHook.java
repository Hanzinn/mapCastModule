package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
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
    private static final String ACTION_STOP_HEARTBEAT = "com.xsf.amaphelper.STOP_HEARTBEAT";

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

        if (lpparam.packageName.equals(PKG_MAP)) {
            boolean isLegacy75 = XposedHelpers.findClassIfExists("com.AutoHelper", lpparam.classLoader) != null;
            XposedBridge.log("NaviHook: [Map] Version detect: " + (isLegacy75 ? "7.5" : "9.1"));
            
            if (isLegacy75) {
                XposedBridge.log("NaviHook: [Map] 7.5 mode - Observer only");
            } else {
                XposedBridge.log("NaviHook: [Map] 9.1 mode - Trojan injected");
                try {
                    XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(new TrojanBinder(lpparam.classLoader));
                        }
                    });
                } catch (Throwable t) {}
            }
        }

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

    // =============================================================
    // 🔍 全量日志版 TrojanBinder (诊断专用)
    // =============================================================
    public static class TrojanBinder extends Binder {
        private ClassLoader classLoader;
        private boolean isSurfaceActive = false;
        private Handler uiHandler;
        private IBinder systemProvider = null;
        private long lastLogTime = 0; // 防止日志刷屏

        public TrojanBinder(ClassLoader cl) {
            this.classLoader = cl;
            this.uiHandler = new Handler(Looper.getMainLooper());
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                // 🔥 全量日志记录每个 Code
                int dataSize = data.dataSize();
                int available = data.dataAvail();
                
                // 读取前16字节用于分析协议
                StringBuilder hexDump = new StringBuilder();
                int startPos = data.dataPosition();
                byte[] header = new byte[Math.min(16, dataSize)];
                try {
                    data.readBytes(header);
                    for (byte b : header) {
                        hexDump.append(String.format("%02X ", b));
                    }
                    data.setDataPosition(startPos); // 恢复位置
                } catch (Exception e) {
                    hexDump.append("READ_ERROR");
                }
                
                // 每5秒才打印一次重复 Code，防止刷屏，但首次一定打印
                long now = System.currentTimeMillis();
                boolean shouldLog = (now - lastLogTime > 5000) || (code != 1 && code != 2);
                
                if (shouldLog) {
                    XposedBridge.log(String.format(
                        "NaviHook: [Binder] Code=%d | Size=%d | Avail=%d | Header=%s",
                        code, dataSize, available, hexDump.toString()
                    ));
                    lastLogTime = now;
                }

                // ========== Code 4: 首次握手 (Register) ==========
                if (code == 4) {
                    XposedBridge.log("NaviHook: [Binder] 🎯 Code 4 = First Handshake (System->Map)");
                    try {
                        data.setDataPosition(0);
                        systemProvider = data.readStrongBinder();
                        XposedBridge.log("NaviHook: [Binder] SystemProvider saved: " + systemProvider);
                    } catch (Exception e) {
                        XposedBridge.log("NaviHook: [Binder] Code 4 read error: " + e);
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // ========== Code 1: 可能是心跳，也可能是 Surface ==========
                if (code == 1) {
                    // 根据数据大小区分
                    if (dataSize > 100 && !isSurfaceActive) {
                        // 大概率是 Surface 传输
                        XposedBridge.log("NaviHook: [Binder] 🎯 Code 1 = AddSurface (Large packet: " + dataSize + ")");
                        
                        data.setDataPosition(0);
                        Surface surface = null;
                        
                        // 尝试多种解析方式
                        try {
                            int hasSurface = data.readInt();
                            XposedBridge.log("NaviHook: [Binder] hasSurface flag: " + hasSurface);
                            if (hasSurface != 0) {
                                surface = Surface.CREATOR.createFromParcel(data);
                            }
                        } catch (Exception e) {
                            XposedBridge.log("NaviHook: [Binder] Parse with flag failed: " + e);
                            // 备选：直接读取
                            try {
                                data.setDataPosition(0);
                                surface = Surface.CREATOR.createFromParcel(data);
                                XposedBridge.log("NaviHook: [Binder] Direct parse success");
                            } catch (Exception e2) {
                                XposedBridge.log("NaviHook: [Binder] Direct parse failed: " + e2);
                            }
                        }

                        if (surface != null && surface.isValid()) {
                            XposedBridge.log("NaviHook: [Binder] ✅ Surface valid! Injecting...");
                            final Surface s = surface;
                            uiHandler.post(() -> injectNativeEngine(s));
                            isSurfaceActive = true;
                        } else {
                            XposedBridge.log("NaviHook: [Binder] ❌ Surface null or invalid");
                        }
                    } else {
                        // 小包 = 心跳
                        if (shouldLog) {
                            XposedBridge.log("NaviHook: [Binder] 💓 Code 1 = Heartbeat (Small packet)");
                        }
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // ========== Code 2: 断开或状态查询 ==========
                if (code == 2) {
                    if (isSurfaceActive) {
                        XposedBridge.log("NaviHook: [Binder] 🎯 Code 2 = RemoveSurface/Disconnect");
                        isSurfaceActive = false;
                        systemProvider = null;
                    } else {
                        if (shouldLog) {
                            XposedBridge.log("NaviHook: [Binder] 💓 Code 2 = Heartbeat/Query");
                        }
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // ========== Code 20: 可能是注册 ==========
                if (code == 20) {
                    XposedBridge.log("NaviHook: [Binder] 🎯 Code 20 = Register/Handshake");
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // 其他未知 Code
                if (shouldLog) {
                    XposedBridge.log("NaviHook: [Binder] ❓ Unknown Code: " + code);
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
                XposedBridge.log("NaviHook: [Map] ✅ Engine injected successfully");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] ❌ Inject failed: " + t);
                isSurfaceActive = false;
            }
        }
    }

    // =============================================================
    // 系统侧代码保持不变...
    // =============================================================
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
                        XposedBridge.log("NaviHook: [PM] Spoofing service visibility");
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
            
            String connName = (conn != null) ? conn.getClass().getName() : "null";
            XposedBridge.log("NaviHook: [Sys] Conn check: " + connName);
            
            if (conn != null) {
                XposedBridge.log("NaviHook: [Sys] 7.5 mode - Heartbeat only");
                startStatusHeartbeat(true);
            } else {
                XposedBridge.log("NaviHook: [Sys] 9.1 mode - Full activation");
                bindToMapService();
                startStatusHeartbeat(false);
            }

        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] Init error: " + t);
        }
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
                        XposedBridge.log("NaviHook: [Sys] 9.1 bound successfully");
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
            XposedBridge.log("NaviHook: [Sys] Triggered switch to status 16");
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
                    boolean statusChanged = (lastSentStatus != 16);
                    
                    ClassLoader cl = sysContext.getClassLoader();
                    Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
                    XposedHelpers.setIntField(sw, "mSwitchState", 3);
                    XposedHelpers.callMethod(dashboardMgr, "a", sw);

                    Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
                    XposedHelpers.setIntField(st, "status", 16);
                    XposedHelpers.callMethod(dashboardMgr, "a", st);
                    
                    if (statusChanged) {
                        XposedBridge.log("NaviHook: [Sys] Status maintained at 16");
                        lastSentStatus = 16;
                    }
                } catch (Throwable t) {}
            }
        }, 1000, isLoop ? 3000 : 9999999);
    }
    
    private void stopStatusHeartbeat() {
        if (statusHeartbeat != null) {
            statusHeartbeat.cancel();
            statusHeartbeat = null;
            lastSentStatus = -1;
        }
    }
    
    private void registerStopReceiver() {
        try {
            IntentFilter filter = new IntentFilter(ACTION_STOP_HEARTBEAT);
            sysContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    stopStatusHeartbeat();
                    XposedBridge.log("NaviHook: [Sys] Heartbeat stopped by broadcast");
                }
            }, filter);
        } catch (Throwable t) {}
    }
}
