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

    // 系统侧变量
    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static Timer statusHeartbeat;
    private static int lastSentStatus = -1;
    
    // 🔥 关键修复：防重入锁，防止 7.5 被误判为 9.1
    private static volatile boolean isEnvChecked = false;

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
            // 1. 版本判定
            boolean isLegacy75 = XposedHelpers.findClassIfExists("com.AutoHelper", lpparam.classLoader) != null;
            
            if (isLegacy75) {
                // 【7.5 策略】绝对不 Hook onBind，避免双重 Binder
                XposedBridge.log("NaviHook: [Map] ⚠️ 识别为 7.5 (Legacy)。启用观察模式，不替换 Binder。");
            } else {
                // 【9.1 策略】植入特洛伊木马
                XposedBridge.log("NaviHook: [Map] ⚡ 识别为 9.1 (Modern)。植入 TrojanBinder。");
                try {
                    XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            XposedBridge.log("NaviHook: [Map] 🚨 9.1 收到 Bind，释放 TrojanBinder...");
                            param.setResult(new TrojanBinder(lpparam.classLoader));
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log("NaviHook: [Map] Hook 失败: " + t);
                }
            }
            
            // 防御性 Hook
            try {
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onCreate", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {}
                });
            } catch (Throwable t) {}
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

    // =============================================================
    // 🦄 特洛伊 Binder (9.1 专用 - 严格区分 Code 4/1)
    // =============================================================
    public static class TrojanBinder extends Binder {
        private ClassLoader classLoader;
        private boolean isSurfaceActive = false;
        private Handler uiHandler;
        private IBinder systemProvider = null; // 保存系统回调，用于防闪烁

        public TrojanBinder(ClassLoader cl) {
            this.classLoader = cl;
            this.uiHandler = new Handler(Looper.getMainLooper());
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                // 🔥 修复 1：Code 4 是握手 (setWidgetStateControl)，不是 Surface！
                if (code == 4) {
                    XposedBridge.log("NaviHook: [Binder] Code 4 (Handshake)");
                    
                    // 读取系统回调 IBinder (v182 经验)
                    try {
                        data.setDataPosition(0);
                        systemProvider = data.readStrongBinder();
                        XposedBridge.log("NaviHook: [Binder] SystemProvider attached");
                    } catch (Exception e) {}
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // 🔥 修复 2：Code 1 才是真正的 addSurface
                if (code == 1) {
                    XposedBridge.log("NaviHook: [Binder] Code 1 (AddSurface)");
                    
                    if (isSurfaceActive) {
                        if (reply != null) reply.writeNoException();
                        return true;
                    }

                    data.setDataPosition(0);
                    Surface surface = null;
                    
                    // 尝试解析 Surface (可能有 hasSurface 标志位)
                    try {
                        int hasSurface = data.readInt();
                        if (hasSurface != 0) {
                            surface = Surface.CREATOR.createFromParcel(data);
                        }
                    } catch (Exception e) {
                        // 备选：直接尝试读取
                        try {
                            data.setDataPosition(0);
                            surface = Surface.CREATOR.createFromParcel(data);
                        } catch (Exception e2) {}
                    }

                    if (surface != null && surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ✅ Surface valid, injecting...");
                        final Surface s = surface;
                        uiHandler.post(() -> injectNativeEngine(s));
                        isSurfaceActive = true;
                        
                        // 通知系统帧就绪 (防闪烁关键)
                        notifyProviderReady();
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // Code 2: 断开/重置
                if (code == 2) {
                    XposedBridge.log("NaviHook: [Binder] Code 2 (Reset)");
                    isSurfaceActive = false;
                    systemProvider = null;
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // Code 20/43: 其他握手或兼容
                if (code == 20 || code == 43) {
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
                XposedBridge.log("NaviHook: [Map] Engine injected");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] Inject failed: " + t);
                isSurfaceActive = false;
            }
        }
        
        // v182 防闪烁协议：通知系统一帧已就绪
        private void notifyProviderReady() {
            if (systemProvider == null) return;
            try {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                systemProvider.transact(1, data, reply, 1); // 通知系统
                data.recycle();
                reply.recycle();
            } catch (RemoteException e) {}
        }
    }

    // =============================================================
    // 🛠️ PM 欺骗 (7.5 巡航核心)
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
                        XposedBridge.log("NaviHook: [PM] 🎭 伪造服务可见性");
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

    // =============================================================
    // 📡 系统侧智能初始化 (防重入修复)
    // =============================================================
    private void initSystemEnvironment(ClassLoader cl) {
        // 🔥 关键修复：防止重复执行导致 7.5 被误判为 9.1
        if (isEnvChecked) {
            XposedBridge.log("NaviHook: [Sys] 环境已初始化，跳过");
            return;
        }
        isEnvChecked = true;
        
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            
            Object conn = null;
            try { conn = XposedHelpers.getObjectField(dashboardMgr, "f"); } catch (Throwable t) {}
            
            String connName = (conn != null) ? conn.getClass().getName() : "null";
            XposedBridge.log("NaviHook: [Sys] 当前连接对象: " + connName);
            
            if (conn != null) {
                // 【7.5 模式】原生连接已存在，绝对不能 bind！
                XposedBridge.log("NaviHook: [Sys] ✅ 7.5 Native Mode (conn exists)");
                XposedBridge.log("NaviHook: [Sys] ⛔ 停止主动 Bind，仅启动巡航心跳");
                startStatusHeartbeat(true); // 7.5 需要循环心跳维持巡航状态
            } else {
                // 【9.1 模式】无原生连接，需要激活
                XposedBridge.log("NaviHook: [Sys] ⚡ 9.1 Mode (no conn)");
                XposedBridge.log("NaviHook: [Sys] 🚀 执行 Bind + 激活...");
                bindToMapService();
                startStatusHeartbeat(false); // 9.1 只需要一次激活
            }

        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] 初始化错误: " + t);
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
                        XposedBridge.log("NaviHook: [Sys] 9.1 物理连接成功");
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
                    boolean statusChanged = (lastSentStatus != 16);
                    
                    ClassLoader cl = sysContext.getClassLoader();
                    Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
                    XposedHelpers.setIntField(sw, "mSwitchState", 3);
                    XposedHelpers.callMethod(dashboardMgr, "a", sw);

                    Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
                    XposedHelpers.setIntField(st, "status", 16);
                    XposedHelpers.callMethod(dashboardMgr, "a", st);
                    
                    if (statusChanged) {
                        XposedBridge.log("NaviHook: [Sys] ⚡ Status forced to 16");
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
                    XposedBridge.log("NaviHook: [Sys] 🛑 Heartbeat stopped");
                }
            }, filter);
        } catch (Throwable t) {}
    }
}
