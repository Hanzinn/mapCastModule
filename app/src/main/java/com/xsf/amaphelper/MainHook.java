package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.lang.reflect.Field;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
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
                sendAppLog(context, "✅ 模块加载成功 (核心激活版)");
                registerReceiver(context, lpparam.classLoader);
            }
        });
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                    int status = intent.getIntExtra("status", 0);
                    if (status == 13) {
                        sendAppLog(ctx, "🚀 触发唤醒序列 (1 -> 27)");
                        sendStatus(cl, 1, ctx);
                        new Thread(() -> {
                            try { Thread.sleep(300); } catch (Exception e) {}
                            sendStatus(cl, 27, ctx);
                        }).start();
                    }
                } else if ("XSF_ACTION_SEND_GUIDE".equals(action)) {
                    String type = intent.getStringExtra("type");
                    sendStatus(cl, 27, ctx); // 模拟路口前先确保是Navi状态
                    if ("cruise".equals(type)) {
                        sendAppLog(ctx, "🛳️ 发送巡航模拟数据");
                        sendGuide(cl, "当前路", "巡航中", 1, 0, ctx);
                    } else {
                        sendAppLog(ctx, "🚗 发送路口模拟数据");
                        sendGuide(cl, "测试路", "成功街", 2, 500, ctx);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("XSF_ACTION_SEND_STATUS");
        filter.addAction("XSF_ACTION_SEND_GUIDE");
        context.registerReceiver(receiver, filter);
    }

    private void sendStatus(ClassLoader cl, int status, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> statusClass = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            Object statusObj = XposedHelpers.newInstance(statusClass, 2); // 尝试构造2
            Field f = XposedHelpers.findFirstFieldByExactType(statusClass, int.class);
            f.setAccessible(true);
            f.setInt(statusObj, status);
            Object msg = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WRAPPER, cl), 0x7d2, statusObj);
            XposedHelpers.callMethod(bus, "a", msg);
        } catch (Exception e) { sendAppLog(ctx, "状态发送失败: " + e.getMessage()); }
    }

    private void sendGuide(ClassLoader cl, String cur, String next, int icon, int dist, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> guideClass = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object guideObj = XposedHelpers.newInstance(guideClass, 2);
            XposedHelpers.setObjectField(guideObj, "curRoadName", cur);
            XposedHelpers.setObjectField(guideObj, "nextRoadName", next);
            XposedHelpers.setIntField(guideObj, "turnId", icon);
            XposedHelpers.setIntField(guideObj, "nextTurnDistance", dist);
            Object msg = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WRAPPER, cl), 0x7d0, guideObj);
            XposedHelpers.callMethod(bus, "a", msg);
        } catch (Exception e) { sendAppLog(ctx, "路口发送失败: " + e.getMessage()); }
    }

    private void sendAppLog(Context ctx, String log) {
        Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
        i.putExtra("log", log);
        ctx.sendBroadcast(i);
    }
}
