package com.xsf.amaphelper;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviSpy: 🚀 V123 源头阻击版启动 - 正在监听对象创建...");

        // 1. 监控 MapSwitchingInfo 的构造函数 (II)V
        // 这是寻找切换指令参数（Vendor vs Mode）的终极铁证
        try {
            XposedHelpers.findAndHookConstructor(
                "ecarx.naviservice.map.entity.MapSwitchingInfo", 
                lpparam.classLoader, 
                int.class, // 参数1: oldVendor?
                int.class, // 参数2: newVendor?
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int arg1 = (int) param.args[0];
                        int arg2 = (int) param.args[1];
                        
                        XposedBridge.log("NaviSpy: 🚨 发现 SwitchingInfo 创建! 参数: [" + arg1 + ", " + arg2 + "]");
                        
                        // 🔥 打印调用栈：这是找到“VIP密道”的关键！
                        XposedBridge.log("NaviSpy: 🕵️‍♂️ 谁在调用我？\n" + Log.getStackTraceString(new Throwable()));
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("NaviSpy: ❌ 监控 SwitchingInfo 失败: " + t);
        }

        // 2. 监控 MapGuideInfo 的构造函数 (I)V
        // 这是寻找正确 Vendor ID 的关键
        try {
            XposedHelpers.findAndHookConstructor(
                "ecarx.naviservice.map.entity.MapGuideInfo", 
                lpparam.classLoader, 
                int.class, // 参数: vendor
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int vendor = (int) param.args[0];
                        XposedBridge.log("NaviSpy: 📦 发现 GuideInfo 创建! Vendor=" + vendor);
                        
                        // 我们不需要每次都打印栈，只打一次就行，避免日志爆炸
                        // XposedBridge.log("NaviSpy: 调用栈...\n" + Log.getStackTraceString(new Throwable()));
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("NaviSpy: ❌ 监控 GuideInfo 失败: " + t);
        }
    }
}