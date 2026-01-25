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
    private static final String FIELD_INTERACTION = "d"; 
    private static final String FIELD_INSTANCE = "b";
    
    // ⚠️ 核心策略：首选 AdaptAPI 类，如果报错则换下面的 MapGuideInfo
    private static final String CLASS_NAVI_INFO = "com.ecarx.xui.adaptapi.diminteraction.NaviInfo";
    // private static final String CLASS_NAVI_INFO = "ecarx.naviservice.map.entity.MapGuideInfo"; // 备选方案

    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

    // 🔧 强测试数据 (Strong Test Data)
    private static String curRoadName = "系统就绪";
    private static String nextRoadName = "等待导航数据";
    private static int turnIcon = 4; // 默认给4 (右转)，确保非0，一眼能看出区别
    private static int segmentDis = 888; // 默认888米，吉利数字
    private static int routeRemainDis = 9999;
    private static int routeRemainTime = 600;
    
    // 默认 V0 (高德) + S1 (Start)
    private static int currentVendor = 0; 
    private static int currentStatus = 1; 

    private static Object dashboardManagerInstance = null;
    private static Object naviInteractionInstance = null;
    private static Class<?> naviInfoClass = null; 
    
    private static boolean isHookReady = false;
    private static Context systemContext = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V91 黄金候选版启动");
        
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
            if (naviInfoClass == null) {
                naviInfoClass = XposedHelpers.findClassIfExists(CLASS_NAVI_INFO, cl);
            }

            Class<?> mgrClass = XposedHelpers.findClass(CLASS_DASHBOARD_MGR, cl);
            Field instanceField = XposedHelpers.findField(mgrClass, FIELD_INSTANCE);
            instanceField.setAccessible(true);
            dashboardManagerInstance = instanceField.get(null);
            
            if (dashboardManagerInstance != null) {
                Field interactionField = XposedHelpers.findField(mgrClass, FIELD_INTERACTION);
                interactionField.setAccessible(true);
                naviInteractionInstance = interactionField.get(dashboardManagerInstance);
                
                if (naviInteractionInstance != null) {
                    XposedBridge.log("NaviHook: 🎉 捕获硬件接口!");
                    sendAppLog("STATUS_IPC_CONNECTED");
                    isHookReady = true;
                    activateCluster();
                }
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
                            // 🟢 恢复：完整的数据处理逻辑
                            int keyType = intent.getIntExtra("KEY_TYPE", 0);
                            if (keyType == 10065) return; 

                            Bundle b = intent.getExtras();
                            if (b != null) {
                                b.keySet(); // Unpack
                                extractData(b);
                                if (isHookReady) updateClusterDirectly();
                                else captureCoreObjects(context.getClassLoader());
                            }
                        }
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                             currentVendor = intent.getIntExtra("vendor", 0);
                             sendAppLog("🔄 V -> " + currentVendor);
                             activateCluster();
                        }
                        else if ("XSF_ACTION_SET_STATUS".equals(action)) {
                             currentStatus = intent.getIntExtra("status", 1);
                             sendAppLog("🔄 S -> " + currentStatus);
                             updateClusterDirectly();
                        }
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            captureCoreObjects(context.getClassLoader());
                            activateCluster();
                        }
                    } catch (Throwable t) {}
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION);
            filter.addAction("XSF_ACTION_SET_VENDOR");
            filter.addAction("XSF_ACTION_SET_STATUS");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    // 🔥 激活流程
    private void activateCluster() {
        if (naviInteractionInstance == null) return;
        try {
            XposedBridge.log("NaviHook: 执行完整激活流程...");
            
            // 1. 设置厂商 (激活通道)
            try {
                XposedHelpers.callMethod(naviInteractionInstance, "setMapType", currentVendor);
            } catch (Throwable t) {}
            
            // 2. 状态机置位
            try {
                XposedHelpers.callMethod(naviInteractionInstance, "notifyStartNavigation");
            } catch (Throwable t) {}

            SystemClock.sleep(150);

            // 3. TBT 模式
            XposedHelpers.callMethod(naviInteractionInstance, "notifyTurnByTurnStarted");
            
            // 4. 发送第一帧 (包含强测试数据)
            updateClusterDirectly();
            
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: 激活异常: " + t);
        }
    }

    // 🔥 核心注入 (V91 精确版)
    private void updateClusterDirectly() {
        if (naviInteractionInstance == null || naviInfoClass == null) return;
        
        try {
            // ✅ 使用带 vendor 的构造函数
            Object naviInfoObj = null;
            try {
                naviInfoObj = XposedHelpers.newInstance(naviInfoClass, currentVendor);
            } catch (Throwable t) {
                naviInfoObj = XposedHelpers.newInstance(naviInfoClass);
            }

            // 🟢 精确字段注入 (MapGuideInfo 真实字段名)
            XposedHelpers.setObjectField(naviInfoObj, "curRoadName", curRoadName); 
            XposedHelpers.setObjectField(naviInfoObj, "nextRoadName", nextRoadName);
            
            // ⚠️ 修正：使用真实字段名 turnId 和 nextTurnDistance
            XposedHelpers.setIntField(naviInfoObj, "turnId", turnIcon); 
            XposedHelpers.setIntField(naviInfoObj, "nextTurnDistance", segmentDis);
            XposedHelpers.setIntField(naviInfoObj, "remainDistance", routeRemainDis);
            XposedHelpers.setIntField(naviInfoObj, "remainTime", routeRemainTime);
            
            // 状态与类型 (强制 GPS)
            XposedHelpers.setIntField(naviInfoObj, "status", currentStatus);
            XposedHelpers.setIntField(naviInfoObj, "guideType", 0); 
            
            // Vendor 保险
            try { XposedHelpers.setIntField(naviInfoObj, "type", currentVendor); } catch(Throwable t){}
            try { XposedHelpers.setIntField(naviInfoObj, "source", currentVendor); } catch(Throwable t){}
            try { XposedHelpers.setIntField(naviInfoObj, "vendor", currentVendor); } catch(Throwable t){}
            try { XposedHelpers.setIntField(naviInfoObj, "eventMapVendor", currentVendor); } catch(Throwable t){}

            // 🟢 黄金验证：检查写入结果
            try {
                 Field f = naviInfoClass.getDeclaredField("turnId"); // 验证关键字段是否存在
                 f.setAccessible(true);
                 int val = f.getInt(naviInfoObj);
                 XposedBridge.log("🎯 验证写入: turnId=" + val + " (预期:" + turnIcon + ")");
                 if (val != turnIcon) {
                     sendAppLog("❌ 写入失败，可能字段名不对");
                 }
            } catch (NoSuchFieldException e) {
                 sendAppLog("❌ 致命错误: 找不到字段 turnId，请切换 ClassName!");
                 XposedBridge.log("NaviHook: 找不到字段 turnId, 建议切换为 ecarx.naviservice.map.entity.MapGuideInfo");
            }

            // 发射
            XposedHelpers.callMethod(naviInteractionInstance, "updateNaviInfo", naviInfoObj);
            sendAppLog("💉 V91: [V" + currentVendor + "][Icon:" + turnIcon + "][Dis:" + segmentDis + "]");

        } catch (Throwable t) {
            sendAppLog("❌ 注入异常: " + t.getMessage());
            XposedBridge.log(t);
        }
    }
    
    // 🟢 恢复：完整的数据提取逻辑
    private void extractData(Bundle b) {
        try {
            if (b.containsKey("CUR_ROAD_NAME")) curRoadName = b.getString("CUR_ROAD_NAME");
            else if (b.containsKey("cur_road_name")) curRoadName = b.getString("cur_road_name");
            else if (b.containsKey("ROAD_NAME")) curRoadName = b.getString("ROAD_NAME");
            
            if (b.containsKey("NEXT_ROAD_NAME")) nextRoadName = b.getString("NEXT_ROAD_NAME");
            else if (b.containsKey("next_road_name")) nextRoadName = b.getString("next_road_name");
            
            segmentDis = getInt(b, "SEG_REMAIN_DIS", "seg_remain_dis");
            turnIcon = getInt(b, "ICON", "icon");
            if (turnIcon == 0 && b.containsKey("NAV_ICON")) turnIcon = b.getInt("NAV_ICON");
            
            routeRemainDis = getInt(b, "ROUTE_REMAIN_DIS", "route_remain_dis");
            routeRemainTime = getInt(b, "ROUTE_REMAIN_TIME", "route_remain_time");

            if (curRoadName == null) curRoadName = "当前道路";
            if (nextRoadName == null) nextRoadName = "";
            
            // 收到数据自动切状态
            if (turnIcon > 0 && currentStatus != 1) {
                 currentStatus = 1;
            }
        } catch (Exception e) {}
    }
    
    // 🟢 恢复：getInt 实现
    private int getInt(Bundle b, String k1, String k2) {
        int v = b.getInt(k1, -1);
        if (v == -1) v = b.getInt(k2, -1);
        return (v == -1) ? 0 : v;
    }

    private void sendAppLog(String log) {
        if (systemContext != null) {
            try {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.setPackage(PKG_SELF);
                i.putExtra("log", log);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                try {
                    UserHandle allUser = (UserHandle) XposedHelpers.getStaticField(UserHandle.class, "ALL");
                    XposedHelpers.callMethod(systemContext, "sendBroadcastAsUser", i, allUser);
                } catch (Throwable t) {
                    systemContext.sendBroadcast(i);
                }
            } catch (Throwable t) {}
        }
    }
}