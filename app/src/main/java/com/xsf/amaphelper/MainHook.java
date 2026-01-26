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
    // 🟢 补全缺失的字段名
    private static final String FIELD_INSTANCE = "b";
    
    // 基础配置类 (抽象父类)
    private static final String CLASS_MAP_CONFIG_BASE = "ecarx.naviservice.map.co"; 
    // 配置包装类 (用于抓捕子类)
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
    
    private static class NavMode {
        static final int CRUISE = 0; 
        static final int GUIDE = 1;  
    }

    private static class SwitchState {
        static final int GUIDE_TO_GUIDE = 1;
        static final int GUIDE_TO_CRUISE = 2; 
        static final int CRUISE_TO_GUIDE = 3; 
    }

    private static String curRoadName = "等待数据";
    private static String nextRoadName = "V113分层版";
    private static int turnIcon = 4; 
    private static int segmentDis = 500;
    private static int routeRemainDis = 2000;
    private static int routeRemainTime = 600;
    
    // 默认欺骗 Vendor
    private static int currentVendor = 5; 
    
    private static Object dashboardManagerInstance = null;
    private static Class<?> mapGuideInfoClass = null; 
    private static Class<?> mapStatusInfoClass = null;
    private static Class<?> mapSwitchInfoClass = null;
    
    private static boolean isHookReady = false;
    private static boolean isHandshaking = false;
    private static Context systemContext = null;
    private static Handler mainHandler = null;
    
    private static Set<String> hookedConfigClasses = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V113 分层手术版启动");
        
        initLBSHook(lpparam);
        
        // 🟢 1. 静态 Hook 父类 (解决 g() 方法问题)
        hookAbstractBaseClass(lpparam.classLoader);
        
        // 🟢 2. 动态 Hook 子类 (解决 b() 方法问题)
        setupDynamicJailbreak(lpparam.classLoader);
        
        hookNaviBaseModel(lpparam.classLoader);
    }

    // 🔥 步骤1：搞定父类 co 的 g() 方法
    private void hookAbstractBaseClass(ClassLoader cl) {
        try {
            Class<?> baseClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG_BASE, cl);
            if (baseClass != null) {
                // g() 是 isValid 检查，在父类中定义，直接 Hook 父类即可
                XposedHelpers.findAndHookMethod(baseClass, "g", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        // 强制返回 true，表示配置有效
                        return true; 
                    }
                });
                XposedBridge.log("NaviHook: 🔓 父类 co.g() 已破解");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: ❌ 父类 Hook 失败: " + t);
        }
    }

    // 🔥 步骤2：搞定子类的 b() 方法
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
                XposedBridge.log("NaviHook: 🪤 已设置 ce.a() 抓捕陷阱");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: ❌ 设置抓捕失败: " + t);
        }
    }

    // 🔨 针对捕获到的子类，只 Hook b()
    private void hookConcreteConfigClass(Class<?> realClass) {
        String className = realClass.getName();
        if (hookedConfigClasses.contains(className)) return; 

        XposedBridge.log("NaviHook: 🎯 捕获到真实配置类: " + className);
        
        try {
            // b() 是获取 Vendor，必须在子类 Hook
            XposedHelpers.findAndHookMethod(realClass, "b", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    // XposedBridge.log("NaviHook: 👻 拦截 b() -> 返回 " + currentVendor);
                    return currentVendor; 
                }
            });

            // 注意：不要在这里 Hook g()，因为已经在父类 Hook 过了！
            
            hookedConfigClasses.add(className);
            XposedBridge.log("NaviHook: 🔓 越狱成功！类 " + className + " 已攻破");
            sendJavaBroadcast("🔓 越狱成功: " + className);

        } catch (Throwable t) {
            XposedBridge.log("NaviHook: ❌ Hook真实类失败: " + t);
        }
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
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: onCreate Hook 失败: " + t);
        }
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
            // 尝试主动触发一次抓捕
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
                                else captureCoreObjects(context.getClassLoader());
                            }
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
                // 1. 模式唤醒 (CRUISE -> GUIDE)
                injectModeSwitch(NavMode.CRUISE, NavMode.GUIDE, SwitchState.CRUISE_TO_GUIDE);
                sendJavaBroadcast("⚡ [0/7] 模式唤醒");

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
                                        
                                        updateClusterDirectly();
                                        
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

    private void stopProjection() {
        if (dashboardManagerInstance == null || mainHandler == null) return;
        try {
            clearClusterData();
            mainHandler.postDelayed(() -> {
                injectStatus(Status.GUIDE_STOP);
                sendJavaBroadcast("🛑 导航已停止");
                mainHandler.postDelayed(() -> {
                    injectModeSwitch(NavMode.GUIDE, NavMode.CRUISE, SwitchState.GUIDE_TO_CRUISE);
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

    private void injectModeSwitch(int oldMode, int newMode, int state) {
        try {
            if (mapSwitchInfoClass == null) return;
            Object switchInfo = XposedHelpers.newInstance(mapSwitchInfoClass, oldMode, newMode);
            XposedHelpers.setIntField(switchInfo, "mSwitchState", state);
            XposedHelpers.callMethod(dashboardManagerInstance, "a", switchInfo);
        } catch (Throwable t) {
            sendJavaBroadcast("⚠️ 模式切换失败: " + t);
        }
    }

    private void clearClusterData() {
        try {
            Object guideInfo = XposedHelpers.newInstance(mapGuideInfoClass, currentVendor);
            XposedHelpers.setObjectField(guideInfo, "curRoadName", "");
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", "");
            XposedHelpers.setIntField(guideInfo, "turnId", 0);
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", 0);
            XposedHelpers.setIntField(guideInfo, "remainDistance", 0);
            XposedHelpers.setIntField(guideInfo, "remainTime", 0);
            XposedHelpers.setIntField(guideInfo, "guideType", 0);
            XposedHelpers.setIntField(guideInfo, "roadType", 0);
            XposedHelpers.callMethod(dashboardManagerInstance, "a", guideInfo);
            sendJavaBroadcast("🧹 屏幕已深度清空");
        } catch (Throwable t) {}
    }

    private void injectStatus(int status) {
        try {
            Object statusInfo = XposedHelpers.newInstance(mapStatusInfoClass, currentVendor);
            XposedHelpers.setIntField(statusInfo, "status", status);
            XposedHelpers.callMethod(dashboardManagerInstance, "a", statusInfo);
        } catch (Throwable t) {}
    }

    private void updateClusterDirectly() {
        if (dashboardManagerInstance == null || mapGuideInfoClass == null) return;
        
        try {
            Object guideInfo = XposedHelpers.newInstance(mapGuideInfoClass, currentVendor);

            int finalIcon = (turnIcon == 0) ? 4 : turnIcon; 
            int finalDis = (segmentDis == 0) ? 500 : segmentDis;

            XposedHelpers.setObjectField(guideInfo, "curRoadName", curRoadName); 
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", nextRoadName);
            
            XposedHelpers.setIntField(guideInfo, "turnId", finalIcon); 
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", finalDis);
            XposedHelpers.setIntField(guideInfo, "remainDistance", routeRemainDis);
            XposedHelpers.setIntField(guideInfo, "remainTime", routeRemainTime);
            
            XposedHelpers.setIntField(guideInfo, "guideType", 0); 
            try { XposedHelpers.setIntField(guideInfo, "roadType", 1); } catch (Throwable t) {}
            try { XposedHelpers.setBooleanField(guideInfo, "isCustomTBTEnabled", true); } catch (Throwable t) {}
            
            XposedHelpers.callMethod(dashboardManagerInstance, "a", guideInfo);

            sendJavaBroadcast("💉 V113: [V" + currentVendor + "][Icon:" + finalIcon + "]");

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