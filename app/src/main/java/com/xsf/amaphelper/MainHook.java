package com.xsf.amaphelper;

import android.app.Application;
import android.app.Presentation;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    // 目标包名
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    private static final String PKG_MAP = "com.autonavi.amapauto";

    // 关键类名 (根据你上传的 smali 确认)
    private static final String CLASS_MAP_CONFIG = "ecarx.naviservice.map.ce"; 
    private static final String TARGET_AIDL_INTERFACE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String TARGET_SERVICE_IMPL = "com.autosimilarwidget.view.AutoSimilarWidgetViewService";

    // 全局变量
    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Presentation clusterWindow = null;
    private static Binder fakeServiceBinder = null;
    private static boolean isReceiverRegistered = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 1. 激活模块自身UI显示状态
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // 2. 只 Hook 导航服务
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V136 启动 - 注入进程: " + lpparam.processName);

        // 3. 获取 System Context (双重保险)
        // 方案A: Application.onCreate
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context ctx = (Context) param.thisObject;
                if (systemContext == null) {
                    systemContext = ctx;
                    mainHandler = new Handler(Looper.getMainLooper());
                    XposedBridge.log("NaviHook: ✅ 通过 Application 拿到 Context");
                    initEverything();
                }
            }
        });
        
        // 方案B: NaviService.onCreate (备用)
        try {
            XposedHelpers.findAndHookMethod("ecarx.naviservice.service.NaviService", lpparam.classLoader, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context ctx = (Context) param.thisObject;
                    if (systemContext == null) {
                        systemContext = ctx;
                        mainHandler = new Handler(Looper.getMainLooper());
                        XposedBridge.log("NaviHook: ✅ 通过 NaviService 拿到 Context");
                        initEverything();
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Hook NaviService.onCreate 失败 (非致命): " + t);
        }

        // 4. 强制 MapVendor 为 0 (AMAP)
        try {
            Class<?> configClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG, lpparam.classLoader);
            if (configClass != null) {
                XposedHelpers.findAndHookMethod(configClass, "b", XC_MethodReplacement.returnConstant(0));
                XposedBridge.log("NaviHook: 🔓 强制 Vendor=0 (高德) 成功");
            }
        } catch (Throwable t) {
             XposedBridge.log("NaviHook: Hook MapVendor 失败: " + t);
        }

        // 5. 拦截 bindService (核心劫持逻辑)
        hookBindService(lpparam.classLoader);
    }

    private void initEverything() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        
        // 初始化伪造的Binder
        initFakeBinder();
        
        // 注册广播接收器 (接收APP的开关指令)
        if (!isReceiverRegistered && systemContext != null) {
            try {
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        String action = intent.getAction();
                        XposedBridge.log("NaviHook: 收到广播 " + action);
                        if ("XSF_ACTION_START_CAST".equals(action)) {
                            sendJavaBroadcast("收到开启指令，执行操作...");
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
                sendJavaBroadcast("✅ 服务端Hook成功，通信链路就绪");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: 注册广播失败: " + t);
            }
        }
    }

    // 🟢 核心：伪造 Binder，骗过系统的检查
    private void initFakeBinder() {
        if (fakeServiceBinder != null) return;
        
        fakeServiceBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                // 打印所有交互，方便调试
                // XposedBridge.log("NaviHook: Binder被调用 code=" + code);
                
                try {
                    // 强制校验通过
                    data.enforceInterface(TARGET_AIDL_INTERFACE);
                    
                    // 根据 7.5 smali 分析，setSurface 可能是第一个方法
                    if (code == 1) { 
                        XposedBridge.log("NaviHook: ⚡ 系统调用了 setSurface (code=1)");
                        // 读取 Surface (Parcelable)
                        if (data.readInt() != 0) {
                            Surface surface = Surface.CREATOR.createFromParcel(data);
                            int id = data.readInt();
                            XposedBridge.log("NaviHook: 🎯 捕获到系统提供的 Surface: " + surface + " ID: " + id);
                            sendJavaBroadcast("✅ 成功获取系统Surface! 通道打通!");
                            
                            // 这里我们其实不需要往这个 Surface 画东西，
                            // 因为我们会用 TYPE_APPLICATION_OVERLAY 直接覆盖在上面。
                            // 只要不报错，系统就会以为高德正常工作，从而保持 GUIDE 状态。
                        }
                    } else if (code == 4) { // w() isReady
                         XposedBridge.log("NaviHook: 系统询问是否就绪 (isReady)");
                         reply.writeNoException();
                         reply.writeInt(1); // true
                         return true;
                    }
                } catch (Throwable e) {
                    // 忽略所有错误，防止崩溃
                    // XposedBridge.log("NaviHook: Binder transact error: " + e);
                }
                
                return true; // 永远返回成功，骗过系统
            }
        };
        XposedBridge.log("NaviHook: 🎭 伪造 Binder 已创建");
    }

    // 🟢 核心：劫持 bindService
    private void hookBindService(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", cl, "bindService",
                Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                ServiceConnection conn = (ServiceConnection) param.args[1];
                
                if (intent != null && intent.getComponent() != null) {
                    String className = intent.getComponent().getClassName();
                    String pkgName = intent.getComponent().getPackageName();
                    
                    // 判断是否是连接高德投屏服务
                    if (className.contains("AutoSimilarWidgetViewService") || 
                        (pkgName.equals(PKG_MAP) && className.contains("Widget"))) {
                        
                        XposedBridge.log("NaviHook: 🚨 拦截到高德连接请求: " + className);
                        sendJavaBroadcast("⚡ 拦截连接 -> " + className);
                        
                        // 1. 阻止原方法执行
                        param.setResult(true); 
                        
                        // 2. 手动触发连接成功回调，注入我们的 Fake Binder
                        if (conn != null && fakeServiceBinder != null) {
                            // 必须在主线程回调
                            if (mainHandler != null) {
                                mainHandler.post(() -> {
                                    try {
                                        ComponentName cn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
                                        conn.onServiceConnected(cn, fakeServiceBinder);
                                        XposedBridge.log("NaviHook: ✅ 手动回调 onServiceConnected 完成劫持");
                                        sendJavaBroadcast("✅ 劫持成功: 虚拟服务已连接");
                                    } catch (Throwable t) {
                                        XposedBridge.log("NaviHook: 回调失败: " + t);
                                    }
                                });
                            }
                        }
                    }
                }
            }
        });
        } catch (Throwable t) {
             XposedBridge.log("NaviHook: Hook bindService 失败: " + t);
        }
    }

    // 🟢 核心：创建悬浮窗
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
                
                // 寻找副屏
                for (Display d : displays) {
                    XposedBridge.log("NaviHook: 发现屏幕 ID=" + d.getDisplayId() + " Name=" + d.getName());
                    if (d.getDisplayId() != 0) { // 通常 0 是主屏
                        targetDisplay = d;
                        // 不 break，继续看，或者根据名字 "Cluster" 过滤
                        // break; 
                    }
                }
                
                if (targetDisplay == null) {
                    sendJavaBroadcast("❌ 未找到仪表屏幕!");
                    return;
                }
                
                XposedBridge.log("NaviHook: 🎯 将在屏幕 ID=" + targetDisplay.getDisplayId() + " 上创建窗口");

                // 获取对应屏幕的 Context
                Context displayContext = systemContext.createDisplayContext(targetDisplay);
                
                clusterWindow = new Presentation(displayContext, targetDisplay) {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        
                        // 这里是你可以自定义布局的地方
                        // 暂时放一个最简单的TextView测试
                        TextView tv = new TextView(getContext());
                        tv.setText("V136 劫持成功\n高德地图 9.1");
                        tv.setTextSize(40);
                        tv.setTextColor(Color.WHITE);
                        tv.setGravity(Gravity.CENTER);
                        tv.setBackgroundColor(Color.parseColor("#000000")); // 纯黑背景
                        
                        setContentView(tv);
                    }
                };

                // 设置为系统悬浮窗类型
                clusterWindow.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                
                // 关键 Flags
                clusterWindow.getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | 
                    WindowManager.LayoutParams.FLAG_FULLSCREEN | 
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                );

                clusterWindow.show();
                
                sendJavaBroadcast("✅ 投屏窗口已创建 (Type:2038)");
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: 窗口创建失败: " + t);
                sendJavaBroadcast("❌ 窗口失败: " + t.getMessage());
            }
        });
    }

    private void destroyOverlayWindow() {
        mainHandler.post(() -> {
            try {
                if (clusterWindow != null) {
                    clusterWindow.dismiss();
                    clusterWindow = null;
                    sendJavaBroadcast("🛑 投屏已关闭");
                }
            } catch (Throwable t) {}
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
                // i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND); // 部分系统需要
                
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