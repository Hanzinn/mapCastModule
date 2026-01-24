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
    
    // 🔥 关键修正：直接使用实体类，而非接口
    private static final String CLASS_NAVI_INFO = "com.ecarx.xui.adaptapi.diminteraction.NaviInfo";
    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

    // 数据仓库
    private static String curRoadName = "等待高德...";
    private static String nextRoadName = "";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;
    private static int currentVendor = 2;

    // 对象引用
    private static Object dashboardManagerInstance = null;
    private static Object naviInteractionInstance = null;
    private static Class<?> naviInfoClass = null; // 缓存 NaviInfo 类
    private static boolean isHookReady = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V77 实体伪造模式启动");
        
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
                        return currentVendor > 0 ? currentVendor : 1; 
                    }
                });
            }
        } catch (Throwable t) {}
    }

    private void captureCoreObjects(ClassLoader cl) {
        try {
            // 1. 预加载 NaviInfo 类
            if (naviInfoClass == null) {
                naviInfoClass = XposedHelpers.findClassIfExists(CLASS_NAVI_INFO, cl);
                if (naviInfoClass != null) {
                    XposedBridge.log("NaviHook: ✅ 找到 NaviInfo 类: " + naviInfoClass);
                    // 打印一下字段，方便排查
                    printClassFields(naviInfoClass);
                } else {
                    XposedBridge.log("NaviHook: ❌ 致命错误：未找到 " + CLASS_NAVI_INFO);
                }
            }

            // 2. 捕获 Manager 和 Interaction
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
                    // 立即尝试一发
                    updateClusterDirectly(); 
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: 捕获异常: " + t);
        }
    }

    // 🖨️ 辅助：打印类字段
    private void printClassFields(Class<?> clazz) {
        try {
            Field[] fields = clazz.getDeclaredFields();
            StringBuilder sb = new StringBuilder("🔍 [NaviInfo 字段列表]: ");
            for (Field f : fields) {
                sb.append(f.getName()).append("(").append(f.getType().getSimpleName()).append("); ");
            }
            XposedBridge.log(sb.toString());
        } catch (Exception e) {}
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
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            captureCoreObjects(context.getClassLoader());
                            curRoadName = "强制测试 V77";
                            turnIcon = 2;
                            updateClusterDirectly();
                        }
                    } catch (Throwable t) {}
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION);
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    // 🔥 V77 核心：实体注入 + 智能填充
    private void updateClusterDirectly() {
        if (naviInteractionInstance == null || naviInfoClass == null) return;
        
        try {
            // 1. 实例化 NaviInfo
            Object naviInfoObj = naviInfoClass.newInstance();

            // 2. 智能填充数据 (Fuzzy Fill)
            fuzzySetField(naviInfoObj, "current", curRoadName); // 找包含 current 的字段填路名
            fuzzySetField(naviInfoObj, "curRoad", curRoadName); // 备用

            fuzzySetField(naviInfoObj, "next", nextRoadName);   // 找包含 next 的字段填下个路名
            
            fuzzySetField(naviInfoObj, "icon", turnIcon);       // 找包含 icon 的字段填图标
            fuzzySetField(naviInfoObj, "type", 1);              // 找 type 填 1 (Vendor?)
            fuzzySetField(naviInfoObj, "status", 1);            // 找 status 填 1 (Navigating)

            fuzzySetField(naviInfoObj, "distance", segmentDis); // 找 distance 填距离
            fuzzySetField(naviInfoObj, "remain", routeRemainDis); // 找 remain 填剩余距离

            // 3. 调用 updateNaviInfo(NaviInfo)
            XposedHelpers.callMethod(naviInteractionInstance, "updateNaviInfo", naviInfoObj);
            
            // 4. 双保险
            try {
                XposedHelpers.callMethod(naviInteractionInstance, "updateTurnByTurnArrow", turnIcon);
            } catch (Throwable t) {}

            XposedBridge.log("NaviHook: 💉 实体注入成功: " + curRoadName);

        } catch (Throwable t) {
            XposedBridge.log("NaviHook: 注入失败: " + t);
        }
    }

    // 🧠 智能字段填充器
    private void fuzzySetField(Object obj, String keyword, Object value) {
        try {
            Field[] fields = obj.getClass().getDeclaredFields();
            for (Field f : fields) {
                f.setAccessible(true);
                String name = f.getName().toLowerCase();
                // 如果字段名包含关键字，且类型匹配
                if (name.contains(keyword.toLowerCase())) {
                    if (value instanceof Integer && (f.getType() == int.class || f.getType() == Integer.class)) {
                        f.set(obj, value);
                        // XposedBridge.log("   - 填充字段 " + f.getName() + " = " + value);
                        return; // 填一个就够了，防止填错
                    }
                    if (value instanceof String && f.getType() == String.class) {
                        f.set(obj, value);
                        // XposedBridge.log("   - 填充字段 " + f.getName() + " = " + value);
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
        // ... (API Hook 保留不变)
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

