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

    // 🎯 核心类
    private static final String CLASS_AMAP_AIDL_MANAGER = "ecarx.naviservice.map.amap.h";
    private static final String CLASS_MAP_MANAGER = "ecarx.naviservice.map.cf";
    private static final String CLASS_MAP_CONFIG_BASE = "ecarx.naviservice.map.co"; 
    
    // 🎯 实体类
    private static final String CLASS_EVENT_BUS = "ecarx.naviservice.d.e";
    private static final String CLASS_MAP_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLASS_MAP_SWITCHING_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLASS_MAP_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLASS_MAP_EVENT = "ecarx.naviservice.map.bz";

    private static final String DESCRIPTOR_SERVICE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String DESCRIPTOR_PROVIDER = "com.autosimilarwidget.view.IAutoWidgetStateProvider";
    
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";

    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Binder fakeServiceBinder = null;
    private static ClassLoader hostClassLoader = null;
    private static Presentation clusterWindow = null;
    private static IBinder systemProvider = null; // 系统给我们的 Binder
    
    private static volatile long drawEpoch = 0;
    // 🔒 永远锁定为 0 (Amap)，不再变来变去
    private static final int TARGET_VENDOR = 0; 
    
    private static boolean isConnected = false;
    private static Timer heartbeatTimer = null; 

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        hostClassLoader = lpparam.classLoader;
        XposedBridge.log("NaviHook: 🚀 V175 总线唤醒版启动");

        // 1. 获取 Context
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder(); 
                registerReceiver(systemContext);
                sendJavaBroadcast("⚡ V175 就绪");
            }
        });

        // 2. 身份伪造 (MapManager.c -> 0)
        try {
            Class<?> managerClass = XposedHelpers.findClassIfExists(CLASS_MAP_MANAGER, lpparam.classLoader);
            if (managerClass != null) {
                XposedHelpers.findAndHookMethod(managerClass, "c", XC_MethodReplacement.returnConstant(TARGET_VENDOR));
            }
        } catch (Throwable t) {}
        
        // 3. 鉴权解锁
        hookConfigClasses(lpparam.classLoader);
        
        // 4. 拦截 Bind (兼容 7.5)
        hookBindService();
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
                        case 4: // setWidgetStateControl (握手)
                            systemProvider = data.readStrongBinder(); // 🔥 保存系统给的 Binder
                            if (reply != null) reply.writeNoException(); 
                            
                            isConnected = true;
                            sendJavaBroadcast("✅ 握手成功 (拿到 Provider)");
                            
                            // 启动心跳和 FrameDrawn 轰炸
                            startHeartbeat();
                            
                            if (mainHandler != null) {
                                mainHandler.postDelayed(() -> triggerActivationSequence(), 200);
                            }
                            return true;
                        
                        case 1: // addSurface
                            Surface surface = null;
                            int hasSurface = data.readInt();
                            if (hasSurface != 0) surface = Surface.CREATOR.createFromParcel(data);
                            int id = data.readInt(); 
                            if (reply != null) reply.writeNoException();
                            
                            sendJavaBroadcast("🎯 收到 Surface! ID=" + id);
                            
                            // 收到 Surface 后，再次强推数据
                            triggerActivationSequence();
                            
                            if (surface != null) {
                                logSurfaceDetails(surface);
                                startEpochDrawing(surface); 
                            }
                            createOverlayWindow(); 
                            return true;

                        case 2: // Keep Alive
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
    
    // 💓 心跳：数据 + FrameDrawn 通知
    private void startHeartbeat() {
        if (heartbeatTimer != null) return;
        
        sendJavaBroadcast("💓 启动双泵心跳 (数据+Frame)...");
        heartbeatTimer = new Timer();
        heartbeatTimer.schedule(new TimerTask() {
            private int tick = 0;
            @Override
            public void run() {
                // 1. 发送 FrameDrawn 通知 (告诉系统我画好了，快显示)
                notifyFrameDrawn();
                
                // 2. 轮流发送状态和数据
                if (tick % 2 == 0) {
                    injectMapStatusSingle(1); // 状态 1: 初始化完成 (FINISHED)
                    injectMapStatusSingle(3); // 状态 3: 前台 (FOREGROUND)
                } else {
                    injectMapStatusSingle(16); // 状态 16: 导航中
                    injectMapGuideInfo();
                }
                tick++;
            }
        }, 0, 500); // 500ms 一次
    }
    
    private void stopHeartbeat() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
            sendJavaBroadcast("💔 心跳停止");
        }
    }

    private void notifyFrameDrawn() {
        if (systemProvider == null) return;
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR_PROVIDER);
                // transact 1 = frameDrawn
                systemProvider.transact(1, data, reply, 1); // 1 = FLAG_ONEWAY
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {}
    }

    // 🚀 激活序列
    private void triggerActivationSequence() {
        new Thread(() -> {
            try {
                sendJavaBroadcast("🚀 激活序列 (Status 1 -> 3 -> 16)");
                
                // 1. 初始化完成 (MapStatusInfo = 1)
                injectMapStatusSingle(1);
                Thread.sleep(50);
                
                // 2. 前台可见 (MapStatusInfo = 3)
                injectMapStatusSingle(3);
                Thread.sleep(50);
                
                // 3. 布局切换 (SwitchingInfo 5->0)
                injectMapSwitchingInfo(5, 0);
                Thread.sleep(100);
                
                // 4. 导航数据流
                injectFullStatusSequence();
                injectMapGuideInfo();
                
            } catch (Throwable t) {}
        }).start();
    }

    // 🔥 强制注入 (Trojan)
    private void performTrojanInjection() {
        mainHandler.post(() -> {
            try {
                sendJavaBroadcast("🛠️ 强制注入 (Trojan)...");
                
                Class<?> managerClass = XposedHelpers.findClass(CLASS_AMAP_AIDL_MANAGER, hostClassLoader);
                Object managerInstance = XposedHelpers.getStaticObjectField(managerClass, "e");
                
                Object connectionObj = XposedHelpers.getObjectField(managerInstance, "f");
                if (connectionObj instanceof ServiceConnection) {
                    ServiceConnection conn = (ServiceConnection) connectionObj;
                    ComponentName fakeCn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
                    conn.onServiceConnected(fakeCn, fakeServiceBinder);
                    sendJavaBroadcast("💉 注入完成");
                    
                    // 注入后立即激活
                    mainHandler.postDelayed(() -> triggerActivationSequence(), 200);
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
            Object guideInfo = XposedHelpers.newInstance(guideClass, TARGET_VENDOR);
            setBaseMapVendor(guideInfo, TARGET_VENDOR); 
            
            try { XposedHelpers.setObjectField(guideInfo, "curRoadName", "V175激活"); } catch (Throwable t) {}
            try { XposedHelpers.setObjectField(guideInfo, "nextRoadName", "全状态覆盖"); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "turnId", 2); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "nextTurnDistance", 200); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "remainDistance", 1000); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "remainTime", 60); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "guideType", 1); } catch (Throwable t) {}
            try { XposedHelpers.setBooleanField(guideInfo, "isCustomTBTEnabled", true); } catch (Throwable t) {}

            postEvent(1002, guideInfo);
        } catch (Throwable t) {
            sendJavaBroadcast("❌ GuideInfo: " + t.getMessage());
        }
    }
    
    private void injectFullStatusSequence() throws Exception {
        Class<?> statusClass = XposedHelpers.findClass(CLASS_MAP_STATUS_INFO, hostClassLoader);
        // 关键状态序列：1(完成) -> 3(前台) -> 12(活跃) -> 16(导航)
        int[] statuses = {1, 3, 7, 8, 12, 13, 14, 16}; 
        sendJavaBroadcast("💉 注入全状态流...");
        for (int s : statuses) {
            injectMapStatusSingle(s);
            Thread.sleep(30);
        }
    }
    
    private void injectMapStatusSingle(int status) {
        try {
            Class<?> statusClass = XposedHelpers.findClass(CLASS_MAP_STATUS_INFO, hostClassLoader);
            Object info = XposedHelpers.newInstance(statusClass, TARGET_VENDOR); 
            setBaseMapVendor(info, TARGET_VENDOR);
            XposedHelpers.setIntField(info, "status", status);
            postEvent(1001, info);
            if (status == 16) postEvent(2002, info);
        } catch (Exception e) {}
    }
    
    private void injectMapSwitchingInfo(int oldV, int newV) {
        try {
            Class<?> switchClass = XposedHelpers.findClass(CLASS_MAP_SWITCHING_INFO, hostClassLoader);
            Object switchInfo = XposedHelpers.newInstance(switchClass, oldV, newV);
            setBaseMapVendor(switchInfo, TARGET_VENDOR); // 这里也必须是 0
            XposedHelpers.setIntField(switchInfo, "mSwitchState", 3); 
            postEvent(2003, switchInfo);
            sendJavaBroadcast("🚀 Switch Sent");
        } catch (Throwable t) {}
    }
    
    private void postEvent(int type, Object eventObj) {
        try {
            Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader);
            Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class);
            Object event = eventConstructor.newInstance(type, eventObj);
            
            Class<?> busClass = XposedHelpers.findClass(CLASS_EVENT_BUS, hostClassLoader);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            XposedHelpers.callMethod(busInstance, "a", event);
        } catch (Throwable t) {}
    }

    private void startEpochDrawing(Surface surface) {
        if (!surface.isValid()) return;
        final long myEpoch = ++drawEpoch;
        new Thread(() -> {
            sendJavaBroadcast("🎨 启动绘制...");
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setTextSize(60);
            paint.setFakeBoldText(true);
            int frame = 0;
            while (drawEpoch == myEpoch && surface.isValid()) {
                Canvas c = null;
                try {
                    c = surface.lockCanvas(null);
                } catch (Exception e) { return; }
                if (c != null) {
                    c.drawColor(Color.YELLOW); // 黄色
                    c.drawText("V175 BusWaker", 50, 150, paint);
                    surface.unlockCanvasAndPost(c);
                }
                frame++;
                try { Thread.sleep(100); } catch (Exception e) {}
            }
        }).start();
    }

    private void notifyFrameDrawnAsync(IBinder provider) {
        // 在 V175 中，我们用心跳来做这个，这里留空
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
                        tv.setText("V175-BusWaker");
                        tv.setTextColor(Color.BLACK);
                        tv.setTextSize(50);
                        tv.setGravity(Gravity.CENTER);
                        tv.setBackgroundColor(Color.YELLOW); 
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