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
import android.os.RemoteException;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_MAP = "com.autonavi.amapauto";
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String TARGET_SERVICE = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    private static final String BINDER_DESCRIPTOR = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String PROVIDER_DESCRIPTOR = "com.autosimilarwidget.view.IAutoWidgetStateProvider";

    private static Context sysContext;
    private static Handler sysHandler;
    private static ClassLoader sysClassLoader;
    private static Object dashboardMgr;
    
    private static boolean isNaviRunning = false;
    private static boolean isInjecting = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.xsf.amaphelper")) {
            XposedHelpers.findAndHookMethod("com.xsf.amaphelper.MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // ==========================================
        // 核心战场 A：LBSNavi 端 (虚拟吉利特权控制器)
        // ==========================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            hookPackageManager(lpparam.classLoader);

            // 动态保活机制，防止启动即投屏
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) {
                    XposedBridge.hookAllMethods(cfg, "g", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isNaviRunning) param.setResult(true); 
                        }
                    });
                }
            } catch (Throwable t) {}

            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysClassLoader = sysContext.getClassLoader();
                    sysHandler = new Handler(Looper.getMainLooper());
                    sysHandler.postDelayed(() -> initVirtualJiliManager(), 4000);
                }
            });
        }

        // ==========================================
        // 核心战场 B：高德地图端 (接管画板 + 护航回调)
        // ==========================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            hookPackageManager(lpparam.classLoader);
            hookSurfaceDimensions(lpparam.classLoader);

            try {
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(new TrojanProxyBinder((IBinder) param.getResult(), (Context) param.thisObject));
                    }
                });
            } catch (Throwable t) {}
        }
    }

    private static void initVirtualJiliManager() {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", sysClassLoader);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");

            // 没收原生干瘪的 16 和 17，由我们全盘接管状态
            XposedBridge.hookAllMethods(mgrClass, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isInjecting || param.args == null || param.args.length == 0 || param.args[0] == null) return;
                    if ("MapStatusInfo".equals(param.args[0].getClass().getSimpleName())) {
                        int status = XposedHelpers.getIntField(param.args[0], "status");
                        if (status == 16 || status == 17) param.setResult(null);
                    }
                }
            });

            // 雷达窃听 9.1 的真实动向
            IntentFilter filter = new IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND");
            sysContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    int keyType = intent.getIntExtra("KEY_TYPE", -1);
                    boolean shouldStart = false;
                    boolean shouldStop = false;

                    if (keyType == 10117) {
                        int mode = intent.getIntExtra("EXTSCREEN_MODE_INFO", -1);
                        int status = intent.getIntExtra("EXTSCREEN_STATUS_INFO", -1);
                        if ((mode == 1 || mode == 2) && status == 0) shouldStart = true;
                        else if (mode == 0) shouldStop = true;
                    } else if (keyType == 10019) {
                        int state = intent.getIntExtra("EXTRA_STATE", -1);
                        if (state == 16) shouldStart = true;
                        else if (state == 17 || state == 9 || state == 12) shouldStop = true;
                    } else if (keyType == 10001) {
                        shouldStart = true;
                    }

                    if (shouldStart && !isNaviRunning) {
                        XposedBridge.log("NaviHook: [Sys] 🚨 雷达侦测到高德 9.1 开始导航! 启动 V254 完美阶梯连招...");
                        isNaviRunning = true;
                        triggerSafeActivationSequence();
                    } else if (shouldStop && isNaviRunning) {
                        XposedBridge.log("NaviHook: [Sys] 🚨 侦测到退出导航, 平稳关屏...");
                        isNaviRunning = false;
                        closeDashboard();
                    }
                }
            }, filter);

            forceBindWidgetService();
            XposedBridge.log("NaviHook: [Sys] 虚拟特权后门就绪, 等待雷达唤醒!");
        } catch (Throwable t) {}
    }

    private static void forceBindWidgetService() {
        try {
            Class<?> hClass = XposedHelpers.findClass("ecarx.naviservice.map.amap.h", sysClassLoader);
            Object inst = XposedHelpers.getStaticObjectField(hClass, "e");
            if (inst != null) {
                Object conn = XposedHelpers.getObjectField(inst, "f");
                if (conn != null) {
                    Intent intent = new Intent().setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                    sysContext.bindService(intent, (ServiceConnection) conn, Context.BIND_AUTO_CREATE);
                }
            } else {
                sysHandler.postDelayed(MainHook::forceBindWidgetService, 2000);
            }
        } catch (Throwable t) {}
    }

    // 🔥 满血复活：严格还原 V254 的阶梯状态机连招！
    private static void triggerSafeActivationSequence() {
        isInjecting = true;
        try {
            Class<?> statusCls = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", sysClassLoader);
            Class<?> switchCls = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", sysClassLoader);

            // 1. 瞬间下发: 状态 1 (清理后台残留)
            sysHandler.post(() -> {
                try {
                    XposedBridge.log("NaviHook: [Sys] 连招1: 发送 MapStatusInfo(1)");
                    Object st1 = XposedHelpers.newInstance(statusCls, 1);
                    XposedHelpers.callMethod(dashboardMgr, "a", st1);
                } catch (Throwable t) {}
            });

            // 2. 延时 100ms 下发: 状态 3 (激活车机前台引擎)
            sysHandler.postDelayed(() -> {
                try {
                    XposedBridge.log("NaviHook: [Sys] 连招2: 发送 MapStatusInfo(3)");
                    Object st3 = XposedHelpers.newInstance(statusCls, 3);
                    XposedHelpers.callMethod(dashboardMgr, "a", st3);
                } catch (Throwable t) {}
            }, 100);

            // 3. 延时 200ms 下发: Switch 3 (下达拉开物理屏幕的指令)
            sysHandler.postDelayed(() -> {
                try {
                    XposedBridge.log("NaviHook: [Sys] 连招3: 发送 Switch(3) (拉开物理幕布)");
                    Object sw = XposedHelpers.newInstance(switchCls, 5, 0); // Ecarx切换至Amap
                    XposedHelpers.setIntField(sw, "mSwitchState", 3);
                    XposedHelpers.callMethod(dashboardMgr, "a", sw);
                } catch (Throwable t) {}
            }, 200);

            // 4. 延时 1700ms: 等待 1.5 秒仪表盘动画结束，拿取画板！防闪退核心！
            sysHandler.postDelayed(() -> {
                if (!isNaviRunning) return; 
                isInjecting = true;
                try {
                    XposedBridge.log("NaviHook: [Sys] 连招4: 发送 MapStatusInfo(16) (索要 Code 1 画板)");
                    Object st16 = XposedHelpers.newInstance(statusCls, 16);
                    XposedHelpers.callMethod(dashboardMgr, "a", st16);
                } catch (Throwable t) {}
                isInjecting = false;
            }, 1700);

        } catch (Throwable t) {
            isInjecting = false;
        }
    }

    private static void closeDashboard() {
        isInjecting = true;
        try {
            Class<?> statusCls = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", sysClassLoader);
            Class<?> switchCls = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", sysClassLoader);

            sysHandler.post(() -> {
                try {
                    Object st17 = XposedHelpers.newInstance(statusCls, 17);
                    XposedHelpers.callMethod(dashboardMgr, "a", st17);
                } catch (Throwable t) {}
            });

            sysHandler.postDelayed(() -> {
                try {
                    Object sw2 = XposedHelpers.newInstance(switchCls, 0, 5); // Amap切回Ecarx
                    XposedHelpers.setIntField(sw2, "mSwitchState", 2); 
                    XposedHelpers.callMethod(dashboardMgr, "a", sw2);
                } catch (Throwable t) {}
                isInjecting = false;
            }, 100);
        } catch (Throwable t) {
            isInjecting = false;
        }
    }

    private static void hookPackageManager(ClassLoader cl) {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null && TARGET_SERVICE.equals(intent.getComponent().getClassName())) {
                    Object res = param.getResult();
                    if (res == null || (res instanceof java.util.List && ((java.util.List) res).isEmpty())) {
                        android.content.pm.ResolveInfo info = new android.content.pm.ResolveInfo();
                        info.serviceInfo = new android.content.pm.ServiceInfo();
                        info.serviceInfo.packageName = PKG_MAP; 
                        info.serviceInfo.name = TARGET_SERVICE; 
                        info.serviceInfo.exported = true;
                        info.serviceInfo.applicationInfo = new android.content.pm.ApplicationInfo(); 
                        info.serviceInfo.applicationInfo.packageName = PKG_MAP;
                        if (res instanceof java.util.List) { 
                            java.util.List l = new java.util.ArrayList(); 
                            l.add(info); 
                            param.setResult(l); 
                        } else { param.setResult(info); }
                    }
                }
            }
        };
        try { 
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", cl, "queryIntentServices", Intent.class, int.class, h); 
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", cl, "resolveService", Intent.class, int.class, h); 
        } catch (Throwable t) {}
    }

    private static void hookSurfaceDimensions(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", cl);
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals("getMapSurfaceWidth")) XposedBridge.hookMethod(m, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return 1920; } });
                else if (m.getName().equals("getMapSurfaceHeight")) XposedBridge.hookMethod(m, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return 720; } });
            }
        } catch (Throwable t) {}
    }

    public static class TrojanProxyBinder extends Binder {
        private IBinder realBinder;
        private Context amapContext;
        private static IBinder sSystemProvider = null;
        private static boolean isUnlockingEngine = false;

        public TrojanProxyBinder(IBinder real, Context ctx) {
            this.realBinder = real; 
            this.amapContext = ctx;
        }

        private void enforceInterfaceSafely(Parcel data, String desc, int pos) {
            try { data.enforceInterface(desc); } catch (Throwable t) { 
                data.setDataPosition(pos); try { data.readInt(); } catch (Throwable ignored) {} data.enforceInterface(desc); 
            }
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1598968902) { if (reply != null) reply.writeString(BINDER_DESCRIPTOR); return true; }
            int start = data.dataPosition();
            
            if (code == 4) {
                try { enforceInterfaceSafely(data, BINDER_DESCRIPTOR, start); sSystemProvider = data.readStrongBinder(); } catch (Throwable t) {}
                data.setDataPosition(start);
            }
            if (code == 3) { if (reply != null) { reply.writeNoException(); reply.writeInt(1); } return true; }

            if (code == 1) {
                XposedBridge.log("NaviHook: [Proxy] 🎯 拿到 Code 1 画板! 移交 9.1 渲染...");
                data.setDataPosition(start);
                boolean realRes = false;
                try { if (realBinder != null) realRes = realBinder.transact(code, data, reply, flags); } catch (Throwable t) {}

                // Binder 首帧护航
                if (sSystemProvider != null && sSystemProvider.isBinderAlive()) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        Parcel d = Parcel.obtain(); Parcel r = Parcel.obtain();
                        try { 
                            d.writeInterfaceToken(PROVIDER_DESCRIPTOR); 
                            sSystemProvider.transact(1, d, r, 0); 
                            XposedBridge.log("NaviHook: [Proxy] 🛡️ Binder 首帧护航信号已发");
                        } catch (Throwable t) {} finally { d.recycle(); r.recycle(); }
                    }, 500);
                }

                // 虚拟 7.5 特供首帧广播
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        Intent intent = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                        intent.putExtra("KEY_TYPE", 10019);
                        intent.putExtra("EXTRA_CURRENT_STATE", 116);
                        amapContext.sendBroadcast(intent);
                        XposedBridge.log("NaviHook: [Proxy] 🚨 10019(116) 虚拟首帧广播已补发! 堵死看门狗!");
                    } catch (Throwable t) {}
                }, 600);

                // 解锁引擎
                if (!isUnlockingEngine) {
                    isUnlockingEngine = true;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            Intent intent1 = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                            intent1.putExtra("KEY_TYPE", 10122);
                            intent1.putExtra("EXTRA_EXTERNAL_MAP_LEVEL", 17.0f);
                            intent1.putExtra("EXTRA_EXTERNAL_MAP_MODE", 3);
                            intent1.putExtra("EXTRA_EXTERNAL_ENGINE_ID", 1001); 
                            amapContext.sendBroadcast(intent1);
                            XposedBridge.log("NaviHook: [Proxy] 🔓 引擎解锁码 10122 已补发!");
                        } catch (Throwable t) {}
                        isUnlockingEngine = false;
                    }, 800);
                }
                return realRes; 
            }
            
            if (code == 2) {
                XposedBridge.log("NaviHook: [Proxy] 🛑 Code 2 (屏幕被收回)");
                data.setDataPosition(start);
                boolean realRes = false;
                try { if (realBinder != null) realRes = realBinder.transact(code, data, reply, flags); } catch (Throwable t) {}
                return realRes;
            }
            
            boolean realRes = false;
            try { if (realBinder != null) { data.setDataPosition(start); realRes = realBinder.transact(code, data, reply, flags); } } catch (Throwable t) { if (reply != null) reply.writeNoException(); return true; }
            return realRes;
        }
    }
}