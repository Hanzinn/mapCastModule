package com.xsf.amaphelper;

import android.app.Application;
import android.app.Presentation;
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
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    // 🎯 核心目标类：高德AIDL管理器 (根据你的smali分析)
    private static final String CLASS_AMAP_AIDL_MANAGER = "ecarx.naviservice.map.amap.h";
    // 🎯 核心目标类：地图配置管理器 (用于强制切换Vendor)
    private static final String CLASS_MAP_MANAGER = "ecarx.naviservice.map.cf";
    
    private static final String TARGET_AIDL_INTERFACE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    
    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Presentation clusterWindow = null;
    private static Binder fakeServiceBinder = null;
    private static boolean isReceiverRegistered = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V137 主动注入版启动");

        // 1. 获取 Context
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder();
                registerReceiver(systemContext, lpparam.classLoader);
                sendJavaBroadcast("⚡ V137就绪，等待注入指令...");
            }
        });

        // 2. 修正：强制 MapVendor 为 0 (高德)
        // 之前hook ce.b 报错，这次改 hook cf.c() (根据 smali: cf.g -> return g)
        try {
            Class<?> managerClass = XposedHelpers.findClassIfExists(CLASS_MAP_MANAGER, lpparam.classLoader);
            if (managerClass != null) {
                // c() 方法通常返回当前地图类型
                XposedHelpers.findAndHookMethod(managerClass, "c", XC_MethodReplacement.returnConstant(0));
                XposedBridge.log("NaviHook: 🔓 强制 Vendor=0 (AMAP)");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Vendor Hook Error: " + t);
        }
        
        // 3. 仍然保留 bindService 拦截作为双重保险
        hookBindService(lpparam.classLoader);
    }

    // 🟢 核心大招：主动找到 ServiceConnection 并注入
    private void performActiveInjection(ClassLoader cl) {
        mainHandler.post(() -> {
            try {
                sendJavaBroadcast("💉 开始主动注入...");
                
                // 1. 获取 h 类的单例 (static volatile e)
                Class<?> hClass = XposedHelpers.findClass(CLASS_AMAP_AIDL_MANAGER, cl);
                Object hInstance = XposedHelpers.getStaticObjectField(hClass, "e");
                
                if (hInstance == null) {
                    sendJavaBroadcast("❌ 注入失败: AmapManager(h) 单例为空! 系统可能未初始化");
                    return;
                }
                
                // 2. 获取内部的 ServiceConnection (field f)
                Object connection = XposedHelpers.getObjectField(hInstance, "f");
                
                if (connection instanceof ServiceConnection) {
                    ServiceConnection conn = (ServiceConnection) connection;
                    
                    // 3. 伪造组件名
                    ComponentName fakeCn = new ComponentName("com.autonavi.amapauto", "com.autosimilarwidget.view.AutoSimilarWidgetViewService");
                    
                    // 4. 🔥 暴力调用 onServiceConnected
                    conn.onServiceConnected(fakeCn, fakeServiceBinder);
                    
                    sendJavaBroadcast("✅✅✅ 注入成功！已强制触发 onServiceConnected");
                    XposedBridge.log("NaviHook: Active injection success!");
                    
                    // 5. 顺便把 Presentation 也开了
                    createOverlayWindow();
                    
                } else {
                    sendJavaBroadcast("❌ 注入失败: 未找到 ServiceConnection (field f)");
                }
                
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 注入崩溃: " + t.getMessage());
                XposedBridge.log(t);
            }
        });
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        if (isReceiverRegistered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if ("XSF_ACTION_START_CAST".equals(action)) {
                    // 点击开启时，执行【主动注入】
                    performActiveInjection(cl);
                } else if ("XSF_ACTION_STOP_CAST".equals(action)) {
                    destroyOverlayWindow();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("XSF_ACTION_START_CAST");
        filter.addAction("XSF_ACTION_STOP_CAST");
        context.registerReceiver(receiver, filter);
        isReceiverRegistered = true;
    }

    // 伪造 Binder
    private void initFakeBinder() {
        if (fakeServiceBinder != null) return;
        fakeServiceBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                // 简单粗暴，不管什么请求全部通过
                if (reply != null) {
                    reply.writeNoException();
                    if (code == 4) reply.writeInt(1); // isReady = true
                }
                return true; 
            }
        };
    }

    // 备用：bindService 拦截
    private void hookBindService(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", cl, "bindService",
                Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null) {
                    String className = intent.getComponent().getClassName();
                    if (className.contains("AutoSimilarWidgetViewService")) {
                        XposedBridge.log("NaviHook: 🚨 系统尝试连接高德，允许通过但会被我们截胡");
                        // 这里不需要做什么，因为我们已经有主动注入了。
                        // 如果系统真的发起了，我们也可以在这里直接回调，构成双保险。
                        ServiceConnection conn = (ServiceConnection) param.args[1];
                        if (conn != null && fakeServiceBinder != null) {
                             // 异步回调，防止阻塞
                             new Handler(Looper.getMainLooper()).post(() -> {
                                 try {
                                     conn.onServiceConnected(intent.getComponent(), fakeServiceBinder);
                                     sendJavaBroadcast("♻️ 被动劫持成功");
                                 } catch (Exception e) {}
                             });
                             param.setResult(true); // 阻止系统真实调用
                        }
                    }
                }
            }
        });
        } catch (Throwable t) {}
    }

    // 创建悬浮窗 (无录屏)
    private void createOverlayWindow() {
        if (systemContext == null) return;
        
        mainHandler.post(() -> {
            try {
                if (clusterWindow != null) { clusterWindow.dismiss(); clusterWindow = null; }

                DisplayManager dm = (DisplayManager) systemContext.getSystemService(Context.DISPLAY_SERVICE);
                Display[] displays = dm.getDisplays();
                Display targetDisplay = null;
                for (Display d : displays) {
                    if (d.getDisplayId() != 0) { targetDisplay = d; break; }
                }
                
                if (targetDisplay == null) {
                    sendJavaBroadcast("❌ 无副屏");
                    return;
                }

                Context displayContext = systemContext.createDisplayContext(targetDisplay);
                clusterWindow = new Presentation(displayContext, targetDisplay) {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        TextView tv = new TextView(getContext());
                        tv.setText("V137 注入成功\n画面测试");
                        tv.setTextColor(Color.WHITE);
                        tv.setTextSize(50);
                        tv.setGravity(Gravity.CENTER);
                        tv.setBackgroundColor(Color.BLUE); 
                        setContentView(tv);
                    }
                };
                
                clusterWindow.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                clusterWindow.getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | 
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | 
                    WindowManager.LayoutParams.FLAG_FULLSCREEN | 
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                );
                clusterWindow.show();
                sendJavaBroadcast("✅ 窗口已显示");
                
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 窗口失败: " + t.getMessage());
            }
        });
    }

    private void destroyOverlayWindow() {
        mainHandler.post(() -> {
            if (clusterWindow != null) {
                try { clusterWindow.dismiss(); clusterWindow = null; sendJavaBroadcast("🛑 投屏关闭"); } catch (Exception e) {}
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
