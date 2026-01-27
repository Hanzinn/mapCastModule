package com.xsf.amaphelper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviSpy: 🚀 V125 填空题抄写版启动");

        // 🟢 监控 MapGuideInfo 的所有 set 方法
        // 这样我们就能看到它到底填了哪些值！
        try {
            Class<?> guideInfoClass = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapGuideInfo", lpparam.classLoader);
            
            for (Method method : guideInfoClass.getDeclaredMethods()) {
                if (method.getName().startsWith("set")) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            // 获取方法名（例如 setTurnIcon）
                            String methodName = param.method.getName();
                            // 获取参数值（例如 2）
                            Object value = (param.args.length > 0) ? param.args[0] : "null";
                            
                            XposedBridge.log("NaviSpy: ✍️ [填空] " + methodName + " = " + value);
                        }
                    });
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviSpy: ❌ 监控 Set 方法失败: " + t);
        }
        
        // 🟢 同时监控 Switch (虽然可能抓不到，但为了保险)
        try {
            Class<?> switchClass = XposedHelpers.findClass("ecarx.naviservice.map.entity.MapSwitchingInfo", lpparam.classLoader);
             for (Method method : switchClass.getDeclaredMethods()) {
                if (method.getName().startsWith("set") || method.getName().equals("toString")) { // toString可能会暴露内部状态
                     XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                             XposedBridge.log("NaviSpy: 🚦 [Switch] " + param.method.getName() + " -> " + param.getResult());
                        }
                    });
                }
            }
        } catch (Throwable t) {}
    }
}