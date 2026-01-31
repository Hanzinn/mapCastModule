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
    private static int lastSentStatus = -1; // 状态缓存，防止日志刷屏

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
                // 【7.5 策略 - 优化版】
                // 植入"观察者" Binder，不拦截逻辑，仅用于确认原生连接状态
                XposedBridge.log("NaviHook: [Map] ⚠️ 识别为 7.5。启用观察模式，不替换 Binder。");
                try {
                    XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // 不修改 result，让原生 Binder 返回
                            XposedBridge.log("NaviHook: [Map-7.5] 原生 onBind 被调用 (Native Binder Active)");
                        }
                    });
                } catch (Throwable t) {}
            } else {
                // 【9.1 策略】
                // 必须 Hook onBind 植入特洛伊木马
                XposedBridge.log("NaviHook: [Map] ⚡ 识别为 9.1。启用注入模式。");
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
                    
                    // 注册优雅停止广播
                    registerStopReceiver();
                    
                    sysHandler.postDelayed(() -> initSystemEnvironment(lpparam.classLoader), 5000);
                }
            });

            // 🔥 PM 欺骗：7.5 巡航投屏的基础
            hookPackageManager(lpparam.classLoader);

            // 解锁 Vendor 校验
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}
        }
    }

    // =============================================================
    // 🦄 特洛伊 Binder (9.1 专用 + 数据包校验)
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
                // Code 4/43: Surface 传输
                if (code == 4 || code == 43) {
                    // 🔥 优化：数据包大小校验，防止误判
                    if (data.dataSize() < 50) {
                        XposedBridge.log("NaviHook: [Binder] 忽略小包 (Size: " + data.dataSize() + ")");
                        return true; 
                    }

                    if (isSurfaceActive) {
                        if (reply != null) reply.writeNoException();
                        return true;
                    }

                    data.setDataPosition(0);
                    try { data.readString(); } catch (Exception e) {} 

                    if (data.readInt() != 0) {
                        Surface surface = Surface.CREATOR.createFromParcel(data);
                        if (surface != null && surface.isValid()) {
                            XposedBridge.log("NaviHook: [Binder] 9.1 捕获有效 Surface，主线程注入...");
                            uiHandler.post(() -> injectNativeEngine(surface));
                            isSurfaceActive = true;
                        }
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // Code 2: Reset
                if (code == 2) {
                    isSurfaceActive = false;
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // Code 20/1: Heartbeat
                if (code == 20 || code == 1) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }
            } catch (Throwable t) {}
            return true;
        }

        private void injectNativeEngine(Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                m.invoke(null, 1, 2, surface);
            } catch (Throwable t) { isSurfaceActive = false; }
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
    // 📡 系统侧智能初始化 & 优雅停止
    // =============================================================
    
    // 注册广播接收器，用于优雅停止心跳
    private void registerStopReceiver() {
        try {
            IntentFilter filter = new IntentFilter(ACTION_STOP_HEARTBEAT);
            sysContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_STOP_HEARTBEAT.equals(intent.getAction())) {
                        stopStatusHeartbeat();
                        XposedBridge.log("NaviHook: [Sys] 🛑 收到指令，心跳已停止。");
                    }
                }
            }, filter);
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] 广播注册失败: " + t);
        }
    }

    private void initSystemEnvironment(ClassLoader cl) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            
            Object conn = null;
            try { conn = XposedHelpers.getObjectField(dashboardMgr, "f"); } catch (Throwable t) {}
            
            if (conn != null) {
                // 【7.5 模式】
                XposedBridge.log("NaviHook: [Sys] ✅ 发现原生连接 (7.5)。仅启动心跳，不Bind。");
                // 启动循环心跳，维持巡航状态
                startStatusHeartbeat(true); 
            } else {
                // 【9.1 模式】
                XposedBridge.log("NaviHook: [Sys] ❌ 未发现连接 (9.1)。执行 Bind + 激活。");
                bindToMapService();
                // 9.1 不需要循环心跳，发一次即可
                startStatusHeartbeat(false); 
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
            }
        } catch (Throwable t) {}
    }

    // 💓 智能心跳
    private void startStatusHeartbeat(boolean isLoop) {
        stopStatusHeartbeat(); // 先清理旧的
        
        statusHeartbeat = new Timer();
        statusHeartbeat.schedule(new TimerTask() {
            @Override
            public void run() {
                if (sysContext == null || dashboardMgr == null) {
                    this.cancel();
                    return;
                }
                try {
                    // 🔥 优化：只在状态变化时打印日志，但指令依然发送（为了覆盖系统回滚）
                    boolean statusChanged = (lastSentStatus != 16);
                    
                    ClassLoader cl = sysContext.getClassLoader();
                    
                    // Switch State 3 (投屏)
                    Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
                    XposedHelpers.setIntField(sw, "mSwitchState", 3);
                    XposedHelpers.callMethod(dashboardMgr, "a", sw);

                    // Status 16 (导航中)
                    Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
                    XposedHelpers.setIntField(st, "status", 16);
                    XposedHelpers.callMethod(dashboardMgr, "a", st);
                    
                    if (statusChanged) {
                        XposedBridge.log("NaviHook: [Sys] ⚡ 状态已强制为 16 (投屏模式)");
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
            lastSentStatus = -1; // 重置状态
        }
    }
}