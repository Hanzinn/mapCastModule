package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private static final String CLS_PROTOCOL_MGR = "ecarx.naviservice.map.d.g"; // 协议管理

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_XSF)) return;

        // 1. 注入反馈 (最基础的检查)
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                sendAppLog(context, "STATUS_HOOK_READY");
                registerReceiver(context, lpparam.classLoader);
            }
        });

        // 2. 服务运行反馈 (证明服务没挂)
        try {
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sendAppLog((Context)param.thisObject, "STATUS_SERVICE_RUNNING");
                }
            });
        } catch (Throwable t) {}

        // 3. 🛡️【必须保留】欺骗版本号检查 (y.smali)
        try {
            XposedHelpers.findAndHookMethod(CLS_VERSION_UTIL, lpparam.classLoader, "b", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(70500); // 伪装成 7.5.0
                }
            });
        } catch (Throwable t) {}

        // 4. 🛡️【必须恢复】伪造协议绑定状态 (g.smali)
        // 既然物理 IPC 很难连，我们必须骗过内部逻辑，否则消息发不出去
        try {
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, lpparam.classLoader, "f", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    // 强制设置 isBind = true
                    XposedHelpers.setBooleanField(param.thisObject, "c", true);
                    return true;
                }
            });
            
            // 拦截回调，模拟连接成功
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, lpparam.classLoader, "h", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object lac = XposedHelpers.getObjectField(param.thisObject, "g");
                    if (lac != null) {
                        XposedHelpers.callMethod(lac, "a"); 
                    }
                }
            });
        } catch (Throwable t) {}

        // 5. 监控 IPC 连接 (b.smali) - 仅监控，不干预
        try {
            XposedHelpers.findAndHookMethod(CLS_CONNECTION, lpparam.classLoader, "onServiceConnected", ComponentName.class, IBinder.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sendAppLog(null, "STATUS_IPC_CONNECTED"); 
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
                    if (status == 13) {
                        // 连招：Switch -> 13 -> 25
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
            Object switchObj = XposedHelpers.newInstance(switchCls, 0, 4); // 0->4
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d7, switchObj);
            XposedHelpers.callMethod(bus, "a", msg);
            sendAppLog(ctx, "Switch(0->4) 已发送");
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
            sendAppLog(ctx, "Status " + statusValue + " 已发送");
        } catch (Exception e) { sendAppLog(ctx, "Err: " + e.getMessage()); }
    }

    private void sendGuide(ClassLoader cl, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> guideCls = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object gObj = XposedHelpers.newInstance(guideCls, 4);
            XposedHelpers.callMethod(gObj, "setGuideType", 2);
            XposedHelpers.callMethod(gObj, "setTurnId", 2);
            XposedHelpers.callMethod(gObj, "setCurRoadName", "测试路");
            XposedHelpers.callMethod(gObj, "setNextTurnDistance", 500);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d0, gObj);
            XposedHelpers.callMethod(bus, "a", msg);
        } catch (Exception e) {}
    }

    private void startOfficialService(Context ctx) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("ecarx.naviservice", "ecarx.naviservice.service.NaviService"));
            intent.setAction("ecarx.intent.action.NAVI_SERVICE_STARTED");
            intent.addCategory("ecarx.intent.category.NAVI_INNER");
            ctx.startService(intent);
            
            new Thread(() -> {
                try {
                    Thread.sleep(3000); 
                    ctx.sendBroadcast(new Intent("ecarx.intent.action.MAP_OPEN"));
                    sendAppLog(ctx, "MAP_OPEN 已补发");
                    Thread.sleep(500);
                    Intent vIntent = new Intent("com.ecarx.naviservice.action.MAP_VENDOR_CHANGE");
                    vIntent.putExtra("EXTRA_MAP_VENDOR", 4);
                    ctx.sendBroadcast(vIntent);
                } catch (Exception e) {}
            }).start();
            
            sendAppLog(ctx, "冷启动执行中...");
        } catch (Exception e) { sendAppLog(ctx, "启动失败: " + e.getMessage()); }
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
