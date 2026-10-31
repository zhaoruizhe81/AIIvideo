package com.huangguo.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 站点侧的隐藏 WebView —— 在真浏览器内核里过 Cloudflare，再做同源 fetch。
 *
 * ══ 为什么需要它 ══════════════════════════════════════════════════════
 * missav.live 全站（页面和 /api/* 一起）挂在 Cloudflare 的 JS 挑战后面。
 * 2026-10-18 在本机实测过四条路，全部 403：
 *
 * <pre>
 *   curl + 完整浏览器头                        → 403 Just a moment
 *   curl + 换成安卓 Chrome 的 UA               → 403
 *   curl + 从真 Chrome 里偷出来的 cf_clearance  → 403
 *   okhttp（本 App 的默认出口）                 → 403
 * </pre>
 *
 * <b>把浏览器 cookie 喂给 okhttp 依然过不去</b>，这是最容易走错的一步：
 * {@code cf_clearance} 不只是个 token，它绑的是<b>发出请求那台的 TLS 指纹</b>
 * （JA3/JA4 —— ClientHello 里的密码套件、扩展、曲线顺序）。curl 和 okhttp 的
 * ClientHello 和 Chrome 不一样，云端算出来的指纹对不上就再挑战一次，
 * 而且是每次请求都重新判。所以「先拿 cookie 再走 okhttp」这条路在代码上写得通，
 * 在协议上是死的 —— 别再往那个方向试了。
 *
 * 唯一能过盾的执行环境就是真 WebView：那个挑战本身就是靠跑一段 JS 过的。
 *
 * ══ 为什么不复用 MainActivity 那个 WebView ════════════════════════════
 * 主 WebView 的 origin 是 {@code https://appassets.androidplatform.net}
 * （见 MainActivity onCreate 里的 WebViewAssetLoader），那是我们自己的页面。
 * missav 的页面必须<b>同源</b>才能读到自己的 cookie、能免 CORS 地 fetch ——
 * 而它和 appassets 不同源。所以只能另起一个 WebView，独立 origin。
 *
 * ══ 这东西怎么活 ══════════════════════════════════════════════════════
 * 一个 WebView 必须 attach 到一个 window token 上才能跑，所以不能凭空 new：
 * 我们把它挂进 Activity 的 decor View 里（<b>1×1 像素、GONE、不参与布局</b>），
 * 拿到 window 就有了。Activity onDestroy 时必须 {@link #shutdown()}，
 * 否则渲染进程会一直挂着，定时器和视频缓冲都不退。
 *
 * ══ 线程模型 ═══════════════════════════════════════════════════════════
 * 调用方在 {@code AiiHttp} 的工作线程上（后台线程），所以：
 * <ul>
 *   <li>所有 WebView/UI 操作 → post 到主线程</li>
 *   <li>等待结果 → 在调用线程上 CountDownLatch 阻塞</li>
 *   <li>永不反向阻塞主线程（主线程正忙着跑那个 WebView，堵它等于自己等自己）</li>
 * </ul>
 *
 * ══ 用法 ══════════════════════════════════════════════════════════════
 * 只从后台线程调用：
 * <pre>
 *   String html = SiteFetchWebView.fetchPage("https://missav.live/cn/new");
 * </pre>
 */
final class SiteFetchWebView {

    private static final String TAG = "AIIvideo";
    private static final String LOG = "[site-wv] ";

    /**
     * 加载站点首页用于「过盾」的地址。
     *
     * 为什么是 {@code /dm539/cn/new}：这是站点的「最近更新」列表页，实测是
     * 全站里最轻的一页（12 张卡片，约 400KB），而详情页要塞 recommbee 追踪
     * 和一堆广告组件 —— 拿详情页去过盾明显更慢。
     */
    /**
     * 过盾用的页面现在是**源自己声明**的（{@code web.shield.warmup}）。
     *
     * <p>这里原来写死的是 {@code missav.live/dm539/cn/new} —— 壳知道某个源
     * 拿哪一页去过盾，接第二个盾后站点就得改 Java 发版。收回 {@link ShieldProfile}。
     */
    private static String warmupUrl() throws IOException {
        String url = ShieldProfile.warmup();
        if (url.length() == 0) {
            throw new IOException("这个源没有声明 web.shield.warmup，过盾没有入口页。"
                    + "源配置里补一条再试。");
        }
        return url;
    }

    /**
     * 「已经过了盾」的判定。
     *
     * <p><b>不能用状态码或标题</b> —— Cloudflare 挑战页返回的是 403 + 标题
     * 「Just a moment...」，两者都出现在挑战页上，不能当「还没过盾」的证据。
     *
     * <p><b>不能用 {@code .thumbnail}（2026-10-18 真机踩到）</b>：那是卡片容器，
     * 而卡片里第一样东西就是 {@code <img>}。这个 WebView 为了省流量开了
     * {@code setBlockNetworkImage(true)}，被拦掉的图片会让 Chromium 连卡片的
     * 插入都推迟 —— 于是网络其实通了（广告脚本 wheelroot 都加载出来了）、
     * 页面其实也过了盾，但 {@code document.querySelector('.thumbnail')} 一直返回
     * null，最后表现成「过盾 20 秒超时」。判据选在一个会被自己关掉的资源上，
     * 等于自己骗自己。
     *
     * <p>现在用两个判据（<b>都是对着抓下来的真实页面核过的</b>）：
     * <ul>
     *   <li>{@code html[lang]} —— 站点是 {@code <html lang="zh-Hans" data-immersive-translate-page-theme="light">}；
     *       挑战页的 {@code <html>} 上没有 lang。它最稳，因为 html 元素一定先被解析。</li>
     *   <li>{@code .grid} —— 列表页外层栅格（实测 1 个），纯 div，不依赖任何子资源。</li>
     * </ul>
     *
     * <p>一开始还想用 {@code html[data-missav]} 当「站点自己打的标记」，核 fixture
     * 才发现那个属性根本不存在（真实 html 标签是 {@code data-immersive-translate-page-theme}）。
     * 判据这种东西不能凭印象写，<b>必须对着抓下来的页面核一遍</b>。
     *
     * <p>仍然留着 {@code .thumbnail} 吗？不留。它就是被 blockNetworkImage 坑到的那一个，
     * 留着只会让人以为「没命中 .thumbnail 说明没过去」——而实际上它可能已经过去了。
     *
     * <p><b>2026-10-19 晚再修一次：判定不能只写 {@code html[lang]}。</b>
     * Cloudflare 的中文挑战页自己就是
     * {@code <html lang="zh-cn" dir="ltr"><head><title>请稍候…</title>}——
     * 它也有 lang，于是「过盾成功」在第 16 秒就命中，实际拿到的还是挑战页
     * （28956 字节，真页面是 199320 字节）。这就是连续几轮「过盾成功但没内容」
     * 的真正原因：判据命中的是挑战页本身。
     *
     * <p>现在只认站点自己的语言标记 {@code lang="zh-Hans"}（挑战页是 zh-cn）
     * 和列表栅格 {@code .grid}。两者的共同点是：只有真页面才有。
     */
    /**
     * 单次请求超时。
     *
     * 20 秒的由来：过盾本身 1.5~4 秒（慢的话十几秒），加上首屏加载。给太少会把
     * 「网络本来就慢」误判成「被墙了」；给太多会让用户在「设置源」那个按钮上
     * 干等 —— 那一步是「先探通再存」，慢到 40 秒以上用户会以为死机了。
     */
    private static final long TIMEOUT_MS = 45_000L;

    /**
     * in-page fetch 等待的上限，比 TIMEOUT_MS 短 —— 盾已经过了，剩下是普通
     * 同源 XHR，不该继续占着过盾那份额外预算。
     */
    private static final long FETCH_TIMEOUT_MS = 30_000L;

    /** 过盾尝试次数。第二次几乎总能过 —— 挑战过一次 Cloudflare 就发了 clearance。 */
    private static final int PASS_ATTEMPTS = 2;

    /** 回包体积上限。超过就当失败 —— 挑战页/错误页可以很大，真页面用不着。 */
    private static final int MAX_BODY = 4 * 1024 * 1024;

    /** 导航抓页面的上限。挑战 + 400KB 页面，走远程节点要给够。 */
    private static final long NAV_TIMEOUT_MS = 35_000L;

    /**
     * 页面「稳定」的下限：complete 之后至少还要静这么一小段时间才收工。
     *
     * <p>站点在 complete 之后才灌内容（Alpine x-init、懒加载换图），一到 complete
     * 就抄 outerHTML 拿到的是壳 —— 见 doFetchByNav 里那段账。
     */
    private static final long SETTLE_MS = 1_200L;

    /** 等「稳定」的上限：一直在变的页面也得交出去，不能拖满整个导航预算。 */
    private static final long SETTLE_MAX_MS = 10_000L;

    /** 图片通道等解码的上限。封面几十 KB，慢网也就几秒。 */
    private static final long IMAGE_TIMEOUT_MS = 12_000L;

    /**
     * 硬编码的 UA，只在拿不到系统默认 UA 时兜底（见 {@link #chromeUa}）。
     *
     * <p>两个 WebView 共用同一份：Cloudflare 看到 WebView 默认 UA 里的
     * {@code ; wv)} 会把挑战判成「不支持的环境」，转圈永远不放行。
     */
    private static final String UA_CHROME =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

