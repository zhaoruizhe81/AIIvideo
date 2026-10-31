package com.huangguo.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * spider 通道的主进程侧：把一次能力调用变成一次跨进程 Binder 往返。
 *
 * <p>══════ 为什么存在（2026-10-19 根治记录）════════
 * spider 宿主搬进了 {@code :spider} 独立进程（见 {@link SpiderService} 的类注释），
 * 主进程这边需要一个客户端：绑服务、发请求、等应答。原来
 * {@code SpiderRuntime.call} 里的排队/世代语义<strong>原样搬到这里</strong>：
 *
 * <ul>
 *   <li>{@link #CALL_LOCK}：同一时刻最多一个在途调用。服务进程里的 SpiderRuntime
 *       还有自己的一把锁，这里是第一道 —— 把「排队浪费」挡在跨进程之前；</li>
 *   <li>{@link #GENERATION}：连点时排队的那几次拿到锁就自杀（前端 routeToken
 *       本来就会丢掉它们的结果）。</li>
 * </ul>
 *
 * <p>══════ 线程模型 ══════
 * {@link #call} 必须在后台线程（Binder 同步调用会阻塞这条线程到 spider 跑完，
 * 最长 timeoutMs）。绑定是异步的，首次调用会等绑定完成，最多等 3 秒 ——
 * 绑不上按超时报错，措辞和 spider 超时区分开。
 *
 * <p>══════ 服务生命周期 ══════
 * 首次调用 {@code bindService(BIND_AUTO_CREATE)} 拉起 :spider 进程，之后常驻
 * （渲染进程跟着常驻，省掉每次调用的新建开销）。{@link #shutdown} 在主界面
 * onDestroy 时解绑 + stopService，进程随之回收，不占后台内存。
 */
final class SpiderClient {

    private static final String TAG = "HG.Spider";

    /** 绑定服务最多等这么久。正常的 startService 级绑定是毫秒级，3 秒是十倍余量。 */
    private static final long BIND_TIMEOUT_MS = 3_000L;

    private static final Semaphore CALL_LOCK = new Semaphore(1, true);
    private static final AtomicLong GENERATION = new AtomicLong();

    private static volatile ISpiderChannel channel;
    private static volatile CountDownLatch bound = new CountDownLatch(1);
    private static ServiceConnection connection;
    private static Context appContext;
    private static boolean bindAttempted;
    /** 「绑定失败」到底是哪一种，见 channelOf 里那段说明。 */
    private static volatile boolean bindRefused;
    private static volatile boolean disconnected;
    private static volatile boolean bindingDied;

    /**
     * 这一次宿主生命周期里 host_stop 报过没有。
     *
     * 绑上时清零，报过就置位 —— 否则 {@link #shutdown()} 和
     * {@link ServiceConnection#onServiceDisconnected} 会各报一次，
     * 一个 start 配出两个 stop，对账反而更乱。
     */
    private static volatile boolean hostStopReported;

    private SpiderClient() {
    }

    /**
     * 执行一次能力调用。<b>后台线程调用</b>。
     *
     * @return spider 的 payload（契约 §三）
     * @throws IOException 排队超时、被新请求取代、绑定失败、远端报错 —— message
     *                     都是能直接给用户/日志看的那一句
     */
    static JSONObject call(Context ctx, String source, String cap,
                            JSONObject args, int timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("SpiderClient.call 必须在后台线程调用。");
        }
        final long budget = timeoutMs > 0 ? timeoutMs : 15_000L;
        final long gen = GENERATION.incrementAndGet();

        if (!CALL_LOCK.tryAcquire(budget, TimeUnit.MILLISECONDS)) {
            throw new IOException("spider 排队超时（另一个调用还占着宿主）。");
        }
        try {
            if (gen != GENERATION.get()) {
                throw new IOException("被更新的请求取代");
            }
            final ISpiderChannel ch = channelOf(ctx, budget);
            final JSONObject req = new JSONObject()
                    .put("source", source)
                    .put("cap", cap)
                    .put("args", args == null ? new JSONObject() : args)
                    .put("timeoutMs", budget)
                    // 代理快照。内置核心的地址只在主进程内存里，必须显式带过去；
                    // mode 也带上，服务侧用它对齐「用户按了直连」这类全局开关。
                    .put("proxyMode", AiiHttp.proxyModeOrEmpty())
                    .put("effectiveProxy", AiiHttp.effectiveProxy())
                    // 盾后站点的 clearance：主进程有窗口、过盾只要 1~2 秒，
                    // :spider 进程的 WebView 没有窗口，同一个挑战 45 秒都过不去。
                    // 每次调用把主进程的 cookie 带过去，子进程就不用再挑战。
                    // 非盾后站点这里拿到空串，代价只是多一次 ensureWarm 的空判断。
                    .put("cookieHint", cookieHintFor(ctx));
            final String reply = ch.call(req.toString());
            final JSONObject out = new JSONObject(reply);
            if (!out.optBoolean("ok", false)) {
                // 盾后站点 headless 过不去（有 shieldUrl 标记）→ 抛结构化信号，
                // bridge 命令层捕获后用主进程有窗口的 SiteFetchWebView 过盾取 HTML，
                // 回填 __shieldBody 重试一次（方案 1 兜底）。
                if (out.has("shieldUrl")) {
                    throw new ShieldSignal(out.optString("shieldUrl", ""),
                            out.optString("error", "盾后站点过盾失败"));
                }
                throw new IOException(out.optString("error", "spider 调用失败"));
            }
            return out.has("payload") ? out.getJSONObject("payload") : new JSONObject();
        } finally {
            CALL_LOCK.release();
        }
    }

    /**
     * 报一条 host_stop。一次生命周期只报一次，且只在真的绑过之后才报 ——
     * 没绑过就报 stop 会造出一堆配不上 start 的孤儿事件。
     */
    private static void reportHostStop(String reason) {
        if (hostStopReported || !bindAttempted) return;
        hostStopReported = true;
        try {
            JSONObject props = new JSONObject();
            props.put("reason", reason);
            props.put("pid", android.os.Process.myPid());
            Report.event("spider", "host_stop", props);
        } catch (Throwable ignored) {
        }
    }

    /** 当前源是不是盾后站点；是就顺手把主进程的 clearance 取出来。 */
    private static String cookieHintFor(Context ctx) {
        try {
            SiteProfile profile = SiteProfile.current(ctx);
            String origin = profile == null || profile.origin == null ? "" : profile.origin;
            if (origin.length() == 0 || !SiteFetchWebView.isShielded(origin)) return "";
            return SiteFetchWebView.cookieSnapshot();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 主界面退出时调：解绑 + 停服务，:spider 进程随之被系统回收。
     * 在飞调用会被服务侧 {@code onDestroy → SpiderRuntime.shutdown()} 打断。
     */
    static void shutdown() {
        final ServiceConnection c = connection;
        if (c != null) reportHostStop("unbind");
        connection = null;
        channel = null;
        bound = new CountDownLatch(1);
        bindAttempted = false;
        if (c != null && appContext != null) {
            // 埋点：宿主进程的终点，由**掌握生命周期的那一方**报。
            //
            // 原来只有 :spider 自己在 onDestroy 里写一条 host_stop，而那个进程
            // canFlush=false（只写不发，队列交给主进程统一发）。两个问题叠在一起：
            //   ① 进程被系统杀掉时 onDestroy 根本不执行，那条事件压根没写；
            //   ② 就算写了，也要等主进程下一次启动才可能发出去。
            // 结果是 2.0.23 上 host_start 728 / host_stop 494 —— 110 台设备缺 234 条，
            // 而且缺的全是「宿主怎么死的」这种最该知道的情况。
            //
            // 主进程活得比宿主久，队列也归它发，所以由它来说这句话最可靠。
            // SpiderService.onDestroy 里那条已经在 2.0.24 删掉了 —— 两边都写的话，
            // 一次启动会配出两个终点。现在一个 start 对一个 stop：
            //   count(host_start) == count(host_stop)
            try {
                appContext.unbindService(c);
                appContext.stopService(new Intent(appContext, SpiderService.class));
            } catch (Throwable ignored) {
            }
        }
    }

    // ── 内部：绑定 ────────────────────────────────────────────────────

    private static ISpiderChannel channelOf(Context ctx, long budgetMs) throws IOException {
        final ISpiderChannel ready = channel;
        if (ready != null) return ready;
        bind(ctx.getApplicationContext());
        try {
            if (!bound.await(Math.min(budgetMs, BIND_TIMEOUT_MS), TimeUnit.MILLISECONDS)) {
                throw new IOException("spider 服务绑定超时（" + BIND_TIMEOUT_MS + "ms）。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("spider 调用被中断。");
        }
        final ISpiderChannel ch = channel;
        if (ch == null) {
            // 以前只说「绑定失败」，看不出是哪种：bindService 直接被拒、服务连上又断、
            // 还是 binder 死亡。这三种的修法完全不同，所以把理由带上。
            throw new IOException("spider 服务绑定失败："
                    + (bindRefused ? "bindService 被系统拒了（服务进程起不来）"
                    : disconnected ? "服务连上又断开（:spider 进程中途死了）"
                    : "回调到了但没有拿到 channel（binder 为空）")
                    + "。");
        }
        return ch;
    }

    private static synchronized void bind(final Context app) {
        if (bindAttempted && connection != null) return;
        appContext = app;
        bindAttempted = true;
        // 新的一次绑定 = 新的一次宿主生命周期，host_stop 可以再报一次。
        hostStopReported = false;
        disconnected = false;
        bindingDied = false;
        bound = new CountDownLatch(1);
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                channel = ISpiderChannel.Stub.asInterface(service);
                bound.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // 进程被杀：置空让下一次调用重新走绑定。bound 保持原值没关系 ——
                // 它只在 ensure 的等待窗口里被消费，断连时那个窗口早已结束。
                disconnected = true;
                channel = null;
                // 光清 channel 不够：bindAttempted/connection 不复位的话，bind() 会
                // 直接 return（bound 闩锁还是放开的旧值），重连窗口内所有调用立刻
                // 报「绑定失败」。解绑旧注册 + 复位，下一次 call() 才会真正重走
                // bind()（那边会 new 闩锁和新 connection）。unbindService 不碰
                // SpiderClient.class 锁，和后台线程的 bind() 互不了卡。
                if (connection != null && appContext != null) {
                    try {
                        appContext.unbindService(connection);
                    } catch (Throwable ignored) {
                    }
                }
                connection = null;
                // 这条路径比 shutdown() 重要得多：它才是「宿主自己死了」的那种死法，
                // 而进程内的 onDestroy 在这种情况下一个字都写不出来。
                // host_stop 依赖 bindAttempted（见 reportHostStop），先报再清。
                reportHostStop("disconnected");
                bindAttempted = false;
            }

            @Override
            public void onBindingDied(ComponentName name) {
                // API 26+ 才有：绑定还活着但服务进程没了。和「断开」分开记，
                // 因为它不会重新连上，下一次必须重新 bind。
                disconnected = true;
                bindingDied = true;
                channel = null;
                // 旧注册不解掉会一直泄漏（反复死亡重绑时每轮漏一个 binder）；
                // 解绑 + 复位，让下一次 call() 真正重走 bind()。
                if (connection != null && appContext != null) {
                    try {
                        appContext.unbindService(connection);
                    } catch (Throwable ignored) {
                    }
                }
                connection = null;
                bindAttempted = false;
            }
        };
        final Intent intent = new Intent(app, SpiderService.class);
        // BIND_AUTO_CREATE：让系统替我们维护 :spider 的生命周期；
        // BIND_IMPORTANT 之外不加任何提升 —— 它是后台工作者，不该抢前台调度。
        bindRefused = false;
        if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            // 绑定请求本身被拒（多为进程处于错误状态）：把闩锁放开，让上面的
            // channelOf 抛「绑定失败」而不是干等到超时。
            bindRefused = true;
            bound.countDown();
        }
    }
}
