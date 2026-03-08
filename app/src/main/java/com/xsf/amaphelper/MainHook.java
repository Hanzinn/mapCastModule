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
    private static boolean isNaviRunning = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.xsf.amaphelper")) {
            XposedHelpers.findAndHookMethod("com.xsf.amaphelper.MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // ==========================================
        // 战场 A：LBSNavi (仅作通道保活与打通，不干涉状态机)
        // ==========================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedBridge.log("NaviHook: [Sys] V298 注入 LBSNavi, 准备极简保活机制");
            hookPackageManager(lpparam.classLoader);
            
            // 动态保活，只在导航时触发
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
                    sysHandler = new Handler(Looper.getMainLooper());
                    
                    // 监听我们自己发出的 116 广播来控制保活状态，杜绝7.5开局霸屏Bug
                    IntentFilter filter = new IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND");
                    sysContext.registerReceiver(new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context ctx, Intent intent) {
                            if (intent == null) return;
                            if (intent.getIntExtra("KEY_TYPE", -1) == 10019) {
                                int state = intent.getIntExtra("EXTRA_CURRENT_STATE", -1);
                                if (state == 116) isNaviRunning = true;
                                else if (state == 117) isNaviRunning = false;
                            }
                        }
                    }, filter);
                    
                    sysHandler.postDelayed(() -> forceBindWidgetService(), 4000);
                }
            });
        }

        // ==========================================
        // 战场 B：高德地图端 (核心翻译拦截 + 护航)
        // ==========================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            XposedBridge.log("NaviHook: [Amap] V298 注入高德，启动原厂克隆翻译官");
            hookPackageManager(lpparam.classLoader);
            hookSurfaceDimensions(lpparam.classLoader);

            // 🔥 核心突界：完美复刻 Amap 7.5 的 ij$a 拦截器逻辑！
            try {
                Class<?> contextImplClass = XposedHelpers.findClass("android.app.ContextImpl", lpparam.classLoader);
                XposedBridge.hookAllMethods(contextImplClass, "sendBroadcast", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args[0] instanceof Intent) {
                            Intent intent = (Intent) param.args[0];
                            if ("AUTONAVI_STANDARD_BROADCAST_SEND".equals(intent.getAction())) {
                                // 防死循环机制
                                if (intent.getBooleanExtra("FROM_HOOK", false)) return;

                                int keyType = intent.getIntExtra("KEY_TYPE", -1);
                                if (keyType == 10117) {
                                    int status = intent.getIntExtra("EXTSCREEN_STATUS_INFO", -1);
                                    Context ctx = (Context) param.thisObject;
                                    
                                    if (status == 0) { // 9.1 C++ 引擎要求开屏
                                        Intent fIntent = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                                        fIntent.putExtra("KEY_TYPE", 10019);
                                        fIntent.putExtra("EXTRA_CURRENT_STATE", 116); // 吉利特权开屏码
                                        fIntent.putExtra("FROM_HOOK", true);
                                        ctx.sendBroadcast(fIntent);
                                        XposedBridge.log("NaviHook: [Amap] 🚨 拦截到 10117(开)，已代发 Geely 物理开屏广播(116)！");
                                    } 
                                    else if (status == 1) { // 9.1 C++ 引擎要求关屏
                                        Intent fIntent = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                                        fIntent.putExtra("KEY_TYPE", 10019);
                                        fIntent.putExtra("EXTRA_CURRENT_STATE", 117); // 吉利特权关屏码
                                        fIntent.putExtra("FROM_HOOK", true);
                                        ctx.sendBroadcast(fIntent);
                                        XposedBridge.log("NaviHook: [Amap] 🚨 拦截到 10117(关)，已代发 Geely 物理关屏广播(117)！");
                                    }
                                }
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Amap] 翻译官注册失败: " + t.getMessage());
            }

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

                // 看门狗护航：精准反射调用 onWidgetFirstFrameDrawn
                if (sSystemProvider != null && sSystemProvider.isBinderAlive()) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            Class<?> stubCls = XposedHelpers.findClass("com.autosimilarwidget.view.IAutoWidgetStateProvider$Stub", amapContext.getClassLoader());
                            Object provider = XposedHelpers.callStaticMethod(stubCls, "asInterface", sSystemProvider);
                            XposedHelpers.callMethod(provider, "onWidgetFirstFrameDrawn");
                            XposedBridge.log("NaviHook: [Proxy] 🛡️ 稳了！精准反射调用 onWidgetFirstFrameDrawn 成功！");
                        } catch (Throwable t) {
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

                // 引擎解锁防黑屏
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