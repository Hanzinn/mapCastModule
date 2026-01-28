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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Random;
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

    // 🎯 核心类
    private static final String CLASS_AMAP_AIDL_MANAGER = "ecarx.naviservice.map.amap.h";
    private static final String CLASS_MAP_MANAGER = "ecarx.naviservice.map.cf";
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    
    // 🎯 状态与事件
    private static final String CLASS_EVENT_BUS = "ecarx.naviservice.d.e";
    private static final String CLASS_MAP_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLASS_MAP_EVENT = "ecarx.naviservice.map.bz";
    private static final String CLASS_MAP_SWITCHING_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";

    // 协议
    private static final String DESCRIPTOR_SERVICE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String DESCRIPTOR_PROVIDER = "com.autosimilarwidget.view.IAutoWidgetStateProvider";

    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Binder fakeServiceBinder = null;
    private static ClassLoader hostClassLoader = null;
    
    private static Presentation clusterWindow = null;
    
    // 🛡️ Epoch
    private static volatile long drawEpoch = 0;
    
    // ⚡ 动态 Vendor
    private static volatile int currentDynamicVendor = 5; 
    
    // 标志位
    private static boolean injectFailedOnce = false;
    private static boolean postEventFailedOnce = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        hostClassLoader = lpparam.classLoader;
        XposedBridge.log("NaviHook: 🚀 V154 抢跑激活版启动");

        // 1. 获取 Context
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder(); 
                registerReceiver(systemContext);
                
                sendJavaBroadcast("⚡ V154 就绪");
                mainHandler.postDelayed(() -> performActiveInjection(), 3000);
            }
        });

        // 2. 动态 Hook Vendor
        try {
            Class<?> managerClass = XposedHelpers.findClassIfExists(CLASS_MAP_MANAGER, lpparam.classLoader);
            if (managerClass != null) {
                XposedHelpers.findAndHookMethod(managerClass, "c", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return currentDynamicVendor;
                    }
                });
            }
        } catch (Throwable t) {
             XposedBridge.log("NaviHook: VendorHook Error: " + t);
        }
        
        // 3. 拦截 bindService
        hookBindService();
    }

    private void initFakeBinder() {
        if (fakeServiceBinder != null) return;
        
        fakeServiceBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                try {
                    if (code == 1598968902) { 
                        if (reply != null) reply.writeString(DESCRIPTOR_SERVICE);
                        return true;
                    }
                    data.enforceInterface(DESCRIPTOR_SERVICE);

                    switch (code) {
                        case 4: // setWidgetStateControl
                            IBinder provider = data.readStrongBinder(); 
                            if (reply != null) reply.writeNoException(); 
                            
                            sendJavaBroadcast("✅ 握手成功 (IPC OK)");
                            
                            // 🔥🔥🔥 V154 关键修改：握手瞬间直接抢跑！
                            // 不等 addSurface，直接告诉系统：我是高德，我在导航，快切屏！
                            triggerVendorJump(0); 
                            
                            if (provider != null) notifyFrameDrawnAsync(provider);
                            return true;
                        
                        case 1: // addSurface
                            Surface surface = null;
                            int hasSurface = data.readInt();
                            if (hasSurface != 0) {
                                surface = Surface.CREATOR.createFromParcel(data);
                            }
                            int id = data.readInt(); 

                            if (reply != null) reply.writeNoException();
                            
                            sendJavaBroadcast("🎯🎯🎯 收到 Surface! ID=" + id);
                            
                            // 收到 Surface 后，再次确认注入，防止漏网
                            if (currentDynamicVendor != 0) triggerVendorJump(0);
                            
                            if (surface != null) {
                                logSurfaceDetails(surface);
                                startEpochDrawing(surface); 
                            } else {
                                sendJavaBroadcast("⚠️ 警告: Surface 为空!");
                            }
                            createOverlayWindow(); 
                            return true;

                        case 2: // removedSurface
                            int hasSurf2 = data.readInt();
                            if (hasSurf2 != 0) Surface.CREATOR.createFromParcel(data);
                            int id2 = data.readInt();
                            if (reply != null) reply.writeNoException();
                            
                            sendJavaBroadcast("♻️ Surface移除 ID=" + id2);
                            
                            // 移除时，恢复 Vendor = 5
                            triggerVendorJump(5); 
                            drawEpoch++; 
                            return true;

                        case 3: // isMapRunning
                            if (reply != null) {
                                reply.writeNoException();
                                reply.writeInt(1); 
                            }
                            return true;
                            
                        case 5: // dispatchTouchEvent
                            int hasEvent = data.readInt();
                            if (hasEvent != 0) android.view.MotionEvent.CREATOR.createFromParcel(data);
                            if (reply != null) reply.writeNoException();
                            return true;

                        default:
                             return super.onTransact(code, data, reply, flags);
                    }
                } catch (Throwable e) {
                    XposedBridge.log("NaviHook: Binder Error: " + e);
                    sendJavaBroadcast("❌ Binder异常: " + e.getMessage());
                    return super.onTransact(code, data, reply, flags);
                }
            }
        };
    }
    
    // ⚡ Vendor 跳变控制器
    private void triggerVendorJump(int targetVendor) {
        // V154: 即使 vendor 没变，如果是 0，也要强制注入状态，因为可能是在重连
        if (currentDynamicVendor == targetVendor && targetVendor != 0) return;
        
        currentDynamicVendor = targetVendor;
        sendJavaBroadcast("🔀 Vendor跳变 -> " + targetVendor);
        
        if (targetVendor == 0) {
            injectMapStatusAsync(); // 立即发射状态全家桶
            injectMapSwitchingInfo(); 
        }
    }

    // 🎨 Epoch 绘制线程
    private void startEpochDrawing(Surface surface) {
        if (!surface.isValid()) return;
        final long myEpoch = ++drawEpoch;
        
        new Thread(() -> {
            sendJavaBroadcast("🎨 启动绘制 (Epoch " + myEpoch + ")...");
            Paint paintStroke = new Paint();
            paintStroke.setColor(Color.RED);
            paintStroke.setStyle(Paint.Style.STROKE);
            paintStroke.setStrokeWidth(20);
            Paint paintText = new Paint();
            paintText.setColor(Color.WHITE);
            paintText.setTextSize(60);
            paintText.setFakeBoldText(true);
            Paint centerPaint = new Paint();
            centerPaint.setColor(Color.YELLOW);
            centerPaint.setStrokeWidth(5);
            int frame = 0;

            while (drawEpoch == myEpoch && surface.isValid()) {
                Canvas c = null;
                try {
                    c = surface.lockCanvas(null);
                } catch (IllegalArgumentException e) {
                    if (frame == 0) sendJavaBroadcast("⚠️ EGL Surface，Canvas不可用");
                    return; 
                } catch (Exception e) {
                    return; 
                }

                if (c != null) {
                    try {
                        int w = c.getWidth();
                        int h = c.getHeight();
                        c.drawColor(Color.BLACK); 
                        c.drawRect(0, 0, w, h, paintStroke);
                        c.drawLine(w/2, 0, w/2, h, centerPaint);
                        c.drawLine(0, h/2, w, h/2, centerPaint);
                        c.drawText("V154 抢跑版", 50, 150, paintText);
                        c.drawText("Frame:" + frame++, 50, 250, paintText);
                    } finally {
                        surface.unlockCanvasAndPost(c);
                        if (frame == 1) sendJavaBroadcast("✅ 绘制成功(Frame 1)");
                    }
                }
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }
        }).start();
    }
    
    // 💉 状态注入 (V126 序列)
    private void injectMapStatusAsync() {
        new Thread(() -> {
            try {
                sendJavaBroadcast("💉 注入 V126 状态流...");
                
                Class<?> statusClass = XposedHelpers.findClass(CLASS_MAP_STATUS_INFO, hostClassLoader);
                Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader);
                Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class);

                int[] statuses = {12, 13, 14, 16}; 
                
                for (int s : statuses) {
                    Object info = XposedHelpers.newInstance(statusClass, 0); 
                    XposedHelpers.setIntField(info, "status", s);
                    
                    postEvent(eventConstructor.newInstance(1001, info));
                    postEvent(eventConstructor.newInstance(2002, info)); 
                    Thread.sleep(100);
                }
                sendJavaBroadcast("✅ 状态注入完毕");
            } catch (Throwable t) {
                if (!injectFailedOnce) {
                    sendJavaBroadcast("❌ 注入失败: " + t.getClass().getSimpleName());
                    injectFailedOnce = true;
                }
            }
        }).start();
    }
    
    private void injectMapSwitchingInfo() {
        new Thread(() -> {
            try {
                Class<?> switchClass = XposedHelpers.findClass(CLASS_MAP_SWITCHING_INFO, hostClassLoader);
                if (switchClass == null) return;
                
                Object switchInfo = null;
                try {
                    switchInfo = switchClass.newInstance();
                } catch (Exception e) {
                    Constructor<?> c = switchClass.getConstructors()[0];
                    if (c.getParameterCount() == 1) switchInfo = c.newInstance(0);
                }
                
                if (switchInfo != null) {
                    Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader);
                    Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class);
                    postEvent(eventConstructor.newInstance(2003, switchInfo));
                }
            } catch (Throwable t) {}
        }).start();
    }
    
    private void postEvent(Object event) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLASS_EVENT_BUS, hostClassLoader);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            XposedHelpers.callMethod(busInstance, "a", event);
        } catch (Throwable t) {
            if (!postEventFailedOnce) {
                 sendJavaBroadcast("❌ PostEvent失败: " + t.getClass().getSimpleName());
                 postEventFailedOnce = true;
            }
        }
    }

    private void notifyFrameDrawnAsync(IBinder provider) {
        new Thread(() -> {
            try {
                Thread.sleep(50);
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR_PROVIDER);
                    provider.transact(1, data, reply, 0); 
                    reply.readException();
                    sendJavaBroadcast("📣 回调 FrameDrawn 完成");
                } catch (Exception e) {
                    sendJavaBroadcast("⚠️ 回调异常: " + e.getMessage());
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            } catch (Throwable t) {}
        }).start();
    }

    private void performActiveInjection() {
        mainHandler.post(() -> {
            try {
                Class<?> hClass = XposedHelpers.findClass(CLASS_AMAP_AIDL_MANAGER, hostClassLoader);
                Object hInstance = XposedHelpers.getStaticObjectField(hClass, "e"); 
                if (hInstance == null) {
                    sendJavaBroadcast("⚠️ 系统未初始化");
                    return;
                }
                Object connection = XposedHelpers.getObjectField(hInstance, "f");
                if (connection instanceof ServiceConnection) {
                    ComponentName fakeCn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
                    ((ServiceConnection) connection).onServiceConnected(fakeCn, fakeServiceBinder);
                    sendJavaBroadcast("💉 注入成功");
                }
            } catch (Throwable t) {
                 sendJavaBroadcast("❌ 注入崩溃: " + t.getMessage());
            }
        });
    }

    private void hookBindService() {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", null, "bindService",
                Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent != null && intent.getComponent() != null) {
                    String className = intent.getComponent().getClassName();
                    if (TARGET_SERVICE_IMPL.equals(className)) {
                        sendJavaBroadcast("🚨 拦截连接 (STATUS_HOOK_SUCCESS)");
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
        } catch (Throwable t) {
             XposedBridge.log("NaviHook: BindService Hook Error: " + t);
        }
    }
    
    private void logSurfaceDetails(Surface s) {
        String info = "Valid=" + s.isValid() + ", Hash=" + System.identityHashCode(s);
        sendJavaBroadcast("🏥 " + info);
    }

    private void registerReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if ("XSF_ACTION_START_CAST".equals(intent.getAction())) {
                    performActiveInjection();
                }
            }
        };
        IntentFilter filter = new IntentFilter("XSF_ACTION_START_CAST");
        context.registerReceiver(receiver, filter);
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
                        tv.setText("V154-Pre 强制显示");
                        tv.setTextColor(Color.GREEN);
                        tv.setTextSize(50);
                        tv.setGravity(Gravity.CENTER);
                        tv.setBackgroundColor(Color.BLACK);
                        setContentView(tv);
                    }
                };
                clusterWindow.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                clusterWindow.show();
            } catch (Throwable t) {
                sendJavaBroadcast("❌ Overlay失败: " + t.getMessage());
            }
        });
    }
private void sendJavaBroadcast(String log) {
        if (systemContext == null) {
            XposedBridge.log("NaviHook-Pre: " + log);
            return;
        }
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
    