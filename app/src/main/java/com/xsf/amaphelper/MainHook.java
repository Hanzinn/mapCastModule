package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    
    // 权限 (虽然我们回退到隐式，但带着权限总没错)
    private static final String PERMISSION_NAVI = "ecarx.oem.permission.OPENAPI_NAVI_PERMISSION";

    // 这个 Context 只在 Service 进程有效
    private static Context mServiceContext = null;
    // 心跳开关 (只在 Service 进程有效)
    private static boolean isHeartbeatRunning = false; 

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 0. 自身激活检测
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 1. Hook NaviService (宿主：负责发广播、亮灯) -> 恢复 V30 逻辑
        if (lpparam.packageName.equals(PKG_SERVICE)) {
            initNaviServiceHook(lpparam);
        }

        // 2. Hook NaviWidget (显示端：负责劫持数据、内部唤醒)
        if (lpparam.packageName.equals(PKG_WIDGET)) {
            initNaviWidgetHook(lpparam);
        }
    }

    // =============================================================
    // PART 1: NaviService 进程 (恢复 V30 的通讯能力 - 确保亮灯)
    // =============================================================
    private void initNaviServiceHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 🌟 回退到 Application.onCreate，这是最稳的 Context 获取点
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                mServiceContext = (Context) param.thisObject;
                sendAppLog(mServiceContext, "STATUS_HOOK_READY (V40-Revival)");
                // 立即注册接收器，确保按钮好使
                registerReceiver(mServiceContext, lpparam.classLoader);
            }
        });
        
        // 辅助：生存补丁
        try { XposedHelpers.findAndHookMethod("ecarx.naviservice.d.y", lpparam.classLoader, "b", String.class, XC_MethodReplacement.returnConstant(70500)); } catch (Throwable t) {}
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                        // 收到 App 开关指令
                        int status = intent.getIntExtra("status", 0);
                        sendAppLog(ctx, "收到指令: " + status);
                        handleStatusAction(ctx, status);
                    }
                    else if ("XSF_ACTION_FORCE_CONNECT".equals(action)) {
                        // 收到 App 强制连接指令 (手动点亮)
                        sendAppLog(ctx, "⚡ 执行强制重连...");
                        // 这里不再做复杂的 Matrix 操作，直接点亮状态
                        sendAppLog(ctx, "⚡ IPC 绿灯 (V40)");
                        sendHandshakeBroadcasts(ctx, 1);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction("XSF_ACTION_SEND_STATUS");
            filter.addAction("XSF_ACTION_FORCE_CONNECT");
            context.registerReceiver(receiver, filter);
            sendAppLog(context, "✅ 监听器已恢复");
        } catch (Throwable t) {
            sendAppLog(context, "监听器注册失败: " + t);
        }
    }

    // =============================================================
    // PART 2: NaviWidget 进程 (核心数据劫持 + 内部唤醒)
    // =============================================================
    private void initNaviWidgetHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // A. 劫持数据接口 (V38 无门槛版)
        hookEcarxOpenApi(lpparam);

        // B. 内部爆破：Activity 启动时，自己在内部伪造广播！
        // 这样可以绕过系统对外部广播的拦截，逼迫组件刷新
        try {
            XposedHelpers.findAndHookMethod("com.ecarx.naviwidget.DisplayInfoActivity", lpparam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context ctx = (Context) param.thisObject;
                    XposedBridge.log("NaviHook: [Widget] Activity 启动，准备内部唤醒...");
                    
                    // 🌟 核心杀招：在组件进程内部发送广播，系统无法拦截！
                    sendInternalWakeUp(ctx);
                }
            });
        } catch (Throwable t) {}
    }

    // 内部唤醒：直接在 Widget 进程发广播给它自己
    private void sendInternalWakeUp(Context ctx) {
        new Thread(() -> {
            try {
                Thread.sleep(2000); // 等 Activity 初始化完
                
                // 伪造 UPDATE_STATUS (开始导航)
                Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
                iStatus.putExtra("status", 1); 
                iStatus.putExtra("is_navi", true);
                iStatus.putExtra("vendor", 1); // 先试 Vendor 1
                iStatus.putExtra("route_state", 0);
                iStatus.setPackage(PKG_WIDGET); // 发给自己
                ctx.sendBroadcast(iStatus); // 这里发广播肯定能收到，因为是同进程
                
                XposedBridge.log("NaviHook: [Widget] 内部唤醒广播已发送 (Vendor 1)");
                
                Thread.sleep(1000);
                
                // 伪造 REFRESH_WIDGET
                Intent iRefresh = new Intent("ecarx.navi.REFRESH_WIDGET");
                iRefresh.setPackage(PKG_WIDGET);
                ctx.sendBroadcast(iRefresh);
                
            } catch (Exception e) {}
        }).start();
    }

    private void hookEcarxOpenApi(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> apiClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.EcarxOpenApi", lpparam.classLoader);
            Class<?> callbackClass = XposedHelpers.findClass("com.neusoft.nts.ecarxnavsdk.IAPIGetGuideInfoCallBack", lpparam.classLoader);
            
            XposedHelpers.findAndHookMethod(apiClass, "getGuideInfo", callbackClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 🚨 无条件拦截！只要组件来问，无条件给数据！不判断开关！
                    // 这样即使 Service 进程的心跳没开，只要组件刷新，就能拿到数据
                    XposedBridge.log("NaviHook: [Widget] 拦截到查询请求，注入 V40 数据!");
                    
                    Object callback = param.args[0];
                    if (callback != null) {
                        try {
                            // 17 参数全量注入
                            XposedHelpers.callMethod(callback, "getGuideInfoResult",
                                1, // type
                                666, // remain_dis (特征值)
                                60, // remain_time
                                0, 0, 0, // camera
                                "V40复活成功", // road
                                "V40复活成功", // next_road
                                0.5f, // progress
                                0, // nav_type
                                300, // distance
                                2, // icon (左转)
                                "当前路名V40", 
                                666, 60, 0, 0 // total & unknown
                            );
                            param.setResult(true); // 拦截成功，不再执行原方法
                        } catch (Throwable e) {
                            XposedBridge.log("NaviHook: 注入异常 " + e);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook API Hook Err: " + t);
        }
    }

    // =============================================================
    // PART 3: 心跳逻辑 (广播发射)
    // =============================================================
    private void handleStatusAction(Context ctx, int status) {
        if (isHeartbeatRunning) return;
        isHeartbeatRunning = true;
        
        new Thread(() -> {
            sendAppLog(ctx, "💓 V40 引擎启动...");
            int count = 0;
            while (isHeartbeatRunning) {
                try {
                    // 1. 物理维持
                    if (count % 5 == 0) keepAliveAndGreen(ctx.getClassLoader(), ctx);
                    
                    // 2. 发送外部握手 (作为辅助，万一能收到呢)
                    int vendor = (count % 2 == 0) ? 1 : 4;
                    sendHandshakeBroadcasts(ctx, vendor);
                    
                    // 3. 补发焦点
                    Intent iFocus = new Intent("com.ecarx.intent.action.NAVI_FOCUS_GAIN");
                    iFocus.putExtra("packageName", "com.autonavi.amapauto");
                    ctx.sendBroadcast(iFocus);

                    Thread.sleep(2000); 
                    count++;
                } catch (Exception e) { break; }
            }
        }).start();
    }

    private void sendHandshakeBroadcasts(Context ctx, int vendor) {
        try {
            // 告诉组件：导航开始了 (状态机唤醒)
            Intent iStatus = new Intent("ecarx.navi.UPDATE_STATUS");
            iStatus.putExtra("status", 1);
            iStatus.putExtra("is_navi", true);
            iStatus.putExtra("vendor", vendor);
            iStatus.putExtra("route_state", 0);
            // 显式指定发给 Widget 包，确保收到
            iStatus.setPackage(PKG_WIDGET);
            ctx.sendBroadcast(iStatus); 
            
            // 强制刷新
            Intent iRefresh = new Intent("ecarx.navi.REFRESH_WIDGET");
            iRefresh.setPackage(PKG_WIDGET);
            ctx.sendBroadcast(iRefresh);
        } catch (Throwable t) {}
    }

    // 🚑 Matrix Lite (修复版)
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
                XposedHelpers.callMethod(conn, "onServiceConnected", new ComponentName("f","f"), null); // Binder传null也能亮
                sendAppLog(ctx, "⚡ IPC 绿灯 (Matrix)");
            }
        } catch (Throwable t) {}
    }

    // 恢复 V30 的隐式日志广播，解决 App 灯不亮问题
    private void sendAppLog(Context ctx, String log) {
        if (ctx == null) return;
        try {
            Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
            i.setPackage(PKG_SELF); // 必须指定你的 App 包名
            i.putExtra("log", log);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {}
    }
}
