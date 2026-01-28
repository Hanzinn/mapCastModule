package com.xsf.amaphelper;

import android.app.Application;
import android.app.Presentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

    // 核心类与接口定义
    private static final String CLASS_MAP_MANAGER = "ecarx.naviservice.map.cf";
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    private static final String DESCRIPTOR_SERVICE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String DESCRIPTOR_PROVIDER = "com.autosimilarwidget.view.IAutoWidgetStateProvider"; // 盲猜的接口名
    
    // Transaction Codes
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
    
    // 状态灯反馈
    private static boolean hasHooked = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🚀 V141 握手闭环版启动");

        // 1. 获取 Context
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder(); 
                registerReceiver(systemContext);
                sendJavaBroadcast("⚡ V141 就绪 (Waiting for connect)");
            }
        });

        // 2. 强制 Vendor=0
        try {
            Class<?> managerClass = XposedHelpers.findClassIfExists(CLASS_MAP_MANAGER, lpparam.classLoader);
            if (managerClass != null) {
                XposedHelpers.findAndHookMethod(managerClass, "c", XC_MethodReplacement.returnConstant(0));
            }
        } catch (Throwable t) {}
        
        // 3. 拦截 bindService (核心入口)
        hookBindService(lpparam.classLoader);
    }

    // 🟢 核心：Fake Binder (带反向回调)
    private void initFakeBinder() {
        if (fakeServiceBinder != null) return;
        
        fakeServiceBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                try {
                    if (code == 1598968902) { // INTERFACE_TRANSACTION
                        if (reply != null) reply.writeString(DESCRIPTOR_SERVICE);
                        return true;
                    }
                    
                    data.enforceInterface(DESCRIPTOR_SERVICE);

                    switch (code) {
                        case TRANSACTION_setWidgetStateControl: { // 4
                            // 🔥 关键点：读取系统传过来的回调接口
                            IBinder provider = data.readStrongBinder(); 
                            if (reply != null) reply.writeNoException();
                            
                            sendJavaBroadcast("✅ 握手成功! (Step 1/2)");
                            XposedBridge.log("NaviHook: 收到 Provider, 准备反向调用...");
                            
                            // 🔥🔥🔥 立即回调，告诉系统“我好了”
                            if (provider != null) {
                                notifyProvider(provider);
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
                            
                            sendJavaBroadcast("✅✅✅ 收到 Surface! (Step 2/2)");
                            sendJavaBroadcast("ID: " + id);
                            
                            // 只要走到这一步，我们就赢了！
                            // 尝试在 Surface 上画个红底，或者直接起悬浮窗
                            if (surface != null) {
                                drawRedScreen(surface);
                            }
                            // 同时启动悬浮窗作为双重保障
                            createOverlayWindow(); 
                            return true;
                        }

                        case TRANSACTION_isMapRunning: { // 3
                            if (reply != null) {
                                reply.writeNoException();
                                reply.writeInt(1); // true
                            }
                            return true;
                        }
                        
                        // 其他不需要处理，只要读完 Parcel 即可
                        case TRANSACTION_removedSurface: 
                        case TRANSACTION_dispatchTouchEvent:
                            // 简单读完 buffer 防止报错
                            if (data.dataAvail() > 0) data.readInt(); 
                            if (data.dataAvail() > 0) data.readInt(); // 多读几次无妨
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
    
    // 🔔 反向通知逻辑 (盲打 Transaction 1, 2, 3)
    private void notifyProvider(IBinder provider) {
        new Thread(() -> {
            try {
                // 模拟一点点延时
                Thread.sleep(200);
                
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                
                try {
                    // 尝试 Transaction 1 (通常是 onWidgetFirstFrameDrawn 或 onStateChanged)
                    // 注意：这里 Interface Token 可能不准，如果系统校验 Token 可能会失败
                    // 但我们先试 com.autosimilarwidget.view.IAutoWidgetStateProvider
                    data.writeInterfaceToken(DESCRIPTOR_PROVIDER);
                    
                    // 有些接口需要传 int (比如 0 或 1)
                    // 我们先试无参调用
                    sendJavaBroadcast("📣 尝试反向回调 Transact 1...");
                    provider.transact(1, data, reply, 0);
                    reply.readException();
                    sendJavaBroadcast("✅ 回调 Transact 1 成功!");
                } catch (Exception e) {
                    XposedBridge.log("NaviHook: Callback 1 failed: " + e);
                    // 如果失败，尝试带参数的 (例如 surfaceID 或 state)
                    // data.writeInt(1); ...
                } finally {
                    data.recycle();
                    reply.recycle();
                }
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: Provider thread error: " + t);
            }
        }).start();
    }
    
    // 绘制测试 (画红屏)
    private void drawRedScreen(Surface surface) {
        if (!surface.isValid()) return;
        new Thread(() -> {
            try {
                Canvas c = surface.lockCanvas(null);
                if (c != null) {
                    c.drawColor(Color.RED);
                    Paint p = new Paint();
                    p.setColor(Color.WHITE); 
                    p.setTextSize(60); 
                    c.drawText("V141 破解成功", 50, 200, p);
                    surface.unlockCanvasAndPost(c);
                }
            } catch (Exception e) {
                sendJavaBroadcast("⚠️ EGL Surface (Canvas不可用)");
            }
        }).start();
    }

    // 拦截 bindService
    private void hookBindService(ClassLoader appClassLoader) {
        try {
            // 使用 null ClassLoader 以匹配系统类
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", null, "bindService",
                Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null) {
                    String className = intent.getComponent().getClassName();
                    
                    if (TARGET_SERVICE_IMPL.equals(className)) {
                        XposedBridge.log("NaviHook: 🚨 拦截 -> " + className);
                        sendJavaBroadcast("🚨 拦截连接请求 (Service劫持)");
                        param.setResult(true); 
                        
                        ServiceConnection conn = (ServiceConnection) param.args[1];
                        if (conn != null && fakeServiceBinder != null) {
                             mainHandler.post(() -> {
                                 try {
                                     ComponentName cn = new ComponentName(PKG_MAP, className);
                                     conn.onServiceConnected(cn, fakeServiceBinder);
                                     sendJavaBroadcast("✅ 劫持成功! 等待握手...");
                                 } catch (Throwable t) {}
                             });
                        }
                    }
                }
            }
        });
        } catch (Throwable t) {}
    }
    
    // 悬浮窗 (作为 addSurface 拿不到时的备用方案)
    private void createOverlayWindow() {
        if (systemContext == null || clusterWindow != null) return;
        mainHandler.post(() -> {
            try {
                DisplayManager dm = (DisplayManager) systemContext.getSystemService(Context.DISPLAY_SERVICE);
                Display targetDisplay = null;
                for (Display d : dm.getDisplays()) {
                    if (d.getDisplayId() != 0) { targetDisplay = d; break; }
                }
                if (targetDisplay == null) return;

                Context displayContext = systemContext.createDisplayContext(targetDisplay);
                clusterWindow = new Presentation(displayContext, targetDisplay) {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        TextView tv = new TextView(getContext());
                        tv.setText("V141 强行显示");
                        tv.setTextColor(Color.GREEN);
                        tv.setTextSize(50);
                        tv.setGravity(Gravity.CENTER);
                        tv.setBackgroundColor(Color.BLACK);
                        setContentView(tv);
                    }
                };
                clusterWindow.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                clusterWindow.show();
            } catch (Throwable t) {}
        });
    }

    private void registerReceiver(Context context) {
        if (isReceiverRegistered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                // 这里可以加手动重置逻辑
            }
        };
        IntentFilter filter = new IntentFilter("XSF_ACTION_RESET");
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