package com.xsf.amaphelper;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";
    private static final String TARGET_SERVICE_IMPL = "com.autonavi.amapauto.adapter.internal.widget.AutoSimilarWidgetService";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviHook: 🕵️‍♂️ V180 (Final) 中间人透明抓包版启动");

        // 拦截 bindService，注入间谍
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, "bindService",
            Intent.class, ServiceConnection.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Intent intent = (Intent) param.args[0];
                final ServiceConnection originalConn = (ServiceConnection) param.args[1];

                if (intent != null && intent.getComponent() != null && 
                    TARGET_SERVICE_IMPL.equals(intent.getComponent().getClassName())) {
                    
                    XposedBridge.log("NaviHook: 👻 捕获目标 Bind 请求，植入间谍...");

                    // 创建一个代理 Connection
                    ServiceConnection spyConn = new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            XposedBridge.log("NaviHook: 🔗 原始服务已连接，开始劫持 Binder...");
                            
                            // 🔥 核心：用 SpyBinder 包裹原始 Binder
                            IBinder spyBinder = new SpyBinder(service);
                            
                            // 把间谍交给系统
                            if (originalConn != null) {
                                originalConn.onServiceConnected(name, spyBinder);
                            }
                        }

                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                            if (originalConn != null) originalConn.onServiceDisconnected(name);
                        }
                    };

                    // 替换参数
                    param.args[1] = spyConn;
                }
            }
        });
    }

    // 🕵️‍♂️ 间谍 Binder：负责记录和转发
    public static class SpyBinder extends Binder {
        private IBinder mOriginal;

        public SpyBinder(IBinder original) {
            this.mOriginal = original;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            // 1. 记录日志 (偷看信件)
            logTransaction(code, data);

            // 2. 转发给原始 Binder (送信)
            // mOriginal.transact 可能会抛出 RemoteException，Binder.onTransact 允许抛出，所以这里不需要 try-catch
            return mOriginal.transact(code, data, reply, flags);
        }

        // --- 修复部分：必须捕获 RemoteException 以匹配父类 Binder 的签名 ---

        @Override 
        public String getInterfaceDescriptor() { 
            try {
                return mOriginal.getInterfaceDescriptor(); 
            } catch (RemoteException e) {
                return null;
            }
        }

        @Override 
        public boolean pingBinder() { 
            return mOriginal.pingBinder(); 
        }

        @Override 
        public boolean isBinderAlive() { 
            return mOriginal.isBinderAlive(); 
        }

        @Override 
        public IInterface queryLocalInterface(String descriptor) { 
            return mOriginal.queryLocalInterface(descriptor); 
        }

        @Override 
        public void dump(FileDescriptor fd, String[] args) { 
            try {
                mOriginal.dump(fd, args); 
            } catch (RemoteException e) {
                // Ignore
            }
        }

        @Override 
        public void dumpAsync(FileDescriptor fd, String[] args) { 
            try {
                mOriginal.dumpAsync(fd, args); 
            } catch (RemoteException e) {
                // Ignore
            }
        }

        @Override 
        public void linkToDeath(IBinder.DeathRecipient recipient, int flags) { 
            try {
                mOriginal.linkToDeath(recipient, flags); 
            } catch (RemoteException e) {
                // Ignore
            }
        }

        @Override 
        public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) { 
            return mOriginal.unlinkToDeath(recipient, flags); 
        }

        private void logTransaction(int code, Parcel data) {
            // 忽略系统底层高频调用
            if (code == 1598968902) return; 

            int startPos = data.dataPosition();
            StringBuilder sb = new StringBuilder();
            sb.append("📡 [Send] Code: ").append(code);

            try {
                // 尝试读取接口名
                if (data.dataAvail() > 0) {
                    String token = data.readString();
                    sb.append(" | Token: ").append(token);
                }
                
                // 盲读参数 (前 5 个 Int)
                sb.append(" | Args: ");
                for (int i = 0; i < 5; i++) {
                    if (data.dataAvail() > 0) {
                        try {
                            sb.append(data.readInt()).append(", ");
                        } catch (Exception e) {
                            break; 
                        }
                    }
                }
                
                // 如果是关键指令，尝试读 String
                if (code == 2001) { 
                    data.setDataPosition(startPos); // 回到开头
                    try { data.readString(); } catch(Exception e){} // 跳过 Token
                    try {
                        sb.append(" [TryStr]: ");
                        sb.append(data.readInt()).append(", "); // Int1
                        sb.append(data.readString()); // Str1
                    } catch (Exception e) {}
                }

            } catch (Throwable t) {
                sb.append(" [ParseError]");
            } finally {
                // 🔥 必须复位！
                data.setDataPosition(startPos);
            }
            XposedBridge.log("NaviHook: " + sb.toString());
        }
    }
}