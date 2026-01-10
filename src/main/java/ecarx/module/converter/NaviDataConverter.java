package ecarx.module.converter;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 导航数据转换器
 * 将高德地图的导航信息转换为仪表可识别的格式
 */
public class NaviDataConverter {
    
    private static final String TAG = "NaviDataConverter";
    
    /**
     * 从高德地图导航信息转换为仪表导航信息
     */
    public static Object convertFromAMap(Object amapNaviInfo) {
        try {
            // 创建仪表导航信息对象
            Class<?> guideInfoClass = Class.forName("ecarx.naviservice.map.entity.MapGuideInfo");
            Object instrumentGuideInfo = guideInfoClass.newInstance();
            
            // 转换基本信息
            convertBasicInfo(amapNaviInfo, instrumentGuideInfo);
            
            // 转换路线信息
            convertRouteInfo(amapNaviInfo, instrumentGuideInfo);
            
            // 转换速度信息
            convertSpeedInfo(amapNaviInfo, instrumentGuideInfo);
            
            // 转换时间距离信息
            convertTimeDistanceInfo(amapNaviInfo, instrumentGuideInfo);
            
            return instrumentGuideInfo;
            
        } catch (Throwable t) {
            Log.e(TAG, "转换导航信息失败", t);
            return null;
        }
    }
    
    /**
     * 转换引导信息
     */
    public static Object convertGuideInfo(Object amapGuideInfo) {
        try {
            // 创建仪表引导信息对象
            Class<?> guideInfoClass = Class.forName("ecarx.naviservice.map.entity.MapGuideInfo");
            Object instrumentGuideInfo = guideInfoClass.newInstance();
            
            // 转换引导信息
            convertGuideDetails(amapGuideInfo, instrumentGuideInfo);
            
            return instrumentGuideInfo;
            
        } catch (Throwable t) {
            Log.e(TAG, "转换引导信息失败", t);
            return null;
        }
    }
    
    /**
     * 转换车道信息
     */
    public static Object convertLaneInfo(Object amapLaneInfo) {
        try {
            // 创建仪表车道信息对象
            Class<?> lanesInfoClass = Class.forName("com.ecarx.lbsnavilib.widget.entity.MapLanesInfo");
            Object instrumentLanesInfo = lanesInfoClass.newInstance();
            
            // 转换车道信息
            convertLaneDetails(amapLaneInfo, instrumentLanesInfo);
            
            return instrumentLanesInfo;
            
        } catch (Throwable t) {
            Log.e(TAG, "转换车道信息失败", t);
            return null;
        }
    }
    
    /**
     * 转换路况信息
     */
    public static Object convertTrafficInfo(Object amapTrafficInfo) {
        try {
            // 创建仪表路况信息对象
            Class<?> trafficInfoClass = Class.forName("ecarx.naviservice.map.entity.MapRoadConditionEntity");
            Object instrumentTrafficInfo = trafficInfoClass.newInstance();
            
            // 转换路况信息
            convertTrafficDetails(amapTrafficInfo, instrumentTrafficInfo);
            
            return instrumentTrafficInfo;
            
        } catch (Throwable t) {
            Log.e(TAG, "转换路况信息失败", t);
            return null;
        }
    }
    
    /**
     * 转换限速信息
     */
    public static Object convertSpeedLimitInfo(int speedLimit, int mapVendor) {
        try {
            // 创建仪表限速信息对象
            Class<?> speedLimitInfoClass = Class.forName("com.ecarx.lbsnavilib.widget.entity.MapSpeedLimitInfo");
            Object instrumentSpeedLimitInfo = speedLimitInfoClass.getDeclaredConstructor(int.class).newInstance(mapVendor);
            
            // 设置限速值
            Method setSpeedLimit = speedLimitInfoClass.getMethod("setSpeedLimit", int.class);
            setSpeedLimit.invoke(instrumentSpeedLimitInfo, speedLimit);
            
            // 设置模式
            Method setWidgetMode = speedLimitInfoClass.getMethod("setWidgetMode", int.class);
            setWidgetMode.invoke(instrumentSpeedLimitInfo, 1); // 导航模式
            
            return instrumentSpeedLimitInfo;
            
        } catch (Throwable t) {
            Log.e(TAG, "转换限速信息失败", t);
            return null;
        }
    }
    
    /**
     * 转换基本信息
     */
    private static void convertBasicInfo(Object source, Object target) {
        try {
            // 获取源对象的方法
            Method getGuideType = source.getClass().getMethod("getGuideType");
            Method getTurnId = source.getClass().getMethod("getTurnId");
            Method getCurRoadName = source.getClass().getMethod("getCurRoadName");
            Method getNextRoadName = source.getClass().getMethod("getNextRoadName");
            
            // 获取目标对象的方法
            Method setGuideType = target.getClass().getMethod("setGuideType", int.class);
            Method setTurnId = target.getClass().getMethod("setTurnId", int.class);
            Method setCurRoadName = target.getClass().getMethod("setCurRoadName", String.class);
            Method setNextRoadName = target.getClass().getMethod("setNextRoadName", String.class);
            
            // 转换数据
            Integer guideType = (Integer) getGuideType.invoke(source);
            if (guideType != null) {
                setGuideType.invoke(target, guideType);
            }
            
            Integer turnId = (Integer) getTurnId.invoke(source);
            if (turnId != null) {
                setTurnId.invoke(target, turnId);
            }
            
            String curRoadName = (String) getCurRoadName.invoke(source);
            if (curRoadName != null) {
                setCurRoadName.invoke(target, curRoadName);
            }
            
            String nextRoadName = (String) getNextRoadName.invoke(source);
            if (nextRoadName != null) {
                setNextRoadName.invoke(target, nextRoadName);
            }
            
        } catch (Throwable t) {
            Log.e(TAG, "转换基本信息失败", t);
        }
    }
    
