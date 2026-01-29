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
    
    // 目标（不存在的）服务
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    // 宿主服务 (9.1 肯定有的)
    private static final String HOST_SERVICE_IMPL = "com.autonavi.amapauto.service.MapService"; 

    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Binder fakeServiceBinder = null;
    private static ClassLoader hostClassLoader = null;
    private static Presentation clusterWindow = null;
    private static IBinder systemProvider = null;
    
    private static volatile long drawEpoch = 0;
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
        XposedBridge.log("NaviHook: 🚀 V181 (Fix) 裸奔兼容版启动 (No-Token)");

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder(); 
                registerReceiver(systemContext);
                sendJavaBroadcast("⚡ V181 就绪");
            }
        });

        // 1. Hook MapManager
        try {
            Class<?> managerClass = XposedHelpers.findClassIfExists(CLASS_MAP_MANAGER, lpparam.classLoader);
            if (managerClass != null) {
                XposedHelpers.findAndHookMethod(managerClass, "c", XC_MethodReplacement.returnConstant(TARGET_VENDOR));
            }
        } catch (Throwable t) {}
        
        // 2. 解锁配置
        hookConfigClasses(lpparam.classLoader);
        
        // 3. 拦截 Bind
        hookBindService();
    }

    private void initFakeBinder() {
        if (fakeServiceBinder != null) return;
        
        fakeServiceBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                try {
                    // 🔥🔥🔥 核心修改：彻底移除 Token 校验
                    // data.enforceInterface(DESCRIPTOR_SERVICE); <-- 已删除
                    
                    // 尝试跳过 Token (如果存在)
                    int startPos = data.dataPosition();
                    try {
                        String token = data.readString();
                        // 如果读出来的不是我们预期的接口名，说明可能根本没发 Token，或者 Token 不一样
                        if (token == null || !token.contains("AutoSimilarWidget")) {
                            // 回退指针，把它当参数读
                            data.setDataPosition(startPos);
                        }
                    } catch (Exception e) {
                        data.setDataPosition(startPos);
                    }

                    switch (code) {
                        case 4: // setWidgetStateControl (握手)
                            systemProvider = data.readStrongBinder(); 
                            if (reply != null) reply.writeNoException(); 
                            
                            isConnected = true;
                            sendJavaBroadcast("✅ 握手成功 (No-Token)");
                            
                            startHeartbeat();
                            if (mainHandler != null) {
                                mainHandler.postDelayed(() -> triggerActivationSequence(), 200);
                            }
                            return true;
                        
                        case 1: // addSurface
                            Surface surface = null;
                            try {
                                int hasSurface = data.readInt();
                                if (hasSurface != 0) surface = Surface.CREATOR.createFromParcel(data);
                                int id = data.readInt(); 
                                sendJavaBroadcast("🎯 收到 Surface! ID=" + id);
                            } catch (Exception e) {
                                sendJavaBroadcast("❌ 解析 Surface 失败: " + e.getMessage());
                            }
                            
                            if (reply != null) reply.writeNoException();
                            
                            triggerActivationSequence();
                            startHeartbeat(); 
                            
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
                            try {
                                int hasEvent = data.readInt();
                                if (hasEvent != 0) android.view.MotionEvent.CREATOR.createFromParcel(data);
                            } catch (Exception e) {}
                            if (reply != null) reply.writeNoException();
                            return true;
                            
                        case 1598968902: // INTERFACE_TRANSACTION
                            if (reply != null) reply.writeString(DESCRIPTOR_SERVICE);
                            return true;

                        default:
                             return super.onTransact(code, data, reply, flags);
                    }
                } catch (Throwable e) {
                    sendJavaBroadcast("❌ Transact异常: " + e.getMessage());
                    return super.onTransact(code, data, reply, flags);
                }
            }
        };
    }
    
    // 🔥 寄生策略
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
                    sendJavaBroadcast("👻 拦截 Bind，寄生启动...");
                    
                    // 指向真实存在的 MapService
                    intent.setComponent(new ComponentName(PKG_MAP, HOST_SERVICE_IMPL));
                    param.args[0] = intent;
                    
                    final ServiceConnection originalConn = (ServiceConnection) param.args[1];
                    ServiceConnection proxyConn = new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            sendJavaBroadcast("🎭 寄生连接建立，替换 Binder");
                            ComponentName targetCn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
                            if (originalConn != null) {
                                originalConn.onServiceConnected(targetCn, fakeServiceBinder);
                            }
                            isConnected = true;
                            if (mainHandler != null) {
                                mainHandler.postDelayed(() -> triggerActivationSequence(), 200);
                            }
                        }

                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                            if (originalConn != null) originalConn.onServiceDisconnected(name);
                        }
                    };
                    param.args[1] = proxyConn;
                }
            }
        });
        } catch (Throwable t) { // 修复了这里缺少 catch 的问题
             XposedBridge.log("NaviHook: Bind Hook Error: " + t);
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

    private void startHeartbeat() {
        if (heartbeatTimer != null) return;
        sendJavaBroadcast("💓 启动心跳...");
        heartbeatTimer = new Timer();
        heartbeatTimer.schedule(new TimerTask() {
            private int tick = 0;
            @Override
            public void run() {
                notifyFrameDrawn();
                if (tick % 2 == 0) {
                    injectMapStatusSingle(3); 
                } else {
                    injectMapStatusSingle(16);
                    injectMapGuideInfo();
                }
                tick++;
            }
        }, 0, 500); 
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
                systemProvider.transact(1, data, reply, 1); 
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {}
    }

    private void triggerActivationSequence() {
        new Thread(() -> {
            try {
                sendJavaBroadcast("🚀 激活序列...");
                injectMapStatusSingle(1); 
                Thread.sleep(50);
                injectMapStatusSingle(3);
                Thread.sleep(50);
                injectMapSwitchingInfo(5, 0); 
                Thread.sleep(100);
                injectFullStatusSequence();
                injectMapGuideInfo();
            } catch (Throwable t) {}
        }).start();
    }

    // 手动触发
    private void performTrojanInjection() {
        mainHandler.post(() -> {
            try {
                sendJavaBroadcast("🛠️ 触发模拟启动...");
                Intent intent = new Intent("ecarx.intent.action.NAVI_SERVICE_STARTED");
                intent.setPackage(PKG_SERVICE);
                systemContext.sendBroadcast(intent);
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 广播失败: " + t.getMessage());
            }
        });
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
            try { XposedHelpers.setObjectField(guideInfo, "curRoadName", "V181 NoToken"); } catch (Throwable t) {}
            try { XposedHelpers.setObjectField(guideInfo, "nextRoadName", "Green Screen"); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "turnId", 2); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "nextTurnDistance", 555); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "remainDistance", 1000); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "remainTime", 60); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "guideType", 1); } catch (Throwable t) {}
            try { XposedHelpers.setBooleanField(guideInfo, "isCustomTBTEnabled", true); } catch (Throwable t) {}
            postEvent(1002, guideInfo);
        } catch (Throwable t) {}
    }
    
    private void injectFullStatusSequence() throws Exception {
        Class<?> statusClass = XposedHelpers.findClass(CLASS_MAP_STATUS_INFO, hostClassLoader);
        int[] statuses = {1, 3, 7, 8, 12, 13, 14, 16}; 
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
            setBaseMapVendor(switchInfo, TARGET_VENDOR); 
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
                    c.drawColor(Color.GREEN); // 绿色 = 成功
                    c.drawText("V181 NoToken", 50, 150, paint);
                    surface.unlockCanvasAndPost(c);
                }
                frame++;
                try { Thread.sleep(100); } catch (Exception e) {}
            }
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
                        tv.setText("V181-NoToken");
                        tv.setTextColor(Color.WHITE);
                        tv.setTextSize(50);
                        tv.setGravity(Gravity.CENTER);
                        tv.setBackgroundColor(Color.GREEN); 
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