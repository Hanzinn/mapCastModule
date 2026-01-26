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
    private static final String FIELD_INSTANCE = "b";
    
    // 目标类定义
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
    
    // 🟢 正确的 Mode 定义：0=巡航/待机, 1=导航中
    private static class NavMode {
        static final int CRUISE = 0; 
        static final int GUIDE = 1;  
    }

    private static class SwitchState {
        static final int CRUISE_TO_GUIDE = 3; 
        static final int GUIDE_TO_CRUISE = 2; 
    }

    private static String curRoadName = "等待数据";
    private static String nextRoadName = "V115逻辑修正";
    private static int turnIcon = 4; 
    private static int segmentDis = 500;
    private static int routeRemainDis = 2000;
    private static int routeRemainTime = 600;
    
    // 🟢 Vendor ID：用于通过校验 (co.b) 和数据包签名
    // 既然日志里有 amap.g，它默认返回 0，我们建议保持 0
    private static int currentVendor = 0; 
    
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

        XposedBridge.log("NaviHook: 🚀 V115 逻辑修正融合版启动");
        
        initLBSHook(lpparam);
        
        // 1. 越狱模块：确保 MapStatusInfo/MapGuideInfo 能发出去
        hookAbstractBaseClass(lpparam.classLoader);
        setupDynamicJailbreak(lpparam.classLoader);
        
        hookNaviBaseModel(lpparam.classLoader);
    }

    private void hookAbstractBaseClass(ClassLoader cl) {
        try {
            Class<?> baseClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG_BASE, cl);
            if (baseClass != null) {
                // Hook 父类的 g() (isValid)，解决基础校验
                XposedHelpers.findAndHookMethod(baseClass, "g", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return true; 
                    }
                });
                XposedBridge.log("NaviHook: 🔓 父类 co.g() 已破解");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: ❌ 父类 Hook 失败: " + t);
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

    private void hookConcreteConfigClass(Class<?> realClass) {
        String className = realClass.getName();
        if (hookedConfigClasses.contains(className)) return; 

        XposedBridge.log("NaviHook: 🎯 捕获真实类: " + className);
        
        try {
            // Hook b() (Vendor) -> 强制返回我们设定的 currentVendor
            // 这确保了 "MapStatusInfo.vendor == Config.vendor" 校验永远通过
            XposedHelpers.findAndHookMethod(realClass, "b", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return currentVendor; 
                }
            });

            hookedConfigClasses.add(className);
            XposedBridge.log("NaviHook: 🔓 越狱成功: " + className);
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
            // 主动触发一次抓捕
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
                // 🟢 修正点1：参数解耦
                // 模式切换：从 0 (Cruise) 切到 1 (Guide)，状态 3
                // 这与 Vendor 无关！
                injectSwitch(NavMode.CRUISE, NavMode.GUIDE, SwitchState.CRUISE_TO_GUIDE);
                sendJavaBroadcast("⚡ [0/7] 模式唤醒: CRUISE->GUIDE");

                mainHandler.postDelayed(() -> {
                    // 1. APP_START
                    injectStatus(Status.APP_START);
                    sendJavaBroadcast("⚡ [1/7] APP启动(7)");

                    mainHandler.postDelayed(() -> {
                        // 2. APP_START_FINISH
                        injectStatus(Status.APP_START_FINISH);
                        sendJavaBroadcast("⚡ [2/7] 启动完成(8)");

                        mainHandler.postDelayed(() -> {
                            // 3. APP_ACTIVE
                            injectStatus(Status.APP_ACTIVE);
                            sendJavaBroadcast("⚡ [3/7] APP活跃(12)");
                            
                            mainHandler.postDelayed(() -> {
                                // 4. ROUTE_START
                                injectStatus(Status.ROUTE_START);
                                sendJavaBroadcast("⚡ [4/7] 路径计算(13)");
                                
                                mainHandler.postDelayed(() -> {
                                    // 5. ROUTE_SUCCESS
                                    injectStatus(Status.ROUTE_SUCCESS);
                                    sendJavaBroadcast("⚡ [5/7] 计算成功(14)");
                                    
                                    mainHandler.postDelayed(() -> {
                                        // 6. GUIDE_START
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
                // 🟢 修正点2：正确的退出顺序
                // 先停导航
                injectStatus(Status.GUIDE_STOP);
                sendJavaBroadcast("🛑 导航已停止");
                
                mainHandler.postDelayed(() -> {
                    // 再切回巡航模式 (Guide -> Cruise)
                    injectSwitch(NavMode.GUIDE, NavMode.CRUISE, SwitchState.GUIDE_TO_CRUISE);
                    sendJavaBroadcast("🛑 切回巡航");
                    
                    mainHandler.postDelayed(() -> {
                        // 最后退出APP状态
                        injectStatus(Status.APP_FINISH);
                        isHookReady = false;
                        sendJavaBroadcast("🛑 应用退出 -> 📴");
                    }, 300);
                }, 300);
            }, 100);
        } catch (Throwable t) {}
    }

    // 🟢 修正点3：参数明确化 (Mode, Mode, State)
    private void injectSwitch(int oldMode, int newMode, int state) {
        try {
            if (mapSwitchInfoClass == null) return;
            // 构造函数：MapSwitchingInfo(旧模式, 新模式)
            Object switchInfo = XposedHelpers.newInstance(mapSwitchInfoClass, oldMode, newMode);
            // 状态：SwitchState
            XposedHelpers.setIntField(switchInfo, "mSwitchState", state);
            
            // MapSwitchingInfo 没有 vendor 字段，所以它不会被 Vendor 校验拦截
            // 只有 MapStatusInfo/MapGuideInfo 会被拦截，而它们已经被越狱模块保护了
            XposedHelpers.callMethod(dashboardManagerInstance, "a", switchInfo);
        } catch (Throwable t) {
            sendJavaBroadcast("⚠️ 模式切换失败: " + t);
        }
    }

    private void clearClusterData() {
        try {
            // 这里使用 currentVendor，因为 MapGuideInfo 需要通过校验
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
            // 这里使用 currentVendor，因为 MapStatusInfo 需要通过校验
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

            sendJavaBroadcast("💉 V115: [V" + currentVendor + "][Icon:" + finalIcon + "]");

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