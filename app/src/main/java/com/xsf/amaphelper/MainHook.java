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

    // 核心管理类
    private static final String CLASS_DASHBOARD_MGR = "ecarx.naviservice.a.a";
    private static final String FIELD_INSTANCE = "b";
    
    // 🟢 重点：直接使用内部实体类 (你有它的smali，字段绝对正确)
    private static final String CLASS_MAP_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

    // 缓存数据
    private static String curRoadName = "系统就绪";
    private static String nextRoadName = "等待导航";
    private static int turnIcon = 4; // 右转
    private static int segmentDis = 500;
    private static int routeRemainDis = 2000;
    private static int routeRemainTime = 600;
    
    // 默认 V0 (高德) + S1 (Start)
    private static int currentVendor = 0; 
    private static int currentStatus = 1; 

    private static Object dashboardManagerInstance = null;
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

        XposedBridge.log("NaviHook: 🚀 V92 降维打击版启动");
        
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
            // 1. 获取内部 MapGuideInfo 类
            mapGuideInfoClass = XposedHelpers.findClassIfExists(CLASS_MAP_GUIDE_INFO, cl);
            if (mapGuideInfoClass == null) {
                sendAppLog("❌ 找不到 MapGuideInfo 类");
                return;
            }

            // 2. 获取 DashboardManager (a.a)
            Class<?> mgrClass = XposedHelpers.findClass(CLASS_DASHBOARD_MGR, cl);
            Field instanceField = XposedHelpers.findField(mgrClass, FIELD_INSTANCE);
            instanceField.setAccessible(true);
            dashboardManagerInstance = instanceField.get(null);
            
            if (dashboardManagerInstance != null) {
                XposedBridge.log("NaviHook: 🎉 捕获管理器成功!");
                sendAppLog("STATUS_IPC_CONNECTED");
                isHookReady = true;
                // 自动激活
                updateClusterDirectly(); 
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
                            int keyType = intent.getIntExtra("KEY_TYPE", 0);
                            if (keyType == 10065) return; 

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
                             updateClusterDirectly();
                        }
                        else if ("XSF_ACTION_SET_STATUS".equals(action)) {
                             currentStatus = intent.getIntExtra("status", 1);
                             sendAppLog("🔄 S -> " + currentStatus);
                             updateClusterDirectly();
                        }
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            captureCoreObjects(context.getClassLoader());
                            updateClusterDirectly();
                        }
                        else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                            // 收到查询广播，再次发射状态
                            if (isHookReady) sendAppLog("STATUS_IPC_CONNECTED");
                            else sendAppLog("STATUS_SERVICE_RUNNING");
                        }
                    } catch (Throwable t) {}
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION);
            filter.addAction("XSF_ACTION_SET_VENDOR");
            filter.addAction("XSF_ACTION_SET_STATUS");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            filter.addAction("XSF_ACTION_SEND_STATUS"); // 确保注册了这个
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    // 🔥 V92 核心注入：构造 MapGuideInfo 并喂给 Manager
    private void updateClusterDirectly() {
        if (dashboardManagerInstance == null || mapGuideInfoClass == null) return;
        
        try {
            // 🟢 1. 构造内部对象 MapGuideInfo (使用带参构造)
            Object guideInfo = XposedHelpers.newInstance(mapGuideInfoClass, currentVendor);

            // 🟢 2. 精确填充 (字段名来自 smali)
            XposedHelpers.setObjectField(guideInfo, "curRoadName", curRoadName); 
            XposedHelpers.setObjectField(guideInfo, "nextRoadName", nextRoadName);
            
            // 真实字段名
            XposedHelpers.setIntField(guideInfo, "turnId", turnIcon); 
            XposedHelpers.setIntField(guideInfo, "nextTurnDistance", segmentDis);
            XposedHelpers.setIntField(guideInfo, "remainDistance", routeRemainDis);
            XposedHelpers.setIntField(guideInfo, "remainTime", routeRemainTime);
            
            // 状态控制
            XposedHelpers.setIntField(guideInfo, "guideType", 0); // 0=GPS
            // 注意：MapGuideInfo 可能没有 status 字段，status 通常在 MapStatusInfo 里
            // 但我们先把 guideType 设对。
            
            // 🟢 3. 核心调用：DashboardManager.a(MapGuideInfo)
            // 在 a.java 中，有一个方法接收 MapGuideInfo。
            // 由于混淆，它可能叫 'a'。Xposed 会自动匹配参数类型。
            try {
                XposedHelpers.callMethod(dashboardManagerInstance, "a", guideInfo);
                
                // 🟢 4. 补充调用：模拟状态变更
                // a.java 中 case 1002 是处理状态的。我们需要构造 MapStatusInfo
                // 但简单起见，我们假设 DashboardManager 会自动处理
                
            } catch (NoSuchMethodError e) {
                // 如果找不到 'a'，尝试 'b' 或其他单参数且参数为 MapGuideInfo 的方法
                // 这里我们盲猜 'a' 因为反编译代码里它是 a(MapGuideInfo)
                sendAppLog("❌ 方法名不对，Manager 拒绝接收");
            }

            sendAppLog("💉 V92: Internal Inject [V" + currentVendor + "][Icon:" + turnIcon + "]");

        } catch (Throwable t) {
            // 这里会打印详细错误，如果是因为字段找不到，这里会报 NoSuchField
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
            else if (b.containsKey("next_road_name")) nextRoadName = b.getString("next_road_name");
            
            segmentDis = getInt(b, "SEG_REMAIN_DIS", "seg_remain_dis");
            turnIcon = getInt(b, "ICON", "icon");
            if (turnIcon == 0 && b.containsKey("NAV_ICON")) turnIcon = b.getInt("NAV_ICON");
            
            routeRemainDis = getInt(b, "ROUTE_REMAIN_DIS", "route_remain_dis");
            routeRemainTime = getInt(b, "ROUTE_REMAIN_TIME", "route_remain_time");

            if (curRoadName == null) curRoadName = "当前道路";
            if (nextRoadName == null) nextRoadName = "";
            
            if (turnIcon > 0 && currentStatus != 1) {
                 currentStatus = 1;
            }
        } catch (Exception e) {}
    }
    
    private int getInt(Bundle b, String k1, String k2) {
        int v = b.getInt(k1, -1);
        if (v == -1) v = b.getInt(k2, -1);
        return (v == -1) ? 0 : v;
    }

    // 🟢 通讯修复版
    private void sendAppLog(String log) {
        if (systemContext != null) {
            try {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.setPackage(PKG_SELF);
                i.putExtra("log", log);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                try {
                    UserHandle allUser = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "ALL");
                    XposedHelpers.callMethod(systemContext, "sendBroadcastAsUser", i, allUser);
                } catch (Throwable t) {
                    systemContext.sendBroadcast(i);
                }
            } catch (Throwable t) {}
        }
    }
}