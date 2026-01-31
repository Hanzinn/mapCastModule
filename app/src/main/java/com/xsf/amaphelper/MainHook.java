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

    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static Timer statusHeartbeat;
    private static int lastSentStatus = -1;

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
            // 版本判定：7.5 必须避让，9.1 必须注入
            boolean isLegacy75 = XposedHelpers.findClassIfExists("com.AutoHelper", lpparam.classLoader) != null;
            
            if (isLegacy75) {
                // 【7.5 策略】绝对不 Hook onBind，防止双重 Binder 冲突（闪烁根源）
                XposedBridge.log("NaviHook: [Map] ⚠️ 识别为 7.5。保留原生 Binder，不进行植入。");
            } else {
                // 【9.1 策略】Hook onBind，植入特洛伊木马
                XposedBridge.log("NaviHook: [Map] ⚡ 识别为 9.1。植入 V206 TrojanBinder。");
                try {
                    XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            XposedBridge.log("NaviHook: [Map] 🚨 9.1 Bind 拦截，返回 TrojanBinder");
                            param.setResult(new TrojanBinder(lpparam.classLoader));
                        }
                    });
                } catch (Throwable t) {}
            }
            
            // 防御性 Hook，防止 onCreate 报错
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
                    
                    // 延迟执行，等待系统初始化完成
                    sysHandler.postDelayed(() -> initSystemEnvironment(lpparam.classLoader), 5000);
                }
            });

            // 🔥 [关键] PM 欺骗：让 7.5 在巡航时也能被发现
            hookPackageManager(lpparam.classLoader);

            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}
        }
    }

    // =============================================================
    // 🦄 特洛伊 Binder (仅 9.1 激活)
    // =============================================================
    public static class TrojanBinder extends Binder {
        private ClassLoader classLoader;
        private boolean isSurfaceActive = false;
        private Handler uiHandler; // ✅ 修复 9.1 黑屏/握手失败的核心

        public TrojanBinder(ClassLoader cl) {
            this.classLoader = cl;
            this.uiHandler = new Handler(Looper.getMainLooper());
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                // Code 4 / 43: Surface 传输
                if (code == 4 || code == 43) {
                    // 数据包大小校验，过滤无效包
                    if (data.dataSize() < 50) return true;

                    if (isSurfaceActive) {
                        if (reply != null) reply.writeNoException();
                        return true;
                    }

                    data.setDataPosition(0);
                    try { data.readString(); } catch (Exception e) {} 

                    if (data.readInt() != 0) {
                        Surface surface = Surface.CREATOR.createFromParcel(data);
                        if (surface != null && surface.isValid()) {
                            XposedBridge.log("NaviHook: [Binder] 9.1 收到 Surface! 主线程注入...");
                            
                            // 🔥🔥🔥 核心修复：必须切回主线程调用 Native 引擎
                            // 否则 9.1 会黑屏并断开连接 (Code 2)
                            uiHandler.post(() -> injectNativeEngine(surface));
                            
                            isSurfaceActive = true;
                        }
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // Code 2: 断开/停止
                if (code == 2) {
                    XposedBridge.log("NaviHook: [Binder] 收到 Code 2 (系统请求断开)");
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

        private void injectNativeEngine(Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                m.invoke(null, 1, 2, surface);
                XposedBridge.log("NaviHook: [Map] ✅ 引擎注入调用完成");
            } catch (Throwable t) { 
                XposedBridge.log("NaviHook: [Map] ❌ 引擎注入失败: " + t);
                isSurfaceActive = false; 
            }
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
                        if (param.getResult() == null) result = new ArrayList<>();
                        else return; // 已经找到了，无需欺骗
                    }
                    
                    if (result == null) result = new ArrayList<>();
                    if (result.isEmpty()) {
                        // XposedBridge.log("NaviHook: [PM] 欺骗系统服务存在"); 
                        ResolveInfo info = new ResolveInfo();
                        info.serviceInfo = new ServiceInfo();
                        info.serviceInfo.packageName = PKG_MAP;
                        info.serviceInfo.name = TARGET_SERVICE;
                        info.serviceInfo.exported = true; // 关键：Exported
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
    // 📡 系统侧逻辑 (V206 修正版)
    // =============================================================
    private void initSystemEnvironment(ClassLoader cl) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            
            Object conn = null;
            try { conn = XposedHelpers.getObjectField(dashboardMgr, "f"); } catch (Throwable t) {}
            
            // 🔥 V206 核心修正：
            // 不要检查类名！因为日志显示是混淆的 "ecarx.naviservice.a.i"。
            // 只要 conn 不为空，就说明系统认为自己连上了（7.5 原生模式）。
            
            if (conn != null) {
                // 【7.5 模式】
                XposedBridge.log("NaviHook: [Sys] ✅ 发现现有连接 (判定为 7.5 原生)。");
                XposedBridge.log("NaviHook: [Sys] ⛔ 禁止执行 Bind，根治闪烁！");
                
                // 启动循环心跳 (每3秒)，强行把状态置为 16
                // 这样即使 7.5 在巡航，也会被心跳“骗”去投屏
                startStatusHeartbeat(true); 
            } else {
                // 【9.1 模式】
                XposedBridge.log("NaviHook: [Sys] ❌ 连接为空 (判定为 9.1)。");
                XposedBridge.log("NaviHook: [Sys] 🚀 执行 Bind + 激活。");
                bindToMapService();
                // 9.1 只需要发一次激活
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
                        XposedBridge.log("NaviHook: [Sys] 9.1 Bind 成功");
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

    // 状态心跳
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
                    // 7.5 需要循环覆盖原生状态，9.1 只需要激活一次
                    ClassLoader cl = sysContext.getClassLoader();
                    
                    Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
                    XposedHelpers.setIntField(sw, "mSwitchState", 3);
                    XposedHelpers.callMethod(dashboardMgr, "a", sw);

                    Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
                    XposedHelpers.setIntField(st, "status", 16); // 16 = 导航中
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
            IntentFilter filter = new IntentFilter(ACTION_STOP_HEARTBEAT);
            sysContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_STOP_HEARTBEAT.equals(intent.getAction())) {
                        stopStatusHeartbeat();
                        XposedBridge.log("NaviHook: [Sys] 心跳已停止");
                    }
                }
            }, filter);
        } catch (Throwable t) {}
    }
}