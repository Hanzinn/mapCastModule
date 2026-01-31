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
            // 7.5 和 9.1 的分流判断
            boolean isLegacy75 = XposedHelpers.findClassIfExists("com.AutoHelper", lpparam.classLoader) != null;
            
            if (isLegacy75) {
                // 【7.5 策略】绝对不 Hook onBind，防止双重 Binder 冲突
                XposedBridge.log("NaviHook: [Map] ⚠️ 识别为 7.5。保留原生 Binder，不进行植入。");
            } else {
                // 【9.1 策略】Hook onBind，植入 V208 暴力解析版
                XposedBridge.log("NaviHook: [Map] ⚡ 识别为 9.1。植入 V208 Brute-Force Binder。");
                try {
                    XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(new TrojanBinder(lpparam.classLoader));
                        }
                    });
                } catch (Throwable t) {}
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
    // 🦄 V208 特洛伊 Binder (暴力解析版)
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
                
                // 🔥 策略：无论 Code 是多少 (1, 2, 4, 43)，只要包够大，就说明里面藏着 Surface
                // 你的日志证明 Code 2 大小为 248，这就是我们要找的！
                if (dataSize > 100) {
                    
                    if (isSurfaceActive && code == 1) {
                         // 已经激活了，Code 1 可能是后续更新，忽略以防闪烁
                         if (reply != null) reply.writeNoException();
                         return true;
                    }

                    XposedBridge.log("NaviHook: [Binder] 🔍 收到大包 Code " + code + " (Size=" + dataSize + ")，尝试暴力破解...");
                    
                    // 调用暴力解析器
                    Surface surface = tryBruteForceParse(data);
                    
                    if (surface != null && surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ✅✅✅ 破解成功！在 Code " + code + " 中找到 Surface！");
                        XposedBridge.log("NaviHook: [Binder] 🚀 立即注入引擎...");
                        
                        final Surface s = surface;
                        uiHandler.post(() -> injectNativeEngine(s));
                        
                        isSurfaceActive = true;
                    } else {
                         XposedBridge.log("NaviHook: [Binder] ❌ 解析失败，可能不是 Surface 包");
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // 处理明确的 Reset 指令 (Code 2 且包很小的时候)
                if (code == 2 && dataSize < 50) { 
                    XposedBridge.log("NaviHook: [Binder] 收到 Reset 指令");
                    isSurfaceActive = false;
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

        // 🔥🔥🔥 核心：暴力解析器 (Brute Force Parser)
        // 它的作用是不管包头是什么，从第 0 个字节开始往后试，直到读出 Surface 为止
        private Surface tryBruteForceParse(Parcel data) {
            int originalPos = data.dataPosition();
            
            // 尝试这些常见偏移量 (跳过 InterfaceToken)
            // 0, 4, 8 ... 40
            int[] offsets = {0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40};
            
            for (int offset : offsets) {
                if (offset >= data.dataSize()) break;
                try {
                    data.setDataPosition(offset);
                    // 尝试读取
                    Surface s = Surface.CREATOR.createFromParcel(data);
                    if (s != null && s.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] 🔓 在 Offset " + offset + " 处成功读取 Surface！");
                        return s;
                    }
                } catch (Throwable e) {
                    // 读错了会报错，忽略，继续试下一个
                }
            }
            
            // 恢复指针
            data.setDataPosition(originalPos);
            return null;
        }

        private void injectNativeEngine(Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                // 参数：1=仪表DisplayID, 2=SurfaceType, surface
                Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                m.invoke(null, 1, 2, surface);
                XposedBridge.log("NaviHook: [Map] ✅ Native 引擎调用完成");
            } catch (Throwable t) { 
                XposedBridge.log("NaviHook: [Map] ❌ 注入异常: " + t);
                isSurfaceActive = false; 
            }
        }
    }

    // =============================================================
    // 🛠️ PM 欺骗 (7.5)
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
                        else return; 
                    }
                    if (result == null) result = new ArrayList<>();
                    if (result.isEmpty()) {
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
    // 📡 系统侧逻辑
    // =============================================================
    private void initSystemEnvironment(ClassLoader cl) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            Object conn = null;
            try { conn = XposedHelpers.getObjectField(dashboardMgr, "f"); } catch (Throwable t) {}
            
            // 7.5 判定逻辑：如果有连接，说明是 7.5 原生
            if (conn != null) {
                XposedBridge.log("NaviHook: [Sys] ✅ 判定为 7.5 原生模式，仅启动心跳。");
                startStatusHeartbeat(true); 
            } else {
                XposedBridge.log("NaviHook: [Sys] ❌ 判定为 9.1 模式，执行 Bind。");
                bindToMapService();
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
            IntentFilter filter = new IntentFilter(ACTION_STOP_HEARTBEAT);
            sysContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_STOP_HEARTBEAT.equals(intent.getAction())) {
                        stopStatusHeartbeat();
                    }
                }
            }, filter);
        } catch (Throwable t) {}
    }
}