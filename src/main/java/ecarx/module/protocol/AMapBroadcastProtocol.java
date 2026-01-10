package ecarx.module.protocol;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 高德地图广播协议实现
 * 实现高德车机版标准广播协议，用于与高德地图通信
 */
public class AMapBroadcastProtocol {
    
    private static final String TAG = "AMapBroadcastProtocol";
    
    // 广播Action
    public static final String ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND";
    public static final String ACTION_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV";
    
    // Key类型常量
    public static final int KEY_TYPE_EXTERNAL_SCREEN_CONFIG = 10104;
    public static final int KEY_TYPE_EXTERNAL_CROSS_CONTROL = 10105;
    public static final int KEY_TYPE_EXTERNAL_CROSS_TYPE = 10106;
    public static final int KEY_TYPE_LOCATION_INFO = 10065;
    public static final int KEY_TYPE_HEARTBEAT = 10019;
    public static final int KEY_TYPE_ZOOM_CHANGE = 10074;
    public static final int KEY_TYPE_AREA_INFO = 10030;
    public static final int KEY_TYPE_NAVI_STATUS = 10001;
    public static final int KEY_TYPE_GUIDE_INFO = 10002;
    public static final int KEY_TYPE_LANE_INFO = 10003;
    public static final int KEY_TYPE_TRAFFIC_INFO = 10004;
    public static final int KEY_TYPE_SPEED_LIMIT = 10005;
    
    // 地图模式
    public static final int MAP_MODE_2D_CAR_UP = 0;
    public static final int MAP_MODE_2D_NORTH_UP = 1;
    public static final int MAP_MODE_3D_CAR_UP = 2;
    
    // 地图位置
    public static final int MAP_POSITION_LEFT = 1;
    public static final int MAP_POSITION_CENTER = 2;
    public static final int MAP_POSITION_RIGHT = 3;
    
    /**
     * 发送多屏显示配置
     */
    public static void sendExternalScreenConfig(Context context, int engineId, 
            int mapMode, int position, int level) {
        try {
            Intent intent = new Intent(ACTION_RECV);
            intent.putExtra("KEY_TYPE", KEY_TYPE_EXTERNAL_SCREEN_CONFIG);
            intent.putExtra("EXTRA_EXTERNAL_ENGINE_ID", engineId);
            intent.putExtra("EXTRA_EXTERNAL_MAP_MODE", mapMode);
            intent.putExtra("EXTRA_EXTERNAL_MAP_POSITION", position);
            intent.putExtra("EXTRA_EXTERNAL_MAP_LEVEL", level);
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            
            context.sendBroadcast(intent);
            Log.d(TAG, "发送多屏显示配置: engineId=" + engineId + 
                  ", mode=" + mapMode + ", position=" + position + ", level=" + level);
            
        } catch (Exception e) {
            Log.e(TAG, "发送多屏显示配置失败", e);
        }
    }
    
    /**
     * 发送路口大图控制
     */
    public static void sendCrossImageControl(Context context, int engineId, boolean show) {
        try {
            Intent intent = new Intent(ACTION_RECV);
            intent.putExtra("KEY_TYPE", KEY_TYPE_EXTERNAL_CROSS_CONTROL);
            intent.putExtra("EXTRA_EXTERNAL_ENGINE_ID", engineId);
            intent.putExtra("EXTRA_EXTERNAL_CROSS_CONTROL", show);
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            
            context.sendBroadcast(intent);
            Log.d(TAG, "发送路口大图控制: engineId=" + engineId + ", show=" + show);
            
        } catch (Exception e) {
            Log.e(TAG, "发送路口大图控制失败", e);
        }
    }
    
    /**
     * 发送路口大图类型
     */
    public static void sendCrossImageType(Context context, int engineId, int type) {
        try {
            Intent intent = new Intent(ACTION_RECV);
            intent.putExtra("KEY_TYPE", KEY_TYPE_EXTERNAL_CROSS_TYPE);
            intent.putExtra("EXTRA_EXTERNAL_ENGINE_ID", engineId);
            intent.putExtra("EXTRA_EXTERNAL_CROSS_TYPE", type);
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            
            context.sendBroadcast(intent);
            Log.d(TAG, "发送路口大图类型: engineId=" + engineId + ", type=" + type);
            
        } catch (Exception e) {
            Log.e(TAG, "发送路口大图类型失败", e);
        }
    }
    
