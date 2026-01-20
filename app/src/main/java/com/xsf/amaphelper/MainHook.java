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
    
    // 类名常量 (根据 Smali 分析)
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_SWITCH_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo"; // 新增
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_SERVICE = "ecarx.naviservice.service.NaviService";
    private static final String CLS_CONNECTION = "ecarx.naviservice.b";
    
    // 专家建议的拦截类
    private static final String CLS_VERSION_UTIL = "ecarx.naviservice.d.y"; // 版本解析
    private static final String CLS_PROTOCOL_MGR = "ecarx.naviservice.map.d.g"; // 协议绑定管理

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_XSF)) return;

        // 1. Hook Application: 注入反馈
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                sendAppLog(context, "STATUS_HOOK_READY");
                registerReceiver(context, lpparam.classLoader);
            }
        });

        // 2. 🛡️【关键补丁】欺骗版本号检查 (z.smali/y.smali)
        try {
            XposedHelpers.findAndHookMethod(CLS_VERSION_UTIL, lpparam.classLoader, "b", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 强行返回 70500 (7.5.0)，防止系统觉得高德版本太低而拒收
                    param.setResult(70500); 
                    // sendAppLog(null, "已拦截版本校验，伪装为 7.5.0");
                }
            });
        } catch (Throwable t) { /* 忽略类找不到的错误 */ }

        // 3. 🛡️【核心补丁】伪造服务绑定状态 (g.smali)
        try {
            // 强制让 isBind (字段 c) 变为 true
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, lpparam.classLoader, "f", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    XposedHelpers.setBooleanField(param.thisObject, "c", true);
                    return true;
                }
            });
            
            // 拦截 h() 方法，模拟回调触发
            XposedHelpers.findAndHookMethod(CLS_PROTOCOL_MGR, lpparam.classLoader, "h", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object lac = XposedHelpers.getObjectField(param.thisObject, "g");
                    if (lac != null) {
                        XposedHelpers.callMethod(lac, "a"); // 触发 connected
                    }
                }
            });
        } catch (Throwable t) { /* 忽略 */ }

        // 4. 服务运行反馈
        try {
            XposedHelpers.findAndHookMethod(CLS_SERVICE, lpparam.classLoader, "onStartCommand", Intent.class, int.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sendAppLog((Context)param.thisObject, "STATUS_SERVICE_RUNNING");
                }
            });
        } catch (Throwable t) {}

        // 5. IPC 连接监控
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
                    // 激活流程：切换信号 -> 13 -> 25
                    if (status == 13) {
                        // 1. 先发 0x7d7 (Switch to Vendor 4)
                        sendMapSwitching(cl, ctx);
                        
                        // 2. 延时发 13
                        new Thread(()->{ 
                            try{Thread.sleep(500);}catch(Exception e){} 
                            sendData(cl, 13, 4, ctx); 
                            
                            // 3. 再发 25
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

    // 🚀 新增：发送地图源切换信号 (0x7d7)
    private void sendMapSwitching(ClassLoader cl, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> switchCls = XposedHelpers.findClass(CLS_SWITCH_INFO, cl);
            
            // 构造 MapSwitchingInfo(int from, int to) -> 从 0 切到 4
            Object switchObj = XposedHelpers.newInstance(switchCls, 0, 4);
            
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d7, switchObj); // 0x7d7 = Switch
            XposedHelpers.callMethod(bus, "a", msg);
            sendAppLog(ctx, "已发送 MapSwitchingInfo (0->4)");
        } catch (Throwable e) {
            // 如果类不存在，也不要崩溃，继续后面的流程
            // sendAppLog(ctx, "SwitchingInfo 发送跳过: " + e.getMessage());
        }
    }

    private void sendData(ClassLoader cl, int statusValue, int vendor, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> infoCls = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            
            // 只用构造函数传参 (专家建议去掉 setMapVendor 以防万一)
            Object infoObj = XposedHelpers.newInstance(infoCls, vendor);
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
            XposedHelpers.callMethod(gObj, "setCurRoadName", "成功大道");
            XposedHelpers.callMethod(gObj, "setNextRoadName", "胜利街");
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
            
            // 延时加大到 3 秒
            new Thread(() -> {
                try {
                    Thread.sleep(3000); 
                    ctx.sendBroadcast(new Intent("ecarx.intent.action.MAP_OPEN"));
                    sendAppLog(ctx, "MAP_OPEN 广播已补发");
                    
                    Thread.sleep(500);
                    Intent vIntent = new Intent("com.ecarx.naviservice.action.MAP_VENDOR_CHANGE");
                    vIntent.putExtra("EXTRA_MAP_VENDOR", 4);
                    ctx.sendBroadcast(vIntent);
                } catch (Exception e) {}
            }).start();
            
            sendAppLog(ctx, "冷启动执行中 (延时3秒等待初始化)...");
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
