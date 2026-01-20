package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder; 
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
    
    // --- 类名定义 ---
    private static final String CLS_PROTOCOL_FACTORY = "j"; 
    private static final String CLS_PROTOCOL_MGR = "g"; 
    private static final String CLS_WIDGET_MGR_HOLDER = "q"; 
    private static final String CLS_WIDGET_MGR = "l"; 
    private static final String CLS_WIDGET_CONNECTION = "o";
    private static final String CLS_VERSION_UTIL = "y"; 
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
                Context appCtx = (Context) param.thisObject;
                sendAppLog(appCtx, "STATUS_HOOK_READY (V24-Pressure)");
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

        // 3. 生存补丁
        try {
            XposedHelpers.findAndHookMethod(CLS_VERSION_UTIL, lpparam.classLoader, "b", String.class, XC_MethodReplacement.returnConstant(70500));
        } catch (Throwable t) {}

        // 4. 心脏起搏 (Hook j.a)
        try {
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_FACTORY, lpparam.classLoader, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object inst = param.getResult();
                    if (inst != null) XposedHelpers.setBooleanField(inst, "c", true);
                }
            });
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, lpparam.classLoader, "f", XC_MethodReplacement.returnConstant(true));
        } catch (Throwable t) {}

        // 5. IPC 监控
        XC_MethodHook ipcHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                isIpcConnected = true;
                sendAppLog(null, "STATUS_IPC_CONNECTED (Real)"); 
            }
        };
        try { XposedHelpers.findAndHookMethod(CLS_WIDGET_CONNECTION, lpparam.classLoader, "onServiceConnected", ComponentName.class, IBinder.class, ipcHook); } catch (Throwable t) {}
        try { XposedHelpers.findAndHookMethod(CLS_CONNECTION_B, lpparam.classLoader, "onServiceConnected", ComponentName.class, IBinder.class, ipcHook); } catch (Throwable t) {}
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

    // 🚑 Matrix 伪造连接 (保持绿灯)
    private void resurrectAndConnect(ClassLoader cl, Context ctx) {
        try {
            Context targetCtx = (mServiceContext != null) ? mServiceContext : ctx;
            Class<?> holderClass = XposedHelpers.findClass(CLS_WIDGET_MGR_HOLDER, cl);
            Object mgrInstance = XposedHelpers.getStaticObjectField(holderClass, "a");
            
            if (mgrInstance == null) {
                mgrInstance = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WIDGET_MGR, cl));
                XposedHelpers.setStaticObjectField(holderClass, "a", mgrInstance);
            }

            if (mgrInstance != null) {
                try { XposedHelpers.callMethod(mgrInstance, "a", targetCtx); } catch (Throwable t) {}
                try {
                    Object conn = XposedHelpers.getObjectField(mgrInstance, "i");
                    if (conn != null) {
                        ComponentName fakeName = new ComponentName("com.fake.pkg", "com.fake.cls");
                        IBinder fakeBinder = new Binder(); 
                        XposedHelpers.callMethod(conn, "onServiceConnected", fakeName, fakeBinder);
                        sendAppLog(ctx, "⚡ IPC 绿灯 (Matrix)");
                    }
                } catch (Throwable t) {}
            }
        } catch (Throwable e) {
            sendAppLog(ctx, "Matrix Err: " + e.getMessage());
        }
    }

    // 🚑 焦点抢占 (V22逻辑)
    private void grabNaviFocus(Context ctx) {
        try {
            Context target = (mServiceContext != null) ? mServiceContext : ctx;
            Intent i1 = new Intent("ecarx.intent.action.NAVI_STATE_CHANGE");
            i1.putExtra("NAVI_STATE", 1); 
            target.sendBroadcast(i1);
            
            Intent i2 = new Intent("com.ecarx.intent.action.NAVI_FOCUS_GAIN");
            i2.putExtra("packageName", "com.autonavi.amapauto");
            target.sendBroadcast(i2);
            
            sendAppLog(ctx, "📡 焦点广播已发");
        } catch (Throwable t) {}
    }

    // 🚑 JSON 注入
    private void injectAmapJson(ClassLoader cl, int protocolId, String dataJson, Context ctx) {
        try {
            Class<?> factoryClass = XposedHelpers.findClass(CLS_PROTOCOL_FACTORY, cl);
            Object gInst = XposedHelpers.callStaticMethod(factoryClass, "a");
            if (gInst != null) {
                String payload = "{\"messageType\":\"dispatch\",\"protocolId\":" + protocolId + ",\"data\":" + dataJson + "}";
                XposedHelpers.callMethod(gInst, "a", payload);
                // sendAppLog(ctx, "JSON " + protocolId + " OK");
            }
        } catch (Throwable t) {}
    }

    // 🔥 V24 核心：时序压力测试逻辑
    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        new Thread(()->{
            if (status == 13) {
                // 1. 基础连接
                resurrectAndConnect(cl, ctx);
                
                // 2. 抢占焦点
                grabNaviFocus(ctx);
                
                // ⏰ 延迟 800ms，让决策中心反应过来
                try{Thread.sleep(800);}catch(Exception e){}
                
                sendAppLog(ctx, ">>> 开始 V24 压力测试 <<<");

                // 3. 注入 AppStart
                injectAmapJson(cl, 7, "{}", ctx);
                try{Thread.sleep(300);}catch(Exception e){}

                // 4. 🚀 巡航预热 (Status 28)
                // 尝试 Vendor 4 和 1
                injectAmapJson(cl, 3027, "{\"autoStatus\":28,\"eventMapVendor\":4}", ctx);
                try{Thread.sleep(200);}catch(Exception e){}
                injectAmapJson(cl, 3027, "{\"autoStatus\":28,\"eventMapVendor\":1}", ctx);
                
                sendAppLog(ctx, "⏱️ 巡航预热中 (1s)...");
                try{Thread.sleep(1000);}catch(Exception e){}

                // 5. 🚀 导航开始 (参数穷举)
                // 循环尝试 naviState 1, 2, 3
                int[] states = {1, 2, 3};
                // 循环尝试 vendor 4, 1
                int[] vendors = {4, 1};
                
                for (int v : vendors) {
                    for (int s : states) {
                        String json = "{\"autoStatus\":13,\"eventMapVendor\":" + v + ",\"naviState\":" + s + "}";
                        injectAmapJson(cl, 3027, json, ctx);
                        // 快速连发间隔
                        try{Thread.sleep(100);}catch(Exception e){}
                    }
                }
                
                // 6. 🚀 引导信息轰炸 (数据丰满化)
                // 补全所有可能需要的字段
                String fullGuide = "{" +
                        "\"turnId\":2," + 
                        "\"roadName\":\"V24压力测试\"," +
                        "\"distance\":500," +
                        "\"nextRoadName\":\"成功\"," +
                        "\"cameraDist\":0," +
                        "\"icon\":1," +
                        "\"routeRemainDist\":1000," +
                        "\"routeRemainTime\":60" +
                        "}";
                
                // 对所有 Vendor 进行引导信息注入
                injectAmapJson(cl, 101, fullGuide, ctx); 
                
                sendAppLog(ctx, "✅ V24 压力测试序列完成");
                
            } else if (status == 28) {
                injectAmapJson(cl, 3027, "{\"autoStatus\":28,\"eventMapVendor\":4,\"naviState\":1}", ctx);
            }
        }).start();
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

            sendAppLog(ctx, "冷启动序列(V24)已触发");
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
