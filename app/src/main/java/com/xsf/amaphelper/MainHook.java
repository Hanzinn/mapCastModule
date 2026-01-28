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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
    private static final String PKG_MAP = "com.autonavi.amapauto";

    // 🎯 核心目标类
    private static final String CLASS_AMAP_AIDL_MANAGER = "ecarx.naviservice.map.amap.h";
    private static final String CLASS_MAP_MANAGER = "ecarx.naviservice.map.cf";
    
    // 🎯 真正的 Service 类名 (精准匹配)
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    
    // Binder 描述符
    private static final String DESCRIPTOR = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final int INTERFACE_TRANSACTION = 1598968902;
    
    // Transaction Codes
    private static final int TRANSACTION_addSurface = 1;
    private static final int TRANSACTION_removedSurface = 2;
    private static final int TRANSACTION_isMapRunning = 3;
    private static final int TRANSACTION_setWidgetStateControl = 4;
    private static final int TRANSACTION_dispatchTouchEvent = 5;

    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Binder fakeServiceBinder = null;
    private static boolean isReceiverRegistered = false;
    
    // 保存回调
    private static IBinder mProviderCallback = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V140 协议完美对齐版启动");

        // 1. 获取 Context 并设置自动注入
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                
                initFakeBinder(); 
                registerReceiver(systemContext, lpparam.classLoader);
                
                sendJavaBroadcast("⚡ V140 就绪 (Application)");
                
                // ⏰ 自动起搏器：2秒后自动尝试注入，不等用户操作，防止错过时机
                mainHandler.postDelayed(() -> {
                    sendJavaBroadcast("⏰ 自动执行主动注入...");
                    performActiveInjection(lpparam.classLoader);
                }, 2000);
            }
        });

        // 2. 强制 MapVendor = 0
        try {
            Class<?> managerClass = XposedHelpers.findClassIfExists(CLASS_MAP_MANAGER, lpparam.classLoader);
            if (managerClass != null) {
                XposedHelpers.findAndHookMethod(managerClass, "c", XC_MethodReplacement.returnConstant(0));
                XposedBridge.log("NaviHook: 🔓 强制 Vendor=0");
            }
        } catch (Throwable t) {}
        
        // 3. 拦截 bindService (使用 BootClassLoader 确保拦截 ContextWrapper)
        hookBindService(lpparam.classLoader);
    }

    // 🟢 核心：精准对齐的 Binder 实现
    private void initFakeBinder() {
        if (fakeServiceBinder != null) return;
        
        fakeServiceBinder = new Binder() {
            @Override
            public String getInterfaceDescriptor() {
                return DESCRIPTOR;
            }

            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                try {
                    // 1) 处理 INTERFACE_TRANSACTION (重要!)
                    if (code == INTERFACE_TRANSACTION) {
                        if (reply != null) reply.writeString(DESCRIPTOR);
                        return true;
                    }
                    
                    // 2) 校验 Token
                    data.enforceInterface(DESCRIPTOR);

                    switch (code) {
                        case TRANSACTION_setWidgetStateControl: { // 4
                            // ★ 必须把 Binder 读出来，即使还没用，也要清空 Parcel 缓冲区
                            IBinder provider = data.readStrongBinder(); 
                            mProviderCallback = provider;
                            
                            // 📝 里程碑 A
                            sendJavaBroadcast("✅ setWidgetStateControl ok provider=" + (provider != null));
                            
                            if (reply != null) reply.writeNoException();
                            
                            // 暂时注释，等待拿到 IAutoWidgetStateProvider Stub 后再开
                            // notifyFrameDrawn(); 
                            return true;
                        }
                        
                        case TRANSACTION_addSurface: { // 1
                            // ★ 严格按照协议读取：先读 int(hasSurface)，再读 Surface，最后读 int(id)
                            Surface surface = null;
                            int hasSurface = data.readInt();
                            if (hasSurface != 0) {
                                surface = Surface.CREATOR.createFromParcel(data);
                            }
                            int id = data.readInt(); // ★ 必须读，不可遗漏！

                            // 📝 里程碑 B
                            sendJavaBroadcast("✅ addSurface: surface=" + (surface != null) + " id=" + id);
                            XposedBridge.log("NaviHook: addSurface surface=" + surface + " id=" + id);

                            if (reply != null) reply.writeNoException();
                            
                            // 🎨 尝试绘制 (里程碑 C 的前置)
                            if (surface != null) {
                                drawOnSurface(surface);
                            }
                            return true;
                        }

                        case TRANSACTION_removedSurface: { // 2
                            // ★ 同样严格读取
                            int hasSurface = data.readInt();
                            if (hasSurface != 0) {
                                Surface.CREATOR.createFromParcel(data); // 读出来丢掉
                            }
                            int id = data.readInt(); // 必须读

                            if (reply != null) reply.writeNoException();
                            sendJavaBroadcast("🧹 removedSurface id=" + id);
                            return true;
                        }

                        case TRANSACTION_isMapRunning: { // 3
                            if (reply != null) {
                                reply.writeNoException();
                                reply.writeInt(1); // true
                            }
                            return true;
                        }

                        case TRANSACTION_dispatchTouchEvent: { // 5
                            // ★ 严格读取 MotionEvent
                            int hasEvent = data.readInt();
                            if (hasEvent != 0) {
                                android.view.MotionEvent.CREATOR.createFromParcel(data); // 读出来丢掉
                            }
                            if (reply != null) reply.writeNoException();
                            return true;
                        }
                    }
                } catch (Throwable e) {
                    XposedBridge.log("NaviHook: Binder Error: " + e);
                    sendJavaBroadcast("❌ Binder异常: " + e.getClass().getSimpleName() + ":" + e.getMessage());
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        XposedBridge.log("NaviHook: 🎭 伪造 Binder V140 已创建");
    }
    
    // 🎨 测试绘制：在 Surface 上画红色
    private void drawOnSurface(Surface surface) {
        if (surface == null || !surface.isValid()) return;
        new Thread(() -> {
            try {
                sendJavaBroadcast("🖌️ 尝试 LockCanvas 绘制...");
                Canvas canvas = null;
                try {
                    canvas = surface.lockCanvas(null);
                } catch (IllegalArgumentException | OutOfMemoryError e) {
                    // 📝 里程碑 C (失败分支)
                    sendJavaBroadcast("⚠️ lockCanvas 失败 (需要 EGL): " + e.getMessage());
                    return;
                }

                if (canvas != null) {
                    canvas.drawColor(Color.RED); // 画大红屏
                    Paint paint = new Paint();
                    paint.setColor(Color.WHITE);
                    paint.setTextSize(60);
                    paint.setFakeBoldText(true);
                    canvas.drawText("V140 通路打通!", 50, 100, paint);
                    canvas.drawText("等待 EGL 注入...", 50, 200, paint);
                    surface.unlockCanvasAndPost(canvas);
                    // 📝 里程碑 C (成功分支)
                    sendJavaBroadcast("✅ 绘制成功！请看仪表盘是否变红！");
                }
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 绘制线程异常: " + t.getMessage());
                XposedBridge.log(t);
            }
        }).start();
    }
    
    // 主动注入逻辑
    private void performActiveInjection(ClassLoader cl) {
        mainHandler.post(() -> {
            try {
                // sendJavaBroadcast("💉 执行主动注入...");
                Class<?> hClass = XposedHelpers.findClass(CLASS_AMAP_AIDL_MANAGER, cl);
                Object hInstance = XposedHelpers.getStaticObjectField(hClass, "e"); 
                
                if (hInstance == null) {
                    sendJavaBroadcast("❌ 注入失败: h单例为空");
                    return;
                }
                
                Object connection = XposedHelpers.getObjectField(hInstance, "f");
                if (connection instanceof ServiceConnection) {
                    ComponentName fakeCn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
                    
                    // 🔥 暴力调用
                    ((ServiceConnection) connection).onServiceConnected(fakeCn, fakeServiceBinder);
                    
                    sendJavaBroadcast("✅ onServiceConnected injected");
                }
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 注入异常: " + t.getMessage());
            }
        });
    }
    
    // 拦截 bindService (使用 null ClassLoader 以匹配 ContextWrapper)
    private void hookBindService(ClassLoader appClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", null, "bindService",
                Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null) {
                    String className = intent.getComponent().getClassName();
                    
                    // 🔥 精准匹配目标 Service 类名
                    if (TARGET_SERVICE_IMPL.equals(className)) {
                        XposedBridge.log("NaviHook: 🚨 拦截连接 -> " + className);
                        sendJavaBroadcast("✅ hijack bindService hit");
                        
                        param.setResult(true); // 阻止系统真实调用
                        
                        ServiceConnection conn = (ServiceConnection) param.args[1];
                        if (conn != null && fakeServiceBinder != null) {
                             mainHandler.post(() -> {
                                 try {
                                     ComponentName cn = new ComponentName(PKG_MAP, className);
                                     conn.onServiceConnected(cn, fakeServiceBinder);
                                     sendJavaBroadcast("✅ 劫持回调成功");
                                 } catch (Throwable t) {
                                     XposedBridge.log("Cb err: " + t);
                                 }
                             });
                        }
                    }
                }
            }
        });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Hook bindService error: " + t);
        }
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        if (isReceiverRegistered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if ("XSF_ACTION_START_CAST".equals(intent.getAction())) {
                    performActiveInjection(cl);
                } else if ("XSF_ACTION_STOP_CAST".equals(intent.getAction())) {
                    // 保留关闭逻辑
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("XSF_ACTION_START_CAST");
        filter.addAction("XSF_ACTION_STOP_CAST");
        context.registerReceiver(receiver, filter);
        isReceiverRegistered = true;
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