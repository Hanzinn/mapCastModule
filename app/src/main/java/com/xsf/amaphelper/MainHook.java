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
        // 战场 A：LBSNavi 端 (破除软件画板锁 + 物理动画锁)
        // ==========================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedBridge.log("NaviHook: [Sys] V297 终极双锁破壁版就绪");
            hookPackageManager(lpparam.classLoader);
            
            // 动态保活
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
                    sysHandler.postDelayed(() -> initDualLockBreaker(), 4000);
                }
            });
        }

        // ==========================================
        // 战场 B：高德端 (首帧精准护航与引擎解锁)
        // ==========================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            XposedBridge.log("NaviHook: [Amap] V297 注入高德");
            hookPackageManager(lpparam.classLoader);
            hookSurfaceDimensions(lpparam.classLoader);
            
            try {
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XposedBridge.log("NaviHook: [Amap] 成功接管 WidgetService onBind");
                        param.setResult(new TrojanProxyBinder((IBinder) param.getResult(), (Context) param.thisObject));
                    }
                });
            } catch (Throwable t) {}
        }
    }

    private static void initDualLockBreaker() {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", sysClassLoader);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");

            // 没收原生杂乱状态，防止打断连招
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

            IntentFilter filter = new IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND");
            sysContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getBooleanExtra("FROM_HOOK", false)) return;
                    
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
                        if (state == 16 || state == 200 || state == 8) shouldStart = true;
                        else if (state == 17 || state == 9 || state == 12) shouldStop = true;
                    } else if (keyType == 10001) {
                        shouldStart = true;
                    }

                    if (shouldStart && !isNaviRunning) {
                        XposedBridge.log("NaviHook: [Sys] 🚨 侦测到导航！正在同时砸开软件画板锁和物理动画锁...");
                        isNaviRunning = true;
                        triggerDualActivationSequence();
                    } else if (shouldStop && isNaviRunning) {
                        XposedBridge.log("NaviHook: [Sys] 🚨 退出导航，下发双重关屏指令...");
                        isNaviRunning = false;
                        closeDashboard();
                    }
                }
            }, filter);

            forceBindWidgetService();
            XposedBridge.log("NaviHook: [Sys] 双锁破壁雷达就绪!");
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

    private static void triggerDualActivationSequence() {
        isInjecting = true;
        try {
            // 🔥 第一锁 (物理动画锁)：注入吉利特权变量 EXTSCREEN_OPERATE_TYPE，逼迫仪表盘拉开转速表！
            Intent openIntent = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
            openIntent.putExtra("KEY_TYPE", 10117);
            openIntent.putExtra("EXTSCREEN_MODE_INFO", 1);     // 导航模式
            openIntent.putExtra("EXTSCREEN_STATUS_INFO", 0);   // 9.1 通用开屏码
            openIntent.putExtra("EXTSCREEN_OPERATE_TYPE", 0);  // 吉利 7.5 特权开屏码 (核心暴击)
            openIntent.putExtra("FROM_HOOK", true);
            sysContext.sendBroadcast(openIntent);
            
            // 盲发一条 Geely 原厂强制开屏广播补刀
            Intent easIntent = new Intent("ecarx.intent.action.EAS_NAVI_DASHBOARD_SHOW");
            sysContext.sendBroadcast(easIntent);
            XposedBridge.log("NaviHook: [Sys] 💥 物理动画锁指令已发射！");

            // 🔥 第二锁 (软件画板锁)：向 LBSNavi 状态机索要 Code 1
            Class<?> statusCls = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", sysClassLoader);
            Class<?> switchCls = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", sysClassLoader);

            Object sw = XposedHelpers.newInstance(switchCls, 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", 3);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);

            // 1.5秒动画等待期后索要画布
            sysHandler.postDelayed(() -> {
                if (!isNaviRunning) return; 
                isInjecting = true;
                try {
                    Object st16 = XposedHelpers.newInstance(statusCls, 0);
                    XposedHelpers.setIntField(st16, "status", 16);
                    XposedHelpers.callMethod(dashboardMgr, "a", st16);
                    XposedBridge.log("NaviHook: [Sys] 💥 软件画板锁指令(16)已下发，等待 Code 1！");
                } catch (Throwable t) {}
                isInjecting = false;
            }, 1500);

        } catch (Throwable t) {}
        isInjecting = false;
    }

    private static void closeDashboard() {
        isInjecting = true;
        try {
            // 关屏特权广播
            Intent closeIntent = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
            closeIntent.putExtra("KEY_TYPE", 10117);
            closeIntent.putExtra("EXTSCREEN_MODE_INFO", 0);
            closeIntent.putExtra("EXTSCREEN_STATUS_INFO", 1);
            closeIntent.putExtra("EXTSCREEN_OPERATE_TYPE", 1);
            closeIntent.putExtra("FROM_HOOK", true);
            sysContext.sendBroadcast(closeIntent);
            sysContext.sendBroadcast(new Intent("ecarx.intent.action.EAS_NAVI_DASHBOARD_HIDE"));

            Class<?> statusCls = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", sysClassLoader);
            Class<?> switchCls = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", sysClassLoader);
            
            Object st17 = XposedHelpers.newInstance(statusCls, 0);
            XposedHelpers.setIntField(st17, "status", 17);
            XposedHelpers.callMethod(dashboardMgr, "a", st17);
            
            Object sw2 = XposedHelpers.newInstance(switchCls, 5, 0);
            XposedHelpers.setIntField(sw2, "mSwitchState", 2); 
            XposedHelpers.callMethod(dashboardMgr, "a", sw2);
        } catch (Throwable t) {}
        isInjecting = false;
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
                            java.util.List l = new java.util.ArrayList(); l.add(info); param.setResult(l); 
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

                // 🔥 首帧护航终极版：反射精准调用方法，杜绝 transact(1) 瞎猜！
                if (sSystemProvider != null && sSystemProvider.isBinderAlive()) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            Class<?> stubCls = XposedHelpers.findClass("com.autosimilarwidget.view.IAutoWidgetStateProvider$Stub", amapContext.getClassLoader());
                            Object provider = XposedHelpers.callStaticMethod(stubCls, "asInterface", sSystemProvider);
                            XposedHelpers.callMethod(provider, "onWidgetFirstFrameDrawn");
                            XposedBridge.log("NaviHook: [Proxy] 🛡️ 稳了！精准反射调用 onWidgetFirstFrameDrawn 成功！");
                        } catch (Throwable t) {
                            // 防御性降级
                            try {
                                Parcel d = Parcel.obtain(); Parcel r = Parcel.obtain();
                                d.writeInterfaceToken(PROVIDER_DESCRIPTOR); 
                                sSystemProvider.transact(1, d, r, 0); 
                                d.recycle(); r.recycle();
                                XposedBridge.log("NaviHook: [Proxy] 🛡️ 降级 transact(1) 护航发出");
                            } catch (Throwable t2) {}
                        }
                    }, 500);
                }

                // 看门狗护航 2：补发 116 首帧广播
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        Intent intent = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                        intent.putExtra("KEY_TYPE", 10019);
                        intent.putExtra("EXTRA_CURRENT_STATE", 116);
                        intent.putExtra("FROM_HOOK", true);
                        amapContext.sendBroadcast(intent);
                        XposedBridge.log("NaviHook: [Proxy] 🚨 10019(116) 首帧广播已补发!");
                    } catch (Throwable t) {}
                }, 600);

                if (!isUnlockingEngine) {
                    isUnlockingEngine = true;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            Intent intent1 = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                            intent1.putExtra("KEY_TYPE", 10122);
                            intent1.putExtra("EXTRA_EXTERNAL_MAP_LEVEL", 17.0f);
                            intent1.putExtra("EXTRA_EXTERNAL_MAP_MODE", 3);
                            intent1.putExtra("EXTRA_EXTERNAL_ENGINE_ID", 1001); 
                            intent1.putExtra("FROM_HOOK", true);
                            amapContext.sendBroadcast(intent1);
                            XposedBridge.log("NaviHook: [Proxy] 🔓 引擎解锁码 10122 已发!");
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