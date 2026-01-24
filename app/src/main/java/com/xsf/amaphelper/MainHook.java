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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
    private static final String INTERFACE_NAVI_INFO = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction$INavigationInfo";
    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

    private static String curRoadName = "等待高德...";
    private static String nextRoadName = "";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;
    private static int currentVendor = 2; 

    private static Object dashboardManagerInstance = null;
    private static Object naviInteractionInstance = null;
    private static Method updateNaviInfoMethod = null; // 缓存找到的方法
    private static boolean isHookReady = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V77 暴力反射版启动");
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
            Class<?> mgrClass = XposedHelpers.findClass(CLASS_DASHBOARD_MGR, cl);
            Field instanceField = XposedHelpers.findField(mgrClass, FIELD_INSTANCE);
            instanceField.setAccessible(true);
            dashboardManagerInstance = instanceField.get(null);
            
            if (dashboardManagerInstance != null) {
                Field interactionField = XposedHelpers.findField(mgrClass, FIELD_INTERACTION);
                interactionField.setAccessible(true);
                naviInteractionInstance = interactionField.get(dashboardManagerInstance);
                
                if (naviInteractionInstance != null) {
                    XposedBridge.log("NaviHook: 🎉 捕获硬件接口: " + naviInteractionInstance.getClass().getName());
                    
                    // 🌟🌟🌟 V77 核心：手动查找方法 🌟🌟🌟
                    Method[] methods = naviInteractionInstance.getClass().getMethods();
                    for (Method m : methods) {
                        if (m.getName().equals("updateNaviInfo")) {
                            updateNaviInfoMethod = m;
                            updateNaviInfoMethod.setAccessible(true);
                            XposedBridge.log("NaviHook: ✅ 锁定目标方法: " + m.toString());
                            break;
                        }
                    }
                    
                    if (updateNaviInfoMethod == null) {
                        XposedBridge.log("NaviHook: ❌ 致命：未找到 updateNaviInfo 方法！");
                        // 打印所有方法以供调试
                        for (Method m : methods) XposedBridge.log("Found: " + m.getName());
                    } else {
                        isHookReady = true;
                        updateClusterDirectly(cl);
                    }

                } else {
                    XposedBridge.log("NaviHook: ⚠️ 硬件接口为空");
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
                                if (isHookReady) {
                                    updateClusterDirectly(context.getClassLoader());
                                } else {
                                    captureCoreObjects(context.getClassLoader());
                                }
                            }
                        }
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                             currentVendor = intent.getIntExtra("vendor", 2);
                             XposedBridge.log("NaviHook: 伪装 Vendor=" + currentVendor);
                        }
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            captureCoreObjects(context.getClassLoader());
                            curRoadName = "强制测试 V77";
                            turnIcon = 2;
                            updateClusterDirectly(context.getClassLoader());
                        }
                    } catch (Throwable t) {}
                }
            };
            
            IntentFilter filter = new IntentFilter();
            filter.addAction(AMAP_ACTION);
            filter.addAction("XSF_ACTION_SET_VENDOR");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            context.registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    private void updateClusterDirectly(ClassLoader cl) {
        if (naviInteractionInstance == null || updateNaviInfoMethod == null) return;
        
        try {
            Class<?> naviInfoInterface = XposedHelpers.findClass(INTERFACE_NAVI_INFO, cl);
            
            Object proxyNaviInfo = Proxy.newProxyInstance(cl, new Class[]{naviInfoInterface}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if ("getCurrentRoadName".equals(name)) return curRoadName;
                    if ("getNextGuidancePointName".equals(name)) return nextRoadName;
                    if ("getIconType".equals(name)) return turnIcon;
                    if ("getDistanceToNextGuidancePoint".equals(name)) return segmentDis;
                    if ("getRouteRemainDistance".equals(name)) return routeRemainDis;
                    if ("getRouteRemainTime".equals(name)) return routeRemainTime;
                    if ("getNavigateStatus".equals(name)) return 1; 
                    
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == double.class) return 0.0;
                    if (method.getReturnType() == String.class) return "";
                    return null;
                }
            });

            // 🌟 使用反射直接调用，绕过参数类型检查
            updateNaviInfoMethod.invoke(naviInteractionInstance, proxyNaviInfo);
            
            // 顺手刷一下箭头
            XposedHelpers.callMethod(naviInteractionInstance, "updateTurnByTurnArrow", turnIcon);

            XposedBridge.log("NaviHook: 💉 暴力注入成功: " + curRoadName);

        } catch (Throwable t) {
            XposedBridge.log("NaviHook: 注入失败: " + t);
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
        // ... (保持 API Hook 作为备份)
    }
}

