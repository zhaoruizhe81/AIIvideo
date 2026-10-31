package com.huangguo.mobile;

import android.app.Application;
import android.util.Log;
import com.parse.Parse;

public final class HuangGuoApp extends Application {
    @Override // android.app.Application
    public void onCreate() {
        super.onCreate();
        // 上报要在其它东西之前起来：崩溃钩子挂得越晚，漏掉的启动期崩溃越多。
        // :spider 也会跑这个 onCreate —— 它只写不发（见 ReportQueue.canFlush），
        // 否则两个进程会抢同一批队列文件。
        boolean mainProcess = isMainProcess();
        Report.install(this, mainProcess);
        installCrashReporter();
        Parse.initialize(new Parse.Configuration.Builder(this).applicationId(getString(R.string.back4app_app_id)).clientKey(getString(R.string.back4app_client_key)).server(Endpoints.API_BASE).build());
        // 预热内置核心的运行环境（gojni 加载 + initCoreEnv），放后台线程。
        //
        // 这一步冷启动实测要 3 秒左右（日志里「核心就绪 Lib v31」那行之前）。
        // 以前它只在「保存并启动」时做，于是进程起来后第一次点测速要白等这 3 秒，
        // 用户看到的就是「明明只有一个节点，测速怎么这么慢」。
        // 放这儿预热不影响启动：不碰网络，只是把 so 和 Go 运行时加载进来。
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ProxyCore.get(HuangGuoApp.this).ensureEnv();
                } catch (Throwable error) {
                    // 预热失败不该崩进程，但也不能一点痕迹都没有 —— 不然预热一直
                    // 坏着没人知道，测速/代理第一次永远多等那 3 秒。
                    Log.d("AIIvideo", "[warmup] 代理核心预热失败：" + error);
                }
            }
        }, "proxy-core-warmup").start();

        if (mainProcess) {
            Report.event("app", "app_open", null);
        }
    }

    /** 当前进程是不是主进程。读不到就按主进程算 —— 宁可多报一次，别漏整进程。 */
    private boolean isMainProcess() {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader("/proc/self/cmdline"));
            String name = reader.readLine();
            return name == null || name.trim().equals(getPackageName());
        } catch (Throwable error) {
            return true;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (java.io.IOException ignored) {
                }
            }
        }
    }

    /**
     * 未捕获异常先落盘再交给系统。
     *
     * 顺序不能反：交出去之后进程就被回收了，这时候再想写文件已经晚了。
     * 也不在这儿发网络 —— 手机可能正断网，写本地、下次启动补发才是稳的。
     */
    private void installCrashReporter() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                Report.crash(error, "uncaught");
            } catch (Throwable ignored) {}
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }
}
