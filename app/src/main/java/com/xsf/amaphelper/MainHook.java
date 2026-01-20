package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.lang.reflect.Field;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // 根据 Smali 确定的类名
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_XSF)) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                sendAppLog(context, "✅ 模块加载成功 (Smali逻辑还原版)");
                registerReceiver(context, lpparam.classLoader);
            }
        });
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if ("XSF_ACTION_SUPER_TEST".equals(action)) {
                    // 启动轰炸线程
                    new Thread(() -> {
                        // 1. 尝试 13 (Route Start)
                        sendAppLog(ctx, "尝试 13 (构造2)");
                        sendExhaustiveStatus(cl, 13, 2, ctx);
                        sleep(300);
                        // 2. 尝试 25 (CAR_UP_3D)
                        sendAppLog(ctx, "尝试 25 (构造2)");
                        sendExhaustiveStatus(cl, 25, 2, ctx);
                        sleep(300);
                        // 3. 尝试 27 (CAR_UP_2D)
                        sendAppLog(ctx, "尝试 27 (构造2)");
                        sendExhaustiveStatus(cl, 27, 2, ctx);
                    }).start();
                } else if ("XSF_ACTION_SEND_GUIDE".equals(action)) {
                    String type = intent.getStringExtra("type");
                    sendExhaustiveGuide(cl, ctx, "cruise".equals(type));
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("XSF_ACTION_SUPER_TEST");
        filter.addAction("XSF_ACTION_SEND_GUIDE");
        context.registerReceiver(receiver, filter);
    }

    private void sendExhaustiveStatus(ClassLoader cl, int statusValue, int constructorArg, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> infoCls = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            
            // 容错构造对象
            Object infoObj;
            try { infoObj = XposedHelpers.newInstance(infoCls, constructorArg); }
            catch (Throwable t) { infoObj = XposedHelpers.newInstance(infoCls); }

            // 尝试两种方式填入状态：方法调用和直接字段修改
            try { XposedHelpers.callMethod(infoObj, "setStatus", statusValue); } catch (Throwable t) {}
            try {
                Field f = XposedHelpers.findFirstFieldByExactType(infoCls, int.class);
                f.setAccessible(true);
                f.setInt(infoObj, statusValue);
            } catch (Throwable t) {}

            // 打包并发送 (bz 信封)
            Object msg = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WRAPPER, cl), 0x7d2, infoObj);
            XposedHelpers.callMethod(bus, "a", msg);
            sendAppLog(ctx, "成功送达总线: " + statusValue);
        } catch (Exception e) {
            sendAppLog(ctx, "状态发送报错: " + e.getMessage());
        }
    }

    private void sendExhaustiveGuide(ClassLoader cl, Context ctx, boolean isCruise) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> guideCls = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object gObj = XposedHelpers.newInstance(guideCls, 2);

            if (isCruise) {
                XposedHelpers.setObjectField(gObj, "curRoadName", "巡航中");
                XposedHelpers.setObjectField(gObj, "nextRoadName", "前路顺畅");
                XposedHelpers.setIntField(gObj, "turnId", 1);
            } else {
                XposedHelpers.setObjectField(gObj, "curRoadName", "测试路");
                XposedHelpers.setObjectField(gObj, "nextRoadName", "成功街");
                XposedHelpers.setIntField(gObj, "turnId", 2);
                XposedHelpers.setIntField(gObj, "nextTurnDistance", 500);
            }

            Object msg = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WRAPPER, cl), 0x7d0, gObj);
            XposedHelpers.callMethod(bus, "a", msg);
            sendAppLog(ctx, "路口模拟已发送");
        } catch (Exception e) {
            sendAppLog(ctx, "路口报错: " + e.getMessage());
        }
    }

    private void sleep(int ms) { try { Thread.sleep(ms); } catch (Exception e) {} }
    private void sendAppLog(Context ctx, String log) {
        Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
        i.putExtra("log", log);
        ctx.sendBroadcast(i);
    }
}
