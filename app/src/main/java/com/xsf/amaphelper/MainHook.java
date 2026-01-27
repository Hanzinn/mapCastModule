package com.xsf.amaphelper;

import android.app.Application;
import android.app.Presentation;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.UserHandle;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.TextView;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // 目标特征字符串，用于识别高德服务连接
    private static final String TARGET_SERVICE_KEYWORD = "AutoSimilarWidgetViewService";
    
    // 伪造的 Service 类名 (必须和 AndroidManifest 里一致)
    private static final String FAKE_SERVICE_CLASS = "com.xsf.amaphelper.FakeNaviService";

    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Presentation clusterWindow = null;
    private static Binder fakeServiceBinder = null;
    private static boolean isReceiverRegistered = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 1. 激活模块自身UI
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 2. 只注入导航服务进程
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V136 注入成功 (PID: " + android.os.Process.myPid() + ")");

        // 3. 获取 Context (双重保险)
        XC_MethodHook contextHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context ctx = (Context) param.thisObject;
                if (systemContext == null) {
                    systemContext = ctx;
                    mainHandler = new Handler(Looper.getMainLooper());
                    XposedBridge.log("NaviHook: Context 获取成功!");
                    initEverything();
                }
            }
        };
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", contextHook);
        try {
            XposedHelpers.findAndHookMethod("ecarx.naviservice.service.NaviService", lpparam.classLoader, "onCreate", contextHook);
        } catch (Throwable t) {}

        // 4. 强制识别为高德 (Vendor = 0)
        try {
            // 根据之前的分析，ecarx.naviservice.map.ce 似乎是 MapConfigWrapper
            Class<?> configClass = XposedHelpers.findClassIfExists("ecarx.naviservice.map.ce", lpparam.classLoader);
            if (configClass != null) {
                // 假设 b() 方法返回 mapVendor
                XposedHelpers.findAndHookMethod(configClass, "b", XC_MethodReplacement.returnConstant(0));
                XposedBridge.log("NaviHook: 🔓 强制 Vendor=0 (AMAP) 成功");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Vendor Hook Error: " + t);
        }

        // 5. 核心：劫持 bindService
        hookBindService(lpparam.classLoader);
    }

    private void initEverything() {
        if (fakeServiceBinder == null) createFakeBinder();
        
        if (!isReceiverRegistered && systemContext != null) {
            try {
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        String action = intent.getAction();
                        if ("XSF_ACTION_START_CAST".equals(action)) {
                            sendJavaBroadcast("收到开启指令...");
                            createOverlayWindow();
                        } else if ("XSF_ACTION_STOP_CAST".equals(action)) {
                            destroyOverlayWindow();
                        }
                    }
                };
                IntentFilter filter = new IntentFilter();
                filter.addAction("XSF_ACTION_START_CAST");
                filter.addAction("XSF_ACTION_STOP_CAST");
                systemContext.registerReceiver(receiver, filter);
                isReceiverRegistered = true;
                sendJavaBroadcast("✅ 模块就绪，Context已获取");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: Receiver Error: " + t);
            }
        }
    }

    // 🟢 创建伪造的 Binder (模拟高德服务端)
    private void createFakeBinder() {
        fakeServiceBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                // 只要系统调这个 Binder，我们就认为连接通了
                try {
                    // data.enforceInterface("com.autosimilarwidget.view.IAutoSimilarWidgetViewService");
                    // 即使 Interface Token 不对，我们也尽量不抛异常
                    
                    if (code == 1) { // setSurface
                        XposedBridge.log("NaviHook: ⚡ 系统调用 setSurface (code=1)");
                        sendJavaBroadcast("⚡ 链路IPC: 系统请求设置 Surface");
                        // 这里我们不需要真的拿 Surface，因为我们是用悬浮窗覆盖
                    } else if (code == 4) { // isReady
                        XposedBridge.log("NaviHook: ⚡ 系统调用 isReady (code=4)");
                        reply.writeNoException();
                        reply.writeInt(1); // true
                        return true;
                    }
                } catch (Throwable e) {
                    XposedBridge.log("NaviHook: Binder Transact Ignored: " + e);
                }
                return true; // 永远返回成功
            }
        };
        sendJavaBroadcast("🛠️ 虚拟Binder已创建");
    }

    // 🟢 拦截 bindService
    private void hookBindService(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", cl, "bindService",
                Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                ServiceConnection conn = (ServiceConnection) param.args[1];
                
                if (intent == null || intent.getComponent() == null) return;
                
                String className = intent.getComponent().getClassName();
                
                // 只要是连高德投屏服务的，一律拦截
                if (className.contains(TARGET_SERVICE_KEYWORD)) {
                    XposedBridge.log("NaviHook: 🚨 拦截到连接请求 -> " + className);
                    sendJavaBroadcast("🚨 拦截到高德连接请求!");
                    
                    // 1. 阻止原方法
                    param.setResult(true); 
                    
                    // 2. 只有初始化好了才能回调
                    if (fakeServiceBinder != null && conn != null) {
                        // 在主线程回调 onServiceConnected
                        if (mainHandler != null) {
                            mainHandler.post(() -> {
                                try {
                                    // 伪造一个 ComponentName，让 ServiceConnection 以为连上了高德
                                    ComponentName cn = new ComponentName("com.autonavi.amapauto", className);
                                    conn.onServiceConnected(cn, fakeServiceBinder);
                                    
                                    XposedBridge.log("NaviHook: ✅ 已手动回调 onServiceConnected");
                                    sendJavaBroadcast("✅ 劫持成功: 已注入虚拟服务");
                                } catch (Throwable t) {
                                    XposedBridge.log("NaviHook: 回调失败: " + t);
                                }
                            });
                        }
                    } else {
                        XposedBridge.log("NaviHook: ❌ 拦截成功但 Binder 未就绪");
                    }
                }
            }
        });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Hook bindService 失败: " + t);
        }
    }

    // 🟢 创建副屏悬浮窗
    private void createOverlayWindow() {
        if (systemContext == null) return;
        
        mainHandler.post(() -> {
            try {
                if (clusterWindow != null) {
                    clusterWindow.dismiss();
                    clusterWindow = null;
                }

                DisplayManager dm = (DisplayManager) systemContext.getSystemService(Context.DISPLAY_SERVICE);
                Display[] displays = dm.getDisplays();
                Display targetDisplay = null;
                
                for (Display d : displays) {
                    // 排除主屏(0)，找副屏
                    if (d.getDisplayId() != 0) {
                        targetDisplay = d;
                        XposedBridge.log("NaviHook: 🎯 找到目标屏幕 ID=" + d.getDisplayId());
                        break;
                    }
                }
                
                if (targetDisplay == null) {
                    sendJavaBroadcast("❌ 错误: 未找到仪表屏幕!");
                    return;
                }

                Context displayContext = systemContext.createDisplayContext(targetDisplay);
                
                clusterWindow = new Presentation(displayContext, targetDisplay) {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        TextView tv = new TextView(getContext());
                        tv.setText("V136 悬浮窗测试\nIPC链路正常");
                        tv.setTextColor(Color.WHITE);
                        tv.setTextSize(40);
                        tv.setGravity(Gravity.CENTER);
                        tv.setBackgroundColor(Color.BLUE); // 蓝色背景方便识别
                        setContentView(tv);
                    }
                };

                // 2038 = TYPE_APPLICATION_OVERLAY
                clusterWindow.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                clusterWindow.getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | 
                    WindowManager.LayoutParams.FLAG_FULLSCREEN | 
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                );
                
                clusterWindow.show();
                sendJavaBroadcast("✅ 投屏窗口已创建 (Type 2038)");
                
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 窗口创建失败: " + t.getMessage());
                XposedBridge.log(t);
            }
        });
    }

    private void destroyOverlayWindow() {
        mainHandler.post(() -> {
            if (clusterWindow != null) {
                try {
                    clusterWindow.dismiss();
                    clusterWindow = null;
                    sendJavaBroadcast("🛑 投屏已关闭");
                } catch (Throwable t) {}
            }
        });
    }

    private void sendJavaBroadcast(String log) {
        if (systemContext == null) return;
        new Thread(() -> {
            try {
                Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
                i.setPackage(PKG_SELF);
                i.putExtra("log", log);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                try {
                    Object userAll = XposedHelpers.getStaticObjectField(UserHandle.class, "ALL");
                    Method method = Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class);
                    method.invoke(systemContext, i, userAll);
                } catch (Throwable t) {
                    systemContext.sendBroadcast(i);
                }
            } catch (Throwable t) {}
        }).start();
    }
}