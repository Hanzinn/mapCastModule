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
    private static boolean isHeartbeatRunning = false;

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
                sendAppLog(appCtx, "STATUS_HOOK_READY (V27-AutoResume)");
                registerReceiver(appCtx, lpparam.classLoader);
            }
        });

        // 2. 捕获 Service Context (双重保险)
        try {
            // 保险 A: onCreate
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mServiceContext = (Context) param.thisObject;
                    sendAppLog(mServiceContext, "STATUS_SERVICE_RUNNING");
                }
            });

            // 🚑 保险 B: onStartCommand (修复重启后灯灭的问题)
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mServiceContext = (Context) param.thisObject;
                    sendAppLog(mServiceContext, "STATUS_SERVICE_RUNNING (Resumed)");
                    
                    // 🌟 自动续航逻辑
                    if (!isHeartbeatRunning) {
                        sendAppLog(mServiceContext, "💓 检测到服务重启，自动恢复心跳");
                        // 自动触发激活连招 (Status 13)
                        handleStatusAction(lpparam.classLoader, mServiceContext, 13);
                    }
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
                        keepAliveAndGreen(cl, ctx);
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

    // 🚑 物理层: Matrix Lite (维持绿灯)
    private void keepAliveAndGreen(ClassLoader cl, Context ctx) {
        try {
            Context targetCtx = (mServiceContext != null) ? mServiceContext : ctx;
            Class<?> holderClass = XposedHelpers.findClass(CLS_WIDGET_MGR_HOLDER, cl);
            Object mgrInstance = XposedHelpers.getStaticObjectField(holderClass, "a");
            
            if (mgrInstance == null) {
                mgrInstance = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WIDGET_MGR, cl));
                XposedHelpers.setStaticObjectField(holderClass, "a", mgrInstance);
            }

            if (mgrInstance != null) {
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

    // 🚑 焦点层
    private void grabNaviFocus(Context ctx) {
        try {
            Context target = (mServiceContext != null) ? mServiceContext : ctx;
            Intent i1 = new Intent("ecarx.intent.action.NAVI_STATE_CHANGE");
            i1.putExtra("NAVI_STATE", 1); 
            target.sendBroadcast(i1);
            
            Intent i2 = new Intent("com.ecarx.intent.action.NAVI_FOCUS_GAIN");
            i2.putExtra("packageName", "com.autonavi.amapauto");
            target.sendBroadcast(i2);
        } catch (Throwable t) {}
    }

    // 🚑 协议层: JSON 注入
    private void injectAmapJson(ClassLoader cl, int protocolId, String dataJson, Context ctx) {
        try {
            Class<?> factoryClass = XposedHelpers.findClass(CLS_PROTOCOL_FACTORY, cl);
            Object gInst = XposedHelpers.callStaticMethod(factoryClass, "a");
            if (gInst != null) {
                String payload = "{\"messageType\":\"dispatch\",\"protocolId\":" + protocolId + ",\"data\":" + dataJson + "}";
                XposedHelpers.callMethod(gInst, "a", payload);
            }
        } catch (Throwable t) {}
    }

    // 🛡️ 切换源穷举
    private void sendMapSwitchingSpecific(ClassLoader cl, int from, int to, Context ctx) {
        try {
            String switchJson = "{\"fromVendor\":" + from + ",\"toVendor\":" + to + "}";
            injectAmapJson(cl, 2007, switchJson, ctx);
            sendAppLog(ctx, "🔄 强切: " + from + " -> " + to);
        } catch (Throwable t) {}
    }

    // 💓 V27 自动续航心跳
    private void startV27Heartbeat(ClassLoader cl, Context ctx) {
        if (isHeartbeatRunning) return;
        isHeartbeatRunning = true;
        
        new Thread(() -> {
            sendAppLog(ctx, "💓 V27 自动续航心跳已启动...");
            int count = 0;
            // 只要 IPC 绿灯亮着，就一直跳
            // 这里移除了 count < 60 的限制，只要 App 活着就一直维持
            while (isHeartbeatRunning) { 
                try {
                    // 1. 最完整的状态包
                    String fullStatusJson = "{" +
                            "\"autoStatus\":13," +
                            "\"eventMapVendor\":4," +
                            "\"naviState\":1," +
                            "\"isWholeWorld\":false," +
                            "\"mapStatus\":0" +
                            "}";
                    injectAmapJson(cl, 3027, fullStatusJson, ctx);
                    
                    // 2. 引导包 (动态变化一点点距离，防止被去重)
                    String guideJson = "{\"turnId\":2,\"roadName\":\"V27自动续航\",\"distance\":" + (500 + count%10) + ",\"icon\":1}";
                    injectAmapJson(cl, 101, guideJson, ctx);
                    
                    // 3. 焦点补发 (每10秒)
                    if (count % 5 == 0) grabNaviFocus(ctx);

                    Thread.sleep(2000);
                    count++;
                } catch (Exception e) { break; }
            }
            isHeartbeatRunning = false;
            sendAppLog(ctx, "💔 心跳停止");
        }).start();
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        new Thread(()->{
            if (status == 13) {
                // 1. 物理层 & 焦点
                keepAliveAndGreen(cl, ctx); 
                grabNaviFocus(ctx);
                try{Thread.sleep(500);}catch(Exception e){}

                sendAppLog(ctx, ">>> 启动 V27 终极激活 <<<");

                // 2. 注入启动指令 (ID 7)
                injectAmapJson(cl, 7, "{}", ctx);
                try{Thread.sleep(300);}catch(Exception e){}
                
                // 3. 🛡️ 切换源穷举
                int[] froms = {0, 1, 4};
                for (int f : froms) {
                    sendMapSwitchingSpecific(cl, f, 4, ctx);
                    try{Thread.sleep(200);}catch(Exception e){}
                }

                // 4. 💓 启动心跳
                startV27Heartbeat(cl, ctx);
                
                sendAppLog(ctx, "✅ 激活指令已全量注入");
                
            } else if (status == 29) {
                isHeartbeatRunning = false;
                injectAmapJson(cl, 3027, "{\"autoStatus\":29,\"eventMapVendor\":4,\"naviState\":0}", ctx);
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
            
            // 延时检测，如果服务没自己亮，就帮它亮
            new Thread(()->{
                try {
                    Thread.sleep(3000);
                    // 确保物理连接
                    keepAliveAndGreen(cl, ctx);
                } catch (Exception e) {}
            }).start();

            sendAppLog(ctx, "冷启动序列(V27)已触发");
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
