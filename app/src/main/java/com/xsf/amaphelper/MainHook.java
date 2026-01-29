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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Timer;
import java.util.TimerTask;
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

    // 🎯 核心控制类 (总指挥)
    private static final String CLASS_DASHBOARD_MGR = "ecarx.naviservice.a.a";
    
    // 🎯 核心类
    private static final String CLASS_AMAP_AIDL_MANAGER = "ecarx.naviservice.map.amap.h";
    private static final String CLASS_MAP_MANAGER = "ecarx.naviservice.map.cf";
    private static final String CLASS_MAP_CONFIG_BASE = "ecarx.naviservice.map.co"; 
    
    // 🎯 实体类
    private static final String CLASS_MAP_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLASS_MAP_SWITCHING_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLASS_MAP_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";

    private static final String DESCRIPTOR_SERVICE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String DESCRIPTOR_PROVIDER = "com.autosimilarwidget.view.IAutoWidgetStateProvider";
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";

    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Binder fakeServiceBinder = null;
    private static ClassLoader hostClassLoader = null;
    private static Presentation clusterWindow = null;
    
    private static volatile long drawEpoch = 0;
    private static volatile int currentDynamicVendor = 5; 
    private static boolean isConnected = false;
    private static Timer heartbeatTimer = null; 
    
    // 🎯 直接持有的管理器实例
    private static Object dashboardManagerRef = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        hostClassLoader = lpparam.classLoader;
        XposedBridge.log("NaviHook: 🚀 V174 直捣黄龙版启动");

        // 1. 获取 Context
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder(); 
                registerReceiver(systemContext);
                sendJavaBroadcast("⚡ V174 就绪");
                
                // 尝试提前捕获 DashboardManager
                captureDashboardManager();
            }
        });

        // 2. 动态 Hook Vendor
        try {
            Class<?> managerClass = XposedHelpers.findClassIfExists(CLASS_MAP_MANAGER, lpparam.classLoader);
            if (managerClass != null) {
                XposedHelpers.findAndHookMethod(managerClass, "c", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return currentDynamicVendor;
                    }
                });
            }
        } catch (Throwable t) {}
        
        // 3. 解锁配置
        hookConfigClasses(lpparam.classLoader);
        
        // 4. 拦截 Bind
        hookBindService();
    }
    
    // 🔥 V174 核心：捕获 DashboardManager 单例
    private void captureDashboardManager() {
        if (dashboardManagerRef != null) return;
        try {
            Class<?> mgrClass = XposedHelpers.findClass(CLASS_DASHBOARD_MGR, hostClassLoader);
            // 静态字段 b 通常是单例
            Field instanceField = XposedHelpers.findField(mgrClass, "b");
            instanceField.setAccessible(true);
            Object instance = instanceField.get(null);
            if (instance != null) {
                dashboardManagerRef = instance;
                sendJavaBroadcast("🎉 捕获总指挥官 (DashboardManager)");
            }
        } catch (Throwable t) {
            // 暂时忽略，后续还有机会
        }
    }

    // 🔥 V174 核心：直接调用管理器方法，不再走 EventBus
    private void directInject(Object dataInfo) {
        if (dataInfo == null) return;
        
        // 确保拿到实例
        if (dashboardManagerRef == null) captureDashboardManager();
        
        if (dashboardManagerRef != null) {
            try {
                // 方法 a(Object) 通常是入口
                XposedHelpers.callMethod(dashboardManagerRef, "a", dataInfo);
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 直注失败: " + t.getMessage());
            }
        } else {
            sendJavaBroadcast("⚠️ 总指挥官未就绪，无法注入");
        }
    }

    private void hookConfigClasses(ClassLoader cl) {
        try {
            Class<?> baseClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG_BASE, cl);
            if (baseClass != null) {
                XposedHelpers.findAndHookMethod(baseClass, "g", XC_MethodReplacement.returnConstant(true));
            }
        } catch (Throwable t) {}
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
                            
                            isConnected = true;
                            sendJavaBroadcast("✅ 握手成功");
                            startHeartbeat();
                            
                            if (mainHandler != null) {
                                mainHandler.postDelayed(() -> triggerHandoverSequence(), 300);
                            }
                            if (provider != null) notifyFrameDrawnAsync(provider);
                            return true;
                        
                        case 1: // addSurface
                            Surface surface = null;
                            int hasSurface = data.readInt();
                            if (hasSurface != 0) surface = Surface.CREATOR.createFromParcel(data);
                            int id = data.readInt(); 
                            if (reply != null) reply.writeNoException();
                            
                            sendJavaBroadcast("🎯 收到 Surface! ID=" + id);
                            
                            triggerHandoverSequence();
                            startHeartbeat();
                            
                            if (surface != null) {
                                logSurfaceDetails(surface);
                                startEpochDrawing(surface); 
                            }
                            createOverlayWindow(); 
                            return true;

                        case 2: // Keep alive
                            if (reply != null) reply.writeNoException();
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
                    return super.onTransact(code, data, reply, flags);
                }
            }
        };
    }
    
    private void startHeartbeat() {
        if (heartbeatTimer != null) return;
        
        sendJavaBroadcast("💓 启动强力心跳...");
        heartbeatTimer = new Timer();
        heartbeatTimer.schedule(new TimerTask() {
            private int tick = 0;
            @Override
            public void run() {
                if (currentDynamicVendor == 0) {
                    // 轮询轰炸
                    if (tick % 3 == 0) {
                        injectMapStatusSingle(3); // 前台
                    } else if (tick % 3 == 1) {
                        injectMapStatusSingle(16); // 导航中
                    } else {
                        injectMapGuideInfo(); // 数据
                    }
                    tick++;
                }
            }
        }, 0, 300); // 300ms 极速轰炸
    }
    
    private void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
    }

    private void performTrojanInjection() {
        mainHandler.post(() -> {
            try {
                sendJavaBroadcast("🛠️ 强制注入...");
                currentDynamicVendor = 5; 
                
                Class<?> managerClass = XposedHelpers.findClass(CLASS_AMAP_AIDL_MANAGER, hostClassLoader);
                Object managerInstance = XposedHelpers.getStaticObjectField(managerClass, "e");
                
                Object connectionObj = XposedHelpers.getObjectField(managerInstance, "f");
                if (connectionObj instanceof ServiceConnection) {
                    ServiceConnection conn = (ServiceConnection) connectionObj;
                    ComponentName fakeCn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
                    conn.onServiceConnected(fakeCn, fakeServiceBinder);
                    sendJavaBroadcast("💉 注入完成");
                    
                    mainHandler.postDelayed(() -> triggerHandoverSequence(), 500);
                }
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 强注失败: " + t.getMessage());
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
                if (intent == null || intent.getComponent() == null) return;

                String className = intent.getComponent().getClassName();
                if (TARGET_SERVICE_IMPL.equals(className)) {
                    sendJavaBroadcast("👻 捕获绑定");
                    isConnected = true; 
                    param.setResult(true); 
                    
                    final ServiceConnection conn = (ServiceConnection) param.args[1];
                    if (conn != null && fakeServiceBinder != null) {
                         if (mainHandler != null) {
                             mainHandler.post(() -> {
                                 try {
                                     ComponentName cn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
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
    
    private void registerReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if ("XSF_ACTION_START_CAST".equals(intent.getAction())) {
                    isConnected = false; 
                    stopHeartbeat();
                    performTrojanInjection();
                }
            }
        };
        IntentFilter filter = new IntentFilter("XSF_ACTION_START_CAST");
        context.registerReceiver(receiver, filter);
    }
    
    private void triggerHandoverSequence() {
        new Thread(() -> {
            try {
                sendJavaBroadcast("🚀 直注序列开始...");
                injectMapSwitchingInfo(5, 0);
                Thread.sleep(100);
                
                currentDynamicVendor = 0;
                injectMapStatusSingle(3); 
                Thread.sleep(100);
                injectFullStatusSequence();
                injectMapGuideInfo();
                
            } catch (Throwable t) {}
        }).start();
    }

    private void setBaseMapVendor(Object instance, int vendor) {
        try {
            Class<?> clazz = instance.getClass();
            while (clazz != null) {
                try {
                    Field f = clazz.getDeclaredField("mapVendor");
                    f.setAccessible(true);
                    f.setInt(instance, vendor);
                    return;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass(); 
                }
            }
        } catch (Throwable t) {}
    }

    private void injectMapGuideInfo() {
        try {
            Class<?> guideClass = XposedHelpers.findClass(CLASS_MAP_GUIDE_INFO, hostClassLoader);
            if (guideClass == null) return;
            
            Object guideInfo = XposedHelpers.newInstance(guideClass, 0);
            setBaseMapVendor(guideInfo, 0); 
            
            try { XposedHelpers.setObjectField(guideInfo, "curRoadName", "V174直注版"); } catch (Throwable t) {}
            try { XposedHelpers.setObjectField(guideInfo, "nextRoadName", "终极方案"); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "turnId", 2); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "nextTurnDistance", 100); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "remainDistance", 2000); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "remainTime", 60); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "guideType", 1); } catch (Throwable t) {}
            try { XposedHelpers.setBooleanField(guideInfo, "isCustomTBTEnabled", true); } catch (Throwable t) {}

            directInject(guideInfo); // 🔥 直接注入!
            
        } catch (Throwable t) {
            sendJavaBroadcast("❌ GuideInfo: " + t.getMessage());
        }
    }
    
    private void injectFullStatusSequence() throws Exception {
        Class<?> statusClass = XposedHelpers.findClass(CLASS_MAP_STATUS_INFO, hostClassLoader);
        int[] statuses = {3, 7, 8, 12, 13, 14, 16}; 
        
        for (int s : statuses) {
            Object info = XposedHelpers.newInstance(statusClass, 0); 
            setBaseMapVendor(info, 0);
            XposedHelpers.setIntField(info, "status", s);
            directInject(info); // 🔥 直接注入!
            Thread.sleep(50);
        }
    }
    
    private void injectMapStatusSingle(int status) {
        try {
            Class<?> statusClass = XposedHelpers.findClass(CLASS_MAP_STATUS_INFO, hostClassLoader);
            Object info = XposedHelpers.newInstance(statusClass, 0); 
            setBaseMapVendor(info, 0);
            XposedHelpers.setIntField(info, "status", status);
            directInject(info); // 🔥 直接注入!
        } catch (Exception e) {}
    }
    
    private void injectMapSwitchingInfo(int oldV, int newV) {
        try {
            Class<?> switchClass = XposedHelpers.findClass(CLASS_MAP_SWITCHING_INFO, hostClassLoader);
            if (switchClass == null) return;
            
            Object switchInfo = XposedHelpers.newInstance(switchClass, oldV, newV);
            setBaseMapVendor(switchInfo, 0);
            XposedHelpers.setIntField(switchInfo, "mSwitchState", 3); 
            
            directInject(switchInfo); // 🔥 直接注入!
            sendJavaBroadcast("🚀 Switch 直注");
        } catch (Throwable t) {}
    }

    private void startEpochDrawing(Surface surface) {
        if (!surface.isValid()) return;
        final long myEpoch = ++drawEpoch;
        new Thread(() -> {
            sendJavaBroadcast("🎨 启动绘制...");
            Paint paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setTextSize(60);
            paint.setFakeBoldText(true);
            int frame = 0;
            while (drawEpoch == myEpoch && surface.isValid()) {
                Canvas c = null;
                try {
                    c = surface.lockCanvas(null);
                } catch (Exception e) { return; }
                if (c != null) {
                    c.drawColor(Color.BLUE); // 蓝色 = 直注模式
                    c.drawText("V174 Direct", 50, 150, paint);
                    surface.unlockCanvasAndPost(c);
                }
                frame++;
                try { Thread.sleep(100); } catch (Exception e) {}
            }
        }).start();
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
                } catch (Exception e) {} finally {
                    data.recycle();
                    reply.recycle();
                }
            } catch (Throwable t) {}
        }).start();
    }
    
    private void logSurfaceDetails(Surface s) {
        String info = "Valid=" + s.isValid() + ", Hash=" + System.identityHashCode(s);
        sendJavaBroadcast("🏥 " + info);
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
                        tv.setText("V174-Direct");
                        tv.setTextColor(Color.WHITE);
                        tv.setTextSize(50);
                        tv.setGravity(Gravity.CENTER);
                        tv.setBackgroundColor(Color.BLUE); 
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