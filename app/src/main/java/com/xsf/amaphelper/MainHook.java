package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
import java.lang.reflect.Method;
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
    private static final String BINDER_DESCRIPTOR = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String PROVIDER_DESCRIPTOR = "com.autosimilarwidget.view.IAutoWidgetStateProvider";
    private static final String ACTION_VERSION_CHECK = "com.xsf.amaphelper.VERSION_CHECK";

    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static Timer statusHeartbeat;
    private static boolean isSystemReady = false;
    private static boolean isSpoofingAllowed = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (lpparam.packageName.equals(PKG_MAP)) {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    ClassLoader cl = ctx.getClassLoader();
                    boolean isMap9 = false;
                    try {
                        cl.loadClass("com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService");
                        isMap9 = true;
                    } catch (ClassNotFoundException e) {}

                    final boolean finalIsMap9 = isMap9;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sendVersionBroadcast(ctx, finalIsMap9), 3000);
                    sendVersionBroadcast(ctx, finalIsMap9);

                    if (!isMap9) return;

                    hookSurfaceDimensions(cl);
                    try {
                        XposedHelpers.findAndHookMethod(TARGET_SERVICE, cl, "onBind", Intent.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                                IBinder realBinder = (IBinder) param.getResult();
                                if (realBinder != null) {
                                    XposedBridge.log("NaviHook: [Proxy] 🚀 拦截到原生 Binder，建立 MITM 代理！");
                                    param.setResult(new TrojanProxyBinder(realBinder, cl));
                                } else {
                                    XposedBridge.log("NaviHook: [Proxy] ⚠️ 原生 Binder 为空，降级为纯假实现！");
                                    param.setResult(new TrojanProxyBinder(null, cl));
                                }
                            }
                        });
                    } catch (Throwable t) {}
                }
            });
        }

        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    registerVersionReceiver();
                    sysHandler.postDelayed(() -> {
                        if (!isSystemReady && isSpoofingAllowed) initAs91();
                    }, 12000);
                }
            });
            hookPackageManager(lpparam.classLoader);
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}
        }
    }

    private static void initAs91() {
        if (sysContext == null || isSystemReady) return;
        isSystemReady = true;
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            performManualBind();
            startStatusHeartbeat();
        } catch (Throwable t) {}
    }

    private static void performManualBind() {
        if (sysContext == null) return;
        sysHandler.postDelayed(() -> {
            try {
                ClassLoader cl = sysContext.getClassLoader();
                Class<?> hClass = XposedHelpers.findClass("ecarx.naviservice.map.amap.h", cl);
                Object managerInstance = XposedHelpers.getStaticObjectField(hClass, "e");
                if (managerInstance == null) {
                    sysHandler.postDelayed(() -> performManualBind(), 3000);
                    return;
                }
                Object connectionObj = null;
                try { connectionObj = XposedHelpers.getObjectField(managerInstance, "f"); } catch (Throwable t) {}
                if (connectionObj == null) return;

                Intent intent = new Intent();
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                sysContext.bindService(intent, (ServiceConnection) connectionObj, Context.BIND_AUTO_CREATE);
                triggerActivationSequence();
            } catch (Throwable t) {}
        }, 2000);
    }

    private static void triggerActivationSequence() {
        if (dashboardMgr == null) return;
        // 修正为非阻塞激活，防止卡死系统导致被重置
        sysHandler.postDelayed(() -> sendMapStatus(1), 0);
        sysHandler.postDelayed(() -> sendMapStatus(3), 50);
        sysHandler.postDelayed(() -> {
            sendMapSwitch(3); 
            sendMapStatus(16); 
        }, 100);
    }

    // 🔥 V254 核心：MITM 中间人代理 Binder
    public static class TrojanProxyBinder extends Binder {
        private IBinder realBinder;
        private ClassLoader classLoader;
        private Handler uiHandler;
        private static IBinder sSystemProvider = null;

        public TrojanProxyBinder(IBinder real, ClassLoader cl) {
            this.realBinder = real;
            this.classLoader = cl;
            this.uiHandler = new Handler(Looper.getMainLooper());
        }

        private void enforceInterfaceSafely(Parcel data, String descriptor, int startPos) {
            try {
                data.enforceInterface(descriptor);
            } catch (Throwable first) {
                data.setDataPosition(startPos);
                try { data.readInt(); } catch (Throwable ignored) {}
                data.enforceInterface(descriptor);
            }
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1598968902) {
                if (reply != null) reply.writeString(BINDER_DESCRIPTOR);
                return true;
            }

            int startPos = data.dataPosition();

            // 1. 偷看 Code 4，截获系统 Provider
            if (code == 4) {
                try {
                    enforceInterfaceSafely(data, BINDER_DESCRIPTOR, startPos);
                    sSystemProvider = data.readStrongBinder();
                    if (sSystemProvider != null) {
                        XposedBridge.log("NaviHook: [Proxy] 🤝 Code4 Provider 截获成功.");
                    }
                } catch (Throwable t) {}
                data.setDataPosition(startPos); // 游标复位，留给原生去读
            }

            boolean realResult = false;
            Throwable realError = null;

            // 2. 丢给高德原生 Binder 处理（原生初始化图层、状态等）
            try {
                if (realBinder != null) {
                    realResult = realBinder.transact(code, data, reply, flags);
                } else {
                    realError = new RuntimeException("Fallback to pure fake");
                }
            } catch (Throwable t) {
                realError = t;
                XposedBridge.log("NaviHook: [Proxy] ⚠️ 原生 Binder 异常 (通常为白名单拦截): " + t.getMessage());
            }

            // 3. 兜底与强制回调补全
            if (code == 1) {
                if (realError != null || !realResult) {
                    XposedBridge.log("NaviHook: [Proxy] 🛡️ 原生注入失败，启用伪装注入兜底!");
                    data.setDataPosition(startPos);
                    Surface surface = null;
                    try {
                        enforceInterfaceSafely(data, BINDER_DESCRIPTOR, startPos);
                        if (data.readInt() != 0) {
                            surface = Surface.CREATOR.createFromParcel(data);
                        }
                    } catch (Throwable t) {}
                    
                    if (surface == null || !surface.isValid()) {
                        data.setDataPosition(startPos);
                        surface = tryExtendedBruteForce(data);
                    }
                    
                    if (surface != null && surface.isValid()) {
                        boolean ok = injectNativeEngine(surface);
                        XposedBridge.log("NaviHook: [Proxy] 兜底注入 " + (ok ? "成功" : "失败"));
                    }
                } else {
                    XposedBridge.log("NaviHook: [Proxy] ✅ 原生注入执行完毕.");
                }

                // 无论原生是否成功，强制帮高德发 FrameDrawn 告诉车机开屏 (放长线)
                uiHandler.postDelayed(this::notifySystemFrameDrawn, 200);
                uiHandler.postDelayed(this::notifySystemFrameDrawn, 800);

                if (realError != null && reply != null) {
                    reply.writeNoException();
                    return true; // 吞掉报错，不让车机崩溃
                }
            }

            if (code == 2 || code == 4 || code == 5) {
                if (realError != null && reply != null) {
                    reply.writeNoException();
                    return true;
                }
            }

            if (code == 3) {
                if (realError != null && reply != null) {
                    reply.writeNoException();
                    reply.writeInt(1); // 强制返回 true
                    return true;
                }
            }

            if (realError != null) return true;
            return realResult;
        }

        private void notifySystemFrameDrawn() {
            if (sSystemProvider == null || !sSystemProvider.isBinderAlive()) return;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(PROVIDER_DESCRIPTOR);
                boolean ok = sSystemProvider.transact(1, data, reply, 0);
                try { reply.readException(); } catch (Throwable ignored) {}
                XposedBridge.log("NaviHook: [Proxy] 🔔 FrameDrawn 通知完毕: " + ok);
            } catch (Throwable t) {
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        private Surface tryExtendedBruteForce(Parcel data) {
            int originalPos = data.dataPosition();
            for (int offset = 0; offset <= 128; offset += 4) {
                try {
                    data.setDataPosition(offset);
                    Surface s = Surface.CREATOR.createFromParcel(data);
                    if (s != null && s.isValid()) return s;
                } catch (Throwable e) {}
            }
            data.setDataPosition(originalPos);
            return null;
        }

        private boolean injectNativeEngine(Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                Method mCreate = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                mCreate.invoke(null, 1, 2, surface);
                try {
                    Method mChange = XposedHelpers.findMethodExact(cls, "nativesurfaceChanged", int.class, Surface.class, int.class, int.class, int.class);
                    mChange.invoke(null, 1, surface, 0, 1920, 720);
                } catch (Throwable t) {
                    Method mRedraw = XposedHelpers.findMethodExact(cls, "nativeSurfaceRedrawNeeded", int.class, int.class, Surface.class);
                    mRedraw.invoke(null, 1, 2, surface);
                }
                return true; 
            } catch (Throwable t) { return false; }
        }
    }

    private static void hookPackageManager(ClassLoader cl) {
        XC_MethodHook spoofHook = new XC_MethodHook() {
            @SuppressWarnings("unchecked")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isSpoofingAllowed) return;
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null && TARGET_SERVICE.equals(intent.getComponent().getClassName())) {
                    Object result = param.getResult();
                    boolean isEmpty = (result == null) || (result instanceof java.util.List && ((java.util.List) result).isEmpty());
                    if (isEmpty) {
                        android.content.pm.ResolveInfo info = new android.content.pm.ResolveInfo();
                        info.serviceInfo = new android.content.pm.ServiceInfo();
                        info.serviceInfo.packageName = PKG_MAP;
                        info.serviceInfo.name = TARGET_SERVICE;
                        info.serviceInfo.exported = true;
                        info.serviceInfo.applicationInfo = new android.content.pm.ApplicationInfo();
                        info.serviceInfo.applicationInfo.packageName = PKG_MAP;
                        if (result instanceof java.util.List) {
                            java.util.List list = new java.util.ArrayList();
                            list.add(info);
                            param.setResult(list);
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

    private static void hookSurfaceDimensions(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", cl);
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals("getMapSurfaceWidth")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam param) { return 1920; }
                    });
                }
                if (m.getName().equals("getMapSurfaceHeight")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam param) { return 720; }
                    });
                }
                if (m.getName().equals("getMapSurfaceDpi")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        @Override protected Object replaceHookedMethod(MethodHookParam param) { return 240; }
                    });
                }
            }
        } catch (Throwable t) {}
    }

    private static void sendVersionBroadcast(Context ctx, boolean isMap9) {
        try {
            Intent intent = new Intent(ACTION_VERSION_CHECK);
            intent.setPackage(PKG_SERVICE);
            intent.putExtra("is_75", !isMap9);
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {}
    }

    private static void registerVersionReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_VERSION_CHECK);
        sysContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean is75 = intent.getBooleanExtra("is_75", false);
                isSpoofingAllowed = !is75;
                if (!is75) initAs91();
            }
        }, filter);
    }

    private static void sendMapStatus(int status) {
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
            XposedHelpers.setIntField(st, "status", status);
            XposedHelpers.callMethod(dashboardMgr, "a", st);
        } catch (Throwable t) {}
    }

    private static void sendMapSwitch(int state) {
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", state);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);
        } catch (Throwable t) {}
    }

    private static void startStatusHeartbeat() {
        if (statusHeartbeat != null) statusHeartbeat.cancel();
        statusHeartbeat = new Timer();
        statusHeartbeat.schedule(new TimerTask() {
            @Override
            public void run() { sendMapStatus(16); }
        }, 1000, 3000); 
    }
}