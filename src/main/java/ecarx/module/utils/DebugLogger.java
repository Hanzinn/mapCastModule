package ecarx.module.utils;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;


/**
 * 调试日志工具
 * 提供日志记录功能，支持控制台输出和文件记录
 */
public class DebugLogger {
    
    private static final String TAG = "AMapProjection";
    private static final String LOG_FILE_NAME = "amap_projection.log";
    private static final boolean ENABLE_FILE_LOG = true;
    private static final boolean ENABLE_CONSOLE_LOG = true;
    
    private static File logFile;
    private static SimpleDateFormat dateFormat;
    
    static {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        
        if (ENABLE_FILE_LOG) {
            initializeLogFile();
        }
    }
    
    /**
     * 初始化日志文件
     */
    private static void initializeLogFile() {
        try {
            File externalStorage = Environment.getExternalStorageDirectory();
            File logDir = new File(externalStorage, "AMapProjection");
            
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            
            logFile = new File(logDir, LOG_FILE_NAME);
            
            // 如果文件存在且大小超过10MB，则重命名
            if (logFile.exists() && logFile.length() > 10 * 1024 * 1024) {
                File backupFile = new File(logDir, LOG_FILE_NAME + ".backup");
                logFile.renameTo(backupFile);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "初始化日志文件失败", e);
        }
    }
    
    /**
     * 记录日志
     */
    public static void log(String message) {
        String logMessage = formatLogMessage(message);
        
        if (ENABLE_CONSOLE_LOG) {
            Log.d(TAG, message);
        }
        
        if (ENABLE_FILE_LOG && logFile != null) {
            writeToFile(logMessage);
        }
    }
    
    /**
     * 记录错误日志
     */
    public static void logError(String message, Throwable throwable) {
        String logMessage = formatLogMessage("ERROR: " + message);
        
        if (ENABLE_CONSOLE_LOG) {
            Log.e(TAG, message, throwable);
        }
        
        if (ENABLE_FILE_LOG && logFile != null) {
            writeToFile(logMessage);
            if (throwable != null) {
                writeToFile(android.util.Log.getStackTraceString(throwable));
            }
        }
    }
    
    /**
     * 记录警告日志
     */
    public static void logWarning(String message) {
        String logMessage = formatLogMessage("WARNING: " + message);
        
        if (ENABLE_CONSOLE_LOG) {
            Log.w(TAG, message);
        }
        
        if (ENABLE_FILE_LOG && logFile != null) {
            writeToFile(logMessage);
        }
    }
    
    /**
     * 记录Intent信息
     */
    public static void logIntent(String action, Intent intent) {
        StringBuilder sb = new StringBuilder();
        sb.append("Intent: ").append(action).append("\n");
        sb.append("  Action: ").append(intent.getAction()).append("\n");
        sb.append("  Data: ").append(intent.getDataString()).append("\n");
        sb.append("  Type: ").append(intent.getType()).append("\n");
        sb.append("  Flags: ").append(intent.getFlags()).append("\n");
        
        Bundle extras = intent.getExtras();
        if (extras != null) {
            sb.append("  Extras:\n");
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                sb.append("    ").append(key).append(" = ").append(value).append("\n");
            }
        }
        
        log(sb.toString());
    }
    
    /**
     * 记录Bundle信息
     */
    public static void logBundle(String prefix, Bundle bundle) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(" Bundle:\n");
        
        if (bundle != null) {
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                sb.append("  ").append(key).append(" = ").append(value).append("\n");
            }
        } else {
            sb.append("  (null)\n");
        }
        
        log(sb.toString());
    }
    
    /**
     * 记录对象信息
     */
    public static void logObject(String prefix, Object obj) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(" Object: ").append(obj != null ? obj.getClass().getSimpleName() : "null").append("\n");
        
        if (obj != null) {
            try {
                java.lang.reflect.Method[] methods = obj.getClass().getDeclaredMethods();
                for (java.lang.reflect.Method method : methods) {
                    if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                        try {
                            Object value = method.invoke(obj);
                            sb.append("  ").append(method.getName()).append("() = ").append(value).append("\n");
                        } catch (Exception e) {
                            sb.append("  ").append(method.getName()).append("() = [error: ").append(e.getMessage()).append("]\n");
                        }
                    }
                }
            } catch (Exception e) {
                sb.append("  [error getting methods: ").append(e.getMessage()).append("]\n");
            }
        }
        
        log(sb.toString());
    }
    
    /**
     * 记录方法调用
     */
    public static void logMethodCall(String methodName, Object... args) {
        StringBuilder sb = new StringBuilder();
        sb.append("Call: ").append(methodName).append("(");
        
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(args[i] != null ? args[i].toString() : "null");
            }
        }
        
        sb.append(")");
        log(sb.toString());
    }
    
    /**
     * 记录方法返回值
     */
    public static void logMethodReturn(String methodName, Object result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Return: ").append(methodName).append(" = ")
          .append(result != null ? result.toString() : "null");
        log(sb.toString());
    }
    
    /**
     * 格式化日志消息
     */
    private static String formatLogMessage(String message) {
        String timestamp = dateFormat.format(new Date());
        return String.format("[%s] %s", timestamp, message);
    }
    
    /**
     * 写入文件
     */
    private static void writeToFile(String message) {
        if (logFile == null) {
            return;
        }
        
        FileWriter writer = null;
        try {
            writer = new FileWriter(logFile, true);
            writer.write(message);
            writer.write("\n");
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "写入日志文件失败", e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // 忽略关闭异常
                }
            }
        }
    }
    
    /**
     * 获取日志文件路径
     */
    public static String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : null;
    }
    
    /**
     * 清除日志文件
     */
    public static void clearLogFile() {
        if (logFile != null && logFile.exists()) {
            logFile.delete();
            initializeLogFile();
        }
    }
    
    /**
     * 记录模块启动信息
     */
    public static void logModuleStart() {
        log("========================================");
        log("高德地图投屏模块已启动");
        log("版本: 1.0.0");
        log("构建时间: " + new Date().toString());
        log("========================================");
    }
    
    /**
     * 记录模块停止信息
     */
    public static void logModuleStop() {
        log("========================================");
        log("高德地图投屏模块已停止");
        log("========================================");
    }
    
    /**
     * 记录Hook信息
     */
    public static void logHookInfo(String className, String methodName, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hook: ").append(className).append("#").append(methodName);
        if (description != null) {
            sb.append(" - ").append(description);
        }
        log(sb.toString());
    }
    
    /**
     * 记录异常信息
     */
    public static void logException(String context, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("Exception in ").append(context).append(": ").append(throwable.getMessage());
        logError(sb.toString(), throwable);
    }
}
