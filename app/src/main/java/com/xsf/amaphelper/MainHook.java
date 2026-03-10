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
    private static final String TAG = "【NaviFixer】";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {

        String currentPkg = lpparam.packageName;

        if (!currentPkg.equals(PKG_MAP) && !currentPkg.equals(PKG_SERVICE)) {
            return;
        }

        // =====================================================================
        // ⚔️ 阵列 A：高德地图端 (执行欺骗与强制覆写手术)
        // =====================================================================
        if (currentPkg.equals(PKG_MAP)) {
            XposedBridge.log(TAG + "成功注入高德地图，开始执行三重破解手术...");

            // 1. 属性伪装
            try {
                Class<?> sysPropClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader);
                XC_MethodHook propHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        String value = (String) param.getResult();
                        if (value != null) {
                            if (value.contains("IHU516G") || value.contains("IHU509G") || value.contains("SX11")) {
                                String fakeValue = "IHU519G";
                                if (value.contains("SX11")) fakeValue = "FS11A1";
                                param.setResult(fakeValue);
                                XposedBridge.log(TAG + "🎭 [属性伪装-高德] 拦截读取 " + key + " -> 伪装为: " + fakeValue);
                            }
                        }
                    }
                };
                XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, propHook);
                XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, String.class, propHook);
            } catch (Throwable t) {}

            // 2. 广播强改
            try {
                XposedHelpers.findAndHookMethod(ContextWrapper.class, "sendBroadcast", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Intent intent = (Intent) param.args[0];
                        if (intent != null && intent.getExtras() != null) {
                            int keyType = intent.getIntExtra("KEY_TYPE", -1);
                            
                            if (keyType == 10117) {
                                int status = intent.getIntExtra("EXTSCREEN_STATUS_INFO", -1);
                                XposedBridge.log(TAG + "📡 [高德-发信] 准备发送 10117 指令，当前状态: " + status);
                                if (status == 2) {
                                    intent.putExtra("EXTSCREEN_STATUS_INFO", 0);
                                    XposedBridge.log(TAG + "🚨 [高德-篡改] 警告！强行将状态 2 (关屏) 改写为 0 (开屏)！");
                                }
                            }
                            if (keyType == 10019) {
                                int state = intent.getIntExtra("EXTRA_CURRENT_STATE", -1);
                                XposedBridge.log(TAG + "📡 [高德-发信] 发送 10019 状态同步 -> EXTRA_CURRENT_STATE: " + state);
                            }
                        }
                    }
                });
            } catch (Throwable t) {}

            // 3. C++ JNI 回传监控与修改
            try {
                Class<?> protocolClass = XposedHelpers.findClassIfExists("com.autonavi.amapauto.jni.protocol.AndroidProtocolExe", lpparam.classLoader);
                if (protocolClass != null) {
                    XposedHelpers.findAndHookMethod(protocolClass, "sendExScreenStatus", int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            int status = (int) param.args[0];
                            if (status == 2) {
                                XposedBridge.log(TAG + "⚙️ [JNI-篡改] 拦截向 C++ 引擎回传状态 2，强行修改为 0！");
                                param.args[0] = 0;
                            }
                        }
                    });
                }
            } catch (Throwable t) {}
        }

        // =====================================================================
        // 🎧 阵列 B：车机系统 LBSNavi 端 (纯监听，不干预)
        // =====================================================================
        if (currentPkg.equals(PKG_SERVICE)) {
            XposedBridge.log(TAG + "成功潜入 LBSNavi 系统服务，系统侧回音雷达已开启！");

            // 1. 监听 LBSNavi 是否收到了我们的广播
            try {
                Class<?> broadcastReceiverClass = XposedHelpers.findClass("android.content.BroadcastReceiver", lpparam.classLoader);
                XposedHelpers.findAndHookMethod(broadcastReceiverClass, "onReceive", Context.class, Intent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Intent intent = (Intent) param.args[1];
                        if (intent != null && intent.getAction() != null) {
                            String action = intent.getAction();
                            // 只抓取高德相关的广播
                            if (action.contains("AUTONAVI") || action.contains("ecarx") || action.contains("navi")) {
                                int keyType = intent.getIntExtra("KEY_TYPE", -1);
                                if (keyType == 10117 || keyType == 10019) {
                                    XposedBridge.log(TAG + "📥 [LBS-收信] 系统成功接收广播！KeyType: " + keyType);
                                    if (keyType == 10117) {
                                        int status = intent.getIntExtra("EXTSCREEN_STATUS_INFO", -1);
                                        XposedBridge.log(TAG + "📥 [LBS-收信] -> 提取到的屏幕控制状态: " + status);
                                    }
                                    if (keyType == 10019) {
                                        int state = intent.getIntExtra("EXTRA_CURRENT_STATE", -1);
                                        XposedBridge.log(TAG + "📥 [LBS-收信] -> 提取到的引擎状态(如116): " + state);
                                    }
                                }
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "LBSNavi 广播监听部署失败: " + t.getMessage());
            }

            // 2. 监听 LBSNavi 内部是否在读取底层属性 (防止二次拦截)
            try {
                Class<?> sysPropClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader);
                XC_MethodHook propHookLbs = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String key = (String) param.args[0];
                        String value = (String) param.getResult();
                        if (key != null && (key.contains("easdemon") || key.contains("product"))) {
                            XposedBridge.log(TAG + "🔍 [LBS-查岗] 系统内部读取属性 -> Key: " + key + " | Value: " + value);
                        }
                    }
                };
                XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, propHookLbs);
                XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, String.class, propHookLbs);
            } catch (Throwable t) {}
        }
    }
}