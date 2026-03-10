package com.xsf.amaphelper;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_MAP = "com.autonavi.amapauto";
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String TAG = "【NaviFixer V5】";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {

        String currentPkg = lpparam.packageName;

        if (!currentPkg.equals(PKG_MAP) && !currentPkg.equals(PKG_SERVICE)) {
            return;
        }

        XposedBridge.log(TAG + "安全模式启动，当前潜入: " + currentPkg);

        // =====================================================================
        // 核心 1：统一属性伪装 (骗过高德和 LBSNavi)
        // =====================================================================
        try {
            Class<?> sysPropClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader);
            XC_MethodHook propHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    String value = (String) param.getResult();
                    if (value != null) {
                        if (value.contains("IHU516G") || value.contains("IHU509G") || value.contains("SX11")) {
                            String fakeValue = value.contains("SX11") ? "FS11A1" : "IHU519G";
                            param.setResult(fakeValue);
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, propHook);
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, String.class, propHook);
        } catch (Throwable t) {}

        // =====================================================================
        // 核心 2：高德地图端 —— 伪造 7.5 的 VIP 组合拳
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
                            
                            // 拦截到 10117 屏幕控制
                            if (keyType == 10117) {
                                int status = intent.getIntExtra("EXTSCREEN_STATUS_INFO", -1);
                                if (status == 2) {
                                    intent.putExtra("EXTSCREEN_STATUS_INFO", 0);
                                    XposedBridge.log(TAG + "🚨 [高德] 强行将 10117 状态改为 0 (开屏)");
                                }
                                
                                // 【组合拳 1】：代发 10019 (116) 首帧就绪信号
                                Intent fake10019 = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                                fake10019.putExtra("KEY_TYPE", 10019);
                                fake10019.putExtra("EXTRA_CURRENT_STATE", 116);
                                context.sendBroadcast(fake10019); 
                                XposedBridge.log(TAG + "💥 [高德] 组合拳 1/2: 发射 10019(116) 首帧破门信号！");

                                // 【组合拳 2】：伪造高德 7.5 专属的 10122 仪表控制协议
                                Intent fake10122 = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                                fake10122.putExtra("KEY_TYPE", 10122);
                                fake10122.putExtra("EXTRA_EXTERNAL_MAP_LEVEL", 17);
                                fake10122.putExtra("EXTRA_EXTERNAL_MAP_MODE", 3);
                                context.sendBroadcast(fake10122);
                                XposedBridge.log(TAG + "💥 [高德] 组合拳 2/2: 发射 10122(7.5专属暗号) VIP放行信号！");
                            }
                        }
                    }
                });
            } catch (Throwable t) {}

            // JNI 状态强改 (安抚 C++ 引擎)
            try {
                Class<?> protocolClass = XposedHelpers.findClassIfExists("com.autonavi.amapauto.jni.protocol.AndroidProtocolExe", lpparam.classLoader);
                if (protocolClass != null) {
                    XposedHelpers.findAndHookMethod(protocolClass, "sendExScreenStatus", int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if ((int) param.args[0] == 2) {
                                param.args[0] = 0;
                            }
                        }
                    });
                }
            } catch (Throwable t) {}
        }

        // =====================================================================
        // 核心 3：LBSNavi 系统端 —— 监听是否收到“包裹”
        // =====================================================================
        if (currentPkg.equals(PKG_SERVICE)) {
            try {
                // 监听 LBSNavi 收到的广播，确认组合拳是否打到了肉上！
                Class<?> receiverClass = XposedHelpers.findClass("android.content.BroadcastReceiver", lpparam.classLoader);
                XposedBridge.hookAllMethods(receiverClass, "onReceive", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Intent intent = (Intent) param.args[1];
                        if (intent != null && intent.getAction() != null) {
                            if (intent.getAction().contains("AUTONAVI")) {
                                int keyType = intent.getIntExtra("KEY_TYPE", -1);
                                if (keyType == 10117 || keyType == 10019 || keyType == 10122) {
                                    XposedBridge.log(TAG + "📥 [LBSNavi] 成功接收到高德广播！协议号: " + keyType);
                                }
                            }
                        }
                    }
                });
            } catch (Throwable t) {}
        }
    }
}