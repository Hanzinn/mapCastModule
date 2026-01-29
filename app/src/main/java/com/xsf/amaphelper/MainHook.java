package com.xsf.amaphelper;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import java.lang.reflect.Method;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";
    
    // 重点关注的接口特征
    private static final String DESCRIPTOR_KEYWORD = "AutoSimilarWidget";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🕵️‍♂️ V179 全域黑匣子启动 - 准备抓取 7.5 通信数据");

        // 1. 监听【系统发出】的指令 (System -> Map)
        try {
            XposedHelpers.findAndHookMethod("android.os.BinderProxy", lpparam.classLoader, "transact",
                int.class, Parcel.class, Parcel.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int code = (int) param.args[0];
                    Parcel data = (Parcel) param.args[1];
                    analyzeParcel("⬆️ [SEND/出]", code, data);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: Hook BinderProxy Failed: " + t);
        }

        // 2. 监听【系统收到】的回调 (Map -> System)
        XposedHelpers.findAndHookMethod(Binder.class, "onTransact", 
            int.class, Parcel.class, Parcel.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int code = (int) param.args[0];
                Parcel data = (Parcel) param.args[1];
                analyzeParcel("⬇️ [RECV/入]", code, data);
            }
        });

        // 3. 监听 Bind 请求，看看系统到底在连谁
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", null, "bindService",
            Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                if (intent != null) {
                    XposedBridge.log("NaviHook: 👻 系统尝试 Bind: " + intent.toString());
                    if (intent.getComponent() != null) {
                        XposedBridge.log("NaviHook:    -> Component: " + intent.getComponent().flattenToString());
                    }
                }
            }
        });
        
        // 4. 监听连接成功回调
        // 我们需要 Hook ServiceConnection 的 onServiceConnected，但这通常是匿名内部类
        // 这里尝试 Hook 系统回调的入口 (LoadedApk$ServiceDispatcher) 太复杂，不如只看 Bind 动作
    }

    // 🔬 数据包显微镜
    private void analyzeParcel(String direction, int code, Parcel data) {
        // 过滤掉系统底层噪音，只保留业务指令 (1-5) 和 扩展指令 (>1000)
        // 排除 1598968902 (INTERFACE_TRANSACTION)
        if (code == 1598968902) return; 

        int startPos = data.dataPosition();
        boolean isTarget = false;
        String token = "";

        try {
            // 1. 尝试读取 Interface Token
            if (data.dataAvail() > 0) {
                token = data.readString();
                // 只有包含关键字的 Token 才是我们要的投屏协议
                if (token != null && token.contains(DESCRIPTOR_KEYWORD)) {
                    isTarget = true;
                }
            }
        } catch (Throwable t) {} finally {
            data.setDataPosition(startPos); // 必须复位
        }

        // 如果不是目标接口，直接忽略，避免日志爆炸
        if (!isTarget) return;

        StringBuilder sb = new StringBuilder();
        sb.append(direction).append(" Code: ").append(code);
        sb.append(" | Token: ").append(token);
        
        // 2. 尝试解析参数 (Blind Read)
        // 我们不知道具体参数类型，只能盲读几个 Int/String 看看
        data.setDataPosition(startPos + (token != null ? (token.length() * 2 + 4 + 4) : 0)); // 跳过 Token (粗略估算)
        // 上面的跳过逻辑可能不准，标准的 Parcel 读 Token 会自动处理头，
        // 这里为了安全，我们重新读一次 Token 推进指针
        try {
             data.readString(); // 消耗 Token
        } catch(Exception e) {}

        sb.append(" | Args: [");
        try {
            // 尝试读取前 5 个数据，看看是啥
            for (int i = 0; i < 5; i++) {
                if (data.dataAvail() <= 0) break;
                // 简单的启发式探测：先读 Int
                int val = data.readInt();
                sb.append(val).append(", ");
                
                // 如果这个 Int 看起来像个长度指示器 (且不算太大)，尝试读 String
                // 但这很危险，容易 Crash。
                // 安全起见，我们只记录 Int 值。
                // 很多时候 Boolean 也是 Int (0/1)。
            }
        } catch (Throwable t) {
            sb.append("EOF");
        } finally {
            sb.append("]");
            data.setDataPosition(startPos); // 再次复位，确保系统正常运行
        }

        XposedBridge.log("NaviHook: " + sb.toString());
    }
}