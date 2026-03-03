package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals("com.xsf.amaphelper")) {
            XposedHelpers.findAndHookMethod("com.xsf.amaphelper.MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // ==========================================
        // 监听端 A：高德地图 7.5 (窃听它发出的一切)
        // ==========================================
        if (lpparam.packageName.equals("com.autonavi.amapauto")) {
            
            // 1. 窃听高德向系统发出的所有广播 (可能包含导航状态)
            XposedBridge.hookAllMethods(android.content.ContextWrapper.class, "sendBroadcast", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Intent)) return;
                    Intent intent = (Intent) param.args[0];
                    String action = intent.getAction();
                    if (action != null && (action.toUpperCase().contains("AUTONAVI") || action.toUpperCase().contains("ECARX"))) {
                        StringBuilder sb = new StringBuilder();
                        Bundle extras = intent.getExtras();
                        if (extras != null) {
                            for (String key : extras.keySet()) {
                                sb.append(key).append("=").append(extras.get(key)).append("; ");
                            }
                        }
                        XposedBridge.log("NaviSpy-Dual: [AMAP->发广播] Action=" + action + " | Extras=" + sb.toString());
                    }
                }
            });

            // 2. 窃听高德的哪个 Service 被车机绑定了
            XposedBridge.hookAllMethods(Service.class, "onBind", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Intent intent = (Intent) param.args[0];
                    Service service = (Service) param.thisObject;
                    XposedBridge.log("NaviSpy-Dual: [AMAP->被绑定] Service=" + service.getClass().getSimpleName() + " | Action=" + (intent != null ? intent.getAction() : "null"));
                }
            });
        }

        // ==========================================
        // 监听端 B：车机 LBSNavi (窃听它与高德和仪表的交互)
        // ==========================================
        if (lpparam.packageName.equals("ecarx.naviservice")) {
            
            // 1. 窃听系统主动去绑定高德的什么服务
            XposedBridge.hookAllMethods(android.content.ContextWrapper.class, "bindService", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Intent)) return;
                    Intent intent = (Intent) param.args[0];
                    if (intent.getComponent() != null && intent.getComponent().getPackageName().contains("autonavi")) {
                        XposedBridge.log("NaviSpy-Dual: [SYS->发绑定] 目标组件=" + intent.getComponent().getClassName());
                    }
                }
            });

            // 2. 窃听系统发给仪表的最终状态序列 (最重要！)
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    ClassLoader cl = ctx.getClassLoader();
                    try {
                        Class<?> mgrClass = XposedHelpers.findClass("ecarx.naviservice.a.a", cl);
                        XposedBridge.hookAllMethods(mgrClass, "a", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args == null || param.args.length == 0) return;
                                Object infoObj = param.args[0];
                                if (infoObj == null) return;
                                
                                String className = infoObj.getClass().getSimpleName();
                                if ("MapStatusInfo".equals(className)) {
                                    int status = XposedHelpers.getIntField(infoObj, "status");
                                    XposedBridge.log("NaviSpy-Dual: [SYS->仪表] 转发 MapStatusInfo -> " + status);
                                } else if ("MapSwitchingInfo".equals(className)) {
                                    int state = XposedHelpers.getIntField(infoObj, "mSwitchState");
                                    XposedBridge.log("NaviSpy-Dual: [SYS->仪表] 转发 MapSwitchingInfo -> " + state);
                                }
                            }
                        });
                        XposedBridge.log("NaviSpy-Dual: ✅ 车机侧系统探针安插成功！等待高德 7.5...");
                    } catch (Throwable t) {
                        XposedBridge.log("NaviSpy-Dual: ❌ 系统探针失败: " + t);
                    }
                }
            });
        }
    }
}