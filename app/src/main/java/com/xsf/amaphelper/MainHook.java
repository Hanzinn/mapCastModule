package com.xsf.amaphelper;

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
    private static final String PKG_SELF = "com.xsf.amaphelper";

    private static final String CLS_BUS = "Lecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "Lecarx.naviservice.map.bz";
    private static final String CLS_GUIDE_INFO = "Lecarx.naviservice.map.entity.MapGuideInfo";
    private static final String CLS_STATUS_INFO = "Lecarx.naviservice.map.entity.MapStatusInfo";
    
    private static final String ACTION_AMAP_STANDARD = "AUTONAVI_STANDARD_BROADCAST_SEND";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }

        if (!lpparam.packageName.equals(PKG_XSF)) return;

        XposedBridge.log(TAG + ": Hooking Target: " + lpparam.packageName);

        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", lpparam.classLoader, 
            "attachBaseContext", Context.class, new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.args[0];
                if (context != null) {
                    registerCombinedReceiver(context, lpparam.classLoader);
                }
            }
        });
    }

    private void registerCombinedReceiver(Context context, ClassLoader cl) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                if (ACTION_AMAP_STANDARD.equals(action)) {
                    printBundleExtras(intent);
                    handleAmapStandardBroadcast(intent, cl);
                } 
                else if ("XSF_ACTION_SEND_GUIDE".equals(action)) {
                    handleAdbGuide(intent, cl);
                } else if ("XSF_ACTION_SEND_STATUS".equals(action)) {
                    handleAdbStatus(intent, cl);
                } 
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_AMAP_STANDARD);
        filter.addAction("XSF_ACTION_SEND_GUIDE");
        filter.addAction("XSF_ACTION_SEND_STATUS");
        
        context.registerReceiver(receiver, filter);
    }

    private void handleAmapStandardBroadcast(Intent intent, ClassLoader cl) {
        try {
            int keyType = intent.getIntExtra("KEY_TYPE", 0);
            if (keyType == 0) keyType = intent.getIntExtra("key_type", 0);
            if (keyType == 0) keyType = intent.getIntExtra("EXTRA_TYPE", 0);

            if (keyType == 10001) {
                sendStatusToBus(cl, 13); 
                String curRoad = getString(intent, "CUR_ROAD_NAME", "cur_road_name");
                String nextRoad = getString(intent, "NEXT_ROAD_NAME", "next_road_name");
                int icon = getInt(intent, "ICON", "icon");
                int distance = getInt(intent, "SEG_REMAIN_DIS", "seg_remain_dis");
                if (distance == 0) distance = getInt(intent, "distance", "distance");
                int routeRemainDis = getInt(intent, "ROUTE_REMAIN_DIS", "route_remain_dis");
                int routeRemainTime = getInt(intent, "ROUTE_REMAIN_TIME", "route_remain_time");

                sendGuideToBus(cl, curRoad, nextRoad, icon, distance, routeRemainDis, routeRemainTime);
            } 
            else if (keyType == 10019) {
                int state = getInt(intent, "EXTRA_STATE", "extra_state");
                if (state == 2) { 
                    sendStatusToBus(cl, 13); 
                    sendGuideToBus(cl, "正在定位...", "巡航中", 1, 1, 1, 60);
                } else if (state == 9 || state == 0) {
                    sendStatusToBus(cl, 29); 
                }
            }
        } catch (Throwable t) { XposedBridge.log(TAG + " Error: " + t); }
    }

    private String getString(Intent i, String k1, String k2) {
        String s = i.getStringExtra(k1);
        return (s != null) ? s : i.getStringExtra(k2);
    }
    private int getInt(Intent i, String k1, String k2) {
        int v = i.getIntExtra(k1, -1);
        return (v != -1) ? v : i.getIntExtra(k2, 0);
    }
    private void printBundleExtras(Intent intent) {
        try {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("【高德广播】").append(intent.getAction()).append("\n");
                for (String key : bundle.keySet()) {
                    sb.append(key).append(":").append(bundle.get(key)).append("\n");
                }
                XposedBridge.log(sb.toString());
            }
        } catch (Exception e) {}
    }

    private void handleAdbGuide(Intent intent, ClassLoader cl) {
        String cur = intent.getStringExtra("curRoad");
        if ("cruise_test".equals(cur)) {
             sendStatusToBus(cl, 13);
             sendGuideToBus(cl, "当前道路", "巡航中", 1, 1, 1, 60);
             return;
        }
        String next = intent.getStringExtra("nextRoad");
        int icon = intent.getIntExtra("icon", 1);
        int dist = intent.getIntExtra("distance", 0);
        sendGuideToBus(cl, cur, next, icon, dist, 0, 0);
    }
    private void handleAdbStatus(Intent intent, ClassLoader cl) {
        int status = intent.getIntExtra("status", 0);
        sendStatusToBus(cl, status);
    }

    private void sendGuideToBus(ClassLoader cl, String cur, String next, int icon, int dist, int totalDist, int totalTime) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            Class<?> guideClass = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Object guideInfo = XposedHelpers.newInstance(guideClass, 1);

            XposedHelpers.callMethod(guideInfo, "setCurRoadName", cur);
            XposedHelpers.callMethod(guideInfo, "setNextRoadName", next);
            XposedHelpers.callMethod(guideInfo, "setTurnId", icon); 
            XposedHelpers.callMethod(guideInfo, "setNextTurnDistance", dist);
            XposedHelpers.callMethod(guideInfo, "setRemainDistance", totalDist);
            XposedHelpers.callMethod(guideInfo, "setRemainTime", totalTime);

            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d0, guideInfo); 
            XposedHelpers.callMethod(busInstance, "a", msg);
        } catch (Throwable t) { XposedBridge.log(t); }
    }
    private void sendStatusToBus(ClassLoader cl, int status) {
        try {
            Class<?> busClass = XposedHelpers.findClass(CLS_BUS, cl);
            Object busInstance = XposedHelpers.callStaticMethod(busClass, "a");
            Class<?> statusClass = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            Object statusObj = XposedHelpers.newInstance(statusClass, 1);
            XposedHelpers.callMethod(statusObj, "setStatus", status);
            Class<?> wrapperClass = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapperClass, 0x7d2, statusObj); 
            XposedHelpers.callMethod(busInstance, "a", msg);
        } catch (Throwable t) { XposedBridge.log(t); }
    }
}
