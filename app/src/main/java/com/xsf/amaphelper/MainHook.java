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
                protected void afterHookedMethod(MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    ClassLoader cl = ctx.getClassLoader();
                    boolean isMap9 = false;
                    try { cl.loadClass(TARGET_SERVICE); isMap9 = true; } catch (Throwable e) {}
                    final boolean finalIsMap9 = isMap9;
                    sendVersionBroadcast(ctx, finalIsMap9);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sendVersionBroadcast(ctx, finalIsMap9), 3000);
                    if (!isMap9) return;
                    hookSurfaceDimensions(cl);
                    try {
                        XposedHelpers.findAndHookMethod(TARGET_SERVICE, cl, "onBind", Intent.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                IBinder real = (IBinder) param.getResult();
                                XposedBridge.log("NaviHook: [Map] onBind hooked, real=" + real);
                                param.setResult(new TrojanProxyBinder(real, cl));
                            }
                        });
                    } catch (Throwable t) { XposedBridge.log("NaviHook: [Map] hook onBind failed: " + t); }
                }
            });
        }
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    registerVersionReceiver();
                    sysHandler.postDelayed(() -> { if (!isSystemReady && isSpoofingAllowed) initAs91(); }, 12000);
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
        XposedBridge.log("NaviHook: [Sys] initAs91 start");
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            XposedBridge.log("NaviHook: [Sys] dashboardMgr=" + dashboardMgr);
            
            XposedBridge.hookAllMethods(mgrClass, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length == 0) return;
                    Object obj = param.args[0];
                    if (obj == null) return;
                    String cls = obj.getClass().getSimpleName();
                    if ("MapStatusInfo".equals(cls)) {
                        int status = XposedHelpers.getIntField(obj, "status");
                        XposedBridge.log("NaviSpy: [Sys] MapStatusInfo -> " + status);
                    } else if ("MapSwitchingInfo".equals(cls)) {
                        int state = XposedHelpers.getIntField(obj, "mSwitchState");
                        XposedBridge.log("NaviSpy: [Sys] MapSwitchingInfo -> " + state);
                    }
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) XposedBridge.log("NaviSpy: [Sys] a() Error: " + param.getThrowable());
                }
            });

            performManualBind();
            startStatusHeartbeat();
        } catch (Throwable t) { XposedBridge.log("NaviHook: [Sys] initAs91 error: " + t); }
    }

    private static void performManualBind() {
        if (sysContext == null) return;
        sysHandler.postDelayed(() -> {
            try {
                ClassLoader cl = sysContext.getClassLoader();
                Class<?> hClass = XposedHelpers.findClass("ecarx.naviservice.map.amap.h", cl);
                Object inst = XposedHelpers.getStaticObjectField(hClass, "e");
                if (inst == null) { 
                    XposedBridge.log("NaviHook: [Sys] managerInstance is null, retry...");
                    sysHandler.postDelayed(() -> performManualBind(), 3000); 
                    return; 
                }
                Object conn = XposedHelpers.getObjectField(inst, "f");
                if (conn == null) {
                    XposedBridge.log("NaviHook: [Sys] connectionObj is null");
                    return;
                }
                XposedBridge.log("NaviHook: [Sys] bindService start");
                Intent intent = new Intent().setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                sysContext.bindService(intent, (ServiceConnection) conn, Context.BIND_AUTO_CREATE);
                triggerActivationSequence();
            } catch (Throwable t) { XposedBridge.log("NaviHook: [Sys] performManualBind error: " + t); }
        }, 2000);
    }

    private static void triggerActivationSequence() {
        if (dashboardMgr == null) return;
        XposedBridge.log("NaviHook: [Sys] triggerActivationSequence 1->3->16");
        sysHandler.postDelayed(() -> sendMapStatus(1), 0);
        sysHandler.postDelayed(() -> sendMapStatus(3), 100);
        sysHandler.postDelayed(() -> { sendMapSwitch(3); sendMapStatus(16); }, 200);
    }

    public static class TrojanProxyBinder extends Binder {
        private IBinder realBinder;
        private ClassLoader classLoader;
        private Handler uiHandler;
        private static IBinder sSystemProvider = null;

        public TrojanProxyBinder(IBinder real, ClassLoader cl) {
            this.realBinder = real; this.classLoader = cl; this.uiHandler = new Handler(Looper.getMainLooper());
        }

        private void enforceInterfaceSafely(Parcel data, String desc, int pos) {
            try { data.enforceInterface(desc); } catch (Throwable t) { 
                data.setDataPosition(pos); 
                try { data.readInt(); } catch (Throwable ignored) {} 
                data.enforceInterface(desc); 
            }
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1598968902) { 
                if (reply != null) reply.writeString(BINDER_DESCRIPTOR); 
                return true; 
            }
            
            int start = data.dataPosition();
            
            if (code == 4) {
                try { 
                    enforceInterfaceSafely(data, BINDER_DESCRIPTOR, start); 
                    sSystemProvider = data.readStrongBinder(); 
                    if (sSystemProvider != null) XposedBridge.log("NaviHook: [Proxy] Code4 Provider OK");
                } catch (Throwable t) {}
                data.setDataPosition(start);
            }
            
            if (code == 3) { 
                XposedBridge.log("NaviHook: [Proxy] Code3 -> return 1");
                if (reply != null) { reply.writeNoException(); reply.writeInt(1); } 
                return true; 
            }

            boolean realRes = false;
            Throwable realErr = null;
            try { 
                if (realBinder != null) realRes = realBinder.transact(code, data, reply, flags); 
                else realErr = new RuntimeException("realBinder is null");
            } catch (Throwable t) { realErr = t; }

            if (code == 1) {
                XposedBridge.log("NaviHook: [Proxy] Code1 start, realRes=" + realRes + ", realErr=" + realErr);
                data.setDataPosition(start); 
                Surface s = null; 
                int dId = -99;
                try { 
                    enforceInterfaceSafely(data, BINDER_DESCRIPTOR, start); 
                    if (data.readInt() != 0) s = Surface.CREATOR.createFromParcel(data); 
                    dId = data.readInt(); 
                } catch (Throwable t) { XposedBridge.log("NaviHook: [Proxy] Code1 parse error: " + t); }
                
                boolean ok = (s != null && s.isValid());
                XposedBridge.log("NaviHook: [Proxy] Code1 parsed valid=" + ok + ", dId=" + dId);
                
                if (!ok) { 
                    data.setDataPosition(start); 
                    s = tryExtendedBruteForce(data); 
                    if (s != null && s.isValid()) XposedBridge.log("NaviHook: [Proxy] Code1 brute force found surface");
                }
                
                if (s != null && s.isValid()) {
                    final Surface fs = s; 
                    final int fdId = (dId <= 0) ? 1 : dId;
                    uiHandler.post(() -> {
                        boolean inj = injectNativeEngine(fs, fdId);
                        XposedBridge.log("NaviHook: [Proxy] inject(ID=" + fdId + ")=" + inj);
                        if (inj) {
                            uiHandler.postDelayed(this::notifySystemFrameDrawn, 200);
                            uiHandler.postDelayed(this::notifySystemFrameDrawn, 800);
                        }
                    });
                } else {
                    XposedBridge.log("NaviHook: [Proxy] Code1 no valid surface");
                }

                if (reply != null && !reply.hasFileDescriptors()) reply.writeNoException();
                return true;
            }
            
            if (code == 2) {
                XposedBridge.log("NaviHook: [Proxy] Code2 removedSurface");
                if (realErr != null && reply != null) { reply.writeNoException(); return true; }
            }
            
            if (code == 5 && realErr != null && reply != null) { reply.writeNoException(); return true; }
            
            if (realErr != null) {
                XposedBridge.log("NaviHook: [Proxy] realErr fallback, code=" + code);
                return true;
            }
            return realRes;
        }

        private void notifySystemFrameDrawn() {
            if (sSystemProvider == null || !sSystemProvider.isBinderAlive()) return;
            Parcel d = Parcel.obtain(); 
            Parcel r = Parcel.obtain();
            try { 
                d.writeInterfaceToken(PROVIDER_DESCRIPTOR); 
                boolean ok = sSystemProvider.transact(1, d, r, 0);
                XposedBridge.log("NaviHook: [Proxy] FrameDrawn ok=" + ok);
            } catch (Throwable t) { XposedBridge.log("NaviHook: [Proxy] FrameDrawn error: " + t); } 
            finally { d.recycle(); r.recycle(); }
        }

        private Surface tryExtendedBruteForce(Parcel data) {
            int p = data.dataPosition();
            for (int o = 0; o <= 128; o += 4) { 
                try { 
                    data.setDataPosition(o); 
                    Surface s = Surface.CREATOR.createFromParcel(data); 
                    if (s != null && s.isValid()) {
                        XposedBridge.log("NaviHook: [Proxy] Brute force hit at offset " + o);
                        return s;
                    }
                } catch (Throwable e) {} 
            }
            data.setDataPosition(p); 
            return null;
        }

        private boolean injectNativeEngine(Surface s, int dId) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                Method mCreate = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                mCreate.invoke(null, dId, 2, s);
                try {
                    Method mChange = XposedHelpers.findMethodExact(cls, "nativesurfaceChanged", int.class, Surface.class, int.class, int.class, int.class);
                    mChange.invoke(null, dId, s, 0, 1920, 720);
                } catch (Throwable t) {
                    Method mRedraw = XposedHelpers.findMethodExact(cls, "nativeSurfaceRedrawNeeded", int.class, int.class, Surface.class);
                    mRedraw.invoke(null, dId, 2, s);
                }
                return true;
            } catch (Throwable t) { 
                XposedBridge.log("NaviHook: [Proxy] inject error: " + t);
                return false; 
            }
        }
    }

    private static void hookPackageManager(ClassLoader cl) {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!isSpoofingAllowed) return;
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null && TARGET_SERVICE.equals(intent.getComponent().getClassName())) {
                    Object res = param.getResult();
                    if (res == null || (res instanceof java.util.List && ((java.util.List) res).isEmpty())) {
                        android.content.pm.ResolveInfo info = new android.content.pm.ResolveInfo();
                        info.serviceInfo = new android.content.pm.ServiceInfo();
                        info.serviceInfo.packageName = PKG_MAP; info.serviceInfo.name = TARGET_SERVICE; info.serviceInfo.exported = true;
                        info.serviceInfo.applicationInfo = new android.content.pm.ApplicationInfo(); info.serviceInfo.applicationInfo.packageName = PKG_MAP;
                        if (res instanceof java.util.List) { java.util.List l = new java.util.ArrayList(); l.add(info); param.setResult(l); } else { param.setResult(info); }
                    }
                }
            }
        };
        try { XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", cl, "queryIntentServices", Intent.class, int.class, h); XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", cl, "resolveService", Intent.class, int.class, h); } catch (Throwable t) {}
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
                if (m.getName().equals("getMapSurfaceDpi")) {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return 240; } });
                }
            }
        } catch (Throwable t) {}
    }

    private static void sendVersionBroadcast(Context ctx, boolean isMap9) {
        try { ctx.sendBroadcast(new Intent(ACTION_VERSION_CHECK).setPackage(PKG_SERVICE).putExtra("is_75", !isMap9)); } catch (Throwable t) {}
    }

    private static void registerVersionReceiver() {
        sysContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean is75 = intent.getBooleanExtra("is_75", false);
                isSpoofingAllowed = !is75; if (!is75) initAs91();
            }
        }, new IntentFilter(ACTION_VERSION_CHECK));
    }

    private static void sendMapStatus(int s) {
        try {
            Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", sysContext.getClassLoader()), 0);
            XposedHelpers.setIntField(st, "status", s); XposedHelpers.callMethod(dashboardMgr, "a", st);
            XposedBridge.log("NaviHook: [Sys] sendMapStatus " + s);
        } catch (Throwable t) { XposedBridge.log("NaviHook: [Sys] sendMapStatus error: " + t); }
    }

    private static void sendMapSwitch(int s) {
        try {
            Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", sysContext.getClassLoader()), 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", s); XposedHelpers.callMethod(dashboardMgr, "a", sw);
            XposedBridge.log("NaviHook: [Sys] sendMapSwitch " + s);
        } catch (Throwable t) { XposedBridge.log("NaviHook: [Sys] sendMapSwitch error: " + t); }
    }

    private static void startStatusHeartbeat() {
        sysHandler.postDelayed(() -> {
            if (statusHeartbeat != null) statusHeartbeat.cancel();
            statusHeartbeat = new Timer();
            statusHeartbeat.schedule(new TimerTask() { @Override public void run() { sendMapStatus(16); } }, 0, 3000);
            XposedBridge.log("NaviSpy: [Sys] Heartbeat started");
        }, 10000);
    }
}