    /**
     * 转换路线信息
     */
    private static void convertRouteInfo(Object source, Object target) {
        try {
            // 获取源对象的方法
            Method getNextTurnTime = source.getClass().getMethod("getNextTurnTime");
            Method getNextTurnDistance = source.getClass().getMethod("getNextTurnDistance");
            Method getRemainDistance = source.getClass().getMethod("getRemainDistance");
            Method getRemainTime = source.getClass().getMethod("getRemainTime");
            
            // 获取目标对象的方法
            Method setNextTurnTime = target.getClass().getMethod("setNextTurnTime", int.class);
            Method setNextTurnDistance = target.getClass().getMethod("setNextTurnDistance", int.class);
            Method setRemainDistance = target.getClass().getMethod("setRemainDistance", int.class);
            Method setRemainTime = target.getClass().getMethod("setRemainTime", int.class);
            
            // 转换数据
            Integer nextTurnTime = (Integer) getNextTurnTime.invoke(source);
            if (nextTurnTime != null) {
                setNextTurnTime.invoke(target, nextTurnTime);
            }
            
            Integer nextTurnDistance = (Integer) getNextTurnDistance.invoke(source);
            if (nextTurnDistance != null) {
                setNextTurnDistance.invoke(target, nextTurnDistance);
            }
            
            Integer remainDistance = (Integer) getRemainDistance.invoke(source);
            if (remainDistance != null) {
                setRemainDistance.invoke(target, remainDistance);
            }
            
            Integer remainTime = (Integer) getRemainTime.invoke(source);
            if (remainTime != null) {
                setRemainTime.invoke(target, remainTime);
            }
            
        } catch (Throwable t) {
            Log.e(TAG, "转换路线信息失败", t);
        }
    }
    
    /**
     * 转换速度信息
     */
    private static void convertSpeedInfo(Object source, Object target) {
        try {
            // 获取源对象的方法
            Method getCameraSpeed = source.getClass().getMethod("getCameraSpeed");
            Method getCameraDistance = source.getClass().getMethod("getCameraDistance");
            
            // 获取目标对象的方法
            Method setCameraSpeed = target.getClass().getMethod("setCameraSpeed", int.class);
            Method setCameraDistance = target.getClass().getMethod("setCameraDistance", int.class);
            
            // 转换数据
            Integer cameraSpeed = (Integer) getCameraSpeed.invoke(source);
            if (cameraSpeed != null) {
                setCameraSpeed.invoke(target, cameraSpeed);
            }
            
            Integer cameraDistance = (Integer) getCameraDistance.invoke(source);
            if (cameraDistance != null) {
                setCameraDistance.invoke(target, cameraDistance);
            }
            
        } catch (Throwable t) {
            Log.e(TAG, "转换速度信息失败", t);
        }
    }
    
    /**
     * 转换时间距离信息
     */
    private static void convertTimeDistanceInfo(Object source, Object target) {
        try {
            // 获取源对象的方法
            Method getSegmentTime = source.getClass().getMethod("getSegmentTime");
            Method getSegmentDistance = source.getClass().getMethod("getSegmentDistance");
            
            // 获取目标对象的方法
            Method setSegmentTime = target.getClass().getMethod("setSegmentTime", int.class);
            Method setSegmentDistance = target.getClass().getMethod("setSegmentDistance", int.class);
            
            // 转换数据
            Integer segmentTime = (Integer) getSegmentTime.invoke(source);
            if (segmentTime != null) {
                setSegmentTime.invoke(target, segmentTime);
            }
            
            Integer segmentDistance = (Integer) getSegmentDistance.invoke(source);
            if (segmentDistance != null) {
                setSegmentDistance.invoke(target, segmentDistance);
            }
            
        } catch (Throwable t) {
            Log.e(TAG, "转换时间距离信息失败", t);
        }
    }
    
    /**
     * 转换引导详情
     */
    private static void convertGuideDetails(Object source, Object target) {
        try {
            // 提取引导信息的关键字段
            Map<String, Object> guideData = extractGuideData(source);
            
            // 应用转换规则
            applyGuideConversion(guideData, target);
            
        } catch (Throwable t) {
            Log.e(TAG, "转换引导详情失败", t);
        }
    }
    
