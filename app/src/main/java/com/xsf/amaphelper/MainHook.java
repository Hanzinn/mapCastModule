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
    
    // 🔴 核心修正：根目录混淆类名 (Short Names)
    private static final String CLS_PROTOCOL_MGR = "g";  // g.smali
    private static final String CLS_WIDGET_MGR_HOLDER = "q"; // q.smali
    private static final String CLS_WIDGET_MGR = "l"; // l.smali
    private static final String CLS_VERSION_UTIL = "y"; // y.smali
    
    // 🟢 未混淆或子包类名 (Full Names)
    // 根据 Smali 分析，d.e 和 d.g 都可能是总线持有者，这里双管齐下
    private static final String CLS_BUS_FACTORY = "ecarx.naviservice.d.e"; 
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_SWITCH_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_SERVICE = "ecarx.naviservice.service.NaviService";
    private static final String CLS_CONNECTION_B = "ecarx.naviservice.b"; 
    private static final String CLS_NEUSOFT_SDK = "ecarx.naviservice.map.d.a";

    private static Context mServiceContext = null;
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
                sendAppLog((Context) param.thisObject, "STATUS_HOOK_READY (V15)");
                registerReceiver((Context) param.thisObject, lpparam.classLoader);
            }
        });

        // 2. 捕获 Service 上下文
        try {
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mServiceContext = (Context) param.thisObject;
                    sendAppLog(mServiceContext, "STATUS_SERVICE_RUNNING");
                }
            });
        } catch (Throwable t) {}

        // 3. 生存补丁 (版本欺骗 + 心脏起搏)
        applySurvivalPatches(lpparam.classLoader);

        // 4. 监控 IPC 连接
        XC_MethodHook ipcHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                isIpcConnected = true;
                sendAppLog(null, "STATUS_IPC_CONNECTED"); 
            }
        };
        try { XposedHelpers.findAndHookMethod(CLS_CONNECTION_B, lpparam.classLoader, "onServiceConnected", ComponentName.class, IBinder.class, ipcHook); } catch (Throwable t) {}
        try { XposedHelpers.findAndHookMethod(CLS_NEUSOFT_SDK, lpparam.classLoader, "a", Context.class, ipcHook); } catch (Throwable t) {}
    }

    private void applySurvivalPatches(ClassLoader cl) {
        try {
            // 修正后的版本欺骗 (y.smali -> "y")
            XposedHelpers.findAndHookMethod(CLS_VERSION_UTIL, cl, "b", String.class, XC_MethodReplacement.returnConstant(70500));
            
            // 修正后的心脏起搏 (g.smali -> "g")
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, cl, "f", XC_MethodReplacement.returnConstant(true));
            
            // Hook g.a() 单例初始化
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, cl, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object inst = param.getResult();
                    if (inst != null) {
                        XposedHelpers.setBooleanField(inst, "c", true);
                        // g.smali 里的 Lac 字段通常是 g
                        Object lac = XposedHelpers.getObjectField(inst, "g");
                        if (lac != null) {
                            try { XposedHelpers.callMethod(lac, "a", 1); } 
                            catch (Throwable t) { try { XposedHelpers.callMethod(lac, "a"); } catch (Throwable t2) {} }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("Patch Error: " + t.getMessage());
        }
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if ("XSF_ACTION_START_SERVICE".equals(action)) {
                        isIpcConnected = false;
                        startOfficialService(ctx, cl);
                    } 
                    else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                        resurrectAndConnect(cl, ctx);
                    }
                    else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                        handleStatusAction(cl, ctx, intent.getIntExtra("status", 0));
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction("XSF_ACTION_START_SERVICE");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            filter.addAction("XSF_ACTION_SEND_STATUS");
            context.getApplicationContext().registerReceiver(receiver, filter);
        } catch (Throwable t) {}
    }

    // 🚑 核心 V15：短类名复活逻辑
    private void resurrectAndConnect(ClassLoader cl, Context ctx) {
        try {
            Context targetCtx = (mServiceContext != null) ? mServiceContext : ctx;
            sendAppLog(ctx, ">>> V15 暴力穿透 (ShortNames) <<<");

            // 1. 获取 WidgetManager (修正为 CLS_WIDGET_MGR_HOLDER = "q")
            Class<?> holderClass = XposedHelpers.findClass(CLS_WIDGET_MGR_HOLDER, cl);
            Object mgrInstance = XposedHelpers.getStaticObjectField(holderClass, "a");
            
            if (mgrInstance == null) {
                sendAppLog(ctx, "WidgetMgr为空，尝试实例化 'l'...");
                // 修正为 CLS_WIDGET_MGR = "l"
                mgrInstance = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WIDGET_MGR, cl));
                XposedHelpers.setStaticObjectField(holderClass, "a", mgrInstance);
                sendAppLog(ctx, "'l' 实例已注入 'q.a'");
            }

            // 2. 捅开物理连接 (l.a(Context))
            if (mgrInstance != null) {
                try {
                    XposedHelpers.callMethod(mgrInstance, "a", targetCtx); 
                    sendAppLog(ctx, "✅ 物理层开启指令(l.a)已送达");
                } catch (Throwable t) {
                    // 如果 a(Context) 失败，尝试无参 a()
                    try {
                        XposedHelpers.callMethod(mgrInstance, "a");
                        sendAppLog(ctx, "✅ 物理层开启指令(l.a无参)已送达");
                    } catch (Throwable t2) {
                        sendAppLog(ctx, "调用连接方法失败: " + t.getMessage());
                    }
                }
            }
            
            // 3. 补发激活信号
            safeSendSwitchInfo(cl, ctx);

        } catch (Throwable e) {
            sendAppLog(ctx, "穿透异常: " + e.getMessage());
        }
    }

    private void safeSendSwitchInfo(ClassLoader cl, Context ctx) {
        try {
            // 获取总线：尝试 d.e.a()
            Object bus = null;
            try {
                Class<?> busClass = XposedHelpers.findClass(CLS_BUS_FACTORY, cl);
                bus = XposedHelpers.callStaticMethod(busClass, "a");
            } catch (Throwable t) {}

            if (bus == null) {
                sendAppLog(ctx, "总线未就绪 (d.e.a 返回空)");
                return;
            }

            Class<?> switchCls = XposedHelpers.findClass(CLS_SWITCH_INFO, cl);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object switchObj = XposedHelpers.newInstance(switchCls, 0, 4);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d7, switchObj);
            
            XposedHelpers.callMethod(bus, "a", msg);
            sendAppLog(ctx, "切换信号(0->4)已发送");
        } catch (Throwable e) {
            // sendAppLog(ctx, "总线异常");
        }
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        new Thread(()->{
            if (status == 13) {
                sendData(cl, 28, ctx); // 预热
                try{Thread.sleep(300);}catch(Exception e){}
                safeSendSwitchInfo(cl, ctx); 
                try{Thread.sleep(300);}catch(Exception e){}
                sendData(cl, 13, ctx); 
                try{Thread.sleep(500);}catch(Exception e){}
                sendData(cl, 25, ctx);
            } else {
                sendData(cl, status, ctx);
            }
        }).start();
    }

    private void sendData(ClassLoader cl, int statusValue, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS_FACTORY, cl);
            Object bus = XposedHelpers.callStaticMethod(busClass, "a");
            if (bus == null) return;

            Class<?> infoCls = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            // 锁定 Vendor 4
            Object infoObj = XposedHelpers.newInstance(infoCls, 4); 
            try { XposedHelpers.callMethod(infoObj, "setMapVendor", 4); } catch(Throwable t){}
            XposedHelpers.callMethod(infoObj, "setStatus", statusValue);
            
            Object msg = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WRAPPER, cl), 0x7d2, infoObj);
            XposedHelpers.callMethod(bus, "a", msg);
            sendAppLog(ctx, "Status " + statusValue + " (V4) 已发送");
        } catch (Exception e) {}
    }
    
    // 补全 sendGuide
    private void sendGuide(ClassLoader cl, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS_FACTORY, cl);
            Object bus = XposedHelpers.callStaticMethod(busClass, "a");
            if (bus == null) return;
            
            Class<?> guideCls = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object gObj = XposedHelpers.newInstance(guideCls, 4);
            XposedHelpers.callMethod(gObj, "setGuideType", 2);
            XposedHelpers.callMethod(gObj, "setTurnId", 2);
            XposedHelpers.callMethod(gObj, "setCurRoadName", "V15真名版");
            XposedHelpers.callMethod(gObj, "setNextTurnDistance", 500);
            
            Object msg = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WRAPPER, cl), 0x7d0, gObj);
            XposedHelpers.callMethod(bus, "a", msg);
        } catch (Exception e) {}
    }

    private void startOfficialService(Context ctx, ClassLoader cl) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("ecarx.naviservice", "ecarx.naviservice.service.NaviService"));
            intent.setAction("ecarx.intent.action.NAVI_SERVICE_STARTED");
            intent.addCategory("ecarx.intent.category.NAVI_INNER");
            ctx.startService(intent);
            
            new Thread(()->{
                try {
                    Thread.sleep(4000);
                    if (!isIpcConnected) {
                        resurrectAndConnect(cl, ctx);
                    }
                } catch (Exception e) {}
            }).start();

            sendAppLog(ctx, "冷启动序列已触发");
        } catch (Exception e) { sendAppLog(ctx, "启动失败"); }
    }

    private void sendAppLog(Context ctx, String log) {
        try {
            Context c = (ctx != null) ? ctx : android.app.AndroidAppHelper.currentApplication();
            if (c != null) {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.putExtra("log", log);
                c.sendBroadcast(i);
            }
        } catch (Throwable t) {}
    }
}