    /**
     * 过盾用的 UA：**系统默认 UA 剥掉 {@code ; wv)}**，不硬编码版本号。
     *
     * <p>2026-10-21 真机（dozagonza）：硬编码 {@code Chrome/130.0.0.0}，而设备
     * WebView 内核是更新的大版本。Cloudflare 会做 UA 与 JS 能力的一致性检查 ——
     * UA 说 130、实测能力不是 130，挑战页就永远停在「请稍候…」（中文版的
     * Just a moment），{@code awaitPass} 判据正确识别成没通过，45 秒 × 2 轮后超时。
     * 同一台机器同一网络下 Chrome 浏览器能打开，报的正是它自己的真实版本。
     *
     * <p>所以这里改成跟随内核：拿 {@code getUserAgentString()}，只做减法 ——
     * 去掉 {@code ; wv)} 和 {@code Version/4.0}（老 WebView 的前缀），剩下的
     * 就是这台设备真实的 Chrome UA。兜底才用 {@link #UA_CHROME}。
     */
    private static String chromeUa(WebSettings s) {
        String ua;
        try {
            ua = s == null ? null : s.getUserAgentString();
        } catch (Throwable e) {
            ua = null;
        }
        if (ua == null || ua.length() == 0) return UA_CHROME;
        // WebView 的默认 UA 形如：
        //   … AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36
        // 去掉 wv 标记和 Version/4.0，就和一个正常的安卓 Chrome 一模一样。
        String out = ua.replace("; wv)", ")").replace("; wv ", " ");
        out = out.replaceAll("Version/\\d+\\.\\d+ ", "");
        return out.length() < 40 ? UA_CHROME : out;
    }

    // ── 单例 ──────────────────────────────────────────────────────────

    /**
     * 唯一实例。<b>必须单例</b> —— 一个进程只有一个 WebView 渲染宿主，
     * 建第二个会在部分 ROM 上直接 crash 或静默不渲染。
     *
     * 它跨 origin 复用：WebView 只有一个渲染宿主，但可以先后加载不同 origin
     * 的页面（cookie jar 按域隔离，导航一次换一次）。所以它服务的是「所有需要
     * 盾的站点」，不是某一个站。两个需要盾的源同时要数据时在这把锁上排队。
     */
    private static SiteFetchWebView instance;

