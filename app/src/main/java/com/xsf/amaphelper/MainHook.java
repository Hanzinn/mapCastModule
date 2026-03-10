package com.xsf.amaphelper;

import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_MAP = "com.autonavi.amapauto";
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String TAG = "【NaviRadar】";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {

        String currentPkg = lpparam.packageName;

        // 只监听高德地图和吉利导航服务
        if (!currentPkg.equals(PKG_MAP) && !currentPkg.equals(PKG_SERVICE)) {
            return;
        }

        XposedBridge.log(TAG + "成功潜入进程: " + currentPkg + "，雷达已开启！");

        // ==========================================
        // 探头 1：系统属性读取监听 (抓取身份判定和功能开关)
        // ==========================================
        try {
            Class<?> sysPropClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader);
            XC_MethodHook propHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    String value = (String) param.getResult();
                    
                    // 不写死！只要 key 包含以下关键字，统统记录下来！
                    if (key != null && (key.contains("ecarx") || key.contains("product") || 
                        key.contains("eas") || key.contains("gkui") || key.contains("navi") || 
                        key.contains("display") || key.contains("board"))) {
                        
                        XposedBridge.log(TAG + "[" + currentPkg + "] 读取系统属性 -> Key: " + key + " | Value: " + value);
                    }
                }
            };
            // 监听 SystemProperties.get(String)
            XposedBridge.hookAllMethods(sysPropClass, "get", propHook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "探头1 (SystemProp) 部署失败: " + t.getMessage());
        }

        // ==========================================
        // 探头 2：全量广播监听 (抓取开屏通讯密码)
        // ==========================================
        try {
            XposedHelpers.findAndHookMethod(ContextWrapper.class, "sendBroadcast", Intent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Intent intent = (Intent) param.args[0];
                    if (intent != null && intent.getAction() != null) {
                        String action = intent.getAction();
                        
                        // 不写死 10117！只要包含 autonavi 或 ecarx，全抓！
                        if (action.toLowerCase().contains("autonavi") || action.toLowerCase().contains("ecarx") || action.toLowerCase().contains("navi")) {
                            XposedBridge.log(TAG + "[" + currentPkg + "] 发射广播 -> Action: " + action);
                            
                            Bundle extras = intent.getExtras();
                            if (extras != null) {
                                Set<String> keys = extras.keySet();
                                for (String key : keys) {
                                    XposedBridge.log(TAG + "    |- 参数: " + key + " = " + extras.get(key));
                                }
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "探头2 (Broadcast) 部署失败: " + t.getMessage());
        }

        // ==========================================
        // 探头 3：C++ 底层 JNI 桥梁监听 (高德专属，抓取底层开屏指令)
        // ==========================================
        if (currentPkg.equals(PKG_MAP)) {
            try {
                Class<?> protocolClass = XposedHelpers.findClassIfExists("com.autonavi.amapauto.jni.protocol.AndroidProtocolExe", lpparam.classLoader);
                if (protocolClass != null) {
                    XposedHelpers.findAndHookMethod(protocolClass, "onOperateExscreenNotified", int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log(TAG + "[高德-JNI] 触发 C++ 层投屏指令 (onOperateExscreenNotified)!");
                            XposedBridge.log(TAG + "    |- operateType(操作类型): " + param.args[0]);
                            XposedBridge.log(TAG + "    |- windowId(窗口ID): " + param.args[1]);
                            XposedBridge.log(TAG + "    |- mode(模式): " + param.args[2]);
                        }
                    });
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "探头3 (JNI) 部署失败，可能非高德7.5版本");
            }
        }

        // ==========================================
        // 探头 4：亿咖通 EAS SDK 底层通道监听 (抓取 Protocol ID 4102 等)
        // ==========================================
        if (currentPkg.equals(PKG_MAP)) {
            try {
                // 监听 b 类 (INaviServer 代理)
                Class<?> naviClassB = XposedHelpers.findClassIfExists("com.ecarx.eas.framework.sdk.navi.b", lpparam.classLoader);
                if (naviClassB != null) {
                    XC_MethodHook protocolHook = new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object naviBaseModel = param.args[0];
                            if (naviBaseModel != null) {
                                int protocolId = (int) XposedHelpers.callMethod(naviBaseModel, "getProtocolID");
                                XposedBridge.log(TAG + "[高德-EAS-SDK] 向底层发送指令 -> Protocol ID: " + protocolId);
                            }
                        }
                    };
                    // 同时监听同步和异步方法
                    XposedBridge.hookAllMethods(naviClassB, "invokeAPISync", protocolHook);
                    XposedBridge.hookAllMethods(naviClassB, "invokeAPIAsync", protocolHook);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "探头4 (EAS-SDK) 部署失败");
            }
        }
    }
}