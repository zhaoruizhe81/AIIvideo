package com.huangguo.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Spider 运行时：一个隐藏 WebView 装一段 spider 源码，调一个能力，把结果的 payload 带回来。
 *
 * <p>阶段 0 只有一条链路：建页 → 注入 spider → 注入驱动 → 等 {@code done} → 销毁。
 * 没有缓存、没有页面复用、没有 crypto（设计文档 §4 里那个 op 留到后面）。
 *
 * <p>══ 为什么是隐藏 WebView，不是 JS 引擎 ══
 * 见 {@code docs/history/2026-10-19-spider运行时设计.md} §二：TVBox 生态的 spider 全部是按「有 DOM、
 * 有 Promise、有真实内核」写的，换 Rhino/QuickJS 得先补一层假 DOM，补出来的行为差异
 * 比换语言的收益大。宿主页那条 CSP（设计 §三）把它的能力削到只剩一个 bridge 调用，
 * 安全性由「不暴露」而不是「不执行」来保证。
 *
 * <p>══ 线程模型（和 SiteFetchWebView 同一套，理由也一样）══
 * <ul>
 *   <li>{@link #call} 在<b>后台线程</b>上阻塞执行；主线程只做 WebView 操作
 *       （{@link Handler#post} + {@link CountDownLatch}）。</li>
 *   <li>永不反向阻塞主线程：主线程正忙着跑那个 WebView，堵它等于自己等自己。</li>
 *   <li>bridge 的调用发生在 WebView 的 Java 桥线程上，那条线程可以被安全阻塞 ——
 *       页面里的 {@code ctx.call('net')} 本来就是同步语义，它就在等这份结果。</li>
 * </ul>
 *
 * <p>══ 一次调用一个页面生命周期 ══
 * 调用结束（成功、失败、超时都算）就把 WebView 销毁，下一次调用重新建。见
 * {@link #releasePage} 里的说明。
 */
final class SpiderRuntime {

    /** 阶段 0 的 ABI 号。壳把它和 spider 的 requires 比对。 */
    static final int ABI = 1;

    private static final String TAG = "HG.Spider";

    /**
     * 宿主页地址。<b>必须是 appassets origin</b>，和 {@code app.html} 同源。
     *
     * <p>为什么不能用 {@code loadDataWithBaseURL}：那个的 origin 是 {@code null}，
     * CSP 的行为和 cookie 都跟真 origin 不一样，宿主页那条 CSP 就成了摆设。
     * 用 {@link WebViewAssetLoader} 把 assets/ 挂到
     * {@code https://appassets.androidplatform.net/assets/}，拿到的就是一个正经 https origin。
     */
    private static final String HOST_PAGE =
            "https://appassets.androidplatform.net/assets/spider-host.html";

    /** {@code net} 的单次超时。和契约 §七 一致：AiiHttp 那族默认 15 秒。 */
    private static final int NET_TIMEOUT_S = 15;

    /**
     * headless WebView 尝试过盾的上限。
     *
     * <p>没有窗口的 WebView 过不了 Cloudflare 的挑战（实测两次 45 秒都超时），
     * 而它的 {@code ensureWarm} 默认要跑满两轮。给它 15 秒就是「试一下，
     * 不行赶紧说」——主进程有窗口，同样的挑战 1~2 秒就过。
     */
    private static final long SHIELD_HEADLESS_MS = 15_000L;

    /**
     * 盾后站点要不要先自己 headless 试一次过盾。
     *
     * <p>false = 直接抛 {@link ShieldSignal} 交主进程（现状，见 net 里的那段账）。
     * :spider 的 WebView 没有窗口，挑战从来过不去，先试只是白等 15 秒；主进程
     * 有窗口，同样的挑战 0.5~3 秒就过。留着开关是为了「哪天 headless 能过了」时
     * 一行改回来，而不是再翻一遍当天的日志去还原这段判断。
     */
    private static final boolean SELF_SHIELD_FIRST = false;

    /**
     * 响应体上限 4MB。
     *
     * <p>超了<b>报错而不是截断</b>（设计 §四）：截断的 JSON 后面会在
     * {@code JSON.parse} 里炸，报出来是「解析失败」而不是「太大了」，
     * 查起来比超时还费劲。
     */
    private static final int MAX_NET_BODY = 4 * 1024 * 1024;

    /**
     * 看门狗之外，调用线程自己再等的一点余量。
     *
     * <p>看门狗在主线程上跑到点才会 {@code countDown}，主线程偶尔被别的事占住，
     * 那一下会晚个几十毫秒。等得比看门狗久一点，让超时的措辞永远出自看门狗
     * （用户看到的必须是「spider 超时（15s）」这一句，不能因为主线程抖一下
     * 就变成另一种说法）。
     */
    private static final long AWAIT_GRACE_MS = 2_000L;

    private static final String[] EMPTY_HOSTS = new String[0];

    /**
     * 探测能力集用的伪 cap。它不是 spider 的一个能力，是一次「读一遍 __spiderSite」
     * 的请求：不跑驱动脚本、不等 done，注入完源码直接求值。
     *
     * <p>为什么不用安装时解析文件头的正则：{@code caps} 是运行时的事实（源里定义了
     * 哪个函数），正则只能猜字面量。而猜错的代价是「源明明实现了，前端说没有」——
     * 这正是这一轮在修的 bug。求值一次几百毫秒，按 id 缓存在前端，一次会话一次。
     */
    static final String META_CAP = "__meta";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 串行化整次调用。
     *
     * <p>WebView 一个进程只有一套渲染宿主，页面状态也只有一份；两个 spider 同时
     * 往同一个页面上注入源码，{@code __spiderSite} 会互相覆盖，症状是「A 源返回了
     * B 源的卡片」，这种 bug 基本查不出来。排队比并发便宜得多。
     */
    private static final Semaphore CALL_LOCK = new Semaphore(1, true);

    /**
     * 一个 WebView 对象连续服务多少次调用之后强制重建。
     *
     * <p>复用的收益是省掉 {@code new WebView()} 那一段渲染进程初始化（实测
     * 100~300ms，低端机更久）；风险是页面状态会攒 —— 渲染线程的堆、WebView 内部
     * 的缓存、spider 如果漏了定时器也会一直挂着。所以不无限复用，跑够一轮就换新的。
     *
     * <p>24 这个数没有理论依据，是「一天里正常的切换次数」量级：少于它就是
     * 频繁重建，多于它就要开始担心内存。
     */
    private static final int MAX_REUSE = 24;

    /** 当前这个 WebView 已经复用了几次。**只在主线程读写**。 */
    private static int reuseCount = 0;

    /**
     * 调用世代。每次 {@link #call} 领一个号，号最大的那次才算数。
     *
     * <p>为什么需要它：{@link #CALL_LOCK} 是串行的，而用户会在 1 秒内连点三个分类。
     * 于是队列里排着三次调用、每次 1.4~1.6 秒 —— 用户点第三下之后要等前两次跑完才
     * 轮到他真正想看的那一屏，体感就是「快速切换特别卡」。
     *
     * <p>而前端那边 {@code routeToken} 早就把前两次的结果丢掉了（它只认最后一次导航）。
     * 也就是说那些排队的时间是**纯粹白等的**：跑出来的数据没人要。
     *
     * <p>所以：排队等锁的那几次，拿到锁第一件事是看自己的号还在不在最新 —— 不在就
     * 立刻让出，不跑。
     */
    private static final java.util.concurrent.atomic.AtomicLong GENERATION =
            new java.util.concurrent.atomic.AtomicLong();

    // ── 单例状态（全部只在主线程写，读的时候容忍旧值）────────────────────

    private static WebView webView;
    private static WebViewAssetLoader loader;

    /** 当前这一次调用的会话。bridge 从这里取，见 {@link #currentSession()}。 */
    private static volatile Session session;

    private SpiderRuntime() {
    }

    /**
     * 一次能力调用的全部状态。<b>跨线程</b>，所以字段该 volatile 的 volatile、
     * 该加锁的加锁。
     *
     * <p>状态放在这里而不是 {@code SpiderBridge} 里：bridge 会被 WebView 在多条
     * 线程上调，让它持状态等于让协议层持状态，出问题时分不清是「页面还活着」还是
     * 「Java 侧没清」。
     */
    static final class Session {
        final String cap;
        final String spiderSource;
        final String argsJson;
        /** 领到的世代号。见 {@link #GENERATION}。 */
        final long gen;
        final long timeoutMs;
        final long beganAt = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        final Runnable watchdog;

        /** 注入后从 {@code __spiderSite.hosts} 读出来的一份快照，net 只认这份。 */
        volatile String[] hosts = EMPTY_HOSTS;

        /**
         * 调用开始那一刻的源配置。net 用它走 {@link NetRoute}（当前源声明的直连/代理
         * 规则），而不是全局代理设置 —— 两条路由真相并存的话，源声明了走代理而全局
         * 是直连时，适配器能取到、spider 取不到，症状是「这个源在网页里好好的，
         * 换成 spider 就打不开」。取不到（没配置）时退回全局，至少不比现在差。
         */
        volatile SiteProfile profile;

        /** 应用上下文，给 net 里的诊断日志用（模式/代理串）。 */
        android.content.Context ctx;

        /** onPageFinished 可能对同一页触发多次（子框架、重定向），注入只做一次。 */
        volatile boolean injected;

        /** 这次只探测元信息，不跑能力。见 {@link #META_CAP}。 */
        final boolean metaOnly;

        /** done 回来（或超时/关闭）之后置位，之后 bridge 的一切 op 都丢弃。 */
        volatile boolean finished;

        volatile boolean timedOut;

        /**
         * 盾后站点 headless 兜底也过不去时，被挡的原始地址（方案 1：过盾外包）。
         * net 在 bridge 线程上抛 ShieldSignal 时类型信息会被 SpiderBridge 压平成
         * error 字符串，url 靠这个字段随会话走，call() 收尾时重建信号抛给服务层。
         */
        volatile String shieldUrl;

        /**
         * 主进程过盾取回的各页正文：{@code url → html}（方案 1 的回填）。
         *
         * <p>2026-10-19 修：原来只有单条，且被塞进「重试调用的 args」，然后在 net 里
         * 从 {@code args.optString("__shieldBody")} 读 —— 读不到，因为 net 的 args
         * 是 spider JS 自己拼的（url/method/headers），宿主塞进去的键根本不经那条路。
         * 真机日志的形态就是：主进程「抓到页面 199139 字节」，紧接着重试调用又是
         * 15 秒 headless 超时，`op=net 命中过盾回填` 一次都没打出来。
         *
         * <p>现在改名 {@code __shieldPages}（url→html 表），call() 在入口摘走挂到会话上
         * （args 照样 sanitize，不给 spider JS 看见），net 直接查会话。做成表而不是
         * 单条，是因为一次重试里 home 和详情页可能先后撞盾 —— 只带最新一张的话，
         * 第二张一撞就把第一张挤掉，重试永远追不上。
         */
        volatile JSONObject cannedPages;

        /** 成功时的 payload；失败时留在 {@link #error}。 */
        JSONObject result;
        String error;

        Session(String cap, String spiderSource, String argsJson, long timeoutMs, long gen,
                JSONObject cannedPages) {
            this.cap = cap;
            this.metaOnly = META_CAP.equals(cap);
            this.spiderSource = spiderSource;
            this.argsJson = argsJson;
            this.timeoutMs = timeoutMs;
            this.gen = gen;
            this.cannedPages = cannedPages;
            this.watchdog = watchdog(this);
        }
    }

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 剥掉宿主握手键（{@code __} 开头，如 {@code __shieldBody} / {@code __shieldUrl}）
     * 后序列化成 argsJson。这些键是主进程和运行时之间的信使，绝不能漏进 spider JS
     * 的 args —— 源拿到不认识的键只会原样拼进请求，等于把内部协议泄露给站点。
     */
    static String sanitizeArgs(JSONObject args) {
        if (args == null) return "{}";
        JSONObject out = new JSONObject();
        Iterator<String> it = args.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith("__")) continue;
            try {
                out.put(k, args.opt(k));
            } catch (Throwable ignored) {
            }
        }
        return out.toString();
    }

    /**
     * 撞盾时给日志/界面看的那一句。
     *
     * <p>把「okhttp 为什么过不去」写清楚（TLS 指纹绑 cookie，不是 cookie 的锅），
     * 免得下次又有人往「先取 cookie 再走 okhttp」那个方向改。
     */
    private static String shieldMessage(String host, Exception httpFailure, String headlessError) {
        return "net 失败：" + host + " 在 Cloudflare 后面，okhttp 被挡（"
                + (httpFailure == null ? "无响应" : httpFailure.getMessage())
                + "）" + (headlessError == null
                ? "，交给主进程过盾"
                : "，改用真浏览器内核也没过：" + headlessError);
    }

    /**
     * 把 {@code url → html} 表塞进调用 args 用的键名。
     *
     * <p>宿主和运行时之间的握手键统一以 {@code __} 开头，{@link #sanitizeArgs}
     * 会把它们全部剥掉，不落到 spider JS 手里。
     */
    static final String ARG_SHIELD_PAGES = "__shieldPages";

    /**
     * 在会话的回填表里找这份地址对应的正文，找到就返回，找不到给 null。
     *
     * <p>严格相等最安全，但源不一定逐字复用同一个地址：主进程过盾用的是被挡的那次
     * 请求地址，spider 回填后再请求时可能带上/去掉尾斜杠、换成别的路径参数。所以
     * 同一主机下再放宽一档「路径前缀一致」，其余一律不吃 —— 把别的页面的正文
     * 当成本页喂给解析器，比超时更难查。
     */
    private static String cannedBodyFor(Session s, String url) {
        final JSONObject pages = s == null ? null : s.cannedPages;
        if (pages == null || pages.length() == 0) return null;
        final String exact = pages.optString(url, "");
        if (exact.length() > 0) return exact;
        final String host = hostOf(url);
        final String path = pathOf(url);
        final Iterator<String> it = pages.keys();
        while (it.hasNext()) {
            final String canned = it.next();
            if (!host.equals(hostOf(canned))) continue;
            final String a = pathOf(canned);
            if (a.length() > 1 && path.length() > 1 && (a.startsWith(path) || path.startsWith(a))) {
                final String body = pages.optString(canned, "");
                if (body.length() > 0) return body;
            }
        }
        return null;
    }

    /** 日志用：把回填表压成「地址(字节数), …」。 */
    private static String describeCanned(JSONObject pages) {
        final StringBuilder sb = new StringBuilder();
        final Iterator<String> it = pages.keys();
        while (it.hasNext()) {
            final String url = it.next();
            if (sb.length() > 0) sb.append(", ");
            sb.append(url).append('(').append(pages.optString(url, "").length()).append(')');
        }
        return sb.toString();
    }


    /**
     * 把一个 spider 源码字符串装进宿主页并调用一次能力。<b>后台线程调用。</b>
     *
     * <p>调用线程被阻塞到 {@code done} 回来、或者看门狗把页面杀掉为止。
     *
     * @return spider 返回的 payload 原样（阶段 0 里 {@code home} 是
     *         {@code {"items":[...]}}）
     * @throws Exception message 就是给用户/日志看的那一句，例如
     *         {@code "spider 超时（15s）"}、{@code "spider 没有实现 home"}
     */
    static JSONObject call(Context ctx, String spiderSource, String cap,
                           JSONObject args, int timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 主线程等闩锁 = 等自己把 WebView 跑完，必死锁。这条在开发期就该炸出来。
            throw new IllegalStateException("SpiderRuntime.call 必须在后台线程调用。");
        }
        if (spiderSource == null || spiderSource.length() == 0) {
            throw new IllegalArgumentException("spider 源码是空的。");
        }
        if (cap == null || cap.length() == 0) {
            throw new IllegalArgumentException("spider 调用缺少 cap。");
        }
        final long budget = timeoutMs > 0 ? timeoutMs : NET_TIMEOUT_S * 1000L;

        // 2026-10-19：宿主搬进 :spider 独立进程（SpiderService），这边不再有
        // Activity 可挂 —— WebView 改成免窗口 headless 跑（见 startPage）。
        // 应用上下文全程够用：assets 供给、cookie、JS 执行都不依赖 window。
        final Context app = ctx.getApplicationContext();
        if (app == null) {
            throw new IOException("spider 跑不了：拿不到应用上下文。");
        }

        // 领号要尽早：越早领，前面那次越早知道自己已经过期。
        final long gen = GENERATION.incrementAndGet();
        // 方案 1 的回填：主进程过盾成功后，把 url→html 表挂在「重试调用」的 args 上
        // 塞回来。必须在这里摘走 —— net 的 args 是 spider JS 自己拼的，看不见调用 args。
        final JSONObject cannedPages = args == null ? null : args.optJSONObject(ARG_SHIELD_PAGES);
        if (cannedPages != null && cannedPages.length() > 0) {
            Log.i(TAG, "本次调用带过盾回填：" + cannedPages.length() + " 页，共 "
                    + describeCanned(cannedPages));
        }
        // 只作废「还在排队」的，不动「正在跑」的那一次。
        //
        // 试过连正在跑的也杀掉（省掉它剩下的那一两秒），但 session 字段是在拿到锁
        // 之后才赋值的，于是「新请求检查 session」和「旧请求赋值 session」之间有窗口：
        // 实测四连点时，最后生效的反而是第三次，第四次被自己误杀了。
        // 结果比慢更糟 —— 用户看到的不是他最后点的那一屏。
        //
        // 现在的语义：正在跑的一定跑完（它顶多多花一次往返），排队的一个都不跑。
        // 四连点的总耗时 = 第一次剩余 + 最后一次，两段网络时间封顶。

        // 排队也算在预算里：等锁等掉的钱不该再从看门狗里另算一份，
        // 否则用户点一下要等 30 秒才看到「超时」。
        final long lockBeganAt = System.currentTimeMillis();
        if (!CALL_LOCK.tryAcquire(budget, TimeUnit.MILLISECONDS)) {
            throw new IOException("spider 排队超时（另一个调用还占着宿主）。");
        }
        Session s = null;
        try {
            // 拿到锁的第一件事：还是不是最新那次？不是就立刻让出去 ——
            // 抢在 startPage 之前判断，省掉的正是用户最不耐烦的那一两秒。
            if (gen != GENERATION.get()) {
                throw new IOException("被更新的请求取代");
            }
            // 等锁实际耗掉的时间从预算里扣掉（保底 1 秒），看门狗和 await 用的
            // 都是这份已扣减的值 —— Session 挪到拿到锁之后才建就是为此。
            // 不然「排队满预算 + 再跑满预算」最坏等 2×timeout，和上面承诺相反。
            final long waitedMs = System.currentTimeMillis() - lockBeganAt;
            s = new Session(cap, spiderSource,
                    sanitizeArgs(args), Math.max(1_000L, budget - waitedMs), gen, cannedPages);
            // 当前生效的源配置。取不到就留 null，net 退回全局路由（见 Session.profile）。
            s.ctx = app.getApplicationContext();
            // profile 是 net 走哪条路由的唯一依据。取不到就别硬撑 —— 那就退回老的
            // 全局路由（不带 NetRoute），并在日志里说清楚，否则「App 能取、spider 不能」
            // 这种问题会被误判成站点抽风。
            try {
                s.profile = SiteProfile.current(s.ctx);
            } catch (Throwable e) {
                UserSource.log("spider 拿不到当前源配置：" + e);
            }
            // 不兜底构造：currentSite() 是「当前生效配置」的唯一入口，它给了 null 说明
            // 配置本身有问题，那时候再拼一份出来只会掩盖真正的错。日志里说清楚就够。
            session = s;
            startPage(app, s);
            if (!s.done.await(s.timeoutMs + AWAIT_GRACE_MS, TimeUnit.MILLISECONDS)) {
                // 走不到这里正常情况：看门狗到点会自己 countDown。只有主线程长时间
                // 被占住才会落到这条兜底上 —— 那也仍然是「超时」，处置一样：杀页。
                s.timedOut = true;
                fail(s, timeoutMessage(s));
            }
            if (s.error != null) {
                if (s.shieldUrl != null) throw new ShieldSignal(s.shieldUrl, s.error);
                throw new IOException(s.error);
            }
            return s.result == null ? new JSONObject() : s.result;
        } finally {
            // s 可能为 null：世代检查没过时根本没建 Session。
            if (s != null) {
                if (session == s) session = null;
                releasePage(s);
            }
            CALL_LOCK.release();
        }
    }

    /**
     * 释放宿主。<b>Service onDestroy 必须调</b>（SpiderService，:spider 进程），
     * 漏了渲染进程常驻。
     *
     * <p>和 SiteFetchWebView 一样：只在 field 上置 null 是不够的，渲染进程不会退，
     * 页面里的定时器还在跑。另外这里顺手把在飞的调用打断，别让调用方干等到超时。
     */
    static void shutdown() {
        final Session s = session;
        session = null;
        if (s != null) fail(s, "spider 宿主已关闭。");
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                destroyOnMain();
            }
        });
    }

    // ── 给 SpiderBridge 用的口子（状态都在这一侧）──────────────────────

    /** 当前会话；没有就是 {@code null}（bridge 据此丢弃一切 op）。 */
    static Session currentSession() {
        return session;
    }

    /**
     * {@code net} 的实现。只放行 https + 当前 spider 自报的 hosts。
     *
     * <p>入参 {@code {url, method, headers, body, charset, format, keep}}。四个可选项：
     * <ul>
     *   <li>{@code charset} 缺省 utf-8：既用来解响应体，也用来编码表单里的非 ASCII
     *       （老站 GBK 靠它）。</li>
     *   <li>{@code format} 只在 POST 上有意义，缺省 {@code form}。</li>
     *   <li>{@code keep} 要回传的 cookie 名字，缺省空（不回传）。</li>
     * </ul>
     *
     * <p>跑在 bridge 线程上，会阻塞到 {@link AiiHttp} 返回 —— 这是同步语义该有的样子：
     * 页面里的 {@code ctx.call('net', …)} 就在等这一份 body。
     */
    static JSONObject net(Session s, JSONObject args) throws Exception {
        final long began = System.currentTimeMillis();
        final String url = args.optString("url", "").trim();

        // 只认 https：http 在移动网络里会被运营商插页，spider 拿到的 body 就不是站点的了。
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IOException("net 拒绝：只允许 https");
        }
        final String host = hostOf(url);
        if (!allowed(s, host)) {
            // 白名单是「spider 自己声明的 hosts」，不是全局表：一个源能碰哪些域名
            // 应该写在源文件里，用户看得见。
            throw new IOException("net 拒绝：主机不在白名单");
        }

        // 过盾外包的回填（方案 1）：:spider 撞盾后主进程用有窗口的 WebView 取回
        // HTML，call() 在入口把「url→html 表」挂到会话上（Session.cannedPages）。
        // 这里不发任何网络，直接当 200 响应交给 spider JS —— 解析照常，全程不触碰
        // 渲染线程。url 要能对上（见 cannedBodyFor），防同一会话里别的请求误吃。
        final String canned = cannedBodyFor(s, url);
        if (canned != null) {
            Log.i(TAG, "op=net 命中过盾回填（" + host + "，" + canned.length() + " 字节），跳过网络");
            return new JSONObject()
                    .put("ok", true)
                    .put("status", 200)
                    .put("body", canned);
        }

        // 打一行路由。spider 这条路最容易出的诊断困境是「App 能取、spider 取不到」，
        // 而两者差的就是路由决策 —— 不把结论打出来，只能靠猜。
        //
        // 2026-10-19 补：还要把「声明了代理但没有代理」这一种说出来。那天真机日志是
        // `net yhdm.one → 代理 profile=有 mode=direct proxy=空`，后半句写着「按直连发」，
        // 但用户看到的仍是 45 秒超时 —— 因为当时路由层没人裁决这个冲突。现在
        // NetRoute.proxyUnavailable 会把这种情形按直连放行，日志也要跟着改口，
        // 否则「→ 代理」那半句会让人以为代理生效了。
        boolean unavailable = NetRoute.proxyUnavailable(host);
        UserSource.log("net " + host + " → "
                + (unavailable ? "无代理可用（源声明要走代理，按直连发）"
                               : (NetRoute.mustProxy(host) ? "代理" : "直连"))
                + (NetRoute.directOnly(host) ? "（直连表命中）" : "")
                + " profile=" + (s.profile != null ? "有" : "空")
                + " mode=" + ProxyConsole.proxyMode(s.ctx)
                + " proxy=" + (AiiHttp.effectiveProxy().length() > 0 ? "有" : "空"));

        // 源说要走代理、可内置核心还没起来的时候，**等它**。
        //
        // 这一条和 AiiHttp 里那个 routeForProxyHost 是同一件事的两个层面：
        // 那边负责「建客户端时别把空窗当直连」，这边负责「等之前先别发请求」。
        // 实测（2026-10-19 00:31:14）spider 在启动 0.3 秒后触发，那一刻
        // effectiveProxy() 是空串 —— 直连去撞被 DNS 污染的 IPv6，一个请求烧 15 秒。
        // 等 3 秒的成本远小于一次 15 秒失败，而且用户看不出差别。
        if (NetRoute.mustProxy(host) && AiiHttp.effectiveProxy().length() == 0) {
            long waitBegan = System.currentTimeMillis();
            while (System.currentTimeMillis() - waitBegan < 3000 && AiiHttp.effectiveProxy().length() == 0) {
                try { Thread.sleep(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            UserSource.log("net 等核心就绪 " + (System.currentTimeMillis() - waitBegan) + "ms → "
                    + (AiiHttp.effectiveProxy().length() > 0 ? "已就绪" : "仍为空，按直连发"));
        }

        Map<String, String> headers = headersOf(args);
        String method = args.optString("method", "GET").trim().toUpperCase(Locale.ROOT);
        // method 白名单：认不出来的值当场抛（和下面 parseFormat「认不出就抛」同一个
        // 约定），不能静默落成 form POST —— 源写错 method 时那是对另一个接口发的
        // 请求，对面回「参数缺失」，从响应上根本查不到这里。
        final boolean headMethod = "HEAD".equals(method);   // HEAD 按 GET 路径发：拿头不要体
        if (!"GET".equals(method) && !"POST".equals(method) && !headMethod) {
            throw new IllegalArgumentException(
                    "net 拒绝：不认识的 method：" + method + "（只认 GET / POST / HEAD）");
        }
        String body = args.isNull("body") ? null : args.optString("body", "");
        // 老站里有一批 GBK/Big5 的（返回头还常常不带 charset，或者带成 ISO-8859-1），
        // 一律按 UTF-8 解就是整页乱码，而且 spider 侧没有补救手段。所以把解码字符集
        // 交给源自己声明，默认仍是 utf-8。

        // format 在这一行就解析，**在发请求之前**：认不出来的值必须当场报错，
        // 不能等请求发出去、拿回一句「path 不能为空」再去猜。GET 上它没有意义，
        // 传了也接受（源可以用一套参数拼两种枚举），但不影响走哪条分支。
        final boolean jsonFormat = "json".equals(parseFormat(args.optString("format", "")));
        // keep 同理，解析完是一张空表就是「不回传」，不区分「没传」和「传了空数组」。
        final String[] keep = parseKeep(args.opt("keep"));
        // cookie 的落点。keep 为空时**保持 null** —— 那样 AiiHttp 那条路一个
        // Set-Cookie 都不读，也不用为用不上的东西建一张表。
        final Map<String, String> cookieJar = keep.length == 0
                ? null : new LinkedHashMap<String, String>();

        try {
            java.nio.charset.Charset charset = charsetOf(args.optString("charset", ""));
            byte[] raw;
            if ("GET".equals(method) || headMethod) {
                SiteProfile prof = s.profile;
                // 只打一次。
                //
                // 这里原来套了个「失败再试一次」的循环，而 AiiHttp.getRaw 内部本来
                // 就有 h2 → h1.1 的降级 —— 两层叠起来，一个连不上的地址要打 4 次：
                // 实测 14:14:14 到 14:14:21，整整 7.2 秒才报失败。用户点一下首页，
                // 屏幕 7 秒不动，然后什么都没有 —— 那就是他说的「卡」。
                //
                // 而真正连不上的时候（api.agedm.io 在手机网络上 TLS 握手后被掐，
                // curl 直接 Empty reply），多打两次不会变通，只是多等 5 秒。
                // 要重试也是上层（界面 + 退避）该管的事，不是在这里原地重打。
                raw = null;   // 失败时保持 null，下面的兜底靠它判断
                Exception httpFailure = null;
                // 盾后站点不再先试 okhttp。
                //
                // 2026-10-19 真机：missav.live 的每个请求都要先撞一次 okhttp，
                // 而 okhttp 永远过不去（HTTP 103 / 403），要等满 NET_TIMEOUT_S
                // 的 15 秒才轮到 WebView —— 首页一次、详情一次、播放一次，
                // 光这一层就吃掉 45 秒，看门狗直接判超时。
                // 域名表是确定的（isShielded），没有任何理由先撞那 15 秒。
                final boolean shieldedEarly = SiteFetchWebView.isShielded(url);
                if (!shieldedEarly) try {
                    if (cookieJar != null && prof != null) {
                        raw = AiiHttp.getRaw(url, headers, NET_TIMEOUT_S, prof, cookieJar);
                    } else if (cookieJar != null) {
                        raw = AiiHttp.getRaw(url, headers, NET_TIMEOUT_S, cookieJar);
                    } else {
                        raw = prof == null
                                ? AiiHttp.getRaw(url, headers, NET_TIMEOUT_S)
                                : AiiHttp.getRaw(url, headers, NET_TIMEOUT_S, prof);
                    }
                } catch (Exception e) {
                    // 先记下来，交给下面的 WebView 兜底判断；兜底也不行时把它原样报出去。
                    httpFailure = e;
                    android.util.Log.w("HG.Spider", "op=net okhttp 失败：" + e.getMessage());
                }
                // ── 盾后站点的兜底：交给真 WebView ────────────────────────
                //
                // missav.live 整站（页面和 /api/* 一起）挂在 Cloudflare 的 JS 挑战
                // 后面。2026-10-19 又实测了一次：带完整安卓 Chrome 头 curl 两个地址
                // （/dm539/cn/new 和 /cn/fct-210）全是 **403 + 5.6KB 挑战页**。
                //
                // 这条不是"可能"，是必然 —— okhttp 永远过不去，而且**不是 cookie 的问题**：
                // cf_clearance 绑的是发出请求那台的 TLS 指纹（JA3/JA4）。所以
                // "先拿 cookie 再走 okhttp" 在协议上是死的，别再往那个方向试。
                //
                // 唯一能过盾的是真 WebView，而 App 里早就有这套东西
                // （SiteFetchWebView，播放层的 VlcRelay 一直在用）。spider 的 net
                // 走的是 okhttp，今天第一次需要它。
                //
                // 判据用域名表（isShielded）而不是"看响应像不像挑战页"：
                // net 对非 2xx 直接抛异常、拿不到正文（见契约 §三），所以这里
                // 根本看不见那个挑战页。域名是此刻唯一能拿到的信号。
                if (raw == null && shieldedEarly) {
                    android.util.Log.i("HG.Spider", "op=net 命中盾后站点，直接交主进程过盾：" + host);
                    // 2026-10-19 深夜第二轮：这里原来先自己 headless 试 15 秒再交出去。
                    // 实测那一轮（22:48:31 → 22:49:05）每次调用都准点烧掉 15 秒：
                    // :spider 的 WebView 没有窗口，挑战从来过不去，那 15 秒是纯等。
                    // 主进程过盾现在是 0.5~3 秒（22:48:44 一次 523ms、22:48:55 一次
                    // 2514ms），所以 headless 先试这件事本身就该删掉 —— 用户看到的
                    // 「点一下要十几秒才出图」有一大半是这段空等。
                    //
                    // 保留一个开关而不是写死：万一哪天 WebView 的 headless 能过挑战
                    // （或者换了能用的内核），把它翻回 true 就能回到「先自己试」的路径。
                    if (SELF_SHIELD_FIRST) {
                        try {
                            String html = fetchPageWithBudget(url, SHIELD_HEADLESS_MS);
                            if (html != null && html.length() > 0) {
                                raw = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            }
                        } catch (Exception e) {
                            s.shieldUrl = url;
                            throw new ShieldSignal(url, shieldMessage(host, httpFailure, e.getMessage()));
                        }
                    } else {
                        // 结构化信号交给主进程：类型信息会被 SpiderBridge 压平，
                        // url 挂在 Session.shieldUrl 上随会话走，call() 收尾时重建。
                        s.shieldUrl = url;
                        throw new ShieldSignal(url, shieldMessage(host, httpFailure, null));
                    }
                }
                if (raw == null) {
                    throw httpFailure != null ? new IOException("net 失败：" + httpFailure.getMessage(), httpFailure)
                            : new IOException("net 失败：没有任何响应");
                }
            } else if (jsonFormat) {
                // JSON 体。OpenList/AList 的 /api/fs/list、/api/fs/get、/api/fs/search
                // 只收 application/json —— 表单体过去它当空对象，回「path 不能为空」，
                // 而那句话看起来像源拼错了参数，实际是编码形式不对。
                //
                // 不做 encodeFormBody 那套 %XX 转换：JSON 的 body 是字符串，字符集
                // 已经在 postJsonRaw 里由 MediaType 钉成 UTF-8，转成 %XX 反而会把
                // 中文变成字面上的 "%E4%B8%AD" 三个字符塞进 JSON 值里。
                //
                // 六参版本把 profile 传下去：POST 原来只能走全局路由建 client，
                // 「源声明这个域要走代理」时兜底 client 拿不到 SmartDns，
                // 源配置里钉死的地址就不生效 —— OpenList 三个接口全在这条路上。
                raw = AiiHttp.postJsonRaw(url, body == null ? "" : body, headers, NET_TIMEOUT_S,
                        cookieJar, s.profile);
            } else {
                // 表单 POST（缺省行为，和 stage 0 一字不差）。
                //
                // 非 UTF-8 的表单体没法靠 Content-Type 说清楚：AiiHttp.postFormRaw 里
                // MediaType 是写死的 `charset=UTF-8`，而 OkHttp 的 BridgeInterceptor
                // 会用 body 的 MediaType 覆盖同名头，外面塞 Content-Type 是无效的
                // （改它就得动 AiiHttp 的公开面，合同不允许）。所以这里退一步：
                // 把 body 里的非 ASCII 字符按目标字符集转成 %XX，实际发出的字节就对了 ——
                // 老服务器大多本来就按自己的默认编码解表单，不看头。
                String form = encodeFormBody(body == null ? "" : body, charset);
                // 六参版本，理由同上面那条 JSON 分支：profile 决定兜底 client 用哪个 DNS。
                raw = AiiHttp.postFormRaw(url, form, headers, NET_TIMEOUT_S, cookieJar, s.profile);
            }
            if (raw.length > MAX_NET_BODY) {
                throw new IOException("net 失败：响应体 " + (raw.length / 1024)
                        + "KB 超过 4MB 上限（不截断，直接报错）");
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            // AiiHttp.getRaw 族对非 2xx 直接抛 IOException（rawWith 里那句
            // `if (!response.isSuccessful()) throw`），所以走到这里的都是 2xx，
            // 而它不把 Response 交出来，真实状态码拿不到。契约 §三 已按这个现状改：
            // 成功记 200，非 2xx 走 ok:false 且没有 status 字段。
            out.put("status", 200);
            out.put("body", new String(raw, charset));
            // cookies 只在源要了、而且真的收到了的时候才出现。**没有就不给这个字段**，
            // 不给一个空对象：源那边 `if (r.cookies && r.cookies.ga)` 两种写法都能过，
            // 而空对象会诱使别人写 `r.cookies.ga !== undefined` 这种更脆的判断。
            JSONObject cookies = pickCookies(cookieJar, keep);
            if (cookies != null) out.put("cookies", cookies);
            Log.i(TAG, "op=net host=" + host + " method=" + method
                    + (jsonFormat ? " json" : "") + (cookies != null ? " cookies=" + cookies.length() : "")
                    + " ms=" + (System.currentTimeMillis() - began));
            return out;
        } catch (IOException e) {
            Log.w(TAG, "op=net host=" + host + " ms=" + (System.currentTimeMillis() - began)
                    + " fail=" + e.getMessage());
            throw e;
        }
    }

    /**
     * {@code done} 的落点：把结果写进会话并放闩锁。<b>一次调用只认第一个 done。</b>
     *
     * <p>参数在 bridge 里已经解成 Java 值（那边负责 JSON 编解码），这里只做状态迁移。
     */
    // ── 批量 net ─────────────────────────────────────────────────────

    /**
     * 一次 netAll 最多带多少个子请求。一页 24 条，30 留余量。
     * 上限存在的理由和白名单一样：源能要多少资源该有个明确的数。
     */
    private static final int NETALL_MAX_REQUESTS = 30;

    /** 默认并发。8 是实测出来的：24 条详情 3 轮走完，站点没有触发限流。 */
    private static final int NETALL_MAX_CONCURRENCY = 8;

    /** 复用线程池。只在 :spider 进程里，最多 8 条线程，不值得每次调用重建。 */
    private static final ExecutorService NETALL_POOL =
            Executors.newFixedThreadPool(NETALL_MAX_CONCURRENCY);

    /**
     * 批量 {@code net}：<b>在 Java 侧真并发</b>，一次 bridge 往返带 N 个请求。
     *
     * <p>══ 为什么必须有这个 op ══
     *
     * <p>spider 里的 {@code ctx.call('net')} 是同步桥：JS 在渲染线程上阻塞，等 Java
     * 回包。所以源里写 {@code Promise.all(ids.map(fetch))} 看着像并发，实际一条一条
     * 排队 —— 2026-10-19 在 91porn 上实测，8 个 worker 拉 24 条详情跑到 45 秒被看门狗
     * 杀掉，零结果。当时只能把校正搬到服务器上做，等于给源加了一个外部依赖。
     *
     * <p>现在这条路通了：源把 N 个请求一次交过来，Java 用线程池并发，结果按原顺序
     * 返回。源不再需要「一次一个」，也不再需要外部服务。
     *
     * <p>══ 形状 ══
     *
     * <pre>
     *   入参 {"requests":[{"url":…, "headers":…}, …], "concurrency":8}
     *   出参 {"results":[{"ok":true,"status":200,"body":…} | {"ok":false,"error":…}, …]}
     * </pre>
     *
     * <p><b>单项失败不抛异常</b>：批量请求里坏一条是常态（片子下架、id 打错），
     * 整批失败会让调用方一条都拿不到。失败的那一项带 {@code ok:false} 占位，
     * 顺序和入参一一对应 —— 源按索引映射回自己的 id，不需要再猜。
     */
    static JSONObject netAll(Session s, JSONObject args) throws Exception {
        final JSONArray requests = args.optJSONArray("requests");
        if (requests == null || requests.length() == 0) {
            throw new IOException("netAll 缺少 requests 数组");
        }
        final int count = Math.min(requests.length(), NETALL_MAX_REQUESTS);
        int concurrency = args.optInt("concurrency", NETALL_MAX_CONCURRENCY);
        if (concurrency <= 0) concurrency = NETALL_MAX_CONCURRENCY;
        final Semaphore gate = new Semaphore(Math.min(concurrency, NETALL_MAX_CONCURRENCY));

        final List<Future<JSONObject>> futures = new ArrayList<Future<JSONObject>>(count);
        for (int i = 0; i < count; i++) {
            final JSONObject one = requests.optJSONObject(i);
            futures.add(NETALL_POOL.submit(new java.util.concurrent.Callable<JSONObject>() {
                @Override
                public JSONObject call() {
                    try {
                        gate.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        return net(s, one == null ? new JSONObject() : one);
                    } catch (Throwable e) {
                        final String why = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                        try {
                            return new JSONObject().put("ok", false).put("error", why);
                        } catch (Throwable ignored) {
                            return new JSONObject();
                        }
                    } finally {
                        gate.release();
                    }
                }
            }));
        }

        final JSONArray results = new JSONArray();
        for (int i = 0; i < futures.size(); i++) {
            try {
                // 单项超时给得比 net 自己宽 5 秒：先让 net 自己报超时，它那句更具体。
                results.put(futures.get(i).get(NET_TIMEOUT_S + 5L, TimeUnit.SECONDS));
            } catch (Throwable e) {
                // 等不到（或等待被打断）就把任务取消掉：池子一共 8 条线程，
                // 只放弃等待不 cancel 的话，僵尸任务会把池占满，拖死后续整批请求。
                // 已完成的 future cancel 是无害空操作。
                futures.get(i).cancel(true);
                try {
                    results.put(new JSONObject().put("ok", false).put("error", "netAll 单项超时"));
                } catch (Throwable ignored) {
                    results.put(JSONObject.NULL);
                }
            }
        }
        Log.i(TAG, "cap=" + s.cap + " netAll n=" + count + " conc=" + concurrency);
        return new JSONObject().put("results", results);
    }

    static void finish(Session s, boolean ok, Object payload, String error) {
        if (s == null) return;
        synchronized (s) {
            if (s.finished) return;
            s.finished = true;
            if (!ok) {
                s.error = (error == null || error.length() == 0)
                        ? "spider 调用失败（done 没带 error）" : error;
            } else if (payload == null || payload == JSONObject.NULL) {
                // 一个不返回东西的能力是合法的，别把它当错误。
                s.result = new JSONObject();
            } else if (payload instanceof JSONObject) {
                s.result = (JSONObject) payload;
            } else {
                // 契约把返回值定成 JSONObject（§三「成功：spider 返回的 payload 原样
                // （JSONObject）」），数组/字符串在这里说不出口 —— 不硬塞成 {"items":…}，
                // 那种静默转型以后一定会有人踩。
                s.error = "spider 返回的 payload 不是 JSON 对象（阶段 0 只收对象）";
            }
        }
        if (s.error == null) {
            Log.i(TAG, "cap=" + s.cap + " ok ms=" + (System.currentTimeMillis() - s.beganAt));
        } else {
            Log.w(TAG, "cap=" + s.cap + " fail: " + s.error);
        }
        s.done.countDown();
    }

    // ── 建页 / 注入 / 销毁 ────────────────────────────────────────────

    /** 主线程：建页、挂 bridge、loadUrl、上看门狗。 */
    @SuppressLint("SetJavaScriptEnabled")
    private static void startPage(final Context ctx, final Session s) throws Exception {
        final CountDownLatch created = new CountDownLatch(1);
        final IOException[] failed = new IOException[1];

        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // 复用判定。见 reuseCount 的注释：正常收尾的页面留着，下一轮接着用。
                    final boolean reuse = webView != null && reuseCount < MAX_REUSE;
                    if (reuse) {
                        reuseCount++;
                        // 重载宿主页 = 换一个干净的文档作用域。__spiderSite、
                        // spider 自己挂的定时器、上一轮留下的 Promise 全在这一下里没了，
                        // 但 WebView 实例和它的渲染线程留着 —— 省掉的正是 new WebView
                        // 那一两百毫秒。
                        webView.stopLoading();
                        webView.loadUrl(HOST_PAGE);
                        MAIN.postDelayed(s.watchdog, s.timeoutMs);
                        return;
                    }
                    // 上一页不管是超时留下来的还是 shutdown 没清干净的，这里先兜一次。
                    // 必须执行在主线程上，且和下面的新建在同一个 runnable 里，
                    // 这样「销毁旧的 → 建新的」不会被另一次调用插队。
                    destroyOnMain();
                    reuseCount = 0;

                    // 免窗口 headless（2026-10-19）：宿主在 :spider 进程的 Service 里，
                    // 没有 decorView 可挂。不 attach 的 WebView 照常 loadUrl /
                    // evaluateJavascript / 触发生命周期回调（页面本来就该不可见），
                    // 唯一放弃的是「像素级渲染」，而 spider 只吃 DOM 和网络，用不到。
                    final WebView wv = new WebView(ctx.getApplicationContext());
                    WebSettings ws = wv.getSettings();
                    ws.setJavaScriptEnabled(true);
                    // 宿主页由 WebViewAssetLoader 从 assets 里供给，不需要 file:// 访问；
                    // spider 也不该有 localStorage（设计 §五：能力集只有 ctx.net / ctx.log）。
                    // 关掉这两样，等于把「绕过 bridge 自己想办法拿数据」的边角再削一层。
                    ws.setAllowFileAccess(false);
                    ws.setAllowContentAccess(false);
                    ws.setDomStorageEnabled(false);

                    wv.setBackgroundColor(0);
                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                         WebResourceRequest req) {
                            // 和 MainActivity 那个 loader 是同一套：/assets/ 走 APK 里的
                            // 静态文件。这里单独建一份而不是复用 MainActivity 的 ——
                            // 那个是匿名 WebViewClient 里的局部变量，拿不到。loader 本身
                            // 无状态，domain 默认就是 appassets.androidplatform.net，
                            // 所以 origin 和 app.html 完全一致。
                            return loaderOf(view.getContext())
                                    .shouldInterceptRequest(req.getUrl());
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                            // 宿主页不需要导航到任何地方。设计 §三 明确要求这里一律拦掉：
                            // CSP 和老内核之间有能力覆盖的缺口，导航再上一把锁。
                            return true;
                        }

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            // 收尾时那一枪 about:blank 也会走到这里，它不该触发注入。
                            if (url != null && url.startsWith("about:")) return;
                            // 读静态 session 而不是闭包里的 s：页面会被复用，
                            // 同一个 WebViewClient 会为下一次调用再触发一次这里。
                            // 闭包里那份是建页时的旧会话，拿它注入等于把这次调用的
                            // spider 源码执行在上一轮的上下文里。
                            inject(view, session);
                        }
                    });
                    wv.setWebChromeClient(new WebChromeClient() {
                        @Override
                        public boolean onCreateWindow(WebView view, boolean isDialog,
                                                      boolean isUserGesture, android.os.Message resultMsg) {
                            // window.open 一律拒绝：新窗口意味着一个我们没配过 CSP 的
                            // WebView，等于把暴露面翻倍。
                            return false;
                        }
                    });
                    // 桥的全局名写死成 __hgSpider：契约 §二 的宿主页代码里就是它。
                    wv.addJavascriptInterface(new SpiderBridge(), "__hgSpider");

                    webView = wv;

                    // 看门狗在建页这一步就上，不能等 onPageFinished —— 卡在加载上的
                    // spider 一样是卡住，一样得杀。
                    MAIN.postDelayed(s.watchdog, s.timeoutMs);
                    wv.loadUrl(HOST_PAGE);
                } catch (Throwable e) {
                    failed[0] = new IOException("spider 宿主页建不起来：" + e);
                } finally {
                    created.countDown();
                }
            }
        });

        if (!created.await(s.timeoutMs + AWAIT_GRACE_MS, TimeUnit.MILLISECONDS)) {
            throw new IOException("spider 宿主页建不起来（等主线程超时）。");
        }
        if (failed[0] != null) throw failed[0];
    }

    /**
     * 主线程：注入 spider 源码 → 读一次 hosts → 注入驱动。
     *
     * <p>顺序是硬要求（契约 §三）：源码必须在驱动之前执行完，hosts 必须在驱动之前
     * 读到手 —— 否则第一个 {@code net} 打进来时白名单还是空的，会把合法请求拒掉。
     * JS 在渲染线程上是顺序执行的，所以「源码 → 读 hosts → 驱动」这样连着注入就够了。
     */
    private static void inject(final WebView wv, final Session s) {
        // s 可能是 null：上一轮收尾时会 loadUrl("about:blank")，那也会触发一次
        // onPageFinished，而那时 call() 已经把静态 session 清掉了。
        if (s == null || session != s || s.finished || s.injected) return;
        s.injected = true;

        // 直接执行，不包函数：spider 文件是 `__spiderSite = {...}` 这种顶层赋值，
        // 包进函数体虽然非严格模式下也能落到全局，但多一层作用域就多一种出错姿势。
        wv.evaluateJavascript(s.spiderSource, null);

        // hosts 取一次快照。让 JS 直接求值成数组，evaluateJavascript 会把它
        // JSON 序列化后交回来 —— 比 JSON.stringify 再解一层字符串干净。
        wv.evaluateJavascript(HOSTS_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String raw) {
                s.hosts = parseHosts(raw);
                if (session != s || s.finished) return;
                if (s.metaOnly) {
                    // 探测：只读一遍对象，不驱动任何能力。
                    wv.evaluateJavascript(META_JS, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String metaRaw) {
                            finishMeta(s, metaRaw);
                        }
                    });
                    return;
                }
                wv.evaluateJavascript(driverJs(s), null);
            }
        });
    }

    /**
     * {@code __spiderSite.hosts} 的读取表达式。spider 没导出对象时给空数组，
     * 后面由 {@code __spiderRun} 报「spider 没有导出 __spiderSite」。
     */
    private static final String HOSTS_JS =
            "(function(){try{var e=window.__spiderSite;var h=e&&e.hosts;"
                    + "return Array.isArray(h)?h:[];}catch(x){return [];}})()";

    /**
     * 读 {@code __spiderSite} 的元信息：能力集、meta、id、version。
     *
     * <p><b>能力集按函数存在性枚举</b>，不读源里写的字面量声明 —— 源只要加一个函数，
     * 壳立刻知道；忘了同步声明也不会出现「实现了但前端说没有」。
     *
     * <p>求值结果由 evaluateJavascript 自动 JSON 序列化，对象/数组都直接过来，
     * 不用再 stringify 一层。没导出对象时返回 null，由 finishMeta 落成空对象。
     */
    private static final String META_JS =
            "(function(){try{var e=window.__spiderSite;if(!e)return null;"
                    + "var caps=[];for(var k in e){if(typeof e[k]==='function')caps.push(k);}"
                    + "return {id:String(e.id||''),version:Number(e.version)||0,"
                    + "caps:caps,meta:e.meta||null,hosts:Array.isArray(e.hosts)?e.hosts:[]};"
                    + "}catch(x){return null;}})()";

    /**
     * 收下探测结果。和 {@code finish} 的区别：这里没有 done 协议，也不认 error ——
     * 探测失败（没导出对象）就是一份空的能力集，前端据此回落到「让 spider 自己报错」。
     */
    private static void finishMeta(Session s, String raw) {
        if (session != s || s.finished) return;
        JSONObject out = null;
        try {
            // raw 可能是 JSON 文本、也可能是字面量 null。包一层再取，两种都能 parse。
            Object parsed = new JSONObject("{\"v\":" + (raw == null ? "null" : raw) + "}").opt("v");
            if (parsed instanceof JSONObject) out = (JSONObject) parsed;
        } catch (Throwable ignored) {
            // 解析不出来就当没探到。
        }
        s.result = out == null ? new JSONObject() : out;
        Log.i(TAG, "cap=__meta ok ms=" + (System.currentTimeMillis() - s.beganAt)
                + " caps=" + s.result.optJSONArray("caps"));
        s.done.countDown();
    }

    /**
     * 驱动脚本：跑一次能力，把结果送回 bridge 的 {@code done}。
     *
     * <p><b>编码只走一层</b>（契约 §二）：{@code done} 传<b>对象</b> {@code r}，
     * 由宿主页的 {@code __spider.call} 负责唯一的 {@code JSON.stringify}。
     * 这里再 stringify 一次的话，Java 收到的是「字符串字面量」，得 parse 两遍才解得开 ——
     * 那正是契约早期版本里的 bug。
     *
     * <p><b>能力名必须拼进来</b>，不能写死 'home'：阶段 0 只调 home，但
     * category/detail/search/play 接进来时不该回头改 Java。
     *
     * <p>为什么整段还包一层 try/catch：{@code __spiderRun} 是宿主页定义的，万一宿主页
     * 没加载成功（或版本不对没有这个函数），抛的是 ReferenceError —— 同步异常 Promise
     * 的 rejection 接不住，不接就成了「没人调 done」，用户看到 15 秒超时而不是
     * 「宿主页没起来」。多包一层，错误立刻变成一句可读的话。
     *
     * <p><b>then 链尾必须跟 .catch</b>（和上面同一个理由的另一半）：cap 函数异步
     * 抛出的异常同步 try 接不到，不接就没人调 done —— 错误被掩盖成 15 秒超时，
     * 看门狗还把页面销毁了。
     */
    private static String driverJs(Session s) {
        return "(function(){try{"
                + "__spiderRun(" + JSONObject.quote(s.cap) + "," + JSONObject.quote(s.argsJson) + ")"
                + ".then(function(r){__spider.call('done', r)})"
                + ".catch(function(e){__spider.call('done',{ok:false,error:String((e&&e.message)||e)})});"
                + "}catch(e){__spider.call('done',{ok:false,error:String((e&&e.message)||e)});}})()";
    }

    /** 看门狗。跑在主线程上。 */
    private static Runnable watchdog(final Session s) {
        return new Runnable() {
            @Override
            public void run() {
                if (s.finished) return;
                s.timedOut = true;
                // 先杀页再报错：卡住的 spider 在渲染线程里，Java 侧没有任何办法中断它
                // （设计 §七 第 2 条）。所谓「超时处置」就是整页换掉。
                destroyOnMain();
                fail(s, timeoutMessage(s));
            }
        };
    }

    /**
     * 释放这一次调用的页面。
     *
     * <p><b>2026-10-19 改：正常收尾的页面不再销毁，改成清文档复用。</b>
     *
     * <p>原来每次调用都 {@code new WebView}，理由是「页面里留着 {@code __spiderSite}
     * 和 spider 自己挂的定时器，复用会让第二个源继承第一个源的全局对象」。那个担心
     * 是对的，但解法可以更便宜：{@code loadUrl(HOST_PAGE)} 重载一次宿主页，整个
     * 文档作用域就换掉了 —— {@code __spiderSite = null} 会重新执行，定时器随旧文档
     * 一起没了。留着的是 WebView 对象和它的渲染线程，而省掉的正是那一两百毫秒的
     * 初始化。
     *
     * <p>超时和出错的页面仍然销毁：那种页面的状态本来就不该信（可能停在一半的
     * spider 里、可能卡在渲染线程上），重建是唯一确定的做法。
     *
     * <p>连续复用满 {@link #MAX_REUSE} 次也重建一次，防止渲染线程的堆和进程内的
     * 缓存一路涨上去。
     */
    private static void releasePage(final Session s) {
        // 任何线程都能 removeCallbacks；闩锁和会话状态先落定，再让主线程收拾页面。
        MAIN.removeCallbacks(s.watchdog);
        synchronized (s) {
            s.finished = true;
        }
        // 正常收尾的页面留着给下一次用；超时/出错的页面状态不可信，必须扔。
        final boolean reusable = !s.timedOut && s.error == null;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                // 只收拾「这一次」的页面：如果更新的一次调用已经建好了新页，
                // 那是它的东西，不能动。
                if (session != null && session != s) return;
                if (!reusable) {
                    destroyOnMain();
                    reuseCount = 0;
                    return;
                }
                final WebView wv = webView;
                if (wv == null) return;
                try {
                    // 清文档但不销毁。下一次 startPage 复用它时 loadUrl 会重新
                    // 走一遍 onPageFinished → inject，等于换了个干净的全局作用域。
                    wv.stopLoading();
                    wv.loadUrl("about:blank");
                } catch (Throwable ignored) {
                    // 清不干净就下次重建，不在收尾路径上抛。
                    destroyOnMain();
                    reuseCount = 0;
                }
            }
        });
    }

    /** 真正销毁。**必须在主线程上**，且可重复调用。 */
    private static void destroyOnMain() {
        final WebView wv = webView;
        webView = null;
        reuseCount = 0;
        try {
            if (wv != null) {
                // stopLoading + about:blank 是 SiteFetchWebView 验过的收尾顺序：
                // 先让渲染线程停下，再切走文档，最后 destroy，不然某些 ROM 上
                // destroy 会撞上还在跑的定时器。
                wv.stopLoading();
                wv.loadUrl("about:blank");
                wv.removeJavascriptInterface("__hgSpider");
                // headless：从未 attach，无需 removeView。
                wv.destroy();
            }
        } catch (Throwable ignored) {
            // 销毁路径上什么都别抛：它要么在 Activity 退出流程里，要么在超时处置里，
            // 抛出去只会把这两条路一起打断。
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    /** 会话落定为失败。第一个原因生效，后面的（比如释放页面）不再改它。 */
    private static void fail(Session s, String message) {
        boolean first;
        synchronized (s) {
            first = !s.finished;
            if (first) {
                s.finished = true;
                s.error = message;
            }
        }
        if (!first) return;
        if (s.timedOut) {
            Log.w(TAG, "cap=" + s.cap + " timeout " + s.timeoutMs + "ms");
        } else {
            Log.w(TAG, "cap=" + s.cap + " fail: " + message);
        }
        s.done.countDown();
    }

    /** {@code spider 超时（15s）}。契约 §三 逐字要求的一句话。 */
    private static String timeoutMessage(Session s) {
        long seconds = Math.max(1L, Math.round(s.timeoutMs / 1000.0));
        return "spider 超时（" + seconds + "s）";
    }

    /**
     * assets 供给器。惰性建一份就够 —— 它无状态（只持有 Handler 和路径表），
     * 每次请求都 new 一遍纯属浪费。
     *
     * <p>加锁是因为 {@code shouldInterceptRequest} 在部分 WebView 版本上跑在
     * 非主线程，而宿主页加载时可能有并发请求。
     */
    private static WebViewAssetLoader loaderOf(Context ctx) {
        synchronized (SpiderRuntime.class) {
            if (loader == null) {
                loader = new WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/",
                                new WebViewAssetLoader.AssetsPathHandler(ctx.getApplicationContext()))
                        .build();
            }
            return loader;
        }
    }

    private static boolean allowed(Session s, String host) {
        if (s == null || host == null || host.length() == 0) return false;
        String[] hosts = s.hosts;
        if (hosts == null) return false;
        for (int i = 0; i < hosts.length; i++) {
            if (host.equals(hosts[i])) return true;
        }
        return false;
    }

    /**
     * evaluateJavascript 回包是 JSON：数组原样是 {@code ["a","b"]}。
     *
     * <p>包内可见是为了单测（{@code SpiderRuntimeTest}）—— 这是整个白名单链路里
     * 唯一不依赖 Android 的一段，而它错了的表现是「net 全被拒」或「什么都放行」，
     * 两种都不会崩，只会让人以为站点坏了。
     */
    static String[] parseHosts(String raw) {
        if (raw == null || raw.length() == 0 || "null".equals(raw)) return EMPTY_HOSTS;
        try {
            JSONArray arr = new JSONArray(raw);
            String[] out = new String[arr.length()];
            int n = 0;
            for (int i = 0; i < arr.length(); i++) {
                String h = arr.optString(i, "").trim().toLowerCase(Locale.ROOT);
                if (h.length() > 0) out[n++] = h;
            }
            if (n == out.length) return out;
            String[] trimmed = new String[n];
            System.arraycopy(out, 0, trimmed, 0, n);
            return trimmed;
        } catch (Throwable e) {
            // 读不出来就按空白名单算：net 全拒。宁可拒绝，不能放行。
            return EMPTY_HOSTS;
        }
    }

    /**
     * 解析 {@code net} 的 {@code format} 参数：空 = {@code form}（历史行为）。
     *
     * <p>返回值是<b>归一化后的小写串</b>，调用方拿它跟字面量比。认不出来的值
     * <b>抛</b>而不是回退到 form：回退的表现是「请求发出去了、对方说参数缺失」，
     * 而真正的原因是源写错了 {@code format}，这两件事从响应上分不出来。
     * 报错文案把原值带回去，一眼能看出是 {@code "form"} 少写了一个引号还是拼成了
     * {@code "jsonp"}。
     *
     * <p>只认这两个值：多一个就别扭 —— 表单和 JSON 是 POST 体仅有的两种现实形态，
     * 而 {@code multipart} 在 spider 侧没有使用场景（文件上传不在能力集里）。
     *
     * <p>包级可见是为了单测：这段不碰 Android，而它错了的表现是「静默走错编码」。
     *
     * @throws IOException 值不是 {@code form} / {@code json}
     */
    static String parseFormat(String raw) throws IOException {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        // 空就是 form：这是加 format 之前所有源的行为，一个字节都不能变。
        if (v.length() == 0 || "form".equals(v)) return "form";
        if ("json".equals(v)) return "json";
        // 文案里的原值用引号包起来：源传了 null / 数字 / 空格 时，日志上能看出边界
        // （"不认识的 format：" 后面直接跟一个空串，那种打印什么也看不出来）。
        throw new IOException("net 失败：不认识的 format：" + (raw == null ? "" : raw.trim())
                + "（只认 form / json，缺省是 form）");
    }

    /**
     * 解析 {@code net} 的 {@code keep} 参数：要回传哪些 cookie 名字。
     *
     * <p>形式很窄：<b>字符串数组</b>。字符串（{@code keep:"ga"}）也接受，当成只含一项的
     * 数组 —— 这种写法太自然了，拒绝它只会换来「源作者以为是别的错」。
     *
     * <p>空串、非字符串条目一律丢掉：留着空名字等于让 {@code cookieJar.get("")} 命中
     * 一个不存在的键，而报错时机在很远的地方（源那边拿到 {@code undefined}）。
     *
     * <p>返回 {@code String[0]} 表示「不要 cookie」，不返回 null：调用方只需要判长度，
     * 少一个 null 判断就少一个空指针的机会。
     *
     * <p>包级可见是为了单测。参数类型故意收窄成 {@link Object} 而不是 JSONObject：
     * 这样测试不用为了让 {@code opt} 生效去构造 Android 的桩，直接传
     * {@code JSONArray} / {@code String} / null 就行。
     */
    static String[] parseKeep(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return EMPTY_HOSTS;
        if (raw instanceof String) {
            String one = ((String) raw).trim();
            // 空串按「没要」处理，而不是按「要一个叫空串的 cookie」处理。
            return one.length() == 0 ? EMPTY_HOSTS : new String[]{one};
        }
        if (!(raw instanceof JSONArray)) return EMPTY_HOSTS;
        JSONArray arr = (JSONArray) raw;
        String[] out = new String[arr.length()];
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.opt(i);
            // 只认字符串。数字/对象/null 全丢 —— cookie 名字本来就是字符串，
            // 收别的只会把错误推迟到拼请求头的时候。
            if (!(item instanceof String)) continue;
            String name = ((String) item).trim();
            if (name.length() == 0) continue;
            // 去重：同一个名字要两遍没有任何额外含义，而 cookieOut 那张表只有一个位置。
            boolean dup = false;
            for (int k = 0; k < n; k++) {
                if (out[k].equals(name)) { dup = true; break; }
            }
            if (!dup) out[n++] = name;
        }
        if (n == out.length) return out;
        String[] trimmed = new String[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /**
     * 从收来的 cookie 表里挑出 {@code keep} 要的那几个。**一个都没有就返回 null。**
     *
     * <p>返回 null 而不是空 {@link JSONObject}：调用方据此决定要不要往返回值里放
     * {@code cookies} 字段。契约里「keep 为空就不带 cookies 字段」是给源看的，
     * 而「站点没下发这个 cookie」和「源没要」在源那一侧应该长得一样 ——
     * 都是 {@code undefined}，都让它走兜底值（91porn 的兜底就是 {@code ga=1}）。
     *
     * <p>值原样保留，不做任何解码或裁剪：`Set-Cookie` 的值就是站点给的那串字节，
     * 引号、base64 里的 {@code =} 都得原封不动还回去，否则带回去的标记不认。
     *
     * <p>包级可见是为了单测。
     */
    static JSONObject pickCookies(Map<String, String> jar, String[] keep) {
        if (jar == null || jar.isEmpty() || keep == null || keep.length == 0) return null;
        JSONObject out = null;
        for (int i = 0; i < keep.length; i++) {
            String name = keep[i];
            if (name == null || name.length() == 0) continue;
            String value = jar.get(name);
            // 空值也算「没收到」：站点清标记时会下发 `ga=;Max-Age=0`，
            // 把那个空串当成有效标记带回去，请求头里就是 `Cookie: ga=`，
            // 对面会当成「有标记但无效」，比不带更糟。
            if (value == null || value.length() == 0) continue;
            if (out == null) out = new JSONObject();
            try {
                out.put(name, value);
            } catch (Exception e) {
                // JSONObject.put 只在不合法的 key（null）上抛，上面已经挡过。
                // 真抛了也不能让一次 net 因为「cookie 名字怪」而失败 —— 少一个标记而已。
                Log.w(TAG, "op=net cookie 名放不进 JSON：" + e);
            }
        }
        return out;
    }

    private static Map<String, String> headersOf(JSONObject args) {
        JSONObject h = args.optJSONObject("headers");
        if (h == null || h.length() == 0) return null;
        Map<String, String> map = new LinkedHashMap<String, String>();
        Iterator<String> it = h.keys();
        while (it.hasNext()) {
            String k = it.next();
            map.put(k, h.optString(k, ""));
        }
        return map;
    }

    /**
     * 解析 {@code net} 的 {@code charset} 参数。空 = utf-8。
     *
     * <p>认不出来的名字直接报错，不做静默回退：静默退回 UTF-8 出来的是一屏乱码，
     * 而这种乱码从日志上完全看不出「是字符集错了还是站点改版了」。
     */
    private static java.nio.charset.Charset charsetOf(String name) throws IOException {
        String n = name == null ? "" : name.trim();
        if (n.length() == 0) return java.nio.charset.Charset.forName("UTF-8");
        try {
            return java.nio.charset.Charset.forName(n);
        } catch (IllegalArgumentException e) {
            throw new IOException("net 失败：不认识的 charset " + n);
        }
    }

    /**
     * 把表单体里的非 ASCII 字符按目标字符集转成 {@code %XX}。
     *
     * <p>为什么不是整串 {@code URLEncoder.encode}：那会把 {@code =} 和 {@code &} 也编码掉，
     * 表单结构直接没了。这里只动非 ASCII —— 它们才是编码会出错的部分，ASCII 原样放行
     * （源自己拼 body 时该转义的部分应该已经转义过了）。
     *
     * <p>UTF-8 不用处理：{@code AiiHttp.postFormRaw} 本来就用 UTF-8 把 String 转字节。
     */
    private static String encodeFormBody(String body, java.nio.charset.Charset cs) {
        if (body == null || body.length() == 0) return "";
        if ("UTF-8".equalsIgnoreCase(cs.name())) return body;
        StringBuilder sb = new StringBuilder(body.length() + 32);
        int i = 0;
        while (i < body.length()) {
            int cp = body.codePointAt(i);
            if (cp < 0x80) {
                sb.append((char) cp);
            } else {
                byte[] bytes = new String(Character.toChars(cp)).getBytes(cs);
                for (int k = 0; k < bytes.length; k++) {
                    sb.append('%');
                    int b = bytes[k] & 0xFF;
                    sb.append(HEX[b >>> 4]).append(HEX[b & 0x0F]);
                }
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** 大写十六进制表，避开 {@code String.format} 在每个字节上开一次 Formatter 的开销。 */
    /**
     * 带预算的 fetchPage：超时就中断等待（线程自己会跑完，不影响进程）。
     *
     * <p>为什么用独立线程而不是改 {@code SiteFetchWebView} 的超时常量：那个常量
     * 同时管主进程那条路，改小会把主进程也拖下水（主进程过盾 1~2 秒，但冷启动
     * 那次挑战要 30 秒以上）。
     */
    private static String fetchPageWithBudget(final String url, long budgetMs) throws Exception {
        java.util.concurrent.FutureTask<String> task =
                new java.util.concurrent.FutureTask<String>(new java.util.concurrent.Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        return SiteFetchWebView.fetchPage(url);
                    }
                });
        Thread worker = new Thread(task, "shield-headless");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(budgetMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            // 掐断等待还不够——必须**取消任务本身**（2026-10-19 真机第二轮）。
            // FutureTask.cancel(true) 会中断 worker 线程，让它从 SiteFetchWebView
            // 的 passLock 排队里退出；否则它继续占着队列跑完 2×45 秒（日志里
            // 「信号已发出」之后还冒出「过盾第 2 次超时」就是它），下一个请求
            // 又要跟着排队，主进程的外包窗口被白白拖后。
            task.cancel(true);
            throw new IOException("headless WebView 过盾超时（" + (budgetMs / 1000) + " 秒），交给主进程");
        }
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static String hostOf(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Throwable e) {
            return "";
        }
    }

    /** 只取 path，取不到给 "/"（回填比对用，见 {@link #cannedUrl}）。 */
    private static String pathOf(String url) {
        try {
            String p = new java.net.URI(url).getRawPath();
            return p == null || p.length() == 0 ? "/" : p;
        } catch (Throwable e) {
            return "/";
        }
    }
}