    private static synchronized SiteFetchWebView get() {
        if (instance != null && instance.alive) return instance;
        if (instance != null) {
            // 上一个已经没用了（宿主 Activity 换了）。真正销毁它 ——
            // 只在 field 上置 null 是不够的，渲染进程不会退。
            final SiteFetchWebView old = instance;
            instance = null;
            old.alive = false;
            onMain(new Runnable() {
                @Override
                public void run() {
                    old.destroyNow();
                }
            });
        }
        instance = new SiteFetchWebView();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * 宿主 Activity。**弱引用** —— 强引用会把这个 Activity 拖过它自己的生命周期。
     */
    private WeakReference<Activity> hostRef = new WeakReference<Activity>(null);

    /**
     * 没有 Activity 时的宿主上下文。
     *
     * <p>2026-10-19：spider 宿主搬进 {@code :spider} 独立进程之后，这个类在那边
     * 拿不到 {@code MainActivity.host}（跨进程的静态字段各是一份，子进程里恒 null），
     * 于是每次过盾都抛「界面还没起来」—— MissAV 首页直接挂。
     *
     * <p>headless WebView 不需要 decorView（{@code SpiderRuntime} 一直是这么跑的：
     * 不 attach 也能 loadUrl / evaluateJavascript，只是不渲染像素）。所以这里允许
     * 用 Application 上下文建一个不挂窗口的页面，过盾流程原样复用。
     */
    private static volatile android.content.Context appContext;

    /**
     * 代理提示：{@code socks5://127.0.0.1:PORT} 或空串。
     *
     * <p>内置代理核心跑在<b>主进程</b>，而 spider 宿主在 {@code :spider} 进程 ——
     * 那边 {@code ProxyCore.get()} 是一个没启动过的空实例，{@code httpPort()} 恒 0。
     * 所以子进程必须由主进程把端口告诉它（SpiderClient 本来就把 effectiveProxy
     * 随每次调用带过来了，这里只是接住）。
     */
    private static volatile String proxyHint = "";

    /**
     * 主进程过盾后拿到的 cookie 快照，用来喂给 :spider 进程那个 WebView。
     *
     * <p>为什么必须这么做（2026-10-19 真机，根因）：两个进程的 WebView
     * <b>数据目录是分开的</b>（{@code setDataDirectorySuffix("spider")}，
     * Chromium 不允许同目录），所以 cookie 不共享；而子进程的 WebView 没有 window，
     * Cloudflare 挑战在那种环境里要跑 45 秒以上还是过不去。
     * 主进程有 Activity，同一个挑战 1.3 秒就过 —— 把那边的 {@code cf_clearance}
     * 搬过来，子进程就根本不用再挑战一次：clearance 绑的是 IP + TLS 指纹，
     * 两边都是同一台设备、同一个 WebView 内核，搬过来是有效的。
     */
    private static volatile String cookieHint = "";

    /** 子进程侧：主进程递过来的 cookie。 */
    static void setCookieHint(String raw) {
        cookieHint = raw == null ? "" : raw.trim();
        cookieApplied = false;
    }

    /** 已经往 CookieManager 写过就不要再写第二遍（会互相覆盖）。 */
    private static volatile boolean cookieApplied;

    static void setProxyHint(String effectiveProxy) {
        proxyHint = effectiveProxy == null ? "" : effectiveProxy.trim();
    }

    /** 由 :spider 进程的 Service 在 onCreate 里调一次。主进程不需要（有 Activity）。 */
    static void init(android.content.Context context) {
        appContext = context == null ? null : context.getApplicationContext();
    }

    private WebView webView;
    private View holder;
    private volatile boolean alive;
    private volatile boolean passed;

    /**
     * 页面代号：每次 {@code onPageStarted} 自增。
     *
     * <p>2026-10-19 真机踩到的大坑：{@code loadUrl} 之后立刻轮询判据，读到的是
     * <b>上一份文档</b> —— 它还带着 {@code html[lang]}，于是「第 2 次过盾成功」
     * 在第 2 秒就命中，实际新页面刚起步、还在挑战。后面拿到的永远是一份挑战页，
     * 日志里就是「站点那边重新要了一次访问验证」，而 onPageFinished 在 30 秒后才来。
     *
     * <p>所以判据必须等到「新文档真的开始了」才开始轮询，靠这个代号对齐。
     */
    private volatile int pageGen;

    /** 串行化 fetch：页面状态只有一份，并发请求会互相污染。 */
    private final Semaphore fetchLock = new Semaphore(1, true);

    /**
     * 串行化「过盾」。
     *
     * 单独一把锁而不是复用 fetchLock：过盾是<b>有状态</b>的，中途不能有别的
     * fetch 打进来把页面搅了；而且首次调用要跑完整套挑战流程，比普通 fetch 慢得多。
     */
    private final Semaphore passLock = new Semaphore(1, true);

    /**
     * 图片通道用的第二个 WebView。
     *
     * <p>为什么封面不能再借站点那个 WebView：站点页面的 CSP 把 img-src 收得很死，
     * 在页面里 {@code new Image().src='https://fourhoi.com/…'} 直接被 CSP 拦
     * （真机 CDP 实测 blockedReason=csp）；跨源 fetch 又没有 ACAO 头。
     * <b>导航不受 CSP 约束</b> —— 整个文档换成那张图，图片本身就是同源文档，
     * canvas 画上去不 taint，能直接读像素。
     *
     * <p>为什么单独一个实例而不是复用 siteWebView 导航过去再回来：那会把已经过盾的
     * 列表页顶掉，每张封面都要重新过一遍盾，12 张封面就是 12 次 1.3 秒。
     */
    private WebView imageView;

    /** 图片通道一次只跑一张：一个 WebView 只有一个文档。 */
    private final Semaphore imageLock = new Semaphore(1, true);

    /** 在飞的 fetch，按 id 匹配回包。 */
    private final Map<String, Pending> waiting = new ConcurrentHashMap<String, Pending>();
    private final java.util.concurrent.atomic.AtomicInteger seq =
            new java.util.concurrent.atomic.AtomicInteger();

    private SiteFetchWebView() {}

    private static final class Pending {
        Reply reply;
        final CountDownLatch latch = new CountDownLatch(1);
        /**
         * 二进制请求专用：页面那边按块回调 {@code bin()}，这里累加。
         *
         * 不走 {@link #reply} 的原因很实在 —— 那条路是「JSON 里塞 body 字符串」，
         * 二进制过一轮 responseText 就被当成 UTF-8 解了，字节全毁。视频分片
         * 必须原样，所以单开一条只搬 base64、不碰文本的通道。
         */
        final java.io.ByteArrayOutputStream bin = new java.io.ByteArrayOutputStream();
        int binStatus;
    }

    /** 回包信封。字段对应页面内 fetch 的四个回包键。 */
    private static final class Reply {
        final boolean ok;
        final int status;
        final String body;
        final String error;

        Reply(boolean ok, final int status, String body, String error) {
            this.ok = ok;
            this.status = status;
            this.body = body == null ? "" : body;
            this.error = error == null ? "" : error;
        }
    }

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 在站点页面里发一次同源 GET，把响应体原样带回。
     *
     * 调用线程被阻塞到拿到响应或超时。<b>只从后台线程调用</b>。
     *
     * @param absoluteUrl 站点内的绝对地址，例如 {@code https://missav.live/cn/fct-210}
     */
    static String fetchPage(String absoluteUrl) throws Exception {
        SiteFetchWebView wv = get();
        // 有 Activity 就绑它（主进程那条路，页面挂进 decor）；没有就走无窗口模式。
        // 两条路的分叉在 ensureWebView 里，这里只决定绑不绑。
        MainActivity host = MainActivity.host;
        if (host != null) wv.bindHost(host);
        return wv.doFetchByNav(absoluteUrl);
    }

    /** 站点 URL 是否属于「需要真 WebView 兜着」的站点。 */
    static boolean handles(String url) {
        // 域名表在源配置的 web.shield.hosts 里（2026-10-19 从这行硬编码里收走）。
        return ShieldProfile.handles(url);
    }

    /**
     * 在站点页面里发一次跨源 GET，把<b>响应字节</b>原样带回。
     *
     * <p>存在的唯一理由是视频：{@code surrit.com} 那个 m3u8 / 分片域名用 OkHttp
     * 请求永远是 Cloudflare 的 {@code Attention Required}（2026-10-18 真机：
     * {@code [relay] 上游 403 https://surrit.com/…/playlist.m3u8}），而同一个
     * 地址在 WebView 里 fetch 是 200 —— 差的不是 cookie（实测跨源、不带
     * credentials 也是 200），是 TLS 指纹/UA 那一层。OkHttp 改不了，只能借
     * 浏览器的栈。
     *
     * <p>为什么一次拿完而不是流式：分片实测 236KB（720p，4 秒），1080p 也就
     * 几百 KB。整块 base64 过 bridge 一次到位，比维护背压简单得多，也不会因为
     * VLC 暂时不读就把 WebView 的渲染线程卡住。
     *
     * <p><b>只从后台线程调用。</b>超时和 {@link #fetchPage} 共用一套常量。
     */
    static byte[] fetchBytes(String absoluteUrl) throws Exception {
        SiteFetchWebView wv = get();
        MainActivity host = MainActivity.host;
        if (host != null) wv.bindHost(host);
        return wv.doFetchBytes(absoluteUrl);
    }

    /**
     * 图片取字节：navigate 过去再读 canvas 像素。
     *
     * <p>给静态资源用（封面）。站点页里 fetch 会被 CORS 挡、{@code <img>} 会被
     * 站点 CSP 挡，而 navigate 两者都不受管 —— 这是 Cloudflare 后面那些图片域
     * 唯一还能走通的路。
     *
     * <p><b>只从后台线程调用。</b>
     */
    static byte[] fetchImage(String absoluteUrl) throws Exception {
        SiteFetchWebView wv = get();
        MainActivity host = MainActivity.host;
        if (host != null) wv.bindHost(host);
        return wv.doFetchImage(absoluteUrl);
    }

    /**
     * 释放。<b>必须在 Activity onDestroy 里调用。</b>
     *
     * 漏掉的后果不是「下次再建一个」那么轻：渲染进程留着，页面里的定时器和
     * 视频缓冲都不退，耗电和内存都在涨。
     */
    static void shutdown() {
        final SiteFetchWebView self;
        synchronized (SiteFetchWebView.class) {
            self = instance;
            instance = null;
        }
        if (self == null) return;
        self.alive = false;
        onMain(new Runnable() {
            @Override
            public void run() {
                self.destroyNow();
            }
        });
    }

    // ── 真正干活 ──────────────────────────────────────────────────────

    private void bindHost(Activity activity) {
        if (hostRef.get() != activity) {
            hostRef = new WeakReference<Activity>(activity);
            // 宿主换了，之前那个页面上的 cookie 不再可信（IP 可能变了，
            // 而 clearance 是绑 IP + TLS 指纹的）。重新过一次。
            passed = false;
        }
    }

    /**
     * 盾后站点抓页面的主路：<b>导航过去，把 document 的 HTML 读回来</b>。
     *
     * <p>为什么不再用页面内 XHR（2026-10-19 真机，连续三轮实测）：过盾之后
     * 页面自己还在拉 12 张封面和一堆广告脚本，主线程和下行都被占着；紧接着
     * 发的那次同源 XHR 抢不到执行机会，{@code onload}/{@code onerror}/
     * {@code ontimeout} 一个都不回，30 秒硬超时 —— 日志里就是「页面内那一次
     * 请求超时了」。而 {@code onPageFinished} 恰好在超时之后才来，说明页面
     * 根本没闲着。
     *
     * <p>导航这条路把问题反过来了：浏览器会<b>取消</b>当前页的其它加载去导航，
     * 目标文档加载完 {@code readyState} 就是 {@code complete}，直接读
     * {@code document.documentElement.outerHTML} 即可。clearance 是 cookie，
     * 导航过去照样带着，所以目标页不会再要一次挑战。
     */
    private String doFetchByNav(String absoluteUrl) throws Exception {
        ensureWarm();
        acquire(fetchLock, TIMEOUT_MS, "站点侧请求排队超时。");
        try {
            if (!passed) ensureWarm();

            final String target = absoluteUrl;
            onMain(new Runnable() {
                @Override
                public void run() {
                    if (webView != null) webView.loadUrl("about:blank");
                }
            });
            Thread.sleep(200);
            onMain(new Runnable() {
                @Override
                public void run() {
                    if (webView != null) webView.loadUrl(target);
                }
            });

            long deadline = System.currentTimeMillis() + NAV_TIMEOUT_MS;
            String html = "";
            // 读到内容 ≠ 页面已经长完。
            //
            // 2026-10-19 真机账：同一条链路抓回来的字节数在 167580 / 183982 /
            // 193753 / 382494 之间跳，而参考页（fixtures）是 423841 字节、12 张
            // class="thumbnail group" 卡片。小的那几次 spider 全报「没解析到卡片」——
            // 不是源写坏了，是我们在 readyState=complete 那一瞬间就把 outerHTML
            // 抄下来了：complete 只保证文档加载完，后面站点自己还要往里灌内容
            // （Alpine 那套 x-init 拉列表、懒加载换图）。抄早了拿到的是壳。
            //
            // 所以判据从「读全了」改成「不再变了」：连续 STABLE_HITS 次采样长度一致
            // 才收工；期间页面还在长就继续等。SETTLE_MS 是下限 —— 刚 complete 的
            // 那一下页面必然「稳定」（还没开始变），不等这一小段就会抓早。
            long settleBegan = 0;
            int lastLen = -1;
            int stable = 0;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(400);
                String len = evalOnPage("(function(){"
                        + "if(document.readyState!=='complete')return '';"
                        + "var h=document.documentElement;"
                        + "return h?String(h.outerHTML.length):'';})()");
                if (len.length() == 0) continue;
                int n;
                try {
                    n = Integer.parseInt(len.trim());
                } catch (Throwable bad) {
                    continue;
                }
                if (n < 512) continue;          // 还没渲染出东西
                if (settleBegan == 0) settleBegan = System.currentTimeMillis();
                String current = evalOnPage("document.documentElement.outerHTML");
                if (current.length() < n * 9 / 10) continue;   // 读取过程被改写，重来
                // 长度一致才算一次「稳定」；差多少算变？小改（一个数字、一个时间戳）
                // 不该拖住我们，所以给 2% 或 80 字节的容差。
                final int tol = Math.max(80, lastLen / 50);
                if (lastLen > 0 && Math.abs(current.length() - lastLen) <= tol) {
                    stable++;
                } else {
                    stable = 0;
                }
                lastLen = current.length();
                final long settledFor = settleBegan == 0 ? 0 : System.currentTimeMillis() - settleBegan;
                if (stable >= 4 || settledFor > SETTLE_MAX_MS) {
                    // 4 次 × 400ms ≈ 1.6 秒没再变；或者等了 10 秒还在长（广告轮播、
                    // 计数动画这类永远在变的页面）—— 那样也得交，不能一直等到 NAV 上限。
                    html = current;
                    break;
                }
                if (html.length() == 0) html = current;   // 兜底：至少有得交
                // 下限没到之前不许走「兜底」那条退出，继续等它长。
                if (settleBegan != 0 && System.currentTimeMillis() - settleBegan < SETTLE_MS) {
                    html = "";
                }
            }
            if (html.length() == 0) {
                html = evalOnPage("document.documentElement.outerHTML");
            }
            if (html.length() == 0) {
                throw new IOException("导航到站点页面后没读到内容（"
                        + (NAV_TIMEOUT_MS / 1000) + " 秒）：" + absoluteUrl);
            }
            if (looksLikeChallenge(html)) {
                passed = false;
                // 把证据打出来：是挑战页还是误判，一眼可分。
                log("判成挑战页 " + html.length() + " 字节 head="
                        + html.substring(0, Math.min(160, html.length())).replace('\n', ' '));
                throw new IOException("站点那边重新要了一次访问验证。再进一次通常就过了。");
            }
            log("抓到页面 " + html.length() + " 字节 " + absoluteUrl + "（" + describePage(html) + "）");
            dumpPage(absoluteUrl, html);
            return html;
        } finally {
            fetchLock.release();
        }
    }

    /** 在站点 WebView 上同步跑一段 JS，返回 unquote 后的字符串。 */
    private String evalOnPage(final String js) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[]{""};
        onMain(new Runnable() {
            @Override
            public void run() {
                WebView wv = webView;
                if (wv == null) { latch.countDown(); return; }
                wv.evaluateJavascript(js, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        out[0] = unquote(value);
                        latch.countDown();
                    }
                });
            }
        });
        if (!latch.await(10, TimeUnit.SECONDS)) return "";
        return out[0];
    }

    private String doFetch(String absoluteUrl) throws Exception {
        ensureWarm();
        acquire(fetchLock, TIMEOUT_MS, "站点侧请求排队超时。");
        try {
            // 再确认一次盾还在。过期时站点不会报错，只会给一份 403 的挑战页 ——
            // 那时上层会把挑战页当列表解析，症状是「首页空着但不报错」，很难查。
            if (!passed) ensureWarm();

            String id = String.valueOf(seq.incrementAndGet());
            final Pending pending = new Pending();
            waiting.put(id, pending);

            final String b64 = Base64.encodeToString(
                    specJson(absoluteUrl).getBytes("UTF-8"), Base64.NO_WRAP);

            onMain(new Runnable() {
                @Override
                public void run() {
                    WebView wv = webView;
                    if (wv == null) {
                        deliver(id, new Reply(false, 0, "", "no-webview"));
                        return;
                    }
                    // 注入这一次的 id，再跑 fetch 脚本 —— 页面里的 __hgSiteDone
                    // 会带着同一个 id 回调 __hgSiteRecv。
                    wv.evaluateJavascript(
                            "window.__hgSiteId=" + quote(id) + ";"
                                    + String.format(FETCH_JS, quote(b64)),
                            null);
                }
            });

            boolean settled = pending.latch.await(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            waiting.remove(id);

            if (!settled) {
                throw new IOException("站点页面里那一次请求超时了（"
                        + (FETCH_TIMEOUT_MS / 1000) + " 秒没回）。"
                        + "网络慢的时候它会先失败一次，再进一次通常就出内容。");
            }
            Reply reply = pending.reply;
            if (reply == null || !reply.ok) {
                throw new IOException("站点页面里那一次请求失败："
                        + (reply == null ? "没回话" :
                        (reply.error.length() > 0 ? reply.error : ("HTTP " + reply.status))));
            }
            if (reply.body.length() > MAX_BODY) {
                throw new IOException("站点回了一份 " + (reply.body.length() / 1024)
                        + "KB 的东西，超出上限 —— 多半是拿到了拦截页而不是内容。");
            }
            // 挑战页和真 403 都是 403，靠 body 区分：挑战页一定含这几个特征。
            if (looksLikeChallenge(reply.body)) {
                passed = false;          // 盾掉了，下次重新过
                throw new IOException("站点那边重新要了一次访问验证。"
                        + "网络抖动或者等得太久都会这样 —— 再进一次通常就过了。");
            }
            return reply.body;
        } finally {
            fetchLock.release();
        }
    }

    // ── 页面内 fetch ──────────────────────────────────────────────────

    /**
     * 页面内的 fetch 脚本。
     *
     * <b>为什么走页面内 fetch 而不是 Java 里发</b>：跨源会被 CORS 拦掉
     * （站点响应头有 {@code access-control-allow-origin: *}，但那只对<b>跨源</b>
     * XHR 有用，而我们要的恰恰是同源 —— 只有同源才自动带上 cf_clearance 和
     * user_uuid）。
     *
     * <b>为什么请求体传 base64</b>：列表页 HTML 可能几百 KB，拼进
     * {@code evaluateJavascript} 的源码要处理各种转义；base64 只含
     * {@code [A-Za-z0-9+/=]}，直接拼最安全。代价是 +33% 体积和一次解码，可忽略。
     *
     * <b>为什么必须走 XHR 而不是 fetch()</b>：页面里那条 CSP 禁止了 connect-src 之外的
     * 一些源，而 XHR 的 onload 拿到的 responseText 在这里够用；更重要的是 fetch()
     * 是 Promise，回包还得再包一层才能送回 Java。这里能省一层是一层。
     */
    private static final String FETCH_JS =
            "(function (b64) {"
            + "  var spec = JSON.parse(decodeURIComponent(escape(atob(b64))));"
            + "  var id = String(window.__hgSiteId || '0');"
            + "  var xhr = new XMLHttpRequest();"
            + "  xhr.open(spec.method || 'GET', spec.url, true);"
            + "  try { xhr.withCredentials = true; } catch (e) {}"
            + "  if (spec.headers) { for (var k in spec.headers) {"
            + "    try { xhr.setRequestHeader(k, spec.headers[k]); } catch (e) {} } }"
            + "  xhr.timeout = 15000;"
            + "  function done(r) {"
            + "    try {"
            + "      function b64enc(s) {"
            + "        return btoa(unescape(encodeURIComponent(s))); }"
            + "      window.__hgSiteRecv.recv(b64enc(id), b64enc(JSON.stringify(r)));"
            + "    } catch (e) {}"
            + "  }"
            + "  xhr.onload = function () {"
            + "    done({ ok: true, status: xhr.status, body: xhr.responseText }); };"
            + "  xhr.onerror = function () { done({ ok: false, status: 0, body: '', error: 'network' }); };"
            + "  xhr.ontimeout = function () { done({ ok: false, status: 0, body: '', error: 'timeout' }); };"
            + "  xhr.send(spec.body || null);"
            + "  return 1;"
            + "})(%s)";

    // ── 页面内 fetch（二进制） ────────────────────────────────────────

    /**
     * 二进制取流的执行体。
     *
     * <p>故意<b>不抢 {@link #fetchLock}</b>：播放时 VLC 会连着要好几个分片，
     * 串行化只会把开播拖到几秒以上。这条路是幂等的（只为拿字节，不写页面状态），
     * 并发安全；线程归属靠每次调用自带 id，不依赖 {@code window.__hgSiteId}
     * 那个全局变量（并发时会被覆盖）。
     */
    private byte[] doFetchBytes(String absoluteUrl) throws Exception {
        ensureWarm();

        String id = String.valueOf(seq.incrementAndGet());
        final Pending pending = new Pending();
        waiting.put(id, pending);

        final String b64 = Base64.encodeToString(
                absoluteUrl.getBytes("UTF-8"), Base64.NO_WRAP);
        final String js = "(function(b64,id){" + BIN_JS_BODY + "})("
                + quote(b64) + "," + quote(id) + ")";

        onMain(new Runnable() {
            @Override
            public void run() {
                WebView wv = webView;
                if (wv == null) {
                    deliver(id, new Reply(false, 0, "", "no-webview"));
                    return;
                }
                wv.evaluateJavascript(js, null);
            }
        });

        // 分片要等整块落地，给的时间比普通 fetch 宽一倍：236KB 实测 810ms，
        // 但代理抖动时会更久，而这里是播放链路上唯一一帧，卡住就是黑屏。
        long budget = FETCH_TIMEOUT_MS * 2;
        boolean settled = pending.latch.await(budget, TimeUnit.MILLISECONDS);
        waiting.remove(id);

        if (!settled) {
            throw new IOException("取流超时（" + (budget / 1000) + " 秒没回）：" + absoluteUrl);
        }
        Reply reply = pending.reply;
        if (reply != null && !reply.ok) {
            throw new IOException("取流失败：" + (reply.error.length() > 0
                    ? reply.error : ("HTTP " + reply.status)));
        }
        byte[] data = pending.bin.toByteArray();
        if (pending.binStatus >= 400) {
            throw new IOException("取流被拒：HTTP " + pending.binStatus);
        }
        if (data.length == 0) {
            throw new IOException("取流回来是空的：HTTP " + pending.binStatus);
        }
        return data;
    }

    /**
     * 页面侧二进制取流脚本（只含函数体，id/url 由外面拼进去）。
     *
     * <p>为什么不用 {@code responseText}：那会把二进制当 UTF-8 解，TS 分片
     * 过一遍就废了。这里读 {@code arrayBuffer} 的 reader，按块转 base64 回传。
     *
     * <p><b>不带 credentials</b>（2026-10-18 真机踩到）：第一版写了
     * {@code credentials:'include'}，结果每一次都 {@code TypeError: Failed to fetch}。
     * 原因是 surrit 的 CORS 回的是 {@code Access-Control-Allow-Origin: *}，
     * 而浏览器规定「带凭据的跨源请求」不接受通配 —— 通配只能配
     * {@code credentials:'omit'}。去掉之后立刻 200，说明这条路压根不需要 cookie：
     * 放行我们的是浏览器栈本身，不是某个令牌。
     *
     * <p>为什么分块而不是攒完再传：{@code String.fromCharCode.apply} 参数一多
     * 就爆栈（实测 8 万参数左右），所以每块只取 8192 字节拼一次 {@code btoa}。
     * 分块还能让 Java 侧边收边攒，内存峰值只有整块大小。
     */
    private static final String BIN_JS_BODY =
            "var url=decodeURIComponent(escape(atob(b64)));"
            + "var idb=btoa(id);"
            + "function send(k,p){try{window.__hgSiteRecv.bin(idb,k,p||'')}catch(e){}}"
            + "function enc(u8){var s='',CH=8192;"
            + "  for(var i=0;i<u8.length;i+=CH){"
            + "    s+=String.fromCharCode.apply(null,u8.subarray(i,i+CH));}"
            + "  return btoa(s);}"
            + "fetch(url).then(function(r){"
            + "  if(!r.ok){send('E'+r.status,'');return;}"
            + "  if(!r.body||!r.body.getReader){"
            + "    return r.arrayBuffer().then(function(b){"
            + "      send('S'+r.status,'');send('C',enc(new Uint8Array(b)));send('Z','');});}"
            + "  var rd=r.body.getReader();send('S'+r.status,'');"
            + "  (function pump(){rd.read().then(function(x){"
            + "    if(x.done){send('Z','');return;}"
            + "    send('C',enc(x.value));pump();"
            + "  },function(e){send('X'+e,'');});})();"
            + "}).catch(function(e){send('F'+e,'');});";


    final class SiteBridge {
        @JavascriptInterface
        public void recv(final String idB64, final String payloadB64) {
            Reply reply;
            try {
                String payload = new String(Base64.decode(payloadB64, Base64.DEFAULT), "UTF-8");
                JSONObject o = new JSONObject(payload);
                reply = new Reply(o.optBoolean("ok", false), o.optInt("status", 0),
                        o.optString("body", ""), o.optString("error", ""));
            } catch (Throwable e) {
                reply = new Reply(false, 0, "", "bad-payload");
            }
            String id;
            try {
                id = new String(Base64.decode(idB64, Base64.DEFAULT), "UTF-8");
            } catch (Throwable e) {
                id = "";
            }
            Pending p = id.length() == 0 ? null : waiting.get(id);
            if (p != null) {
                p.reply = reply;
                p.latch.countDown();
            }
        }

        /**
         * 二进制通道。页面那边每块调一次。
         *
         * <p>协议（{@code kind} 的首字符）：
         * <ul>
         *   <li>{@code S<code>} 响应头到了，<code>code</code> 是 HTTP 状态；</li>
         *   <li>{@code C} 一块数据，{@code payload} 是 base64；</li>
         *   <li>{@code Z} 正常结束；</li>
         *   <li>{@code E}/{@code X}/{@code F} 失败，{@code code}/{@code payload}
         *       里是状态码或异常名。</li>
         * </ul>
         *
         * <p><b>这个方法必须立刻返回。</b>它在 WebView 的 Java 桥线程上被调用，
         * 一阻塞就会把渲染线程一起卡住 —— 所以只做 {@code write} 到内存流，
         * 绝不碰 socket、不做 IO。
         */
        @JavascriptInterface
        public void bin(final String idB64, final String kind, final String payload) {
            if (kind == null || kind.length() == 0) return;
            String id;
            try {
                id = new String(Base64.decode(idB64, Base64.DEFAULT), "UTF-8");
            } catch (Throwable e) {
                return;
            }
            Pending p = id.length() == 0 ? null : waiting.get(id);
            if (p == null) return;
            char tag = kind.charAt(0);
            try {
                if (tag == 'S') {
                    p.binStatus = Integer.parseInt(kind.substring(1).trim());
                    return;
                }
                if (tag == 'C') {
                    if (payload == null || payload.length() == 0) return;
                    p.bin.write(Base64.decode(payload, Base64.DEFAULT));
                    return;
                }
                if (tag == 'Z') {
                    p.latch.countDown();
                    return;
                }
                // E / X / F：把状态码或异常名塞进 reply，让上层能报出原因。
                int code = 0;
                if (tag == 'E') {
                    try {
                        code = Integer.parseInt(kind.substring(1).trim());
                    } catch (Throwable ignored) {
                    }
                }
                p.reply = new Reply(false, code, "",
                        tag == 'E' ? ("HTTP " + code) : kind.substring(1));
                p.latch.countDown();
            } catch (Throwable e) {
                p.reply = new Reply(false, 0, "", "bin-bridge");
                p.latch.countDown();
            }
        }
    }

    private void deliver(String id, Reply reply) {
        Pending p = waiting.get(id);
        if (p != null) {
            p.reply = reply;
            p.latch.countDown();
        }
    }

    // ── 过盾 ──────────────────────────────────────────────────────────

    /**
     * 确保盾已过。幂等。
     *
     * 流程：建 WebView → 挂进宿主 decor → 载入站点页 → 轮询文档里有没有出现
     * 列表容器 —— 出现即过盾成功（Cloudflare 通过后会自己导航到目标 URL，
     * 不需要我们再动作一次）。
     */
    void ensureWarm() throws Exception {
        if (passed && webView != null) {
            reapplyProxyIfChanged();
            return;
        }
        acquire(passLock, TIMEOUT_MS, "过盾排队超时。");
        try {
            // 双重检查：等锁期间可能已经有人过好了。
            if (passed && webView != null) return;
            for (int attempt = 1; attempt <= PASS_ATTEMPTS; attempt++) {
                long began = System.currentTimeMillis();
                try {
                    ensureWebView();
                    reapplyProxyIfChanged();
                    boolean ok = loadAndAwait();
                    passed = ok;
                    log("过盾第 " + attempt + " 次" + (ok ? "成功" : "超时")
                            + "，耗时 " + (System.currentTimeMillis() - began) + "ms");
                    if (ok) return;
                } catch (Exception e) {
                    passed = false;
                    log("过盾第 " + attempt + " 次失败：" + e.getMessage());
                }
            }
            throw new IOException("没通过站点的访问验证（Cloudflare）。"
                    + "那道门要真浏览器内核跑一段 JS 才开得了 —— 网络不通、或者出口 IP "
                    + "被标记过，都过不去。换个网络或者开启代理再试一次。");
        } finally {
            passLock.release();
        }
    }

    /** 载入过盾页，等到列表容器出现。 */
    private boolean loadAndAwait() throws Exception {
        final String warmup = warmupUrl();
        // 先把文档清成 about:blank，再导航目标。
        //
        // 不是为了「干净」，是为了判据：`loadUrl` 之后立刻轮询
        // `querySelector('html[lang]')`，读到的是**上一份** DOM —— 它当然还带着
        // html[lang]，于是「过盾成功」在第 1 秒就命中，而新页面其实还在挑战。
        // （2026-10-19 真机：onPageStarted 在无窗口 WebView 上不回调，靠代号也等不到，
        // 只能从文档本身下手。about:blank 里没有 html[lang]，判据不可能骗人。）
        onMain(new Runnable() {
            @Override
            public void run() {
                WebView wv = webView;
                if (wv != null) wv.loadUrl("about:blank");
            }
        });
        Thread.sleep(250);
        onMain(new Runnable() {
            @Override
            public void run() {
                WebView wv = webView;
                if (wv != null) wv.loadUrl(warmup);
            }
        });
        return awaitPass();
    }

    /**
     * 等页面里出现列表容器。
     *
     * <b>轮询而不是只等 onPageFinished</b>：Cloudflare 通过后会<b>再导航一次</b>，
     * 第一次 onPageFinished 触发时可能还是挑战页。
     */
    private boolean awaitPass() throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            final String[] box = new String[1];
            final CountDownLatch latch = new CountDownLatch(1);
            onMain(new Runnable() {
                @Override
                public void run() {
                    WebView wv = webView;
                    if (wv == null) {
                        latch.countDown();
                        return;
                    }
                    // 判据第三版（2026-10-19 深夜，前两版都被真机打脸）：
                    //   v1 正向（zh-Hans/.grid）—— 站点改版就瞎；
                    //   v2 否定式（非挑战特征 + 正文>200）—— 挑战页自己的说明文字
                    //      就有几百字，「过盾成功 602ms」后 fetchPage 拿到的还是
                    //      28KB 新鲜挑战页（22:09 现场）。
                    //   v3 **出站链接计数**：挑战页是孤岛，没有任何 <a href> 指向
                    //      站点内容；真页面（列表/详情）有几十个。这个信号挑战页
                    //      伪造不出来，也不依赖站点写法。
                    wv.evaluateJavascript(
                            "(function(){var h=document.documentElement;"
                            + "if(!h||!document.body) return '0';"
                            + "var o=h.outerHTML;"
                            + "if(o.indexOf('Just a moment')>=0||o.indexOf('_cf_chl_opt')>=0) return '0';"
                            + "var links=document.querySelectorAll('a[href]');"
                            + "return links.length>=8 ? '1':'0';})()",
                            new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String v) {
                                    box[0] = v;
                                    latch.countDown();
                                }
                            });
                }
            });
            if (latch.await(2, TimeUnit.SECONDS)) {
                // evaluateJavascript 的返回值是 **JSON 序列化** 的：JS 里返回 '1'，
                // Java 侧拿到的是带引号的 "1"（三个字符）。直接 equals("1") 永远不等 ——
                // 2026-10-18 真机踩到：页面早就过盾了（12 张卡片都在），
                // 判定却轮询满 20 秒，症状就是「过盾超时」。剥掉引号再比。
                if ("1".equals(unquote(box[0]))) return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    // ── WebView 生命周期 ──────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureWebView() throws Exception {
        if (webView != null && alive) return;
        Activity host = hostRef.get();
        if (host == null && appContext == null) {
            // 两条路都没有：主进程里 Activity 已销毁，或者子进程没 init。
            throw new IOException("没有可用的宿主页面，站点侧请求发不出去（稍等一下再试）。");
        }
        if (host != null && (host.isFinishing() || host.isDestroyed())) {
            throw new IOException("界面正在退出，站点侧请求发不出去。");
        }

        // 无窗口模式（:spider 进程）用 Application 上下文；有 Activity 时仍用它，
        // 少一个跨上下文的坑。
        final android.content.Context activity = host != null ? host : appContext;
        final CountDownLatch latch = new CountDownLatch(1);
        final IOException[] failed = new IOException[1];

        onMain(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup decor = host == null
                            ? null : (ViewGroup) host.getWindow().getDecorView();

                    WebView wv = new WebView(activity);
                    WebSettings s = wv.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    // ★ UA 必须去掉 WebView 标记（2026-10-18 真机实测）
                    //
                    // 系统 WebView 默认 UA 里有 "; wv)"，Cloudflare 看到它就直接把
                    // 挑战判成「不支持的环境」，挑战页转圈永远不放行 —— 网络通、
                    // 代理挂上了、JS 也在跑，就是过不去。表现是 awaitPass 20 秒超时
                    // 连 html[lang] 都等不到（挑战页根本没渲染）。
                    //
                    // 换成不带 wv 的 Chrome 移动版 UA，挑战把它当普通 Chrome 处理，
                    // 正常放行。
                    s.setUserAgentString(chromeUa(s));
                    // 关掉用不到的能力：减少攻击面，也让加载快一点。
                    s.setAllowFileAccess(false);
                    s.setAllowContentAccess(false);
                    // ★ 不要设 setBlockNetworkImage(true) ★
                    //
                    // 2026-10-18 真机踩到：开了它之后，懒加载的 <img> 被拦，
                    // Chromium 连卡片容器的插入都推迟，document.querySelector('.thumbnail')
                    // 20 秒都返回 null —— 明明网络是通的、盾已经过了，判定却永远不成立。
                    //
                    // 代价是过盾那一下会真的下一批图（首屏 12 张封面，每张几十 KB），
                    // 换来的是判据可靠。这个交换值得：判据不可靠的代价是「这个源永远
                    // 加不进来」，而多下几百 KB 只是一次握手。

                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                            pageGen++;
                            // 无窗口模式（:spider 进程）下，页面是「不可见」的：
                            // document.visibilityState 恒 hidden、window.innerWidth 是 0。
                            // Cloudflare 的挑战脚本第一件事就是看这几个值，判定成无头
                            // 浏览器之后挑战页会一直转、永远不跳 —— 表现就是过盾 20 秒
                            // 超时（日志里只有「过盾第 N 次超时」，看不出是这个原因）。
                            //
                            // 所以进页面之前先把这几项掰成普通手机浏览器的值。
                            // 只覆盖读属性，不改行为：挑战该跑的 JS 一行不少。
                            view.evaluateJavascript(STEALTH_JS, null);
                        }

                        @Override
                        public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                                    android.webkit.WebResourceError error) {
                            // 无窗口模式下最怕的就是「什么都不发生」：没有 onPageFinished、
                            // 没有异常，只有 20 秒后的超时。把加载失败的原因直接打出来。
                            log("加载失败 " + request.getUrl() + " code=" + error.getErrorCode()
                                    + " " + error.getDescription());
                        }

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            // 一行就够判断「到底是没加载出来，还是加载了没过盾」：
                            // 前者看不到这句，后者能看到 url 和长度。
                            view.evaluateJavascript(
                                    "(document.title||'').length + '|' + (document.body?document.body.innerText.length:0)",
                                    value -> log("页面加载完 " + url + " title/innerText=" + value));
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view,
                                                              android.webkit.WebResourceRequest r) {
                            // 站点内部的跳转（挑战通过后的重定向、年龄确认页）都不拦。
                            // 跳去别的域名也放过 —— 站点自己会处理，我们只关心
                            // 最终落在不在带 .thumbnail 的那个页面上。
                            return false;
                        }
                    });
                    wv.addJavascriptInterface(new SiteBridge(), "__hgSiteRecv");
                    applyCookieHint();
                    installStealthEarly(wv);
                    applyProxy(wv);

                    // 1×1 + GONE：attach 到 window 之后页面才「有窗口」，这是主进程那条路。
                    //
                    // 子进程没有 decor 可挂 —— headless WebView 本来就不需要它
                    // （SpiderRuntime 从头到尾都没 attach 过，挑战页照样渲染、JS 照样跑）。
                    FrameLayout box = null;
                    if (decor != null) {
                        box = new FrameLayout(activity);
                        box.setVisibility(View.GONE);
                        box.addView(wv, new FrameLayout.LayoutParams(1, 1));
                        decor.addView(box, new ViewGroup.LayoutParams(1, 1));
                    }

                    webView = wv;
                    holder = box;      // 无窗口模式是 null，销毁路径已经判过空
                    alive = true;
                } catch (Throwable e) {
                    failed[0] = new IOException("站点侧 WebView 建不起来：" + e);
                } finally {
                    latch.countDown();
                }
            }
        });

        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IOException("站点侧 WebView 建不起来（等主线程超时）。");
        }
        if (failed[0] != null) throw failed[0];
    }

    /**
     * 把当前生效的代理挂到这个 WebView 上。
     *
     * <p><b>这一步不能省</b>（2026-10-18 真机踩到）：隐藏 WebView 是**另一个网络栈**，
     * {@code AiiHttp} 挂在 OkHttp 上的 {@code proxy()} 对它毫无作用。而需要过 Cloudflare
     * 的站点恰恰全都是「国内直连不通、必须走代理」的那种 —— 不挂代理的表现是
     * 「过盾 20 秒超时」，日志只说超时、看不出是网络根本不通，白查半天。
     *
     * <p>用的是 {@link androidx.webkit.ProxyController}（AndroidX WebKit）而不是
     * {@code android.webkit.ProxyController}：后者 API 21+ 就有，但要求 WebView
     * 跑在独立进程里才生效（{@code setProcessData} 那套），同进程里调它会抛。
     * AndroidX 这份是对同进程场景的兼容封装，正是我们要的。
     *
     * <p>抛异常一律吞掉并退回直连 —— 代理配错不该让整个加源流程崩掉，
     * 表现退回成「过不去」而不是「崩」。
     */
    /**
     * 无窗口模式的可见性伪装。
     *
     * <p>只覆盖「读」：visibilityState / hidden / innerWidth / innerHeight。
     * 不动 userAgent、不动 canvas、不碰 webdriver 那些 —— 这个 WebView 本来就是
     * 真内核，唯一不真实的地方是「没有窗口」，那就只补这一处。
     */
    /**
     * 把无窗口 WebView 掰成「一台普通的可见手机浏览器」。
     *
     * <p>2026-10-19 真机：{@code :spider} 进程里的 WebView 没有 window，
     * {@code document.visibilityState} 恒 {@code hidden}、{@code innerWidth} 是 0。
     * Cloudflare 的挑战脚本第一件事就是看这几个值，判定成无头浏览器之后挑战页
     * 会一直转，表现为「过盾 20 秒超时」（日志里看不到任何报错）。
     *
     * <p><b>注入时机决定成败</b>：原来只在 {@code onPageStarted} 里
     * {@code evaluateJavascript}，那时挑战脚本已经开始跑了，改得太晚。
     * 现在用 {@code addDocumentStartJavaScript} 在文档开始执行前就装好补丁
     * （见 {@link #installStealthEarly}），{@code onPageStarted} 里那份留作兜底。
     */
    private static final String STEALTH_JS =
            "(function(){try{"
                    + "var d=document;"
                    + "Object.defineProperty(d,'visibilityState',{get:function(){return 'visible';}});"
                    + "Object.defineProperty(d,'hidden',{get:function(){return false;}});"
                    + "Object.defineProperty(d,'webkitHidden',{get:function(){return false;}});"
                    + "if(!d.hasFocus){d.hasFocus=function(){return true;};}"
                    + "var w=window;"
                    + "Object.defineProperty(w,'innerWidth',{get:function(){return 412;}});"
                    + "Object.defineProperty(w,'innerHeight',{get:function(){return 915;}});"
                    + "Object.defineProperty(w,'outerWidth',{get:function(){return 412;}});"
                    + "Object.defineProperty(w,'outerHeight',{get:function(){return 915;}});"
                    + "Object.defineProperty(w,'devicePixelRatio',{get:function(){return 2.75;}});"
                    + "try{Object.defineProperty(screen,'width',{get:function(){return 412;}});"
                    + "Object.defineProperty(screen,'height',{get:function(){return 915;}});"
                    + "Object.defineProperty(screen,'availWidth',{get:function(){return 412;}});"
                    + "Object.defineProperty(screen,'availHeight',{get:function(){return 915;}});}catch(e){}"
                    + "if(navigator.webdriver){try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;}});}catch(e){}}"
                    + "}catch(e){}})()";

    /**
     * 在文档开始执行前注入 stealth 补丁。
     *
     * <p>{@code addDocumentStartJavaScript} 只在支持该特性的 WebView 上有；
     * 老内核退回「onPageStarted 里注入」那条，效果差但不会崩。
     */
    private static void installStealthEarly(WebView wv) {
        try {
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                log("内核不支持文档前置注入，退回 onPageStarted");
                return;
            }
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    wv, STEALTH_JS, java.util.Collections.singleton("*"));
        } catch (Throwable e) {
            log("文档前置注入失败，退回 onPageStarted：" + e);
        }
    }


    private void applyProxy(final WebView wv) {
        try {
            final java.net.Proxy proxy = AiiHttp.effectiveJavaProxy();
            androidx.webkit.ProxyController controller = androidx.webkit.ProxyController.getInstance();

            if (proxy == null || proxy == java.net.Proxy.NO_PROXY) {
                // 直连不是「不设置」，是显式清掉 —— 否则上一次设的代理会留在
                // WebView 的全局状态里，用户切回直连后请求还照样走代理。
                controller.clearProxyOverride(MAIN, RUNNER);
                log("直连（当前没有生效的代理）");
                return;
            }
            java.net.SocketAddress address = proxy.address();
            if (!(address instanceof java.net.InetSocketAddress)) {
                log("代理地址不是 IP:PORT 形态，跳过：" + address);
                return;
            }
            java.net.InetSocketAddress isa = (java.net.InetSocketAddress) address;
            String url;
            // 内置核心现在同时开 socks 和 http 两个入站（XrayConfig.httpInbound），
            // WebView 优先走 http 那条 —— 它比 socks5 稳，也比自写桥稳。
            int localHttp = 0;
            try {
                localHttp = ProxyCore.get(wv.getContext()).httpPort();
            } catch (Throwable ignored) {
            }
            if (localHttp == 0) {
                // 子进程：核心在主进程，只能靠主进程传来的端口。
                // effectiveProxy 是 socks5://127.0.0.1:PORT，HTTP 入站在 PORT+1。
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("127\\.0\\.0\\.1:(\\d+)").matcher(proxyHint);
                if (m.find()) {
                    try {
                        localHttp = Integer.parseInt(m.group(1)) + 1;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (localHttp > 0) {
                url = "http://127.0.0.1:" + localHttp;
                controller.setProxyOverride(configFor(url), MAIN, new Runnable() {
                    @Override
                    public void run() {
                        log("WebView 代理已挂上（HTTP 入站）");
                    }
                });
                log("WebView 走代理：HTTP 127.0.0.1:" + localHttp);
                return;
            }
            if (proxy.type() == java.net.Proxy.Type.SOCKS) {
                // ★ WebView 的 ProxyController 对 socks5 支持不稳（2026-10-19 实测）：
                // 设置回调说「已挂上」，但页面根本不加载（onPageFinished 一次都不触发），
                // 而同一个 socks 用 OkHttp 走是通的（能拿到 103）。
                // 设备是 WebView 143，不是版本老的问题，是这条路本身不可靠。
                //
                // 所以在本进程起一个只认 CONNECT 的 HTTP→SOCKS 桥，WebView 走 http://。
                // 桥只绑 127.0.0.1、只转发、不缓存，用完即弃。
                url = "http://127.0.0.1:" + SocksBridge.ensure(isa.getHostString(), isa.getPort());
            } else {
                url = "http://" + isa.getHostString() + ":" + isa.getPort();
            }

            androidx.webkit.ProxyConfig config = configFor(url);

            controller.setProxyOverride(config, MAIN, new Runnable() {
                @Override
                public void run() {
                    log("WebView 代理已挂上");
                }
            });
            log("WebView 走代理：" + proxy.type() + " " + url);
        } catch (Throwable e) {
            // 挂不上就退回直连 —— 代理配错不该让整个加源流程崩掉，
            // 表现退回成「过不去盾」，而不是崩在设置代理这一步。
            log("挂代理失败，退回直连：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private static androidx.webkit.ProxyConfig configFor(String url) {
        java.util.List<androidx.webkit.ProxyConfig.ProxyRule> rules =
                java.util.Collections.singletonList(
                        new androidx.webkit.ProxyConfig.ProxyRule(
                                androidx.webkit.ProxyConfig.MATCH_ALL_SCHEMES, url));
        return new androidx.webkit.ProxyConfig(
                rules, java.util.Collections.<String>emptyList(), false);
    }

    /** {@code setProxyOverride} 要求一个 Executor 和一个完成回调，两边都在主线程。 */
    private static final java.util.concurrent.Executor MAIN =
            new java.util.concurrent.Executor() {
                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            };

    private static final Runnable RUNNER = new Runnable() {
        @Override
        public void run() {
        }
    };

    // ── 图片通道：导航过去再读像素 ────────────────────────────────────

    /**
     * 把图片当页面 navigate 过去，再用 canvas 把像素读回来。
     *
     * <p>存在的理由（2026-10-19 真机实测三条路都断了）：
     * <ul>
     *   <li>okhttp 取 {@code fourhoi.com} 的封面 → 失败；</li>
     *   <li>站点页里 {@code fetch()} 它 → 跨源没 ACAO，{@code TypeError: Failed to fetch}；</li>
     *   <li>站点页里 {@code new Image()} → 被站点 CSP 的 img-src 拦（CDP 报 blockedReason=csp）；</li>
     *   <li>主页面（appassets）里 {@code new Image()} → {@code net::ERR_BLOCKED_BY_ORB}。</li>
     * </ul>
     * 导航是唯一不受 CSP / ORB 管辖的加载方式：整份文档就是那张图，图片成了同源
     * 资源，canvas 画上去不 taint，{@code toDataURL} 能读。
     *
     * <p>返回原始图片字节（JPEG 重编码后的）。失败抛 IOException，上层照常降级成占位字。
     */
    byte[] doFetchImage(String absoluteUrl) throws Exception {
        ensureImageView();
        acquire(imageLock, TIMEOUT_MS, "图片通道排队超时。");
        try {
            final String target = absoluteUrl;
            onMain(new Runnable() {
                @Override
                public void run() {
                    if (imageView == null) return;
                    imageView.loadUrl(target);
                }
            });

            // 等图片解码完成。不能只等 onPageFinished —— 图片文档的 finish 早于
            // 解码，那时 naturalWidth 还是 0，canvas 会画出空白。
            long deadline = System.currentTimeMillis() + IMAGE_TIMEOUT_MS;
            boolean ready = false;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(300);
                String probe = evalOnImage("(function(){var im=document.images&&document.images[0];"
                        + "return im&&im.complete&&im.naturalWidth>0?'Y':'N';})()");
                if ("Y".equals(probe)) { ready = true; break; }
            }
            if (!ready) {
                String ct = evalOnImage("String(document.contentType||'')");
                throw new IOException("图片没加载出来（contentType=" + ct + "）：" + absoluteUrl);
            }

            String data = evalOnImage("(function(){var im=document.images[0];"
                    + "try{var c=document.createElement('canvas');"
                    + "c.width=im.naturalWidth;c.height=im.naturalHeight;"
                    + "c.getContext('2d').drawImage(im,0,0);"
                    + "return c.toDataURL('image/jpeg',0.92).split(',')[1]||'';}"
                    + "catch(e){return 'E'+e;}})()");
            if (data == null || data.length() == 0) {
                throw new IOException("图片读不出像素：" + absoluteUrl);
            }
            if (data.charAt(0) == 'E') {
                throw new IOException("图片读像素失败：" + data.substring(1));
            }
            return Base64.decode(data, Base64.DEFAULT);
        } finally {
            // 导航下一个地址前先清空，避免上一张的像素被当成这一张。
            onMain(new Runnable() {
                @Override
                public void run() {
                    if (imageView != null) imageView.loadUrl("about:blank");
                }
            });
            imageLock.release();
        }
    }

    /** 同步跑一段 JS，返回 unquote 后的字符串。超时返回 ""。 */
    private String evalOnImage(final String js) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[]{""};
        onMain(new Runnable() {
            @Override
            public void run() {
                WebView wv = imageView;
                if (wv == null) { latch.countDown(); return; }
                wv.evaluateJavascript(js, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        out[0] = unquote(value);
                        latch.countDown();
                    }
                });
            }
        });
        if (!latch.await(6, TimeUnit.SECONDS)) {
            // runnable 没跑 = 主线程被占，不是求值失败：返回空串会让上层把
            // 「主线程卡了」误诊成「图片没加载出来」。
            throw new IllegalStateException("等待主线程超时：图片通道 JS 求值没执行（主线程被占住）");
        }
        return out[0];
    }

    private void ensureImageView() throws Exception {
        if (imageView != null && alive) return;
        Activity host = hostRef.get();
        if (host == null && appContext == null) {
            throw new IOException("没有可用的宿主页面，图片通道起不来。");
        }
        final android.content.Context ctx = host != null ? host : appContext;
        final CountDownLatch latch = new CountDownLatch(1);
        final Throwable[] failed = new Throwable[1];
        onMain(new Runnable() {
            @Override
            public void run() {
                try {
                    WebView wv = new WebView(ctx);
                    WebSettings s = wv.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setBlockNetworkImage(false);
                    s.setUserAgentString(chromeUa(s));
                    s.setAllowFileAccess(false);
                    s.setAllowContentAccess(false);
                    wv.setWebViewClient(new WebViewClient());
                    Activity host = hostRef.get();
                    if (host != null) {
                        ViewGroup decor = (ViewGroup) host.getWindow().getDecorView();
                        wv.setVisibility(View.GONE);
                        decor.addView(wv, new FrameLayout.LayoutParams(1, 1));
                    }
                    applyProxy(wv);
                    imageView = wv;
                } catch (Throwable e) {
                    failed[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!latch.await(8, TimeUnit.SECONDS)) {
            // runnable 没跑 = 主线程被占：往下走只会误报「没建出 WebView」，
            // 让上层把「主线程卡了」当成图片通道坏了。
            throw new IllegalStateException("等待主线程超时：图片通道 WebView 没建出来（主线程被占住）");
        }
        if (failed[0] != null) throw new IOException("图片通道起不来：" + failed[0]);
        if (imageView == null) throw new IOException("图片通道起不来（没建出 WebView）");
    }

    /**
     * 代理从「没有」变成「有」之后，把 ProxyController 重挂一次。
     *
     * <p>2026-10-19 真机：冷启动时代理核心还没就绪（{@code [core] 核心就绪} 比
     * WebView 第一次加载晚 300ms），那次 {@code applyProxy} 判定成「直连」，
     * 而它只在建 WebView 的时候跑一次 —— 于是整个进程生命周期里这个 WebView
     * 都在直连，missav 直连是 {@code ERR_CONNECTION_TIMED_OUT}，过盾永远超时。
     */
    private void reapplyProxyIfChanged() {
        // 比的是「当前实际生效的代理」，不是 proxyHint —— 后者只有 :spider 进程会设，
        // 主进程拿不到。2026-10-19 真机：主进程的 WebView 在代理核心起来之前就建好了，
        // applyProxy 判定成直连，之后再也不重挂，missav 直连 ERR_CONNECTION_TIMED_OUT。
        String want;
        try {
            java.net.Proxy proxy = AiiHttp.effectiveJavaProxy();
            want = (proxy == null || proxy == java.net.Proxy.NO_PROXY)
                    ? "direct" : String.valueOf(proxy.address());
        } catch (Throwable e) {
            return;
        }
        if (want.equals(appliedProxyHint)) return;
        final WebView wv = webView;
        if (wv == null) return;
        appliedProxyHint = want;
        onMain(new Runnable() {
            @Override
            public void run() {
                applyProxy(wv);
            }
        });
    }

    /** 已经挂到 ProxyController 上的那份 hint。空串 = 没挂过（或挂的是直连）。 */
    private volatile String appliedProxyHint = "";

    /** 把主进程递过来的 cookie 写进本进程的 CookieManager（每个进程只写一次）。 */
    private static void applyCookieHint() {
        final String raw = cookieHint;
        if (cookieApplied || raw == null || raw.length() == 0) return;
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            String declared = ShieldProfile.cookieOrigin();
            String origin = declared.length() > 0
                    ? declared
                    : ("https://" + hostOf(ShieldProfile.warmup()));
            // CookieManager.setCookie 的第一个参数是 **URL**，不是 bare origin：
            // 域名归属靠它解析，路径取它的 path。配置里存的是 "https://missav.live/"
            // 这种带路径的形式没问题，但 bare host 或漏 scheme 就会写错地方 ——
            // 统一补全成 scheme://host/ 再写。
            if (!origin.startsWith("http")) origin = "https://" + origin;
            if (!origin.endsWith("/")) origin = origin + "/";
            int wrote = 0;
            for (String part : raw.split(";")) {
                String item = part.trim();
                if (item.length() == 0 || item.indexOf('=') <= 0) continue;
                String name = item.substring(0, item.indexOf('=')).trim();
                // clearance 之类带 Secure/HttpOnly 属性的，setCookie 只收 name=value，
                // 属性段剥掉（CookieManager 自己会按 origin 规则补 Secure）。
                String value = item;
                int semi = item.indexOf(';');
                if (semi > 0) value = item.substring(0, semi).trim();
                // setCookie(url, value) 返回 boolean，但部分 ROM 的重载是 void ——
                // 用 tryRegion 式的保守写法：boolean 可用时记成功数，否则只记调用。
                try {
                    Object r = android.webkit.CookieManager.class
                            .getMethod("setCookie", String.class, String.class)
                            .invoke(cm, origin, value);
                    if (r instanceof Boolean && !((Boolean) r)) {
                        log("cookie 写入被拒：" + name + " → " + origin);
                    } else {
                        wrote++;
                    }
                } catch (Throwable reflectFail) {
                    cm.setCookie(origin, value);
                    wrote++;
                }
            }
            cm.flush();
            // 只有真的写进去过才置位：全被拒时置 true 会让这里永远不再重试，
            // clearance 一过期就再也注入不回去了。
            if (wrote > 0) cookieApplied = true;
            // 回读验证：写完立刻 getCookie，读不回来 = 写失败（数据目录/域规则问题），
            // 这行日志能把「注入了但没用」和「根本没注入」一次分清。
            String readBack = cm.getCookie(origin);
            log("已注入主进程 cookie：" + origin + " 写入 " + wrote + " 条，回读 "
                    + (readBack == null ? "空" : String.valueOf(readBack.length()) + " 字节"));
        } catch (Throwable e) {
            log("cookie 注入失败：" + e);
        }
    }

    /**
     * 主进程侧：确保自己过完盾，然后把 cookie 交给要跨进程的那一次调用。
     *
     * <p>首次会阻塞 1~2 秒（主进程有 window，实测 1.3 秒过盾）；之后直接返回。
     * 拿不到就返回空串，子进程退回自己过盾（慢，但不会因此失败）。
     */
    static String cookieSnapshot() {
        try {
            SiteFetchWebView wv = get();
            MainActivity host = MainActivity.host;
            if (host != null) wv.bindHost(host);
            wv.ensureWarm();
            String declared = ShieldProfile.cookieOrigin();
            String target = declared.length() > 0 ? declared : ShieldProfile.warmup();
            if (target.length() == 0) return "";
            String raw = android.webkit.CookieManager.getInstance().getCookie(target);
            return raw == null ? "" : raw;
        } catch (Throwable e) {
            log("取 cookie 快照失败：" + e);
            return "";
        }
    }

    /** 真正销毁。**必须在主线程上**。 */
    private void destroyNow() {
        try {
            if (webView != null) {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.removeJavascriptInterface("__hgSiteRecv");
                if (holder != null) {
                    if (holder instanceof ViewGroup) ((ViewGroup) holder).removeView(webView);
                }
                webView.destroy();
                webView = null;
            }
            if (holder != null && holder.getParent() instanceof ViewGroup) {
                ((ViewGroup) holder.getParent()).removeView(holder);
            }
            holder = null;
            if (imageView != null) {
                imageView.stopLoading();
                imageView.loadUrl("about:blank");
                android.view.ViewParent p = imageView.getParent();
                if (p instanceof ViewGroup) ((ViewGroup) p).removeView(imageView);
                imageView.destroy();
                imageView = null;
            }
            passed = false;
        } catch (Throwable ignored) {
            // 销毁路径上什么都别做：抛出去只会打断 Activity 的销毁流程。
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    /** 剥掉 evaluateJavascript 回包的 JSON 引号。"1" → 1；null/空原样返回。 */
    /**
     * 把 {@code evaluateJavascript} 的返回值还原成 JS 里那个字符串。
     *
     * <p>2026-10-19 深夜真机查到的真凶：这个回调给的**不是裸字符串，是 JSON 序列化
     * 过的字符串**。原来只把最外层的一对引号剥掉（`"1"` → `1` 那种小返回够用），
     * 但 `outerHTML` 走同一条路就全毁了 —— HTML 里的 `<` 被写成 `\u003C`、引号写成
     * `\"`，于是「抓到的页面」里一个 `<div class="thumbnail"` 都不存在，
     * 症状是：日志写「抓到页面 184959 字节」，spider 报「没解析到卡片」，
     * 而卡片计数是 0、链接计数也是 0（真实页面有几十个 <a href>）。
     * 用 JSONTokener 整体解一遍，转义、\\u 序列、换行都跟着还原。
     */
    private static String unquote(String v) {
        if (v == null) return "";
        final String t = v.trim();
        if (t.length() == 0) return "";
        try {
            final Object o = new org.json.JSONTokener(t).nextValue();
            if (o instanceof String) return (String) o;
            if (o == null || o == org.json.JSONObject.NULL) return "";
            return String.valueOf(o);          // 数字/布尔（rare，但别丢）
        } catch (Throwable ignored) {
            // 解不出来就原样返回：至少不比原来更差。
            if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
                return t.substring(1, t.length() - 1);
            }
            return t;
        }
    }

    private static void onMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    private static void acquire(Semaphore lock, long ms, String message) throws Exception {
        while (true) {
            try {
                if (lock.tryAcquire(ms, TimeUnit.MILLISECONDS)) return;
                throw new IOException(message);
            } catch (InterruptedException e) {
                // FutureTask.cancel(true) 会中断排队中的 worker（SpiderRuntime 的
                // 15 秒预算掐断，2026-10-19）。信号不能吞：吞了的话排队的这次
                // 过盾会继续跑完 2×45 秒，占住 passLock，主进程的外包窗口被拖后。
                Thread.currentThread().interrupt();
                throw new IOException("过盾排队被取消（预算已交给主进程）。", e);
            }
        }
    }

    private static String specJson(String url) throws Exception {
        JSONObject spec = new JSONObject();
        spec.put("url", url);
        spec.put("method", "GET");
        return spec.toString();
    }

    /** JS 字符串字面量。id 和 base64 都只含安全字符，但仍然统一走这里。 */
    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** Cloudflare 挑战页的特征，命中任意一个就算挑战页。 */
    static boolean looksLikeChallenge(String html) {
        if (html == null) return false;
        // 2026-10-19 真机踩到：原来这里还认 "challenges.cloudflare.com"，
        // 而**正常页面自己就会引用挑战脚本**（Turnstile / 站点后台的机器人校验），
        // 于是真实的列表页被判成挑战页，日志里冒出「站点那边重新要了一次访问验证」，
        // 内容却明明抓到了（title/innerText="4|113"）。
        // 现在只认挑战页独有的东西：那句标题，和挑战页才有的 _cf_chl_opt。
        return html.contains("Just a moment")
                || html.contains("_cf_chl_opt");
    }

    static String hostOf(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private static void log(String message) {
        try {
            Log.d(TAG, LOG + message);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这一页到底是什么形状 —— 只有字节数判断不了。
     *
     * <p>2026-10-19 现场：过盾成功、抓到 183982 字节、回填也命中了，spider 却报
     * 「没解析到卡片」。字节数一样能是「挑战已过但列表还没渲染」的那种半成品页，
     * 所以把卡片数、链接数、正文长度一并打出来，和源的切分正则对齐着看。
     */
    private static String describePage(String html) {
        if (html == null || html.length() == 0) return "空";
        final java.util.regex.Matcher m = CARD_DIV.matcher(html);
        int cards = 0;
        while (m.find()) cards++;
        final java.util.regex.Matcher a = LINK_A.matcher(html);
        int links = 0;
        while (a.find()) links++;
        return "卡片=" + cards + " 链接=" + links
                + " 含thumbnail=" + (html.indexOf("thumbnail") >= 0)
                + " 挑战特征=" + looksLikeChallenge(html)
                + " head=" + html.substring(0, Math.min(120, html.length())).replace('\n', ' ');
    }

    private static final java.util.regex.Pattern CARD_DIV =
            java.util.regex.Pattern.compile("<div\\b[^>]*\\bclass\\s*=\\s*\"[^\"]*\\bthumbnail\\b");

    private static final java.util.regex.Pattern LINK_A =
            java.util.regex.Pattern.compile("<a\\b[^>]*\\bhref\\s*=");

    /**
     * 把过盾拿到的页面落一份盘（files/site-dump/，最多留 8 份）。
     *
     * <p>为什么要留：解析失败时唯一能回答「是站点改版还是拿到半成品页」的就是原文，
     * 而这份原文只有过盾那条路拿得到（okhttp 拿不到、curl 也拿不到）。留在手机上
     * 才能 adb pull 出来对照源的正则。
     */
    private static void dumpPage(String url, String html) {
        try {
            final android.content.Context ctx = appContext;
            if (ctx == null || html == null || html.length() == 0) return;
            final java.io.File dir = new java.io.File(ctx.getFilesDir(), "site-dump");
            if (!dir.exists() && !dir.mkdirs()) return;
            final java.io.File[] old = dir.listFiles();
            if (old != null && old.length >= 8) {
                java.util.Arrays.sort(old, new java.util.Comparator<java.io.File>() {
                    @Override
                    public int compare(java.io.File x, java.io.File y) {
                        return Long.compare(x.lastModified(), y.lastModified());
                    }
                });
                for (int i = 0; i <= old.length - 8; i++) old[i].delete();
            }
            final String name = System.currentTimeMillis() + "-"
                    + hostOf(url).replaceAll("[^A-Za-z0-9.-]", "_") + ".html";
            final java.io.File out = new java.io.File(dir, name);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            try {
                fos.write(html.getBytes("UTF-8"));
                fos.flush();
            } finally {
                fos.close();
            }
            log("页面已落盘 " + out.getAbsolutePath() + "（" + html.length() + " 字节）");
        } catch (Throwable e) {
            log("页面落盘失败：" + e);
        }
    }

    // ── 站点侧要过盾的域名 ────────────────────────────────────────────

    /**
     * 要过盾的域名来自源配置的 {@code web.shield.hosts}。
     *
     * <p>这里原来是 {@code {"missav.live", "surrit.com"}} 两行硬编码 ——
     * 壳知道某个源的站点域和视频 CDN，换源就得改 Java。
     */
    static boolean isShielded(String url) {
        return ShieldProfile.isShielded(url);
    }
}
