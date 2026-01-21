package com.xsf.amaphelper;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder; 
import android.os.Bundle;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_WIDGET = "com.ecarx.naviwidget";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // 权限
    private static final String PERMISSION_NAVI = "ecarx.oem.permission.OPENAPI_NAVI_PERMISSION";

    private static Context mServiceContext = null;
    private static boolean isReceiverRegistered = false;
    private static boolean isHeartbeatRunning = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 1. Hook NaviService (宿主 & 强控中心)
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        // 2. Hook NaviWidget (显示端)
        if (lpparam.packageName.equals(PKG_WIDGET)) {
            XposedBridge.log("NaviHook: 注入 NaviWidget 进程成功");
            hookEcarxOpenApi(lpparam);
        }
    }

    // ===========================
    // 🗡️ API 劫持 (带防爆盾)
    // ===========================
    private void hookEcarxOpenApi(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> apiClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.EcarxOpenApi", lpparam.classLoader);
            Class<?> callbackClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.IAPIGetGuideInfoCallBack", lpparam.classLoader);
            
            XposedHelpers.findAndHookMethod(apiClass, "getGuideInfo", callbackClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 无条件拦截！只要组件敢问，我们就敢给！
                    XposedBridge.log("NaviHook: [Widget进程] 拦截到 getGuideInfo，准备注入...");
                    
                    Object callback = param.args[0];
                    if (callback != null) {
                        try {
                            // 17参数全量注入 (严格匹配 Smali 类型)
                            XposedHelpers.callMethod(callback, "getGuideInfoResult",
                                1, // type (1=转向)
                                666, // remain_dis
                                120, // remain_time
                                0, 0, 0, // camera args
                                "V39强控版", // road name
                                "V39强控版", // next road
                                0.5f, // progress
                                0, // nav_type
                                300, // distance
                                2, // icon
                                "当前路名V39", // cur road
                                666, 120, 0, 0 // total args
                            );
                            param.setResult(true); // 拦截成功
                            XposedBridge.log("NaviHook: ✅ 数据注入成功！");
                        } catch (Throwable t) {
                            // 🔴 防爆盾：如果注入失败，打印错误，但不要让 App 崩溃
                            XposedBridge.log("NaviHook: ❌ 数据注入异常: " + t);
                            // 不调用 setResult(true)，让它走原生逻辑作为保底
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: API Hook 初始化失败: " + t);
        }
    }

    // ===========================
    // 🧠 NaviService Hook (强控发射源)
    // ===========================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 抢跑注入
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject instanceof Service) {
                    mServiceContext = (Context) param.thisObject;
                    ensureReceiverRegistered(mServiceContext);
                    
                    // 🌟 核心：使用 Root 广播点亮 App 灯
                    sendShellLog("STATUS_HOOK_READY (V39-Root)");
                    updateAppUIStatusRoot(13);
                    
                    // 物理绿灯
                    keepAliveAndGreen(lpparam.classLoader, mServiceContext);
                    
                    if (!isHeartbeatRunning) {
                        handleStatusAction(lpparam.classLoader, mServiceContext, 13);
                    }
                }
            }
        });
        
        try { XposedHelpers.findAndHookMethod("ecarx.naviservice.d.y", lpparam.classLoader, "b", String.class, XC_MethodReplacement.returnConstant(70500)); } catch (Throwable t) {}
    }

    // 🤝 Root 级广播：唤醒组件 + 点亮 App
    private void sendRootBroadcasts(int count) {
        try {
            // 1. REFRESH_WIDGET (唤醒组件)
            Runtime.getRuntime().exec("am broadcast -a ecarx.navi.REFRESH_WIDGET -p " + PKG_WIDGET);
            
            // 2. UPDATE_STATUS (告诉组件：进导航模式！)
            // --ei 参数传递 int, --ez 传递 boolean
            int vendor = (count % 2 == 0) ? 1 : 4;
            String cmd = "am broadcast -a ecarx.navi.UPDATE_STATUS -p " + PKG_WIDGET + 
                         " --ei status 1 --ez is_navi true --ei route_state 0 --ei vendor " + vendor;
            Runtime.getRuntime().exec(cmd);
            
            // 3. 补充：UPDATE_GUIDEINFO (作为敲门砖)
            Runtime.getRuntime().exec("am broadcast -a ecarx.navi.UPDATE_GUIDEINFO -p " + PKG_WIDGET + 
                                      " --es road_name V39唤醒 --ei guide_type 1");

        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Root广播失败: " + t);
        }
    }

    private void handleStatusAction(ClassLoader cl, Context ctx, int status) {
        if (isHeartbeatRunning) return;
        isHeartbeatRunning = true;
        
        new Thread(() -> {
            sendShellLog("💓 V39 系统强控引擎启动...");
            int count = 0;
            while (isHeartbeatRunning) {
                try {
                    // 物理维持
                    if (count % 10 == 0) keepAliveAndGreen(cl, ctx);
                    
                    // 🌟 系统级强控广播
                    sendRootBroadcasts(count);
                    
                    // 补发焦点
                    Intent iFocus = new Intent("com.ecarx.intent.action.NAVI_FOCUS_GAIN");
                    iFocus.putExtra("packageName", "com.autonavi.amapauto");
                    ctx.sendBroadcast(iFocus); // 普通广播作为辅助

                    // 维持 App 绿灯
                    if (count % 5 == 0) updateAppUIStatusRoot(13);

                    Thread.sleep(2000); 
                    count++;
                } catch (Exception e) { break; }
            }
        }).start();
    }

    // 辅助：使用 Shell 命令发送日志给 App (解决跨用户问题)
    private void sendShellLog(String log) {
        try {
            String cmd = "am broadcast -a com.xsf.amaphelper.LOG_UPDATE -n com.xsf.amaphelper/.receiver.LogReceiver --es log \"" + log + "\"";
            // 如果不知道 Receiver 全名，尝试包名匹配
            String cmdSimple = "am broadcast -a com.xsf.amaphelper.LOG_UPDATE -p com.xsf.amaphelper --es log \"" + log + "\"";
            Runtime.getRuntime().exec(cmdSimple);
        } catch (Throwable t) {}
    }

    // 辅助：使用 Shell 命令点亮 UI 灯
    private void updateAppUIStatusRoot(int status) {
        try {
            String cmd = "am broadcast -a com.xsf.amaphelper.STATUS_UPDATE -p com.xsf.amaphelper --ei status " + status;
            Runtime.getRuntime().exec(cmd);
        } catch (Throwable t) {}
    }

    private void ensureReceiverRegistered(Context ctx) {
        if (isReceiverRegistered) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("XSF_ACTION_SEND_STATUS".equals(intent.getAction())) {
                        // 收到 App 指令
                        handleStatusAction(context.getClassLoader(), context, intent.getIntExtra("status", 0));
                    }
                }
            };
            ctx.registerReceiver(receiver, new IntentFilter("XSF_ACTION_SEND_STATUS"));
            isReceiverRegistered = true;
        } catch (Throwable t) {}
    }

    private void keepAliveAndGreen(ClassLoader cl, Context ctx) {
        try {
            Class<?> q = XposedHelpers.findClass("q", cl);
            Object mgr = XposedHelpers.getStaticObjectField(q, "a");
            if (mgr == null) {
                mgr = XposedHelpers.newInstance(XposedHelpers.findClass("l", cl));
                XposedHelpers.setStaticObjectField(q, "a", mgr);
            }
            Object conn = XposedHelpers.getObjectField(mgr, "i");
            if (conn != null) {
                XposedHelpers.callMethod(conn, "onServiceConnected", new ComponentName("f","f"), new Binder());
            }
        } catch (Throwable t) {}
    }
}
