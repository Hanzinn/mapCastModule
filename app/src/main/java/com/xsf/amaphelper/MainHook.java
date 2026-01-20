package com.xsf.amaphelper;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_XSF = "ecarx.naviservice";
    private static final String PKG_SELF = "com.xsf.amaphelper";
    
    private static final String CLS_BUS = "ecarx.naviservice.d.e";
    private static final String CLS_WRAPPER = "ecarx.naviservice.map.bz"; // 信封类
    private static final String CLS_STATUS_INFO = "ecarx.naviservice.map.entity.MapStatusInfo";
    private static final String CLS_GUIDE_INFO = "ecarx.naviservice.map.entity.MapGuideInfo";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName.equals(PKG_SELF)) {
            XposedHelpers.findAndHookMethod(PKG_SELF + ".MainActivity", lpparam.classLoader, 
                "isModuleActive", XC_MethodReplacement.returnConstant(true));
            return;
        }
        if (!lpparam.packageName.equals(PKG_XSF)) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.thisObject;
                sendAppLog(context, "✅ 模块加载成功，等待指令");
                registerReceiver(context, lpparam.classLoader);
            }
        });
    }

    private void registerReceiver(Context context, ClassLoader cl) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if ("XSF_ACTION_SUPER_TEST".equals(action)) {
                    startSuperExhaustiveTest(cl, ctx);
                } else if ("XSF_ACTION_SEND_GUIDE".equals(action)) {
                    sendExhaustiveGuide(cl, ctx);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("XSF_ACTION_SUPER_TEST");
        filter.addAction("XSF_ACTION_SEND_GUIDE");
        context.registerReceiver(receiver, filter);
    }

    /**
     * 🟢 地毯式轰炸方法：测试所有状态码 + 所有构造参数组合
     */
    private void startSuperExhaustiveTest(ClassLoader cl, Context ctx) {
        new Thread(() -> {
            try {
                int[] testStatuses = {1, 25, 13, 27, 2, 8}; // 可能的唤醒码
                int[] constructors = {0, 1, 2}; // 尝试 new Info(0), (1), (2)

                for (int status : testStatuses) {
                    for (int constr : constructors) {
                        sendAppLog(ctx, "👉 尝试组合: 状态码(" + status + ") + 构造参数(" + constr + ")");
                        sendExhaustiveStatus(cl, status, constr, ctx);
                        Thread.sleep(200); // 间隔防止粘包
                    }
                    Thread.sleep(500); // 每一组大码后休息一下
                }
                sendAppLog(ctx, "🏁 轰炸完成，请观察仪表盘是否亮起");
            } catch (Exception e) {
                sendAppLog(ctx, "❌ 测试线程崩溃: " + e.getMessage());
            }
        }).start();
    }

    private void sendExhaustiveStatus(ClassLoader cl, int status, int constructorArg, Context ctx) {
        try {
            // 1. 获取总线
            Class<?> busCls = XposedHelpers.findClass(CLS_BUS, cl);
            Object bus = XposedHelpers.callStaticMethod(busCls, "a");
            if (bus == null) { sendAppLog(ctx, "ERR: 总线对象为空"); return; }

            // 2. 构造 StatusInfo
            Class<?> infoCls = XposedHelpers.findClass(CLS_STATUS_INFO, cl);
            Object infoObj;
            try { 
                infoObj = XposedHelpers.newInstance(infoCls, constructorArg); 
            } catch (Throwable t) {
                if (constructorArg == 0) infoObj = XposedHelpers.newInstance(infoCls);
                else return; // 构造函数不支持则跳过
            }

            // 3. 寻找所有 int 字段并填入状态码 (地毯式填值)
            Field[] fields = infoCls.getDeclaredFields();
            for (Field f : fields) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    f.setInt(infoObj, status);
                }
            }

            // 4. 打包进信封 (0x7d2 = Status)
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);
            Object msg = XposedHelpers.newInstance(wrapCls, 0x7d2, infoObj);

            // 5. 发射
            XposedHelpers.callMethod(bus, "a", msg);

        } catch (Throwable e) {
            // 这里不弹吐司，日志记录即可，防止干扰
        }
    }

    private void sendExhaustiveGuide(ClassLoader cl, Context ctx) {
        try {
            Object bus = XposedHelpers.callStaticMethod(XposedHelpers.findClass(CLS_BUS, cl), "a");
            Class<?> guideCls = XposedHelpers.findClass(CLS_GUIDE_INFO, cl);
            Class<?> wrapCls = XposedHelpers.findClass(CLS_WRAPPER, cl);

            // 尝试三种构造函数
            for (int c = 0; c <= 2; c++) {
                Object gObj;
                try { gObj = XposedHelpers.newInstance(guideCls, c); } 
                catch (Throwable t) { if(c==0) gObj = XposedHelpers.newInstance(guideCls); else continue; }

                // 填入所有已知字段
                trySetField(gObj, "curRoadName", "全量测试路");
                trySetField(gObj, "nextRoadName", "成功街");
                trySetField(gObj, "turnId", 2);
                trySetField(gObj, "nextTurnDistance", 500);

                Object msg = XposedHelpers.newInstance(wrapCls, 0x7d0, gObj);
                XposedHelpers.callMethod(bus, "a", msg);
                sendAppLog(ctx, "🚕 路口模拟(构造" + c + ")已发出");
            }
        } catch (Exception e) {
            sendAppLog(ctx, "❌ 路口发送报错: " + e.getMessage());
        }
    }

    private void trySetField(Object obj, String field, Object val) {
        try { XposedHelpers.setObjectField(obj, field, val); } catch (Throwable t) {}
        try { XposedHelpers.setIntField(obj, field, (Integer)val); } catch (Throwable t) {}
    }

    private void sendAppLog(Context ctx, String log) {
        Intent i = new Intent("com.xsf.amaphelper.LOG_UPDATE");
        i.putExtra("log", log);
        ctx.sendBroadcast(i);
    }
}
