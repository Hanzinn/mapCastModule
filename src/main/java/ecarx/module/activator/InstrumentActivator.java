package ecarx.module.activator;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;

import ecarx.module.protocol.AMapBroadcastProtocol;
import ecarx.module.utils.DebugLogger;

/**
 * 仪表激活器
 * 负责激活仪表投屏功能，建立与XSFNaviService的连接
 */
public class InstrumentActivator {
    
    private static final String TAG = "InstrumentActivator";
    
    // 激活状态
    private static boolean isActivated = false;
    private static boolean isServiceBound = false;
    private static boolean isInstrumentReady = false;
    
    // 服务连接
    private static ServiceConnection naviServiceConnection;
    private static Object naviServiceStub;
    private static Object instrumentService;
    
    // 激活配置
    private static final int DEFAULT_ENGINE_ID = 3;
    private static final int DEFAULT_MAP_MODE = 2; // 3D车上
    private static final int DEFAULT_MAP_POSITION = 2; // 居中
    private static final int DEFAULT_MAP_LEVEL = 17; // 最大比例尺
    
    /**
     * 激活仪表投屏功能
     */
    public static void activate(Context context) {
        if (isActivated) {
            DebugLogger.log("仪表已经激活");
            return;
        }
        
        try {
            DebugLogger.log("开始激活仪表投屏功能");
            
            // 1. 启动XSFNaviService
            startNaviService(context);
            
            // 2. 绑定导航服务
            bindNaviService(context);
            
            // 3. 初始化仪表
            initializeInstrument(context);
            
            // 4. 配置多屏显示
            configureExternalScreen(context);
            
            // 5. 发送激活广播
            sendActivationBroadcast(context);
            
            // 6. 注册状态监听器
            registerStateListener();
            
            isActivated = true;
            DebugLogger.log("仪表激活成功");
            
        } catch (Exception e) {
            Log.e(TAG, "仪表激活失败", e);
            DebugLogger.log("仪表激活失败: " + e.getMessage());
        }
    }
    
    /**
     * 停止仪表投屏功能
     */
    public static void deactivate(Context context) {
        try {
            if (!isActivated) {
                return;
            }
            
            DebugLogger.log("开始停止仪表投屏功能");
            
            // 1. 取消状态监听
            unregisterStateListener();
            
            // 2. 停止服务
            if (isServiceBound && naviServiceConnection != null) {
                context.unbindService(naviServiceConnection);
                isServiceBound = false;
            }
            
            // 3. 重置状态
            isActivated = false;
            isInstrumentReady = false;
            naviServiceConnection = null;
            naviServiceStub = null;
            instrumentService = null;
            
            DebugLogger.log("仪表停止成功");
            
        } catch (Exception e) {
            Log.e(TAG, "停止仪表失败", e);
            DebugLogger.log("停止仪表失败: " + e.getMessage());
        }
    }
    
    /**
     * 启动导航服务
     */
    private static void startNaviService(Context context) {
        try {
            Intent serviceIntent = new Intent();
            ComponentName component = new ComponentName(
                "ecarx.naviservice",
                "ecarx.naviservice.service.NaviService"
            );
            serviceIntent.setComponent(component);
            serviceIntent.setAction("ecarx.intent.action.NAVI_SERVICE_STARTED");
            serviceIntent.addCategory("ecarx.intent.category.NAVI_INNER");
            
            context.startService(serviceIntent);
            DebugLogger.log("导航服务已启动");
            
        } catch (Exception e) {
            Log.e(TAG, "启动导航服务失败", e);
            throw new RuntimeException("启动导航服务失败", e);
        }
    }
    
    /**
     * 绑定导航服务
     */
    private static void bindNaviService(final Context context) {
        try {
            Intent intent = new Intent("ecarx.naviservice.IEASNaviServer");
            intent.setPackage("ecarx.naviservice");
            
            naviServiceConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    try {
                        DebugLogger.log("导航服务已连接");
                        
                        // 获取服务接口
                        Class<?> stubClass = Class.forName("ecarx.naviservice.IEASNaviServer$Stub");
                        Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                        instrumentService = asInterface.invoke(null, service);
                        
                        isServiceBound = true;
                        
                        // 注册导航观察者
                        registerNaviObserver();
                        
                        DebugLogger.log("导航服务绑定成功");
                        
                    } catch (Exception e) {
                        Log.e(TAG, "绑定导航服务失败", e);
                        DebugLogger.log("绑定导航服务失败: " + e.getMessage());
                    }
                }
                
                @Override
                public void onServiceDisconnected(ComponentName name) {
                    DebugLogger.log("导航服务已断开");
                    isServiceBound = false;
                    instrumentService = null;
                }
            };
            
