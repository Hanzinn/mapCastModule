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
                                param.setResult(new TrojanProxyBinder(real, cl));
                            }
                        });
                    } catch (Throwable t) {}
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
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");

            XposedBridge.hookAllMethods(mgrClass, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length == 0) return;
                    Object obj = param.args[0];
                    if (obj == null) return;
                    String cls = obj.getClass().getSimpleName();
                    if ("MapStatusInfo".equals(cls)) {
                        XposedBridge.log("NaviSpy: [Sys] MapStatusInfo -> " + XposedHelpers.getIntField(obj, "status"));
                    } else if ("MapSwitchingInfo".equals(cls)) {
                        XposedBridge.log("NaviSpy: [Sys] MapSwitchingInfo -> " + XposedHelpers.getIntField(obj, "mSwitchState"));
                    }
                }
            });

            performManualBind();
            startSpoofingHeartbeat();
        } catch (Throwable t) {}
    }

    private static void performManualBind() {
        if (sysContext == null) return;
        sysHandler.postDelayed(() -> {
            try {
                ClassLoader cl = sysContext.getClassLoader();
                Class<?> hClass = XposedHelpers.findClass("ecarx.naviservice.map.amap.h", cl);
                Object inst = XposedHelpers.getStaticObjectField(hClass, "e");
                if (inst == null) { sysHandler.postDelayed(() -> performManualBind(), 3000); return; }
                Object conn = XposedHelpers.getObjectField(inst, "f");
                if (conn == null) return;
                Intent intent = new Intent().setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                sysContext.bindService(intent, (ServiceConnection) conn, Context.BIND_AUTO_CREATE);
            } catch (Throwable t) {}
        }, 2000);
    }

    // 欺骗 LBSNavi，引诱它下发 Code 1 屏幕
    private static void startSpoofingHeartbeat() {
        sysHandler.postDelayed(() -> {
            if (statusHeartbeat != null) statusHeartbeat.cancel();
            statusHeartbeat = new Timer();
            statusHeartbeat.schedule(new TimerTask() { 
                @Override 
                public void run() { 
                    try {
                        Intent intentNavi = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                        intentNavi.putExtra("KEY_TYPE", 10019);
                        intentNavi.putExtra("EXTRA_STATE", 40); 
                        intentNavi.putExtra("EXTRA_STATUS_DETAILS", -1);
                        sysContext.sendBroadcast(intentNavi);

                        Intent intentEngine = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                        intentEngine.putExtra("KEY_TYPE", 10122);
                        intentEngine.putExtra("EXTRA_EXTERNAL_MAP_LEVEL", 17.0f);
                        intentEngine.putExtra("EXTRA_EXTERNAL_MAP_MODE", 3);
                        intentEngine.putExtra("EXTRA_EXTERNAL_ENGINE_ID", 1001); 
                        sysContext.sendBroadcast(intentEngine);
                        
                        Intent intentSync = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                        intentSync.putExtra("KEY_TYPE", 13034);
                        intentSync.putExtra("EXTRA_EXTERNAL_MAP_LEVEL", 17.0f);
                        sysContext.sendBroadcast(intentSync);
                    } catch (Throwable t) {}
                } 
            }, 0, 2500);
        }, 5000);
    }

    private static void sendMapStatus(int s) {
        if (dashboardMgr == null) return;
        try {
            Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", sysContext.getClassLoader()), 0);
            XposedHelpers.setIntField(st, "status", s);
            XposedHelpers.callMethod(dashboardMgr, "a", st);
        } catch (Throwable t) {}
    }

    private static void sendMapSwitch(int s) {
        if (dashboardMgr == null) return;
        try {
            Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", sysContext.getClassLoader()), 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", s);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);
        } catch (Throwable t) {}
    }

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

            // 🔥 核心修正：绝对不发 17 干扰！安安静静把 Code 2 吃掉
            if (code == 2) {
                XposedBridge.log("NaviHook: [Proxy] 收到 Code 2 (RemovedSurface) -> 拦截吃掉，不干扰仪表盘");
                if (reply != null) reply.writeNoException();
                return true; 
            }

            // 🔥 Code 1: 拿到画板，直接画图并拉开幕布！
            if (code == 1) {
                XposedBridge.log("NaviHook: [Proxy] 🎉 苍天有眼！收到 Code 1 (AddSurface)！");
                data.setDataPosition(start); 
                Surface s = null; 
                int dId = -99;
                
                try { enforceInterfaceSafely(data, BINDER_DESCRIPTOR, start); if (data.readInt() != 0) s = Surface.CREATOR.createFromParcel(data); dId = data.readInt(); } catch (Throwable t) {}
                if (s == null || !s.isValid()) { data.setDataPosition(start); s = tryExtendedBruteForce(data); }
                
                if (s != null && s.isValid()) {
                    final int fdId = (dId <= 0) ? 1 : dId;
                    boolean inj = injectNativeEngine(s, fdId);
                    XposedBridge.log("NaviHook: [Proxy] C++ 引擎强制注入 (ID=" + fdId + ") = " + inj);
                    
                    // 🚀 核心动作：强制仪表盘拉开转速表，露出地图！
                    sysHandler.post(() -> {
                        sendMapSwitch(3);  // 强切导航 UI
                        sendMapStatus(16); // 确认状态
                    });

                    uiHandler.postDelayed(this::notifySystemFrameDrawn, 500);
                }

                if (reply != null && !reply.hasFileDescriptors()) reply.writeNoException();
                return true; 
            }
            
            boolean realRes = false;
            try { if (realBinder != null) { data.setDataPosition(start); realRes = realBinder.transact(code, data, reply, flags); } } catch (Throwable t) { if (reply != null) reply.writeNoException(); return true; }
            return realRes;
        }

        private void notifySystemFrameDrawn() {
            if (sSystemProvider == null || !sSystemProvider.isBinderAlive()) return;
            Parcel d = Parcel.obtain(); Parcel r = Parcel.obtain();
            try { d.writeInterfaceToken(PROVIDER_DESCRIPTOR); sSystemProvider.transact(1, d, r, 0); } catch (Throwable t) {} finally { d.recycle(); r.recycle(); }
        }

        private Surface tryExtendedBruteForce(Parcel data) {
            int p = data.dataPosition();
            for (int o = 0; o <= 128; o += 4) { try { data.setDataPosition(o); Surface s = Surface.CREATOR.createFromParcel(data); if (s != null && s.isValid()) return s; } catch (Throwable e) {} }
            data.setDataPosition(p); return null;
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
            } catch (Throwable t) { return false; }
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
}