
package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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

    // 目标：9.1 真实存在的 Service
    private static final String TARGET_SERVICE = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    
    private static final String PKG_MAP = "com.autonavi.amapauto";
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";

    // 地图侧变量
    private static Context mapContext;
    
    // 系统侧变量
    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static boolean isLegacy75 = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // =============================================================
        // 🏰 战场 A：高德地图 9.1 (埋下特洛伊木马)
        // =============================================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            XposedBridge.log("NaviHook: [Map] 正在改造 AutoSimilarWidgetService...");
            
            try {
                // 1. Hook onBind：偷梁换柱，返回我们的 Binder
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("NaviHook: [Map] 系统来连接了！返回 FakeBinder...");
                        // 返回这里的 TrojanBinder，它在 Map 进程运行
                        param.setResult(new TrojanBinder(lpparam.classLoader)); 
                    }
                });
                
                // 2. Hook onCreate：防止 9.1 原生代码报错 (如果缺类)
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log("NaviHook: [Map] Service onCreate 拦截保护");
                        // 如果原版代码有 bug (缺 AutoHelper)，这里如果不拦截可能会崩
                        // 我们可以选择 param.setResult(null) 跳过原逻辑，或者 try-catch
                        // 暂时先让它跑，如果崩了再完全替换
                    }
                });

                // 3. 拿到 Context 备用
                XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        mapContext = (Context) param.thisObject;
                    }
                });

            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] Hook 失败: " + t);
            }
        }

        // =============================================================
        // 🚗 战场 B：车机系统 (发起进攻)
        // =============================================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedBridge.log("NaviHook: [Sys] 注入车机系统...");
            
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    
                    registerSysReceiver(sysContext);
                    sysHandler.postDelayed(() -> checkEnvironment(lpparam.classLoader), 3000);
                }
            });
            
            // 破解 Vendor 切换校验
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}
        }
    }

    // =============================================================
    // 🦄 特洛伊 Binder (运行在高德进程)
    // =============================================================
    public static class TrojanBinder extends Binder {
        private ClassLoader classLoader;
        
        public TrojanBinder(ClassLoader cl) {
            this.classLoader = cl;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                // Code 43: addSurface (系统送画布来了)
                if (code == 43) {
                    XposedBridge.log("NaviHook: [Map-Binder] 收到 addSurface (Code 43)!");
                    
                    data.setDataPosition(0);
                    try { data.readString(); } catch(Exception e){} // Skip Token
                    
                    if (data.readInt() != 0) {
                        Surface surface = Surface.CREATOR.createFromParcel(data);
                        if (surface != null) {
                            XposedBridge.log("NaviHook: [Map-Binder] 拿到 Surface! 直接注入引擎...");
                            // 🔥 就在这里，直接调用 Native 引擎！不用广播！
                            injectNativeEngine(classLoader, surface);
                        }
                    }
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // Code 20: Register (握手)
                if (code == 20) {
                    XposedBridge.log("NaviHook: [Map-Binder] 收到 Register (Code 20)");
                    if (reply != null) reply.writeNoException();
                    return true;
                }
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map-Binder] Error: " + t);
            }
            return true; // 吞掉所有异常，防止系统崩溃
        }
        
        private void injectNativeEngine(ClassLoader cl, Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", cl);
                // static void nativeSurfaceCreated(int displayId, int type, Surface surface)
                Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                m.invoke(null, 1, 2, surface); // 1=仪表盘
                XposedBridge.log("NaviHook: [Map] ✅✅✅ 引擎已接管 Surface! 投屏成功!");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] 引擎注入失败: " + t);
            }
        }
    }

    // =============================================================
    // 📡 系统侧逻辑 (负责连接)
    // =============================================================
    private void checkEnvironment(ClassLoader cl) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            Object conn = XposedHelpers.getObjectField(dashboardMgr, "f");
            if (conn != null) {
                isLegacy75 = true;
                sendJavaBroadcast("⚠️ [Sys] 发现 7.5 连接，防冲突休眠");
            } else {
                isLegacy75 = false;
                sendJavaBroadcast("⚡ [Sys] 9.1 模式就绪");
            }
        } catch (Throwable t) {}
    }

    private void startActivation() {
        if (isLegacy75 || sysContext == null) return;
        
        sysHandler.post(() -> {
            try {
                sendJavaBroadcast("🚀 [1/3] 连接 AutoSimilarWidgetService...");
                Intent intent = new Intent();
                // 🔥 直接连接 9.1 的这个 Service
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                
                // 系统会去 Bind 9.1，9.1 的 onBind 会被我们 Hook，返回 TrojanBinder
                sysContext.bindService(intent, new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder service) {
                        sendJavaBroadcast("🔗 [2/3] 连接成功! 注入系统...");
                        // 这里的 service 就是我们的 TrojanBinder
                        injectToSystem(service);
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        sendJavaBroadcast("❌ 连接断开");
                    }
                }, Context.BIND_AUTO_CREATE);
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 启动失败: " + t);
            }
        });
    }

    private void injectToSystem(IBinder binder) {
        try {
            Object internalConn = XposedHelpers.getObjectField(dashboardMgr, "f");
            if (internalConn != null) {
                // 把我们的 TrojanBinder 塞给系统管理器
                // 系统随后会调用 binder.addSurface(43)
                // 这个调用会直接走进 TrojanBinder.onTransact -> injectNativeEngine
                ComponentName fakeCn = new ComponentName(PKG_MAP, TARGET_SERVICE);
                Method onConnected = internalConn.getClass().getMethod("onServiceConnected", ComponentName.class, IBinder.class);
                onConnected.invoke(internalConn, fakeCn, binder);
                
                sendJavaBroadcast("💉 [3/3] 注入完毕，诱导系统...");
                sysHandler.postDelayed(() -> triggerSwitch(), 500);
            }
        } catch (Throwable t) {
            sendJavaBroadcast("❌ 注入系统失败: " + t);
        }
    }

    private void triggerSwitch() {
        try {
            // 触发 5->0 切换，系统会响应并调用 addSurface
            ClassLoader cl = sysContext.getClassLoader();
            Class<?> clsSwitch = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl);
            Object sw = XposedHelpers.newInstance(clsSwitch, 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", 3);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);
            
            Class<?> clsStatus = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl);
            Object st = XposedHelpers.newInstance(clsStatus, 0);
            XposedHelpers.setIntField(st, "status", 16);
            XposedHelpers.callMethod(dashboardMgr, "a", st);
            
            sendJavaBroadcast("⚡ 激活信号已发");
            // 启动心跳保活
            new Timer().schedule(new TimerTask() { public void run() { triggerSwitch(); } }, 1000, 1000);
        } catch (Throwable t) {}
    }

    private void registerSysReceiver(Context ctx) {
        IntentFilter filter = new IntentFilter("XSF_ACTION_START_CAST");
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                startActivation();
            }
        }, filter);
    }

    private void sendJavaBroadcast(String log) { if (sysContext == null) return; new Thread(() -> { try { Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE"); i.setPackage(PKG_SELF); i.putExtra("log", log); i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES); try { Object userAll = XposedHelpers.getStaticObjectField(UserHandle.class, "ALL"); Method method = Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class); method.invoke(sysContext, i, userAll); } catch (Throwable t) { sysContext.sendBroadcast(i); } } catch (Throwable t) {} }).start(); }
}