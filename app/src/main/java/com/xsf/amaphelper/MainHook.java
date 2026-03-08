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
    
    private static boolean isNaviRunning = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.xsf.amaphelper")) {
            XposedHelpers.findAndHookMethod("com.xsf.amaphelper.MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // ==========================================
        // 战场 A：LBSNavi 端 (EventBus 内部总线注入)
        // ==========================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedBridge.log("NaviHook: [Sys] V296 EventBus 总线注入版已就绪");
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
                    sysHandler.postDelayed(() -> initEventBusRadar(), 4000);
                }
            });
        }

        // ==========================================
        // 战场 B：高德端 (首帧护航与画板接管)
        // ==========================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            XposedBridge.log("NaviHook: [Amap] V296 注入高德");
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

    private static void initEventBusRadar() {
        try {
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
                        // 无视原厂 Bug，16、200、8 都算开始！
                        if (state == 16 || state == 200 || state == 8) shouldStart = true;
                        else if (state == 17 || state == 9 || state == 12) shouldStop = true;
                    } else if (keyType == 10001) {
                        shouldStart = true;
                    }

                    if (shouldStart && !isNaviRunning) {
                        isNaviRunning = true;
                        XposedBridge.log("NaviHook: [Sys] 🚨 侦测到导航！向内部总线发射 116 物理开屏事件...");
                        triggerEventBus(116); 
                    } else if (shouldStop && isNaviRunning) {
                        isNaviRunning = false;
                        XposedBridge.log("NaviHook: [Sys] 🚨 退出导航，向内部总线发射 117 关屏事件...");
                        triggerEventBus(117); 
                    }
                }
            }, filter);

            forceBindWidgetService();
            XposedBridge.log("NaviHook: [Sys] EventBus 雷达就绪！");
        } catch (Throwable t) {}
    }

    // 🔥 核心突破：直接调用 LBSNavi 内部 EventBus 发射事件
    private static void triggerEventBus(int eventCode) {
        try {
            // 获取 RxJava EventBus 单例：e.a()
            Class<?> eClass = XposedHelpers.findClass("ecarx.naviservice.d.e", sysClassLoader);
            Object eInstance = XposedHelpers.callStaticMethod(eClass, "a");

            // 创建 bz 事件对象
            Class<?> bzClass = XposedHelpers.findClass("ecarx.naviservice.map.bz", sysClassLoader);
            Object bzEvent = null;
            
            // 兼容 bz 类的不同构造函数 (带参或不带参)
            try {
                bzEvent = XposedHelpers.newInstance(bzClass, eventCode);
            } catch (Throwable t1) {
                try {
                    bzEvent = XposedHelpers.newInstance(bzClass, eventCode, null);
                } catch (Throwable t2) {
                    bzEvent = XposedHelpers.newInstance(bzClass, eventCode, "");
                }
            }

            // 发射事件：e.a().a(new bz(eventCode))
            if (eInstance != null && bzEvent != null) {
                XposedHelpers.callMethod(eInstance, "a", bzEvent);
                XposedBridge.log("NaviHook: [Sys] 💥 内部总线事件 [" + eventCode + "] 发射成功！");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] 内部总线事件发射失败: " + t);
        }
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

                // 看门狗护航 1：Binder 回调
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

                // 看门狗护航 2：模拟 7.5 特供首帧广播
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        Intent intent = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                        intent.putExtra("KEY_TYPE", 10019);
                        intent.putExtra("EXTRA_CURRENT_STATE", 116);
                        amapContext.sendBroadcast(intent);
                        XposedBridge.log("NaviHook: [Proxy] 🚨 10019(116) 虚拟首帧广播已发!");
                    } catch (Throwable t) {}
                }, 600);

                // 解锁引擎防黑屏
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