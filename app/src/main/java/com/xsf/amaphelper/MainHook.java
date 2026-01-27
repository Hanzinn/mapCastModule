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
import java.lang.reflect.Method; // 🟢 补上了这个关键引用
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
    
    // 🎯 核心伪造目标：官方高德 7.5 的包名
    private static final String TARGET_PKG = "com.autonavi.amapauto"; 

    private static final String CLASS_DASHBOARD_MGR = "ecarx.naviservice.a.a";
    private static final String FIELD_INSTANCE = "b";
    
    private static final String CLASS_MAP_CONFIG_WRAPPER = "ecarx.naviservice.map.ce"; 
    private static final String CLASS_MAP_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLASS_MAP_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLASS_MAP_SWITCH_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

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
    
    private static class SwitchState {
        static final int CRUISE_TO_GUIDE = 3; 
        static final int GUIDE_TO_CRUISE = 2; 
    }

    private static String curRoadName = "等待数据";
    private static String nextRoadName = "V128身份全覆盖";
    private static int turnIcon = 2; 
    private static int segmentDis = 888;
    private static int routeRemainDis = 2000;
    private static int routeRemainTime = 600;
    
    private static int currentVendor = 0; // 锁定 V0
    private static int fakeOldVendor = 5; // 用于制造差异
    
    private static Object dashboardManagerInstance = null;
    private static Class<?> mapGuideInfoClass = null; 
    private static Class<?> mapStatusInfoClass = null;
    private static Class<?> mapSwitchInfoClass = null;
    
    private static boolean isHookReady = false;
    private static boolean isHandshaking = false;
    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Timer heartbeatTimer = null;
    private static Set<String> hookedConfigClasses = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V128 全链路身份伪造版启动");
        
        initLBSHook(lpparam);
        setupDynamicJailbreak(lpparam.classLoader);
        hookNaviBaseModel(lpparam.classLoader);
    }

    // 🟢 核心 1：通用盖章机 (Identity Stamper)
    // 无论发什么对象，先调用这个方法填上包名
    private void stampIdentity(Object infoObj) {
        if (infoObj == null) return;
        try {
            // 1. 尝试直接设置字段 (MapGuideInfo, MapStatusInfo 可能直接有这些字段)
            try { XposedHelpers.setObjectField(infoObj, "packageName", TARGET_PKG); } catch (Throwable t) {}
            try { XposedHelpers.setObjectField(infoObj, "protocolVersion", "10"); } catch (Throwable t) {} // 7.5 日志里的版本
            
            // 2. 尝试设置父类或 base 字段 (如果它们继承自 NaviBaseModel)
            // 很多时候 infoObj.base 才是重点
            try {
                Object baseModel = XposedHelpers.getObjectField(infoObj, "base");
                if (baseModel != null) {
                    XposedHelpers.setObjectField(baseModel, "packageName", TARGET_PKG);
                    XposedHelpers.setObjectField(baseModel, "mapVendor", String.valueOf(currentVendor));
                }
            } catch (Throwable t) {}
            
        } catch (Throwable t) {
            // 忽略错误，尽力而为
        }
    }

    private void setupDynamicJailbreak(ClassLoader cl) {
        try {
            Class<?> wrapperClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG_WRAPPER, cl);
            if (wrapperClass != null) {
                XposedHelpers.findAndHookMethod(wrapperClass, "a", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object concreteConfig = param.getResult(); 
                        if (concreteConfig != null) hookConcreteConfigClass(concreteConfig.getClass());
                    }
                });
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
                    return currentVendor; 
                }
            });
            hookedConfigClasses.add(className);
            XposedBridge.log("NaviHook: 🔓 越狱成功: " + className);
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
                    mainHandler.postDelayed(() -> captureCoreObjects(lpparam.classLoader), 5000);
                }
            });
        } catch (Throwable t) {}
    }

    private void hookNaviBaseModel(ClassLoader cl) {
        try {
            Class<?> baseModelClass = XposedHelpers.findClassIfExists(CLASS_NAVI_BASE_MODEL, cl);
            if (baseModelClass != null) {
                XposedHelpers.findAndHookMethod(baseModelClass, "getPackageName", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return TARGET_PKG; 
                    }
                });
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
                            Bundle b = intent.getExtras();
                            if (b != null) {
                                extractData(b);
                                if (isHookReady) updateClusterDirectly(); 
                            }
                        }
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                             currentVendor = 0; 
                             sendJavaBroadcast("🔄 强制重连 -> V0");
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
        if (heartbeatTimer != null) { heartbeatTimer.cancel(); heartbeatTimer = null; }
        
        mainHandler.postDelayed(() -> {
            if (isHandshaking) { isHandshaking = false; isHookReady = true; }
        }, 10000);
        
        runHandshakeSequence();
    }

    private void runHandshakeSequence() {
        final int STEP_DELAY = 200;
        final int MODE_SWITCH_DELAY = 500;

        mainHandler.post(() -> {
            try {
                // 1. 差异切换 (5->0) + 身份盖章
                injectSwitch(fakeOldVendor, currentVendor, SwitchState.CRUISE_TO_GUIDE);
                sendJavaBroadcast("⚡ [0/7] 身份伪造切换: V5 -> V0");

                mainHandler.postDelayed(() -> {
                    // 2. 状态机握手 (全程盖章)
                    injectStatus(Status.APP_START);
                    sendJavaBroadcast("⚡ [1/7] APP启动(7)");

                    mainHandler.postDelayed(() -> {
                        injectStatus(Status.APP_START_FINISH);
                        sendJavaBroadcast("⚡ [2/7] 启动完成(8)");

                        mainHandler.postDelayed(() -> {
                            injectStatus(Status.APP_ACTIVE);
                            sendJavaBroadcast("⚡ [3/7] APP活跃(12)");
                            
                            mainHandler.postDelayed(() -> {
                                injectStatus(Status.ROUTE_START);
                                sendJavaBroadcast("⚡ [4/7] 路径计算(13)");
                                
                                mainHandler.postDelayed(() -> {
                                    injectStatus(Status.ROUTE_SUCCESS);
                                    sendJavaBroadcast("⚡ [5/7] 计算成功(14)");
                                    
                                    mainHandler.postDelayed(() -> {
                                        injectStatus(Status.GUIDE_START);
                                        sendJavaBroadcast("⚡ [6/7] 导航开始(16) -> ✅");
                                        
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

    private void startHeartbeat() {
        if (heartbeatTimer != null) heartbeatTimer.cancel();
        heartbeatTimer = new Timer();
        segmentDis = 888;
        
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (dashboardManagerInstance == null) return;
                mainHandler.post(() -> {
                    if (isHookReady) updateClusterDirectly();
                });
            }
        }, 0, 2000); 
    }

    private void stopProjection() {
        if (heartbeatTimer != null) { heartbeatTimer.cancel(); heartbeatTimer = null; }
        if (dashboardManagerInstance == null || mainHandler == null) return;
        try {
            clearClusterData();
            mainHandler.postDelayed(() -> {
                injectStatus(Status.GUIDE_STOP);
                sendJavaBroadcast("🛑 导航已停止");
                mainHandler.postDelayed(() -> {
                    injectSwitch(currentVendor, fakeOldVendor, SwitchState.GUIDE_TO_CRUISE);
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

    private void injectSwitch(int oldV, int newV, int state) {
        try {
            Object switchInfo = XposedHelpers.newInstance(mapSwitchInfoClass, oldV, newV);
            XposedHelpers.setIntField(switchInfo, "mSwitchState", state);
            stampIdentity(switchInfo); // 🔥 盖章！
            XposedHelpers.callMethod(dashboardManagerInstance, "a", switchInfo);
        } catch (Throwable t) {}
    }

    private void injectStatus(int status) {
        try {
            Object statusInfo = XposedHelpers.newInstance(mapStatusInfoClass, currentVendor);
            XposedHelpers.setIntField(statusInfo, "status", status);
            stampIdentity(statusInfo); // 🔥 盖章！
            XposedHelpers.callMethod(dashboardManagerInstance, "a", statusInfo);
        } catch (Throwable t) {}
    }

    private void updateClusterDirectly() {
        try {
            Object guideInfo = XposedHelpers.newInstance(mapGuideInfoClass, currentVendor);

            XposedHelpers.setObjectField(guideInfo, "curRoadName", curRoadName); 
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", nextRoadName);
            
            XposedHelpers.setIntField(guideInfo, "turnId", turnIcon); 
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", segmentDis);
            XposedHelpers.setIntField(guideInfo, "remainDistance", routeRemainDis);
            XposedHelpers.setIntField(guideInfo, "remainTime", routeRemainTime);
            
            XposedHelpers.setIntField(guideInfo, "guideType", 1); 
            
            try { XposedHelpers.setIntField(guideInfo, "roadType", 1); } catch (Throwable t) {} 
            try { XposedHelpers.setIntField(guideInfo, "cameraDistance", 0); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "cameraSpeed", 0); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "cameraType", 0); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "sapaType", 0); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "sapaDistance", 0); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "trafficLightIcon", 0); } catch (Throwable t) {}
            try { XposedHelpers.setBooleanField(guideInfo, "isCustomTBTEnabled", true); } catch (Throwable t) {}
            
            stampIdentity(guideInfo); // 🔥 盖章！
            
            XposedHelpers.callMethod(dashboardManagerInstance, "a", guideInfo);
            sendJavaBroadcast("💉 V128: [V0][STAMPED][Dis:" + segmentDis + "]");
        } catch (Throwable t) {
            sendJavaBroadcast("❌ 注入失败: " + t.getMessage());
        }
    }
    
    private void clearClusterData() {
        try {
            Object guideInfo = XposedHelpers.newInstance(mapGuideInfoClass, currentVendor);
            XposedHelpers.setObjectField(guideInfo, "curRoadName", "");
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", "");
            XposedHelpers.setIntField(guideInfo, "turnId", 0);
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", 0);
            XposedHelpers.setIntField(guideInfo, "guideType", 0);
            stampIdentity(guideInfo);
            XposedHelpers.callMethod(dashboardManagerInstance, "a", guideInfo);
        } catch (Throwable t) {}
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
    
    private void extractData(Bundle b) {
        try {
            if (b.containsKey("CUR_ROAD_NAME")) curRoadName = b.getString("CUR_ROAD_NAME");
            if (b.containsKey("NEXT_ROAD_NAME")) nextRoadName = b.getString("NEXT_ROAD_NAME");
            segmentDis = getInt(b, "SEG_REMAIN_DIS", "seg_remain_dis");
            turnIcon = getInt(b, "ICON", "icon");
            if (turnIcon == 0 && b.containsKey("NAV_ICON")) turnIcon = b.getInt("NAV_ICON");
            routeRemainDis = getInt(b, "ROUTE_REMAIN_DIS", "route_remain_dis");
            routeRemainTime = getInt(b, "ROUTE_REMAIN_TIME", "route_remain_time");
            if (curRoadName == null) curRoadName = "当前道路";
        } catch (Exception e) {}
    }
    
    private int getInt(Bundle b, String k1, String k2) {
        int v = b.getInt(k1, -1);
        if (v == -1) v = b.getInt(k2, -1);
        return (v == -1) ? 0 : v;
    }
}