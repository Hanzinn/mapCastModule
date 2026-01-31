private void injectToDashboard(IBinder binder) {
        try {
            if (dashboardMgr == null) {
                XposedBridge.log("NaviHook: [Sys] ❌ dashboardMgr 为空");
                return;
            }

            // 1. 获取 f 字段 (连接对象)
            Object internalConn = XposedHelpers.getObjectField(dashboardMgr, "f");
            if (internalConn == null) {
                XposedBridge.log("NaviHook: [Sys] ❌ dashboardMgr.f 为空");
                return;
            }

            XposedBridge.log("NaviHook: [Sys] 目标对象类名: " + internalConn.getClass().getName());

            boolean injected = false;

            // 2. 🔥 暴力扫描：不找名字，找参数特征！
            // 遍历这个对象的所有方法
            for (Method m : internalConn.getClass().getDeclaredMethods()) {
                
                // 获取参数列表
                Class<?>[] params = m.getParameterTypes();
                
                // 🔍 指纹比对：参数数量必须是 2，且类型必须对得上
                if (params.length == 2 && 
                    params[0] == ComponentName.class && 
                    params[1] == IBinder.class) {
                    
                    // Bingo! 找到了！不管它叫什么名字，它肯定就是 onServiceConnected
                    try {
                        m.setAccessible(true); // 强制解锁权限
                        m.invoke(internalConn, new ComponentName(PKG_MAP, TARGET_SERVICE), binder);
                        
                        XposedBridge.log("NaviHook: [Sys] ✅✅✅ 注入成功！(指纹匹配)");
                        XposedBridge.log("NaviHook: [Sys] 捕获到的方法名: " + m.getName()); // 看看它到底叫什么
                        
                        injected = true;
                        triggerWakeUp();
                        break; // 成功后立即退出循环
                    } catch (Exception e) {
                        XposedBridge.log("NaviHook: [Sys] ⚠️ 找到疑似方法但调用失败: " + e);
                    }
                }
            }

            if (!injected) {
                XposedBridge.log("NaviHook: [Sys] ❌ 扫描结束，未找到符合 (ComponentName, IBinder) 的方法");
                // 兜底：如果还没找到，打印所有方法名，发给我分析
                XposedBridge.log("--- 调试：打印所有方法 ---");
                for (Method m : internalConn.getClass().getDeclaredMethods()) {
                    XposedBridge.log("Method: " + m.getName() + " Params: " + m.getParameterCount());
                }
            }

        } catch (Throwable t) {
            XposedBridge.log("NaviHook: [Sys] ❌ 注入过程崩溃: " + t);
        }
    }