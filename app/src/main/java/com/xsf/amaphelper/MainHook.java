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
import android.view.Gravity; // ✅ 修复：补全缺失引用
import android.view.Surface;
import android.view.WindowManager;
import android.widget.TextView;
import java.lang.reflect.Constructor;
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

    // 🎯 核心类
    private static final String CLASS_AMAP_AIDL_MANAGER = "ecarx.naviservice.map.amap.h";
    private static final String CLASS_MAP_MANAGER = "ecarx.naviservice.map.cf";
    private static final String CLASS_MAP_CONFIG_BASE = "ecarx.naviservice.map.co"; 
    private static final String CLASS_MAP_CONFIG_WRAPPER = "ecarx.naviservice.map.ce"; 
    
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";
    
    // 🎯 实体类
    private static final String CLASS_EVENT_BUS = "ecarx.naviservice.d.e";
    private static final String CLASS_MAP_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLASS_MAP_SWITCHING_INFO = "ecarx.naviservice.map.entity.MapSwitchingInfo";
    private static final String CLASS_MAP_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLASS_MAP_EVENT = "ecarx.naviservice.map.bz";

    private static final String DESCRIPTOR_SERVICE = "com.autosimilarwidget.view.IAutoSimilarWidgetViewService";
    private static final String DESCRIPTOR_PROVIDER = "com.autosimilarwidget.view.IAutoWidgetStateProvider";

    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Binder fakeServiceBinder = null;
    private static ClassLoader hostClassLoader = null;
    private static Presentation clusterWindow = null;
    
    private static volatile long drawEpoch = 0;
    private static volatile int currentDynamicVendor = 5; 
    
    private static boolean injectFailedOnce = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        hostClassLoader = lpparam.classLoader;
        XposedBridge.log("NaviHook: 🚀 V159 鉴权解锁版启动");

        // 1. 获取 Context
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder(); 
                registerReceiver(systemContext);
                sendJavaBroadcast("⚡ V159 就绪");
                mainHandler.postDelayed(() -> performActiveInjection(), 3000);
            }
        });

        // 2. 基础 Vendor Hook
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
        
        // 3. 解锁鉴权配置 (co.g() -> true)
        hookConfigClasses(lpparam.classLoader);
        
        // 4. 拦截 bindService
        hookBindService();
    }
    
    private void hookConfigClasses(ClassLoader cl) {
        try {
            Class<?> baseClass = XposedHelpers.findClassIfExists(CLASS_MAP_CONFIG_BASE, cl);
            if (baseClass != null) {
                XposedHelpers.findAndHookMethod(baseClass, "g", XC_MethodReplacement.returnConstant(true));
                XposedBridge.log("NaviHook: 🔓 已解锁 MapConfigBase.g()");
            }
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: ConfigBase Hook Error: " + t);
        }
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
                            triggerActivationSequence();
                            
                            if (provider != null) notifyFrameDrawnAsync(provider);
                            return true;
                        
                        case 1: // addSurface
                            Surface surface = null;
                            int hasSurface = data.readInt();
                            if (hasSurface != 0) surface = Surface.CREATOR.createFromParcel(data);
                            int id = data.readInt(); 
                            if (reply != null) reply.writeNoException();
                            
                            sendJavaBroadcast("🎯 收到 Surface! ID=" + id);
                            
                            if (currentDynamicVendor != 0) triggerActivationSequence();
                            
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
                            currentDynamicVendor = 5; 
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
                    return super.onTransact(code, data, reply, flags);
                }
            }
        };
    }
    
    private void triggerActivationSequence() {
        if (currentDynamicVendor == 0) return;
        currentDynamicVendor = 0;
        sendJavaBroadcast("🔀 Vendor -> 0 (激活序列启动)");
        
        new Thread(() -> {
            try {
                injectMapSwitchingInfo();
                Thread.sleep(200);
                injectFullStatusSequence();
                injectMapGuideInfo();
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 序列异常: " + t.getMessage());
            }
        }).start();
    }

    private void injectMapGuideInfo() {
        try {
            Class<?> guideClass = XposedHelpers.findClass(CLASS_MAP_GUIDE_INFO, hostClassLoader);
            if (guideClass == null) return;
            
            Object guideInfo = XposedHelpers.newInstance(guideClass, 0);
            try { XposedHelpers.setObjectField(guideInfo, "curRoadName", "V159洋红色"); } catch (Throwable t) {}
            try { XposedHelpers.setObjectField(guideInfo, "nextRoadName", "即将点亮"); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "turnId", 2); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "nextTurnDistance", 500); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "remainDistance", 1000); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "remainTime", 60); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "guideType", 1); } catch (Throwable t) {}
            try { XposedHelpers.setBooleanField(guideInfo, "isCustomTBTEnabled", true); } catch (Throwable t) {}

            Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader);
            Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class);
            postEvent(eventConstructor.newInstance(1002, guideInfo));
            
        } catch (Throwable t) {
            sendJavaBroadcast("❌ GuideInfo 失败: " + t.getMessage());
        }
    }
    
    private void injectFullStatusSequence() throws Exception {
        Class<?> statusClass = XposedHelpers.findClass(CLASS_MAP_STATUS_INFO, hostClassLoader);
        Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader);
        Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class);

        int[] statuses = {7, 8, 12, 13, 14, 16}; 
        for (int s : statuses) {
            Object info = XposedHelpers.newInstance(statusClass, 0); 
            XposedHelpers.setIntField(info, "status", s);
            postEvent(eventConstructor.newInstance(1001, info));
            if (s == 16) postEvent(eventConstructor.newInstance(2002, info));
            Thread.sleep(100);
        }
    }
    
    private void injectMapSwitchingInfo() {
        try {
            Class<?> switchClass = XposedHelpers.findClass(CLASS_MAP_SWITCHING_INFO, hostClassLoader);
            if (switchClass == null) return;
            
            Object switchInfo = XposedHelpers.newInstance(switchClass, 5, 0);
            XposedHelpers.setIntField(switchInfo, "mSwitchState", 3); 
            
            Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader);
            Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class);
            postEvent(eventConstructor.newInstance(2003, switchInfo));
            
        } catch (Throwable t) {
            sendJavaBroadcast("❌ SwitchingInfo 失败: " + t.getMessage());
        }
    }
    
    private void postEvent(Object event) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLASS_EVENT_BUS, hostClassLoader);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            XposedHelpers.callMethod(busInstance, "a", event);
        } catch (Throwable t) {}
    }

    private void startEpochDrawing(Surface surface) {
        if (!surface.isValid()) return;
        final long myEpoch = ++drawEpoch;
        new Thread(() -> {
            sendJavaBroadcast("🎨 启动洋红色绘制...");
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
                    c.drawColor(Color.MAGENTA); 
                    c.drawText("V159 UNVEIL", 50, 150, paint);
                    c.drawText("Vendor: " + currentDynamicVendor, 50, 250, paint);
                    surface.unlockCanvasAndPost(c);
                    if (frame == 1) sendJavaBroadcast("✅ 绘制成功 (洋红)");
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

    private void performActiveInjection() {
        mainHandler.post(() -> {
            try {
                Class<?> hClass = XposedHelpers.findClass(CLASS_AMAP_AIDL_MANAGER, hostClassLoader);
                Object hInstance = XposedHelpers.getStaticObjectField(hClass, "e"); 
                if (hInstance == null) return;
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
                        tv.setText("V159-Unveil");
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