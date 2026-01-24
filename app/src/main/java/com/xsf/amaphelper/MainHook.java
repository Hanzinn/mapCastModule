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
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    // 🎯 核心目标
    private static final String CLASS_DASHBOARD_MGR = "ecarx.naviservice.a.a";
    private static final String FIELD_INTERACTION = "d"; 
    private static final String FIELD_INSTANCE = "b";
    
    // 🔥 目标实体类
    private static final String CLASS_NAVI_INFO = "com.ecarx.xui.adaptapi.diminteraction.NaviInfo";
    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

    // 数据仓库
    private static String curRoadName = "等待高德...";
    private static String nextRoadName = "";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;
    
    // 🌟 核心变量：这个值现在由 App 按钮控制！
    // 1=高德/通用, 2=亿咖通/百度, 4=东软/吉利
    private static int currentVendor = 2; 

    // 对象引用
    private static Object dashboardManagerInstance = null;
    private static Object naviInteractionInstance = null;
    private static Class<?> naviInfoClass = null; 
    private static boolean isHookReady = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V79 万能钥匙模式启动");
        
        initLBSHook(lpparam);
        hookNaviBaseModel(lpparam.classLoader);
    }

    private void initLBSHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    registerReceiver(context);
                    
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                         captureCoreObjects(lpparam.classLoader);
                    }, 5000);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: onCreate Hook 失败: " + t);
        }
        hookApiByReflection(lpparam);
    }

    private void hookNaviBaseModel(ClassLoader cl) {
        try {
            Class<?> baseModelClass = XposedHelpers.findClassIfExists(CLASS_NAVI_BASE_MODEL, cl);
            if (baseModelClass != null) {
                XposedHelpers.findAndHookMethod(baseModelClass, "getMapVendor", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        // 强制统一口径
                        return currentVendor > 0 ? currentVendor : 1; 
                    }
                });
            }
        } catch (Throwable t) {}
    }

    private void captureCoreObjects(ClassLoader cl) {
        try {
            if (naviInfoClass == null) {
                naviInfoClass = XposedHelpers.findClassIfExists(CLASS_NAVI_INFO, cl);
                if (naviInfoClass != null) {
                    XposedBridge.log("NaviHook: ✅ 找到 NaviInfo 类");
                }
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
                    XposedBridge.log("NaviHook: 🎉 捕获硬件接口对象!");
                    isHookReady = true;
                    updateClusterDirectly(); 
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
                        // 🔥 监听 App 的 Vendor 切换指令
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                             int v = intent.getIntExtra("vendor", 2);
                             currentVendor = (v == -1) ? 2 : v;
                             XposedBridge.log("NaviHook: 🔄 切换暗号 (Type) 为: " + currentVendor);
                             
                             // 立即刷新一发，看看效果
                             curRoadName = "测试模式 Type=" + currentVendor;
                             updateClusterDirectly();
                        }
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            captureCoreObjects(context.getClassLoader());
                            curRoadName = "强制重连 V79";
                            updateClusterDirectly();
                        }
                    } catch (Throwable t) {}
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION);
            filter.addAction("XSF_ACTION_SET_VENDOR"); // 👈 关键
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    // 🔥 V79 核心：动态 Type 注入
    private void updateClusterDirectly() {
        if (naviInteractionInstance == null || naviInfoClass == null) return;
        
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            Object naviInfoObj = allocateInstance.invoke(unsafe, naviInfoClass);

            // 1. 基础数据
            fuzzySetField(naviInfoObj, "current", curRoadName); 
            fuzzySetField(naviInfoObj, "curRoad", curRoadName);
            fuzzySetField(naviInfoObj, "next", nextRoadName);
            fuzzySetField(naviInfoObj, "icon", turnIcon);
            fuzzySetField(naviInfoObj, "status", 1); // 1=Navigating
            fuzzySetField(naviInfoObj, "distance", segmentDis);
            fuzzySetField(naviInfoObj, "remain", routeRemainDis);

            // 🌟 2. 关键暗号：使用 currentVendor 变量
            fuzzySetField(naviInfoObj, "type", currentVendor); 
            // 有些车型字段叫 source
            fuzzySetField(naviInfoObj, "source", currentVendor); 
            // 有些车型字段叫 vendor
            fuzzySetField(naviInfoObj, "vendor", currentVendor); 

            // 3. 发射！
            XposedHelpers.callMethod(naviInteractionInstance, "updateNaviInfo", naviInfoObj);
            
            // 4. 双保险
            try {
                XposedHelpers.callMethod(naviInteractionInstance, "updateTurnByTurnArrow", turnIcon);
            } catch (Throwable t) {}

            XposedBridge.log("NaviHook: 💉 注入成功: " + curRoadName + " [Type=" + currentVendor + "]");

        } catch (Throwable t) {
            XposedBridge.log("NaviHook: 注入失败: " + t);
        }
    }

    private void fuzzySetField(Object obj, String keyword, Object value) {
        try {
            Field[] fields = obj.getClass().getDeclaredFields();
            for (Field f : fields) {
                f.setAccessible(true);
                String name = f.getName().toLowerCase();
                if (name.contains(keyword.toLowerCase())) {
                    if (value instanceof Integer && (f.getType() == int.class || f.getType() == Integer.class)) {
                        f.set(obj, value);
                        // 稍微多试几次，防止只填了一个字段
                    }
                    if (value instanceof String && f.getType() == String.class) {
                        f.set(obj, value);
                        // String 类型通常只有一个匹配的，填完返回
                        return; 
                    }
                }
            }
        } catch (Exception e) {}
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

            if (curRoadName == null) curRoadName = "";
            if (nextRoadName == null) nextRoadName = "";
        } catch (Exception e) {}
    }
    
    private int getInt(Bundle b, String k1, String k2) {
        int v = b.getInt(k1, -1);
        if (v == -1) v = b.getInt(k2, -1);
        return (v == -1) ? 0 : v;
    }

    private void hookApiByReflection(XC_LoadPackage.LoadPackageParam lpparam) {
        // API Hook 保留
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

