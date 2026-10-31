package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.InetSocketAddress;
import java.net.Proxy;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * AIIvideo —— HTTP 层（OkHttp）。
 *
 * 原来这里用 HttpURLConnection，为了加 DoH 折腾过一版，撞墙了：入口域名在
 * Cloudflare 后面，必须发对 SNI 才握手成功，而「连 A 地址、握手时报 B 域名」
 * 这件事 HttpURLConnection 做不到 —— 它只会拿 URL 里的 host 去建 SSLSocket，
 * 那个 host 被换成了 IP，Android 对 IP 字面量不发 SNI，事后用
 * SSLParameters.setServerNames() 覆盖也不生效（实测 verifier 收到的一直是 IP，
 * 证书全被判不匹配）。日志长这样：
 *
 *     SSLPeerUnverifiedException: Hostname 116.131.57.65 not verified
 *
 * OkHttp 把这两件事分开了：URL 保留域名 → 握手自然带对 SNI；只有**解析**这一步
 * 交给 Dns 接口替换。所以同一个 URL，DoH 解析和多 IP 重试都能挂上去，SNI 不受影响。
 *
 * ── 关于 DoH 到底有多大用 ────────────────────────────────────────────
 * 实测结论（2026-10-16，真机 Redmi 23113RKC6C）：
 *
 *   墙外 DoH   8.8.8.8 / 1.1.1.1 / 9.9.9.9   全部 6 秒超时，连不上
 *   墙内 DoH   阿里 223.5.5.5、腾讯 120.53.53.53 通
 *
 * 但墙内 DoH 绕不开污染 —— 它只保护「设备到 223.5.5.5」这一段，阿里自己向上
 * 递归那一段照样被污染，然后把毒答案通过加密通道完好地递过来。对 huangguoai.com
 * 阿里给 108.160.163.108（Dropbox 段）、腾讯给 104.244.46.63（Twitter 段），
 * 每次还不一样；而同一个服务器问 cab3f.nt17g20k.cc 就能正确返回那五个 IP。
 *
 * 所以 DoH 不是「绕过污染的银弹」，它的实际价值是：
 *   1. 拿到某个域名的**全部** IP 而不是系统给的那一个，坏一个能换下一个
 *   2. 为「入口域名被墙了换一个」留出可配置的接口
 * 而当前这个 origin（cab3f.nt17g20k.cc）在手机系统解析上是干净的，
 * 所以 enabled 默认就是 false —— 没有要修的东西，开着只是多一跳。
 *
 * ── 编译 ────────────────────────────────────────────────────────────
 * 这份用 Java 写，build.sh 负责编译成 classes5.dex：
 *   javac（android.jar + okhttp/okio/kotlin-stdlib）→ d8 → unknown/classes5.dex
 * OkHttp 4.10.0 是 Kotlin 写的，所以编译期需要 okio 与 kotlin-stdlib 的 jar；
 * 运行期这两个包里本来就有（红果源在用），不额外增加体积。
 */
public final class AiiHttp {

    private static final int CONNECT_TIMEOUT = 8;
    private static final int READ_TIMEOUT = 15;
    private static volatile OkHttpClient client;

    private static volatile Context appContext;

    // ── 代理 ──────────────────────────────────────────────────────────
    //
    // 给有机场的用户用：填一个 host:port，所有请求走它。
    // 支持三种写法：host:port（HTTP 代理）/ http://host:port / socks5://host:port
    //
    // 为什么值得做：实测这条链路六次里四次 9~18 秒（cf-ray 落在 LAX），
    // 而这是客户端唯一能改变路由的手段 —— DoH、连接复用、预热都只是把
    // 已经确定的那段延迟挪个位置，换路由才是换一条路。
    private static final String NET_STORE = "aii-net";
    private static final String PROXY_KEY = "proxy";
    private static volatile String appliedProxy = null;

    /**
     * 真正拿去建客户端的那个代理串。跟 appliedProxy 的区别：后者只记「用户手填的」，
     * 因为 setProxy() 改的也是它 —— 两者混用的话，内置核心一启动，
     * 「用户填的代理」这个事实就丢了，阶段 4 的设置页读回 proxyRaw() 会读出一个
     * 用户从没填过的 127.0.0.1 端口号。
     */
    private static volatile String appliedEffective = null;

    // ── 代理来源的优先级 ──────────────────────────────────────────────
    //
    // 两个来源：内置核心起的本地 SOCKS5，和用户手填的外部地址。
    // 内置优先 —— 用户点「开启」就是明确要用它，手填的那条只是他没删掉的历史配置。
    //
    // **回落语义要想清楚再改：** 内置核心没跑（含启动失败）时这里回落，不是置空。
    // 用户填了外部代理就还走那条，什么都没填才是直连。所以「核心挂了」不等于
    // 「流量从本地 IP 出去」，除非他本来就没配过外部代理。
    // 失败时的报文由 UI 负责（ProxyCore.lastError()），这一层只决定走哪条管道路由。
    //
    // 反过来说，「只要内置、不许回落」是个合理的诉求 —— 那是阶段 4 的设置项，
    // 不是这里的判断。这里一旦硬编码成「内置挂了就直连」，用户会以为代理还生效着。
    //
    // 别把这段逻辑读进 proxyRaw()：它代表「用户填了什么」，是这个类对外唯一的
    // 那个事实来源，混进来之后阶段 4 就没法把两个概念分开显示了。
    /**
     * 优先级：**直连 > 内置核心 > 外部地址**。
     *
     * 之前的实现只有一句「内置非空就用内置，否则用手填的」—— 它压根不看模式，
     * 于是「直连」只是「把核心停掉」，盘上那个手填地址照样生效：
     * 设置页写着「所有请求都不走代理」，实际每条请求都在往 127.0.0.1:7897 上撞。
     * 拔了那台电脑 / 关掉那个客户端，全站请求失败，而界面还在说"直连"。
     *
     * 现在模式是开关，「直连」真的直连：
     *   direct   → 空串。用户按了直连，就不该还有东西偷偷走代理
     *   external → 手填地址
     *   builtin  → 内置核心；核心没起来回落到手填地址（见 EffectiveProxyTest：
     *              核心起不来不等于用户配的外部代理作废）
     * 读不到模式（init 之前 / 老调用点）时退回老规矩：内置优先、否则手填。
     */
    static String effectiveProxy(String mode, String builtin, String raw) {
        String core = builtin == null ? "" : builtin;
        String manual = raw == null ? "" : raw;
        if (ProxyConsole.MODE_DIRECT.equals(mode)) return "";
        if (ProxyConsole.MODE_EXTERNAL.equals(mode)) return manual;
        return core.length() > 0 ? core : manual;
    }

