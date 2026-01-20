package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.lang.reflect.Field;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // 官方类名
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; 
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_XSF)) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                sendAppLog(context, "✅ 模块加载成功 (官方复刻版)");
                registerReceiver(context, lpparam.classLoader);
            }
        });
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                
                // 1. 启动服务逻辑 (复刻官方 Intent)
                if ("XSF_ACTION_START_SERVICE".equals(action)) {
                    startOfficialService(ctx);
                } 
                
                // 2. 发送状态 (支持 28 巡航)
                else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                    int status = intent.getIntExtra("status", 0);
                    if (status == 28) {
                        sendAppLog(ctx, "🚀 执行官方巡航逻辑 (Status 28)");
                        // 官方逻辑：先发状态，再发消息循环（这里简化为先发一组数据）
                        sendStatus(cl, 28, ctx); 
                        new Thread(()->{
                            try { Thread.sleep(200); } catch(Exception e){}
                            sendOfficialGuide(cl, ctx); // 紧接着发一条数据
                        }).start();
                    } else {
                        sendStatus(cl, status, ctx);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("XSF_ACTION_START_SERVICE");
        filter.addAction("XSF_ACTION_SEND_STATUS");
        context.registerReceiver(receiver, filter);
    }

    // 复刻官方的 startService 代码
    private void startOfficialService(Context ctx) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("ecarx.naviservice", "ecarx.naviservice.service.NaviService"));
            intent.setAction("ecarx.intent.action.NAVI_SERVICE_STARTED");
            intent.addCategory("ecarx.intent.category.NAVI_INNER");
            ctx.startService(intent);
            sendAppLog(ctx, "⚡ 已发送官方启动广播 (NAVI_SERVICE_STARTED)");
        } catch (Exception e) {
            sendAppLog(ctx, "❌ 启动服务失败: " + e.getMessage());
        }
    }

    // 复刻官方 sendStatus (尝试 vendor 1 和 2)
    private void sendStatus(ClassLoader cl, int statusValue, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> infoCls = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            
            // 尝试构造参数 1 和 2，确保命中
            int[] vendors = {1, 2};
            boolean success = false;
            
            for (int v : vendors) {
                try {
                    Object infoObj = XposedHelpers.newInstance(infoCls, v);
                    // 调用 setStatus 方法 (这是官方做法)
                    XposedHelpers.callMethod(infoObj, "setStatus", statusValue);
                    
                    // 打包发送
                    Object msg = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WRAPPER, cl), 0x7d2, infoObj);
                    XposedHelpers.callMethod(bus, "a", msg);
                    success = true;
                } catch (Throwable t) {}
            }
            
            if (success) sendAppLog(ctx, "状态码 " + statusValue + " 已送达总线");
            else sendAppLog(ctx, "⚠️ 状态码发送可能失败 (构造函数不匹配)");
            
        } catch (Exception e) {
            sendAppLog(ctx, "❌ 发送报错: " + e.getMessage());
        }
    }

    // 复刻官方 sendGuideInfo
    private void sendOfficialGuide(ClassLoader cl, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> guideCls = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            
            // 同样尝试 vendor 1 和 2
            int[] vendors = {1, 2};
            for (int v : vendors) {
                try {
                    Object gObj = XposedHelpers.newInstance(guideCls, v);
                    XposedHelpers.callMethod(gObj, "setGuideType", 2);
                    XposedHelpers.callMethod(gObj, "setTurnId", 0x66); // 官方demo里的值
                    XposedHelpers.callMethod(gObj, "setCurRoadName", "测试路");
                    XposedHelpers.callMethod(gObj, "setNextRoadName", "成功街");
                    XposedHelpers.callMethod(gObj, "setNextTurnDistance", 500);
                    
                    Object msg = XposedHelpers.newInstance(XposedHelpers.findClass(CLS_WRAPPER, cl), 0x7d0, gObj);
                    XposedHelpers.callMethod(bus, "a", msg);
                } catch (Throwable t) {}
            }
        } catch (Exception e) {}
    }

    private void sendAppLog(Context ctx, String log) {
        Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
        i.putExtra("log", log);
        ctx.sendBroadcast(i);
    }
}
