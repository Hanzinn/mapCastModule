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

    private static final String CLASS_AMAP_AIDL_MANAGER = "ecarx.naviservice.map.amap.h";
    private static final String CLASS_MAP_MANAGER = "ecarx.naviservice.map.cf";
    private static final String CLASS_MAP_CONFIG_BASE = "ecarx.naviservice.map.co"; 
    
    private static final String CLASS_EVENT_BUS = "ecarx.naviservice.d.e";
    private static final String CLASS_MAP_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLASS_MAP_SWITCHING_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLASS_MAP_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLASS_MAP_EVENT = "ecarx.naviservice.map.bz";

    private static final String DESCRIPTOR_SERVICE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String DESCRIPTOR_PROVIDER = "com.autosimilarwidget.view.IAutoWidgetStateProvider";
    
    // 目标服务类名 (9.1 不存在，但我们要用它来欺骗系统)
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";

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
        XposedBridge.log("NaviHook: 🚀 V183 无Token特洛伊版启动");

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder(); 
                registerReceiver(systemContext);
                sendJavaBroadcast("⚡ V183 就绪");
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
        
        // 3. 拦截 Bind (仅作为 7.5 的兼容，9.1 主要靠手动强注)
        hookBindService();
    }

    private void initFakeBinder() {
        if (fakeServiceBinder != null) return;
        
        fakeServiceBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                try {
                    // 🔥🔥🔥 V183 核心：绝对不检查 Token！
                    // data.enforceInterface(...); // 删掉！
                    
                    // 尝试跳过 Token (如果存在)
                    int startPos = data.dataPosition();
                    try {
                        String token = data.readString();
                        // 简单的启发式检查：如果读出来的像 Token，就消耗掉；否则回退
                        if (token == null || !token.contains("AutoSimilarWidget")) {
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
                            
                            // 握手后立即启动心跳
                            startHeartbeat();
                            
                            // 延时触发激活序列，防止系统状态未就绪
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
                            } catch (Exception e) {}
                            
                            if (reply != null) reply.writeNoException();
                            
                            // 收到 Surface 后再次强推
                            triggerActivationSequence();
                            
                            if (surface != null) {
                                logSurfaceDetails(surface);
                                startEpochDrawing(surface); 
                            }
                            createOverlayWindow(); 
                            return true;

                        case 2: // Keep Alive
                            // V171 的教训：千万别自杀！
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

                        default:
                             return super.onTransact(code, data, reply, flags);
                    }
                } catch (Throwable e) {
                    // 只有这里不抛异常，系统才不会断开连接
                    sendJavaBroadcast("⚠️ Transact隐忍: " + e.getMessage());
                    return true; 
                }
            }
        };
    }
    
    // 🔥 V183 核心：手动强注 + NoToken Binder
    private void performTrojanInjection() {
        mainHandler.post(() -> {
            try {
                sendJavaBroadcast("🛠️ 执行 V183 强注...");
                
                Class<?> managerClass = XposedHelpers.findClass(CLASS_AMAP_AIDL_MANAGER, hostClassLoader);
                Object managerInstance = XposedHelpers.getStaticObjectField(managerClass, "e");
                Object connectionObj = XposedHelpers.getObjectField(managerInstance, "f");
                
                if (connectionObj instanceof ServiceConnection) {
                    ServiceConnection conn = (ServiceConnection) connectionObj;
                    ComponentName fakeCn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
                    
                    // 🔥 强行插入我们的 No-Token Binder
                    conn.onServiceConnected(fakeCn, fakeServiceBinder);
                    
                    sendJavaBroadcast("💉 强注完成");
                    
                    // 注入后尝试激活
                    mainHandler.postDelayed(() -> triggerActivationSequence(), 300);
                } else {
                    sendJavaBroadcast("❌ 找不到连接对象");
                }
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 强注失败: " + t.getMessage());
            }
        });
    }

    // 辅助 Bind 拦截 (为 7.5 保底)
    private void hookBindService() {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", null, "bindService",
                Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent == null || intent.getComponent() == null) return;
                if (TARGET_SERVICE_IMPL.equals(intent.getComponent().getClassName())) {
                    sendJavaBroadcast("👻 捕获自动绑定");
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

    private void hookConfigClasses(ClassLoader cl) {
        try {
            Class<?> baseClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG_BASE, cl);
            if (baseClass != null) {
                XposedHelpers.findAndHookMethod(baseClass, "g", XC_MethodReplacement.returnConstant(true));
            }
        } catch (Throwable t) {}
    }

    // 💓 心跳泵：每秒刷新 FrameDrawn 并轮询状态
    private void startHeartbeat() {
        if (heartbeatTimer != null) return;
        sendJavaBroadcast("💓 启动心跳...");
        heartbeatTimer = new Timer();
        heartbeatTimer.schedule(new TimerTask() {
            private int tick = 0;
            @Override
            public void run() {
                notifyFrameDrawn(); // 告诉系统我画好了
                
                // 轮流强调我是前台(3)和导航中(16)
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
                // 不带 Token 发送 FrameDrawn (因为系统发来也没带)
                systemProvider.transact(1, data, reply, 1); 
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {}
    }

    // 🚀 全套激活序列
    private void triggerActivationSequence() {
        new Thread(() -> {
            try {
                sendJavaBroadcast("🚀 激活序列...");
                // 1. 初始化完成
                injectMapStatusSingle(1); 
                Thread.sleep(50);
                
                // 2. 切布局
                injectMapSwitchingInfo(5, 0); 
                Thread.sleep(100);
                
                // 3. 必须：声明前台状态
                injectMapStatusSingle(3); 
                Thread.sleep(50);
                
                // 4. 导航数据流
                injectFullStatusSequence();
                injectMapGuideInfo();
            } catch (Throwable t) {}
        }).start();
    }
    
    private void registerReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if ("XSF_ACTION_START_CAST".equals(intent.getAction())) {
                    isConnected = false; 
                    stopHeartbeat();
                    // 9.1 必须靠这个！
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
            try { XposedHelpers.setObjectField(guideInfo, "curRoadName", "V183终极"); } catch (Throwable t) {}
            try { XposedHelpers.setObjectField(guideInfo, "nextRoadName", "TokenFree"); } catch (Throwable t) {}
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
                    c.drawColor(Color.MAGENTA); // 洋红色
                    c.drawText("V183 Trojan NoToken", 50, 150, paint);
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
                        tv.setText("V183-Trojan-NoToken");
                        tv.setTextColor(Color.WHITE);
                        tv.setTextSize(50);
                        tv.setGravity(Gravity.CENTER);
                        tv.setBackgroundColor(Color.MAGENTA); 
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