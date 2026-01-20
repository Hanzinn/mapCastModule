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
    
    // 类名保持不变
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_SERVICE = "ecarx.naviservice.service.NaviService";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_XSF)) return;

        // 1. Hook Application
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                sendAppLog(context, "STATUS_HOOK_READY"); 
                registerReceiver(context, lpparam.classLoader);
            }
        });

        // 2. Hook Service 启动反馈
        try {
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Service service = (Service) param.thisObject;
                    sendAppLog(service, "STATUS_SERVICE_RUNNING"); 
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
                } 
                else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                    int status = intent.getIntExtra("status", 0);
                    
                    if (status == 13) {
                        // 常规激活：优先发 Vendor 4
                        sendAppLog(ctx, "⚡ 激活测试 (Vendor 4)");
                        sendStatus(cl, 13, ctx);
                        new Thread(()->{
                            try{Thread.sleep(300);}catch(Exception e){}
                            sendStatus(cl, 25, ctx);
                        }).start();
                    } 
                    else if (status == 28) {
                        // 官方巡航：优先发 Vendor 4
                        sendAppLog(ctx, "🚀 巡航测试 (Vendor 4)");
                        sendStatus(cl, 28, ctx); 
                        new Thread(()->{
                            try{Thread.sleep(200);}catch(Exception e){}
                            sendOfficialGuide(cl, ctx); 
                        }).start();
                    } 
                    else {
                        sendStatus(cl, status, ctx);
                        if(status == 29) sendAppLog(ctx, "🛑 停止 (Vendor 4)");
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("XSF_ACTION_START_SERVICE");
        filter.addAction("XSF_ACTION_SEND_STATUS");
        context.registerReceiver(receiver, filter);
    }

    private void startOfficialService(Context ctx) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("ecarx.naviservice", "ecarx.naviservice.service.NaviService"));
            intent.setAction("ecarx.intent.action.NAVI_SERVICE_STARTED");
            intent.addCategory("ecarx.intent.category.NAVI_INNER");
            ctx.startService(intent);
            sendAppLog(ctx, "已发送启动广播...");
        } catch (Exception e) {
            sendAppLog(ctx, "启动失败: " + e.getMessage());
        }
    }

    // 🔴 核心修改：优先使用 Vendor = 4
    private void sendStatus(ClassLoader cl, int statusValue, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> infoCls = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            
            // 🔴 根据 d.b.smali 分析结果，Vendor ID 必须是 4
            // 为了容错，我们发 4, 1, 2，但 4 排第一
            int[] vendors = {4, 1, 2}; 
            
            for (int v : vendors) {
                try {
                    Object infoObj = XposedHelpers.newInstance(infoCls, v); // new MapStatusInfo(4)
                    XposedHelpers.callMethod(infoObj, "setStatus", statusValue);
                    
                    Object msg = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WRAPPER, cl), 0x7d2, infoObj);
                    XposedHelpers.callMethod(bus, "a", msg);
                    
                    if (v == 4) sendAppLog(ctx, "Status " + statusValue + " (Vendor 4) 已发送");
                } catch (Throwable t) {}
            }
        } catch (Exception e) { sendAppLog(ctx, "Err: " + e.getMessage()); }
    }

    // 🔴 核心修改：路口信息也优先使用 Vendor = 4
    private void sendOfficialGuide(ClassLoader cl, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> guideCls = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            
            int[] vendors = {4, 1, 2}; // 优先尝试 4
            
            for (int v : vendors) {
                try {
                    Object gObj = XposedHelpers.newInstance(guideCls, v); // new MapGuideInfo(4)
                    XposedHelpers.callMethod(gObj, "setGuideType", 2);
                    XposedHelpers.callMethod(gObj, "setTurnId", 0x66);
                    XposedHelpers.callMethod(gObj, "setCurRoadName", "Vendor 4 测试");
                    XposedHelpers.callMethod(gObj, "setNextRoadName", "成功在望");
                    XposedHelpers.callMethod(gObj, "setNextTurnDistance", 500);
                    
                    Object msg = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WRAPPER, cl), 0x7d0, gObj);
                    XposedHelpers.callMethod(bus, "a", msg);
                } catch (Throwable t) {}
            }
        } catch (Exception e) {}
    }

    private void sendAppLog(Context ctx, String log) {
        Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
        i.putExtra("log", log);
        ctx.sendBroadcast(i);
    }
}
