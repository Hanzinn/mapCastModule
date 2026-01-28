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
    
    // 🎯 状态/事件/切换
    private static final String CLASS_EVENT_BUS = "ecarx.naviservice.d.e";
    private static final String CLASS_MAP_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLASS_MAP_SWITCHING_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo"; // 新增
    private static final String CLASS_MAP_EVENT = "ecarx.naviservice.map.bz";

    // 协议
    private static final String DESCRIPTOR_SERVICE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String DESCRIPTOR_PROVIDER = "com.autosimilarwidget.view.IAutoWidgetStateProvider";

    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Binder fakeServiceBinder = null;
    private static ClassLoader hostClassLoader = null;
    private static Presentation clusterWindow = null;
    
    private static volatile long drawEpoch = 0;
    private static volatile int currentDynamicVendor = 5; 
    
    // 日志控制
    private static boolean injectFailedOnce = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        hostClassLoader = lpparam.classLoader;
        XposedBridge.log("NaviHook: 🚀 V155 总线欺骗+布局强切版");

        // 1. 获取 Context
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder(); 
                registerReceiver(systemContext);
                sendJavaBroadcast("⚡ V155 就绪");
                mainHandler.postDelayed(() -> performActiveInjection(), 3000);
            }
        });

        // 2. 动态 Vendor Hook
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
        } catch (Throwable t) {}
        
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
                            
                            sendJavaBroadcast("✅ 握手成功 (Step 1)");
                            
                            // 🔥 V155: 握手即激活
                            triggerVendorJump(0);
                            
                            // 即使不发 addSurface，我们也要尝试自己模拟这一步
                            // 强制注入 SwitchingInfo，这是 V155 的杀手锏
                            injectMapSwitchingInfoAsync();
                            
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
                            
                            if (currentDynamicVendor != 0) triggerVendorJump(0);
                            
                            if (surface != null) {
                                logSurfaceDetails(surface);
                                startEpochDrawing(surface); 
                            }
                            createOverlayWindow(); 
                            return true;

                        case 2: // removedSurface
                            int hasSurf2 = data.readInt();
                            if (hasSurf2 != 0) Surface.CREATOR.createFromParcel(data);
                            int id2 = data.readInt();
                            if (reply != null) reply.writeNoException();
                            
                            sendJavaBroadcast("♻️ Surface移除 ID=" + id2);
                            triggerVendorJump(5); 
                            drawEpoch++; 
                            return true;

                        case 3: // isMapRunning
                            sendJavaBroadcast("ℹ️ 系统问: isMapRunning? -> YES");
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
    
    private void triggerVendorJump(int targetVendor) {
        if (currentDynamicVendor == targetVendor && targetVendor != 0) return;
        currentDynamicVendor = targetVendor;
        sendJavaBroadcast("🔀 Vendor -> " + targetVendor);
        
        if (targetVendor == 0) {
            injectMapStatusAsync();
        }
    }

    private void startEpochDrawing(Surface surface) {
        if (!surface.isValid()) return;
        final long myEpoch = ++drawEpoch;
        new Thread(() -> {
            sendJavaBroadcast("🎨 启动绘制...");
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setTextSize(60);
            int frame = 0;
            while (drawEpoch == myEpoch && surface.isValid()) {
                Canvas c = null;
                try {
                    c = surface.lockCanvas(null);
                } catch (Exception e) {
                    if(frame==0) sendJavaBroadcast("⚠️ 绘图需要EGL");
                    return;
                }
                if (c != null) {
                    c.drawColor(Color.BLACK);
                    c.drawText("V155 Activated", 50, 150, paint);
                    surface.unlockCanvasAndPost(c);
                    if (frame == 1) sendJavaBroadcast("✅ 绘制成功");
                }
                frame++;
                try { Thread.sleep(100); } catch (Exception e) {}
            }
        }).start();
    }
    
    // 💉 注入 MapStatusInfo (扩展状态码)
    private void injectMapStatusAsync() {
        new Thread(() -> {
            try {
                sendJavaBroadcast("💉 注入 MapStatusInfo...");
                Class<?> statusClass = XposedHelpers.findClass(CLASS_MAP_STATUS_INFO, hostClassLoader);
                Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader);
                Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class);

                // 增加状态 1 (READY) 和 10 (RESUME)
                int[] statuses = {1, 10, 12, 13, 14, 16}; 
                
                for (int s : statuses) {
                    // 使用 Vendor=0 构造
                    Object info = XposedHelpers.newInstance(statusClass, 0); 
                    XposedHelpers.setIntField(info, "status", s);
                    
                    // 1001: 状态更新
                    postEvent(eventConstructor.newInstance(1001, info));
                    // 2002: 首帧通知 (强行帮 j.smali 干活)
                    postEvent(eventConstructor.newInstance(2002, info));
                    
                    Thread.sleep(100);
                }
                sendJavaBroadcast("✅ 状态注入完毕");
            } catch (Throwable t) {
                if (!injectFailedOnce) {
                    sendJavaBroadcast("❌ 状态注入失败: " + t.getClass().getSimpleName());
                    injectFailedOnce = true;
                }
            }
        }).start();
    }
    
    // 🔥 V155 杀手锏：注入 MapSwitchingInfo
    // 这是触发仪表盘布局切换的关键 (Switch from Widget to Full Map)
    private void injectMapSwitchingInfoAsync() {
        new Thread(() -> {
            try {
                Class<?> switchClass = XposedHelpers.findClass(CLASS_MAP_SWITCHING_INFO, hostClassLoader);
                if (switchClass == null) {
                    sendJavaBroadcast("⚠️ 未找到 MapSwitchingInfo 类");
                    return;
                }
                sendJavaBroadcast("🚀 注入 MapSwitchingInfo (布局切换)...");
                
                Object switchInfo = null;
                // 尝试多种构造方式
                try {
                    // 1. 无参构造
                    switchInfo = switchClass.newInstance();
                } catch (Exception e) {
                    // 2. 带参构造 (vendor?)
                    try {
                        Constructor<?> c = switchClass.getConstructors()[0];
                        if (c.getParameterCount() == 1) switchInfo = c.newInstance(0);
                    } catch (Exception ex) {}
                }
                
                if (switchInfo != null) {
                    // 尝试设置一些常见字段，让它看起来像真的
                    try { XposedHelpers.setIntField(switchInfo, "type", 1); } catch (Throwable t) {}
                    try { XposedHelpers.setIntField(switchInfo, "vendor", 0); } catch (Throwable t) {}

                    Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader);
                    Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class);
                    
                    // Event Code 2003 (SWAP/SWITCH) - 盲猜但概率很高
                    postEvent(eventConstructor.newInstance(2003, switchInfo));
                    // 备用：Code 1005 (Layout Change)
                    postEvent(eventConstructor.newInstance(1005, switchInfo));
                    
                    sendJavaBroadcast("✅ 布局切换指令已发送");
                } else {
                    sendJavaBroadcast("❌ SwitchingInfo 构造失败");
                }
            } catch (Throwable t) {
                XposedBridge.log("NaviHook: SwitchInfo Error: " + t);
            }
        }).start();
    }
    
    private void postEvent(Object event) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLASS_EVENT_BUS, hostClassLoader);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            XposedHelpers.callMethod(busInstance, "a", event);
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: PostEvent Error: " + t);
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
                        tv.setText("V155-Force 强制显示");
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