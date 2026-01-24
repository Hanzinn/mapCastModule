package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    // 目标包名
    private static final String PKG_SERVICE = "ecarx.naviservice";
    // 自身包名（用于模块激活状态检测）
    private static final String PKG_SELF = "com.xsf.amaphelper";
    // 广播 Action
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    // ⚠️ 核心混淆类名 (如果车机OTA升级，这里可能需要更新)
    private static final String CLASS_DASHBOARD_MGR = "ecarx.naviservice.a.a";
    private static final String FIELD_INTERACTION = "d"; 
    private static final String FIELD_INSTANCE = "b";
    
    // 反射目标类
    private static final String CLASS_NAVI_INFO = "com.ecarx.xui.adaptapi.diminteraction.NaviInfo";
    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

    // 导航数据缓存
    private static String curRoadName = "等待高德数据...";
    private static String nextRoadName = "";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;
    
    // 🔧 全局配置 (默认配置：高德厂商 + 导航中状态)
    private static int currentVendor = 0; // 0=AutoNavi(高德)
    private static int currentStatus = 1; // 1=Start(AdaptAPI层) 或 16=GUIDE_START(内部层)

    // 核心对象引用
    private static Object dashboardManagerInstance = null;
    private static Object naviInteractionInstance = null;
    private static Class<?> naviInfoClass = null; 
    
    // 状态标记
    private static boolean isHookReady = false;
    private static Context systemContext = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 1. 模块自检 Hook
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 2. 仅针对目标包 Hook
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V85 最终修正版启动 [Target: " + PKG_SERVICE + "]");
        
        initLBSHook(lpparam);
        hookNaviBaseModel(lpparam.classLoader);
    }

    private void initLBSHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook Application onCreate 以获取 Context 并注册广播
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    systemContext = context;
                    registerReceiver(context);
                    sendAppLog("✅ LBSNavi 服务已启动 (Service Running)");
                    
                    // 延迟 5 秒捕获核心对象，等待单例初始化完成
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                         captureCoreObjects(lpparam.classLoader);
                    }, 5000);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: onCreate Hook 失败: " + t);
        }
        
        // 尝试 Hook 第三方 SDK 接口（备用方案）
        hookApiByReflection(lpparam);
    }

    /**
     * 底层模型欺骗：强制所有 NaviBaseModel 返回指定的 Vendor ID
     * 解决车机内部对第三方地图的鉴权拦截
     */
    private void hookNaviBaseModel(ClassLoader cl) {
        try {
            Class<?> baseModelClass = XposedHelpers.findClassIfExists(CLASS_NAVI_BASE_MODEL, cl);
            if (baseModelClass != null) {
                XposedHelpers.findAndHookMethod(baseModelClass, "getMapVendor", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        // 强制返回设定的 Vendor (如 0-高德)
                        return currentVendor; 
                    }
                });
                sendAppLog("✅ Vendor 欺骗模块已就绪");
            }
        } catch (Throwable t) {
            sendAppLog("❌ Vendor Hook 失败: " + t.getMessage());
        }
    }

    /**
     * 核心捕获：获取 DashboardManager 单例和 INaviInteraction 接口
     */
    private void captureCoreObjects(ClassLoader cl) {
        try {
            // 1. 获取 NaviInfo 类
            if (naviInfoClass == null) {
                naviInfoClass = XposedHelpers.findClassIfExists(CLASS_NAVI_INFO, cl);
            }

            // 2. 获取 DashboardManager (a.a)
            Class<?> mgrClass = XposedHelpers.findClass(CLASS_DASHBOARD_MGR, cl);
            Field instanceField = XposedHelpers.findField(mgrClass, FIELD_INSTANCE);
            instanceField.setAccessible(true);
            dashboardManagerInstance = instanceField.get(null);
            
            // 3. 获取 INaviInteraction (d)
            if (dashboardManagerInstance != null) {
                Field interactionField = XposedHelpers.findField(mgrClass, FIELD_INTERACTION);
                interactionField.setAccessible(true);
                naviInteractionInstance = interactionField.get(dashboardManagerInstance);
                
                if (naviInteractionInstance != null) {
                    XposedBridge.log("NaviHook: 🎉 捕获硬件接口成功!");
                    sendAppLog("🎉 核心接口捕获成功 (IPC Connected)");
                    isHookReady = true;
                    // 立即发送一次握手包，尝试点亮
                    updateClusterDirectly(); 
                } else {
                    sendAppLog("❌ 接口为空 (Manager found, Interaction null)");
                }
            } else {
                 sendAppLog("❌ 管理类未初始化 (Manager null)");
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
                        
                        // 接收高德数据
                        if (AMAP_ACTION.equals(action)) {
                            int keyType = intent.getIntExtra("KEY_TYPE", 0);
                            // 过滤无用类型
                            if (keyType == 10065) return; 

                            Bundle b = intent.getExtras();
                            if (b != null) {
                                b.keySet(); // 解包防止 ClassLoader 问题
                                extractData(b);
                                if (isHookReady) updateClusterDirectly();
                                else captureCoreObjects(context.getClassLoader()); // 再次尝试捕获
                            }
                        }
                        // 调试指令：切换厂商
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                             currentVendor = intent.getIntExtra("vendor", 0);
                             sendAppLog("🔄 切换 Vendor -> V" + currentVendor);
                             curRoadName = "测试 V" + currentVendor;
                             updateClusterDirectly();
                        }
                        // 调试指令：切换状态
                        else if ("XSF_ACTION_SET_STATUS".equals(action)) {
                             currentStatus = intent.getIntExtra("status", 1);
                             sendAppLog("🔄 切换 Status -> S" + currentStatus);
                             curRoadName = "状态测试 S" + currentStatus;
                             updateClusterDirectly();
                        }
                        // 调试指令：强制重连
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            captureCoreObjects(context.getClassLoader());
                            curRoadName = "强制重连 V85";
                            updateClusterDirectly();
                        }
                        // 状态查询
                        else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                            if (systemContext != null) {
                                sendAppLog("STATUS_SERVICE_RUNNING");
                                if (isHookReady) sendAppLog("STATUS_IPC_CONNECTED");
                            }
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

    /**
     * 🔥 核心注入方法：绕过协议层，直接操作内存对象
     */
    private void updateClusterDirectly() {
        if (naviInteractionInstance == null || naviInfoClass == null) return;
        
        try {
            // [步骤1] 唤醒沉睡的仪表盘 (Pre-Heating)
            // 参考 a.java 逻辑，必须先 notifyStart 才能显示 TBT
            try {
                XposedHelpers.callMethod(naviInteractionInstance, "notifyTurnByTurnStarted");
                // 顺便更新一次简单箭头，确保通道活跃
                int safeIcon = (turnIcon > 0 && turnIcon < 100) ? turnIcon : 2;
                XposedHelpers.callMethod(naviInteractionInstance, "updateTurnByTurnArrow", safeIcon);
            } catch (Throwable t) {
                // 部分旧版本可能没有此方法，忽略异常
            }

            // [步骤2] 使用 Unsafe 绕过构造函数创建实例 (God Mode)
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            Object naviInfoObj = allocateInstance.invoke(unsafe, naviInfoClass);

            // [步骤3] 填充基础导航数据 (Fuzzy Field Injection)
            fuzzySetField(naviInfoObj, "current", curRoadName); 
            fuzzySetField(naviInfoObj, "curRoad", curRoadName);
            fuzzySetField(naviInfoObj, "next", nextRoadName);
            fuzzySetField(naviInfoObj, "icon", turnIcon);
            fuzzySetField(naviInfoObj, "distance", segmentDis);
            fuzzySetField(naviInfoObj, "remain", routeRemainDis);
            fuzzySetField(naviInfoObj, "time", routeRemainTime);
            
            // [步骤4] 🌟 关键修正：GuideType 与 Status 的逻辑自洽 🌟
            // 逻辑：如果状态是导航(1/16)，GuideType 必须是 0 (GPS)，绝不能是 2 (Cruise)
            int finalGuideType = 0; 
            int finalStatus = currentStatus;

            boolean hasTurnIcon = (turnIcon > 0 && turnIcon < 100);
            
            if (hasTurnIcon) {
                // 如果有图标，说明必须在 TBT 模式
                finalGuideType = 0; // 0 = GPS Nav
                if (finalStatus == 28 || finalStatus == 2 || finalStatus == 12) {
                    finalStatus = 1; // 强制修正为 START
                    // sendAppLog("⚠️ 逻辑自愈：有图标但状态错误，已修正为 S1/G0");
                }
            } else {
                // 如果没有图标，且状态是巡航，则允许巡航模式
                if (finalStatus == 28 || finalStatus == 2) {
                    finalGuideType = 2; // 2 = Cruise
                }
            }
            
            fuzzySetField(naviInfoObj, "status", finalStatus);
            fuzzySetField(naviInfoObj, "guideType", finalGuideType); 
            
            // [步骤5] 注入 Vendor ID
            fuzzySetField(naviInfoObj, "type", currentVendor);   
            fuzzySetField(naviInfoObj, "source", currentVendor); 
            fuzzySetField(naviInfoObj, "vendor", currentVendor); 

            // [步骤6] 发射数据！
            XposedHelpers.callMethod(naviInteractionInstance, "updateNaviInfo", naviInfoObj);
            
            // 日志 (仅在关键变化时发送，避免刷屏)
            // sendAppLog("💉 注入: [V" + currentVendor + "][S" + finalStatus + "][G" + finalGuideType + "]");

        } catch (Throwable t) {
            sendAppLog("❌ 注入异常: " + t.getMessage());
        }
    }

    private void sendAppLog(String log) {
        if (systemContext != null) {
            try {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.setPackage(PKG_SELF);
                i.putExtra("log", log);
                systemContext.sendBroadcast(i);
            } catch (Throwable t) {}
        }
    }

    /**
     * 模糊字段设置：通过字段名关键词匹配，无需精确混淆名
     */
    private void fuzzySetField(Object obj, String keyword, Object value) {
        try {
            Field[] fields = obj.getClass().getDeclaredFields();
            for (Field f : fields) {
                f.setAccessible(true);
                String name = f.getName().toLowerCase();
                if (name.contains(keyword.toLowerCase())) {
                    if (value instanceof Integer && (f.getType() == int.class || f.getType() == Integer.class)) {
                        f.set(obj, value);
                        return; 
                    }
                    if (value instanceof String && f.getType() == String.class) {
                        f.set(obj, value);
                        return;
                    }
                    if (value instanceof Long && (f.getType() == long.class || f.getType() == Long.class)) {
                        f.set(obj, value);
                        return;
                    }
                }
            }
        } catch (Exception e) {}
    }

    private void extractData(Bundle b) {
        try {
            // 尝试多种 Key 提取路名
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

            // 自动修正数据，防止 null
            if (curRoadName == null) curRoadName = "";
            if (nextRoadName == null) nextRoadName = "";
            
            // 自动状态推断：如果收到图标信息，认为在导航中，自动切状态
            if (turnIcon > 0 && currentStatus != 1 && currentStatus != 16) {
                 // 除非手动设为巡航，否则自动切回导航
                 if (currentStatus != 28) currentStatus = 1;
            }
        } catch (Exception e) {}
    }
    
    private int getInt(Bundle b, String k1, String k2) {
        int v = b.getInt(k1, -1);
        if (v == -1) v = b.getInt(k2, -1);
        return (v == -1) ? 0 : v;
    }

    // 备用方案：通过反射 Hook 开放接口
    private void hookApiByReflection(XC_LoadPackage.LoadPackageParam lpparam) {
         try {
            Class<?> apiClass = XposedHelpers.findClassIfExists("com.neusoft.nts.ecarxnavsdk.EcarxOpenApi", lpparam.classLoader);
            if (apiClass == null) return;
            Class<?> cbClass = XposedHelpers.findClassIfExists("com.neusoft.nts.ecarxnavsdk.IAPIGetGuideInfoCallBack", lpparam.classLoader);
            if (cbClass == null) return;

            XposedHelpers.findAndHookMethod(apiClass, "getGuideInfo", cbClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object callback = param.args[0];
                        if (callback != null) {
                            XposedHelpers.callMethod(callback, "getGuideInfoResult",
                                1, routeRemainDis, routeRemainTime, 0, 0, 0,
                                nextRoadName, nextRoadName, 
                                0.5f, 0, segmentDis, turnIcon, 
                                curRoadName, routeRemainDis, routeRemainTime, 0, 0
                            );
                        }
                    } catch (Throwable t) {}
                }
            });
        } catch (Throwable t) {}
    }
}

