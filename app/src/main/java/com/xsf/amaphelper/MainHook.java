package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder; 
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // Smali 类名映射
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_SWITCH_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_SERVICE = "ecarx.naviservice.service.NaviService";
    private static final String CLS_CONNECTION = "ecarx.naviservice.b"; 
    private static final String CLS_VERSION_UTIL = "ecarx.naviservice.d.y";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_XSF)) return;

        // 1. 注入反馈
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                sendAppLog(context, "STATUS_HOOK_READY");
                registerReceiver(context, lpparam.classLoader);
            }
        });

        // 2. 欺骗版本号 (保留这个，很有用)
        try {
            XposedHelpers.findAndHookMethod(CLS_VERSION_UTIL, lpparam.classLoader, "b", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(70500); 
                }
            });
        } catch (Throwable t) {}

        // 3. 监控 bindService (看看谁在尝试连接)
        XposedHelpers.findAndHookMethod(Context.class, "bindService", Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent i = (Intent) param.args[0];
                // 打印出它想连谁，帮我们定位问题
                if (i.getComponent() != null) {
                    sendAppLog(null, "系统尝试连接: " + i.getComponent().getShortClassName());
                }
            }
        });

        // 4. 服务运行反馈
        try {
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sendAppLog((Context)param.thisObject, "STATUS_SERVICE_RUNNING");
                }
            });
        } catch (Throwable t) {}

        // 5. 🔴 核心：监控 IPC 连接 (ecarx.naviservice.b)
        // 只要这个方法被调用，说明物理链路通了
        try {
            XposedHelpers.findAndHookMethod(CLS_CONNECTION, lpparam.classLoader, "onServiceConnected", ComponentName.class, IBinder.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sendAppLog(null, "STATUS_IPC_CONNECTED"); 
                }
            });
        } catch (Throwable t) {
            sendAppLog(null, "Err: 找不到连接类 " + CLS_CONNECTION);
        }
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
                    if (status == 13) {
                        sendMapSwitching(cl, ctx);
                        new Thread(()->{ 
                            try{Thread.sleep(500);}catch(Exception e){} 
                            sendData(cl, 13, 4, ctx); 
                            try{Thread.sleep(500);}catch(Exception e){}
                            sendData(cl, 25, 4, ctx);
                        }).start();
                    } 
                    else if (status == 28) {
                        sendData(cl, 28, 4, ctx);
                        // 补发 TBT 防止黑屏
                        new Thread(()->{ try{Thread.sleep(200);}catch(Exception e){} sendGuide(cl, ctx); }).start();
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

    private void sendMapSwitching(ClassLoader cl, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> switchCls = XposedHelpers.findClass(CLS_SWITCH_INFO, cl);
            Object switchObj = XposedHelpers.newInstance(switchCls, 0, 4);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d7, switchObj);
            XposedHelpers.callMethod(bus, "a", msg);
            sendAppLog(ctx, "已发送 MapSwitchingInfo (0->4)");
        } catch (Throwable e) {}
    }

    private void sendData(ClassLoader cl, int statusValue, int vendor, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> infoCls = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            Object infoObj = XposedHelpers.newInstance(infoCls, vendor);
            try { XposedHelpers.callMethod(infoObj, "setMapVendor", vendor); } catch(Throwable t){}
            XposedHelpers.callMethod(infoObj, "setStatus", statusValue);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d2, infoObj);
            XposedHelpers.callMethod(bus, "a", msg);
            sendAppLog(ctx, "Status " + statusValue + " 发送成功");
        } catch (Exception e) { sendAppLog(ctx, "Status Err: " + e.getMessage()); }
    }

    private void sendGuide(ClassLoader cl, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> guideCls = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object gObj = XposedHelpers.newInstance(guideCls, 4);
            XposedHelpers.callMethod(gObj, "setGuideType", 2);
            XposedHelpers.callMethod(gObj, "setTurnId", 2);
            XposedHelpers.callMethod(gObj, "setCurRoadName", "测试路");
            XposedHelpers.callMethod(gObj, "setNextRoadName", "IPC监控中");
            XposedHelpers.callMethod(gObj, "setNextTurnDistance", 500);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d0, gObj);
            XposedHelpers.callMethod(bus, "a", msg);
        } catch (Exception e) {}
    }

    private void startOfficialService(Context ctx) {
        try {
            // 1. 启动服务
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("ecarx.naviservice", "ecarx.naviservice.service.NaviService"));
            intent.setAction("ecarx.intent.action.NAVI_SERVICE_STARTED");
            intent.addCategory("ecarx.intent.category.NAVI_INNER");
            ctx.startService(intent);
            
            // 2. 关键：尝试手动查找并触发 AutoWidgetManager 的连接 (l.smali)
            // 如果系统不自动连，我们试图用代码去触发它 (这是一个盲测尝试)
            // 稍后在日志里看 "系统尝试连接" 是否出现
            
            // 3. 延时补发广播
            new Thread(() -> {
                try {
                    Thread.sleep(3000); 
                    ctx.sendBroadcast(new Intent("ecarx.intent.action.MAP_OPEN"));
                    sendAppLog(ctx, "MAP_OPEN 已发送 (等待IPC连接)");
                    
                    Thread.sleep(500);
                    Intent vIntent = new Intent("com.ecarx.naviservice.action.MAP_VENDOR_CHANGE");
                    vIntent.putExtra("EXTRA_MAP_VENDOR", 4);
                    ctx.sendBroadcast(vIntent);
                } catch (Exception e) {}
            }).start();
            
            sendAppLog(ctx, "冷启动执行中...");
        } catch (Exception e) { sendAppLog(ctx, "Start Err: " + e.getMessage()); }
    }

    private void sendAppLog(Context ctx, String log) {
        try {
            Context c = ctx;
            if (c == null) c = android.app.AndroidAppHelper.currentApplication();
            if (c != null) {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.putExtra("log", log);
                c.sendBroadcast(i);
            }
        } catch (Throwable t) {}
    }
                }
