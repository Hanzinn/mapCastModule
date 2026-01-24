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
    // 🌟 目标进程：亿咖通导航服务
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    // 🎯 核心类名 (基于你提供的 a.zip/a.smali)
    private static final String CLASS_DASHBOARD_MGR = "ecarx.naviservice.a.a"; 
    // 🎯 核心字段名 (在 a.smali 中是 private d:Lcom/ecarx/xui/adaptapi/diminteraction/INaviInteraction;)
    private static final String FIELD_INTERACTION = "d"; 
    // 🎯 核心单例字段 (在 a.smali 中是 private static b:Lecarx/naviservice/a/a;)
    private static final String FIELD_INSTANCE = "b";

    // 硬件接口定义
    private static final String INTERFACE_NAVI_INFO = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction$INavigationInfo";
    
    // 数据模型基类 (用于身份欺诈)
    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

    // 数据仓库
    private static String curRoadName = "等待高德...";
    private static String nextRoadName = "";
    private static int turnIcon = 2;
    private static int segmentDis = 0;
    private static int routeRemainDis = 0;
    private static int routeRemainTime = 0;
    private static int currentVendor = 2; // 默认伪装成 Vendor 2

    // 核心对象引用
    private static Object dashboardManagerInstance = null;
    private static Object naviInteractionInstance = null;
    private static boolean isHookReady = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 1. 自身激活状态
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 2. 只处理 LBSNavi 进程
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V75 最终降维打击方案启动: " + lpparam.processName);
        
        initLBSHook(lpparam);
        hookNaviBaseModel(lpparam.classLoader);
    }

    private void initLBSHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook Application onCreate 以注册广播并获取 Context
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    registerReceiver(context);
                    
                    // 延迟 5 秒去“偷”核心对象，确保 LBSNavi 初始化完毕
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                         captureCoreObjects(lpparam.classLoader);
                    }, 5000);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: onCreate Hook 失败: " + t);
        }
        
        // 保持 OpenAPI Hook 作为双重保险
        hookApiByReflection(lpparam);
    }

    // 🕵️‍♂️ 策略一：身份欺诈
    // 强制修改所有数据包的 Vendor ID，骗过 LBSNavi 的安检
    private void hookNaviBaseModel(ClassLoader cl) {
        try {
            Class<?> baseModelClass = XposedHelpers.findClassIfExists(CLASS_NAVI_BASE_MODEL, cl);
            if (baseModelClass != null) {
                XposedHelpers.findAndHookMethod(baseModelClass, "getMapVendor", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        // 无论原本是谁发的包，现在都是我们指定的 Vendor
                        return currentVendor > 0 ? currentVendor : 1; 
                    }
                });
                XposedBridge.log("NaviHook: ✅ 身份欺诈模块已激活 (MapVendor)");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: ⚠️ 身份欺诈模块挂载失败: " + t);
        }
    }

    // 🕵️‍♂️ 策略二：核心窃取
    // 直接从 DashboardManager 单例中拿出 INaviInteraction 接口
    private void captureCoreObjects(ClassLoader cl) {
        try {
            Class<?> mgrClass = XposedHelpers.findClass(CLASS_DASHBOARD_MGR, cl);
            
            // 1. 获取单例
            Field instanceField = XposedHelpers.findField(mgrClass, FIELD_INSTANCE);
            instanceField.setAccessible(true);
            dashboardManagerInstance = instanceField.get(null);
            
            if (dashboardManagerInstance != null) {
                XposedBridge.log("NaviHook: ✅ 捕获 NaviDashboardManager 单例");
                
                // 2. 获取接口
                Field interactionField = XposedHelpers.findField(mgrClass, FIELD_INTERACTION);
                interactionField.setAccessible(true);
                naviInteractionInstance = interactionField.get(dashboardManagerInstance);
                
                if (naviInteractionInstance != null) {
                    XposedBridge.log("NaviHook: 🎉🎉🎉 成功窃取 INaviInteraction 令牌！Ready to fire!");
                    isHookReady = true;
                    // 立即测试一发
                    updateClusterDirectly(cl); 
                } else {
                    XposedBridge.log("NaviHook: ⚠️ Manager 获取成功，但 Interaction 字段为空 (硬件未连接?)");
                }
            } else {
                XposedBridge.log("NaviHook: ❌ Manager 单例尚为空，等待下一次捕获");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: 捕获核心对象异常: " + t);
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
                            // 过滤垃圾包
                            int keyType = intent.getIntExtra("KEY_TYPE", 0);
                            if (keyType == 10065) return; 

                            Bundle b = intent.getExtras();
                            if (b != null) {
                                b.keySet(); // 解包
                                extractData(b);
                                
                                // 🔥 收到广播 -> 直接操作硬件
                                if (isHookReady) {
                                    updateClusterDirectly(context.getClassLoader());
                                } else {
                                    // 还没 ready? 再试一次
                                    captureCoreObjects(context.getClassLoader());
                                }
                            }
                        }
                        else if ("XSF_ACTION_SET_VENDOR".equals(action)) {
                             currentVendor = intent.getIntExtra("vendor", 2);
                             XposedBridge.log("NaviHook: 切换伪装身份为 Vendor " + currentVendor);
                        }
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            // 手动触发重连逻辑
                            captureCoreObjects(context.getClassLoader());
                            curRoadName = "强制测试";
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

    // 🔥 核心攻击：利用动态代理构造数据包，直接喂给硬件接口
    private void updateClusterDirectly(ClassLoader cl) {
        if (naviInteractionInstance == null) return;
        
        try {
            // 1. 动态生成一个 INavigationInfo 接口的实现类
            Class<?> naviInfoInterface = XposedHelpers.findClass(INTERFACE_NAVI_INFO, cl);
            
            Object proxyNaviInfo = Proxy.newProxyInstance(cl, new Class[]{naviInfoInterface}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    // 根据接口定义，返回我们的数据
                    if ("getCurrentRoadName".equals(name)) return curRoadName;
                    if ("getNextGuidancePointName".equals(name)) return nextRoadName;
                    if ("getIconType".equals(name)) return turnIcon;
                    if ("getDistanceToNextGuidancePoint".equals(name)) return segmentDis;
                    if ("getRouteRemainDistance".equals(name)) return routeRemainDis;
                    if ("getRouteRemainTime".equals(name)) return routeRemainTime;
                    if ("getNavigateStatus".equals(name)) return 1; // 1 = 导航中 (关键！)
                    
                    // 默认返回值处理
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == double.class) return 0.0;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == String.class) return "";
                    return null;
                }
            });

            // 2. 调用 INaviInteraction.updateNaviInfo(info)
            XposedHelpers.callMethod(naviInteractionInstance, "updateNaviInfo", proxyNaviInfo);
            
            // 3. 额外调用 updateTurnByTurnArrow (有些旧版仪表只认这个)
            try {
                XposedHelpers.callMethod(naviInteractionInstance, "updateTurnByTurnArrow", turnIcon);
            } catch (Throwable t) {}

            XposedBridge.log("NaviHook: 💉 硬件注入成功: " + curRoadName + " [Icon=" + turnIcon + "]");

        } catch (Throwable t) {
            XposedBridge.log("NaviHook: 硬件注入失败: " + t);
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

