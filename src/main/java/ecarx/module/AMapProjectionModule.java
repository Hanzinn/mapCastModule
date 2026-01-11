package ecarx.module;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class AMapProjectionModule implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log("✅ 模块加载成功: " + lpparam.packageName);
        
        // 只对高德车机版
        if ("com.autonavi.amapauto".equals(lpparam.packageName)) {
            XposedBridge.log("🎯 高德地图已启动，准备Hook...");
            // 暂时不Hook任何东西，先测试模块稳定性
        }
    }
}

