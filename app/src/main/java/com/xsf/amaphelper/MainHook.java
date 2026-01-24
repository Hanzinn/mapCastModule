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
import java.util.Arrays;
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
    private static final String INTERFACE_NAVI_INFO = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction$INavigationInfo";
    private static final String CLASS_NAVI_BASE_MODEL = "com.ecarx.sdk.navi.model.base.NaviBaseModel";

    // 数据
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
    private static boolean isHookReady = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V76 扫描模式启动");
        
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
                    XposedBridge.log("NaviHook: 🎉 捕获到硬件接口对象!");
                    isHookReady = true;
                    
                    // 🌟🌟🌟 V76 核心：打印所有方法签名 🌟🌟🌟
                    printAllMethods(naviInteractionInstance);
                    
                } else {
                    XposedBridge.log("NaviHook: ⚠️ 硬件接口对象为空");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: 捕获异常: " + t);
        }
    }

    // 🖨️ 扫描仪逻辑
    private void printAllMethods(Object obj) {
        try {
            XposedBridge.log("------ [开始扫描硬件接口方法] ------");
            XposedBridge.log("类名: " + obj.getClass().getName());
            
            Method[] methods = obj.getClass().getDeclaredMethods(); // 获取所有方法（包括私有）
            for (Method m : methods) {
                StringBuilder sb = new StringBuilder();
                sb.append("方法: ").append(m.getName()).append("(");
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    sb.append(params[i].getName());
                    if (i < params.length - 1) sb.append(", ");
                }
                sb.append(")");
                XposedBridge.log(sb.toString());
            }
            XposedBridge.log("------ [扫描结束] ------");
        } catch (Exception e) {
            XposedBridge.log("扫描失败: " + e);
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
                            
                            // 收到广播，尝试执行
                            if (isHookReady) {
                                // 这里先只调用最简单的接口，防止崩溃
                                tryUpdateArrow(context.getClassLoader());
                            } else {
                                captureCoreObjects(context.getClassLoader());
                            }
                        }
                        else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                            captureCoreObjects(context.getClassLoader());
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
    
    // 只尝试更新箭头，这是最不可能出错的方法
    private void tryUpdateArrow(ClassLoader cl) {
        if (naviInteractionInstance == null) return;
        try {
             // 尝试调用 updateTurnByTurnArrow(int)
            XposedHelpers.callMethod(naviInteractionInstance, "updateTurnByTurnArrow", turnIcon);
            XposedBridge.log("NaviHook: 尝试更新箭头: " + turnIcon);
        } catch (Throwable t) {
            // 忽略错误，等待扫描结果
        }
    }

    private void hookApiByReflection(XC_LoadPackage.LoadPackageParam lpparam) {
        // ... (保持原有的 API Hook，这里为了节省篇幅省略，实际上请保留，作为保底)
        // 你可以直接复制 V75 的这部分代码
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

