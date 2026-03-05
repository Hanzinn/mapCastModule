package com.xsf.amaphelper;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
    
    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    
    // 防止循环调用的锁
    private static boolean isOurInjecting = false;
    private static boolean isNavigating = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        
        // ==========================================
        // 主线战场 1：LBSNavi 车机端 (状态机安全缓冲流)
        // ==========================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            // 绕过系统休眠检测，保活高德
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}

            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    sysHandler.postDelayed(() -> initLBSNavi(sysContext.getClassLoader()), 8000);
                }
            });
        }

        // ==========================================
        // 主线战场 2：高德地图端 (接管画布 + 解决黑屏)
        // ==========================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            hookPackageManager(lpparam.classLoader);
            hookSurfaceDimensions(lpparam.classLoader);

            try {
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        IBinder real = (IBinder) param.getResult();
                        Context amapContext = (Context) param.thisObject;
                        XposedBridge.log("NaviHook: [Amap] 成功接管 onBind");
                        param.setResult(new TrojanProxyBinder(real, amapContext));
                    }
                });
            } catch (Throwable t) {}
        }
    }

    private static void initLBSNavi(ClassLoader cl) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");

            // 🔥 核心：拦截高德原生状态，注入安全时序 (解决 V254/V268 的死因)
            XposedBridge.hookAllMethods(mgrClass, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (isOurInjecting || param.args == null || param.args.length == 0 || param.args[0] == null) return;
                    
                    Object obj = param.args[0];
                    String cls = obj.getClass().getSimpleName();

                    if ("MapStatusInfo".equals(cls)) {
                        int status = XposedHelpers.getIntField(obj, "status");
                        XposedBridge.log("NaviSpy: [Sys] 拦截到系统欲下发状态 -> " + status);

                        // 高德发出 16(导航开始)，但还没真正开屏
                        if (status == 16 && !isNavigating) {
                            param.setResult(null); // 拦截！扣留原生的 16
                            isNavigating = true;
                            XposedBridge.log("NaviHook: [Sys] 扣留 16 状态，启动 V254 完美安全开屏时序...");
                            triggerSafeActivationSequence();
                        } 
                        // 退出导航
                        else if (status == 17) {
                            isNavigating = false;
                        }
                    }
                }
            });

            // 强制修桥铺路，保证高德 9.1 和底层的物理连接存在
            forceBindWidgetService();
        } catch (Throwable t) {}
    }

    private static void forceBindWidgetService() {
        try {
            Class<?> hClass = XposedHelpers.findClass("ecarx.naviservice.map.amap.h", sysContext.getClassLoader());
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

    // 🔥 解决 V254 闪退的终极连招：拉开动画安全期
    private static void triggerSafeActivationSequence() {
        isOurInjecting = true;
        try {
            // 1. 发送 Switch(3)，让底层 AdaptAPI 开始拉开仪表盘转速表
            XposedBridge.log("NaviHook: [Sys] 步骤 1：发送 MapSwitchingInfo(3) 申请物理开屏");
            Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", sysContext.getClassLoader()), 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", 3);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);

            // 2. 核心延时：必须给车机系统 1.5 秒做动画，避开 AppWatcherService 看门狗的绞杀！
            sysHandler.postDelayed(() -> {
                try {
                    XposedBridge.log("NaviHook: [Sys] 步骤 2：动画安全期结束，释放 MapStatusInfo(16)");
                    Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", sysContext.getClassLoader()), 0);
                    XposedHelpers.setIntField(st, "status", 16);
                    XposedHelpers.callMethod(dashboardMgr, "a", st);
                } catch (Throwable t) {}
                isOurInjecting = false;
            }, 1500);

        } catch (Throwable t) {
            isOurInjecting = false;
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
                        } else { 
                            param.setResult(info); 
                        }
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
                if (m.getName().equals("getMapSurfaceWidth")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return 1920; } });
                }
                if (m.getName().equals("getMapSurfaceHeight")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return 720; } });
                }
            }
        } catch (Throwable t) {}
    }

    public static class TrojanProxyBinder extends Binder {
        private IBinder realBinder;
        private Context amapContext;
        private static boolean isBroadcasting = false;

        public TrojanProxyBinder(IBinder real, Context context) {
            this.realBinder = real; 
            this.amapContext = context; 
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
                try { enforceInterfaceSafely(data, BINDER_DESCRIPTOR, start); } catch (Throwable t) {}
                data.setDataPosition(start);
            }
            if (code == 3) { if (reply != null) { reply.writeNoException(); reply.writeInt(1); } return true; }

            // 🔥 拿到画板，不仅移交给 9.1，而且补发 10122 解决透明黑屏
            if (code == 1) {
                XposedBridge.log("NaviHook: [Proxy] 🎯 收到 Code 1 画板！移交原生渲染");
                data.setDataPosition(start);
                boolean realRes = false;
                try { if (realBinder != null) realRes = realBinder.transact(code, data, reply, flags); } catch (Throwable t) {}

                if (!isBroadcasting) {
                    isBroadcasting = true;
                    // 补发引擎解锁广播，强制使用 331 渲染配置
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            Intent intent = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                            intent.putExtra("KEY_TYPE", 10122);
                            intent.putExtra("EXTRA_EXTERNAL_MAP_LEVEL", 17.0f);
                            intent.putExtra("EXTRA_EXTERNAL_MAP_MODE", 3);
                            intent.putExtra("EXTRA_EXTERNAL_ENGINE_ID", 1001); 
                            amapContext.sendBroadcast(intent);
                            XposedBridge.log("NaviHook: [Proxy] 10122 黑屏解锁广播已发送");
                            isBroadcasting = false;
                        } catch (Throwable t) { isBroadcasting = false; }
                    }, 800);
                }
                return realRes; 
            }
            
            if (code == 2) {
                XposedBridge.log("NaviHook: [Proxy] 🛑 收到 Code 2 屏幕被收回");
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