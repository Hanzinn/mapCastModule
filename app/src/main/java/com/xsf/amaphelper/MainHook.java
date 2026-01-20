package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // 基于 Smali 的类名
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_SERVICE = "ecarx.naviservice.service.NaviService";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_XSF)) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                sendAppLog(context, "STATUS_HOOK_READY"); // 中间灯亮
                registerReceiver(context, lpparam.classLoader);
            }
        });

        // 核心反馈：服务真的跑起来了
        try {
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Service service = (Service) param.thisObject;
                    sendAppLog(service, "STATUS_SERVICE_RUNNING"); // 右边灯亮
                }
            });
        } catch (Throwable t) {}
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if ("XSF_ACTION_START_SERVICE".equals(action)) {
                    startOfficialService(ctx);
                } else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                    int status = intent.getIntExtra("status", 0);
                    // 🔴 强制使用 Vendor 4
                    if (status == 13) {
                        sendData(cl, 13, 4, ctx);
                        new Thread(()->{ try{Thread.sleep(400);}catch(Exception e){} sendData(cl, 25, 4, ctx); }).start();
                    } else if (status == 28) {
                        sendData(cl, 28, 4, ctx);
                        // 巡航模式最好补发一条引导数据防止黑屏
                    } else {
                        sendData(cl, status, 4, ctx);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("XSF_ACTION_START_SERVICE");
        filter.addAction("XSF_ACTION_SEND_STATUS");
        context.registerReceiver(receiver, filter);
    }

    private void sendData(ClassLoader cl, int statusValue, int vendor, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> infoCls = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            Object infoObj = XposedHelpers.newInstance(infoCls, vendor); // new Info(4)
            XposedHelpers.callMethod(infoObj, "setStatus", statusValue);
            
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d2, infoObj);
            XposedHelpers.callMethod(bus, "a", msg);
            sendAppLog(ctx, "指令 " + statusValue + " 已送达 (Vendor " + vendor + ")");
        } catch (Exception e) { sendAppLog(ctx, "发送失败: " + e.getMessage()); }
    }

    private void startOfficialService(Context ctx) {
        try {
            Intent intent = new Intent();
            // 🔴 精准复刻 MainActivity.smali 的参数 [cite: 60, 61, 62]
            intent.setComponent(new ComponentName("ecarx.naviservice", "ecarx.naviservice.service.NaviService"));
            intent.setAction("ecarx.intent.action.NAVI_SERVICE_STARTED");
            intent.addCategory("ecarx.intent.category.NAVI_INNER");
            ctx.startService(intent);
            sendAppLog(ctx, "正在冷启动服务...");
        } catch (Exception e) { sendAppLog(ctx, "冷启动失败: " + e.getMessage()); }
    }

    private void sendAppLog(Context ctx, String log) {
        Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
        i.putExtra("log", log);
        ctx.sendBroadcast(i);
    }
}
