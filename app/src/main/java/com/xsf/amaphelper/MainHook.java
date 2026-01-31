package com.xsf.amaphelper;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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

    // 静态变量保持跨方法状态
    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static boolean isLegacy75 = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 模块自激活检查
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // =============================================================
        // 🏰 战场 A：高德地图进程 (特洛伊木马核心)
        // =============================================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            // 🔍 侦察兵：判断版本
            boolean hasAutoHelper = XposedHelpers.findClassIfExists("com.AutoHelper", lpparam.classLoader) != null;
            String versionMode = hasAutoHelper ? "7.5 Legacy" : "9.1 Modern";
            XposedBridge.log("NaviHook: [Map] 侦测到版本模式: " + versionMode);

            try {
                // ⚔️ 核心 Hook：拦截 Bind 请求，植入特洛伊 Binder
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XposedBridge.log("NaviHook: [Map] 🚨 收到 Bind 请求，释放 V203 特洛伊 Binder...");
                        // 传入 ClassLoader 用于反射 Native 引擎
                        param.setResult(new TrojanBinder(lpparam.classLoader));
                    }
                });
                
                // 🛡️ 防御 Hook：防止 Service 初始化崩溃
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log("NaviHook: [Map] Service onCreate 保护生效");
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] Hook 失败: " + t);
            }
        }

        // =============================================================
        // 🚗 战场 B：车机系统进程 (PM 欺骗 + 激活器)
        // =============================================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    
                    // 延迟 5秒 等待系统完全加载
                    sysHandler.postDelayed(() -> initSystemEnvironment(lpparam.classLoader), 5000);
                }
            });

            // 🔥 [V182 遗产] PM 欺骗：这是 7.5 实现“巡航投屏”的关键！
            // 欺骗系统：告诉它 AutoSimilarWidgetService 存在且已导出 (Exported)
            // 这样系统在巡航状态下也会尝试连接它
            hookPackageManager(lpparam.classLoader);

            // 🔓 解锁 Vendor 校验
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}
        }
    }

    // =============================================================
    // 🦄 V203 特洛伊 Binder (修复线程崩溃 + 防闪烁)
    // =============================================================
    public static class TrojanBinder extends Binder {
        private ClassLoader classLoader;
        private boolean isSurfaceActive = false; // 🔒 防闪烁锁
        private Handler uiHandler; // 🧵 主线程 Handler

        public TrojanBinder(ClassLoader cl) {
            this.classLoader = cl;
            // 获取主线程 Looper，解决 9.1 握手失败的核心
            this.uiHandler = new Handler(Looper.getMainLooper());
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                // Code 4 (或 43): 系统传输 Surface 过来
                if (code == 4 || code == 43) {
                    // XposedBridge.log("NaviHook: [Binder] 收到 Surface 请求 (Code " + code + ")");

                    // 🛑 如果已经激活，忽略后续的重复请求 (防止 7.5 闪烁)
                    if (isSurfaceActive) {
                        if (reply != null) reply.writeNoException();
                        return true;
                    }

                    data.setDataPosition(0);
                    try { data.readString(); } catch (Exception e) {} // 跳过 Token

                    if (data.readInt() != 0) {
                        Surface surface = Surface.CREATOR.createFromParcel(data);
                        if (surface != null && surface.isValid()) {
                            XposedBridge.log("NaviHook: [Binder] 🔥 捕获有效 Surface！");
                            
                            // ✅ 关键修复：切回主线程执行 Native 调用
                            // 之前在 Binder 线程调用导致了 9.1 的 Code 2 Reset
                            uiHandler.post(() -> injectNativeEngine(surface));
                            
                            isSurfaceActive = true; // 🔒 锁定状态
                        }
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // Code 2: 系统要求重置/断开
                if (code == 2) {
                    XposedBridge.log("NaviHook: [Binder] 收到 Reset (Code 2) - 重置锁");
                    isSurfaceActive = false; // 🔓 解锁，允许下次重连
                    if (reply != null) reply.writeNoException();
                    return true;
                }

                // Code 20 / 1: 握手与心跳
                if (code == 20 || code == 1) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }

            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Binder] Transact 异常: " + t);
            }
            return true;
        }

        private void injectNativeEngine(Surface surface) {
            try {
                // 反射调用高德地图的底层引擎
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
                // nativeSurfaceCreated(int displayId, int type, Surface surface)
                Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                m.invoke(null, 1, 2, surface);
                XposedBridge.log("NaviHook: [Map] ✅ Native 引擎注入成功 (Main Thread)");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] ❌ 注入失败: " + t);
                isSurfaceActive = false; // 失败则不锁定，允许重试
            }
        }
    }

    // =============================================================
    // 🛠️ [V182] PM 欺骗逻辑 (7.5 巡航投屏核心)
    // =============================================================
    private void hookPackageManager(ClassLoader cl) {
        try {
            // 拦截 queryIntentServices
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", cl, "queryIntentServices", Intent.class, int.class, new XC_MethodHook() {
                @SuppressWarnings("unchecked")
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Intent intent = (Intent) param.args[0];
                    // 如果系统在找 AutoSimilarWidgetService
                    if (intent != null && intent.getComponent() != null && TARGET_SERVICE.equals(intent.getComponent().getClassName())) {
                        List<ResolveInfo> result = (List<ResolveInfo>) param.getResult();
                        if (result == null) result = new ArrayList<>();

                        if (result.isEmpty()) {
                            XposedBridge.log("NaviHook: [PM] 🎭 触发 PM 欺骗：伪造服务存在");
                            ResolveInfo info = new ResolveInfo();
                            info.serviceInfo = new ServiceInfo();
                            info.serviceInfo.packageName = PKG_MAP;
                            info.serviceInfo.name = TARGET_SERVICE;
                            info.serviceInfo.exported = true; // 关键：必须是 exported
                            info.serviceInfo.applicationInfo = new ApplicationInfo();
                            info.serviceInfo.applicationInfo.packageName = PKG_MAP;
                            result.add(info);
                            param.setResult(result);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] PM Hook 错误: " + t);
        }
    }

    // =============================================================
    // 📡 系统侧环境初始化
    // =============================================================
    private void initSystemEnvironment(ClassLoader cl) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            
            Object conn = XposedHelpers.getObjectField(dashboardMgr, "f");
            
            // 智能判定：如果已经有连接对象，且包含 AutoHelper，说明是 7.5
            if (conn != null && conn.getClass().getName().contains("AutoHelper")) {
                isLegacy75 = true;
                XposedBridge.log("NaviHook: [Sys] ⚠️ 识别为 7.5 模式。PM 欺骗已生效，等待系统自动连接...");
                // 7.5 不需要手动 bind，PM 欺骗会让系统在巡航时自动 bind
            } else {
                XposedBridge.log("NaviHook: [Sys] ⚡ 识别为 9.1 模式 (或空闲)。准备主动 Bind...");
                bindToMapService();
            }

        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] 环境初始化异常: " + t);
        }
    }

    // 针对 9.1 的主动连接逻辑
    private void bindToMapService() {
        if (sysContext == null) return;
        sysHandler.post(() -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                
                sysContext.bindService(intent, new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        XposedBridge.log("NaviHook: [Sys] ✅ 9.1 物理连接成功！注入 DashboardMgr...");
                        injectToDashboard(service);
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                         XposedBridge.log("NaviHook: [Sys] ❌ 连接断开");
                    }
                }, Context.BIND_AUTO_CREATE);
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Sys] Bind 失败: " + t);
            }
        });
    }

    private void injectToDashboard(IBinder binder) {
        try {
            Object internalConn = XposedHelpers.getObjectField(dashboardMgr, "f");
            // 将我们的 TrojanBinder 塞给系统
            if (internalConn != null) {
                Method onConnected = internalConn.getClass().getMethod("onServiceConnected", ComponentName.class, IBinder.class);
                onConnected.invoke(internalConn, new ComponentName(PKG_MAP, TARGET_SERVICE), binder);
                
                // 触发一次状态切换，让屏幕亮起来
                triggerMapSwitch();
            }
        } catch (Throwable t) {
             XposedBridge.log("NaviHook: [Sys] 注入 Dashboard 失败: " + t);
        }
    }

    private void triggerMapSwitch() {
        try {
            ClassLoader cl = sysContext.getClassLoader();
            // Switch State 3 (投屏)
            Object sw = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl), 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", 3);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);
            
            // Status 16 (导航中)
            Object st = XposedHelpers.newInstance(XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl), 0);
            XposedHelpers.setIntField(st, "status", 16);
            XposedHelpers.callMethod(dashboardMgr, "a", st);
            
            XposedBridge.log("NaviHook: [Sys] 激活指令已发送");
        } catch (Throwable t) {}
    }
}