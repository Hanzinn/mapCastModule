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
    
    // ⚠️ 9.1 缺失的组件，我们要伪造它
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";

    private static Context systemContext = null;
    private static Handler mainHandler = null;
    private static Binder fakeServiceBinder = null;
    private static ClassLoader hostClassLoader = null;
    private static Presentation clusterWindow = null;
    
    private static volatile long drawEpoch = 0;
    
    // 🦎 默认 5，激活时变 0
    private static volatile int currentDynamicVendor = 5; 

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        hostClassLoader = lpparam.classLoader;
        XposedBridge.log("NaviHook: 🚀 V165 幽灵服务版启动");

        // 1. 获取 Context
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                systemContext = (Context) param.thisObject;
                mainHandler = new Handler(Looper.getMainLooper());
                initFakeBinder(); 
                registerReceiver(systemContext);
                sendJavaBroadcast("⚡ V165 就绪 (无视地图版本)");
                // 3秒后尝试强制触发一次连接逻辑(针对9.1可能不主动连接的情况)
                mainHandler.postDelayed(() -> forceTriggerBind(), 5000);
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
        
        // 4. 🔥 核心：拦截 Bind 请求，即使目标不存在
        hookBindService();
    }
    
    // 🔥 强制让系统以为它应该连接 (如果系统因为找不到包而放弃连接，我们可以在这里手动推一把)
    // 但更重要的是下面的 hookBindService
    private void forceTriggerBind() {
        // V165 策略：主要依赖 Hook 系统发出的 Bind 请求。
        // 如果系统完全不发 Bind (因为检测到包不存在)，我们需要 Hook 系统检测包的代码，但这比较复杂。
        // 先假设系统会尝试 Bind，只是会失败。
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
                            IBinder provider = data.readStrongBinder(); 
                            if (reply != null) reply.writeNoException(); 
                            
                            sendJavaBroadcast("✅ 幽灵握手成功 (Step 1)");
                            
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
                            
                            if (currentDynamicVendor != 0) triggerHandoverSequence();
                            
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
                            
                            sendJavaBroadcast("♻️ Surface移除");
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
    
    // 🔥 V165 核心：拦截系统发出的 bindService
    // 即使 9.1 没有这个服务，我们拦截下来，假装有，并返回我们的 binder
    private void hookBindService() {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", null, "bindService",
                Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent == null || intent.getComponent() == null) return;

                String className = intent.getComponent().getClassName();
                
                // 只要是针对高德投屏服务的请求，一律拦截！
                if (TARGET_SERVICE_IMPL.equals(className) || 
                    "com.autonavi.amapauto".equals(intent.getPackage())) {
                    
                    if (TARGET_SERVICE_IMPL.equals(className)) {
                        sendJavaBroadcast("👻 捕获绑定请求 (AutoSimilarWidgetService)");
                        
                        // 1. 阻止系统真正的 bind (因为真的 bind 会失败)
                        param.setResult(true); 
                        
                        // 2. 手动触发 onServiceConnected，把我们的 FakeBinder 塞回去
                        // 这就是“欺骗”系统的关键一步
                        final ServiceConnection conn = (ServiceConnection) param.args[1];
                        if (conn != null && fakeServiceBinder != null) {
                             if (mainHandler != null) {
                                 mainHandler.post(() -> {
                                     try {
                                         ComponentName cn = new ComponentName(PKG_MAP, TARGET_SERVICE_IMPL);
                                         conn.onServiceConnected(cn, fakeServiceBinder);
                                         sendJavaBroadcast("👻 幽灵连接已建立");
                                     } catch (Throwable t) {
                                         sendJavaBroadcast("❌ 连接回调失败: " + t.getMessage());
                                     }
                                 });
                             }
                        }
                    }
                }
            }
        });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Bind Hook Error: " + t);
        }
    }
    
    private void triggerHandoverSequence() {
        if (currentDynamicVendor == 0) return;
        
        new Thread(() -> {
            try {
                sendJavaBroadcast("🚀 启动交接 (5->0)...");
                injectMapSwitchingInfo(5, 0);
                Thread.sleep(200);
                currentDynamicVendor = 0;
                sendJavaBroadcast("🦎 身份已变更为 0");
                injectFullStatusSequence();
                injectMapGuideInfo();
            } catch (Throwable t) {
                sendJavaBroadcast("❌ 交接失败: " + t.getMessage());
            }
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
            
            try { XposedHelpers.setObjectField(guideInfo, "curRoadName", "V165幽灵版"); } catch (Throwable t) {}
            try { XposedHelpers.setObjectField(guideInfo, "nextRoadName", "无需旧版高德"); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "turnId", 2); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "nextTurnDistance", 999); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "remainDistance", 2000); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "remainTime", 120); } catch (Throwable t) {}
            try { XposedHelpers.setIntField(guideInfo, "guideType", 1); } catch (Throwable t) {}
            try { XposedHelpers.setBooleanField(guideInfo, "isCustomTBTEnabled", true); } catch (Throwable t) {}

            Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader);
            Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class);
            postEvent(eventConstructor.newInstance(1002, guideInfo));
            sendJavaBroadcast("✅ GuideInfo 已发");
            
        } catch (Throwable t) {
            sendJavaBroadcast("❌ GuideInfo 失败: " + t.getMessage());
        }
    }
    
    private void injectFullStatusSequence() throws Exception {
        Class<?> statusClass = XposedHelpers.findClass(CLASS_MAP_STATUS_INFO, hostClassLoader);
        Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader);
        Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class);

        int[] statuses = {7, 8, 12, 13, 14, 16}; 
        sendJavaBroadcast("💉 注入状态流 (7->16)...");
        
        for (int s : statuses) {
            Object info = XposedHelpers.newInstance(statusClass, 0); 
            setBaseMapVendor(info, 0);
            XposedHelpers.setIntField(info, "status", s);
            
            postEvent(eventConstructor.newInstance(1001, info));
            if (s == 16) postEvent(eventConstructor.newInstance(2002, info));
            Thread.sleep(80);
        }
    }
    
    private void injectMapSwitchingInfo(int oldV, int newV) {
        try {
            Class<?> switchClass = XposedHelpers.findClass(CLASS_MAP_SWITCHING_INFO, hostClassLoader);
            if (switchClass == null) return;
            
            Object switchInfo = XposedHelpers.newInstance(switchClass, oldV, newV);
            setBaseMapVendor(switchInfo, 0);
            
            XposedHelpers.setIntField(switchInfo, "mSwitchState", 3); 
            
            Class<?> eventClass = XposedHelpers.findClass(CLASS_MAP_EVENT, hostClassLoader);
            Constructor<?> eventConstructor = eventClass.getConstructor(int.class, Object.class);
            postEvent(eventConstructor.newInstance(2003, switchInfo));
            
            sendJavaBroadcast("🚀 切换指令 (" + oldV + "->" + newV + ") 已发");
            
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
                    c.drawColor(Color.GREEN); // 绿色 = 通行
                    c.drawText("V165 Ghost", 50, 150, paint);
                    c.drawText("Vendor: " + currentDynamicVendor, 50, 250, paint);
                    surface.unlockCanvasAndPost(c);
                    if (frame == 1) sendJavaBroadcast("✅ 绘制成功 (绿色)");
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

    private void registerReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) { }
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
                        tv.setText("V165-Ghost");
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