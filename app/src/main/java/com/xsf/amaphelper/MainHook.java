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
    
    // 官方类名
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_SERVICE = "ecarx.naviservice.service.NaviService";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // Hook 自身
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_XSF)) return;

        // 1. Hook Application (为了注册广播接收器)
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                sendAppLog(context, "✅ 模块挂载成功");
                registerReceiver(context, lpparam.classLoader);
            }
        });

        // 2. 🔴 核心反馈：Hook 服务启动，证明服务真的活了！
        try {
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Service service = (Service) param.thisObject;
                    // 发送特殊暗号，让APP变绿
                    sendAppLog(service, "NAVI_SERVICE_RUNNING"); 
                }
            });
        } catch (Throwable t) {
            // 忽略错误，防止服务类名不对导致崩溃
        }
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                
                // 1. 启动服务 (APP发出的请求)
                if ("XSF_ACTION_START_SERVICE".equals(action)) {
                    startOfficialService(ctx);
                } 
                
                // 2. 发送状态
                else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                    int status = intent.getIntExtra("status", 0);
                    
                    if (status == 13) {
                        // 常规激活：同时发 13 和 25，双保险
                        sendAppLog(ctx, "⚡ 执行常规激活 (发送 13 & 25)");
                        sendStatus(cl, 13, ctx);
                        new Thread(()->{
                            try{Thread.sleep(300);}catch(Exception e){}
                            sendStatus(cl, 25, ctx);
                        }).start();
                    } 
                    else if (status == 28) {
                        // 官方巡航
                        sendAppLog(ctx, "🚀 执行官方巡航 (Status 28)");
                        sendStatus(cl, 28, ctx); 
                        // 紧接着发路口数据，模仿官方逻辑
                        new Thread(()->{
                            try{Thread.sleep(200);}catch(Exception e){}
                            sendOfficialGuide(cl, ctx); 
                        }).start();
                    } 
                    else {
                        // 其他状态 (停止等)
                        sendStatus(cl, status, ctx);
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
            sendAppLog(ctx, "⚡ 已发送启动广播，等待服务响应...");
        } catch (Exception e) {
            sendAppLog(ctx, "❌ 启动服务失败: " + e.getMessage());
        }
    }

    private void sendStatus(ClassLoader cl, int statusValue, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> infoCls = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            
            // 尝试构造参数 1 和 2 (对应 mMapVendor)
            int[] vendors = {1, 2};
            boolean sent = false;
            
            for (int v : vendors) {
                try {
                    Object infoObj = XposedHelpers.newInstance(infoCls, v);
                    XposedHelpers.callMethod(infoObj, "setStatus", statusValue);
                    Object msg = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WRAPPER, cl), 0x7d2, infoObj);
                    XposedHelpers.callMethod(bus, "a", msg);
                    sent = true;
                } catch (Throwable t) {}
            }
            if(sent) sendAppLog(ctx, "状态 " + statusValue + " 已送入总线");
            
        } catch (Exception e) { sendAppLog(ctx, "❌ 发送报错: " + e.getMessage()); }
    }

    private void sendOfficialGuide(ClassLoader cl, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> guideCls = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            
            int[] vendors = {1, 2};
            for (int v : vendors) {
                try {
                    Object gObj = XposedHelpers.newInstance(guideCls, v);
                    XposedHelpers.callMethod(gObj, "setGuideType", 2);
                    XposedHelpers.callMethod(gObj, "setTurnId", 0x66);
                    XposedHelpers.callMethod(gObj, "setCurRoadName", "巡航中");
                    XposedHelpers.callMethod(gObj, "setNextRoadName", "调试模式");
                    XposedHelpers.callMethod(gObj, "setNextTurnDistance", 888);
                    
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
