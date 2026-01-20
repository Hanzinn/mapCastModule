package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder; 
import java.lang.reflect.Constructor;
import java.lang.reflect.Method; // 🟢 引入反射包
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // 类名常量
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_SWITCH_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_SERVICE = "ecarx.naviservice.service.NaviService";
    
    private static final String CLS_CONNECTION_B = "ecarx.naviservice.b"; 
    private static final String CLS_NEUSOFT_SDK = "ecarx.naviservice.map.d.a";
    private static final String CLS_VERSION_UTIL = "ecarx.naviservice.d.y"; 
    private static final String CLS_PROTOCOL_MGR = "ecarx.naviservice.map.d.g";
    
    private static final String CLS_WIDGET_MGR_HOLDER = "ecarx.naviservice.map.q"; 
    private static final String CLS_WIDGET_MGR = "ecarx.naviservice.map.l"; 

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
                sendAppLog(appCtx, "STATUS_HOOK_READY");
                registerReceiver(appCtx, lpparam.classLoader);
            }
        });

        // 2. Service Context 捕获
        try {
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mServiceContext = (Context) param.thisObject;
                    sendAppLog(mServiceContext, "已捕获 Service Context");
                }
            });
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mServiceContext = (Context) param.thisObject;
                    sendAppLog(mServiceContext, "STATUS_SERVICE_RUNNING");
                }
            });
        } catch (Throwable t) {}

        // 3. 版本欺骗
        try {
            XposedHelpers.findAndHookMethod(CLS_VERSION_UTIL, lpparam.classLoader, "b", String.class, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    return 70500;
                }
            });
        } catch (Throwable t) {}

        // 4. 心脏起搏
        patchHeartbeat(lpparam.classLoader);

        // 5. 监控 IPC
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

    private void patchHeartbeat(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, cl, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object instance = param.getResult();
                    if (instance != null) {
                        XposedHelpers.setBooleanField(instance, "c", true);
                        Object lac = XposedHelpers.getObjectField(instance, "g");
                        if (lac != null) {
                            try { XposedHelpers.callMethod(lac, "a", 1); } 
                            catch (Throwable t) { try { XposedHelpers.callMethod(lac, "a"); } catch (Throwable t2) {} }
                        }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, cl, "f", XC_MethodReplacement.returnConstant(true));
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
                        int status = intent.getIntExtra("status", 0);
                        handleStatusAction(cl, ctx, status);
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

    // 🚑 V15: 天眼诊断 + 安全连接
    private void resurrectAndConnect(ClassLoader cl, Context ctx) {
        try {
            Context targetCtx = (mServiceContext != null) ? mServiceContext : ctx;
            sendAppLog(ctx, ">>> 开始 V15 诊断与复活 <<<");

            if (cl == null) {
                sendAppLog(ctx, "Fatal: ClassLoader 为空!");
                return;
            }

            // 1. 尝试加载 WidgetManager 类 (l.smali)
            Class<?> mgrClass = null;
            try {
                mgrClass = XposedHelpers.findClass(CLS_WIDGET_MGR, cl);
                sendAppLog(ctx, "Found class: " + CLS_WIDGET_MGR);
                
                // 👁️ 天眼模式：打印所有方法名，寻找 bindService 的真身
                sendAppLog(ctx, "--- 开始扫描 l.smali 方法 ---");
                for (Method m : mgrClass.getDeclaredMethods()) {
                    // 只打印 public 的方法，减少日志量
                    if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                        String args = "";
                        for (Class<?> p : m.getParameterTypes()) args += p.getSimpleName() + ",";
                        sendAppLog(ctx, "Method: " + m.getName() + "(" + args + ")");
                    }
                }
                sendAppLog(ctx, "--- 扫描结束 ---");

            } catch (Throwable t) {
                sendAppLog(ctx, "找不到类 l: " + t.getMessage());
                return; // 找不到类就没法玩了，直接返回
            }

            // 2. 尝试新建实例 (绕过 q.a，直接 new)
            Object mgrInstance = null;
            try {
                // 尝试无参构造
                mgrInstance = XposedHelpers.newInstance(mgrClass);
                sendAppLog(ctx, "直接 new l() 成功");
            } catch (Throwable t) {
                try {
                    // 尝试反射构造
                    Constructor<?>[] cons = mgrClass.getDeclaredConstructors();
                    if (cons.length > 0) {
                        cons[0].setAccessible(true);
                        mgrInstance = cons[0].newInstance(new Object[cons[0].getParameterCount()]);
                        sendAppLog(ctx, "反射 new l(...) 成功");
                    }
                } catch (Throwable t2) {
                    sendAppLog(ctx, "实例化失败: " + t2.getMessage());
                    return;
                }
            }

            // 3. 盲测连接方法 (基于刚才的扫描逻辑)
            if (mgrInstance != null) {
                // 尝试调用所有带 Context 参数的方法 (大概率是 bindService)
                boolean called = false;
                
                // 优先试 'a' (最常见混淆名)
                try {
                    XposedHelpers.callMethod(mgrInstance, "a", targetCtx);
                    sendAppLog(ctx, "调用 l.a(Context) 成功");
                    called = true;
                } catch (Throwable t) {}

                // 再试 'b'
                if (!called) {
                    try {
                        XposedHelpers.callMethod(mgrInstance, "b", targetCtx);
                        sendAppLog(ctx, "调用 l.b(Context) 成功");
                        called = true;
                    } catch (Throwable t) {}
                }
                
                // 如果都没成功，打印提示
                if (!called) sendAppLog(ctx, "⚠️ 未能成功调用任何连接方法");
            }
            
            // 4. 安全发送 SwitchInfo
            safeSendSwitchInfo(cl, ctx);

        } catch (Throwable e) {
            sendAppLog(ctx, "诊断异常: " + e.getMessage());
        }
    }

    private void safeSendSwitchInfo(ClassLoader cl, Context ctx) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            if (busClass == null) return;
            Object bus = XposedHelpers.callStaticMethod(busClass, "a");
            if (bus == null) {
                sendAppLog(ctx, "EventBus(d.e) 未初始化");
                return;
            }

            Class<?> switchCls = XposedHelpers.findClass(CLS_SWITCH_INFO, cl);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object switchObj = XposedHelpers.newInstance(switchCls, 0, 4);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d7, switchObj);
            
            XposedHelpers.callMethod(bus, "a", msg);
            sendAppLog(ctx, "SwitchInfo 安全发送完毕");
        } catch (Throwable e) {
            // sendAppLog(ctx, "Switch Err");
        }
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        if (status == 13) {
            new Thread(()->{
                sendAppLog(ctx, "执行: 28 -> Switch -> 13 -> 25");
                sendData(cl, 28, ctx); 
                try{Thread.sleep(300);}catch(Exception e){}
                safeSendSwitchInfo(cl, ctx);
                try{Thread.sleep(300);}catch(Exception e){}
                sendData(cl, 13, ctx);
                try{Thread.sleep(500);}catch(Exception e){}
                sendData(cl, 25, ctx);
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
                } catch(Throwable t) {}
            }
            sendAppLog(ctx, "Status " + statusValue + " 已广播");
        } catch (Exception e) {}
    }

    private void sendGuide(ClassLoader cl, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            if (bus == null) return;
            
            Class<?> guideCls = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object gObj = XposedHelpers.newInstance(guideCls, 4);
            XposedHelpers.callMethod(gObj, "setGuideType", 2);
            XposedHelpers.callMethod(gObj, "setTurnId", 2);
            XposedHelpers.callMethod(gObj, "setCurRoadName", "V15 诊断");
            XposedHelpers.callMethod(gObj, "setNextTurnDistance", 500);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d0, gObj);
            XposedHelpers.callMethod(bus, "a", msg);
        } catch (Exception e) {}
    }

    // 补全
    private void sendMultipleSwitching(ClassLoader cl, Context ctx) {
        safeSendSwitchInfo(cl, ctx);
    }

    private void startOfficialService(Context ctx, ClassLoader cl) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("ecarx.naviservice", "ecarx.naviservice.service.NaviService"));
            intent.setAction("ecarx.intent.action.NAVI_SERVICE_STARTED");
            intent.addCategory("ecarx.intent.category.NAVI_INNER");
            ctx.startService(intent);
            
            startWatchdog(ctx, cl); 

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
            sendAppLog(ctx, "冷启动指令已发...");
        } catch (Exception e) { sendAppLog(ctx, "启动失败: " + e.getMessage()); }
    }

    private void startWatchdog(Context ctx, ClassLoader cl) {
        new Thread(() -> {
            try {
                Thread.sleep(4000);
                if (isIpcConnected) {
                    sendAppLog(ctx, "看门狗: IPC正常");
                    return;
                }
                sendAppLog(ctx, "⚠️ 看门狗: 触发诊断与复活...");
                resurrectAndConnect(cl, ctx);
            } catch (Exception e) {}
        }).start();
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
