package com.xsf.amaphelper; // 1. 改这里

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "LSPosed_Navi";
    private static final String PKG_XSF = "ecarx.naviservice";
    
    // 2. 改这里，确保 Hook 自己的时候包名是对的
    private static final String PKG_SELF = "com.xsf.amaphelper"; 

    // ... (下面的代码保持不变，还是原来的逻辑) ...
    // ... 为了节省篇幅，这里省略了中间的逻辑代码，请保留之前完整版的逻辑 ...
    // ... 记得检查下 copy 过去的代码里 CLS_BUS 等常量还在 ...

    // (以下仅展示需要注意的 handleLoadPackage 部分)
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        
        // Hook 自己的APP，让界面显示“已激活”
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        // ... (后续 Hook XSF 的代码保持不变) ...
    }
    
    // ... (剩下的方法保持不变) ...
}
