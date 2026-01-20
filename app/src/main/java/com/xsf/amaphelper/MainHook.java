package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder; 
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // Smali 类名
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_SWITCH_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_SERVICE = "ecarx.naviservice.service.NaviService";
    private static final String CLS_CONNECTION = "ecarx.naviservice.b"; 
    private static final String CLS_NEUSOFT_SDK = "ecarx.naviservice.map.d.a";
    private static final String CLS_VERSION_UTIL = "ecarx.naviservice.d.y"; 
    private static final String CLS_PROTOCOL_MGR = "ecarx.naviservice.map.d.g";
    private static final String CLS_WIDGET_MGR_HOLDER = "ecarx.naviservice.map.q"; 
    
    private static boolean isIpcConnected = false;

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
                sendAppLog((Context) param.thisObject, "STATUS_HOOK_READY");
                registerReceiver((Context) param.thisObject, lpparam.classLoader);
            }
        });

        // 2. 版本欺骗 (7.5.0)
        try {
            XposedHelpers.findAndHookMethod(CLS_VERSION_UTIL, lpparam.classLoader, "b", String.class, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    return 70500;
                }
            });
        } catch (Throwable t) {}

        // 3. 心脏起搏 (V10修正：尝试状态码 1)
        patchHeartbeat(lpparam.classLoader);

        // 4. 监控 IPC 连接
        XC_MethodHook ipcHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                isIpcConnected = true;
                sendAppLog(null, "STATUS_IPC_CONNECTED"); 
            }
        };
        try { XposedHelpers.findAndHookMethod(CLS_CONNECTION, lpparam.classLoader, "onServiceConnected", ComponentName.class, IBinder.class, ipcHook); } catch (Throwable t) {}
        try { XposedHelpers.findAndHookMethod(CLS_NEUSOFT_SDK, lpparam.classLoader, "a", Context.class, ipcHook); } catch (Throwable t) {}

        // 5. 服务存活
        try {
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sendAppLog(null, "STATUS_SERVICE_RUNNING");
                }
            });
        } catch (Throwable t) {}
    }

    // 🛡️ 补丁B：心脏起搏逻辑封装
    private void patchHeartbeat(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, cl, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object instance = param.getResult();
                    if (instance != null) {
                        XposedHelpers.setBooleanField(instance, "c", true); // isBind = true
                        Object lac = XposedHelpers.getObjectField(instance, "g");
                        
                        if (lac != null) {
                            // 🔴 V10 修正：优先发 1 (Connected)，备选无参
                            try {
                                XposedHelpers.callMethod(lac, "a", 1); // 尝试 state = 1
                                XposedBridge.log("NaviHook: Lac.a(1) 触发成功");
                            } catch (Throwable t1) {
                                try {
                                    XposedHelpers.callMethod(lac, "a", 0); // 回退 state = 0
                                } catch (Throwable t2) {
                                    try {
                                        XposedHelpers.callMethod(lac, "a"); // 回退无参
                                    } catch (Throwable t3) {
                                        XposedBridge.log("NaviHook: Lac 回调全失败");
                                    }
                                }
                            }
                        }
                    }
                }
            });
            // 强制 bindService 返回 true
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, cl, "f", XC_MethodReplacement.returnConstant(true));
        } catch (Throwable t) {}
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if ("XSF_ACTION_START_SERVICE".equals(action)) {
                    isIpcConnected = false;
                    startOfficialService(ctx, cl);
                } 
                else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                    int status = intent.getIntExtra("status", 0);
                    handleStatusAction(cl, ctx, status);
                }
                else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                    forceConnectIPC(cl, ctx);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("XSF_ACTION_START_SERVICE");
        filter.addAction("XSF_ACTION_SEND_STATUS");
        filter.addAction("XSF_ACTION_FORCE_CONNECT");
        context.registerReceiver(receiver, filter);
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        if (status == 13) {
            new Thread(()->{
                sendAppLog(ctx, "执行: 预热 -> 切换 -> 启动");
                sendData(cl, 28, ctx); // 预热
                try{Thread.sleep(300);}catch(Exception e){}
                sendMultipleSwitching(cl, ctx); // 切换
                try{Thread.sleep(300);}catch(Exception e){}
                sendData(cl, 13, ctx); // 启动
                try{Thread.sleep(500);}catch(Exception e){}
                sendData(cl, 25, ctx); // 确认
            }).start();
        } 
        else if (status == 28) {
            sendData(cl, 28, ctx);
            new Thread(()->{ try{Thread.sleep(200);}catch(Exception e){} sendGuide(cl, ctx); }).start();
        } else {
            sendData(cl, status, ctx);
        }
    }

    private void sendData(ClassLoader cl, int statusValue, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> infoCls = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            
            int[] vendors = {4, 1, 2}; // 盲测
            for (int v : vendors) {
                try {
                    Object infoObj = XposedHelpers.newInstance(infoCls, v);
                    try { XposedHelpers.callMethod(infoObj, "setMapVendor", v); } catch(Throwable t){}
                    XposedHelpers.callMethod(infoObj, "setStatus", statusValue);
                    
                    Object msg = XposedHelpers.newInstance(wrapCls, 0x7d2, infoObj);
                    XposedHelpers.callMethod(bus, "a", msg);
                } catch(Throwable t) {}
            }
            sendAppLog(ctx, "Status " + statusValue + " 已广播");
        } catch (Exception e) { sendAppLog(ctx, "Err: " + e.getMessage()); }
    }

    private void sendMultipleSwitching(ClassLoader cl, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> switchCls = XposedHelpers.findClass(CLS_SWITCH_INFO, cl);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            
            int[] fromVendors = {0, 1, 4};
            for (int from : fromVendors) {
                Object switchObj = XposedHelpers.newInstance(switchCls, from, 4);
                Object msg = XposedHelpers.newInstance(wrapCls, 0x7d7, switchObj);
                XposedHelpers.callMethod(bus, "a", msg);
            }
            sendAppLog(ctx, "SwitchInfo 已穷举");
        } catch (Throwable e) {}
    }

    private void startOfficialService(Context ctx, ClassLoader cl) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("ecarx.naviservice", "ecarx.naviservice.service.NaviService"));
            intent.setAction("ecarx.intent.action.NAVI_SERVICE_STARTED");
            intent.addCategory("ecarx.intent.category.NAVI_INNER");
            ctx.startService(intent);
            sendAppLog(ctx, "冷启动指令已发...");

            // 启动智能看门狗
            startWatchdog(ctx, cl);

            // 广播补发
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    ctx.sendBroadcast(new Intent("ecarx.intent.action.MAP_OPEN"));
                    Thread.sleep(500);
                    Intent vIntent = new Intent("com.ecarx.naviservice.action.MAP_VENDOR_CHANGE");
                    vIntent.putExtra("EXTRA_MAP_VENDOR", 4);
                    ctx.sendBroadcast(vIntent);
                } catch (Exception e) {}
            }).start();

        } catch (Exception e) { sendAppLog(ctx, "启动失败: " + e.getMessage()); }
    }

    private void startWatchdog(Context ctx, ClassLoader cl) {
        new Thread(() -> {
            try {
                // V10: 缩短等待时间到 4秒
                Thread.sleep(4000);
                
                if (isIpcConnected) {
                    sendAppLog(ctx, "看门狗: IPC正常");
                    return;
                }

                sendAppLog(ctx, "⚠️ 看门狗: 执行B计划...");
                forceConnectIPC(cl, ctx);
                sendMultipleSwitching(cl, ctx);
                
            } catch (Exception e) {}
        }).start();
    }

    private void forceConnectIPC(ClassLoader cl, Context ctx) {
        try {
            Class<?> holderClass = XposedHelpers.findClass(CLS_WIDGET_MGR_HOLDER, cl);
            Object mgrInstance = XposedHelpers.getStaticObjectField(holderClass, "a");
            if (mgrInstance != null) {
                Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                try { XposedHelpers.callMethod(mgrInstance, "a", appCtx); } catch (Throwable t) {
                    try { XposedHelpers.callMethod(mgrInstance, "a"); } catch (Throwable t2) {}
                }
                sendAppLog(ctx, "已执行暴力连接(l.a)");
            }
        } catch (Throwable e) { sendAppLog(ctx, "暴力连接失败"); }
    }

    private void sendGuide(ClassLoader cl, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> guideCls = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object gObj = XposedHelpers.newInstance(guideCls, 4);
            XposedHelpers.callMethod(gObj, "setGuideType", 2);
            XposedHelpers.callMethod(gObj, "setTurnId", 2);
            XposedHelpers.callMethod(gObj, "setCurRoadName", "V10终极测试");
            XposedHelpers.callMethod(gObj, "setNextTurnDistance", 500);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d0, gObj);
            XposedHelpers.callMethod(bus, "a", msg);
        } catch (Exception e) {}
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
