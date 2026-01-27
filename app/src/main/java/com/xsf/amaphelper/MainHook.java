package com.xsf.amaphelper;

import android.util.Log;
import java.lang.reflect.Field;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG_SERVICE = "ecarx.naviservice";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(PKG_SERVICE)) return;

        XposedBridge.log("NaviSpy: 🚀 V124 全息透视版启动");

        // 🟢 重点监控 MapGuideInfo (Vendor=0 的那个对象)
        try {
            XposedHelpers.findAndHookConstructor(
                "ecarx.naviservice.map.entity.MapGuideInfo", 
                lpparam.classLoader, 
                int.class, 
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int vendor = (int) param.args[0];
                        Object guideInfo = param.thisObject;
                        
                        // 只看 Vendor=0 的（官方数据）
                        if (vendor == 0) {
                            XposedBridge.log("NaviSpy: 📦 [捕获] MapGuideInfo(V0)");
                            
                            // 1. 打印所有字段值 (抄作业的标准答案)
                            String fields = dumpFields(guideInfo);
                            XposedBridge.log("NaviSpy: 📝 字段详情 -> " + fields);
                            
                            // 2. 打印调用栈 (找到 VIP 密道入口)
                            // 加上这个，我们就能知道是哪个类在发数据！
                            XposedBridge.log("NaviSpy: 🔗 调用来源 -> \n" + Log.getStackTraceString(new Throwable()));
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("NaviSpy: ❌ 监控 GuideInfo 失败: " + t);
        }
    }

    // 反射遍历所有字段
    private String dumpFields(Object obj) {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> clazz = obj.getClass();
            // 遍历当前类及父类的字段
            while (clazz != null) {
                Field[] fields = clazz.getDeclaredFields();
                for (Field f : fields) {
                    f.setAccessible(true);
                    String name = f.getName();
                    Object value = f.get(obj);
                    sb.append(name).append("=").append(value).append("; ");
                }
                clazz = clazz.getSuperclass(); // 继续查父类
            }
        } catch (Exception e) {
            sb.append("解析异常");
        }
        return sb.toString();
    }
}