            boolean bound = context.bindService(intent, naviServiceConnection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                throw new RuntimeException("绑定导航服务失败");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "绑定导航服务失败", e);
            throw new RuntimeException("绑定导航服务失败", e);
        }
    }
    
    /**
     * 初始化仪表
     */
    private static void initializeInstrument(Context context) {
        try {
            // 发送初始化广播
            Intent initIntent = new Intent("AUTONAVI_STANDARD_BROADCAST_RECV");
            initIntent.putExtra("KEY_TYPE", 10034); // 进入主图
            initIntent.putExtra("SOURCE_APP", "InstrumentActivator");
            initIntent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(initIntent);
            
            DebugLogger.log("仪表初始化完成");
            
        } catch (Exception e) {
            Log.e(TAG, "初始化仪表失败", e);
            throw new RuntimeException("初始化仪表失败", e);
        }
    }
    
    /**
     * 配置多屏显示
     */
    private static void configureExternalScreen(Context context) {
        try {
            // 发送多屏显示配置
            AMapBroadcastProtocol.sendExternalScreenConfig(
                context,
                DEFAULT_ENGINE_ID,
                DEFAULT_MAP_MODE,
                DEFAULT_MAP_POSITION,
                DEFAULT_MAP_LEVEL
            );
            
            // 启用路口大图
            AMapBroadcastProtocol.sendCrossImageControl(
                context,
                DEFAULT_ENGINE_ID,
                true
            );
            
            // 设置路口大图类型为矢量图
            AMapBroadcastProtocol.sendCrossImageType(
                context,
                DEFAULT_ENGINE_ID,
                1 // 矢量图
            );
            
            DebugLogger.log("多屏显示配置完成");
            
        } catch (Exception e) {
            Log.e(TAG, "配置多屏显示失败", e);
            throw new RuntimeException("配置多屏显示失败", e);
        }
    }
    
    /**
     * 发送激活广播
     */
    private static void sendActivationBroadcast(Context context) {
        try {
            // 发送激活广播到高德地图
            Intent activateIntent = new Intent("AUTONAVI_STANDARD_BROADCAST_RECV");
            activateIntent.putExtra("KEY_TYPE", 10104); // 多屏显示设置
            activateIntent.putExtra("EXTRA_EXTERNAL_ENGINE_ID", DEFAULT_ENGINE_ID);
            activateIntent.putExtra("EXTRA_EXTERNAL_MAP_MODE", DEFAULT_MAP_MODE);
            activateIntent.putExtra("EXTRA_EXTERNAL_MAP_POSITION", DEFAULT_MAP_POSITION);
            activateIntent.putExtra("EXTRA_EXTERNAL_MAP_LEVEL", DEFAULT_MAP_LEVEL);
            activateIntent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(activateIntent);
            
            // 发送心跳
            AMapBroadcastProtocol.sendHeartbeat(context, 1);
            
            DebugLogger.log("激活广播已发送");
            
        } catch (Exception e) {
            Log.e(TAG, "发送激活广播失败", e);
            throw new RuntimeException("发送激活广播失败", e);
        }
    }
    
    /**
     * 注册导航观察者
     */
    private static void registerNaviObserver() {
        if (instrumentService == null) {
            DebugLogger.log("导航服务未连接，无法注册观察者");
            return;
        }
        
        try {
            // 创建导航观察者
            Class<?> observerClass = Class.forName("ecarx.naviservice.IEASNaviObserver");
            Object observer = java.lang.reflect.Proxy.newProxyInstance(
                observerClass.getClassLoader(),
                new Class[]{observerClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                        if ("onNaviInfoChanged".equals(method.getName()) && args != null && args.length > 0) {
                            // 处理导航信息变化
                            handleNaviInfoChanged(args[0]);
                        }
                        return null;
                    }
                }
            );
            
            // 注册观察者
            Method registerNaviObserver = instrumentService.getClass().getMethod("registerNaviObserver", observerClass);
            registerNaviObserver.invoke(instrumentService, observer);
            
            DebugLogger.log("导航观察者已注册");
            
        } catch (Exception e) {
            Log.e(TAG, "注册导航观察者失败", e);
            DebugLogger.log("注册导航观察者失败: " + e.getMessage());
        }
    }
    
    /**
     * 注册状态监听器
     */
    private static void registerStateListener() {
        try {
            // 创建状态监听器
            Class<?> patternListenerClass = Class.forName("ecarx.decision.IPatternListener");
            Object patternListener = java.lang.reflect.Proxy.newProxyInstance(
                patternListenerClass.getClassLoader(),
                new Class[]{patternListenerClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                        if ("onPatternStateChanged".equals(method.getName()) && args != null && args.length > 0) {
                            // 处理状态变化
                            handlePatternStateChanged(args[0]);
                        }
                        return null;
                    }
                }
            );
            
            // 注册状态监听器
            if (instrumentService != null) {
                Method registerObserver = instrumentService.getClass().getMethod("registerPatternListener", patternListenerClass);
                registerObserver.invoke(instrumentService, patternListener);
                DebugLogger.log("状态监听器已注册");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "注册状态监听器失败", e);
            DebugLogger.log("注册状态监听器失败: " + e.getMessage());
        }
    }
    
    /**
     * 取消状态监听
     */
    private static void unregisterStateListener() {
        try {
            if (instrumentService != null) {
                // 取消导航观察者
                Method unregisterNaviObserver = instrumentService.getClass().getMethod("unregisterNaviObserver");
                unregisterNaviObserver.invoke(instrumentService);
                DebugLogger.log("导航观察者已取消");
            }
        } catch (Exception e) {
            Log.e(TAG, "取消状态监听失败", e);
        }
    }
    
    /**
     * 处理导航信息变化
     */
    private static void handleNaviInfoChanged(Object naviInfo) {
        try {
            DebugLogger.log("导航信息已更新");
            
            // 提取导航信息
            Map<String, Object> naviData = extractNaviInfo(naviInfo);
            
            // 发送到仪表
            sendNaviDataToInstrument(naviData);
            
        } catch (Exception e) {
            Log.e(TAG, "处理导航信息变化失败", e);
        }
    }
    
    /**
     * 处理状态变化
     */
    private static void handlePatternStateChanged(Object patternState) {
        try {
            DebugLogger.log("状态已变化");
            
            // 提取状态信息
            Map<String, Object> stateData = extractPatternState(patternState);
            
            // 根据状态类型处理
            String stateType = (String) stateData.get("StateType");
            if (stateType != null) {
                switch (stateType) {
                    case "NAVI_START":
                        handleNaviStart(stateData);
                        break;
                    case "NAVI_END":
                        handleNaviEnd(stateData);
                        break;
                    case "CURSE_START":
                        handleCurseStart(stateData);
                        break;
                    case "CURSE_END":
                        handleCurseEnd(stateData);
                        break;
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "处理状态变化失败", e);
        }
    }
    
    /**
     * 处理导航开始
     */
    private static void handleNaviStart(Map<String, Object> data) {
        DebugLogger.log("导航开始");
        isInstrumentReady = true;
        
        // 发送导航开始状态到仪表
        AMapBroadcastProtocol.sendNaviStatus(null, 0x1C);
    }
    
    /**
     * 处理导航结束
     */
    private static void handleNaviEnd(Map<String, Object> data) {
        DebugLogger.log("导航结束");
        isInstrumentReady = false;
        
        // 发送导航结束状态到仪表
        AMapBroadcastProtocol.sendNaviStatus(null, 0x21);
    }
    
    /**
     * 处理巡航开始
     */
    private static void handleCurseStart(Map<String, Object> data) {
        DebugLogger.log("巡航开始");
        isInstrumentReady = true;
        
        // 发送巡航开始状态到仪表
        AMapBroadcastProtocol.sendNaviStatus(null, 0x1E);
    }
    
    /**
     * 处理巡航结束
     */
    private static void handleCurseEnd(Map<String, Object> data) {
        DebugLogger.log("巡航结束");
        isInstrumentReady = false;
        
        // 发送巡航结束状态到仪表
        AMapBroadcastProtocol.sendNaviStatus(null, 0x21);
    }
    
    /**
     * 提取导航信息
     */
    private static Map<String, Object> extractNaviInfo(Object naviInfo) {
        Map<String, Object> data = new java.util.HashMap<>();
        
        try {
            // 使用反射提取导航信息
            Method[] methods = naviInfo.getClass().getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    try {
                        Object value = method.invoke(naviInfo);
                        if (value != null) {
                            String key = method.getName().substring(3);
                            data.put(key, value);
                        }
                    } catch (Exception e) {
                        // 忽略提取失败的字段
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "提取导航信息失败", e);
        }
        
        return data;
    }
    
    /**
     * 提取状态信息
     */
    private static Map<String, Object> extractPatternState(Object patternState) {
        Map<String, Object> data = new java.util.HashMap<>();
        
        try {
            // 使用反射提取状态信息
            Method[] methods = patternState.getClass().getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    try {
                        Object value = method.invoke(patternState);
                        if (value != null) {
                            String key = method.getName().substring(3);
                            data.put(key, value);
                        }
                    } catch (Exception e) {
                        // 忽略提取失败的字段
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "提取状态信息失败", e);
        }
        
        return data;
    }
    
    /**
     * 发送导航数据到仪表
     */
    private static void sendNaviDataToInstrument(Map<String, Object> naviData) {
        try {
            if (instrumentService != null) {
                // 调用服务方法发送数据到仪表
                Method sendData = instrumentService.getClass().getMethod("sendDataToInstrument", Object.class);
                sendData.invoke(instrumentService, naviData);
                DebugLogger.log("导航数据已发送到仪表");
            }
        } catch (Exception e) {
            Log.e(TAG, "发送导航数据到仪表失败", e);
        }
    }
    
    /**
     * 检查仪表是否已激活
     */
    public static boolean isActivated() {
        return isActivated && isServiceBound && isInstrumentReady;
    }
    
    /**
     * 获取激活状态
     */
    public static String getActivationStatus() {
        StringBuilder status = new StringBuilder();
        status.append("激活状态: ").append(isActivated ? "已激活" : "未激活").append("\n");
        status.append("服务绑定: ").append(isServiceBound ? "已绑定" : "未绑定").append("\n");
        status.append("仪表就绪: ").append(isInstrumentReady ? "已就绪" : "未就绪");
        return status.toString();
    }
    
    /**
     * 重新激活仪表
     */
    public static void reactivate(Context context) {
        if (isActivated) {
            deactivate(context);
        }
        activate(context);
    }
}