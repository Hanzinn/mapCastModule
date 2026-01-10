package ecarx.module;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ecarx.module.activator.InstrumentActivator;
import ecarx.module.converter.NaviDataConverter;
import ecarx.module.protocol.AMapBroadcastProtocol;
import ecarx.module.utils.DebugLogger;

/**
 * 高德地图投屏启用模块
 * 通过LSPosed框架Hook高德地图9.1版本，启用对车机仪表的投屏功能
 */
public class AMapProjectionModule implements IXposedHookLoadPackage {
    
    private static final String TAG = "AMapProjection";
    private static final String AMAP_PACKAGE = "com.autonavi.amapauto";
    private static final String XSF_PACKAGE = "ecarx.naviservice";
    
    // 投屏状态
    private static boolean isProjectionEnabled = false;
    private static boolean isNaviActive = false;
    private static boolean isCurseActive = false;
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        
        DebugLogger.log("Package loaded: " + lpparam.packageName);
        
        if (lpparam.packageName.equals(AMAP_PACKAGE)) {
            // Hook高德地图
            hookAMap(lpparam);
            DebugLogger.log("高德地图Hook完成");
        }
        
        if (lpparam.packageName.equals(XSF_PACKAGE)) {
            // Hook XSFNaviService
            hookXSFService(lpparam);
            DebugLogger.log("XSFNaviService Hook完成");
        }
    }
    
    /**
     * Hook高德地图相关方法
     */
    private void hookAMap(XC_LoadPackage.LoadPackageParam lpparam) {
        
        // 1. Hook广播发送，强制启用投屏
        hookBroadcastSend(lpparam);
        
        // 2. Hook导航信息获取
        hookNaviInfoMethods(lpparam);
        
        // 3. Hook投屏权限检查
        hookProjectionPermission(lpparam);
        
        // 4. Hook导航状态变化
        hookNaviStateChange(lpparam);
        
        // 5. Hook巡航状态变化
        hookCurseStateChange(lpparam);
    }
    
    /**
     * Hook XSFNaviService相关方法
     */
    private void hookXSFService(XC_LoadPackage.LoadPackageParam lpparam) {
        
        // 1. Hook服务启动，确保激活仪表
        hookServiceStart(lpparam);
        
        // 2. Hook数据发送方法
        hookDataTransmission(lpparam);
        
        // 3. Hook仪表激活状态
        hookInstrumentActivation(lpparam);
    }
    
    /**
     * Hook广播发送方法
     */
    private void hookBroadcastSend(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper",
                lpparam.classLoader,
                "sendBroadcast",
                Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Intent intent = (Intent) param.args[0];
                        if (intent != null && 
                            "AUTONAVI_STANDARD_BROADCAST_SEND".equals(intent.getAction())) {
                            
                            DebugLogger.logIntent("高德发送广播", intent);
                            
                            // 修改广播，强制启用投屏功能
                            modifyBroadcastForProjection(intent);
                        }
                    }
                }
            );
            
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper",
                lpparam.classLoader,
                "sendBroadcast",
                Intent.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Intent intent = (Intent) param.args[0];
                        if (intent != null && 
                            "AUTONAVI_STANDARD_BROADCAST_SEND".equals(intent.getAction())) {
                            
                            // 修改广播，强制启用投屏功能
                            modifyBroadcastForProjection(intent);
                        }
                    }
                }
            );
            
        } catch (Throwable t) {
            DebugLogger.log("Hook广播发送失败: " + t.getMessage());
        }
    }
    
    /**
     * Hook导航信息获取方法
     */
    private void hookNaviInfoMethods(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook导航信息获取
            XposedHelpers.findAndHookMethod("com.autonavi.auto.modules.navi.NaviManager",
                lpparam.classLoader,
                "getNaviInfo",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object naviInfo = param.getResult();
                        if (naviInfo != null) {
                            // 转换导航信息并发送到仪表
                            convertAndSendNaviInfo(naviInfo);
                        }
                    }
                }
            );
            
            // Hook导航引导信息
            XposedHelpers.findAndHookMethod("com.autonavi.auto.modules.navi.NaviManager",
                lpparam.classLoader,
                "getGuideInfo",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object guideInfo = param.getResult();
                        if (guideInfo != null) {
                            // 转换引导信息并发送到仪表
                            convertAndSendGuideInfo(guideInfo);
                        }
                    }
                }
            );
            
        } catch (Throwable t) {
            DebugLogger.log("Hook导航信息获取失败: " + t.getMessage());
        }
    }
    
    /**
     * Hook投屏权限检查
     */
    private void hookProjectionPermission(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook投屏权限检查方法
            XposedHelpers.findAndHookMethod("com.autonavi.auto.modules.screen.ScreenManager",
                lpparam.classLoader,
                "checkScreenProjectionPermission",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        DebugLogger.log("绕过投屏权限检查");
                        return true; // 绕过权限检查
                    }
                }
            );
            
            // Hook多屏显示权限检查
            XposedHelpers.findAndHookMethod("com.autonavi.auto.modules.screen.ScreenManager",
                lpparam.classLoader,
                "isExternalScreenSupported",
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return true; // 强制支持多屏显示
                    }
                }
            );
            
        } catch (Throwable t) {
            DebugLogger.log("Hook投屏权限检查失败: " + t.getMessage());
        }
    }
    
    /**
     * Hook导航状态变化
     */
    private void hookNaviStateChange(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.autonavi.auto.modules.navi.NaviManager",
                lpparam.classLoader,
                "onNaviStart",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        isNaviActive = true;
                        DebugLogger.log("导航开始");
                        
                        // 发送导航开始状态到仪表
                        sendNaviStateToInstrument(0x1C);
                    }
                }
            );
            
            XposedHelpers.findAndHookMethod("com.autonavi.auto.modules.navi.NaviManager",
                lpparam.classLoader,
                "onNaviEnd",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        isNaviActive = false;
                        DebugLogger.log("导航结束");
                        
                        // 发送导航结束状态到仪表
                        sendNaviStateToInstrument(0x21);
                    }
                }
            );
            
        } catch (Throwable t) {
            DebugLogger.log("Hook导航状态变化失败: " + t.getMessage());
        }
    }
    
    /**
     * Hook巡航状态变化
     */
    private void hookCurseStateChange(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.autonavi.auto.modules.curse.CurseManager",
                lpparam.classLoader,
                "onCurseStart",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        isCurseActive = true;
                        DebugLogger.log("巡航开始");
                        
                        // 发送巡航开始状态到仪表
                        sendCurseStateToInstrument(0x1E);
                    }
                }
            );
            
            XposedHelpers.findAndHookMethod("com.autonavi.auto.modules.curse.CurseManager",
                lpparam.classLoader,
                "onCurseEnd",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        isCurseActive = false;
                        DebugLogger.log("巡航结束");
                        
                        // 发送巡航结束状态到仪表
                        sendCurseStateToInstrument(0x21);
                    }
                }
            );
            
        } catch (Throwable t) {
            DebugLogger.log("Hook巡航状态变化失败: " + t.getMessage());
        }
    }
    
    /**
     * Hook服务启动
     */
    private void hookServiceStart(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("ecarx.naviservice.service.NaviService",
                lpparam.classLoader,
                "onStartCommand",
                Intent.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) param.thisObject;
                        DebugLogger.log("XSFNaviService启动，激活仪表");
                        
                        // 激活仪表投屏
                        InstrumentActivator.activate(context);
                    }
                }
            );
            
        } catch (Throwable t) {
            DebugLogger.log("Hook服务启动失败: " + t.getMessage());
        }
    }
    
    /**
     * Hook数据发送方法
     */
    private void hookDataTransmission(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook地图数据发送
            XposedHelpers.findAndHookMethod("ecarx.naviservice.map.cf",
                lpparam.classLoader,
                "a",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // 拦截地图数据并处理
                        Object mapController = param.getResult();
                        if (mapController != null) {
                            DebugLogger.log("获取地图控制器");
                        }
                    }
                }
            );
            
            // Hook导航数据发送
            XposedHelpers.findAndHookMethod("ecarx.naviservice.d.e",
                lpparam.classLoader,
                "a",
                Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object data = param.args[0];
                        if (data != null) {
                            DebugLogger.log("发送导航数据: " + data.getClass().getSimpleName());
                        }
                    }
                }
            );
            
        } catch (Throwable t) {
            DebugLogger.log("Hook数据发送方法失败: " + t.getMessage());
        }
    }
    
    /**
     * Hook仪表激活状态
     */
    private void hookInstrumentActivation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("ecarx.naviservice.map.cf",
                lpparam.classLoader,
                "b",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object mapVendor = param.getResult();
                        DebugLogger.log("获取地图供应商: " + mapVendor);
                        
                        // 确保仪表已激活
                        if (!isProjectionEnabled) {
                            isProjectionEnabled = true;
                            DebugLogger.log("启用投屏功能");
                        }
                    }
                }
            );
            
        } catch (Throwable t) {
            DebugLogger.log("Hook仪表激活状态失败: " + t.getMessage());
        }
    }
    
    /**
     * 修改广播以启用投屏
     */
    private void modifyBroadcastForProjection(Intent intent) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) {
                extras = new Bundle();
                intent.putExtras(extras);
            }
            
            // 强制添加投屏相关参数
            if (!extras.containsKey("EXTRA_EXTERNAL_ENGINE_ID")) {
                intent.putExtra("EXTRA_EXTERNAL_ENGINE_ID", 3);
                DebugLogger.log("添加ENGINE_ID参数");
            }
            
            if (!extras.containsKey("EXTRA_EXTERNAL_MAP_MODE")) {
                intent.putExtra("EXTRA_EXTERNAL_MAP_MODE", 2); // 3D模式
                DebugLogger.log("添加MAP_MODE参数");
            }
            
            if (!extras.containsKey("EXTRA_EXTERNAL_MAP_POSITION")) {
                intent.putExtra("EXTRA_EXTERNAL_MAP_POSITION", 2); // 居中
                DebugLogger.log("添加MAP_POSITION参数");
            }
            
            if (!extras.containsKey("EXTRA_EXTERNAL_MAP_LEVEL")) {
                intent.putExtra("EXTRA_EXTERNAL_MAP_LEVEL", 17); // 最大比例尺
                DebugLogger.log("添加MAP_LEVEL参数");
            }
            
            // 确保包含停止包标志
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            
        } catch (Throwable t) {
            DebugLogger.log("修改广播失败: " + t.getMessage());
        }
    }
    
    /**
     * 转换并发送导航信息
     */
    private void convertAndSendNaviInfo(Object amapNaviInfo) {
        try {
            // 使用数据转换器转换导航信息
            Object instrumentNaviInfo = NaviDataConverter.convertFromAMap(amapNaviInfo);
            if (instrumentNaviInfo != null) {
                // 发送到仪表
                sendToInstrument(instrumentNaviInfo);
                DebugLogger.log("导航信息已发送到仪表");
            }
        } catch (Throwable t) {
            DebugLogger.log("转换导航信息失败: " + t.getMessage());
        }
    }
    
    /**
     * 转换并发送引导信息
     */
    private void convertAndSendGuideInfo(Object amapGuideInfo) {
        try {
            // 使用数据转换器转换引导信息
            Object instrumentGuideInfo = NaviDataConverter.convertGuideInfo(amapGuideInfo);
            if (instrumentGuideInfo != null) {
                // 发送到仪表
                sendToInstrument(instrumentGuideInfo);
                DebugLogger.log("引导信息已发送到仪表");
            }
        } catch (Throwable t) {
            DebugLogger.log("转换引导信息失败: " + t.getMessage());
        }
    }
    
    /**
     * 发送导航状态到仪表
     */
    private void sendNaviStateToInstrument(int state) {
        try {
            // 创建状态信息对象
            Object statusInfo = createStatusInfo(state);
            if (statusInfo != null) {
                sendToInstrument(statusInfo);
                DebugLogger.log("导航状态已发送到仪表: " + state);
            }
        } catch (Throwable t) {
            DebugLogger.log("发送导航状态失败: " + t.getMessage());
        }
    }
    
    /**
     * 发送巡航状态到仪表
     */
    private void sendCurseStateToInstrument(int state) {
        try {
            // 创建状态信息对象
            Object statusInfo = createStatusInfo(state);
            if (statusInfo != null) {
                sendToInstrument(statusInfo);
                DebugLogger.log("巡航状态已发送到仪表: " + state);
            }
        } catch (Throwable t) {
            DebugLogger.log("发送巡航状态失败: " + t.getMessage());
        }
    }
    
    /**
     * 创建状态信息对象
     */
    private Object createStatusInfo(int status) {
        try {
            // 反射创建MapStatusInfo对象
            Class<?> statusInfoClass = Class.forName("ecarx.naviservice.map.entity.MapStatusInfo");
            Object statusInfo = statusInfoClass.newInstance();
            
            // 设置状态
            XposedHelpers.callMethod(statusInfo, "setStatus", status);
            
            return statusInfo;
        } catch (Throwable t) {
            DebugLogger.log("创建状态信息失败: " + t.getMessage());
            return null;
        }
    }
    
    /**
     * 发送到仪表
     */
    private void sendToInstrument(Object data) {
        try {
            // 使用广播协议发送到仪表
            if (data != null) {
                AMapBroadcastProtocol.sendDataToInstrument(data);
            }
        } catch (Throwable t) {
            DebugLogger.log("发送到仪表失败: " + t.getMessage());
        }
    }
}