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
    
    // 核心内部类
    private static final String CLASS_MAP_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLASS_MAP_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

    // 状态常量定义 (源自 MapStatusTypes.smali)
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

    // 默认数据
    private static String curRoadName = "等待数据";
    private static String nextRoadName = "V102-Ult";
    private static int turnIcon = 4; 
    private static int segmentDis = 500;
    private static int routeRemainDis = 2000;
    private static int routeRemainTime = 600;
    
    private static int currentVendor = 0; // 默认高德
    
    private static Object dashboardManagerInstance = null;
    private static Class<?> mapGuideInfoClass = null; 
    private static Class<?> mapStatusInfoClass = null;
    
    private static boolean isHookReady = false;
    private static boolean isHandshaking = false; // 🔒 握手锁
    private static Context systemContext = null;
    private static Handler mainHandler = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V102-Ultimate 终极版启动");
        
        initLBSHook(lpparam);
        hookNaviBaseModel(lpparam.classLoader);
    }

    private void initLBSHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    systemContext = context;
                    mainHandler = new Handler(Looper.getMainLooper()); // 初始化主线程Handler
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
            mapGuideInfoClass = XposedHelpers.findClassIfExists(CLASS_MAP_GUIDE_INFO, cl);
            mapStatusInfoClass = XposedHelpers.findClassIfExists(CLASS_MAP_STATUS_INFO, cl);
            
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
                
                // 启动握手
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
                             final int newVendor = intent.getIntExtra("vendor", 0);
                             // 🔒 智能排队：如果正在握手，延迟3秒重试
                             if (isHandshaking) {
                                 sendJavaBroadcast("⏳ 忙碌中，3秒后自动切换V" + newVendor);
                                 if (mainHandler != null) {
                                     mainHandler.postDelayed(() -> {
                                         currentVendor = newVendor;
                                         performLifecycleHandshake();
                                     }, 3000);
                                 }
                                 return;
                             }
                             currentVendor = newVendor;
                             sendJavaBroadcast("🔄 切换厂商 -> V" + currentVendor);
                             performLifecycleHandshake(); 
                        }
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            captureCoreObjects(context.getClassLoader());
                            performLifecycleHandshake();
                        }
                        else if ("XSF_ACTION_STOP".equals(action)) {
                            stopProjection();
                        }
                        else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                            sendJavaBroadcast("STATUS_SERVICE_RUNNING");
                            if (isHookReady) sendJavaBroadcast("STATUS_IPC_CONNECTED");
                        }
                    } catch (Throwable t) {}
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION);
            filter.addAction("XSF_ACTION_SET_VENDOR");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            filter.addAction("XSF_ACTION_STOP");
            filter.addAction("XSF_ACTION_SEND_STATUS");
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    // 🔥 终极优化：带超时保护的握手流程
    private void performLifecycleHandshake() {
        if (dashboardManagerInstance == null || isHandshaking || mainHandler == null) return;
        
        isHandshaking = true; 
        isHookReady = false;  
        
        // 🛡️ 看门狗：10秒后强制解锁，防止死锁
        mainHandler.postDelayed(() -> {
            if (isHandshaking) {
                sendJavaBroadcast("⚠️ 握手超时，强制解锁");
                isHandshaking = false;
                isHookReady = true; // 尝试强制就绪
            }
        }, 10000);
        
        // 启动链式调用
        runHandshakeSequence();
    }

    private void runHandshakeSequence() {
        final int STEP_DELAY = 200;
        final int CALC_DELAY = 500;

        mainHandler.post(() -> {
            try {
                // 1. APP_START
                injectStatus(Status.APP_START);
                sendJavaBroadcast("⚡ [1/6] APP启动");

                mainHandler.postDelayed(() -> {
                    // 2. APP_START_FINISH
                    injectStatus(Status.APP_START_FINISH);
                    sendJavaBroadcast("⚡ [2/6] 启动完成");

                    mainHandler.postDelayed(() -> {
                        // 3. APP_ACTIVE
                        injectStatus(Status.APP_ACTIVE);
                        sendJavaBroadcast("⚡ [3/6] APP活跃");
                        
                        mainHandler.postDelayed(() -> {
                            // 4. ROUTE_START
                            injectStatus(Status.ROUTE_START);
                            sendJavaBroadcast("⚡ [4/6] 路径计算");
                            
                            mainHandler.postDelayed(() -> {
                                // 5. ROUTE_SUCCESS
                                injectStatus(Status.ROUTE_SUCCESS);
                                sendJavaBroadcast("⚡ [5/6] 计算成功");
                                
                                mainHandler.postDelayed(() -> {
                                    // 6. GUIDE_START
                                    injectStatus(Status.GUIDE_START);
                                    sendJavaBroadcast("⚡ [6/6] 导航开始 -> ✅");
                                    
                                    // 发射首帧数据
                                    updateClusterDirectly();
                                    
                                    // 解锁
                                    isHandshaking = false;
                                    isHookReady = true;
                                }, CALC_DELAY);
                            }, CALC_DELAY);
                        }, STEP_DELAY);
                    }, STEP_DELAY);
                }, STEP_DELAY);
            } catch (Throwable t) {
                isHandshaking = false;
                sendJavaBroadcast("❌ 握手异常: " + t.getMessage());
            }
        });
    }

    // 🔥 终极优化：深度清空 + 优雅退出
    private void stopProjection() {
        if (dashboardManagerInstance == null || mainHandler == null) return;
        try {
            // 1. 深度清空数据
            clearClusterData();
            
            mainHandler.postDelayed(() -> {
                // 2. GUIDE_STOP
                injectStatus(Status.GUIDE_STOP);
                sendJavaBroadcast("🛑 导航已停止");
                
                mainHandler.postDelayed(() -> {
                    // 3. APP_FINISH
                    injectStatus(Status.APP_FINISH);
                    isHookReady = false;
                    sendJavaBroadcast("🛑 应用已退出 -> 📴");
                }, 300);
            }, 100);
        } catch (Throwable t) {}
    }

    // 🧹 深度清空：根据 MapGuideInfo.smali 清理所有字段
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
            
            // 补充字段
            XposedHelpers.setIntField(guideInfo, "nextTurnTime", 0);
            XposedHelpers.setIntField(guideInfo, "cameraDistance", 0);
            XposedHelpers.setIntField(guideInfo, "cameraSpeed", 0);
            XposedHelpers.setIntField(guideInfo, "cameraType", 0);
            XposedHelpers.setIntField(guideInfo, "sapaDistance", 0);
            XposedHelpers.setIntField(guideInfo, "sapaType", 0);
            XposedHelpers.setObjectField(guideInfo, "sapaName", "");
            XposedHelpers.setBooleanField(guideInfo, "isCustomTBTEnabled", false);

            XposedHelpers.callMethod(dashboardManagerInstance, "a", guideInfo);
            sendJavaBroadcast("🧹 屏幕已深度清空");
        } catch (Throwable t) {
            sendJavaBroadcast("❌ 清空失败: " + t.getMessage());
        }
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

            sendJavaBroadcast("💉 V102: [V" + currentVendor + "][Icon:" + finalIcon + "]");

        } catch (Throwable t) {
            sendJavaBroadcast("❌ 注入异常: " + t.getMessage());
        }
    }
    
    // 🔥 终极优化：异步广播 (性能无损)
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