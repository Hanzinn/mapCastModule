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

    // 目标：9.1 和 7.5 都存在的 Service
    private static final String TARGET_SERVICE = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    private static final String PKG_MAP = "com.autonavi.amapauto";
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";

    private static Context mapContext;
    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static boolean isConnected = false;
    
    // 7.5 独有的特征类
    private static final String LEGACY_75_HELPER = "com.AutoHelper";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // =============================================================
        // 🏰 战场 A：高德地图进程
        // =============================================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            // 🔥 步骤 1：指纹识别 (7.5 有 AutoHelper，9.1 没有)
            boolean is75 = XposedHelpers.findClassIfExists(LEGACY_75_HELPER, lpparam.classLoader) != null;
            if (is75) {
                XposedBridge.log("NaviHook: [Map] ⚠️ 发现 com.AutoHelper，确认为 7.5，停止 Hook。");
                return; // 7.5 直接退出
            }

            XposedBridge.log("NaviHook: [Map] ✅ 未发现 AutoHelper，确认为 9.1，准备注入...");
            
            try {
                // Hook 9.1 Service
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XposedBridge.log("NaviHook: [Map] 拦截系统连接，返回 TrojanBinder");
                        param.setResult(new TrojanBinder(lpparam.classLoader)); 
                    }
                });
                
                // 保护性 Hook
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log("NaviHook: [Map] Service onCreate 保护");
                    }
                });

            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] Hook 错误: " + t);
            }
        }

        // =============================================================
        // 🚗 战场 B：车机系统进程
        // =============================================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedBridge.log("NaviHook: [Sys] 注入车机系统...");
            
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override 
                protected void afterHookedMethod(MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    registerReceiver();
                    
                    // 延时等待系统就绪
                    sysHandler.postDelayed(() -> initEnvironment(lpparam.classLoader), 5000);
                }
            });
            
            // 破解 Vendor 校验
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) {
                    XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
                }
            } catch (Throwable t) {}
        }
    }

    // =============================================================
    // 🦄 特洛伊 Binder (核心逻辑)
    // =============================================================
    public static class TrojanBinder extends Binder {
        private ClassLoader classLoader;
        private boolean surfaceInjected = false; // 🔥 防闪烁锁
        
        public TrojanBinder(ClassLoader cl) {
            this.classLoader = cl;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                // 1. 握手 (Code 20)
                if (code == 20) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // 2. Surface 传输 (Code 4 或 43)
                if (code == 4 || code == 43) {
                    // 探针：打印数据包大小
                    int size = data.dataSize();
                    XposedBridge.log("NaviHook: [Binder] 🔥 收到 Code " + code + " | Size: " + size);

                    if (surfaceInjected) {
                        XposedBridge.log("NaviHook: [Binder] Surface 已注入，防闪烁跳过");
                        if (reply != null) reply.writeNoException();
                        return true; 
                    }
                    
                    data.setDataPosition(0);
                    try { data.readString(); } catch(Exception e){} // Skip Token
                    
                    if (data.readInt() != 0) {
                        Surface surface = Surface.CREATOR.createFromParcel(data);
                        if (surface != null && surface.isValid()) {
                            XposedBridge.log("NaviHook: [Binder] 🔥 捕获有效 Surface! 注入引擎...");
                            injectNativeEngine(surface);
                            surfaceInjected = true; // 🔒 锁定
                        }
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // 3. 心跳/注册 (Code 1) - 维持连接
                if (code == 1) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // 4. 断开/移除 (Code 2) - 重置锁
                if (code == 2) {
                    XposedBridge.log("NaviHook: [Binder] 收到 Code 2 (Reset)");
                    surfaceInjected = false; // 🔓 解锁
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // 捕获其他未知 Code
                if (code != 1598968902) { 
                    XposedBridge.log("NaviHook: [Binder] 未知 Code: " + code);
                }
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Binder] 异常: " + t);
            }
            return true;
        }
        
        private void injectNativeEngine(Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                m.invoke(null, 1, 2, surface); 
                XposedBridge.log("NaviHook: [Map] ✅✅✅ 9.1 Native 引擎注入成功！");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] 注入失败: " + t);
            }
        }
    }

    // =============================================================
    // 📡 系统侧逻辑
    // =============================================================
    private void initEnvironment(ClassLoader cl) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            
            if (dashboardMgr == null) {
                sysHandler.postDelayed(() -> initEnvironment(cl), 5000);
                return;
            }
            
            Object conn = null;
            try { conn = XposedHelpers.getObjectField(dashboardMgr, "f"); } catch (Throwable t) {}
            
            boolean isLegacy75 = false;
            
            if (conn != null) {
                String connClass = conn.getClass().getName();
                XposedBridge.log("NaviHook: [Sys] 现有连接: " + connClass);
                
                // 严谨判断：必须是原生的 AutoWidgetService 且不是 BinderProxy
                if (connClass.contains("AutoWidgetService") && !connClass.contains("Proxy")) {
                    isLegacy75 = true;
                }
            }
            
            if (isLegacy75) {
                XposedBridge.log("NaviHook: [Sys] ⚠️ 确认 7.5 原生模式，插件静默。");
            } else {
                XposedBridge.log("NaviHook: [Sys] ⚡ 判定为 9.1 模式，准备激活...");
                bindToMapService();
            }
            
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] 环境错误: " + t);
        }
    }

    private void bindToMapService() {
        if (sysContext == null || isConnected) return;
        
        sysHandler.post(() -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                
                boolean bound = sysContext.bindService(intent, new ServiceConnection() {
                    @Override 
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        XposedBridge.log("NaviHook: [Sys] ✅ 连接成功");
                        isConnected = true;
                        injectToDashboard(service);
                    }
                    
                    @Override 
                    public void onServiceDisconnected(ComponentName name) {
                        XposedBridge.log("NaviHook: [Sys] ❌ 连接断开，3秒后重连...");
                        isConnected = false;
                        sysHandler.postDelayed(() -> bindToMapService(), 3000);
                    }
                }, Context.BIND_AUTO_CREATE);
                
                if (!bound) XposedBridge.log("NaviHook: [Sys] Bind False");
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Sys] Bind 异常: " + t);
            }
        });
    }

    private void injectToDashboard(IBinder binder) {
        try {
            Object internalConn = XposedHelpers.getObjectField(dashboardMgr, "f");
            if (internalConn != null) {
                ComponentName cn = new ComponentName(PKG_MAP, TARGET_SERVICE);
                Method onConnected = internalConn.getClass().getMethod("onServiceConnected", ComponentName.class, IBinder.class);
                onConnected.invoke(internalConn, cn, binder);
                
                XposedBridge.log("NaviHook: [Sys] 💉 注入完成，触发一次激活...");
                triggerMapSwitchOnce();
            }
        } catch (Throwable t) {}
    }

    private void triggerMapSwitchOnce() {
        try {
            ClassLoader cl = sysContext.getClassLoader();
            Class<?> clsSwitch = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl);
            Object sw = XposedHelpers.newInstance(clsSwitch, 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", 3);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);
            
            Class<?> clsStatus = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl);
            Object st = XposedHelpers.newInstance(clsStatus, 0);
            XposedHelpers.setIntField(st, "status", 16);
            XposedHelpers.callMethod(dashboardMgr, "a", st);
            
            XposedBridge.log("NaviHook: [Sys] ⚡ 激活指令已发送 (One Shot)");
            
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] 激活失败: " + t);
        }
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter("XSF_ACTION_START_CAST");
        sysContext.registerReceiver(new BroadcastReceiver() {
            @Override 
            public void onReceive(Context context, Intent intent) {
                XposedBridge.log("NaviHook: [Sys] 手动重连...");
                isConnected = false;
                bindToMapService();
            }
        }, filter);
    }

    // 🌟 修复版广播发送：使用 Class.forName 避免编译错误
    private void sendJavaBroadcast(String log) {
        // 先打印到 Xposed 日志，确保不丢信息
        XposedBridge.log("NaviHook: " + log);
        
        if (sysContext == null) return;
        new Thread(() -> {
            try {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.setPackage(PKG_SELF);
                i.putExtra("log", log);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                try {
                    // 使用纯反射加载 UserHandle，绕过编译器检查
                    Class<?> userHandleClass = Class.forName("android.os.UserHandle");
                    Object userAll = XposedHelpers.getStaticObjectField(userHandleClass, "ALL");
                    Method method = Context.class.getMethod("sendBroadcastAsUser", Intent.class, userHandleClass);
                    method.invoke(sysContext, i, userAll);
                } catch (Throwable t) {
                    sysContext.sendBroadcast(i);
                }
            } catch (Throwable t) {}
        }).start();
    }
}