    /**
     * 发送定位信息
     */
    public static void sendLocationInfo(Context context, double latitude, double longitude,
            float bearing, float speed, float accuracy) {
        try {
            // 创建位置信息JSON
            String locationJson = createLocationJson(latitude, longitude, bearing, speed, accuracy);
            
            Intent intent = new Intent(ACTION_SEND);
            intent.putExtra("KEY_TYPE", KEY_TYPE_LOCATION_INFO);
            intent.putExtra("EXTRA_LOCATION_INFO", locationJson);
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            
            context.sendBroadcast(intent);
            Log.d(TAG, "发送定位信息: lat=" + latitude + ", lon=" + longitude);
            
        } catch (Exception e) {
            Log.e(TAG, "发送定位信息失败", e);
        }
    }
    
    /**
     * 发送心跳
     */
    public static void sendHeartbeat(Context context, int state) {
        try {
            Intent intent = new Intent(ACTION_SEND);
            intent.putExtra("KEY_TYPE", KEY_TYPE_HEARTBEAT);
            intent.putExtra("EXTRA_STATE", state);
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            
            context.sendBroadcast(intent);
            Log.d(TAG, "发送心跳: state=" + state);
            
        } catch (Exception e) {
            Log.e(TAG, "发送心跳失败", e);
        }
    }
    
    /**
     * 发送缩放变化
     */
    public static void sendZoomChange(Context context, int zoomType, boolean canZoom) {
        try {
            Intent intent = new Intent(ACTION_SEND);
            intent.putExtra("KEY_TYPE", KEY_TYPE_ZOOM_CHANGE);
            intent.putExtra("EXTRA_ZOOM_TYPE", zoomType);
            intent.putExtra("EXTRA_CAN_ZOOM", canZoom);
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            
            context.sendBroadcast(intent);
            Log.d(TAG, "发送缩放变化: type=" + zoomType + ", canZoom=" + canZoom);
            
        } catch (Exception e) {
            Log.e(TAG, "发送缩放变化失败", e);
        }
    }
    
    /**
     * 发送区域信息
     */
    public static void sendAreaInfo(Context context, String province, String city) {
        try {
            Intent intent = new Intent(ACTION_SEND);
            intent.putExtra("KEY_TYPE", KEY_TYPE_AREA_INFO);
            intent.putExtra("PROVINCE_NAME", province);
            intent.putExtra("CITY_NAME", city);
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            
            context.sendBroadcast(intent);
            Log.d(TAG, "发送区域信息: " + province + ", " + city);
            
        } catch (Exception e) {
            Log.e(TAG, "发送区域信息失败", e);
        }
    }
    
    /**
     * 发送导航状态
     */
    public static void sendNaviStatus(Context context, int status) {
        try {
            Intent intent = new Intent(ACTION_SEND);
            intent.putExtra("KEY_TYPE", KEY_TYPE_NAVI_STATUS);
            intent.putExtra("EXTRA_NAVI_STATUS", status);
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            
            context.sendBroadcast(intent);
            Log.d(TAG, "发送导航状态: " + status);
            
        } catch (Exception e) {
            Log.e(TAG, "发送导航状态失败", e);
        }
    }
    
