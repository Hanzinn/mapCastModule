package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder; 
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // --- 混淆类名 (Short Names) ---
    private static final String CLS_PROTOCOL_MGR = "g"; 
    private static final String CLS_WIDGET_MGR_HOLDER = "q"; 
    private static final String CLS_WIDGET_MGR = "l"; 
    private static final String CLS_WIDGET_CONNECTION = "o";
    private static final String CLS_VERSION_UTIL = "y"; 
    
    // --- 完整包名 ---
    private static final String CLS_BUS_FACTORY = "ecarx.naviservice.d.e"; 
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_SWITCH_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_SERVICE = "ecarx.naviservice.service.NaviService";
    
    // --- 其他通道 ---
    private static final String CLS_NEUSOFT_SDK = "ecarx.naviservice.map.d.a"; 
    private static final String CLS_CONNECTION_B = "ecarx.naviservice.b"; 

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
                Context appCtx = (Context) param.thisObject;
                sendAppLog(appCtx, "STATUS_HOOK_READY (V17)");
                registerReceiver(appCtx, lpparam.classLoader);
            }
        });

        // 2. 捕获 Service Context
        try {
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mServiceContext = (Context) param.thisObject;
                    sendAppLog(mServiceContext, "STATUS_SERVICE_RUNNING");
                }
            });
        } catch (Throwable t) {}

        // 3. 施加生存补丁 (版本 + 心脏)
        applySurvivalPatches(lpparam.classLoader);

        // 4. 全方位 IPC 监控
        XC_MethodHook ipcHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                isIpcConnected = true;
                String clsName = param.thisObject.getClass().getSimpleName();
                sendAppLog(null, "STATUS_IPC_CONNECTED (" + clsName + ")"); 
            }
        };
        try { XposedHelpers.findAndHookMethod(CLS_WIDGET_CONNECTION, lpparam.classLoader, "onServiceConnected", ComponentName.class, IBinder.class, ipcHook); } catch (Throwable t) {}
        try { XposedHelpers.findAndHookMethod(CLS_CONNECTION_B, lpparam.classLoader, "onServiceConnected", ComponentName.class, IBinder.class, ipcHook); } catch (Throwable t) {}
        try { XposedHelpers.findAndHookMethod(CLS_NEUSOFT_SDK, lpparam.classLoader, "a", Context.class, ipcHook); } catch (Throwable t) {}
    }

    private void applySurvivalPatches(ClassLoader cl) {
        try {
            // 版本欺骗
            XposedHelpers.findAndHookMethod(CLS_VERSION_UTIL, cl, "b", String.class, XC_MethodReplacement.returnConstant(70500));
            
            // 心脏起搏 Hook (被动)
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, cl, "f", XC_MethodReplacement.returnConstant(true));
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, cl, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object inst = param.getResult();
                    if (inst != null) {
                        XposedHelpers.setBooleanField(inst, "c", true);
                        Object lac = XposedHelpers.getObjectField(inst, "g");
                        if (lac != null) {
                            try { XposedHelpers.callMethod(lac, "a", 1); } 
                            catch (Throwable t) { try { XposedHelpers.callMethod(lac, "a"); } catch (Throwable t2) {} }
                        }
                    }
                }
            });
        } catch (Throwable t) {}
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

    // 🚑 核心 V17：主动唤醒 + 方法轰炸
    private void resurrectAndConnect(ClassLoader cl, Context ctx) {
        try {
            Context targetCtx = (mServiceContext != null) ? mServiceContext : ctx;
            sendAppLog(ctx, ">>> V17 主动唤醒 (Awaken) <<<");

            // 1. 🟢 主动唤醒协议单例 (g.a())
            try {
                Class<?> gClass = XposedHelpers.findClass(CLS_PROTOCOL_MGR, cl);
                Object gInst = XposedHelpers.callStaticMethod(gClass, "a");
                if (gInst != null) {
                    sendAppLog(ctx, "协议心脏(g)已手动激活");
                } else {
                    sendAppLog(ctx, "协议心脏(g)激活失败");
                }
            } catch (Throwable t) {
                sendAppLog(ctx, "g.a() 调用失败: " + t.getMessage());
            }

            // 2. 复活 WidgetManager (l)
            Class<?> holderClass = XposedHelpers.findClass(CLS_WIDGET_MGR_HOLDER, cl);
            Object mgrInstance = XposedHelpers.getStaticObjectField(holderClass, "a");
            
            if (mgrInstance == null) {
                sendAppLog(ctx, "WidgetMgr为空，复活中...");
                mgrInstance = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WIDGET_MGR, cl));
                XposedHelpers.setStaticObjectField(holderClass, "a", mgrInstance);
            }

            if (mgrInstance != null) {
                // 3. 🔵 字段强注 (直接塞 Context)
                try {
                    // l.smali 中字段 'a' 通常是 Context
                    XposedHelpers.setObjectField(mgrInstance, "a", targetCtx);
                    // 字段 'b' 可能是状态 int，设为 1
                    // XposedHelpers.setIntField(mgrInstance, "b", 1); 
                } catch (Throwable t) {}

                // 4. 🔴 方法轰炸 (a 和 b 都试一遍)
                boolean methodCalled = false;
                
                // 尝试 a(Context)
                try {
                    XposedHelpers.callMethod(mgrInstance, "a", targetCtx); 
                    sendAppLog(ctx, "调用 l.a(Context) 成功");
                    methodCalled = true;
                } catch (Throwable t) {}

                // 尝试 b() - 你的建议！
                try {
                    XposedHelpers.callMethod(mgrInstance, "b");
                    sendAppLog(ctx, "调用 l.b() 成功"); // 可能是真正的 bind
                    methodCalled = true;
                } catch (Throwable t) {}

                // 尝试 b(Context)
                try {
                    XposedHelpers.callMethod(mgrInstance, "b", targetCtx);
                    sendAppLog(ctx, "调用 l.b(Context) 成功");
                    methodCalled = true;
                } catch (Throwable t) {}

                if (!methodCalled) sendAppLog(ctx, "⚠️ 所有连接方法尝试均失败");
            }
            
            // 5. 补发激活信号
            safeSendSwitchInfo(cl, ctx);

        } catch (Throwable e) {
            sendAppLog(ctx, "复活异常: " + e.getMessage());
        }
    }

    private void safeSendSwitchInfo(ClassLoader cl, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS_FACTORY, cl);
            Object bus = XposedHelpers.callStaticMethod(busClass, "a");
            if (bus == null) return;

            Class<?> switchCls = XposedHelpers.findClass(CLS_SWITCH_INFO, cl);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object switchObj = XposedHelpers.newInstance(switchCls, 0, 4);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d7, switchObj);
            
            XposedHelpers.callMethod(bus, "a", msg);
            sendAppLog(ctx, "Switch(0->4) Sent");
        } catch (Throwable e) {}
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        new Thread(()->{
            if (status == 13) {
                // 连招优化：先唤醒再发
                resurrectAndConnect(cl, ctx); 
                try{Thread.sleep(500);}catch(Exception e){}
                
                sendData(cl, 28, ctx); 
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
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            
            int[] vendors = {4, 1, 2}; 
            for (int v : vendors) {
                try {
                    Object infoObj = XposedHelpers.newInstance(infoCls, v); 
                    try { XposedHelpers.callMethod(infoObj, "setMapVendor", v); } catch(Throwable t){}
                    XposedHelpers.callMethod(infoObj, "setStatus", statusValue);
                    
                    Object msg = XposedHelpers.newInstance(wrapCls, 0x7d2, infoObj);
                    XposedHelpers.callMethod(bus, "a", msg);
                } catch(Exception e) {}
            }
            sendAppLog(ctx, "Status " + statusValue + " Sent");
        } catch (Exception e) {}
    }
    
    private void sendGuide(ClassLoader cl, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS_FACTORY, cl);
            Object bus = XposedHelpers.callStaticMethod(busClass, "a");
            if (bus == null) return;
            
            Class<?> guideCls = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object gObj = XposedHelpers.newInstance(guideCls, 4);
            XposedHelpers.callMethod(gObj, "setGuideType", 2);
            XposedHelpers.callMethod(gObj, "setTurnId", 2);
            XposedHelpers.callMethod(gObj, "setCurRoadName", "V17唤醒");
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

            sendAppLog(ctx, "冷启动序列(V17)已触发");
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
