package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    private static final String CLASS_DASHBOARD_MGR = "ecarx.naviservice.a.a";
    private static final String FIELD_INSTANCE = "b";
    
    private static final String CLASS_MAP_CONFIG_BASE = "ecarx.naviservice.map.co"; 
    private static final String CLASS_MAP_CONFIG_WRAPPER = "ecarx.naviservice.map.ce"; 

    private static final String CLASS_MAP_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLASS_MAP_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLASS_MAP_SWITCH_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

    // 状态常量
    private static class Status {
        static final int APP_START = 7;
        static final int APP_START_FINISH = 8;
        static final int APP_ACTIVE = 12;
        static final int ROUTE_START = 13;
        static final int ROUTE_SUCCESS = 14;
        static final int GUIDE_START = 16;
        static final int GUIDE_STOP = 17;
        static final int APP_FINISH = 9;
    }
    
    private static class NavMode {
        static final int CRUISE = 0; 
        static final int GUIDE = 1;  
    }

    private static class SwitchState {
        static final int CRUISE_TO_GUIDE = 3; 
        static final int GUIDE_TO_CRUISE = 2; 
    }

    // 🟢 初始数据：改成左转图标 (2)，确保显眼
    private static String curRoadName = "测试道路";
    private static String nextRoadName = "V116饱和攻击";
    private static int turnIcon = 2; // 左转 (比直行更显眼)
    private static int segmentDis = 888; // 初始距离
    private static int routeRemainDis = 2000;
    private static int routeRemainTime = 600;
    
    // 锁定 V5 (地头蛇通道)
    private static int currentVendor = 5; 
    
    private static Object dashboardManagerInstance = null;
    private static Class<?> mapGuideInfoClass = null; 
    private static Class<?> mapStatusInfoClass = null;
    private static Class<?> mapSwitchInfoClass = null;
    
    private static boolean isHookReady = false;
    private static boolean isHandshaking = false;
    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Timer heartbeatTimer = null; // 💓 心跳定时器
    
    private static Set<String> hookedConfigClasses = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V116 饱和攻击版启动");
        
        initLBSHook(lpparam);
        hookAbstractBaseClass(lpparam.classLoader);
        setupDynamicJailbreak(lpparam.classLoader);
        hookNaviBaseModel(lpparam.classLoader);
    }

    private void hookAbstractBaseClass(ClassLoader cl) {
        try {
            Class<?> baseClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG_BASE, cl);
            if (baseClass != null) {
                XposedHelpers.findAndHookMethod(baseClass, "g", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) { return true; }
                });
                XposedBridge.log("NaviHook: 🔓 父类 co.g() 已破解");
            }
        } catch (Throwable t) {}
    }

    private void setupDynamicJailbreak(ClassLoader cl) {
        try {
            Class<?> wrapperClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG_WRAPPER, cl);
            if (wrapperClass != null) {
                XposedHelpers.findAndHookMethod(wrapperClass, "a", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object concreteConfig = param.getResult(); 
                        if (concreteConfig != null) {
                            hookConcreteConfigClass(concreteConfig.getClass());
                        }
                    }
                });
                XposedBridge.log("NaviHook: 🪤 动态抓捕已就绪");
            }
        } catch (Throwable t) {}
    }

    private void hookConcreteConfigClass(Class<?> realClass) {
        String className = realClass.getName();
        if (hookedConfigClasses.contains(className)) return; 
        try {
            XposedHelpers.findAndHookMethod(realClass, "b", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return currentVendor; // 动态返回当前选择的Vendor
                }
            });
            hookedConfigClasses.add(className);
            XposedBridge.log("NaviHook: 🔓 越狱成功: " + className);
            sendJavaBroadcast("🔓 越狱成功: " + className);
        } catch (Throwable t) {}
    }

    private void initLBSHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    systemContext = context;
                    mainHandler = new Handler(Looper.getMainLooper());
                    registerReceiver(context);
                    
                    sendJavaBroadcast("STATUS_SERVICE_RUNNING");
                    
                    mainHandler.postDelayed(() -> {
                         captureCoreObjects(lpparam.classLoader);
                    }, 5000);
                }
            });
        } catch (Throwable t) {}
    }

    private void hookNaviBaseModel(ClassLoader cl) {
        try {
            Class<?> baseModelClass = XposedHelpers.findClassIfExists(CLASS_NAVI_BASE_MODEL, cl);
            if (baseModelClass != null) {
                XposedHelpers.findAndHookMethod(baseModelClass, "getMapVendor", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return currentVendor; 
                    }
                });
            }
        } catch (Throwable t) {}
    }

    private void captureCoreObjects(ClassLoader cl) {
        try {
            try {
                Class<?> wrapperClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG_WRAPPER, cl);
                if (wrapperClass != null) {
                    Object config = XposedHelpers.callStaticMethod(wrapperClass, "a");
                    if (config != null) hookConcreteConfigClass(config.getClass());
                }
            } catch (Throwable t) {}

            mapGuideInfoClass = XposedHelpers.findClassIfExists(CLASS_MAP_GUIDE_INFO, cl);
            mapStatusInfoClass = XposedHelpers.findClassIfExists(CLASS_MAP_STATUS_INFO, cl);
            mapSwitchInfoClass = XposedHelpers.findClassIfExists(CLASS_MAP_SWITCH_INFO, cl);
            
            if (mapGuideInfoClass == null || mapStatusInfoClass == null) {
                sendJavaBroadcast("❌ 内部类加载失败");
                return;
            }

            Class<?> mgrClass = XposedHelpers.findClass(CLASS_DASHBOARD_MGR, cl);
            Field instanceField = XposedHelpers.findField(mgrClass, FIELD_INSTANCE);
            instanceField.setAccessible(true);
            dashboardManagerInstance = instanceField.get(null);
            
            if (dashboardManagerInstance != null) {
                XposedBridge.log("NaviHook: 🎉 捕获成功!");
                sendJavaBroadcast("STATUS_SERVICE_RUNNING");
                sendJavaBroadcast("STATUS_IPC_CONNECTED");
                
                performLifecycleHandshake();
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: 捕获异常: " + t);
        }
    }

    private void registerReceiver(final Context context) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        String action = intent.getAction();
                        if (AMAP_ACTION.equals(action)) {
                            // 接收高德真实数据，但我们暂时忽略，使用心跳数据覆盖
                            // Bundle b = intent.getExtras();
                            // extractData(b);
                        }
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                             currentVendor = intent.getIntExtra("vendor", 0);
                             sendJavaBroadcast("🔄 切换 -> V" + currentVendor);
                             performLifecycleHandshake(); 
                        }
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            captureCoreObjects(context.getClassLoader());
                            performLifecycleHandshake();
                        }
                        else if ("XSF_ACTION_STOP".equals(action)) {
                            stopProjection();
                        }
                    } catch (Throwable t) {}
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION);
            filter.addAction("XSF_ACTION_SET_VENDOR");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            filter.addAction("XSF_ACTION_STOP");
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    private void performLifecycleHandshake() {
        if (dashboardManagerInstance == null || isHandshaking || mainHandler == null) return;
        isHandshaking = true; 
        isHookReady = false;
        
        // 停止之前的倒计时
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
        
        mainHandler.postDelayed(() -> {
            if (isHandshaking) {
                isHandshaking = false;
                isHookReady = true; 
            }
        }, 10000);
        
        runHandshakeSequence();
    }

    private void runHandshakeSequence() {
        final int STEP_DELAY = 200;
        final int MODE_SWITCH_DELAY = 500;

        mainHandler.post(() -> {
            try {
                // 1. 模式唤醒: CRUISE -> GUIDE (0->1)
                injectSwitch(NavMode.CRUISE, NavMode.GUIDE, SwitchState.CRUISE_TO_GUIDE);
                sendJavaBroadcast("⚡ [0/7] 模式唤醒: V" + currentVendor);

                mainHandler.postDelayed(() -> {
                    // 2. APP_START
                    injectStatus(Status.APP_START);
                    sendJavaBroadcast("⚡ [1/7] APP启动(7)");

                    mainHandler.postDelayed(() -> {
                        // 3. APP_START_FINISH
                        injectStatus(Status.APP_START_FINISH);
                        sendJavaBroadcast("⚡ [2/7] 启动完成(8)");

                        mainHandler.postDelayed(() -> {
                            // 4. APP_ACTIVE
                            injectStatus(Status.APP_ACTIVE);
                            sendJavaBroadcast("⚡ [3/7] APP活跃(12)");
                            
                            mainHandler.postDelayed(() -> {
                                // 5. ROUTE_START
                                injectStatus(Status.ROUTE_START);
                                sendJavaBroadcast("⚡ [4/7] 路径计算(13)");
                                
                                mainHandler.postDelayed(() -> {
                                    // 6. ROUTE_SUCCESS
                                    injectStatus(Status.ROUTE_SUCCESS);
                                    sendJavaBroadcast("⚡ [5/7] 计算成功(14)");
                                    
                                    mainHandler.postDelayed(() -> {
                                        // 7. GUIDE_START
                                        injectStatus(Status.GUIDE_START);
                                        sendJavaBroadcast("⚡ [6/7] 导航开始(16) -> ✅");
                                        
                                        // 🔥 开始心跳数据泵
                                        startHeartbeat();
                                        
                                        isHandshaking = false;
                                        isHookReady = true;
                                    }, STEP_DELAY);
                                }, STEP_DELAY);
                            }, STEP_DELAY);
                        }, STEP_DELAY);
                    }, STEP_DELAY);
                }, MODE_SWITCH_DELAY);
            } catch (Throwable t) {
                isHandshaking = false;
                sendJavaBroadcast("❌ 握手异常: " + t.getMessage());
            }
        });
    }

    // 💓 心跳数据泵：每秒发一次变化的距离
    private void startHeartbeat() {
        if (heartbeatTimer != null) heartbeatTimer.cancel();
        heartbeatTimer = new Timer();
        segmentDis = 888; // 重置距离
        
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (dashboardManagerInstance == null) return;
                
                // 距离倒数，模拟车辆行驶
                segmentDis -= 10;
                if (segmentDis < 0) segmentDis = 1000;
                
                // 回到主线程发送
                mainHandler.post(() -> {
                    updateClusterDirectly();
                });
            }
        }, 0, 1000); // 每1秒更新一次
    }

    private void stopProjection() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
        
        if (dashboardManagerInstance == null || mainHandler == null) return;
        try {
            clearClusterData();
            mainHandler.postDelayed(() -> {
                injectStatus(Status.GUIDE_STOP);
                sendJavaBroadcast("🛑 导航已停止");
                mainHandler.postDelayed(() -> {
                    injectSwitch(NavMode.GUIDE, NavMode.CRUISE, SwitchState.GUIDE_TO_CRUISE);
                    sendJavaBroadcast("🛑 切回巡航");
                    mainHandler.postDelayed(() -> {
                        injectStatus(Status.APP_FINISH);
                        isHookReady = false;
                        sendJavaBroadcast("🛑 应用退出 -> 📴");
                    }, 300);
                }, 300);
            }, 100);
        } catch (Throwable t) {}
    }

    private void injectSwitch(int oldMode, int newMode, int state) {
        try {
            if (mapSwitchInfoClass == null) return;
            Object switchInfo = XposedHelpers.newInstance(mapSwitchInfoClass, oldMode, newMode);
            XposedHelpers.setIntField(switchInfo, "mSwitchState", state);
            XposedHelpers.callMethod(dashboardManagerInstance, "a", switchInfo);
        } catch (Throwable t) {}
    }

    private void clearClusterData() {
        try {
            Object guideInfo = XposedHelpers.newInstance(mapGuideInfoClass, currentVendor);
            XposedHelpers.setObjectField(guideInfo, "curRoadName", "");
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", "");
            XposedHelpers.setIntField(guideInfo, "turnId", 0);
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", 0);
            XposedHelpers.setIntField(guideInfo, "guideType", 0);
            XposedHelpers.callMethod(dashboardManagerInstance, "a", guideInfo);
        } catch (Throwable t) {}
    }

    private void injectStatus(int status) {
        try {
            Object statusInfo = XposedHelpers.newInstance(mapStatusInfoClass, currentVendor);
            XposedHelpers.setIntField(statusInfo, "status", status);
            XposedHelpers.callMethod(dashboardManagerInstance, "a", statusInfo);
        } catch (Throwable t) {}
    }

    // 🟢 核心：饱和攻击数据包
    private void updateClusterDirectly() {
        if (dashboardManagerInstance == null || mapGuideInfoClass == null) return;
        
        try {
            // 使用当前选定的 Vendor (0, 4, 5, 10...)
            Object guideInfo = XposedHelpers.newInstance(mapGuideInfoClass, currentVendor);

            // 1. 必填字段
            XposedHelpers.setObjectField(guideInfo, "curRoadName", curRoadName); 
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", nextRoadName);
            
            // 2. 核心动态字段
            XposedHelpers.setIntField(guideInfo, "turnId", turnIcon); // 2=左转
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", segmentDis); // 倒数
            XposedHelpers.setIntField(guideInfo, "remainDistance", routeRemainDis);
            XposedHelpers.setIntField(guideInfo, "remainTime", routeRemainTime);
            
            // 3. 🔥 饱和填充：把以前没填的都填上！
            XposedHelpers.setIntField(guideInfo, "guideType", 1); // 1=TBT引导
            try { XposedHelpers.setIntField(guideInfo, "roadType", 1); } catch (Throwable t) {}
            try { XposedHelpers.setBooleanField(guideInfo, "isCustomTBTEnabled", true); } catch (Throwable t) {}
            // 如果有 trafficLightIcon 字段，也填上
            try { XposedHelpers.setIntField(guideInfo, "trafficLightIcon", 0); } catch (Throwable t) {}
            
            XposedHelpers.callMethod(dashboardManagerInstance, "a", guideInfo);

            sendJavaBroadcast("💉 V116: [V" + currentVendor + "][Icon:" + turnIcon + "][Dis:" + segmentDis + "]");

        } catch (Throwable t) {
            sendJavaBroadcast("❌ 注入异常: " + t.getMessage());
        }
    }
    
    private void sendJavaBroadcast(String log) {
        if (systemContext == null) return;
        new Thread(() -> {
            try {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.setPackage(PKG_SELF); 
                i.putExtra("log", log);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND); 
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