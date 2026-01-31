public static class TrojanBinder extends Binder {
    private ClassLoader classLoader;
    private boolean isSurfaceActive = false;
    private Handler uiHandler;

    public TrojanBinder(ClassLoader cl) {
        this.classLoader = cl;
        this.uiHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        try {
            int dataSize = data.dataSize();
            
            // 只打印大包或关键 Code
            if (dataSize > 50 || code == 4) {
                XposedBridge.log("NaviHook: [Binder] Code=" + code + " Size=" + dataSize);
            }

            // 🔥 Code 2 可能是 addSurface（还没导航就出现了）
            if (code == 2 && dataSize > 100) {
                XposedBridge.log("NaviHook: [Binder] 🎯 Code 2 = AddSurface (pre-navigation)");
                Surface s = tryParseSurface(data);
                if (s != null) {
                    uiHandler.post(() -> injectNativeEngine(s));
                    isSurfaceActive = true;
                }
                if (reply != null) reply.writeNoException();
                return true;
            }

            // 🔥 Code 1 可能是 updateSurface 或备用 addSurface
            if (code == 1) {
                if (dataSize > 100 && !isSurfaceActive) {
                    XposedBridge.log("NaviHook: [Binder] 🎯 Code 1 = Surface packet");
                    Surface s = tryParseSurface(data);
                    if (s != null) {
                        uiHandler.post(() -> injectNativeEngine(s));
                        isSurfaceActive = true;
                    }
                } else {
                    // 小包 = 心跳
                    if (reply != null) reply.writeNoException();
                }
                return true;
            }

            // Code 4: 握手
            if (code == 4) {
                XposedBridge.log("NaviHook: [Binder] 🎯 Code 4 = Handshake");
                if (reply != null) reply.writeNoException();
                return true;
            }
            
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Binder] Error: " + t);
        }
        return true;
    }

    // 🔥 暴力解析：尝试多个 offset
    private Surface tryParseSurface(Parcel data) {
        Surface result = null;
        int originalPos = data.dataPosition();
        
        // Surface 对象通常在前 32 字节内开始
        for (int offset = 0; offset <= 32; offset += 4) {
            if (offset >= data.dataSize() - 10) break; // 留足够空间
            
            try {
                data.setDataPosition(offset);
                Surface s = Surface.CREATOR.createFromParcel(data);
                if (s != null && s.isValid()) {
                    XposedBridge.log("NaviHook: [Binder] ✅ Surface at offset " + offset);
                    return s;
                }
            } catch (Exception e) {
                // 继续下一个 offset
            }
        }
        
        data.setDataPosition(originalPos);
        return null;
    }

    private void injectNativeEngine(Surface surface) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.autonavi.amapauto.MapSurfaceView", classLoader);
            Method m = XposedHelpers.findMethodExact(cls, "nativeSurfaceCreated", int.class, int.class, Surface.class);
            m.invoke(null, 1, 2, surface);
            XposedBridge.log("NaviHook: [Map] ✅ Engine injected!");
            
            // 可选：通知系统已就绪（如果需要）
        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Map] ❌ Inject failed: " + t);
            isSurfaceActive = false;
        }
    }
}
