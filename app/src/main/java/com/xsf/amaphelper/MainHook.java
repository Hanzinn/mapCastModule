package com.xsf.amaphelper;

import android.util.Log;
import java.lang.reflect.Field;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    // 监控目标：导航服务后台
    private static final String PKG_SERVICE = "ecarx.naviservice";
    // 监控核心类：DashboardManager
    private static final String CLASS_DASHBOARD_MGR = "ecarx.naviservice.a.a";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 只监控 naviservice 进程
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviSpy: 🕵️‍♂️ 间谍模式启动，正在监听官方高德 7.5...");

        try {
            // Hook 管理器的入口方法 a(Object)
            // 所有发往仪表的数据包都会经过这里
            XposedHelpers.findAndHookMethod(CLASS_DASHBOARD_MGR, lpparam.classLoader, "a", Object.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object packet = param.args[0];
                    if (packet == null) return;

                    String className = packet.getClass().getSimpleName();
                    
                    // 只关心核心的三个包
                    if (className.contains("MapSwitchingInfo") || 
                        className.contains("MapStatusInfo") || 
                        className.contains("MapGuideInfo")) {
                        
                        // 🔍 深度解析：把对象里的所有字段值都打印出来
                        String details = dumpFields(packet);
                        XposedBridge.log("NaviSpy: 📦 捕获 [" + className + "] -> " + details);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviSpy: ❌ 监控失败: " + t);
        }
    }

    // 反射工具：把对象变成字符串
    private String dumpFields(Object obj) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> clazz = obj.getClass();
            Field[] fields = clazz.getDeclaredFields();
            for (Field f : fields) {
                f.setAccessible(true);
                String name = f.getName();
                Object value = f.get(obj);
                sb.append(name).append("=").append(value).append("; ");
            }
        } catch (Exception e) {
            sb.append("解析错误");
        }
        return sb.toString();
    }
}