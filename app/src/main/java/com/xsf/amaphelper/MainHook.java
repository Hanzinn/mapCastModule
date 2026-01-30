package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    private static final String PKG_MAP = "com.autonavi.amapauto";
    
    // 9.1 真实存在的服务，用于保活
    private static final String REAL_HOST_SERVICE = "com.autonavi.amapauto.service.MapService";

    // 内部管理类
    private static final String CLASS_DASHBOARD_MGR = "ecarx.naviservice.a.a";
    private static final String FIELD_INSTANCE = "b";
    
    // 实体类
    private static final String CLASS_MAP_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLASS_MAP_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLASS_MAP_SWITCH_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLASS_MAP_CONFIG_BASE = "ecarx.naviservice.map.co"; 

    private static Object dashboardManagerInstance = null;
    private static Class<?> mapGuideInfoClass = null; 
    private static Class<?> mapStatusInfoClass = null;
    private static Class<?> mapSwitchInfoClass = null;
    
    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Timer heartbeatTimer = null;
    private static ServiceConnection keepAliveConnection;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V193 移花接木终极版启动 (Bind + Inject)");

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                
                // 1. 注册控制广播
                registerReceiver(systemContext);
                
                // 2. 延时捕获内部对象 (V126 逻辑)
                mainHandler.postDelayed(() -> captureCoreObjects(lpparam.classLoader), 3000);
                
                sendJavaBroadcast("⚡ V193 就绪");
            }
        });
        
        // 3. 破解配置 (V126 逻辑)
        hookConfig(lpparam.classLoader);
    }

    private void hookConfig(ClassLoader cl) {
        try {
            Class<?> baseClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG_BASE, cl);
            if (baseClass != null) {
                // 强制返回 true，允许切换
                XposedHelpers.findAndHookMethod(baseClass, "g", XC_MethodReplacement.returnConstant(true));
            }
            // 尝试 Hook 具体配置类，强制 Vendor 为 0
            Class<?> managerClass = XposedHelpers.findClassIfExists("ecarx.naviservice.map.cf", cl);
            if (managerClass != null) {
                XposedHelpers.findAndHookMethod(managerClass, "c", XC_MethodReplacement.returnConstant(0));
            }
        } catch (Throwable t) {}
    }

    private void captureCoreObjects(ClassLoader cl) {
        try {
            mapGuideInfoClass = XposedHelpers.findClassIfExists(CLASS_MAP_GUIDE_INFO, cl);
            mapStatusInfoClass = XposedHelpers.findClassIfExists(CLASS_MAP_STATUS_INFO, cl);
            mapSwitchInfoClass = XposedHelpers.findClassIfExists(CLASS_MAP_SWITCH_INFO, cl);
            
            Class<?> mgrClass = XposedHelpers.findClass(CLASS_DASHBOARD_MGR, cl);
            Field instanceField = XposedHelpers.findField(mgrClass, FIELD_INSTANCE);
            instanceField.setAccessible(true);
            dashboardManagerInstance = instanceField.get(null);
            
            if (dashboardManagerInstance != null) {
                XposedBridge.log("NaviHook: 🎉 内部管理器捕获成功!");
                sendJavaBroadcast("🎉 内部管理器捕获成功");
            } else {
                sendJavaBroadcast("❌ 管理器捕获失败");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: 捕获异常: " + t);
        }
    }

    // 🔥 核心操作入口
    private void startActivation() {
        if (systemContext == null) return;
        
        // 第一步：欺骗操作系统 (V188 逻辑)
        startManualBind();
        
        // 第二步：欺骗 DashboardManager (V126 逻辑)
        // 延时执行，等待 Bind 建立
        if (mainHandler != null) {
            mainHandler.postDelayed(() -> performLogicInjection(), 1000);
        }
    }

    // 🤜 动作 A: 手动 Bind 真实服务
    private void startManualBind() {
        mainHandler.post(() -> {
            try {
                sendJavaBroadcast("🚀 (1/2) 正在建立物理连接...");
                Intent realIntent = new Intent();
                realIntent.setComponent(new ComponentName(PKG_MAP, REAL_HOST_SERVICE));
                
                keepAliveConnection = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        sendJavaBroadcast("🔗 物理连接成功 (MapService)");
                        // 连接成功后，立即启动心跳
                        startHeartbeat();
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        sendJavaBroadcast("❌ 物理连接断开");
                    }
                };
                
                boolean bound = systemContext.bindService(realIntent, keepAliveConnection, Context.BIND_AUTO_CREATE);
                if (!bound) {
                    sendJavaBroadcast("⚠️ Bind返回false，检查高德是否运行");
                    // 即使 bind 失败，也强制尝试注入，死马当活马医
                    startHeartbeat();
                }
            } catch (Throwable t) {
                sendJavaBroadcast("❌ Bind 异常: " + t.getMessage());
            }
        });
    }

    // 💉 动作 B: 内存直注 (V126 核心)
    private void performLogicInjection() {
        if (dashboardManagerInstance == null) {
            sendJavaBroadcast("❌ 无法注入：管理器未捕获");
            return;
        }
        
        new Thread(() -> {
            try {
                sendJavaBroadcast("💉 (2/2) 开始逻辑注入...");
                
                // 1. 强制切换 V5 -> V0 (关键激活信号)
                injectSwitch(5, 0, 3); // 3 = CRUISE_TO_GUIDE
                sendJavaBroadcast("⚡ 发送切换指令: 5 -> 0");
                
                Thread.sleep(200);
                
                // 2. 发送状态序列 (模拟 App 启动)
                injectStatus(7); // APP_START
                Thread.sleep(50);
                injectStatus(8); // START_FINISH
                Thread.sleep(50);
                injectStatus(12); // ACTIVE
                Thread.sleep(50);
                
                // 3. 锁定导航状态 (保持亮屏)
                injectStatus(16); // GUIDE_START
                sendJavaBroadcast("⚡ 发送状态指令: GUIDE_START (16)");
                
                // 4. 发送初始数据 (填充画面)
                injectGuideInfo("V193 激活成功", "请查看仪表");

            } catch (Throwable t) {
                sendJavaBroadcast("❌ 注入异常: " + t.getMessage());
            }
        }).start();
    }

    // 💓 动作 C: 数据泵 (维持显示)
    private void startHeartbeat() {
        if (heartbeatTimer != null) heartbeatTimer.cancel();
        heartbeatTimer = new Timer();
        heartbeatTimer.schedule(new TimerTask() {
            int dis = 1000;
            @Override
            public void run() {
                // 每秒刷新一次数据
                injectGuideInfo("当前: V193移花接木", "剩余: " + dis + "米");
                // 每秒强调一次状态 (防止被重置)
                injectStatus(16);
                dis -= 10;
                if (dis < 0) dis = 1000;
            }
        }, 0, 1000);
    }

    // ⬇️ 反射注入工具方法 ⬇️

    private void injectSwitch(int oldV, int newV, int state) {
        try {
            Object obj = XposedHelpers.newInstance(mapSwitchInfoClass, oldV, newV);
            XposedHelpers.setIntField(obj, "mSwitchState", state);
            XposedHelpers.callMethod(dashboardManagerInstance, "a", obj);
        } catch (Throwable t) {}
    }

    private void injectStatus(int status) {
        try {
            Object obj = XposedHelpers.newInstance(mapStatusInfoClass, 0); // Vendor 0
            XposedHelpers.setIntField(obj, "status", status);
            XposedHelpers.callMethod(dashboardManagerInstance, "a", obj);
        } catch (Throwable t) {}
    }

    private void injectGuideInfo(String road, String nextRoad) {
        try {
            Object obj = XposedHelpers.newInstance(mapGuideInfoClass, 0); // Vendor 0
            
            // 填充基础字段
            XposedHelpers.setObjectField(obj, "curRoadName", road);
            XposedHelpers.setObjectField(obj, "nextRoadName", nextRoad);
            XposedHelpers.setIntField(obj, "turnId", 2);
            XposedHelpers.setIntField(obj, "nextTurnDistance", 500);
            XposedHelpers.setIntField(obj, "remainDistance", 2000);
            XposedHelpers.setIntField(obj, "remainTime", 100);
            
            // 🔥 关键参数：V126 经验
            XposedHelpers.setIntField(obj, "guideType", 1); // 1 = TBT模式
            try { XposedHelpers.setBooleanField(obj, "isCustomTBTEnabled", true); } catch (Throwable t) {}
            
            XposedHelpers.callMethod(dashboardManagerInstance, "a", obj);
        } catch (Throwable t) {}
    }

    private void registerReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if ("XSF_ACTION_START_CAST".equals(intent.getAction())) {
                    startActivation();
                }
            }
        };
        IntentFilter filter = new IntentFilter("XSF_ACTION_START_CAST");
        context.registerReceiver(receiver, filter);
    }

    private void sendJavaBroadcast(String log) {
        if (systemContext == null) return;
        new Thread(() -> {
            try {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.setPackage(PKG_SELF);
                i.putExtra("log", log);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                try {
                    Object userAll = XposedHelpers.getStaticObjectField(UserHandle.class, "ALL");
                    Method method = Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class);
                    method.invoke(systemContext, i, userAll);
                } catch (Throwable t) {
                    systemContext.sendBroadcast(i);
                }
            } catch (Throwable t) {}
        }).start();
    }
}