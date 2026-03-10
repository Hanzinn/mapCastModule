package com.xsf.amaphelper;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_MAP = "com.autonavi.amapauto";
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String TAG = "【NaviFixer V7】";

    private static boolean isSpoofing = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        
        if (lpparam == null || lpparam.packageName == null) return;
        String currentPkg = lpparam.packageName;

        if (!PKG_MAP.equals(currentPkg) && !PKG_SERVICE.equals(currentPkg)) {
            return;
        }

        XposedBridge.log(TAG + "启动！当前潜入: " + currentPkg);

        // =====================================================================
        // 核心 1：全局属性伪装 (恢复关键的 easdemon_support = 1)
        // =====================================================================
        try {
            Class<?> sysPropClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader);
            XC_MethodHook propHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    String value = (String) param.getResult();
                    // 伪装车型
                    if (value != null && (value.contains("IHU516G") || value.contains("IHU509G") || value.contains("SX11"))) {
                        param.setResult(value.contains("SX11") ? "FS11A1" : "IHU519G");
                    }
                    // 【关键修复】：强行打开底层守护进程开关！
                    if (key != null && key.contains("easdemon_support")) {
                        param.setResult("1");
                    }
                }
            };
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, propHook);
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, String.class, propHook);
        } catch (Throwable t) {}

        // =====================================================================
        // 核心 2：高德地图 —— 显式破门
        // =====================================================================
        if (PKG_MAP.equals(currentPkg)) {
            try {
                XposedHelpers.findAndHookMethod(ContextWrapper.class, "sendBroadcast", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isSpoofing) return;

                        Intent intent = (Intent) param.args[0];
                        if (intent == null || intent.getExtras() == null) return;

                        int keyType = intent.getIntExtra("KEY_TYPE", -1);
                        if (keyType == 10117) {
                            int status = intent.getIntExtra("EXTSCREEN_STATUS_INFO", -1);
                            if (status == 2) {
                                intent.putExtra("EXTSCREEN_STATUS_INFO", 0);
                                intent.setPackage(PKG_SERVICE); 
                                XposedBridge.log(TAG + "🚨 [高德] 10117 状态强改 0 并发往 LBSNavi!");
                            }

                            isSpoofing = true;
                            // 发射 10019
                            Intent fake10019 = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                            fake10019.putExtra("KEY_TYPE", 10019);
                            fake10019.putExtra("EXTRA_CURRENT_STATE", 116);
                            fake10019.setPackage(PKG_SERVICE);
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, new Object[]{fake10019});
                            XposedBridge.log(TAG + "💥 [高德] 显式发射 10019(116) 首帧就绪信号！");

                            // 发射 10122 (7.5专属VIP)
                            Intent fake10122 = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                            fake10122.putExtra("KEY_TYPE", 10122);
                            fake10122.putExtra("EXTRA_EXTERNAL_MAP_LEVEL", 17);
                            fake10122.putExtra("EXTRA_EXTERNAL_MAP_MODE", 3);
                            fake10122.setPackage(PKG_SERVICE);
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, new Object[]{fake10122});
                            XposedBridge.log(TAG + "💥 [高德] 显式发射 10122 VIP 放行信号！");
                            isSpoofing = false;
                        }
                    }
                });
            } catch (Throwable t) {}

            try {
                Class<?> protocolClass = XposedHelpers.findClassIfExists("com.autonavi.amapauto.jni.protocol.AndroidProtocolExe", lpparam.classLoader);
                if (protocolClass != null) {
                    XposedHelpers.findAndHookMethod(protocolClass, "sendExScreenStatus", int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if ((int) param.args[0] == 2) param.args[0] = 0;
                        }
                    });
                }
            } catch (Throwable t) {}
        }

        // =====================================================================
        // 核心 3：LBSNavi 系统服务 —— 反休眠 & 内部总线监听 (你 V283 的神操作)
        // =====================================================================
        if (PKG_SERVICE.equals(currentPkg)) {
            // 【关键补全】：绕过休眠检测，强行叫醒 LBSNavi！
            try {
                Class<?> cfg = XposedHelpers.findClassIfExists("ecarx.naviservice.map.co", lpparam.classLoader);
                if (cfg != null) {
                    XposedHelpers.findAndHookMethod(cfg, "g", XC_MethodReplacement.returnConstant(true));
                    XposedBridge.log(TAG + "✅ [LBSNavi] 已强行关闭休眠检测，强制唤醒待命！");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "休眠破解失败: " + t.getMessage());
            }

            // 【超强雷达】：使用你 V283 的内部 RxBus 总线雷达！只要处理了广播，总线必有反应！
            try {
                XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        ClassLoader cl = ((Context) param.thisObject).getClassLoader();
                        try {
                            Class<?> rxBusClass = XposedHelpers.findClass("ecarx.naviservice.d.e", cl);
                            XposedBridge.hookAllMethods(rxBusClass, "a", new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    if (param.args == null || param.args.length == 0 || param.args[0] == null) return;
                                    Object eventObj = param.args[0];
                                    if ("bz".equals(eventObj.getClass().getSimpleName())) {
                                        int eventCode = (int) XposedHelpers.callMethod(eventObj, "b");
                                        XposedBridge.log(TAG + "🎯 [LBS-RxBus] 内部总线开始处理高德指令！Code: " + eventCode);
                                    }
                                }
                            });
                        } catch (Throwable t) {}
                    }
                });
            } catch (Throwable t) {}
        }
    }
}