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
    private static final String PROVIDER_DESCRIPTOR = "com.autosimilarwidget.view.IAutoWidgetStateProvider";

    private static Context sysContext;
    private static Handler sysHandler;
    
    // 动态状态锁：解决 7.5 一启动就霸占屏幕的 Bug
    private static boolean isNaviRunning = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        
        if (lpparam.packageName.equals("com.xsf.amaphelper")) {
            XposedHelpers.findAndHookMethod("com.xsf.amaphelper.MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // ==========================================
        // 战场 A：LBSNavi 端 (不能丢的阵地！负责修桥铺路和动态保活)
        // ==========================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedBridge.log("NaviHook: [Sys] 注入 LBSNavi，部署通道与保活机制");

            hookPackageManager(lpparam.classLoader);

            // 1. 动态保活机制：只在导航时骗系统高德在前台，平时不骗！
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) {
                    XposedBridge.hookAllMethods(cfg, "g", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (isNaviRunning) {
                                param.setResult(true); // 导航中，强制保活
                            }
                            // 否则老老实实执行原逻辑，防止一启动就拉开仪表盘
                        }
                    });
                }
            } catch (Throwable t) {}

            // 2. 监听仪表盘状态，动态更新 isNaviRunning
            try {
                Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", lpparam.classLoader);
                XposedBridge.hookAllMethods(mgrClass, "a", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                        String clsName = param.args[0].getClass().getSimpleName();
                        if ("MapStatusInfo".equals(clsName)) {
                            int status = XposedHelpers.getIntField(param.args[0], "status");
                            if (status == 16) {
                                isNaviRunning = true;
                                XposedBridge.log("NaviHook: [Sys] 监测到 16 (开始导航)，开启底层保活");
                            } else if (status == 17 || status == 9 || status == 12) {
                                isNaviRunning = false;
                                XposedBridge.log("NaviHook: [Sys] 监测到退出导航，释放底层保活");
                            }
                        }
                    }
                });
            } catch (Throwable t) {}

            // 3. 强制建立物理连接通道
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    sysHandler.postDelayed(() -> forceBindWidgetService(), 5000);
                }
            });
        }

        // ==========================================
        // 战场 B：高德地图端 (篡改 10117 投屏指令 + 解锁 331 引擎)
        // ==========================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            XposedBridge.log("NaviHook: [Amap] 注入高德，部署广播篡改器");

            hookPackageManager(lpparam.classLoader);
            hookSurfaceDimensions(lpparam.classLoader);

            // 🔥 核心篡改：把 9.1 错误的“关屏”广播篡改为“开屏”！
            try {
                Class<?> contextImplClass = XposedHelpers.findClass("android.app.ContextImpl", lpparam.classLoader);
                XposedBridge.hookAllMethods(contextImplClass, "sendBroadcast", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args[0] instanceof Intent) {
                            Intent intent = (Intent) param.args[0];
                            if ("AUTONAVI_STANDARD_BROADCAST_SEND".equals(intent.getAction())) {
                                int keyType = intent.getIntExtra("KEY_TYPE", -1);
                                if (keyType == 10117) {
                                    int mode = intent.getIntExtra("EXTSCREEN_MODE_INFO", -1);
                                    if (mode == 1 || mode == 2) {
                                        // 导航中：强制要求车机打开屏幕 (篡改为 1)
                                        intent.putExtra("EXTSCREEN_STATUS_INFO", 1);
                                        XposedBridge.log("NaviHook: [Amap] 🎯 已将 10117 广播篡改为: 开屏 (1)");
                                    } else if (mode == 0) {
                                        // 巡航：要求关闭屏幕 (篡改为 0)
                                        intent.putExtra("EXTSCREEN_STATUS_INFO", 0);
                                        XposedBridge.log("NaviHook: [Amap] 🎯 已将 10117 广播篡改为: 关屏 (0)");
                                    }
                                }
                            }
                        }
                    }
                });
            } catch (Throwable t) {}

            // 接管画板
            try {
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        IBinder real = (IBinder) param.getResult();
                        Context amapContext = (Context) param.thisObject;
                        XposedBridge.log("NaviHook: [Amap] 成功接管 WidgetService onBind");
                        param.setResult(new TrojanProxyBinder(real, amapContext));
                    }
                });
            } catch (Throwable t) {}
        }
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
                    XposedBridge.log("NaviHook: [Sys] 强行绑定 WidgetService 成功");
                }
            } else {
                sysHandler.postDelayed(MainHook::forceBindWidgetService, 3000);
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
                } else if (m.getName().equals("getMapSurfaceHeight")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return 720; } });
                }
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
                try { 
                    enforceInterfaceSafely(data, BINDER_DESCRIPTOR, start); 
                    sSystemProvider = data.readStrongBinder(); 
                } catch (Throwable t) {}
                data.setDataPosition(start);
            }
            if (code == 3) { if (reply != null) { reply.writeNoException(); reply.writeInt(1); } return true; }

            if (code == 1) {
                XposedBridge.log("NaviHook: [Proxy] 🎯 拿到 Code 1 画板！已移交 9.1 原生渲染。");
                data.setDataPosition(start);
                boolean realRes = false;
                try { if (realBinder != null) realRes = realBinder.transact(code, data, reply, flags); } catch (Throwable t) {}

                if (sSystemProvider != null && sSystemProvider.isBinderAlive()) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        Parcel d = Parcel.obtain(); Parcel r = Parcel.obtain();
                        try { 
                            d.writeInterfaceToken(PROVIDER_DESCRIPTOR); 
                            sSystemProvider.transact(1, d, r, 0); 
                        } catch (Throwable t) {} finally { d.recycle(); r.recycle(); }
                    }, 500);
                }

                // 强制解锁 331 大屏引擎，防黑屏
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
                            XposedBridge.log("NaviHook: [Proxy] 引擎解锁码 10122 已补发！");
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