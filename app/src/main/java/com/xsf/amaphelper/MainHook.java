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
    private static final String CLASS_MAP_CONFIG = "ecarx.naviservice.map.co"; // 校验锁

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
    
    // 模式常量
    private static class NavMode {
        static final int CRUISE = 0; 
        static final int GUIDE = 1;  
    }

    // 切换动作
    private static class SwitchState {
        static final int GUIDE_TO_GUIDE = 1;
        static final int GUIDE_TO_CRUISE = 2; 
        static final int CRUISE_TO_GUIDE = 3; 
    }

    private static String curRoadName = "等待数据";
    private static String nextRoadName = "V111安全版";
    private static int turnIcon = 4; 
    private static int segmentDis = 500;
    private static int routeRemainDis = 2000;
    private static int routeRemainTime = 600;
    
    private static int currentVendor = 5; 
    
    private static Object dashboardManagerInstance = null;
    private static Class<?> mapGuideInfoClass = null; 
    private static Class<?> mapStatusInfoClass = null;
    private static Class<?> mapSwitchInfoClass = null;
    
    private static boolean isHookReady = false;
    private static boolean isHandshaking = false;
    private static Context systemContext = null;
    private static Handler mainHandler = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V111 安全越狱版启动");
        
        // 🟢 修正点：先注册核心Hook，防止越狱代码崩溃导致整个模块失效
        initLBSHook(lpparam);
        
        // 尝试Hook Base Model (如果类已加载)
        try {
            hookNaviBaseModel(lpparam.classLoader);
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
                    
                    // 🟢 这一步必须执行，指示灯才会亮
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

    // 🔥 安全越狱：在 captureCoreObjects 内部执行，确保类加载器就绪
    private void bypassSystemVendorCheck(ClassLoader cl) {
        try {
            Class<?> configClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG, cl);
            if (configClass != null) {
                // Hook b() -> 强制返回 5
                XposedHelpers.findAndHookMethod(configClass, "b", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return currentVendor; 
                    }
                });
                // Hook g() -> 强制返回 true
                XposedHelpers.findAndHookMethod(configClass, "g", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return true; 
                    }
                });
                XposedBridge.log("NaviHook: 🔓 越狱补丁植入成功");
                sendJavaBroadcast("🔓 越狱成功: 强制 V" + currentVendor);
            } else {
                XposedBridge.log("NaviHook: ❌ 找不到配置类 co");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: ❌ 越狱尝试失败: " + t);
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
            // 🟢 1. 先尝试越狱 (即使失败也不影响后续)
            bypassSystemVendorCheck(cl);
            
            // 2. 加载核心类
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
                sendJavaBroadcast("STATUS_IPC_CONNECTED"); // 这里的灯必须亮
                
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
                // 这里的参数 (old=0, new=1) 是 Mode，不是 Vendor！
                // 同时，因为我们Hook了co.b()返回5，所以外部调用的Vendor校验也会通过。
                injectModeSwitch(NavMode.CRUISE, NavMode.GUIDE, SwitchState.CRUISE_TO_GUIDE);
                sendJavaBroadcast("⚡ [0/7] 模式唤醒: 巡航->导航");

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

            sendJavaBroadcast("💉 V111: [V" + currentVendor + "][Icon:" + finalIcon + "]");

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