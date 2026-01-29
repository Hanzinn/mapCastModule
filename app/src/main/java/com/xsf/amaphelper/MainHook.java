package com.xsf.amaphelper;

import android.app.Application;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.UserHandle;
import android.content.Intent;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    private static Context systemContext = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🕵️‍♂️ V170 双向监听版启动 (请配合高德7.5使用)");

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                sendJavaBroadcast("⚡ V170 监听就绪 - 请开启投屏");
            }
        });

        // 🔥 1. 监听【入站】流量 (Map -> System)
        XposedHelpers.findAndHookMethod(Binder.class, "onTransact", 
            int.class, Parcel.class, Parcel.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                analyzeTransaction("⬇️ [RECV/入]", (int) param.args[0], (Parcel) param.args[1]);
            }
        });

        // 🔥 2. 监听【出站】流量 (System -> Map)
        try {
            XposedHelpers.findAndHookMethod("android.os.BinderProxy", lpparam.classLoader, "transact",
                int.class, Parcel.class, Parcel.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    analyzeTransaction("⬆️ [SEND/出]", (int) param.args[0], (Parcel) param.args[1]);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Hook BinderProxy failed: " + t);
        }
    }

    private void analyzeTransaction(String direction, int code, Parcel data) {
        // 过滤掉系统高频噪音，只看我们关心的范围
        // 1-5: 投屏核心指令
        // 2001 (0x7d1): SDK版本握手
        // 1000-3000: 业务指令
        if (code != 1 && code != 2 && code != 3 && code != 4 && code != 5 && (code < 1000 || code > 3000)) {
            return; 
        }

        int startPos = data.dataPosition();
        StringBuilder sb = new StringBuilder();
        sb.append(direction).append(" Code: ").append(code);

        try {
            // 尝试读取 Interface Token (看看是谁发的)
            String token = null;
            try { 
                if (data.dataAvail() > 0) token = data.readString(); 
            } catch (Exception e) {}
            
            if (token != null) sb.append(" | Token: ").append(token);

            // 针对性解析
            if (code == 2001 || code == 0x7d1) {
                sb.append(" 🔥 [版本握手!]");
                try {
                    int v1 = data.readInt();
                    sb.append(" Int1:").append(v1);
                    String s1 = data.readString();
                    sb.append(" Str1:").append(s1);
                } catch (Exception e) {}
            } else if (code == 4) {
                sb.append(" [握手]");
            } else if (code == 1) {
                sb.append(" [AddSurface]");
            }

        } catch (Throwable t) {
        } finally {
            data.setDataPosition(startPos); // 必须归位！
        }

        String log = sb.toString();
        XposedBridge.log("NaviHook: " + log);
        sendJavaBroadcast(log);
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