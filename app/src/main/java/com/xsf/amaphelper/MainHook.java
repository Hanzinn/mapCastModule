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
        XposedBridge.log("NaviHook: 🚀 V185 霸道总裁版启动 (Trojan+NoToken+Focus)");

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder(); 
                registerReceiver(systemContext);
                sendJavaBroadcast("⚡ V185 就绪");
            }
        });

        // 1. Hook MapManager
        try {
            Class<?> managerClass = XposedHelpers.findClassIfExists(CLASS_MAP_MANAGER, lpparam.classLoader);
            if (managerClass != null) {
                XposedHelpers.findAndHookMethod(managerClass, "c", XC_MethodReplacement.returnConstant(TARGET_VENDOR));
                
                // Hook getMapInfo (d方法) 防止空指针
                XposedHelpers.findAndHookMethod(managerClass, "d", int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(PKG_MAP); // 强制返回包名，防止系统查不到 0 号厂商报错
                    }
                });
            }
        } catch (Throwable t) {}
        
        hookConfigClasses(lpparam.classLoader);
        hookBindService();
    }

    private void initFakeBinder() {
        if (fakeServiceBinder != null) return;
        
        fakeServiceBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                try {
                    // ❌ 移除 Token 校验 (V183 成功经验)
                    int startPos = data.dataPosition();
                    try {
                        String token = data.readString();
                        if (token == null || !token.contains("AutoSimilarWidget")) {
                            data.setDataPosition(startPos);
                        }
                    } catch (Exception e) {
                        data.setDataPosition(startPos);
                    }

                    switch (code) {
                        case 4: // 握手
                            systemProvider = data.readStrongBinder(); 
                            if (reply != null) reply.writeNoException(); 
                            isConnected = true;
                            sendJavaBroadcast("✅ 握手成功 (No-Token)");
                            startHeartbeat();
                            if (mainHandler != null) mainHandler.postDelayed(() -> triggerActivationSequence(), 200);
                            return true;
                        
                        case 1: // Surface
                            Surface surface = null;
                            try {
                                int hasSurface = data.readInt();
                                if (hasSurface != 0) surface = Surface.CREATOR.createFromParcel(data);
                                int id = data.readInt(); 
                                sendJavaBroadcast("🎯 收到 Surface! ID=" + id);
                            } catch (Exception e) {}
                            
                            if (reply != null) reply.writeNoException();
                            
                            // 🔥 收到 Surface 是抢焦点的最佳时机
                            triggerActivationSequence(); 
                            
                            if (surface != null) {
                                logSurfaceDetails(surface);
                                startEpochDrawing(surface); 
                            }
                            createOverlayWindow(); 
                            return true;

                        case 2: if (reply != null) reply.writeNoException(); return true;
                        case 3: if (reply != null) { reply.writeNoException(); reply.writeInt(1); } return true;
                        case 5: if (reply != null) reply.writeNoException(); return true;
                        case 1598968902: if (reply != null) reply.writeString(DESCRIPTOR_SERVICE); return true;
                        default: return super.onTransact(code, data, reply, flags);
                    }
                } catch (Throwable e) {
                    sendJavaBroadcast("⚠️ Transact隐忍: " + e.getMessage());
                    return true; 
                }
            }
        };
    }
    
    // 💓 心跳泵：状态 + 焦点 + 数据 + Frame
    private void startHeartbeat() {
        if (heartbeatTimer != null) return;
        sendJavaBroadcast("💓 启动霸道心跳...");
        heartbeatTimer = new Timer();
        heartbeatTimer.schedule(new TimerTask() {
            private int tick = 0;
            @Override
            public void run() {
                notifyFrameDrawn();
                
                int step = tick % 3;
                if (step == 0) {
                    // 刷状态：前台 + 导航
                    injectMapStatusSingle(3); 
                    injectMapStatusSingle(16);
                } else if (step == 1) {
                    // 刷焦点：这是 V185 的核心，每3秒抢一次麦
                    injectFocusCommands();
                } else {
                    // 刷数据
                    injectMapGuideInfo();
                }
                tick++;
            }
        }, 0, 1000); // 1秒一次
    }
    
    // 🚀 激活序列
    private void triggerActivationSequence() {
        new Thread(() -> {
            try {
                sendJavaBroadcast("🚀 激活序列 (Status+Focus)...");
                injectMapStatusSingle(1); 
                Thread.sleep(50);
                injectMapSwitchingInfo(5, 0); 
                Thread.sleep(50);
                injectMapStatusSingle(3); 
                Thread.sleep(50);
                
                // 🔥🔥🔥 关键：连续发送焦点指令
                injectFocusCommands(); 
                Thread.sleep(50);
                
                injectFullStatusSequence();
                injectMapGuideInfo();
            } catch (Throwable t) {}
        }).start();
    }
    
    // 🔥 V185 核心：构造并发送焦点指令
    private void injectFocusCommands() {
        try {
            // 1. 抢占导航焦点 (3912 = RSP_NAVI_GUIDE_FOCUS)
            Object focusObj = createFocusModel(TARGET_VENDOR, PKG_MAP);
            if (focusObj != null) {
                postEvent(3912, focusObj);
                // sendJavaBroadcast("🔫 发送抢焦点指令 (3912)");
            }
            
            // 2. 抢占显示焦点 (3902 = RSP_SHOW_ON_DIM_FOCUS)
            Object showObj = createShowModel(TARGET_VENDOR, PKG_MAP);
            if (showObj != null) {
                postEvent(3902, showObj);
                // sendJavaBroadcast("🔫 发送显示指令 (3902)");
            }
            
            // 3. 设置显示模式 (4101 = RSP_DIM_DISPLAY_MODE)
            // 通常是个基础 BaseMode
            Class<?> baseClass = XposedHelpers.findClass("com.ecarx.sdk.navi.model.base.NaviBaseModel", hostClassLoader);
            Object baseObj = XposedHelpers.newInstance(baseClass);
            setBaseMapVendor(baseObj, TARGET_VENDOR);
            postEvent(4101, baseObj);
            
        } catch (Throwable t) {
            sendJavaBroadcast("❌ FocusError: " + t.getMessage());
        }
    }
    
    private Object createFocusModel(int vendor, String pkg) {
        try {
            // 尝试加载特定类
            Class<?> cls = XposedHelpers.findClassIfExists("com.ecarx.sdk.navi.model.service.RspNaviGuideFocus", hostClassLoader);
            if (cls == null) cls = XposedHelpers.findClassIfExists("com.ecarx.sdk.navi.model.service.RspSpeedLimitFocus", hostClassLoader); // 结构类似
            
            if (cls != null) {
                // 构造函数通常是 (int vendor, String pkg)
                Constructor<?> ctor = cls.getConstructors()[0];
                Object obj;
                if (ctor.getParameterTypes().length == 2) {
                    obj = ctor.newInstance(vendor, pkg);
                } else {
                    obj = ctor.newInstance();
                }
                setBaseMapVendor(obj, vendor);
                return obj;
            }
        } catch (Throwable t) {}
        
        // 降级：用 Base Model
        try {
            Class<?> baseClass = XposedHelpers.findClass("com.ecarx.sdk.navi.model.base.NaviBaseModel", hostClassLoader);
            Object obj = XposedHelpers.newInstance(baseClass);
            setBaseMapVendor(obj, vendor);
            return obj;
        } catch (Throwable t) { return null; }
    }
    
    private Object createShowModel(int vendor, String pkg) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists("com.ecarx.sdk.navi.model.service.RspShowOnDimFocus", hostClassLoader);
            if (cls != null) {
                Constructor<?> ctor = cls.getConstructors()[0];
                Object obj;
                if (ctor.getParameterTypes().length == 2) {
                    obj = ctor.newInstance(vendor, pkg);
                } else {
                    obj = ctor.newInstance();
                }
                setBaseMapVendor(obj, vendor);
                return obj;
            }
        } catch (Throwable t) {}
        return createFocusModel(vendor, pkg); // Fallback
    }

    // 手动强注 (V183 逻辑)
    private void performTrojanInjection() {
        mainHandler.post(() -> {
            try {
                sendJavaBroadcast("🛠️ 执行 V185 强注...");
                Class<?> managerClass = XposedHelpers.findClass(CLASS_AMAP_AIDL_MANAGER, hostClassLoader);
                Object managerInstance = XposedHelpers.getStaticObjectField(managerClass, "e");
                Object connectionObj = XposedHelpers.getObjectField(managerInstance, "f");
                if (connectionObj instanceof ServiceConnection) {
                    ServiceConnection conn = (ServiceConnection) connectionObj;
                    ComponentName fakeCn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
                    conn.onServiceConnected(fakeCn, fakeServiceBinder);
                    sendJavaBroadcast("💉 强注完成");
                    mainHandler.postDelayed(() -> triggerActivationSequence(), 200);
                }
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 强注失败: " + t.getMessage());
            }
        });
    }

    // ... Standard Helpers (hookBind, hookConfig, etc) ...
    private void hookBindService() { try { XposedHelpers.findAndHookMethod("android.content.ContextWrapper", null, "bindService", Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() { @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable { Intent intent = (Intent) param.args[0]; if (intent != null && intent.getComponent() != null && TARGET_SERVICE_IMPL.equals(intent.getComponent().getClassName())) { isConnected = true; param.setResult(true); ServiceConnection conn = (ServiceConnection) param.args[1]; if (conn != null) conn.onServiceConnected(new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL), fakeServiceBinder); } } }); } catch (Throwable t) {} }
    private void hookConfigClasses(ClassLoader cl) { try { Class<?> baseClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG_BASE, cl); if (baseClass != null) XposedHelpers.findAndHookMethod(baseClass, "g", XC_MethodReplacement.returnConstant(true)); } catch (Throwable t) {} }
    private void stopHeartbeat() { if (heartbeatTimer != null) { heartbeatTimer.cancel(); heartbeatTimer = null; sendJavaBroadcast("💔 心跳停止"); } }
    private void notifyFrameDrawn() { if (systemProvider == null) return; try { Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain(); try { systemProvider.transact(1, data, reply, 1); } finally { data.recycle(); reply.recycle(); } } catch (Throwable t) {} }
    private void registerReceiver(Context context) { BroadcastReceiver receiver = new BroadcastReceiver() { @Override public void onReceive(Context ctx, Intent intent) { if ("XSF_ACTION_START_CAST".equals(intent.getAction())) { isConnected = false; stopHeartbeat(); performTrojanInjection(); } } }; context.registerReceiver(receiver, new IntentFilter("XSF_ACTION_START_CAST")); }
    private void setBaseMapVendor(Object instance, int vendor) { try { Class<?> clazz = instance.getClass(); while (clazz != null) { try { Field f = clazz.getDeclaredField("mapVendor"); f.setAccessible(true); f.setInt(instance, vendor); return; } catch (NoSuchFieldException e) { clazz = clazz.getSuperclass(); } } } catch (Throwable t) {} }
    private void postEvent(int type, Object eventObj) { try { Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader); Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class); Object event = eventConstructor.newInstance(type, eventObj); Class<?> busClass = XposedHelpers.findClass(CLASS_EVENT_BUS, hostClassLoader); Object busInstance = XposedHelpers.callStaticMethod(busClass, "a"); XposedHelpers.callMethod(busInstance, "a", event); } catch (Throwable t) {} }
    
    // Data Injectors
    private void injectMapGuideInfo() { try { Class<?> guideClass = XposedHelpers.findClass(CLASS_MAP_GUIDE_INFO, hostClassLoader); Object guideInfo = XposedHelpers.newInstance(guideClass, TARGET_VENDOR); setBaseMapVendor(guideInfo, TARGET_VENDOR); XposedHelpers.setObjectField(guideInfo, "curRoadName", "V185霸道"); XposedHelpers.setObjectField(guideInfo, "nextRoadName", "抢焦点"); XposedHelpers.setIntField(guideInfo, "turnId", 2); XposedHelpers.setIntField(guideInfo, "nextTurnDistance", 888); XposedHelpers.setIntField(guideInfo, "guideType", 1); XposedHelpers.setBooleanField(guideInfo, "isCustomTBTEnabled", true); postEvent(1002, guideInfo); } catch (Throwable t) {} }
    private void injectFullStatusSequence() throws Exception { int[] statuses = {1, 3, 7, 8, 12, 13, 14, 16}; for (int s : statuses) { injectMapStatusSingle(s); Thread.sleep(30); } }
    private void injectMapStatusSingle(int status) { try { Class<?> statusClass = XposedHelpers.findClass(CLASS_MAP_STATUS_INFO, hostClassLoader); Object info = XposedHelpers.newInstance(statusClass, TARGET_VENDOR); setBaseMapVendor(info, TARGET_VENDOR); XposedHelpers.setIntField(info, "status", status); postEvent(1001, info); if (status == 16) postEvent(2002, info); } catch (Exception e) {} }
    private void injectMapSwitchingInfo(int oldV, int newV) { try { Class<?> switchClass = XposedHelpers.findClass(CLASS_MAP_SWITCHING_INFO, hostClassLoader); Object switchInfo = XposedHelpers.newInstance(switchClass, oldV, newV); setBaseMapVendor(switchInfo, TARGET_VENDOR); XposedHelpers.setIntField(switchInfo, "mSwitchState", 3); postEvent(2003, switchInfo); } catch (Throwable t) {} }

    private void startEpochDrawing(Surface surface) {
        if (!surface.isValid()) return;
        final long myEpoch = ++drawEpoch;
        new Thread(() -> {
            sendJavaBroadcast("🎨 启动闪烁绘制...");
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
                    // 🔥 闪烁：红 -> 绿 -> 蓝
                    if (frame % 3 == 0) c.drawColor(Color.RED);
                    else if (frame % 3 == 1) c.drawColor(Color.GREEN);
                    else c.drawColor(Color.BLUE);
                    
                    c.drawText("V185 Tyrant", 50, 150, paint);
                    surface.unlockCanvasAndPost(c);
                }
                frame++;
                try { Thread.sleep(500); } catch (Exception e) {}
            }
        }).start();
    }
    
    private void logSurfaceDetails(Surface s) { String info = "Valid=" + s.isValid() + ", Hash=" + System.identityHashCode(s); sendJavaBroadcast("🏥 " + info); }
    private void createOverlayWindow() { /* 省略，同上 */ }
    private void sendJavaBroadcast(String log) { if (systemContext == null) return; new Thread(() -> { try { Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE"); i.setPackage(PKG_SELF); i.putExtra("log", log); i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES); try { Object userAll = XposedHelpers.getStaticObjectField(UserHandle.class, "ALL"); Method method = Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class); method.invoke(systemContext, i, userAll); } catch (Throwable t) { systemContext.sendBroadcast(i); } } catch (Throwable t) {} }).start(); }
}