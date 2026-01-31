package com.xsf.amaphelper;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.BroadcastReceiver;
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

    // 目标：AutoSimilarWidgetService (9.1和7.5都有，但9.1需要我们激活)
    private static final String TARGET_SERVICE = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    // 7.5 独有的类，用于区分版本
    private static final String LEGACY_CLASS_CHECK = "com.AutoHelper";
    
    private static final String PKG_MAP = "com.autonavi.amapauto";
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";

    // 系统侧变量
    private static Context sysContext;
    private static Handler sysHandler;
    private static Object dashboardMgr;
    private static boolean is75Environment = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // =============================================================
        // 🏰 战场 A：高德地图 (区分 7.5 和 9.1)
        // =============================================================
        if (lpparam.packageName.equals(PKG_MAP)) {
            // 🔍 步骤 1：指纹识别
            boolean is75 = XposedHelpers.findClassIfExists(LEGACY_CLASS_CHECK, lpparam.classLoader) != null;
            
            if (is75) {
                XposedBridge.log("NaviHook: [Map] ⚠️ 检测到 7.5 (发现 AutoHelper)，插件进入静默模式！");
                XposedBridge.log("NaviHook: [Map] 不执行任何 Hook，解决闪烁问题。");
                return; // 🔥 7.5 直接退出，不再干扰原生逻辑
            }

            XposedBridge.log("NaviHook: [Map] ✅ 检测到 9.1 (无 AutoHelper)，启动特洛伊木马...");
            
            try {
                // Hook onBind：偷梁换柱
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onBind", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("NaviHook: [Map] 系统连接请求到达！返回 TrojanBinder...");
                        param.setResult(new TrojanBinder(lpparam.classLoader)); 
                    }
                });
                
                // 保护性 Hook onCreate
                XposedHelpers.findAndHookMethod(TARGET_SERVICE, lpparam.classLoader, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log("NaviHook: [Map] Service onCreate 保护");
                    }
                });

            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] Hook 异常: " + t);
            }
        }

        // =============================================================
        // 🚗 战场 B：车机系统 (连接管理)
        // =============================================================
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            XposedBridge.log("NaviHook: [Sys] 注入车机系统...");
            
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    sysContext = (Context) param.thisObject;
                    sysHandler = new Handler(Looper.getMainLooper());
                    
                    registerSysReceiver(sysContext);
                    
                    // 延时检查，给予系统和高德启动时间
                    sysHandler.postDelayed(() -> checkAndActivate(lpparam.classLoader), 5000);
                }
            });
            
            // 破解 Vendor 切换校验 (9.1必须)
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {}
        }
    }

    // =============================================================
    // 🦄 特洛伊 Binder (运行在高德 9.1 进程)
    // =============================================================
    public static class TrojanBinder extends Binder {
        private ClassLoader classLoader;
        
        public TrojanBinder(ClassLoader cl) {
            this.classLoader = cl;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                XposedBridge.log("NaviHook: [Map-Binder] 收到指令 Code: " + code);

                // 🔥 新增 Code 1 处理 (对应 9.1 日志中的调用)
                // 也要保留 Code 43 (addSurface) 以防万一
                if (code == 1 || code == 43) {
                    XposedBridge.log("NaviHook: [Map-Binder] 🎯 捕获 Surface 传输指令 (Code " + code + ")");
                    
                    data.setDataPosition(0);
                    // 尝试跳过 Interface Token
                    try { 
                        String token = data.readString();
                        XposedBridge.log("NaviHook: [Map-Binder] Token: " + token);
                    } catch(Exception e){}
                    
                    // 尝试读取 Surface
                    // 有些协议 Surface 不是第一个参数，这里做个简单的容错尝试
                    Surface surface = null;
                    if (data.dataAvail() > 0) {
                        try {
                            if (data.readInt() != 0) {
                                surface = Surface.CREATOR.createFromParcel(data);
                            }
                        } catch (Exception e) {
                            XposedBridge.log("NaviHook: [Map-Binder] 直接读取 Surface 失败，尝试偏移...");
                            // 如果直接读取失败，可以尝试回退并寻找 Parcelable 头 (暂略，通常第一个就是)
                        }
                    }

                    if (surface != null) {
                        XposedBridge.log("NaviHook: [Map-Binder] ✅ 成功获取 Surface: " + surface);
                        injectNativeEngine(classLoader, surface);
                    } else {
                        XposedBridge.log("NaviHook: [Map-Binder] ❌ 未能解析出 Surface");
                    }
                    
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
                if (code == 20) {
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map-Binder] Transact Error: " + t);
            }
            return true;
        }
        
        private void injectNativeEngine(ClassLoader cl, Surface surface) {
            try {
                Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", cl);
                // 1=仪表盘DisplayId, 2=Type
                Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
                m.invoke(null, 1, 2, surface); 
                XposedBridge.log("NaviHook: [Map] ✅✅✅ 引擎注入成功！亮屏！");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: [Map] 引擎注入失败: " + t);
            }
        }
    }

    // =============================================================
    // 📡 系统侧逻辑
    // =============================================================
    private void checkAndActivate(ClassLoader cl) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
            dashboardMgr = XposedHelpers.getStaticObjectField(mgrClass, "b");
            Object conn = XposedHelpers.getObjectField(dashboardMgr, "f");
            
            if (conn != null) {
                // 如果已有连接，我们假设它是 7.5 的原生连接
                // 为了保险，我们可以检查一下连接对象的类型，或者简单地信任它
                // 因为我们在 Map 侧已经针对 7.5 做了避让，所以这里 System 侧也应该避让
                XposedBridge.log("NaviHook: [Sys] ⚠️ 发现活动连接，判断为 7.5 模式，停止注入。");
                is75Environment = true;
            } else {
                XposedBridge.log("NaviHook: [Sys] ⚡ 无活动连接，判断为 9.1 模式，开始注入...");
                is75Environment = false;
                startActivation();
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] 环境检查错误: " + t);
        }
    }

    private void startActivation() {
        if (is75Environment || sysContext == null) return;
        
        sysHandler.post(() -> {
            try {
                // 这里我们去 Bind 9.1 的 AutoSimilarWidgetService
                // 虽然 9.1 Manifest 里有，但它可能不响应 Bind
                // 不过既然用户说 Manifest 有，我们就去连
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(PKG_MAP, TARGET_SERVICE));
                
                sendJavaBroadcast("🚀 [Sys] 连接 9.1 服务...");
                
                boolean bound = sysContext.bindService(intent, new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder service) {
                        sendJavaBroadcast("🔗 [Sys] 连接成功! 注入系统...");
                        injectToSystem(service);
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        sendJavaBroadcast("❌ [Sys] 断开连接");
                    }
                }, Context.BIND_AUTO_CREATE);
                
                if (!bound) sendJavaBroadcast("❌ [Sys] Bind 失败! 确认 9.1 安装且 Service 存在");
                
            } catch (Throwable t) {
                sendJavaBroadcast("❌ [Sys] 启动异常: " + t);
            }
        });
    }

    private void injectToSystem(IBinder binder) {
        try {
            Object internalConn = XposedHelpers.getObjectField(dashboardMgr, "f");
            if (internalConn != null) {
                ComponentName fakeCn = new ComponentName(PKG_MAP, TARGET_SERVICE);
                Method onConnected = internalConn.getClass().getMethod("onServiceConnected", ComponentName.class, IBinder.class);
                onConnected.invoke(internalConn, fakeCn, binder);
                
                sendJavaBroadcast("💉 [Sys] 注入完成，发送激活指令...");
                sysHandler.postDelayed(() -> triggerSwitch(), 500);
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] 注入系统失败: " + t);
        }
    }

    private void triggerSwitch() {
        try {
            ClassLoader cl = sysContext.getClassLoader();
            // Switch 5 -> 0 (Cruising -> Navi)
            Class<?> clsSwitch = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", cl);
            Object sw = XposedHelpers.newInstance(clsSwitch, 5, 0);
            XposedHelpers.setIntField(sw, "mSwitchState", 3);
            XposedHelpers.callMethod(dashboardMgr, "a", sw);
            
            // Status 16 (Guide)
            Class<?> clsStatus = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapStatusInfo", cl);
            Object st = XposedHelpers.newInstance(clsStatus, 0);
            XposedHelpers.setIntField(st, "status", 16);
            XposedHelpers.callMethod(dashboardMgr, "a", st);
            
            sendJavaBroadcast("⚡ [Sys] 激活指令已发");
            // 心跳保活 (防止系统重置状态)
            new Timer().schedule(new TimerTask() { public void run() { triggerSwitch(); } }, 2000, 2000);
        } catch (Throwable t) {}
    }

    private void registerSysReceiver(Context ctx) {
        IntentFilter filter = new IntentFilter("XSF_ACTION_START_CAST");
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                sendJavaBroadcast("🕹️ 手动触发...");
                startActivation();
            }
        }, filter);
    }

    private void sendJavaBroadcast(String log) {
        XposedBridge.log("NaviHook: " + log);
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