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

    private static final String CLASS_MAP_MANAGER = "ecarx.naviservice.map.cf";
    private static final String CLASS_AMAP_AIDL_MANAGER = "ecarx.naviservice.map.amap.h";
    
    // 🔥 100% 确定的类名和接口
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    private static final String DESCRIPTOR_SERVICE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String DESCRIPTOR_PROVIDER = "com.autosimilarwidget.view.IAutoWidgetStateProvider";
    
    // Transaction Codes (基于你的反编译文件)
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

        XposedBridge.log("NaviHook: 🚀 V143 精准回调版启动");

        // 1. 获取 Context
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder(); 
                registerReceiver(systemContext, lpparam.classLoader);
                sendJavaBroadcast("⚡ V143 就绪 (Context OK)");
                
                // 自动执行一次注入
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
                    // 处理 Interface Token 请求
                    if (code == 1598968902) { 
                        if (reply != null) reply.writeString(DESCRIPTOR_SERVICE);
                        return true;
                    }
                    
                    data.enforceInterface(DESCRIPTOR_SERVICE);

                    switch (code) {
                        case TRANSACTION_setWidgetStateControl: { // 4
                            // 1. 读出回调接口
                            IBinder provider = data.readStrongBinder(); 
                            if (reply != null) reply.writeNoException();
                            
                            sendJavaBroadcast("✅ 握手第一步: 收到 Provider");
                            
                            // 2. 🔥 立即执行反向回调
                            if (provider != null) {
                                notifyFrameDrawn(provider);
                            }
                            return true;
                        }
                        
                        case TRANSACTION_addSurface: { // 1
                            Surface surface = null;
                            int has = data.readInt();
                            if (has != 0) {
                                surface = Surface.CREATOR.createFromParcel(data);
                            }
                            int id = data.readInt(); // 必须读

                            if (reply != null) reply.writeNoException();
                            
                            // 3. 🔥 成功拿到 Surface
                            sendJavaBroadcast("🎯🎯🎯 收到 addSurface! ID=" + id);
                            
                            if (surface != null) {
                                drawRedScreen(surface);
                            }
                            // 双重保险：同时创建悬浮窗
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
                        
                        case TRANSACTION_removedSurface: // 2
                        case TRANSACTION_dispatchTouchEvent: // 5
                            // 简单读完参数
                            if (data.dataAvail() > 0) data.readInt();
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
    
    // 🔔 精准反向通知: onWidgetFirstFrameDrawn()
    private void notifyFrameDrawn(IBinder provider) {
        new Thread(() -> {
            try {
                // 模拟一点点处理耗时 (比如 50ms)，太快可能系统还没准备好
                Thread.sleep(50);
                
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                
                try {
                    // 1. 写入 Provider 的 Token
                    data.writeInterfaceToken(DESCRIPTOR_PROVIDER);
                    // 2. 无参数! (方法是 void onWidgetFirstFrameDrawn())
                    // 不要 writeInt
                    
                    sendJavaBroadcast("📣 发送 onWidgetFirstFrameDrawn...");
                    
                    // 3. Transact Code = 1 (只有一个方法)
                    provider.transact(1, data, reply, 0); // 0 = SYNC 调用
                    
                    reply.readException(); // 检查是否有异常
                    sendJavaBroadcast("✅ 回调成功! 等待 addSurface...");
                    
                } catch (Exception e) {
                    XposedBridge.log("NaviHook: Callback failed: " + e);
                    sendJavaBroadcast("❌ 回调失败: " + e.getMessage());
                } finally {
                    data.recycle();
                    reply.recycle();
                }
                
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: Thread error: " + t);
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
                    c.drawText("V143 成功", 50, 200, p);
                    surface.unlockCanvasAndPost(c);
                    sendJavaBroadcast("🎨 Canvas绘制完成");
                }
            } catch (Exception e) {
                sendJavaBroadcast("⚠️ 收到Surface但需要EGL");
            }
        }).start();
    }

    // 主动注入
    private void performActiveInjection(ClassLoader cl) {
        mainHandler.post(() -> {
            try {
                Class<?> hClass = XposedHelpers.findClass(CLASS_AMAP_AIDL_MANAGER, cl);
                Object hInstance = XposedHelpers.getStaticObjectField(hClass, "e"); 
                if (hInstance == null) return;
                
                Object connection = XposedHelpers.getObjectField(hInstance, "f");
                if (connection instanceof ServiceConnection) {
                    ComponentName fakeCn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
                    ((ServiceConnection) connection).onServiceConnected(fakeCn, fakeServiceBinder);
                    sendJavaBroadcast("💉 注入成功");
                }
            } catch (Throwable t) {
                 sendJavaBroadcast("注入异常: " + t.getMessage());
            }
        });
    }

    // 拦截 bindService (BootClassLoader)
    private void hookBindService(ClassLoader appClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", null, "bindService",
                Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null) {
                    String className = intent.getComponent().getClassName();
                    if (TARGET_SERVICE_IMPL.equals(className)) {
                        sendJavaBroadcast("🚨 拦截连接");
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
                } else if ("XSF_ACTION_STOP_CAST".equals(intent.getAction())) {
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
                        tv.setText("V143 强制显示");
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

    private void destroyOverlayWindow() {
        mainHandler.post(() -> {
            if (clusterWindow != null) {
                try { clusterWindow.dismiss(); clusterWindow = null; sendJavaBroadcast("🛑 关闭"); } catch (Exception e) {}
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