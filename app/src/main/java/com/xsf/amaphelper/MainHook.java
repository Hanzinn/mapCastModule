package com.xsf.amaphelper;

import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_MAP = "com.autonavi.amapauto";
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String TAG = "【NaviFixer V3】";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {

        String currentPkg = lpparam.packageName;

        if (!currentPkg.equals(PKG_MAP) && !currentPkg.equals(PKG_SERVICE)) {
            return;
        }

        XposedBridge.log(TAG + "成功潜入: " + currentPkg + "，开始执行终极破解...");

        // =====================================================================
        // 核心突破 1：全域属性伪装 (高德和 LBSNavi 必须统一口径！)
        // =====================================================================
        try {
            Class<?> sysPropClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader);
            XC_MethodHook propHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    String value = (String) param.getResult();
                    if (value != null) {
                        // 统一把低配缤瑞伪装成高配星瑞！
                        if (value.contains("IHU516G") || value.contains("IHU509G") || value.contains("SX11")) {
                            String fakeValue = "IHU519G";
                            if (value.contains("SX11")) fakeValue = "FS11A1";
                            param.setResult(fakeValue);
                            XposedBridge.log(TAG + "🎭 [" + currentPkg + "] 强行伪装属性 " + key + " -> 假冒为: " + fakeValue);
                        }
                        // 如果系统查 easdemon_support，强行返回 1
                        if (key != null && key.contains("easdemon_support")) {
                            param.setResult("1");
                            XposedBridge.log(TAG + "🎭 [" + currentPkg + "] 强行伪装 easdemon_support -> 1 (开启底层服务)");
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, propHook);
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, String.class, propHook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "属性伪装失败: " + t.getMessage());
        }

        // =====================================================================
        // 核心突破 2：高德广播强改与“强制踹门”
        // =====================================================================
        if (currentPkg.equals(PKG_MAP)) {
            try {
                XposedHelpers.findAndHookMethod(ContextWrapper.class, "sendBroadcast", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        ContextWrapper context = (ContextWrapper) param.thisObject;
                        Intent intent = (Intent) param.args[0];
                        
                        if (intent != null && intent.getExtras() != null) {
                            int keyType = intent.getIntExtra("KEY_TYPE", -1);
                            
                            // 拦截 10117 屏幕控制
                            if (keyType == 10117) {
                                int status = intent.getIntExtra("EXTSCREEN_STATUS_INFO", -1);
                                if (status == 2) {
                                    intent.putExtra("EXTSCREEN_STATUS_INFO", 0);
                                    XposedBridge.log(TAG + "🚨 [高德-篡改] 强行将 10117 状态 2 改为 0 (请求开屏)！");
                                }
                                
                                // 【终极杀招】：只要发出 10117 (0)，我们立刻手动伪造 116 首帧信号！代替高德踹门！
                                XposedBridge.log(TAG + "💥 [高德-踹门] 正在代发 10019 (116) 首帧就绪信号...");
                                Intent fakeIntent = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                                fakeIntent.putExtra("KEY_TYPE", 10019);
                                fakeIntent.putExtra("EXTRA_CURRENT_STATE", 116);
                                context.sendBroadcast(fakeIntent); // 手动发射！
                                XposedBridge.log(TAG + "💥 [高德-踹门] 116 破门信号已发射！大门必须开！");
                            }
                            
                            // 监控 10019
                            if (keyType == 10019) {
                                int state = intent.getIntExtra("EXTRA_CURRENT_STATE", -1);
                                if (state == 116) {
                                    XposedBridge.log(TAG + "✅ [合法通行] 捕获到真实的 116 信号！");
                                }
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "广播拦截失败: " + t.getMessage());
            }

            // 拦截 JNI 底层状态回传
            try {
                Class<?> protocolClass = XposedHelpers.findClassIfExists("com.autonavi.amapauto.jni.protocol.AndroidProtocolExe", lpparam.classLoader);
                if (protocolClass != null) {
                    XposedHelpers.findAndHookMethod(protocolClass, "sendExScreenStatus", int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            int status = (int) param.args[0];
                            if (status == 2) {
                                param.args[0] = 0;
                                XposedBridge.log(TAG + "⚙️ [JNI-篡改] 强行修改 C++ 底层回传状态为 0！");
                            }
                        }
                    });
                }
            } catch (Throwable t) {}
        }
    }
}