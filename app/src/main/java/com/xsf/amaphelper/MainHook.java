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

    // 核心类
    private static final String CLASS_AMAP_AIDL_MANAGER = "ecarx.naviservice.map.amap.h";
    private static final String CLASS_MAP_MANAGER = "ecarx.naviservice.map.cf";
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    
    // 描述符 (严格匹配反编译结果)
    private static final String DESCRIPTOR_SERVICE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String DESCRIPTOR_PROVIDER = "com.autosimilarwidget.view.IAutoWidgetStateProvider";
    
    // Transactions
    private static final int TRANSACTION_addSurface = 1;
    private static final int TRANSACTION_removedSurface = 2;
    private static final int TRANSACTION_isMapRunning = 3;
    private static final int TRANSACTION_setWidgetStateControl = 4;
    private static final int TRANSACTION_dispatchTouchEvent = 5;

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

        XposedBridge.log("NaviHook: 🚀 V144 绝杀版启动");

        // 1. 获取 Context
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder();
                registerReceiver(systemContext, lpparam.classLoader);
                
                sendJavaBroadcast("⚡ V144 就绪");
                
                // 3秒后自动触发一次注入 (防止用户手慢)
                mainHandler.postDelayed(() -> performActiveInjection(lpparam.classLoader), 3000);
            }
        });

        // 2. 强制 Vendor=0
        try {
            Class<?> managerClass = XposedHelpers.findClassIfExists(CLASS_MAP_MANAGER, lpparam.classLoader);
            if (managerClass != null) {
                XposedHelpers.findAndHookMethod(managerClass, "c", XC_MethodReplacement.returnConstant(0));
            }
        } catch (Throwable t) {}
        
        // 3. 拦截 bindService
        hookBindService(lpparam.classLoader);
    }

    private void initFakeBinder() {
        if (fakeServiceBinder != null) return;
        
        fakeServiceBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                try {
                    // INTERFACE Token Check
                    if (code == 1598968902) { 
                        if (reply != null) reply.writeString(DESCRIPTOR_SERVICE);
                        return true;
                    }
                    
                    data.enforceInterface(DESCRIPTOR_SERVICE);

                    switch (code) {
                        case TRANSACTION_setWidgetStateControl: { // 4
                            IBinder provider = data.readStrongBinder(); 
                            if (reply != null) reply.writeNoException();
                            
                            sendJavaBroadcast("✅ 收到控制接口 (Provider)");
                            XposedBridge.log("NaviHook: Got Provider: " + provider);
                            
                            // 🔥 核心：立即触发“第一帧绘制”信号
                            if (provider != null) {
                                triggerFirstFrameDrawn(provider);
                            }
                            return true;
                        }
                        
                        case TRANSACTION_addSurface: { // 1
                            Surface surface = null;
                            if (data.readInt() != 0) {
                                surface = Surface.CREATOR.createFromParcel(data);
                            }
                            int id = data.readInt();
                            if (reply != null) reply.writeNoException();
                            
                            sendJavaBroadcast("🎯🎯🎯 收到 Surface! ID=" + id);
                            XposedBridge.log("NaviHook: ADD SURFACE SUCCESS: " + surface);
                            
                            if (surface != null) {
                                drawOnSurface(surface);
                            }
                            return true;
                        }

                        case TRANSACTION_isMapRunning: { // 3
                            sendJavaBroadcast("ℹ️ 系统询问: isMapRunning? -> YES");
                            if (reply != null) {
                                reply.writeNoException();
                                reply.writeInt(1); // true
                            }
                            return true;
                        }
                        
                        case TRANSACTION_removedSurface: // 2
                        case TRANSACTION_dispatchTouchEvent: // 5
                             if (data.dataAvail() > 0) data.readInt(); // consume
                             if (data.dataAvail() > 0) data.readInt();
                             if (reply != null) reply.writeNoException();
                            return true;
                    }
                } catch (Throwable e) {
                    XposedBridge.log("NaviHook: Binder Error: " + e);
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
    }
    
    // 🔥 触发系统状态机翻转
    private void triggerFirstFrameDrawn(IBinder provider) {
        new Thread(() -> {
            try {
                Thread.sleep(100); // 极短延时，模拟真实绘制耗时
                
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                
                try {
                    // 对应 IAutoWidgetStateProvider.onWidgetFirstFrameDrawn()
                    // 签名: ()V
                    data.writeInterfaceToken(DESCRIPTOR_PROVIDER);
                    
                    // Transaction Code = 1 (onWidgetFirstFrameDrawn)
                    sendJavaBroadcast("📣 发送 FrameDrawn 信号...");
                    provider.transact(1, data, reply, 0); 
                    reply.readException();
                    
                    sendJavaBroadcast("✅ 信号发送成功! 等待 Surface...");
                } catch (Throwable e) {
                    XposedBridge.log("NaviHook: Callback Failed: " + e);
                    sendJavaBroadcast("❌ 信号发送失败: " + e.getMessage());
                } finally {
                    data.recycle();
                    reply.recycle();
                }
                
            } catch (Throwable t) {}
        }).start();
    }
    
    // 🎨 尝试绘制 (红底白字)
    private void drawOnSurface(Surface surface) {
        if (!surface.isValid()) return;
        new Thread(() -> {
            try {
                // 方案A: Canvas绘制
                Canvas c = surface.lockCanvas(null);
                if (c != null) {
                    c.drawColor(Color.RED);
                    Paint p = new Paint();
                    p.setColor(Color.WHITE); 
                    p.setTextSize(60); 
                    p.setFakeBoldText(true);
                    c.drawText("V144 攻破", 100, 200, p);
                    surface.unlockCanvasAndPost(c);
                    sendJavaBroadcast("🎨 Canvas绘制完成 (看仪表)");
                }
            } catch (IllegalArgumentException e) {
                // 方案B: 如果Canvas失败，说明是EGL Surface，这里暂不处理，
                // 但只要走到这里，说明通路已经打通，后面接EGL代码即可。
                sendJavaBroadcast("⚠️ Surface类型为EGL (需要OpenGL)");
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: Draw Error: " + t);
            }
        }).start();
    }

    // 主动注入 (心脏起搏器)
    private void performActiveInjection(ClassLoader cl) {
        mainHandler.post(() -> {
            try {
                Class<?> hClass = XposedHelpers.findClass(CLASS_AMAP_AIDL_MANAGER, cl);
                Object hInstance = XposedHelpers.getStaticObjectField(hClass, "e"); 
                if (hInstance == null) return;
                
                Object connection = XposedHelpers.getObjectField(hInstance, "f");
                if (connection instanceof ServiceConnection) {
                    // 必须伪造正确的 ComponentName
                    ComponentName fakeCn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
                    ((ServiceConnection) connection).onServiceConnected(fakeCn, fakeServiceBinder);
                    sendJavaBroadcast("💉 主动注入完成");
                }
            } catch (Throwable t) {
                 sendJavaBroadcast("注入失败: " + t.getMessage());
            }
        });
    }

    // 拦截 bindService
    private void hookBindService(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", null, "bindService",
                Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null) {
                    String className = intent.getComponent().getClassName();
                    if (TARGET_SERVICE_IMPL.equals(className)) {
                        sendJavaBroadcast("🚨 拦截连接请求");
                        param.setResult(true); 
                        ServiceConnection conn = (ServiceConnection) param.args[1];
                        if (conn != null && fakeServiceBinder != null) {
                             mainHandler.post(() -> {
                                 try {
                                     ComponentName cn = new ComponentName(PKG_MAP, className);
                                     conn.onServiceConnected(cn, fakeServiceBinder);
                                 } catch (Throwable t) {}
                             });
                        }
                    }
                }
            }
        });
        } catch (Throwable t) {}
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        if (isReceiverRegistered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if ("XSF_ACTION_START_CAST".equals(intent.getAction())) {
                    performActiveInjection(cl);
                }
            }
        };
        IntentFilter filter = new IntentFilter("XSF_ACTION_START_CAST");
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