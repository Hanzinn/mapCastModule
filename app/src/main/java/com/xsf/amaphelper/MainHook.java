package com.xsf.amaphelper;

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
    private static final String TAG = "【NaviFixer V6】";

    // 防止广播死循环的开关
    private static boolean isSpoofing = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {

        String currentPkg = lpparam.packageName;

        if (!currentPkg.equals(PKG_MAP) && !currentPkg.equals(PKG_SERVICE)) {
            return;
        }

        XposedBridge.log(TAG + "启动！当前潜入: " + currentPkg);

        // =====================================================================
        // 核心 1：统一属性伪装 (骗过高德和 LBSNavi)
        // =====================================================================
        try {
            Class<?> sysPropClass = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader);
            XC_MethodHook propHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String value = (String) param.getResult();
                    if (value != null && (value.contains("IHU516G") || value.contains("IHU509G") || value.contains("SX11"))) {
                        param.setResult(value.contains("SX11") ? "FS11A1" : "IHU519G");
                    }
                }
            };
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, propHook);
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String.class, String.class, propHook);
        } catch (Throwable t) {}

        // =====================================================================
        // 核心 2：高德地图 —— 定向爆破 (强制显式广播)
        // =====================================================================
        if (currentPkg.equals(PKG_MAP)) {
            try {
                XposedHelpers.findAndHookMethod(ContextWrapper.class, "sendBroadcast", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isSpoofing) return; // 避免自己拦截自己发出的伪造广播

                        ContextWrapper context = (ContextWrapper) param.thisObject;
                        Intent intent = (Intent) param.args[0];
                        
                        if (intent != null && intent.getExtras() != null) {
                            int keyType = intent.getIntExtra("KEY_TYPE", -1);
                            
                            if (keyType == 10117) {
                                int status = intent.getIntExtra("EXTSCREEN_STATUS_INFO", -1);
                                if (status == 2) {
                                    intent.putExtra("EXTSCREEN_STATUS_INFO", 0);
                                    // 穿透安卓 9.0 限制：强制指定原广播的目标包名！
                                    intent.setPackage(PKG_SERVICE); 
                                    XposedBridge.log(TAG + "🚨 [高德] 10117 状态强改 0，并指定发往 LBSNavi!");
                                }
                                
                                isSpoofing = true; // 开启防递归

                                // 【组合拳 1：强制显式发送 10019】
                                Intent fake10019 = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                                fake10019.putExtra("KEY_TYPE", 10019);
                                fake10019.putExtra("EXTRA_CURRENT_STATE", 116);
                                fake10019.setPackage(PKG_SERVICE); // 🎯 致命一击：强制路由给车机服务！
                                context.sendBroadcast(fake10019);
                                XposedBridge.log(TAG + "💥 [高德] 显式发射 10019(116) 首帧就绪信号！");

                                // 【组合拳 2：强制显式发送 10122 VIP 协议】
                                Intent fake10122 = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
                                fake10122.putExtra("KEY_TYPE", 10122);
                                fake10122.putExtra("EXTRA_EXTERNAL_MAP_LEVEL", 17);
                                fake10122.putExtra("EXTRA_EXTERNAL_MAP_MODE", 3);
                                fake10122.setPackage(PKG_SERVICE); // 🎯 致命一击：强制路由给车机服务！
                                context.sendBroadcast(fake10122);
                                XposedBridge.log(TAG + "💥 [高德] 显式发射 10122 VIP 放行信号！");

                                isSpoofing = false; // 关闭防递归
                            }
                        }
                    });
                } catch (Throwable t) {}

                // JNI 状态强改
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
        }

        // =====================================================================
        // 核心 3：LBSNavi 端 —— 全新雷达 (Hook Intent 读取)
        // =====================================================================
        if (currentPkg.equals(PKG_SERVICE)) {
            // 我们不 Hook Receiver，我们直接 Hook Intent 的 getIntExtra，只要 LBSNavi 试图拆包裹，我们就抓现行！
            try {
                XposedHelpers.findAndHookMethod(Intent.class, "getIntExtra", String.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String keyName = (String) param.args[0];
                        if ("KEY_TYPE".equals(keyName)) {
                            int keyValue = (int) param.getResult();
                            if (keyValue == 10117 || keyValue == 10019 || keyValue == 10122) {
                                XposedBridge.log(TAG + "🎯 [LBSNavi-破防] 车机底层成功拆解到广播包裹！协议号: " + keyValue);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "LBSNavi 雷达部署失败: " + t.getMessage());
            }
        }
    }
}