    /** 当前模式。拿不到 context 返回空串，让上面那条退回老规矩。 */
    static String proxyModeOrEmpty() {
        Context context = appContext;
        if (context == null) return "";
        try {
            return ProxyConsole.proxyMode(context);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 进程级代理覆盖。**只有 :spider 服务进程会用到**（SpiderService.call 每次请求
     * 前设置）：内置核心的本地 SOCKS 地址是主进程内存里的值，SharedPreferences
     * 里没有它，独立进程自己读永远读出空 —— 不传这个覆盖，「源声明要走代理」
     * 的源在隔离进程里全部退化成直连。主进程不设置（null），行为与原来完全一致。
     */
    private static volatile String processProxyMode;
    private static volatile String processProxyEffective;

    /** 给 SpiderService 用的注入点。两个值都来自主进程调用那一刻的快照。 */
    static void applyProcessProxyOverride(String mode, String effective) {
        processProxyMode = mode == null ? "" : mode;
        processProxyEffective = effective == null ? "" : effective;
    }

    /**
     * 当前的代理串。每次建客户端时问一次，所以核心启停之后必须
     * invalidateClients()，否则旧客户端会一直用着上一个代理。
     */
    static String effectiveProxy() {
        // 进程覆盖优先：:spider 进程没有核心、SharedPreferences 里也没有内置地址，
        // 主进程传来的快照是那里唯一的代理事实来源。语义与 effectiveProxy(mode,…)
        // 一致：direct 就是空串，否则用主进程算好的那条。
        if (processProxyEffective != null) {
            if (ProxyConsole.MODE_DIRECT.equals(processProxyMode)) return "";
            return processProxyEffective;
        }
        return effectiveProxy(proxyModeOrEmpty(), builtinProxy(), proxyRaw());
    }

    /**
     * 内置核心的地址，空串表示没跑或起不来。
     *
     * 取不到 context 就直接当没跑：init() 之前不会有请求，真走到了说明调用顺序
     * 出了问题，这时候把「内置不可用」当结论比抛异常安全 —— 代理配置永远不该
     * 成为整个 App 的启动阻塞点。
     */
    private static String builtinProxy() {
        Context context = appContext;
        if (context == null) return "";
        try {
            return ProxyCore.get(context).socksUrl();
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ── 预热（②） ─────────────────────────────────────────────────────
    //
    // 实现搬到 {@link AiiHttpCache}（2026-10-23）。这里只留两个入口：warmup / 换源重置。

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 取回 url 的全部响应体。
     * 签名与改造前的 SiteRepository.fetch 对齐，异常语义也一样（抛 Exception）。
     *
     * 2026-10-19：适配器分流那一段（{@code /api/...} 交给各站自己的 Java 实现）
     * 整段撤掉。现在剩下的两种源是：手写 REST 配置（origin + paths，直接拼地址）
     * 与 spider 源（前端经 spider 桥取内容，根本不经过这里）。
     */
    public static byte[] get(String url, SiteProfile profile) throws Exception {
        // 每次请求都记下目标主机。SiteProfile 的 origin 到底有没有被用户配置换掉，
        // 看这一行比往 smali 里插探针可靠 —— 探针写错会把整个类搞成 VerifyError，
        // 而且探针本身没验证过，输出不能当证据。
        // 预热有结果就直接用（等它、或取已经落下的那份），没有就自己发。
        byte[] hot = AiiHttpCache.take(url);
        if (hot != null) return hot;
        return fetchUncached(url, pathOfUrl(url), profile);
    }

    /**
     * 判定「这条连接被中途掐断」。2026-10-17 实测：api.agedm.io 走 Cloudflare，
     * 手机网络上 HTTP/2 会在 1.5 秒左右被重置（unexpected end of stream），而同一条
     * 网络里 HTTP/1.1 完全正常 —— 典型是中间设备不认 h2。这类错误重试同一协议
     * 没有意义，只能换协议。
     */
    static boolean isStreamBreak(Throwable error) {
        Throwable t = error;
        while (t != null) {
            // SocketTimeoutException 也算：h2 的连接池里挂着一条其实已经死掉的链路时，
            // 表现就是读完超时（实测 AGE 的播放解析被这个卡住，同一条网络 curl 0.2 秒）。
            if (t instanceof java.net.SocketTimeoutException) return true;
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            // 裸的 "timeout" 字样不再算数：URL 里带 timeout 的 4xx 也会命中，
            // 把站点级失败误降级成 h1。真超时由上面的 SocketTimeoutException 判定。
            if (m.contains("unexpected end of stream") || m.contains("stream was reset")
                    || m.contains("connection reset") || m.contains("broken pipe")
                    || m.contains("protocol error")) return true;
            t = t.getCause();
        }
        return false;
    }

    /** 真正的网络取数。get() 负责分流、预热命中、然后调它。 */
    private static byte[] fetchUncached(String url, String userFacingPath, SiteProfile profile) throws Exception {
        log("GET " + hostOfUrl(url) + pathOfUrl(url));
        Exception first = null;
        try {
            return getWith(client(profile), url, profile);
        } catch (Exception e) {
            // 这里必须是 Exception，不能是 IOException。
            //
            // getWith → rawWith 的签名是 `throws Exception`，它会把 OkHttp 的错误
            // 包成 java.lang.Exception 再抛出来。原来 catch IOException 永远不命中，
            // 于是「降级重试」这条路径**一次都没执行过** —— 日志里只有
            // `cap=home fail: unexpected end of stream`，从来没有「降级 HTTP/1.1」。
            // 快速切页时每个请求都撞一次、每次都直接失败，用户看到的就是卡住。
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            first = e;
        }
        // 降级之后再给两次机会。
        //
        // 2026-10-19 真机实测：`unexpected end of stream` 在 5G 上会连着出现
        // （一次测试里 4 个请求中了 3 个）。只降级一次的话，第二次又撞上同一条
        // 死链路就直接报「AGE动漫 请求失败」，用户看到的是内容停在缓存不再更新 ——
        // 体感就是「点了没反应」。
        //
        // 中间让出 120ms：坏连接要一点时间才会被 OkHttp 的连接池剔掉，
        // 立刻重试大概率还是它。
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return getWith(http1Client(profile), url, profile);
            } catch (Exception e) {
                boolean last = attempt == 1;
                log("HTTP/1.1 重试 " + (attempt + 1) + " 失败：" + e.getMessage() + (last ? "（放弃）" : ""));
                if (first == null) first = e;
                if (last || !isStreamBreak(e)) throw e;
                try {
                    Thread.sleep(120);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        // **不要把原因吃掉。** 原来这条只写 URL，线上 313 条「请求失败：https://…」
        // 翻遍数据库只看到 code=IOException，分不清是超时、被 RST、还是 TLS 失败。
        // 现在带上原始异常的类名和 message（一次重试链可能有多条，取第一条）。
        throw new IOException("请求失败：" + userFacingPath + "（" + describe(first) + "）", first);
    }

    /** 把异常压成「类名: message」，给上报和日志用。null 安全。 */
    static String describe(Throwable error) {
        if (error == null) return "原因不明";
        String name = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return name;
        // 有些 message 已经自带类名（OkHttp 的 "java.net.SocketTimeoutException: timeout"），
        // 别拼成两遍。
        if (message.startsWith(name)) return message;
        return name + ": " + message;
    }

    private static byte[] getWith(OkHttpClient http, String url, SiteProfile profile) throws Exception {
        return getWith(http, url, null, profile);
    }

    /**
     * 带调用方自定义头的 GET。**走和适配器同一条路由**（{@link NetRoute} + 自洽性兜底）。
     *
     * <p>存在的理由：spider 运行时（{@code __spider:net}）要能带 Referer / UA 抓页面，
     * 而 {@code getRaw} 那一族用的是 {@link #effectiveRoute()}（全局代理设置），
     * 完全不知道「当前源声明哪些域该走代理」。2026-10-19 真机就栽在这上面：
     * 内置核心还没起来的那 0.7 秒里，spider 的 net 按全局设置判成直连，直连撞上
     * 被污染的 IPv6，一个 60 秒的超时串烧（{@code failed to connect to
     * yhdm.one/2001::caa0:…}）。
     *
     * <p>所以这条走 {@link #client(SiteProfile)} + {@code NetRoute.mustProxy} 的
     * 自洽补丁，和适配器取数据一模一样 —— 路由只该有一个真相。
     */
    static byte[] getRaw(String url, java.util.Map<String, String> headers, int timeoutSeconds,
                         SiteProfile profile) throws Exception {
        return getRaw(url, headers, timeoutSeconds, profile, null);
    }

    /**
     * 走源路由的带自定义头 GET，**额外把 {@code Set-Cookie} 收进 {@code cookieOut}**。
     *
     * <p>三参版本原样保留成一行转发（行为不变），真正干活的是这里：多出来的只有
     * 那个 {@code cookieOut}，路由、超时、h2→h1 降级一个字没动。
     *
     * <p>为什么必须走这条而不是 {@code getRaw(url, headers, timeout, cookieOut)}：
     * 后者问的是全局代理设置，不懂「当前源声明哪些域该走代理」。spider 的
     * {@code net} 在两个重载之间选，选错就是「能拿到 cookie 的那次请求走了错误的
     * 路由」——91porn 这种站每次请求都要带标记，那次请求挂了等于整页都挂了。
     */
    static byte[] getRaw(String url, java.util.Map<String, String> headers, int timeoutSeconds,
                         SiteProfile profile, java.util.Map<String, String> cookieOut) throws Exception {
        log("GET " + hostOfUrl(url) + pathOfUrl(url));
        OkHttpClient http = client(profile);
        if (timeoutSeconds > 0) {
            // 必须从现有 client 派生（newBuilder 继承拦截器/连接池），只覆写超时。
            // 裸 new Builder 会把重定向链记录拦截器丢掉，撞环时只剩数字没有路径。
            http = http.newBuilder()
                    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .build();
        }
        // h2 被中途掐断就降级 h1 重试一次 —— 和 fetchUncached 同一条规矩。
        // 出口节点抖的时候实测就是这个形态：18 秒后 Connection reset，重试才过。
        try {
            return getWith(http, url, headers, profile, cookieOut);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            // 同样从现有 client 派生：dns/proxy/认证随 newBuilder 原样继承，
            // 不再手工挑着抄（之前那样漏掉了拦截器和连接池）。
            OkHttpClient h1 = http.newBuilder()
                    .connectTimeout(http.connectTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .readTimeout(http.readTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                    .build();
            return getWith(h1, url, headers, profile, cookieOut);
        }
    }

    private static byte[] getWith(OkHttpClient http, String url,
                                  java.util.Map<String, String> headers, SiteProfile profile) throws Exception {
        return getWith(http, url, headers, profile, null);
    }

    /**
     * 同上，外加把 {@code Set-Cookie} 收进 {@code cookieOut}（null 就是不要）。
     *
     * <p>四参版本原样保留成一行转发，行为不变；真正干活的在这里。加 {@code cookieOut}
     * 只多了一句 {@link #harvestCookies}，请求的构造、状态码判据全部照旧，
     * 分流与失败自愈统一走 {@link #routeAndSend}。
     */
    private static byte[] getWith(OkHttpClient http, String url, java.util.Map<String, String> headers,
                                  SiteProfile profile, java.util.Map<String, String> cookieOut) throws Exception {
        Request.Builder builder = new Request.Builder().url(url);
        applyHeaders(builder, profile, url);
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        // 不要压缩。这条链路本来就慢，多一次解压不值当，也让响应体大小可预期。
        builder.header("Accept-Encoding", "identity");
        try {
            return routeAndSend(http, builder.build(), url, cookieOut, profile);
        } catch (java.io.IOException error) {
            throw withRedirectHint(error);
        }
    }

    /**
     * 给「重定向撞上限」那条错误补一句人话，其余原样返回。
     *
     * OkHttp 抛 `ProtocolException("Too many follow-up requests: 21")` — 只有数字。
     * 线上 74 条全是这个形态，既看不出绕去哪，也分不清是站点在环、还是我们把
     * Host/Referer 发错了触发了循环。拦截器已经记下每一跳，这里把它们拼进去。
     */
    private static java.io.IOException withRedirectHint(java.io.IOException error) {
        String hint = describeRedirects();
        if (hint.isEmpty()) return error;
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (message.contains("重定向环")) return error;
        return new java.io.IOException(message + hint, error);
    }


    /**
     * 记住 application context —— 磁盘缓存要用它。幂等，每次启动调一次即可。
     */
    public static void init(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext();
            AiiHttpDns.init(appContext);
            // 路由记忆的落盘位置。两个进程（主 + :spider）都各自 init 一遍，
            // 文件 + mtime 检查保证两边读到同一份。
            RouteMemory.init(new java.io.File(appContext.getFilesDir(), "route-memory.json"));
            // 进程起来就把老的「手填地址 + 默认直连」迁成 external。
            // 不能等用户打开设置页才迁 —— 那之前所有请求都会变成直连，
            // 用户看到的是「升级完就全站连不上了」。
            ProxyConsole.migrateProxyMode(appContext);
        }
    }

    /**
     * 后台预热首屏（实现与踩过的坑见 {@link AiiHttpCache}）。
     */
    public static void warmup(final SiteProfile profile) {
        if (profile == null || appContext == null) return;
        AiiHttpCache.warmup(profile, new AiiHttpCache.Fetcher() {
            @Override public byte[] fetch(String url, SiteProfile p) throws Exception {
                return fetchUncached(url, pathOfUrl(url), p);
            }
        });
    }

    /**
     * 换源之后必须调一次：把**绑在源上**的进程内状态全部作废。
     *
     * <p>预热／在飞那两摊在 {@link AiiHttpCache#reset}，这里只负责连接池 —— 那个
     * client 把 profile 捕获进去了（见 {@code client()}），不清就还连着旧源。
     */
    public static void resetForSourceChange() {
        int[] cleared = AiiHttpCache.reset();
        invalidateClients();
        log("换源：清掉预热 " + cleared[1] + " 条、在飞 " + cleared[0] + " 条，连接池作废");
    }

    /**
     * 钉死 IP 的直连 GET —— DNS rebinding 防护的接口侧。
     *
     * <p>调用方先在受控环境解析一次域名，把拿到的地址组钉进来；这条请求的 DNS
     * 只会答出这组地址，连接建立前后都<b>不存在第二次解析</b>，域名被污染/被
     * rebinding 也劫不走流量。
     *
     * <p>行为边界（与 {@link #getRaw} 家族刻意不同，别顺手抹平）：
     * <ul>
     *   <li><b>只走直连</b>：代理摘掉 —— 钉死 IP 本身就是「认定了这条直连路径」；</li>
     *   <li><b>失败直接抛</b>：不做 h2→h1 降级、不做路由换路重试 —— 任何回退都会
     *       重新解析域名，防护就白做了；</li>
     *   <li><b>不进路由记忆</b>：钉死 IP 的成败学进 {@link RouteMemory} 会污染
     *       正常路由，这条路径完全绕开 routeAndSend；</li>
     *   <li>超时沿用 getRaw 默认（连接 8s / 读 15s），源声明的响应头模板不套
     *       （调用方无源上下文）；防盗链所需的 Referer 由调用方经
     *       {@code extraHeaders} 自带 —— 与 getRaw 收 headers 的方式一致，
     *       同名头后者覆盖。</li>
     * </ul>
     *
     * @param extraHeaders 逐条挂到请求上（与 getRaw 同语义），null/空 = 不加
     * @param pinnedAddrs  受控解析得到的地址组，空/null 直接拒绝
     */
    public static byte[] getRawPinned(String url, java.util.Map<String, String> extraHeaders,
                                      java.net.InetAddress[] pinnedAddrs) throws Exception {
        if (pinnedAddrs == null || pinnedAddrs.length == 0) {
            throw new IOException("钉死地址为空，拒绝请求：" + hostOfUrl(url));
        }
        log("GET(pinned) " + hostOfUrl(url) + pathOfUrl(url)
                + "（钉死 " + pinnedAddrs.length + " 个地址，只走直连、失败即抛）");
        Response response = pinnedDirectClient(pinnedAddrs).newCall(pinnedRequest(url, extraHeaders)).execute();
        try {
            int code = response.code();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " @ " + url);
            ResponseBody body = response.body();
            return body == null ? new byte[0] : body.bytes();
        } finally {
            response.close();
        }
    }

    /** 两参便捷版：不带额外请求头。保住跨模块约定的短签名。 */
    public static byte[] getRawPinned(String url, java.net.InetAddress[] pinnedAddrs) throws Exception {
        return getRawPinned(url, null, pinnedAddrs);
    }

    /** 钉死请求的统一构造：identity 头 + 调用方的额外头（合并语义同 getRaw）。包内可见给单测。 */
    static Request pinnedRequest(String url, java.util.Map<String, String> extraHeaders) {
        Request.Builder builder = new Request.Builder().url(url)
                .header("Accept-Encoding", "identity");
        if (extraHeaders != null) {
            for (java.util.Map.Entry<String, String> e : extraHeaders.entrySet()) {
                builder.header(e.getKey(), e.getValue());
            }
        }
        return builder.build();
    }

    /**
     * 钉死 IP 的直连 client：DNS 对任何域名都答出恰好这组地址，代理摘掉。
     *
     * <p>从缓存 client 派生（newBuilder 共享连接池和重定向记录拦截器，参照
     * {@link #getRaw} 的 h1 降级写法）。**不进 CLIENTS 缓存**：不同调用方的钉死
     * 地址组各不相同，缓存键表达不了这个维度。
     *
     * <p>包内可见：单测直接验证「DNS 只答钉死地址」「不挂代理」两条。
     */
    static OkHttpClient pinnedDirectClient(java.net.InetAddress[] pinnedAddrs) {
        final java.util.List<InetAddress> pinned = java.util.Arrays.asList(pinnedAddrs);
        Dns pinnedDns = new Dns() {
            @Override public List<InetAddress> lookup(String hostname) {
                return pinned;
            }
        };
        return clientFor(false, CONNECT_TIMEOUT, READ_TIMEOUT, null, null)
                .newBuilder()
                .proxy(null)
                .dns(pinnedDns)
                .build();
    }

    /**
     * 朴素的 GET，不带 profile、不走 DoH、不套 headers 模板。
     *
     * 给 CMS 适配器用：那边要拼自己的查询参数，也不该继承「我们站点」的请求头。
     * DNS 走系统 —— CMS 地址是用户自己填的，我们没有它的 DoH 配置。
     */
    /**
     * 强行走 HTTP/1.1 的朴素 GET，**不尝试 HTTP/2**。
     *
     * 给探针用：mrds66 的 HTTP/2 会被反复重置，OkHttp 降级过程要花掉整个超时窗口，
     * 导致探针在 20 秒内永远完不成。直接走 h1 省掉这一轮折腾。
     */
    public static byte[] getRawHttp1(String url, int timeoutSeconds) throws Exception {
        log("GET(h1) " + hostOfUrl(url) + pathOfUrl(url));
        Request request = new Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .build();
        return rawWith(plainHttp1Client(timeoutSeconds), request, url);
    }

    public static byte[] getRaw(String url, int timeoutSeconds) throws Exception {
        log("GET " + hostOfUrl(url) + pathOfUrl(url));
        Request request = new Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url);
        }
    }

    private static byte[] rawWith(OkHttpClient http, Request request, String url) throws Exception {
        return routeAndSend(http, request, url, null, null);
    }

    // ── 路由判定 + 失败自愈（方案 A，2026-10-19）──────────────────────
    //
    // 优先级：NetRoute 强制分支（directOnly / 声明源 mustProxy，作者显式，失败也不
    // 换路）> RouteMemory 记忆 > 老规则兜底。第一次尝试用初始路由；连接层失败
    // （超时 / reset / TLS / 域名解析）换另一条路重试一次，成功即 RouteMemory.learn。
    // HTTP 4xx/5xx 是站点自己的问题，不换路不学习。

    /**
     * 这个 URL 的初始路由。
     *
     * <p>声明的源里 {@code mustProxy=false} 的域是作者要的直连，所以声明源只认
     * forced 两支；未声明的源记忆优先于「全局代理开 → 走代理」的老兜底。
     */
    private static String initialRoute(String url) {
        final String host = hostOfUrl(url);
        if (NetRoute.directOnly(host)) return RouteMemory.ROUTE_DIRECT;
        final boolean declared = NetRoute.isDeclared();
        if (declared && NetRoute.mustProxy(host)) return RouteMemory.ROUTE_PROXY;
        final String mem = RouteMemory.opinion(host);
        if (mem != null) return mem;
        if (!declared && NetRoute.mustProxy(host)) return RouteMemory.ROUTE_PROXY;
        return RouteMemory.ROUTE_DIRECT;
    }

    /** direct ↔ proxy 互换。其他值原样返回（调用方拿它判「有没有另一条路」）。 */
    static String flipRoute(String route) {
        if (RouteMemory.ROUTE_DIRECT.equals(route)) return RouteMemory.ROUTE_PROXY;
        if (RouteMemory.ROUTE_PROXY.equals(route)) return RouteMemory.ROUTE_DIRECT;
        return route;
    }

    /**
     * 是不是「换个路由可能就好」的失败。
     *
     * <p>判据是排除式的：{@code rawWith} / {@code getWith} 对非 2xx 抛的
     * {@code IOException("HTTP <code> @ …")} 说明<strong>请求已经到达了对方</strong>
     * —— 路由是通的，失败是站点的，换路没有意义。剩下的（超时、连接被拒/重置、
     * TLS、域名解析不出来）大概率是「这条路上不去」。
     */
    static boolean isConnectFailure(IOException e) {
        if (e == null) return false;
        final String m = e.getMessage();
        return m == null || !m.startsWith("HTTP ");
    }

    /**
     * 等本机代理端口能连上（只认 127.0.0.1 / localhost）。
     *
     * <p>真机场景（2026-10-21）：第一次安装、刚点完「保存并启动」就去拉一个必须走
     * 代理的源，核心的 SOCKS 入站还在 listen，connect refused，于是第一条请求直接
     * 失败；重启 App 之后核心已经跑了十几秒，同一个源就能拉。这里给 2 秒把它等回来，
     * 比让用户重启便宜。
     *
     * @return 连上了返回 true（调用方据此同一条路重试一次）
     */
    private static boolean awaitLocalProxy(String proxy, long timeoutMs) {
        if (proxy == null || proxy.length() == 0) return false;
        String host;
        int port;
        try {
            java.net.URI u = java.net.URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
            host = u.getHost();
            port = u.getPort();
        } catch (Throwable e) {
            return false;
        }
        if (host == null || port <= 0) return false;
        if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) return false;
        long began = System.currentTimeMillis();
        while (System.currentTimeMillis() - began < timeoutMs) {
            java.net.Socket probe = new java.net.Socket();
            try {
                probe.connect(new java.net.InetSocketAddress(host, port), 150);
                return true;
            } catch (Throwable ignored) {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } finally {
                try { probe.close(); } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    /**
     * 把路由落到 client 上：direct 手里这本就没代理就直接用（缓存的连接池/拦截器
     * 都还在），带着代理才派生摘掉；proxy 兜 proxiedClient。
     */
    private static OkHttpClient clientFor(OkHttpClient http, String route, SiteProfile profile) {
        if (RouteMemory.ROUTE_DIRECT.equals(route)) {
            // 手里这本本就直连（direct 模式下 client() 建的就是它）→ 原样用。
            // 之前这里无条件 new 一个全新 client：每条请求一次新 TLS 握手、各自一个
            // 连接池（idle 连接各留 5 分钟），还把重定向记录拦截器丢了。
            if (http.proxy() == null) return http;
            // 确需换路（这个域要直连，而手里的 client 带着代理）：派生而不是裸
            // new —— newBuilder 共享连接池和拦截器，只摘代理。proxy(null) 与裸
            // Builder 建的直连 client 同一状态（跟随系统 ProxySelector）。
            return http.newBuilder()
                    .proxy(null)
                    .dns(dnsFor(http, profile))
                    .build();
        }
        if (RouteMemory.ROUTE_PROXY.equals(route) && http.proxy() == null) {
            return proxiedClient(profile, http);
        }
        return http;
    }

    /**
     * 统一的「选路 → 发请求 → 失败换路重试一次 → 学习」。
     *
     * <p>两条老路径（getWith / rawWith）的分流分支收拢到这里之后行为对齐：
     * 以前 getWith 缺「代理路不可用时兜底直连」之外的一切自愈，rawWith 亦然。
     */
    private static byte[] routeAndSend(OkHttpClient http, Request request, String url,
                                       java.util.Map<String, String> cookieOut,
                                       SiteProfile profile) throws Exception {
        final String host = hostOfUrl(url);
        String route = initialRoute(url);
        final String startedRoute = route;
        IOException first = null;
        boolean proxyWakeRetried = false;
        String triedRoutes = route;
        for (int attempt = 0; attempt < 2; attempt++) {
            final OkHttpClient client = clientFor(http, route, profile);
            try {
                Response response = client.newCall(request).execute();
                try {
                    harvestCookies(response, cookieOut);
                    int code = response.code();
                    if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " @ " + url);
                    ResponseBody body = response.body();
                    // 换路换对了：记住这条路，TTL 内同域首访不再先走错一次。
                    // 老代码只有 forget 没有 learn —— opinion() 因此永远是 null，
                    // 记忆层事实上是死的，每个新进程都要重新试错一遍（实测
                    // img.dozagonza.com 直连 178.94.178.146 必失败，每次启动白付一次连接超时）。
                    if (!route.equals(startedRoute)) {
                        RouteMemory.learn(host, route);
                        log("路由记忆 " + host + " → " + route);
                    }
                    return body == null ? new byte[0] : body.bytes();
                } finally {
                    response.close();
                }
            } catch (IOException e) {
                // 走代理这条路失败，而代理是本机那个端口 —— 多半是核心刚起/刚重启，
                // 入站还没 listen（connect refused）。给它 2 秒，同一条路再试一次，
                // 比立刻「换路」正确：换直连对一个必须走代理的域名只会再失败一次。
                //
                // **只能重试一次。** 原来这个口子没有上限，而循环只有两轮 ——
                // 两轮都从这里 continue 出去，换路那条支路一次都不走、异常也一次
                // 都不记，最后抛的是「两条路由都没走通，且没有留下异常」。
                // 那句话是假的：直连根本没试过，真正的原因（代理侧 reset / TLS 失败）
                // 被丢了两次。2.0.24 第一天就撞上：pornhub、porn91 各两次，库里只剩
                // 这一句没法归因的话。所以先留证据，再决定还重不重试。
                if (!proxyWakeRetried && isConnectFailure(e) && RouteMemory.ROUTE_PROXY.equals(route)
                        && awaitLocalProxy(effectiveProxy(), 2000)) {
                    proxyWakeRetried = true;
                    if (first == null) first = e;
                    log("本机代理刚就绪，重试一次 " + host + "（" + describe(e) + "）");
                    continue;
                }
                final String flipped = flipRoute(route);
                final boolean canFlip = !flipped.equals(route)
                        && (!RouteMemory.ROUTE_PROXY.equals(flipped) || effectiveProxy().length() > 0);
                if (!isConnectFailure(e) || !canFlip) {
                    // 站点级失败，或者没有另一条路可换：原样上抛。
                    throw e;
                }
                if (first == null) first = e;
                log("路由自愈 " + host + "：" + route + " 失败（" + describe(e) + "），改走 " + flipped + " 重试");
                // 换路要用「新鲜」的请求体：上一次 execute 消费不掉的话 OkHttp 自己
                // 会重建，Request 本身无状态，直接复用是安全的。
                route = flipped;
                triedRoutes = triedRoutes + "→" + flipped;
            }
        }
        // 两条路都失败到「换路也上不去」：学不了（不知道哪条对），把可能过时的
        // 记忆清掉回落到规则层，抛第一个错。
        //
        // first 为 null 说明一次都没进过 catch（理论上不会），这时也得说清是谁：
        // 原来这里只写一句「请求失败：URL」，线上 313 条无法归因的失败有一半是它。
        RouteMemory.forget(host);
        if (first != null) throw first;
        // 走到这里说明两次都「成功返回却没 return」，理论上不可能。真出现也不许
        // 再冒充「两条路都试过了」—— 把试过哪几条写进去，别再让一句话吃掉整次排查。
        throw new IOException("请求失败：" + url + "（试过的路由：" + triedRoutes + "，且没有留下异常）");
    }

    /**
     * 同上，外加把响应里的 `Set-Cookie` 收进 `cookieOut`（键=名字，值=值）。
     *
     * 有的站「游客标记」只在响应头里下发，正文里一个字节都没有 —— 91porn 的 `ga`
     * 就是这样：没有它，同一个 viewkey 每次请求换一部片（这套「游客标记」原来写在
     * 已删除的 Porn91Adapter 里，现在归 spider 自己处理）。这类站没别的拿法，
     * 只能从响应头剥。
     */
    private static byte[] rawWith(OkHttpClient http, Request request, String url,
                                  java.util.Map<String, String> cookieOut) throws Exception {
        return rawWith(http, request, url, cookieOut, null);
    }

    /**
     * 真正干活的那个 {@code rawWith}：自定义头之外，再带上两个跟「路由」有关的输入。
     *
     * <ul>
     *   <li>{@code cookieOut} 收 {@code Set-Cookie}（见上一段）。</li>
     *   <li>{@code profile} <b>当前源配置</b>，只为一件事存在：路由兜底要拿它建
     *       {@link AiiHttpDns}。传 null 就等于「这次请求不知道源声明了什么」，
     *       兜底会退回 {@code like.dns()} —— 见 {@link #dnsFor}。</li>
     * </ul>
     *
     * <p><b>为什么 profile 是参数而不是静态字段</b>：{@code AiiHttp} 是给整个 App 用的，
     * 同时可能有两个源在跑（主内容 + 播放解析）。路由恰好是最不该有第二份真相的东西 ——
     * 一个静态 {@code currentProfile} 会让「A 源的请求用上 B 源的 hosts 钉死」变成
     * 一个只在并发下复现的 bug，而症状是「偶尔连到错的 IP」，日志上看不出来。
     *
     * <p>分流与失败自愈统一在 {@link #routeAndSend}（2026-10-19 方案 A）——
     * 它和 getWith 的老内联分支就「该不该换 client」做过同样的事，收拢成一处
     * 之后两个出口的行为不会再分叉。
     */
    private static byte[] rawWith(OkHttpClient http, Request request, String url,
                                  java.util.Map<String, String> cookieOut,
                                  SiteProfile profile) throws Exception {
        return routeAndSend(http, request, url, cookieOut, profile);
    }

    /**
     * 把响应里的 {@code Set-Cookie} 收进 {@code cookieOut}（键=名字，值=值，不含属性段）。
     *
     * <p>从 {@link #rawWith} 里搬出来的：GET 和 POST 都需要这段，而它的边界条件
     * （没有等号的行、名字为空的行）值得只写一遍。搬的时候<strong>逻辑一个字没动</strong>
     * —— 顺序、trim、取第一个分号前的那截，全都和原来那条内联循环一样：
     * 同名的多条 {@code Set-Cookie} 后者覆盖前者，{@code Path} / {@code Max-Age}
     * 这些属性一律不要（调用方拼请求头时只认 {@code name=value}）。
     *
     * @param cookieOut 为 null 就什么都不做（不需要 cookie 的调用方不用为此付出代价）
     */
    private static void harvestCookies(Response response, java.util.Map<String, String> cookieOut) {
        if (cookieOut == null || response == null) return;
        for (String line : response.headers("Set-Cookie")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim();
            String rest = line.substring(eq + 1);
            int semi = rest.indexOf(';');
            String value = (semi >= 0 ? rest.substring(0, semi) : rest).trim();
            if (name.length() > 0) cookieOut.put(name, value);
        }
    }

    /**
     * 带自定义头的朴素 GET。播放层解析要用它取播放页（对面按 User-Agent 分流）。
     */
    /**
     * 一次性拉取（订阅用）。**不复用连接池**。
     *
     * 2026-10-18 实测：同一条网络，设备上 curl 1.5 秒拿到 52KB，而走
     * plainClient 的请求卡满 20 秒后 Read timed out。普通 GET 都正常，
     * 只有这个 CDN 的地址会挂 —— 高度怀疑是 OkHttp 连接池里挂着一条
     * 已死的链路（那个域名的 keep-alive 很短），复用它就一直等。
     *
     * 所以这里每次 new 一个 client、并且禁掉连接池复用。代价是一次额外的
     * TLS 握手，换的是「一定不会踩到死连接」。订阅一天拉不了几次，
     * 这点开销无所谓。
     */
    public static byte[] getOnce(String url, int timeoutSeconds) throws Exception {
        return getOnce(url, timeoutSeconds, false);
    }

    /**
     * 代理子系统自己的下载：订阅、内置节点池。
     *
     * 这两件事**必须不受当前内容源的路由声明影响**。2026-10-20 真机踩到：
     * 源里写着 `net.proxy: []`（显式全直连），于是 `NetRoute.directOnly()` 对
     * 任何域名都返回 true，连「拉订阅」都被摘掉代理去直连 —— 而机场域名正在
     * 墙外，表现就是拉订阅一直等到超时。
     *
     * 内容源的 net 说的是「这个站的页面/CDN 怎么走」，跟代理设置里拉订阅没有
     * 半点关系。所以这条入口固定先走代理，代理不通再退回直连（自建服务、内网
     * 订阅地址走代理本来就不对）。
     */
    public static byte[] getOnceRouted(String url, int timeoutSeconds) throws Exception {
        return getOnce(url, timeoutSeconds, true);
    }

    private static byte[] getOnce(String url, int timeoutSeconds, boolean ignoreSourceRoute)
            throws Exception {
        Request request = new Request.Builder().url(url)
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .build();
        ProxyRoute route = ignoreSourceRoute
                ? effectiveRoute()
                : (NetRoute.directOnly(hostOfUrl(url)) ? null : effectiveRoute());
        try {
            return executeOnce(request, route, timeoutSeconds, url);
        } catch (IOException e) {
            // 代理不通就退回直连试一次：订阅地址可能是国内的自建服务，
            // 走机场节点反而绕远甚至被拒。
            // 只退两类：这次真的走了代理（route != null），且失败在连接层
            // （isConnectFailure —— "HTTP 404" 说明请求已穿过代理到达对方，
            // 再直连一次只会拿到另一个答案，把真正的站点错误盖掉）。
            if (route == null || !isConnectFailure(e)) throw e;
            log("订阅下载走代理失败（" + e.getMessage() + "），改直连重试");
            return executeOnce(request, null, timeoutSeconds, url);
        }
    }

    private static byte[] executeOnce(Request request, ProxyRoute route, int timeoutSeconds,
                                      String url) throws Exception {
        OkHttpClient.Builder oneBuilder = new OkHttpClient.Builder()
                .connectTimeout(Math.min(timeoutSeconds, 15), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                // callTimeout 是**整条请求**的上限（DNS + 连接 + 读完 body）。
                // 只有 connect/read 两个超时的话，对方慢慢吐字节就永远读不完：
                // 真机上见过机场订阅 TTFB 9.7 秒、总耗时 10.1 秒，而桥的上限是 30 秒，
                // 中间再有点抖动就整条卡死，用户看到的就是「拉订阅 timeout」。
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .dns(IPV4_FIRST);
        // **必须挂用户配的代理。** 之前这里 new 完就直接 build，整条订阅/节点池的
        // 下载都绕过了代理去直连 GitHub —— 国内基本不通，表现是「节点池更新失败：
        // Read timed out」和「订阅拉取失败：failed to connect ... from 192.168.x.x」。
        // 这两个功能恰恰是最需要代理的：它们下载的地址就在墙外。
        if (route != null) route.applyTo(oneBuilder);
        OkHttpClient one = oneBuilder.build();
        Response response = one.newCall(request).execute();
        try {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " @ " + hostOfUrl(url));
            ResponseBody body = response.body();
            return body == null ? new byte[0] : body.bytes();
        } finally {
            response.close();
            one.dispatcher().executorService().shutdown();
            one.connectionPool().evictAll();
        }
    }

    public static byte[] getRaw(String url, java.util.Map<String, String> headers, int timeoutSeconds) throws Exception {
        Request.Builder builder = new Request.Builder().url(url).header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("GET " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url);
        }
    }

    /**
     * 带自定义头的朴素 GET，**顺手把响应的 `Set-Cookie` 收进 `cookieOut`**。
     *
     * 与三参那个重载行为完全一致（同样的代理、同样的 h2→h1 降级），只是多回一份
     * cookie。给「需要先拿一个站点下发的标记，再带着它重新请求」的适配器用。
     */
    public static byte[] getRaw(String url, java.util.Map<String, String> headers, int timeoutSeconds,
                                java.util.Map<String, String> cookieOut) throws Exception {
        Request.Builder builder = new Request.Builder().url(url).header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("GET " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url, cookieOut);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url, cookieOut);
        }
    }

    /**
     * 表单体的朴素 POST。
     *
     * 播放层的解析接口要带 Video-Parse-Sign / -Time / -Uuid / -Version 这几个自定义头，
     * 而直连会被浏览器的 CORS 预检拦下 —— 对面只回 access-control-allow-origin，
     * 没有 access-control-allow-headers，自定义头过不去。OkHttp 不受同源策略管，走这里。
     */
    public static byte[] postFormRaw(String url, String formBody,
                                     java.util.Map<String, String> headers, int timeoutSeconds) throws Exception {
        okhttp3.RequestBody payload = okhttp3.RequestBody.create(
                formBody == null ? "" : formBody,
                okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"));
        Request.Builder builder = new Request.Builder().url(url).post(payload).header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("POST " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url);
        }
    }

    /**
     * 表单体的朴素 POST，**外加把响应的 `Set-Cookie` 收进 `cookieOut`**。
     *
     * 与四参那个重载行为完全一致（同样的表单 MediaType、同样的 h2→h1 降级），
     * 只多回一份 cookie。给「POST 一次就能拿到游客标记」的站用 —— 91porn 那种
     * 首次不带标记的 GET 会回一份<strong>随机内容</strong>，白白多跑一次请求；
     * 换成 POST 拿标记就不用为了 headers 再取一遍正文。
     */
    public static byte[] postFormRaw(String url, String formBody,
                                     java.util.Map<String, String> headers, int timeoutSeconds,
                                     java.util.Map<String, String> cookieOut) throws Exception {
        return postFormRaw(url, formBody, headers, timeoutSeconds, cookieOut, null);
    }

    /**
     * 表单体的朴素 POST，**带上当前源配置**（{@code profile}），外加 cookie 回传。
     *
     * <p>{@code profile} 只在一处起作用：请求命中「源声明要走代理」而手里的 client
     * 没有代理时，兜底建 client 需要它才能挂上 {@link AiiHttpDns}（DoH + 配置里钉死的
     * 地址）。传 null 不是错误，只是退回普通 DNS —— 但 spider 的 POST 全部在这条路上
     * （OpenList 三个接口都是 POST），所以源声明的东西必须能传进来。
     *
     * <p>不另加「只有 profile 没有 cookieOut」的重载：cookie 是可选的，多一个 null
     * 参数比多一个重载好读，也避免调用点因为参数个数不同而悄悄选了另一条路。
     */
    public static byte[] postFormRaw(String url, String formBody,
                                     java.util.Map<String, String> headers, int timeoutSeconds,
                                     java.util.Map<String, String> cookieOut, SiteProfile profile) throws Exception {
        okhttp3.RequestBody payload = okhttp3.RequestBody.create(
                formBody == null ? "" : formBody,
                okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"));
        Request.Builder builder = new Request.Builder().url(url).post(payload).header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("POST " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url, cookieOut, profile);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url, cookieOut, profile);
        }
    }