    /**
     * 转换车道详情
     */
    private static void convertLaneDetails(Object source, Object target) {
        try {
            // 提取车道信息
            Method getDriveWayInfo = source.getClass().getMethod("getDriveWayInfo");
            Method getDriveWaySize = source.getClass().getMethod("getDriveWaySize");
            Method isDriveWayEnabled = source.getClass().getMethod("isDriveWayEnabled");
            
            // 设置到目标对象
            Object driveWayInfo = getDriveWayInfo.invoke(source);
            Integer driveWaySize = (Integer) getDriveWaySize.invoke(source);
            Boolean driveWayEnabled = (Boolean) isDriveWayEnabled.invoke(source);
            
            // 设置车道信息
            Method setDriveWayInfo = target.getClass().getMethod("setDriveWayInfo", Object.class);
            Method setDriveWaySize = target.getClass().getMethod("setDriveWaySize", int.class);
            Method setDriveWayEnabled = target.getClass().getMethod("setDriveWayEnabled", boolean.class);
            
            if (driveWayInfo != null) {
                setDriveWayInfo.invoke(target, driveWayInfo);
            }
            if (driveWaySize != null) {
                setDriveWaySize.invoke(target, driveWaySize);
            }
            if (driveWayEnabled != null) {
                setDriveWayEnabled.invoke(target, driveWayEnabled);
            }
            
        } catch (Throwable t) {
            Log.e(TAG, "转换车道详情失败", t);
        }
    }
    
    /**
     * 转换路况详情
     */
    private static void convertTrafficDetails(Object source, Object target) {
        try {
            // 提取路况信息
            Method getTmcSegmentSize = source.getClass().getMethod("getTmcSegmentSize");
            Method getTmcInfo = source.getClass().getMethod("getTmcInfo");
            Method isTmcSegmentEnabled = source.getClass().getMethod("isTmcSegmentEnabled");
            
            // 设置到目标对象
            Integer segmentSize = (Integer) getTmcSegmentSize.invoke(source);
            Object tmcInfo = getTmcInfo.invoke(source);
            Boolean enabled = (Boolean) isTmcSegmentEnabled.invoke(source);
            
            // 设置路况信息
            Method setTmcSegmentSize = target.getClass().getMethod("setTmcSegmentSize", int.class);
            Method setTmcInfo = target.getClass().getMethod("setTmcInfo", Object.class);
            Method setTmcSegmentEnabled = target.getClass().getMethod("setTmcSegmentEnabled", boolean.class);
            
            if (segmentSize != null) {
                setTmcSegmentSize.invoke(target, segmentSize);
            }
            if (tmcInfo != null) {
                setTmcInfo.invoke(target, tmcInfo);
            }
            if (enabled != null) {
                setTmcSegmentEnabled.invoke(target, enabled);
            }
            
        } catch (Throwable t) {
            Log.e(TAG, "转换路况详情失败", t);
        }
    }
    
    /**
     * 提取引导数据
     */
    private static Map<String, Object> extractGuideData(Object source) {
        Map<String, Object> data = new HashMap<>();
        
        try {
            // 使用反射提取所有字段
            Method[] methods = source.getClass().getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    try {
                        Object value = method.invoke(source);
                        String key = method.getName().substring(3);
                        data.put(key, value);
                    } catch (Exception e) {
                        // 忽略提取失败的字段
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "提取引导数据失败", t);
        }
        
        return data;
    }
    
    /**
     * 应用引导转换
     */
    private static void applyGuideConversion(Map<String, Object> data, Object target) {
        try {
            // 遍历数据并应用到目标对象
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                try {
                    String setterName = "set" + entry.getKey();
                    Method setter = target.getClass().getMethod(setterName, entry.getValue().getClass());
                    setter.invoke(target, entry.getValue());
                } catch (Exception e) {
                    // 忽略转换失败的字段
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "应用引导转换失败", t);
        }
    }
    
    /**
     * 获取地图供应商
     */
    public static int getMapVendor() {
        // 高德地图供应商ID
        return 1;
    }
    
    /**
     * 创建默认导航信息
     */
    public static Object createDefaultNaviInfo(int mapVendor) {
        try {
            Class<?> guideInfoClass = Class.forName("ecarx.naviservice.map.entity.MapGuideInfo");
            Object guideInfo = guideInfoClass.getDeclaredConstructor(int.class).newInstance(mapVendor);
            
            // 设置默认导航状态
            Method setGuideType = guideInfoClass.getMethod("setGuideType", int.class);
            Method setTurnId = guideInfoClass.getMethod("setTurnId", int.class);
            Method setCurRoadName = guideInfoClass.getMethod("setCurRoadName", String.class);
            Method setNextRoadName = guideInfoClass.getMethod("setNextRoadName", String.class);
            
            setGuideType.invoke(guideInfo, 2); // 导航模式
            setTurnId.invoke(guideInfo, 102); // 直行
            setCurRoadName.invoke(guideInfo, "当前道路");
            setNextRoadName.invoke(guideInfo, "下一路口");
            
            return guideInfo;
            
        } catch (Throwable t) {
            Log.e(TAG, "创建默认导航信息失败", t);
            return null;
        }
    }
}