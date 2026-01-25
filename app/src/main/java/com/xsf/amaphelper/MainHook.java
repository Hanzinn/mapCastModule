package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import java.lang.reflect.Field;
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
    private static final String FIELD_INTERACTION = "d"; 
    
    // 内部实体类
    private static final String CLASS_MAP_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

    private static String curRoadName = "系统就绪";
    private static String nextRoadName = "V94测试";
    private static int turnIcon = 4; 
    private static int segmentDis = 500;
    private static int routeRemainDis = 2000;
    private static int routeRemainTime = 600;
    
    private static int currentVendor = 0; 
    private static int currentStatus = 1; 

    private static Object dashboardManagerInstance = null;
    private static Object naviInteractionInstance = null; 
    private static Class<?> mapGuideInfoClass = null; 
    
    private static boolean isHookReady = false;
    private static Context systemContext = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V94 容错修正版启动");
        
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
                    registerReceiver(context);
                    sendAppLog("STATUS_SERVICE_RUNNING");
                    
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
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
            if (mapGuideInfoClass == null) {
                sendAppLog("❌ 找不到 MapGuideInfo 类");
                return;
            }

            Class<?> mgrClass = XposedHelpers.findClass(CLASS_DASHBOARD_MGR, cl);
            Field instanceField = XposedHelpers.findField(mgrClass, FIELD_INSTANCE);
            instanceField.setAccessible(true);
            dashboardManagerInstance = instanceField.get(null);
            
            if (dashboardManagerInstance != null) {
                Field interactionField = XposedHelpers.findField(mgrClass, FIELD_INTERACTION);
                interactionField.setAccessible(true);
                naviInteractionInstance = interactionField.get(dashboardManagerInstance);

                XposedBridge.log("NaviHook: 🎉 捕获成功!");
                sendAppLog("STATUS_IPC_CONNECTED");
                isHookReady = true;
                
                ensureActiveState();
            } else {
                sendAppLog("❌ 管理器未初始化");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: 捕获异常: " + t);
            sendAppLog("❌ 捕获异常: " + t.getMessage());
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
                                b.keySet();
                                extractData(b);
                                if (isHookReady) updateClusterDirectly();
                                else captureCoreObjects(context.getClassLoader());
                            }
                        }
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                             currentVendor = intent.getIntExtra("vendor", 0);
                             sendAppLog("🔄 V -> " + currentVendor);
                             ensureActiveState(); 
                        }
                        else if ("XSF_ACTION_SET_STATUS".equals(action)) {
                             currentStatus = intent.getIntExtra("status", 1);
                             sendAppLog("🔄 S -> " + currentStatus);
                             updateClusterDirectly();
                        }
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            captureCoreObjects(context.getClassLoader());
                            ensureActiveState();
                            updateClusterDirectly();
                        }
                        else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                            sendAppLog("STATUS_SERVICE_RUNNING");
                            if (isHookReady) sendAppLog("STATUS_IPC_CONNECTED");
                        }
                    } catch (Throwable t) {}
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION);
            filter.addAction("XSF_ACTION_SET_VENDOR");
            filter.addAction("XSF_ACTION_SET_STATUS");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            filter.addAction("XSF_ACTION_SEND_STATUS");
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    // 🔥 V94 核心修正：容错唤醒机制
    // 即使某个方法不存在，也不会打断后续流程！
    private void ensureActiveState() {
        if (naviInteractionInstance == null) return;
        
        // 步骤 1: 尝试 TBT 开启 (这是最关键的开门动作！)
        try {
            XposedHelpers.callMethod(naviInteractionInstance, "notifyTurnByTurnStarted");
            XposedBridge.log("NaviHook: notifyTurnByTurnStarted 成功");
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: TBT 开启失败(忽略): " + t);
        }

        // 步骤 2: 尝试 setMapType (如果不存在就跳过，不崩)
        try {
            XposedHelpers.callMethod(naviInteractionInstance, "setMapType", currentVendor);
            XposedBridge.log("NaviHook: setMapType 成功");
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: setMapType 缺失(忽略)");
        }
        
        // 步骤 3: 尝试 notifyStartNavigation
        try {
            XposedHelpers.callMethod(naviInteractionInstance, "notifyStartNavigation");
        } catch (Throwable t) {}
        
        // 步骤 4: 尝试 updateNaviStatus (这是另一种切 Vendor 的方式)
        try {
             // 0x10 = GUIDE_START
             XposedHelpers.callMethod(naviInteractionInstance, "updateNaviStatus", currentVendor, 16);
        } catch (Throwable t) {}

        sendAppLog("⚡ 唤醒指令已发送");
    }

    private void updateClusterDirectly() {
        if (dashboardManagerInstance == null || mapGuideInfoClass == null) return;
        
        try {
            // 每次注入前都尝试唤醒一下（确保不休眠）
            // 但为了性能，这里我们不再全套调用，只在 ensureActiveState 里做全套
            
            Object guideInfo = XposedHelpers.newInstance(mapGuideInfoClass, currentVendor);

            int finalIcon = (turnIcon == 0) ? 4 : turnIcon; 
            int finalDis = (segmentDis == 0) ? 500 : segmentDis;

            // 字段填充
            XposedHelpers.setObjectField(guideInfo, "curRoadName", curRoadName); 
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", nextRoadName);
            
            XposedHelpers.setIntField(guideInfo, "turnId", finalIcon); 
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", finalDis);
            XposedHelpers.setIntField(guideInfo, "remainDistance", routeRemainDis);
            XposedHelpers.setIntField(guideInfo, "remainTime", routeRemainTime);
            XposedHelpers.setIntField(guideInfo, "guideType", 0); // 0=GPS
            
            // 注入给管理器
            XposedHelpers.callMethod(dashboardManagerInstance, "a", guideInfo);

            sendAppLog("💉 V94: [V" + currentVendor + "][Icon:" + finalIcon + "] OK");

        } catch (Throwable t) {
            sendAppLog("❌ 注入异常: " + t.getMessage());
            XposedBridge.log(t);
        }
    }
    
    private void extractData(Bundle b) {
        try {
            if (b.containsKey("CUR_ROAD_NAME")) curRoadName = b.getString("CUR_ROAD_NAME");
            else if (b.containsKey("cur_road_name")) curRoadName = b.getString("cur_road_name");
            else if (b.containsKey("ROAD_NAME")) curRoadName = b.getString("ROAD_NAME");
            
            if (b.containsKey("NEXT_ROAD_NAME")) nextRoadName = b.getString("NEXT_ROAD_NAME");
            
            segmentDis = getInt(b, "SEG_REMAIN_DIS", "seg_remain_dis");
            turnIcon = getInt(b, "ICON", "icon");
            if (turnIcon == 0 && b.containsKey("NAV_ICON")) turnIcon = b.getInt("NAV_ICON");
            
            routeRemainDis = getInt(b, "ROUTE_REMAIN_DIS", "route_remain_dis");
            routeRemainTime = getInt(b, "ROUTE_REMAIN_TIME", "route_remain_time");

            if (curRoadName == null) curRoadName = "当前道路";
            
            if (currentStatus != 1) currentStatus = 1;
            
        } catch (Exception e) {}
    }
    
    private int getInt(Bundle b, String k1, String k2) {
        int v = b.getInt(k1, -1);
        if (v == -1) v = b.getInt(k2, -1);
        return (v == -1) ? 0 : v;
    }

    // 🟢 修复灯光：双重广播发送
    private void sendAppLog(String log) {
        if (systemContext != null) {
            try {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.setPackage(PKG_SELF);
                i.putExtra("log", log);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                
                // 1. 尝试 Root/System 级广播
                try {
                    UserHandle allUser = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "ALL");
                    XposedHelpers.callMethod(systemContext, "sendBroadcastAsUser", i, allUser);
                } catch (Throwable t) {
                    // 2. 如果失败，尝试降级为普通广播 (有些系统改了API)
                    systemContext.sendBroadcast(i);
                }
            } catch (Throwable t) {}
        }
    }
}