    /**
     * 带 Range 的朴素 GET。用来验直链：音频动辄几十 MB，只要前 1KB 判断格式。
     */
    public static byte[] getRawRange(String url, long from, long to,
                                     java.util.Map<String, String> headers, int timeoutSeconds) throws Exception {
        Request.Builder builder = new Request.Builder().url(url)
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=" + from + "-" + to);
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("GET(range) " + hostOfUrl(url) + pathOfUrl(url));
        return rawWith(plainHttp1Client(timeoutSeconds), builder.build(), url);
    }

    /**
     * JSON 体的朴素 POST。
     *
     * AList / OpenList 那套接口（`/api/fs/list`、`/api/fs/get`、`/api/fs/search`）
     * 只收 `application/json` —— 表单体过去它会当成空对象，返回「path 不能为空」。
     */
    public static byte[] postJsonRaw(String url, String json,
                                     java.util.Map<String, String> headers, int timeoutSeconds) throws Exception {
        okhttp3.RequestBody payload = okhttp3.RequestBody.create(
                json == null ? "{}" : json,
                okhttp3.MediaType.parse("application/json; charset=UTF-8"));
        Request.Builder builder = new Request.Builder().url(url).post(payload)
                .header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("POST " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url);
        }
    }

    /**
     * JSON 体的朴素 POST，**外加把响应的 `Set-Cookie` 收进 `cookieOut`**。
     *
     * 与四参那个重载行为完全一致（同样的 `application/json` MediaType、同样的
     * h2→h1 降级），只多回一份 cookie。AList/OpenList 的接口本身不带 cookie，
     * 但同一条链路上偶尔会有前置的鉴权/限流网关往里塞标记，统一走这条口径
     * 能给 spider 侧省掉「POST 拿不到 cookie」这个特例。
     */
    public static byte[] postJsonRaw(String url, String json,
                                     java.util.Map<String, String> headers, int timeoutSeconds,
                                     java.util.Map<String, String> cookieOut) throws Exception {
        return postJsonRaw(url, json, headers, timeoutSeconds, cookieOut, null);
    }

    /**
     * JSON 体的朴素 POST，**带上当前源配置**（{@code profile}），外加 cookie 回传。
     *
     * <p>理由和 {@code postFormRaw} 的六参版本一样：{@code profile} 决定兜底 client
     * 挂哪个 DNS。OpenList 的 {@code /api/fs/list}、{@code /api/fs/get}、
     * {@code /api/fs/search} 全是这条路径，而这些站的地址恰恰可能需要 DoH 解析。
     */
    public static byte[] postJsonRaw(String url, String json,
                                     java.util.Map<String, String> headers, int timeoutSeconds,
                                     java.util.Map<String, String> cookieOut, SiteProfile profile) throws Exception {
        okhttp3.RequestBody payload = okhttp3.RequestBody.create(
                json == null ? "{}" : json,
                okhttp3.MediaType.parse("application/json; charset=UTF-8"));
        Request.Builder builder = new Request.Builder().url(url).post(payload)
                .header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("POST " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url, cookieOut, profile);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url, cookieOut, profile);
        }
    }

    // ── 客户端工厂（唯一一处）────────────────────────────────────────

    /**
     * 客户端缓存。key 里带齐所有会影响实例行为的参数：
     * <b>协议版本 + 超时 + 路由 + DNS 实现</b>。
     *
     * <p>以前是四个字段四份判断（{@code client} / {@code http1Client} /
     * {@code plainClient} / {@code plainHttp1}，各自配一个 {@code appliedXxx}
     * 记录「上次是按什么参数建的」）。四份代码干同一件事，而且各自漏过不同的边：
     * plainClient 忘了比超时（不同超时的请求复用同一个实例，传进来的
     * timeoutSeconds 等于没生效），http1Client 忘了比 DNS。
     *
     * <p>改成「参数算 key」之后，这类漏判在结构上不存在 —— 参数不同，key 就不同，
     * 一定是新实例。
     */
    private static final java.util.Map<String, OkHttpClient> CLIENTS =
            new java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>();

    /**
     * 建一个客户端。
     *
     * @param http1     只谈 HTTP/1.1（h2 被中间设备重置时的兜底）
     * @param dns       null 表示用 OkHttp 默认解析；和别的实现不共用缓存
     * @param route     null 表示直连
     */
    private static OkHttpClient clientFor(boolean http1, int connectSeconds, int readSeconds,
                                          Dns dns, ProxyRoute route) {
        String key = (http1 ? "h1" : "h2")
                + "|" + connectSeconds + "/" + readSeconds
                + "|" + (route == null ? "direct" : route.key())
                // DNS 身份要带全：SmartDns 按 profile 的 fingerprint 区分，
                // 只记类名的话不同源的钉死 hosts / DoH 配置会互相串。
                + "|" + (dns == null ? "system" : AiiHttpDns.identity(dns));
        OkHttpClient hit = CLIENTS.get(key);
        if (hit != null) return hit;

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(connectSeconds, TimeUnit.SECONDS)
                .readTimeout(readSeconds, TimeUnit.SECONDS);
        if (http1) builder.protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1));
        if (dns != null) builder.dns(dns);
        if (route != null) {
            route.applyTo(builder);
            log("走代理 " + route.describe());
        }
        // 把重定向链记下来。OkHttp 撞到 20 跳上限时抛的是
        // 「Too many follow-up requests: 21」—— 一句只有数字、没有地址的话，
        // 线上 74 条全是这个形态，翻数据库也看不出绕去了哪。
        //
        // 这个拦截器只做观测、不改行为：记下每一跳的地址，撞环时把整条链
        // 塞进异常消息。**上限保持 OkHttp 默认的 20**——真正的环多半是站点
        // 自己的配置问题，放宽只会让用户多等，看不出来才是要修的。
        builder.addInterceptor(new okhttp3.Interceptor() {
            @Override
            public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain) throws java.io.IOException {
                okhttp3.Response response = chain.proceed(chain.request());
                if (response.isRedirect()) {
                    String from = chain.request().url().toString();
                    String to = response.header("Location", "");
                    String hop = from + " → " + to;
                    java.util.ArrayList<String> hops = REDIRECT_CHAIN.get();
                    hops.add(hop);
                    if (hops.size() > 24) hops.remove(0);
                    log("重定向 " + hop);
                }
                return response;
            }
        });
        OkHttpClient built = builder.build();
        CLIENTS.put(key, built);
        return built;
    }

    /**
     * 最近这条请求链上的重定向，只用来在撞环时补一句可读的说明。
     *
     * 用 ThreadLocal 而不是全局：并发请求各自成链，混在一起就分不清谁绕谁。
     * 进出配对由 {@link #describeRedirects} 负责清理。
     */
    private static final ThreadLocal<java.util.ArrayList<String>> REDIRECT_CHAIN =
            new ThreadLocal<java.util.ArrayList<String>>() {
                @Override
                protected java.util.ArrayList<String> initialValue() {
                    return new java.util.ArrayList<String>();
                }
            };

    /**
     * 把「绕了哪几跳」写进人话。没绕就返回空串。
     *
     * OkHttp 的原话 `Too many follow-up requests: 21` 只说数字，运维看到它
     * 既不知道绕去哪、也不知道是不是自己发错了 Host。补一句能直接看见。
     */
    // ── 只给单测用的两个口子 ───────────────────────────────────────────
    // 重定向链是 ThreadLocal，单测里要能造链、也能清干净。

    static void clearRedirectsForTest() {
        REDIRECT_CHAIN.get().clear();
    }

    static void noteRedirectForTest(String from, String to) {
        REDIRECT_CHAIN.get().add(from + " → " + to);
    }

    static String describeRedirects() {
        java.util.ArrayList<String> chain = REDIRECT_CHAIN.get();
        try {
            if (chain == null || chain.isEmpty()) return "";
            int n = Math.min(chain.size(), 3);
            StringBuilder sb = new StringBuilder("（重定向环：");
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append("；");
                sb.append(chain.get(i));
            }
            if (chain.size() > n) sb.append("；…共 ").append(chain.size()).append(" 跳");
            return sb.append("）").toString();
        } finally {
            if (chain != null) chain.clear();
        }
    }

    /** 主客户端：带 AiiHttpDns（DoH + 配置钉死的地址）。 */
    private static OkHttpClient client(SiteProfile profile) {
        return clientFor(false, CONNECT_TIMEOUT, READ_TIMEOUT, AiiHttpDns.dns(profile), effectiveRoute());
    }

    /** 主客户端的 HTTP/1.1 变体。 */
    private static OkHttpClient http1Client(SiteProfile profile) {
        return clientFor(true, CONNECT_TIMEOUT, READ_TIMEOUT, AiiHttpDns.dns(profile), effectiveRoute());
    }

    /** 朴素客户端：指定超时、不带 AiiHttpDns。 */
    private static OkHttpClient plainClient(int timeoutSeconds) {
        return clientFor(false, timeoutSeconds, timeoutSeconds, null, effectiveRoute());
    }

    /** 朴素客户端的 HTTP/1.1 变体。 */
    private static OkHttpClient plainHttp1Client(int timeoutSeconds) {
        return clientFor(true, timeoutSeconds, timeoutSeconds, null, effectiveRoute());
    }

    /**
     * 按当前路由现拼一个 client，**照抄传进来那个的超时和 DNS**。
     *
     * 用在「路由说要代理、但手里的 client 没有代理」这唯一一种自洽性破洞上。
     * 这不是分流逻辑，是纠错：分流只该在 {@link NetRoute} 里判一次。
     */
    private static OkHttpClient proxiedClient(SiteProfile profile, OkHttpClient like) {
        Dns dns = profile != null ? AiiHttpDns.dns(profile) : like.dns();
        return clientFor(false,
                (int) (like.connectTimeoutMillis() / 1000),
                (int) (like.readTimeoutMillis() / 1000),
                dns, routeForProxyHost());
    }

    /** 冷启动时等核心就绪的上限。够核心拉起来，又不至于让用户以为卡死。 */
    private static final long PROXY_READY_WAIT_MS = 2500;

    /**
     * 给「路由说这个 host 要走代理」的请求取代理；内置核心还没起来时**等一会儿**。
     *
     * ── 这个方法是为什么存在的 ────────────────────────────────────────
     * 2026-10-19 真机：spider 在启动 0.4 秒后跑，日志是
     *
     * <pre>
     * [source] net yhdm.one → 代理
     * [http] DoH yhdm.one → 4 个地址          ← 还在做本地 DNS
     * failed to connect to yhdm.one/2001::caa0:…（被污染的 IPv6）
     * </pre>
     *
     * 两个真相：{@link NetRoute} 判的是「这个 host 该走代理」（按源声明），而建
     * 客户端用的是 {@link #effectiveRoute()}（全局设置）。启动头一两秒内置核心还没
     * 监听，{@code effectiveProxy()} 返回空串 —— 那是「还没好」，不是「不走代理」。
     * 把它们当同一件事，结果就是拿直连去撞被 DNS 污染的地址，一个请求烧掉 15 秒。
     *
     * <p>所以这条路只做一件事：模式是内置核心、代理串还是空的时候，最多等
     * {@link #PROXY_READY_WAIT_MS}；等到就用，等不到就照旧直连（比一直卡着好，
     * 而且日志里能看出是核心没起来）。
     */
    private static ProxyRoute routeForProxyHost() {
        ProxyRoute route = effectiveRoute();
        if (effectiveProxy().length() > 0) return route;
        if (!ProxyConsole.MODE_BUILTIN.equals(proxyModeOrEmpty())) return route;
        long until = System.currentTimeMillis() + PROXY_READY_WAIT_MS;
        while (System.currentTimeMillis() < until) {
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (effectiveProxy().length() > 0) {
                log("等内置核心就绪 " + (PROXY_READY_WAIT_MS - (until - System.currentTimeMillis())) + "ms");
                return effectiveRoute();
            }
        }
        log("内置核心迟迟没起来，这手先按直连发（源声明的是走代理）");
        return route;
    }

    /**
     * IPv4 优先的 DNS。
     *
     * ── 为什么需要它 ──────────────────────────────────────────────────
     * 2026-10-18 实测：这台机器（MIUI / Android 16 / 双卡）的网络上报给 App 的
     * 默认路由是 **IPv6 单栈**（`::/0`，没有 IPv4 默认路由），而系统 DNS 仍然
     * 同时返回 A 和 AAAA 记录。于是 OkHttp 拿到一个 AAAA 会去连 IPv6，本机没有
     * IPv6 出口 → 连接一直挂到超时。
     *
     * 同一时刻 `curl -4` 3 秒拿到 200，`curl -6` 立刻 000 —— curl 有 Happy
     * Eyeballs 会自动降级到 IPv4，而 OkHttp 这一版在单栈环境下不会。
     *
     * 表现是「订阅一直拉不下来，报 Read timed out」，看起来像网速问题，
     * 实际上一个字节都没传。这种问题时有时无（换 WiFi 就好了），极难复现，
     * 所以在这里钉死：**解析结果里只要有 IPv4，就只用 IPv4**。
     *
     * 只有 IPv6 的站点依然是可用的（列表为空时才退回原样），不影响纯 v6 环境。
     */
    private static final Dns IPV4_FIRST = new Dns() {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> all = Dns.SYSTEM.lookup(hostname);
            if (all == null || all.isEmpty()) return all;
            List<InetAddress> v4 = new ArrayList<InetAddress>(all.size());
            for (int i = 0; i < all.size(); i++) {
                InetAddress address = all.get(i);
                if (address instanceof Inet4Address) v4.add(address);
            }
            // 一个 IPv4 都没有（纯 IPv6 站点 / v6-only 网络）就原样返回。
            return v4.isEmpty() ? all : v4;
        }
    };

    /**
     * 「直连表命中、要现拼一个不带代理的 client」时，那个新 client 该用哪个 DNS。
     *
     * <p>两条分支（{@link #rawWith} 和 {@link #getWith}）以前各写各的，于是长出了
     * 两种毛病：
     * <ul>
     *   <li>{@code rawWith} 无条件 {@code .dns(IPV4_FIRST)} —— 把传进来 client 的
     *       {@link AiiHttpDns}（DoH + 配置里钉死的地址）盖掉了。GET/POST 全族都走这条，
     *       所以「直连表里的域名突然解析不出配置钉死的那个 IP」随时可能发生，
     *       而症状是「这个站有时候能连有时候不能」，看起来像站点抽风。</li>
     *   <li>{@code getWith} 只在 {@code instanceof AiiHttpDns} 时才保留，否则连
     *       {@code IPV4_FIRST} 都不给，等于新 client 回到 OkHttp 默认 DNS。</li>
     * </ul>
     *
     * <p>决策就一条，按优先级：
     * <ol>
     *   <li>手里的是 {@link AiiHttpDns} → <b>原样保留</b>。它已经包含 DoH、配置钉死的
     *       地址，比任何外层包装都更懂这个源。</li>
     *   <li>手里传了 {@code profile} 而 client 身上却不是 AiiHttpDns → 现建一个
     *       {@link AiiHttpDns}。这就是 POST 那条路上原来的洞：{@code plainClient} 的 DNS
     *       是 OkHttp 默认的，源声明的钉死地址从来没生效过。</li>
     *   <li>其它情况 → {@link #IPV4_FIRST}。<b>不能省</b>：IPv6 单栈网络上 OkHttp 不会
     *       Happy Eyeballs 降级，拿到 AAAA 就一路连到超时（见 IPV4_FIRST 那段实测）。</li>
     * </ol>
     *
     * <p>包级可见是为了单测：这个函数不碰网络也不改状态，输入输出都是引用比较。
     */
    static Dns dnsFor(OkHttpClient http, SiteProfile profile) {
        Dns current = http == null ? null : http.dns();
        if (AiiHttpDns.hasOwnDns(current)) return current;
        // profile 给了就按源的解析规则来 —— DoH 和 hosts 钉死都挂在这上面。
        if (profile != null) return smartDnsFor(profile);
        // 连 profile 都没有（老调用方 / 探针）才退回 IPv4 优先。不是默认值，是兜底。
        return IPV4_FIRST;
    }

    /**
     * 造一个 {@link AiiHttpDns}。**唯一目的是让包外能拿到这个类型的实例** ——
     * {@code AiiHttpDns} 是私有嵌套类，而 {@link #dnsFor} 的判据是
     * {@code instanceof AiiHttpDns}，单测不造一个真的就没法断言「保留」那条路径。
     *
     * <p>不把 {@code AiiHttpDns} 改成包级可见：它的 {@code lookup} 会走 DoH 和磁盘缓存，
     * 是个有副作用的实现类，暴露出去等于邀请别处直接 new。一个工厂方法足够。
     *
     * <p>构造本身不碰网络也不碰 SharedPreferences —— profile 只是存着，
     * 真正的解析发生在 {@code lookup} 里。
     */
    static Dns smartDnsFor(SiteProfile profile) {
        return AiiHttpDns.dns(profile);
    }

    // ── 代理的读写与解析 ──────────────────────────────────────────────

    private static SharedPreferences netStore() {
        Context context = appContext;
        return context == null ? null : context.getSharedPreferences(NET_STORE, Context.MODE_PRIVATE);
    }

    /** 当前配置的代理串，空串表示直连。 */
    public static String proxyRaw() {
        SharedPreferences store = netStore();
        if (store == null) return "";
        String raw = store.getString(PROXY_KEY, "");
        return raw == null ? "" : raw.trim();
    }

    /**
     * 让下一次请求重建全部客户端。
     *
     * 为什么不是「改代理串再重建」：内置核心启停时用户的代理串一个字都没变，
     * 变的只是生效来源。所以这里只作废缓存，让各个 client() 下次自己
     * 重新问一遍 effectiveProxy() —— 四个构造点本来就都有这层判据。
     *
     * 只置缓存字段不够的地方：OkHttp 的连接池和 TLS 会话都挂在已建好的实例上，
     * 不换实例的话旧连接会继续指向已经关掉的端口。
     */
    static void invalidateClients() {
        CLIENTS.clear();
        // 媒体代理是另一条链路，但它读的是同一个 effectiveProxy()。
        MediaProxy.invalidate();
    }

    /** 写入代理串并让客户端重建。返回 null 表示成功，否则是一句给人看的错。 */
    public static String setProxy(String value) {
        SharedPreferences store = netStore();
        if (store == null) return "存储不可用";
        String clean = value == null ? "" : value.trim();
        ProxyRoute.Parsed parsed = ProxyRoute.parse(clean);
        if (!parsed.ok()) return parsed.error;
        store.edit().putString(PROXY_KEY, clean).apply();
        invalidateClients();           // 逼下一次请求重建
        return null;
    }

    /**
     * 当前生效的 {@link java.net.Proxy}，供 {@link SiteFetchWebView} 用。
     *
     * <p><b>为什么 WebView 也要问这里</b>（2026-10-18 加 missav 时才发现的洞）：
     * 隐藏 WebView 是**另一个网络栈**，OkHttp 的 {@code proxy()} 对它一点用没有。
     * 而需要过 Cloudflare 的站点恰恰都是「国内直连不通、必须走代理」的那种
     * （missav / mrds66 / hanime1 在源市场里都标着「需代理」）。所以 WebView
     * 忘了挂代理 = 过盾那一步必然超时，而日志只会说「过盾超时」，看不出是网络不通。
     *
     * <p>返回 null 表示当前是直连，调用方就别去碰 {@code ProxyController}。
     *
     * <p>不把 {@link Route} 整个暴露出去的原因：它带账号密码，
     * 而这个方法的返回值会进 logcat 附近的调试路径。
     */
    static java.net.Proxy effectiveJavaProxy() {
        ProxyRoute route = effectiveRoute();
        return route == null ? null : route.javaProxy();
    }

    /**
     * 当前生效的代理路由。**null 表示直连。**
     *
     * 这是「代理」在这套代码里的唯一形态：字符串只在 {@link #setProxy} 进、
     * {@link #proxyRaw} 出的时候出现，中间所有消费方拿的都是这个对象。
     */
    static ProxyRoute effectiveRoute() {
        ProxyRoute.Parsed parsed = ProxyRoute.parse(effectiveProxy());
        return parsed.route;
    }

    /**
     * 把代理串挂到 builder 上。给 MediaProxy 这类「自己建客户端但该跟随同一套
     * 代理语义」的调用方用 —— 认格式和带认证的规则只该有一份实现。
     *
     * @return null 表示没代理（直连）或挂上了；非 null 是那句认不出来的原串，调用方拿去打日志
     */
    static String attachProxy(OkHttpClient.Builder builder, String proxyText) {
        ProxyRoute.Parsed parsed = ProxyRoute.parse(proxyText);
        if (parsed.direct()) return null;
        if (parsed.route == null) return proxyText;   // 认不出来，调用方拿去打日志
        parsed.route.applyTo(builder);
        return null;
    }




    /**
     * 请求头模板来自 site-default.json 的 headers，{@code {origin}} 换成<b>源自己</b>的域名。
     *
     * <p>2026-10-19 真机踩到：这里原来换的是<b>目标 URL</b> 的 origin。取封面时目标
     * 变成图床 {@code fourhoi.com}，于是 {@code Referer} 发的是 {@code https://fourhoi.com/}，
     * 而图床的防盗链只认站点域 —— 实测 {@code Referer: https://missav.live/…} → 200，
     * {@code https://fourhoi.com/} → 403，{@code Referer: /} → 403。okhttp 报的就是这个 403，
     * 看着像「被墙」，其实是自己把源发错了。
     *
     * <p>源没声明 origin（spider 源就是这样，站点域写在 js 里）时退回目标 URL 的 origin。
     */
    private static void applyHeaders(Request.Builder builder, SiteProfile profile, String url) {
        // 唯一实现在 Outbound —— 中继（播放取流）和这里共用同一份模板，
        // 免得两处判据慢慢分叉（2026-10-21 的 Pornhub 分片 403 就是这么来的）。
        Outbound.applyHeaders(builder, profile, url);
    }

    private static String hostOfUrl(String url) {
        try { return new java.net.URL(url).getHost(); } catch (Exception e) { return "(无法解析)"; }
    }

    private static String pathOfUrl(String url) {
        try { return new java.net.URL(url).getPath(); } catch (Exception e) { return url; }
    }



    /**
     * 包内可见 —— 适配器也要往这条流里写（比如抓取失败、采集站匹配不上，
     * 都要留痕）。用同一个 tag 是为了在 logcat 里能按时间顺序对照
     * 「适配器说了什么」和「HTTP 发了什么」，分成两个 tag 会乱。
     */
    static void log(String message) {
        try { android.util.Log.d("AIIvideo", "[http] " + message); } catch (Throwable ignored) {}
    }
}
