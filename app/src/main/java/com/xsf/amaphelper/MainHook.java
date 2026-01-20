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
    
    // --- 混淆类名 (Short Names) ---
    // 🔴 修正点：单例工厂是 j
    private static final String CLS_PROTOCOL_FACTORY = "j"; 
    private static final String CLS_PROTOCOL_MGR = "g"; 
    
    private static final String CLS_WIDGET_MGR_HOLDER = "q"; 
    private static final String CLS_WIDGET_MGR = "l"; 
    private static final String CLS_WIDGET_CONNECTION = "o";
    private static final String CLS_VERSION_UTIL = "y"; 
    
    // --- 完整包名 ---
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
                sendAppLog(appCtx, "STATUS_HOOK_READY (V20-FIX)");
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

        // 3. 生存补丁 (版本欺骗)
        try {
            XposedHelpers.findAndHookMethod(CLS_VERSION_UTIL, lpparam.classLoader, "b", String.class, XC_MethodReplacement.returnConstant(70500));
        } catch (Throwable t) {}

        // 4. 心脏起搏 (修正：Hook 工厂 j.a 来获取单例)
        try {
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_FACTORY, lpparam.classLoader, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object inst = param.getResult(); // 这里拿到的就是 g 的实例
                    if (inst != null) {
                        // 将 g 实例的 isBind (c) 设为 true
                        XposedHelpers.setBooleanField(inst, "c", true);
                        // sendAppLog(null, "心脏起搏: 协议单例(g)已激活");
                    }
                }
            });
            // 同时 Hook g.f 确保返回 true
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, lpparam.classLoader, "f", XC_MethodReplacement.returnConstant(true));
        } catch (Throwable t) {}

        // 5. IPC 监控
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

    // 🚑 核心功能 1: 物理链路打通 (Matrix 逻辑)
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
                
                // 注入伪造 Binder
                try {
                    Object conn = XposedHelpers.getObjectField(mgrInstance, "i");
                    if (conn != null) {
                        ComponentName fakeName = new ComponentName("com.fake.pkg", "com.fake.cls");
                        IBinder fakeBinder = new Binder(); 
                        XposedHelpers.callMethod(conn, "onServiceConnected", fakeName, fakeBinder);
                        sendAppLog(ctx, "⚡ IPC 绿灯已强制点亮");
                    }
                } catch (Throwable t) {}
            }
        } catch (Throwable e) {
            sendAppLog(ctx, "连接异常: " + e.getMessage());
        }
    }

    // 🚑 核心功能 2: JSON 协议注入 (修正为通过 j.a 获取 g)
    private void injectAmapJson(ClassLoader cl, int protocolId, String dataJson, Context ctx) {
        try {
            // 1. 找到工厂类 j
            Class<?> factoryClass = XposedHelpers.findClass(CLS_PROTOCOL_FACTORY, cl);
            
            // 2. 调用工厂静态方法 j.a() 获取单例 (g 的实例)
            Object gInst = XposedHelpers.callStaticMethod(factoryClass, "a");
            
            if (gInst != null) {
                // 3. 构造 JSON
                String payload = "{\"messageType\":\"dispatch\",\"protocolId\":" + protocolId + ",\"data\":" + dataJson + "}";
                
                // 4. 调用实现类 g 的方法 a(String)
                XposedHelpers.callMethod(gInst, "a", payload);
                
                sendAppLog(ctx, "💉 JSON注入 ID=" + protocolId + " 成功");
            } else {
                sendAppLog(ctx, "❌ 协议单例获取失败 (j.a返回空)");
            }
        } catch (Throwable t) {
            sendAppLog(ctx, "JSON注入失败: " + t.getMessage());
        }
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        new Thread(()->{
            if (status == 13) {
                // 确保物理层是通的
                resurrectAndConnect(cl, ctx);
                try{Thread.sleep(300);}catch(Exception e){}
                
                sendAppLog(ctx, "执行 V20 协议注入...");

                // ID 7: 启动
                injectAmapJson(cl, 7, "{}", ctx);
                try{Thread.sleep(300);}catch(Exception e){}
                
                // ID 3027: 导航开始 (autoStatus 13)
                injectAmapJson(cl, 3027, "{\"autoStatus\":13,\"eventMapVendor\":4}", ctx);
                try{Thread.sleep(400);}catch(Exception e){}
                
                // ID 101: 引导信息 (杀手锏)
                String guideJson = "{\"turnId\":2,\"roadName\":\"V20修正版\",\"distance\":888,\"nextRoadName\":\"成功\",\"cameraDist\":0}";
                injectAmapJson(cl, 101, guideJson, ctx);
                
                sendAppLog(ctx, "✅ 激活指令已发送");
                
            } else if (status == 28) {
                injectAmapJson(cl, 3027, "{\"autoStatus\":28,\"eventMapVendor\":4}", ctx);
            } else if (status == 29) {
                injectAmapJson(cl, 3027, "{\"autoStatus\":29,\"eventMapVendor\":4}", ctx);
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

            sendAppLog(ctx, "冷启动序列(V20)已触发");
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
