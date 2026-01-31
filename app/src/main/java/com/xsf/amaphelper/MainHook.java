package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
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
    private static int lastSentStatus = -1;
    private static volatile boolean isEnvChecked = false;
    private static volatile boolean isLegacy75 = false;

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
                    checkVersionAndHook(ctx, lpparam.classLoader);
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
                    sysHandler.postDelayed(() -> {
                        if (!isEnvChecked) {
                            XposedBridge.log("NaviHook: [Sys] Timeout, default 9.1 mode");
                            initAs91();
                        }
                    }, 5000);
                }
            });

            hookPackageManager(lpparam.classLoader);
            
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}
        }
    }

    private void checkVersionAndHook(Context ctx, ClassLoader cl) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(PKG_MAP, 0);
            String versionName = info.versionName;
            boolean is75 = versionName != null && (versionName.startsWith("7.5") || versionName.startsWith("7."));
            
            if (!is75) {
                is75 = XposedHelpers.findClassIfExists("com.AutoHelper", cl) != null;
            }
            
            isLegacy75 = is75;
            XposedBridge.log("NaviHook: [Map] Version: " + versionName + " -> " + (is75 ? "7.5" : "9.1"));
            
            Intent intent = new Intent("com.xsf.amaphelper.VERSION_NOTIFY");
            intent.setPackage(PKG_SERVICE);
            intent.putExtra("is_legacy_75", is75);
            ctx.sendBroadcast(intent);
            
            if (!is75) {
                try {
                    XposedHelpers.findAndHookMethod(TARGET_SERVICE, cl, "onBind", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(new TrojanBinder(cl));
                        }
                    });
                } catch (Throwable t) {}
            }
        } catch (Throwable t) {}
    }

    private void registerVersionReceiver() {
        try {
            IntentFilter filter = new IntentFilter("com.xsf.amaphelper.VERSION_NOTIFY");
            sysContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (isEnvChecked) return;
                    isLegacy75 = intent.getBooleanExtra("is_legacy_75", false);
                    isEnvChecked = true;
                    
                    if (isLegacy75) {
                        XposedBridge.log("NaviHook: [Sys] 7.5 mode");
                        initAs75();
                    } else {
                        XposedBridge.log("NaviHook: [Sys] 9.1 mode");
                        initAs91();
                    }
                }
            }, filter);
        } catch (Throwable t) {}
    }

    private void initAs75() {
        startStatusHeartbeat(true);
    }

    private void initAs91() {
        bindToMapService();
        startStatusHeartbeat(false);
    }

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
                
                if (dataSize > 50 || code == 4) {
                    XposedBridge.log("NaviHook: [Binder] Code=" + code + " Size=" + dataSize);
                }

                // 🔥 Code 2 可能是 AddSurface（还没导航就出现了）
                if (code == 2 && dataSize > 100) {
                    XposedBridge.log("NaviHook: [Binder] Code 2 = AddSurface?");
                    Surface s = tryParseSurface(data);
                    if (s != null) {
                        uiHandler.post(() -> injectNativeEngine(s));
                        isSurfaceActive = true;
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // 🔥 Code 1 可能是 UpdateSurface
                if (code == 1) {
                    if (dataSize > 100 && !isSurfaceActive) {
                        XposedBridge.log("NaviHook: [Binder] Code 1 = Surface");
                        Surface s = tryParseSurface(data);
                        if (s != null) {
                            uiHandler.post(() -> injectNativeEngine(s));
                            isSurfaceActive = true;
                        }
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                if (code == 4) {
                    XposedBridge.log("NaviHook: [Binder] Code 4 = Handshake");
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Binder] Error: " + t);
            }
            return true;
        }

        // 暴力尝试解析 Surface
        private Surface tryParseSurface(Parcel data) {
            int originalPos = data.dataPosition();
            
            for (int offset = 0; offset <= 32; offset += 4) {
                if (offset >= data.dataSize() - 10) break;
                
                try {
                    data.setDataPosition(offset);
                    Surface s = Surface.CREATOR.createFromParcel(data);
                    if (s != null && s.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] Surface at offset " + offset);
                        return s;
                    }
                } catch (Exception e) {}
            }
            
            data.setDataPosition(originalPos);
            return null;
        }

        private void injectNativeEngine(Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                m.invoke(null, 1, 2, surface);
                XposedBridge.log("NaviHook: [Map] Engine injected");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] Inject failed: " + t);
                isSurfaceActive = false;
            }
        }
    }

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
                        ResolveInfo single = (ResolveInfo) param.getResult();
                        if (single == null) result = new ArrayList<>();
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

    private void initSystemEnvironment(ClassLoader cl) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            
            Object conn = null;
            try { conn = XposedHelpers.getObjectField(dashboardMgr, "f"); } catch (Throwable t) {}
            
            if (conn != null) {
                XposedBridge.log("NaviHook: [Sys] 7.5 mode (conn exists)");
                startStatusHeartbeat(true);
            } else {
                XposedBridge.log("NaviHook: [Sys] 9.1 mode (no conn)");
                bindToMapService();
                startStatusHeartbeat(false);
            }
        } catch (Throwable t) {}
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
                triggerMapSwitch();
            }
        } catch (Throwable t) {}
    }

    private void triggerMapSwitch() {
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", 3);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);
            
            Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
            XposedHelpers.setIntField(st, "status", 16);
            XposedBridge.log("NaviHook: [Sys] Activated");
        } catch (Throwable t) {}
    }

    private void startStatusHeartbeat(boolean isLoop) {
        if (statusHeartbeat != null) statusHeartbeat.cancel();
        
        statusHeartbeat = new Timer();
        statusHeartbeat.schedule(new TimerTask() {
            @Override
            public void run() {
                if (sysContext == null) return;
                try {
                    ClassLoader cl = sysContext.getClassLoader();
                    Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
                    Object mgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
                    
                    Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
                    XposedHelpers.setIntField(sw, "mSwitchState", 3);
                    XposedHelpers.callMethod(mgr, "a", sw);

                    Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
                    XposedHelpers.setIntField(st, "status", 16);
                    XposedHelpers.callMethod(mgr, "a", st);
                } catch (Throwable t) {}
            }
        }, 1000, isLoop ? 3000 : 99999999);
    }
}
