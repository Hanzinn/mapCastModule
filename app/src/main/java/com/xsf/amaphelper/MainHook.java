package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
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
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // 目标：9.1 真实存在的 Service (特洛伊木马宿主)
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
        // 自激活模块
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // =============================================================
        // 🏰 战场 A：高德地图 9.1 (埋下特洛伊木马)
        // =============================================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            XposedBridge.log("NaviHook: [Map] 正在注入高德地图进程...");
            
            try {
                // 1. Hook onBind：偷梁换柱，返回我们的 TrojanBinder
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("NaviHook: [Map] 🚨 系统正在尝试连接 AutoSimilarWidgetService！");
                        XposedBridge.log("NaviHook: [Map] 🛡️ 拦截成功，正在返回 TrojanBinder...");
                        // 返回这里的 TrojanBinder，它在 Map 进程运行
                        param.setResult(new TrojanBinder(lpparam.classLoader)); 
                    }
                });
                
                // 2. Hook onCreate：防止 9.1 原生代码报错 (如果缺类)
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log("NaviHook: [Map] Service onCreate 被调用 (已保护)");
                    }
                });

                // 3. 拿到 Context 备用
                XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        mapContext = (Context) param.thisObject;
                        XposedBridge.log("NaviHook: [Map] Application Context 获取成功");
                    }
                });

            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] Hook 初始化失败: " + t);
            }
        }

        // =============================================================
        // 🚗 战场 B：车机系统 (发起进攻)
        // =============================================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedBridge.log("NaviHook: [Sys] 正在注入车机系统进程...");
            
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    
                    registerSysReceiver(sysContext);
                    
                    XposedBridge.log("NaviHook: [Sys] 系统侧准备就绪，3秒后检查环境...");
                    sysHandler.postDelayed(() -> checkEnvironment(lpparam.classLoader), 3000);
                }
            });
            
            // 破解 Vendor 切换校验
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) {
                    XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
                    XposedBridge.log("NaviHook: [Sys] Vendor 校验破解成功");
                }
            } catch (Throwable t) {}
        }
    }

    // =============================================================
    // 🦄 特洛伊 Binder (运行在高德进程，直接操作引擎)
    // =============================================================
    public static class TrojanBinder extends Binder {
        private ClassLoader classLoader;
        
        public TrojanBinder(ClassLoader cl) {
            this.classLoader = cl;
            XposedBridge.log("NaviHook: [Map-Binder] TrojanBinder 已实例化，等待指令...");
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                // Code 43: addSurface (系统送画布来了)
                if (code == 43) {
                    XposedBridge.log("NaviHook: [Map-Binder] 🔥🔥🔥 收到 addSurface 指令 (Code 43)！");
                    
                    data.setDataPosition(0);
                    try { 
                        String token = data.readString();
                        XposedBridge.log("NaviHook: [Map-Binder] Token: " + token);
                    } catch(Exception e){} // Skip Token
                    
                    if (data.readInt() != 0) {
                        Surface surface = Surface.CREATOR.createFromParcel(data);
                        if (surface != null) {
                            XposedBridge.log("NaviHook: [Map-Binder] ✅ 成功解析 Surface对象: " + surface);
                            XposedBridge.log("NaviHook: [Map-Binder] 🚀 正在尝试注入 Native 引擎...");
                            // 🔥 就在这里，直接调用 Native 引擎！
                            injectNativeEngine(classLoader, surface);
                        } else {
                            XposedBridge.log("NaviHook: [Map-Binder] ❌ Surface 解析为 null");
                        }
                    } else {
                        XposedBridge.log("NaviHook: [Map-Binder] ❌ 数据包中没有 Surface");
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                // Code 20: Register (握手)
                if (code == 20) {
                    XposedBridge.log("NaviHook: [Map-Binder] 收到 Register 指令 (Code 20) - 握手成功");
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                XposedBridge.log("NaviHook: [Map-Binder] 收到未知指令 Code: " + code);
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map-Binder] 处理事务异常: " + t);
                t.printStackTrace();
            }
            return true; // 吞掉所有异常，防止系统崩溃
        }
        
        private void injectNativeEngine(ClassLoader cl, Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", cl);
                XposedBridge.log("NaviHook: [Map] 找到 MapSurfaceView 类");
                
                // 查找方法
                Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                XposedBridge.log("NaviHook: [Map] 找到 nativeSurfaceCreated 方法");
                
                // 执行调用
                // 参数含义：1=仪表盘DisplayId, 2=Type(盲猜是SURFACE_TYPE), surface
                m.invoke(null, 1, 2, surface); 
                
                XposedBridge.log("NaviHook: [Map] ✅✅✅ 引擎注入调用完成！屏幕应该亮了！");
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] ❌ 引擎注入失败: " + t);
                // 尝试备用方案：打印所有方法，看看有没有改名
                try {
                    Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", cl);
                    for (Method method : cls.getDeclaredMethods()) {
                        XposedBridge.log("NaviHook: [Map] 备选方法: " + method.getName() + " Args: " + Arrays.toString(method.getParameterTypes()));
                    }
                } catch(Exception e) {}
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
                sendJavaBroadcast("⚠️ [Sys] 发现 7.5 原生活动连接，插件进入防冲突休眠模式");
            } else {
                isLegacy75 = false;
                sendJavaBroadcast("⚡ [Sys] 环境空闲，9.1 激活模式就绪");
                // 自动触发连接
                startActivation();
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] 环境检查错误: " + t);
        }
    }

    private void startActivation() {
        if (isLegacy75 || sysContext == null) return;
        
        sysHandler.post(() -> {
            try {
                sendJavaBroadcast("🚀 [1/3] 系统发起连接请求 (Target: " + TARGET_SERVICE + ")...");
                Intent intent = new Intent();
                // 🔥 直接连接 9.1 的这个 Service
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                
                // 系统会去 Bind 9.1，9.1 的 onBind 会被我们 Hook，返回 TrojanBinder
                boolean bound = sysContext.bindService(intent, new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder service) {
                        sendJavaBroadcast("🔗 [2/3] 物理连接建立! 准备注入内部逻辑...");
                        // 这里的 service 就是我们的 TrojanBinder
                        injectToSystem(service);
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        sendJavaBroadcast("❌ 物理连接意外断开");
                    }
                }, Context.BIND_AUTO_CREATE);
                
                if (!bound) {
                    sendJavaBroadcast("❌ [Sys] BindService 返回 false！请检查 9.1 是否安装");
                }
                
            } catch (Throwable t) {
                sendJavaBroadcast("❌ [Sys] 启动失败: " + t);
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
                
                sendJavaBroadcast("💉 [3/3] FakeBinder 注入完毕，诱导系统发送 Surface...");
                sysHandler.postDelayed(() -> triggerSwitch(), 500);
            } else {
                sendJavaBroadcast("❌ [Sys] 内部 Connection 对象为空");
            }
        } catch (Throwable t) {
            sendJavaBroadcast("❌ [Sys] 注入系统失败: " + t);
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
            
            sendJavaBroadcast("⚡ [Sys] 激活信号已发送 (Switch 3 / Status 16)");
            
            // 启动心跳保活
            new Timer().schedule(new TimerTask() { public void run() { triggerSwitch(); } }, 2000, 2000);
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] 触发切换失败: " + t);
        }
    }

    private void registerSysReceiver(Context ctx) {
        IntentFilter filter = new IntentFilter("XSF_ACTION_START_CAST");
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                sendJavaBroadcast("🕹️ 收到手动指令，强制重连...");
                startActivation();
            }
        }, filter);
    }

    // 使用反射获取 UserHandle，兼容所有 Android 版本
    private void sendJavaBroadcast(String log) {
        XposedBridge.log("NaviHook: " + log); // 同时打印到 Xposed 日志
        
        if (sysContext == null) return;
        new Thread(() -> {
            try {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.setPackage(PKG_SELF);
                i.putExtra("log", log);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                try {
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