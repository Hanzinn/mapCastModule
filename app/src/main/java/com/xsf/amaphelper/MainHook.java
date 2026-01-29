package com.xsf.amaphelper;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.UserHandle;
import android.view.Surface;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // 🎯 必须精准匹配这个接口名（来自 smali 分析）
    private static final String DESCRIPTOR_SERVICE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";

    private static Context systemContext = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 只在亿咖通导航服务进程中工作
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🕵️‍♂️ V169 全息侦探版启动 - 准备监听 7.5");

        // 1. 获取 Context 用于发日志广播
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                sendJavaBroadcast("⚡ V169 侦探就绪 - 请运行高德7.5进行投屏");
            }
        });

        // 2. 🔥 核心：Hook 系统侧的所有 Binder 通信
        // 不修改返回值，只记录参数，做透明代理
        XposedHelpers.findAndHookMethod(Binder.class, "onTransact", 
            int.class, Parcel.class, Parcel.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int code = (int) param.args[0];
                Parcel data = (Parcel) param.args[1];
                
                // ⚠️ 关键：必须记录原始读取位置，读完后恢复，否则系统读取时会错位崩溃
                int startPos = data.dataPosition();
                
                try {
                    // 尝试读取接口描述符 (Interface Token)
                    String descriptor = data.readString();
                    
                    // 🎯 只关心投屏服务的指令
                    if (DESCRIPTOR_SERVICE.equals(descriptor)) {
                        String log = analyzeTransaction(code, data);
                        XposedBridge.log(log);
                        sendJavaBroadcast(log);
                    }
                } catch (Throwable t) {
                    // 忽略非标准 Binder 调用或读取错误
                } finally {
                    // ♻️ 恢复现场，假装我们没来过
                    data.setDataPosition(startPos);
                }
            }
        });
    }

    // 🕵️‍♂️ 协议分析器：把枯燥的 Hex 数据翻译成人话
    private String analyzeTransaction(int code, Parcel data) {
        StringBuilder sb = new StringBuilder();
        sb.append("📡 捕获指令 Code: ").append(code);
        
        // 根据之前的 smali 和 AIDL 分析进行翻译
        switch (code) {
            case 1: 
                sb.append(" [addSurface/投屏]");
                try {
                    int hasSurface = data.readInt();
                    if (hasSurface != 0) {
                        Surface s = Surface.CREATOR.createFromParcel(data);
                        sb.append(" Surface=").append(s.toString());
                    } else {
                        sb.append(" Surface=null");
                    }
                    int id = data.readInt();
                    sb.append(" ID=").append(id);
                } catch(Exception e) { sb.append(" (解析Arg失败)"); }
                break;
                
            case 2: 
                sb.append(" [removeSurface/移除]");
                try {
                    int hasS = data.readInt(); // Surface
                    int id = data.readInt();
                    sb.append(" ID=").append(id);
                } catch(Exception e) {}
                break;
                
            case 3: 
                sb.append(" [isMapRunning/心跳]");
                break;
                
            case 4: 
                sb.append(" [setWidgetStateControl/握手]");
                try {
                    IBinder binder = data.readStrongBinder();
                    sb.append(" ProviderBinder=").append(binder);
                } catch(Exception e) {}
                break;
                
            case 5: 
                sb.append(" [dispatchTouchEvent/触摸]");
                break;
                
            // 🔥 重点关注区域： smali 里出现的特殊 Code
            case 2001: // 0x7d1 REPORT_NAVI_SDK_VERSION
                sb.append(" 🔥 [REPORT_SDK_VERSION/报版本]");
                try {
                    // 尝试读取参数，看看它传了什么版本号
                    // 可能是 int, String, 或者 Bundle
                    int v1 = data.readInt();
                    sb.append(" Arg1(Int):").append(v1);
                    // 继续尝试读，直到读不出
                    String s1 = data.readString();
                    sb.append(" Arg2(Str):").append(s1);
                } catch (Exception e) {}
                break;
                
            default:
                sb.append(" ❓ [未知指令/新发现]");
                // 打印前几个参数，用于分析
                try {
                    sb.append(" Int1:").append(data.readInt());
                    sb.append(" Int2:").append(data.readInt());
                    sb.append(" Str1:").append(data.readString());
                } catch (Exception e) {}
                break;
        }
        
        return sb.toString();
    }

    private void sendJavaBroadcast(String log) {
        if (systemContext == null) return;
        new Thread(() -> {
            try {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.setPackage(PKG_SELF);
                i.putExtra("log", log);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                try {
                    Object userAll = XposedHelpers.getStaticObjectField(UserHandle.class, "ALL");
                    Method method = Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class);
                    method.invoke(systemContext, i, userAll);
                } catch (Throwable t) {
                    systemContext.sendBroadcast(i);
                }
            } catch (Throwable t) {}
        }).start();
    }
}