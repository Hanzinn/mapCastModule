package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
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

    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static Timer statusHeartbeat;
    private static int lastSentStatus = -1;
    private static volatile boolean isInitialized = false;
    private static volatile boolean isLegacy75 = false; // 明确标记版本

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // =============================================================
        // 🏰 Map 端：版本检测 + 广播通知 System 端
        // =============================================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    checkVersionAndNotify(ctx, lpparam.classLoader);
                }
            });
        }

        // =============================================================
        // 🚗 System 端：等待 Map 端广播，不再猜测
        // =============================================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    
                    // 注册版本检测广播
                    registerVersionReceiver();
                    
                    // 备选：如果 5 秒内没收到广播，默认按 9.1 处理（主动 Bind）
                    sysHandler.postDelayed(() -> {
                        if (!isInitialized) {
                            XposedBridge.log("NaviHook: [Sys] ⚠️ 未收到版本广播，默认 9.1 模式");
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

    // Map 端检测版本并广播给 System 端
    private void checkVersionAndNotify(Context ctx, ClassLoader cl) {
        try {
            String versionName = ctx.getPackageManager().getPackageInfo(PKG_MAP, 0).versionName;
            boolean is75 = versionName != null && (versionName.startsWith("7.5") || versionName.startsWith("7."));
            
            // 双重确认
            if (!is75) {
                is75 = XposedHelpers.findClassIfExists("com.AutoHelper", cl) != null;
            }
            
            isLegacy75 = is75;
            XposedBridge.log("NaviHook: [Map] Version: " + versionName + " -> " + (is75 ? "7.5" : "9.1"));
            
            // 广播给 System 端
            Intent intent = new Intent("com.xsf.amaphelper.VERSION_NOTIFY");
            intent.setPackage(PKG_SERVICE);
            intent.putExtra("is_legacy_75", is75);
            ctx.sendBroadcast(intent);
            
            // 9.1 才需要植入
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

    // System 端接收版本广播
    private void registerVersionReceiver() {
        try {
            IntentFilter filter = new IntentFilter("com.xsf.amaphelper.VERSION_NOTIFY");
            sysContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (isInitialized) return; // 只处理一次
                    
                    isLegacy75 = intent.getBooleanExtra("is_legacy_75", false);
                    isInitialized = true;
                    
                    if (isLegacy75) {
                        XposedBridge.log("NaviHook: [Sys] ✅ 收到广播：7.5 模式");
                        initAs75();
                    } else {
                        XposedBridge.log("NaviHook: [Sys] ⚡ 收到广播：9.1 模式");
                        initAs91();
                    }
                }
            }, filter);
        } catch (Throwable t) {}
    }

    private void initAs75() {
        // 7.5：只发心跳，不 Bind
        startStatusHeartbeat(true);
    }

    private void initAs91() {
        // 9.1：主动 Bind
        bindToMapService();
        startStatusHeartbeat(false);
    }

    // =============================================================
    // 🦄 特洛伊 Binder（修复 Surface 解析）
    // =============================================================
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
                
                // 打印前4个int的hex（调试用）
                StringBuilder hex = new StringBuilder();
                int startPos = data.dataPosition();
                for (int i = 0; i < 4 && data.dataAvail() >= 4; i++) {
                    hex.append(String.format("%08X ", data.readInt()));
                }
                data.setDataPosition(startPos);
                
                XposedBridge.log(String.format("NaviHook: [Binder] Code=%d Size=%d Hex=%s", code, dataSize, hex));

                // 🔥 关键修复：Code 2 也可能是 Surface（从日志看 Size=240）
                // Code 1 和 Code 2 都可能是 Surface 传输，只是不同状态
                
                if ((code == 1 || code == 2) && dataSize > 100) {
                    if (isSurfaceActive && code == 1) {
                        // 已经有 Surface 了，Code 1 可能是更新或心跳
                        if (reply != null) reply.writeNoException();
                        return true;
                    }
                    
                    XposedBridge.log("NaviHook: [Binder] 🎯 Code " + code + " = Surface packet (" + dataSize + ")");
                    
                    data.setDataPosition(0);
                    Surface surface = null;
                    
                    // 🔥 尝试 1：跳过接口头（Interface Token）通常是 String "com.xxx"
                    try {
                        // 通常是 String 描述符，长度不定
                        String descriptor = data.readString();
                        XposedBridge.log("NaviHook: [Binder] Descriptor: " + descriptor);
                    } catch (Exception e) {}
                    
                    // 尝试 2：读取 hasSurface 标志
                    try {
                        int hasSurface = data.readInt();
                        XposedBridge.log("NaviHook: [Binder] hasSurface flag: " + hasSurface);
                        if (hasSurface != 0) {
                            surface = Surface.CREATOR.createFromParcel(data);
                        }
                    } catch (Exception e) {
                        XposedBridge.log("NaviHook: [Binder] Try 1 failed: " + e.getMessage());
                    }
                    
                    // 尝试 3：直接从当前位置读取（如果上面失败）
                    if (surface == null) {
                        try {
                            surface = Surface.CREATOR.createFromParcel(data);
                            XposedBridge.log("NaviHook: [Binder] Try 2 (direct) success");
                        } catch (Exception e2) {
                            XposedBridge.log("NaviHook: [Binder] Try 2 failed: " + e2.getMessage());
                        }
                    }
                    
                    // 尝试 4：回到开头，跳过16字节头再试
                    if (surface == null) {
                        try {
                            data.setDataPosition(16); // 跳过可能的手动头
                            surface = Surface.CREATOR.createFromParcel(data);
                            XposedBridge.log("NaviHook: [Binder] Try 3 (offset 16) success");
                        } catch (Exception e3) {}
                    }

                    if (surface != null && surface.isValid()) {
                        XposedBridge.log("NaviHook: [Binder] ✅ Surface valid, injecting...");
                        final Surface s = surface;
                        uiHandler.post(() -> injectNativeEngine(s));
                        isSurfaceActive = true;
                    } else {
                        XposedBridge.log("NaviHook: [Binder] ❌ Surface invalid");
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // Code 4：握手（Register）
                if (code == 4) {
                    XposedBridge.log("NaviHook: [Binder] 🎯 Code 4 = Handshake");
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // 小包：心跳
                if (code == 20 || code == 1 || code == 2) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Binder] Error: " + t);
            }
            return true;
        }

        private void injectNativeEngine(Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                m.invoke(null, 1, 2, surface);
                XposedBridge.log("NaviHook: [Map] ✅ Engine injected");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] ❌ Inject failed: " + t);
                isSurfaceActive = false;
            }
        }
    }

    // 其他方法保持不变...
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
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", sysContext.getClassLoader());
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            
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
