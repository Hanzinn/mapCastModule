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
                                param.setResult(new TrojanBinder(cl));
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
                try {
                    connectionObj = XposedHelpers.getObjectField(managerInstance, "f");
                } catch (Throwable t) {}

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
        sysHandler.post(() -> {
            try {
                sendMapStatus(1);
                Thread.sleep(50);
                sendMapStatus(3);
                Thread.sleep(50);
                sendMapSwitch(3); 
                sendMapStatus(16); 
            } catch (Throwable t) {}
        });
    }

    public static class TrojanBinder extends Binder {
        private ClassLoader classLoader;
        private boolean isSurfaceActive = false;
        private Handler uiHandler;
        private IBinder systemProvider;

        public TrojanBinder(ClassLoader cl) {
            this.classLoader = cl;
            this.uiHandler = new Handler(Looper.getMainLooper());
        }

        private void enforceInterfaceSafely(Parcel data, String descriptor) {
            try {
                data.enforceInterface(descriptor);
            } catch (Throwable first) {
                data.setDataPosition(0);
                try { data.readInt(); } catch (Throwable ignored) {}
                data.enforceInterface(descriptor);
            }
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                if (code == 1598968902) {
                    if (reply != null) reply.writeString(BINDER_DESCRIPTOR);
                    return true;
                }

                if (code == 4) {
                    data.setDataPosition(0);
                    try {
                        enforceInterfaceSafely(data, BINDER_DESCRIPTOR);
                        systemProvider = data.readStrongBinder();

                        if (systemProvider != null) {
                            boolean isAlive = systemProvider.isBinderAlive();
                            Parcel d = Parcel.obtain();
                            Parcel r = Parcel.obtain();
                            try {
                                systemProvider.transact(1598968902, d, r, 0);
                                XposedBridge.log("NaviHook: [Binder] 🤝 Code4 OK provider desc=" + r.readString() + " alive=" + isAlive);
                            } finally {
                                d.recycle();
                                r.recycle();
                            }
                        } else {
                            XposedBridge.log("NaviHook: [Binder] ⚠️ Code4 provider is null");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("NaviHook: [Binder] ❌ Code4 parse fail: " + t);
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (code == 1) {
                    if (isSurfaceActive) {
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    
                    data.setDataPosition(0); 
                    Surface surface = null;
                    try {
                        enforceInterfaceSafely(data, BINDER_DESCRIPTOR);
                        int hasSurface = data.readInt();
                        if (hasSurface != 0) {
                            surface = Surface.CREATOR.createFromParcel(data);
                        }
                        try { data.readInt(); } catch (Throwable ignored) {}
                    } catch (Throwable t) {
                        XposedBridge.log("NaviHook: [Binder] ⚠️ Code1 严格解析失败，准备盲扫: " + t);
                    }

                    // 🔥 fallback 盲扫兜底
                    if (surface == null || !surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ⚠️ 开始盲扫 Surface...");
                        data.setDataPosition(0);
                        surface = tryExtendedBruteForce(data);
                    }
                    
                    if (surface != null && surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ✅ Surface valid, start inject");
                        final Surface s = surface;
                        uiHandler.post(() -> {
                            if (injectNativeEngine(s)) {
                                isSurfaceActive = true;
                                XposedBridge.log("NaviHook: [Map] ✅ inject ok");
                                notifySystemFrameDrawn();
                                uiHandler.postDelayed(this::notifySystemFrameDrawn, 50);
                            } else {
                                XposedBridge.log("NaviHook: [Map] ❌ inject fail");
                            }
                        });
                    } else {
                        XposedBridge.log("NaviHook: [Binder] ❌ Code1 未找到有效 Surface");
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (code == 2) {
                    isSurfaceActive = false;
                    systemProvider = null; 
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (code == 3) {
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(1);
                    }
                    return true;
                }
                
                if (code == 5) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }

            } catch (Throwable t) {}
            return super.onTransact(code, data, reply, flags);
        }

        private void notifySystemFrameDrawn() {
            if (systemProvider == null || !systemProvider.isBinderAlive()) return;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(PROVIDER_DESCRIPTOR);
                boolean ok = systemProvider.transact(1, data, reply, 0);
                try { reply.readException(); } catch (Throwable ignored) {}
                XposedBridge.log("NaviHook: [Binder] ✅ FrameDrawn transact ok=" + ok);
            } catch (Throwable t) {
                // 🔥 非常关键的探针日志
                XposedBridge.log("NaviHook: [Binder] ❌ FrameDrawn fail: " + t);
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
            } catch (Throwable t) { 
                isSurfaceActive = false; 
                return false; 
            }
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