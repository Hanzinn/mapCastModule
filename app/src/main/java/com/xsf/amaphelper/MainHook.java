package com.xsf.amaphelper;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.lang.reflect.Method;
import java.util.Set;
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // ==========================================
        // 监听阵列 A：高德地图端 (com.autonavi.amapauto)
        // ==========================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            XposedBridge.log("NaviSpy: [INIT] 高德地图雷达已植入!");

            // 1. 拦截高德发出的所有广播
            XposedHelpers.findAndHookMethod(ContextWrapper.class, "sendBroadcast", Intent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Intent intent = (Intent) param.args[0];
                    if (intent != null && intent.getAction() != null) {
                        String action = intent.getAction();
                        if (action.contains("AUTONAVI_STANDARD_BROADCAST_SEND")) {
                            XposedBridge.log("NaviSpy: [Spy-Amap-Brcast] 高德发出广播 -> Action: " + action + " | Extras: " + dumpExtras(intent));
                        }
                    }
                }
            });

            // 2. 探取高德 7.5 特供版底牌 (吉利多屏管理器)
            try {
                Class<?> jiliMgr = XposedHelpers.findClass("com.autonavi.amapauto.business.devices.factory.preassemble.geely.multiscreen.JiliMultiScreenLcManager", lpparam.classLoader);
                XposedBridge.hookAllMethods(jiliMgr, "switchMultiScreen", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("NaviSpy: [Spy-Amap-Jili] 🚨 高德7.5调用了特供接口 switchMultiScreen! 参: " + param.args[0]);
                    }
                });
                XposedBridge.hookAllMethods(jiliMgr, "switchMultiScreenDelay", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("NaviSpy: [Spy-Amap-Jili] 🚨 高德7.5调用了特供接口 switchMultiScreenDelay! 参: " + param.args[0]);
                    }
                });
            } catch (Throwable t) {
                // 9.1 没有这个类是正常的，静默忽略
            }

            // 3. 监听 Binder 画板交接
            try {
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        IBinder real = (IBinder) param.getResult();
                        param.setResult(new SpyProxyBinder(real));
                    }
                });
            } catch (Throwable t) {}
            
            // 锁定分辨率防黑边 (唯一保留的实用功能)
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", lpparam.classLoader);
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals("getMapSurfaceWidth")) XposedBridge.hookMethod(m, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return 1920; } });
                    if (m.getName().equals("getMapSurfaceHeight")) XposedBridge.hookMethod(m, new XC_MethodReplacement() { @Override protected Object replaceHookedMethod(MethodHookParam param) { return 720; } });
                }
            } catch (Throwable t) {}
            
            // 伪装服务防系统杀
            XC_MethodHook spoofHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
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
            try { XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", lpparam.classLoader, "queryIntentServices", Intent.class, int.class, spoofHook); XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", lpparam.classLoader, "resolveService", Intent.class, int.class, spoofHook); } catch (Throwable t) {}
        }

        // ==========================================
        // 监听阵列 B：车机系统端 (ecarx.naviservice)
        // ==========================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedBridge.log("NaviSpy: [INIT] LBSNavi系统雷达已植入!");

            // 绕过休眠检测，保证 LBSNavi 正常运作
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}

            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ClassLoader cl = ((Context) param.thisObject).getClassLoader();

                    // 4. 监听 LBSNavi 的最终仪表盘决策 (Dashboard Manager)
                    try {
                        Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
                        XposedBridge.hookAllMethods(mgrClass, "a", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                                Object arg = param.args[0];
                                String clsName = arg.getClass().getSimpleName();
                                
                                if ("MapStatusInfo".equals(clsName)) {
                                    XposedBridge.log("NaviSpy: [Spy-LBS-Dash] 仪表盘下发 -> MapStatusInfo(状态): " + XposedHelpers.getIntField(arg, "status"));
                                } else if ("MapSwitchingInfo".equals(clsName)) {
                                    XposedBridge.log("NaviSpy: [Spy-LBS-Dash] 仪表盘下发 -> MapSwitchingInfo(切屏): " + XposedHelpers.getIntField(arg, "mSwitchState"));
                                } else {
                                    XposedBridge.log("NaviSpy: [Spy-LBS-Dash] 仪表盘下发 -> 其他类: " + clsName);
                                }
                            }
                        });
                    } catch (Throwable t) {}

                    // 5. 监听 LBSNavi 内部 EventBus (RxBus) 核心事件流
                    try {
                        Class<?> rxBusClass = XposedHelpers.findClass("ecarx.naviservice.d.e", cl);
                        XposedBridge.hookAllMethods(rxBusClass, "a", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                                Object eventObj = param.args[0];
                                if ("bz".equals(eventObj.getClass().getSimpleName())) { // ecarx.naviservice.map.bz 是事件实体类
                                    int eventCode = (int) XposedHelpers.callMethod(eventObj, "b");
                                    Object eventData = XposedHelpers.callMethod(eventObj, "a");
                                    XposedBridge.log("NaviSpy: [Spy-LBS-Bus] 内部总线传递事件 -> Code: 0x" + Integer.toHexString(eventCode) + " | Data: " + eventData);
                                }
                            }
                        });
                    } catch (Throwable t) {}
                }
            });
        }
    }

    // Binder 窃听器 (绝对透明，绝不拦截)
    public static class SpyProxyBinder extends Binder {
        private IBinder realBinder;

        public SpyProxyBinder(IBinder real) {
            this.realBinder = real; 
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                XposedBridge.log("NaviSpy: [Spy-Binder] 🎯 系统下发了 Code 1 (AddSurface) 画布给高德！");
            } else if (code == 2) {
                XposedBridge.log("NaviSpy: [Spy-Binder] 🛑 系统下发了 Code 2 (RemoveSurface) 收回画布！");
            } else if (code == 3) {
                XposedBridge.log("NaviSpy: [Spy-Binder] 🔍 系统查询了 Code 3 (isMapRunning)");
            } else if (code == 4) {
                XposedBridge.log("NaviSpy: [Spy-Binder] 🔗 系统下发了 Code 4 (setWidgetStateControl)");
            }
            
            // 全透明移交原生处理，绝不干预
            if (realBinder != null) {
                return realBinder.transact(code, data, reply, flags);
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    // 格式化输出 Intent 参数
    private static String dumpExtras(Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle == null) return "null";
        StringBuilder sb = new StringBuilder();
        Set<String> keys = bundle.keySet();
        for (String key : keys) {
            sb.append(key).append("=").append(bundle.get(key)).append("; ");
        }
        return sb.toString();
    }
}