    /**
     * 发送导航信息到仪表
     */
    public static void sendGuideInfo(Context context, Object guideInfo) {
        try {
            // 提取导航信息数据
            Map<String, Object> data = extractGuideInfo(guideInfo);
            
            Intent intent = new Intent(ACTION_SEND);
            intent.putExtra("KEY_TYPE", KEY_TYPE_GUIDE_INFO);
            
            // 添加导航信息数据
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                intent.putExtra(entry.getKey(), entry.getValue().toString());
            }
            
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
            Log.d(TAG, "发送导航信息到仪表");
            
        } catch (Exception e) {
            Log.e(TAG, "发送导航信息失败", e);
        }
    }
    
    /**
     * 发送车道信息到仪表
     */
    public static void sendLaneInfo(Context context, Object laneInfo) {
        try {
            // 提取车道信息数据
            Map<String, Object> data = extractLaneInfo(laneInfo);
            
            Intent intent = new Intent(ACTION_SEND);
            intent.putExtra("KEY_TYPE", KEY_TYPE_LANE_INFO);
            
            // 添加车道信息数据
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                intent.putExtra(entry.getKey(), entry.getValue().toString());
            }
            
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
            Log.d(TAG, "发送车道信息到仪表");
            
        } catch (Exception e) {
            Log.e(TAG, "发送车道信息失败", e);
        }
    }
    
    /**
     * 发送路况信息到仪表
     */
    public static void sendTrafficInfo(Context context, Object trafficInfo) {
        try {
            // 提取路况信息数据
            Map<String, Object> data = extractTrafficInfo(trafficInfo);
            
            Intent intent = new Intent(ACTION_SEND);
            intent.putExtra("KEY_TYPE", KEY_TYPE_TRAFFIC_INFO);
            
            // 添加路况信息数据
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                intent.putExtra(entry.getKey(), entry.getValue().toString());
            }
            
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
            Log.d(TAG, "发送路况信息到仪表");
            
        } catch (Exception e) {
            Log.e(TAG, "发送路况信息失败", e);
        }
    }
    
    /**
     * 发送限速信息到仪表
     */
    public static void sendSpeedLimitInfo(Context context, Object speedLimitInfo) {
        try {
            // 提取限速信息数据
            Map<String, Object> data = extractSpeedLimitInfo(speedLimitInfo);
            
            Intent intent = new Intent(ACTION_SEND);
            intent.putExtra("KEY_TYPE", KEY_TYPE_SPEED_LIMIT);
            
            // 添加限速信息数据
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                intent.putExtra(entry.getKey(), entry.getValue().toString());
            }
            
            intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
            Log.d(TAG, "发送限速信息到仪表");
            
        } catch (Exception e) {
            Log.e(TAG, "发送限速信息失败", e);
        }
    }
    
    /**
     * 发送数据到仪表
     */
    public static void sendDataToInstrument(Object data) {
        try {
            // 根据数据类型发送到仪表
            String className = data.getClass().getSimpleName();
            
            switch (className) {
                case "MapGuideInfo":
                    // 发送到仪表的导航信息处理
                    Log.d(TAG, "发送导航信息到仪表");
                    break;
                case "MapLanesInfo":
                    // 发送到仪表的车道信息处理
                    Log.d(TAG, "发送车道信息到仪表");
                    break;
                case "MapRoadConditionEntity":
                    // 发送到仪表的路况信息处理
                    Log.d(TAG, "发送路况信息到仪表");
                    break;
                case "MapSpeedLimitInfo":
                    // 发送到仪表的限速信息处理
                    Log.d(TAG, "发送限速信息到仪表");
                    break;
                default:
                    Log.w(TAG, "未知数据类型: " + className);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "发送数据到仪表失败", e);
        }
    }
    
    /**
     * 创建位置信息JSON
     */
    private static String createLocationJson(double latitude, double longitude,
            float bearing, float speed, float accuracy) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"bearing\":").append(bearing).append(",");
            json.append("\"accuracy\":").append(accuracy).append(",");
            json.append("\"speed\":").append(speed).append(",");
            json.append("\"time\":").append(System.currentTimeMillis()).append(",");
            json.append("\"provider\":\"gps\",");
            json.append("\"latitude\":").append(latitude).append(",");
            json.append("\"longitude\":").append(longitude);
            json.append("}");
            
            return json.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "创建位置信息JSON失败", e);
            return "{}";
        }
    }
    
    /**
     * 提取导航信息
     */
    private static Map<String, Object> extractGuideInfo(Object guideInfo) {
        Map<String, Object> data = new java.util.HashMap<>();
        
        try {
            // 使用反射提取导航信息字段
            Method[] methods = guideInfo.getClass().getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    try {
                        Object value = method.invoke(guideInfo);
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
     * 提取车道信息
     */
    private static Map<String, Object> extractLaneInfo(Object laneInfo) {
        Map<String, Object> data = new java.util.HashMap<>();
        
        try {
            // 使用反射提取车道信息字段
            Method[] methods = laneInfo.getClass().getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    try {
                        Object value = method.invoke(laneInfo);
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
            Log.e(TAG, "提取车道信息失败", e);
        }
        
        return data;
    }
    
    /**
     * 提取路况信息
     */
    private static Map<String, Object> extractTrafficInfo(Object trafficInfo) {
        Map<String, Object> data = new java.util.HashMap<>();
        
        try {
            // 使用反射提取路况信息字段
            Method[] methods = trafficInfo.getClass().getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    try {
                        Object value = method.invoke(trafficInfo);
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
            Log.e(TAG, "提取路况信息失败", e);
        }
        
        return data;
    }
    
    /**
     * 提取限速信息
     */
    private static Map<String, Object> extractSpeedLimitInfo(Object speedLimitInfo) {
        Map<String, Object> data = new java.util.HashMap<>();
        
        try {
            // 使用反射提取限速信息字段
            Method[] methods = speedLimitInfo.getClass().getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                    try {
                        Object value = method.invoke(speedLimitInfo);
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
            Log.e(TAG, "提取限速信息失败", e);
        }
        
        return data;
    }
    
    /**
     * 解析接收到的广播
     */
    public static Bundle parseReceivedBroadcast(Intent intent) {
        Bundle bundle = new Bundle();
        
        try {
            // 获取Key类型
            int keyType = intent.getIntExtra("KEY_TYPE", -1);
            bundle.putInt("KEY_TYPE", keyType);
            
            // 根据Key类型解析数据
            switch (keyType) {
                case KEY_TYPE_EXTERNAL_SCREEN_CONFIG:
                    parseScreenConfig(intent, bundle);
                    break;
                case KEY_TYPE_EXTERNAL_CROSS_CONTROL:
                    parseCrossControl(intent, bundle);
                    break;
                case KEY_TYPE_LOCATION_INFO:
                    parseLocationInfo(intent, bundle);
                    break;
                default:
                    // 解析通用数据
                    parseCommonData(intent, bundle);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "解析广播失败", e);
        }
        
        return bundle;
    }
    
    /**
     * 解析屏幕配置
     */
    private static void parseScreenConfig(Intent intent, Bundle bundle) {
        bundle.putInt("ENGINE_ID", intent.getIntExtra("EXTRA_EXTERNAL_ENGINE_ID", -1));
        bundle.putInt("MAP_MODE", intent.getIntExtra("EXTRA_EXTERNAL_MAP_MODE", -1));
        bundle.putInt("MAP_POSITION", intent.getIntExtra("EXTRA_EXTERNAL_MAP_POSITION", -1));
        bundle.putInt("MAP_LEVEL", intent.getIntExtra("EXTRA_EXTERNAL_MAP_LEVEL", -1));
    }
    
    /**
     * 解析路口控制
     */
    private static void parseCrossControl(Intent intent, Bundle bundle) {
        bundle.putInt("ENGINE_ID", intent.getIntExtra("EXTRA_EXTERNAL_ENGINE_ID", -1));
        bundle.putBoolean("CROSS_CONTROL", intent.getBooleanExtra("EXTRA_EXTERNAL_CROSS_CONTROL", false));
    }
    
    /**
     * 解析位置信息
     */
    private static void parseLocationInfo(Intent intent, Bundle bundle) {
        String locationJson = intent.getStringExtra("EXTRA_LOCATION_INFO");
        bundle.putString("LOCATION_INFO", locationJson);
    }
    
    /**
     * 解析通用数据
     */
    private static void parseCommonData(Intent intent, Bundle bundle) {
        // 将所有额外数据添加到bundle
        Bundle extras = intent.getExtras();
        if (extras != null) {
            bundle.putAll(extras);
        }
    }
}