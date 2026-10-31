package com.huangguo.mobile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import okhttp3.ResponseBody;

/**
 * 把 libVLC 的取流请求接到我们自己的 OkHttp 上，走内置代理。
 *
 * ── 为什么需要这个 ────────────────────────────────────────────────────
 * 内置核心起来的 SOCKS5 只在 Java 侧对 OkHttp 生效。libVLC 是原生栈，
 * 它的取流不走 OkHttp，所以核心开着时视频流仍然是直连的 —— 表现是
 * 「列表能刷出来、点开播不了」，因为接口走了代理而 CDN 没有。
 *
 * 原本以为加个 media option 就完事，实测两条都不行（2026-10-18 真机）：
 *
 *   · `:socks=host:port`      字符串在 .so 里确实有，但挂的是 Live555 的
 *                             RTSP 栈（SocksNegotiate 上下文全是
 *                             OnDemandServerMediaSubsession / RTPSink）。
 *                             播放时 SOCKS 端口零连接，视频直连出画。
 *   · `:http-proxy=host:port` 同样零连接。用假代理（只记日志不转发）验过，
 *                             连上去的请求一个都没有。
 *   · 全局 `--socks=`         放在 new LibVLC(options) 里，一样无效。
 *
 * 所以 VLC 本身没法被配置成走代理。能让它走代理的唯一办法是**换地址**：
 * 给它一个本机 HTTP 地址，那个地址背后是我们的 OkHttp。
 *
 * ── 怎么接 ────────────────────────────────────────────────────────────
 *
 *   VLC ──HTTP──> 127.0.0.1:<port>/s?u=<base64 的原始地址>
 *                        │
 *                        └─ OkHttp（带 effectiveProxy）──> 原站/CDN
 *
 * 选这个形状而不是「透传原始路径」，是因为 VLC 会自己拼相对地址（m3u8 的
 * 分片就是相对主播放列表解析的）。把原始 URL 整个编码进 query，VLC 无论
 * 怎么拼都只会拼出 `/s?u=...` 这一种形状，我们不用维护任何映射表。
 *
 * ── Range 必须原样透传 ────────────────────────────────────────────────
 * 媒体栈靠 Range 做 seek 和拖进度。抽掉的话要么整集从头读，要么直接判
 * 「不支持」。这里上下行都原样搬：请求头 Range 转给 OkHttp，响应的
 * 206 / Content-Range / Content-Length 原样写回。
 *
 * 实测（2026-10-18）：VLC 播本地 HTTP 地址时会带 `Range: bytes=0-` 来请求，
 * 并且能正常出画（decoder output 1280x720）。所以这条路是通的。
 */
final class VlcRelay {

    private static final String TAG_PREFIX = "[relay] ";
    private static final String PATH = "/s";

    /** 单条连接的读写缓冲。媒体流是顺序大块，64KB 比默认 8KB 少很多系统调用。 */
    private static final int BUF = 65536;

    /** 只在没有更多数据可写时用的空闲超时；活着的流不会触发。 */
    private static final int IDLE_MS = 30000;

    private static volatile VlcRelay instance;

    private final ServerSocket server;
    private final int port;
    private volatile boolean closed;
    private final AtomicReference<Thread> acceptor = new AtomicReference<Thread>();

    private VlcRelay(ServerSocket server, int port) {
        this.server = server;
        this.port = port;
    }

    /**
     * 起一个只监听回环的中继。已经起来就直接复用。
     *
     * 绑 127.0.0.1 而不是通配地址：这个是**无鉴权的开放代理**，绑到 0.0.0.0
     * 等于同网段任何人拿它当跳板，而且流量会从用户的代理节点出去。
     */
    static VlcRelay ensure() throws IOException {
        VlcRelay local = instance;
        if (local != null && !local.closed) return local;
        synchronized (VlcRelay.class) {
            if (instance != null && !instance.closed) return instance;
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            VlcRelay relay = new VlcRelay(socket, socket.getLocalPort());
            relay.start();
            instance = relay;
            log("中继已启动 127.0.0.1:" + relay.port);
            return relay;
        }
    }

    static void shutdown() {
        VlcRelay local = instance;
        if (local == null) return;
        instance = null;
        local.closed = true;
        try {
            local.server.close();
        } catch (Throwable ignored) {
        }
        log("中继已停止");
    }

    int port() {
        return closed ? 0 : port;
    }

    /**
     * 这个地址是不是「伪装后缀」—— 视频流被挂成 .jpg / .png / .woff2 之类。
     *
     * <p>AGE动漫 的解析站 2026-10-19 实测返回的是：
     *
     * <pre>
     *   https://jx.wuzhoupai.com:8443/playurl/&lt;hash&gt;…&amp;media_type=jpg
     * </pre>
     *
     * 那个地址里装的是 **MPEG-TS 视频流**，但后缀和 {@code media_type} 参数都在说
     * 「我是图片」。VLC 按 URL 后缀选 demuxer，于是
     * {@code libvlc demux: Failed to create demuxer 0x0 Unknown} —— 黑屏，
     * 而且它不会报「格式不对」，只会一遍遍重试。
     *
     * <p>这类地址**必须绕中继**：中继是自己判内容类型的（见 serveFromWebView
     * 里那句 `isPlaylist(original, data)`），不会照抄服务端的 Content-Type。
     * 直连给 VLC 就一定被后缀骗。
     */
    static boolean disguised(String url) {
        if (url == null) return false;
        String s = url.toLowerCase(java.util.Locale.ROOT);
        int q = s.indexOf('?');
        String path = q < 0 ? s : s.substring(0, q);
        // 后缀名单取 OpenListAdapter 那套伪装扩展名的同类（视频被改名成静态资源）
        String[] ext = { ".jpg", ".jpeg", ".png", ".gif", ".webp", ".woff2", ".woff", ".css", ".js" };
        for (int i = 0; i < ext.length; i++) {
            if (path.endsWith(ext[i])) return true;
        }
        // 查询串里自带 media_type= 的，也是同一个套路
        return q >= 0 && s.indexOf("media_type=") > 0;
    }

    /**
     * 把原始播放地址包成中继地址。中继没起来时返回 null，调用方退回原始地址
     * （直连，和没有内置核心时的行为一致）。
     *
     * 判断根据是 effectiveProxy() 而不是「中继在不在跑」：代理串为空就说明
     * 核心没开（或者是直连/外部地址模式），这时候绕中继纯属多一跳。
     * 这样即使某处漏了 shutdown()，核心一停中继自然就没人用了。
     */
    static String wrap(String originalUrl) {
        if (originalUrl == null || originalUrl.length() == 0) return null;
        // 本地回环地址不包一层：一是没意义，二是会绕出环来。
        if (originalUrl.startsWith("http://127.0.0.1:") || originalUrl.startsWith("http://localhost")) {
            return null;
        }

        // ── 伪装后缀：不管有没有代理都要绕中继 ────────────────────────────
        //
        // 这一条比「走不走代理」更刚性：它是**正确性**问题，不是路径优化。
        // VLC 见到 .jpg 就按图片解，直连过去必然 demux 失败 —— 而绕中继就修好了，
        // 因为中继自己判类型（#EXTM3U 开头当播放列表，其余按 MPEG-TS）。
        //
        // 2026-10-19 真机就是这么卡住的：AGE动漫 播到一半永远黑屏，日志里
        // Failed to create demuxer，而解析出来的地址尾巴是 &media_type=jpg。
        boolean disguise = disguised(originalUrl);
        if (!disguise && AiiHttp.effectiveProxy().length() == 0) return null;

        // 分流：直连表里的域名连中继都不用绕 —— VLC 自己拉原始地址就是直连，
        // 绕一跳本地 OkHttp 纯属白费（而且 relayClient 如果不摘代理，反而把
        // 直连域名又塞回那条节点，表就白挂了）。
        // 路由决策只问 NetRoute 一处。以前这里问的是 DirectHosts（白名单），
        // 而源的 net.proxy 声明改由 NetRoute 接管 —— 两处各问一个表就会漂。
        // 语义只有一条：**这条地址明确该走代理才绕中继**，其余直连。
        // 伪装后缀是例外：它绕中继是为了改类型判定，不是为了代理。
        if (!disguise) {
            // 播放列表一律绕中继 —— 中继要拿到它才能剔广告、才能改写相对地址。
            // 分片仍然按原路由走（见 rewriteM3u8Smart）：直连源的分片一条都不进中继，
            // 否则 1080p 每条分片都多绕一跳本机 OkHttp。
            //
            // 注意：绕进来 ≠ 走代理。中继里那条请求用哪条出口由 relayViaProxy()
            // 单独判（2026-10-21 的回归就是这里只判了「绕不绕」、没判「走不走代理」，
            // 直连 CDN 被塞回境外节点，AGE 全挂）。
            if (!isPlaylistUrl(originalUrl)) {
                try {
                    String host = new java.net.URI(originalUrl).getHost();
                    if (!NetRoute.mustProxy(host)) return null;
                } catch (Exception ignored) {}
            }
        }
        try {
            VlcRelay relay = ensure();
            if (relay.port() <= 0) return null;
            return "http://127.0.0.1:" + relay.port() + PATH
                    + "?u=" + encode(originalUrl);
        } catch (Throwable e) {
            log("包地址失败，退回直连：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    /**
     * 当前源配置，播放器打开时由 {@link VlcPlayerActivity} 递进来。
     *
     * <p>中继转发的是「取流」这一类请求，它们和 AiiHttp 里那些接口请求是同一个
     * 网站的，防盗链规则也一样 —— 但中继只转 VLC 给的 header，拿不到源声明的
     * 那套模板。真机症状（2026-10-21，Pornhub）：master / variant 200，
     * 分片 ts 一律 403，因为那把 CDN 的防盗链只认 Referer，而 VLC 不带。
     */
    private static volatile SiteProfile sourceProfile;

    static void setSourceProfile(SiteProfile profile) {
        sourceProfile = profile;
    }

    /**
     * 中继里这条地址该不该带代理 —— 跟 {@link #wrap} 用同一个判据（NetRoute）。
     *
     * <p>两条路原来是分开的：wrap 决定「绕不绕中继」（mustProxy 才绕），
     * 中继内部固定「跟着全局代理」。2.0.16 起播放列表一律要绕中继（剔广告、
     * 改相对地址都得先拿到它），wrap 那道闸就不再看 mustProxy 了 —— 于是判据
     * 必须在这里补上，否则直连域名会被塞回代理节点。
     *
     * <p>解析不出 host 时按「带代理」处理：宁可多绕一跳，也别把本该走代理的
     * 地址放成直连 —— 后者是连接超时，比 403 还难解释。
     */
    private static boolean relayViaProxy(String url) {
        return Outbound.viaProxy(url);
    }

    // ── base64url，不带 padding ───────────────────────────────────────
    //
    // 用 base64url 而不是 URLEncoder：后者把每个非 ASCII 字节展开成 %XX（三倍长），
    // 而且中文路径/%2B 这类会来回转义。base64url 字母表里没有 + / =，
    // 放进 query 不需要再编码，VLC 自己拼相对地址也不会踩到。

    static String encode(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String s) {
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    // ── 接客 ──────────────────────────────────────────────────────────

    private void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "vlc-relay");
        t.setDaemon(true);
        acceptor.set(t);
        t.start();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client = null;
            try {
                client = server.accept();
                handle(client);
            } catch (Throwable e) {
                if (closed) return;
                // 单条连接出错不该把整个中继带走。
                closeQuietly(client);
            }
        }
    }

    private void handle(final Socket client) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                serve(client);
            }
        }, "vlc-relay-conn");
        t.setDaemon(true);
        t.start();
    }

    private void serve(Socket client) {
        Response response = null;
        String original = null;
        try {
            client.setSoTimeout(IDLE_MS);
            client.setTcpNoDelay(true);

            InputStream in = new BufferedInputStream(client.getInputStream(), BUF);
            OutputStream out = new BufferedOutputStream(client.getOutputStream(), BUF);

            String requestLine = readLine(in);
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeSimple(out, 400, "Bad Request");
                return;
            }
            String method = parts[0];
            String target = parts[1];

            // 头部要读完，否则 next request 会错位；同时把 Range 留下来。
            Map<String, String> headers = new LinkedHashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).trim());
                }
            }

            if (!target.startsWith(PATH + "?")) {
                writeSimple(out, 404, "Not Found");
                return;
            }

            String query = target.substring(PATH.length() + 1);
            String encoded = param(query, "u");
            original = encoded == null ? null : decode(encoded);
            if (original == null || original.length() == 0) {
                writeSimple(out, 400, "Bad target");
                return;
            }

            // 这一条该不该走代理：跟 wrap() 里同一个判据（NetRoute），
            // 不能再无条件跟随 effectiveProxy() —— 播放列表现在即使该直连也
            // 绕中继，直连域被塞回代理节点就会被 CDN 按地区拒（见 MediaProxy
            // 里 relayClient(boolean) 的注释）。
            final boolean viaProxy = relayViaProxy(original);

            if ("HEAD".equalsIgnoreCase(method)) {
                // VLC 有时先探一次。发一个不带 body 的 GET 拿状态和长度。
                Request.Builder probe = new Request.Builder().url(original).head();
                applyHeaders(probe, headers, false, original);
                response = MediaProxy.relayClient(viaProxy).newCall(probe.build()).execute();
                writeHead(out, response);
                return;
            }

            // ── 过盾域名改走 WebView ──────────────────────────────────
            //
            // 2026-10-18 真机：surrit.com 的 playlist.m3u8 用 OkHttp（走代理、
            // 带 Referer、强制 HTTP/1.1）一律 403 —— Cloudflare 的
            // "Attention Required"，和 Mac 上 curl 的响应一模一样。而同一个
            // 地址在站点 WebView 里 fetch 是 200，跨源、不带 cookie 也是 200。
            // 差的是浏览器栈那一层（TLS 指纹/UA），OkHttp 改不了。
            //
            // 所以这一族域名不再自己发请求，交给已经过了盾的那个 WebView。
            if (SiteFetchWebView.isShielded(original)) {
                // WebView 取不到时不要直接 502 —— 回落 okhttp 再试一次。
                // 2026-10-19 真机：surrit.com 的 m3u8 在 WebView 里跨源 fetch 一律
                // TypeError（没有 ACAO 头），而同一地址 okhttp 带上源声明的
                // UA + Referer 是 200（Referer 那个 bug 修好之后才成立）。
                if (serveFromWebView(out, original)) return;
            }

            Request.Builder builder = new Request.Builder().url(original).get();
            // 播放列表不要 Range：上游会回 206，body 只有一段，改写就无从谈起
            // （真机实测 v4.ppqrrs.com 的 index.m3u8 被 VLC 带 Range 请求，回 206，
            //  于是剔广告那段整个被跳过 —— 列表原样透传）。
            applyHeaders(builder, headers, isPlaylistUrl(original) || disguised(original), original);
            response = MediaProxy.relayClient(viaProxy).newCall(builder.build()).execute();
            noteUpstream(response.code());
            log("上游 " + response.code() + " " + response.protocol()
                    + (viaProxy ? " 经代理 " : " 直连 ") + shortUrl(original));

            // ── 播放列表必须改写，不管走哪条路 ─────────────────────────
            //
            // 2026-10-18 接 yhdm 踩到：这条 OkHttp 路原来只透传。master m3u8
            // 里的码率子列表是相对路径（/20261018/…/1754kb/hls/index.m3u8），
            // VLC 相对「它拿到的那个地址」解析 —— 而那是
            // http://127.0.0.1:port/s?u=<base64>，相对解析丢掉 query，
            // 变成 http://127.0.0.1:port/20261018/…，中继只认 /s?u=，回 404。
            // VLC 日志是「libvlc demux: cannot peek」，和上游 200 并排出现 ——
            // 上游 200 是最坏的那种坏法：日志看着没问题，播放就是起不来。
            // serveFromWebView 那条路早就有改写（见它上面那段注释），
            // 这里补上同一条。判定用 Content-Type / 扩展名，不读 body
            // （透传要保持流式，大分片不能整块进内存）。
            String ctype = response.header("Content-Type", "");
            String pathOnly = original.toLowerCase(Locale.ROOT);
            int qmark = pathOnly.indexOf('?');
            if (qmark > 0) pathOnly = pathOnly.substring(0, qmark);
            boolean playlistByUrl = pathOnly.endsWith(".m3u8")
                    || ctype.contains("mpegurl");

            // ── 伪装后缀：读内容自己判，不听服务端也不听扩展名 ─────────────
            //
            // AGE动漫 的解析站给回来的地址是 …&media_type=jpg，里面装的是
            // MPEG-TS 视频流。上游的 Content-Type 多半也是 image/jpeg ——
            // 服务端和扩展名**两个都在骗人**，唯一靠谱的证据是前几个字节。
            //
            // 判据只用前 1KB：#EXTM3U 开头 = 播放列表（小，读全量改写）；
            // 以 0x47 开头（TS 同步字节）= MPEG-TS；都不是就按二进制透传。
            // 不能整块读进内存 —— 这是视频流，可能是几百 MB。
            boolean disguise = disguised(original);
            if (disguise && response.code() == 200 && response.body() != null) {
                BufferedSource peek = response.body().source();
                peek.request(1024);
                byte[] head = peek.peek().readByteArray();
                boolean looksLikePlaylist = startsWith(head, "#EXTM3U");
                if (looksLikePlaylist) {
                    byte[] data = response.body().bytes();
                    String text = new String(data, StandardCharsets.UTF_8);
                    String rewritten = rewritePlaylist(text, original);
                    StringBuilder h2 = new StringBuilder(160);
                    h2.append("HTTP/1.1 200 OK\r\n");
                    h2.append("Content-Type: application/vnd.apple.mpegurl\r\n");
                    h2.append("Content-Length: ").append(rewritten.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
                    h2.append("Accept-Ranges: none\r\n");
                    h2.append("Connection: close\r\n\r\n");
                    out.write(h2.toString().getBytes(StandardCharsets.ISO_8859_1));
                    out.write(rewritten.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    log("伪装播放列表已改写 " + shortUrl(original));
                    return;
                }
                boolean isTs = head.length > 0 && (head[0] & 0xFF) == 0x47;
                writeHeadAs(out, response, isTs ? "video/mp2t" : "application/octet-stream");
                // **边读边写**，不能 readByteArray() 整块拿。
                // peek 不改读指针，所以已经 peek 过的 1KB 还在 source 里，
                // 从头顺序搬运就是完整内容。整块读的话，一部 1080p 的片子
                // 几百 MB 全进堆 —— 而这条路是流式播放，本来一秒只要几十 KB。
                byte[] buf = new byte[16 * 1024];
                int n;
                long moved = 0;
                while ((n = peek.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    moved += n;
                }
                out.flush();
                log("伪装流按 " + (isTs ? "video/mp2t" : "octet-stream") + " 透传 "
                        + shortUrl(original) + " " + (moved / 1024) + "KB（上游声称 " + ctype + "）");
                return;
            }

            if (playlistByUrl && response.code() == 200) {
                // 播放列表都是小文本（master 几百字节、媒体列表几十 KB），
                // 整块读进来改写再写回，不走流式。
                byte[] data = response.body() == null ? new byte[0] : response.body().bytes();
                String text = new String(data, StandardCharsets.UTF_8);
                String rewritten = rewritePlaylist(text, original);
                String type = "application/vnd.apple.mpegurl";
                StringBuilder head = new StringBuilder(160);
                head.append("HTTP/1.1 200 OK\r\n");
                head.append("Content-Type: ").append(type).append("\r\n");
                // Content-Length 必须按 UTF-8 字节数给，下面 body 就是按字节写的；
                // 用 length()（字符数）遇到非 ASCII 会对不上，客户端按头截断。
                head.append("Content-Length: ").append(rewritten.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
                head.append("Accept-Ranges: none\r\n");
                head.append("Connection: close\r\n\r\n");
                out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.write(rewritten.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            writeHead(out, response);

            ResponseBody body = response.body();
            if (body != null) {
                InputStream src = body.byteStream();
                byte[] buf = new byte[BUF];
                int n;
                while ((n = src.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    // 不每块 flush：BufferedOutputStream 满 64KB 自己吐，
                    // 每块 flush 会把吞吐砍一半。VLC 是流式读，能自己等着。
                }
                out.flush();
            }
        } catch (Throwable e) {
            // 用户播完退出、拖进度、VLC 主动断连都会走到这里，属于正常。
            // 但取流本身挂掉也走这里，两者得能分开 —— 所以带上正在取的那个地址
            // 和异常全名（ConnectionReset / SocketTimeout / SSLHandshake 各指向
            // 完全不同的原因：上游掐了 / 代理不通 / Cloudflare 拦了）。
            log("连接结束：" + e.getClass().getName()
                    + (original == null ? "" : " @" + shortUrl(original))
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable ignored) {
                }
            }
            closeQuietly(client);
        }
    }

    /**
     * 借 WebView 的栈取一份回来，整块写给 VLC。
     *
     * <p><b>为什么播放列表必须改写</b>：surrit 的 master 里子播放列表是相对路径
     * （{@code 720p/video.m3u8}），子列表里 1147 个分片也是相对路径
     * （{@code video0.jpeg}）。VLC 会相对<b>它拿到的那个地址</b>去解析，而那个
     * 地址是 {@code http://127.0.0.1:port/s?u=<base64>} —— 相对解析出来是
     * {@code http://127.0.0.1:port/720p/video.m3u8}，query 丢失，中继只会回
     * 404。所以每一行 URI 都得换成「绝对地址再包一层中继」的形式。
     *
     * <p>不换 Content-Type 会踩另一个坑：分片在服务器上标的是
     * {@code image/jpeg}（伪装成图片躲审查）。VLC 的 HLS 解复用器按类型分流，
     * 照抄 {@code image/jpeg} 它会去当图片处理。所以自己判：文本里以
     * {@code #EXTM3U} 开头就是播放列表，其余按 MPEG-TS 给。
     */
    private boolean serveFromWebView(OutputStream out, String original) throws IOException {
        byte[] data;
        try {
            data = SiteFetchWebView.fetchBytes(original);
        } catch (Throwable e) {
            // 不写 502：调用方还有 okhttp 那条路。真正两条都挂了才回错。
            log("WebView 取流失败，回退 okhttp @" + shortUrl(original) + " "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }

        boolean playlist = isPlaylist(original, data);
        byte[] body = data;
        String type;
        if (playlist) {
            String text = new String(data, StandardCharsets.UTF_8);
            body = rewritePlaylist(text, original).getBytes(StandardCharsets.UTF_8);
            type = "application/vnd.apple.mpegurl";
        } else {
            type = "video/mp2t";
        }

        StringBuilder head = new StringBuilder(160);
        head.append("HTTP/1.1 200 OK\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Accept-Ranges: none\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
        return true;
    }

    private static boolean isPlaylist(String url, byte[] data) {
        if (data.length >= 7
                && data[0] == '#' && data[1] == 'E' && data[2] == 'X' && data[3] == 'T') {
            return true;
        }
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        return u.endsWith(".m3u8");
    }

    /**
     * 把播放列表里每一行 URI 换成中继自己的绝对地址。
     *
     * <p>注释行（{@code #} 开头）原样留着 —— 包括 {@code #EXT-X-TOKEN}，
     * VLC 不认识的标签会忽略，改了反而可能让分片对不上。
     */
    private String rewritePlaylist(String text, String baseAbs) {
        String prefix = "http://127.0.0.1:" + port + PATH + "?u=";
        String[] ads = stripAdRuns(text, baseAbs);
        if (!"0".equals(ads[1])) {
            log("剔掉广告分段 " + ads[1] + " 段共 " + ads[2] + " 秒 @" + shortUrl(baseAbs)
                    + "｜" + ads[3]);
        } else {
            log("播放列表结构 @" + shortUrl(baseAbs) + "｜" + ads[3]);
        }
        String[] out = rewriteM3u8Smart(ads[0], baseAbs, prefix);
        log("播放列表改写 " + out[1] + " 条 @" + shortUrl(baseAbs));
        return out[0];
    }

    /**
     * 播放列表改写的纯函数版本（{@code [改写后文本, 改写条数]}）。
     *
     * <p>抽成静态是为了能被单测直接钉住：这段逻辑的输入输出完全由参数决定，
     * 但一旦写错，症状是「能开播但拖进度/切码率就断」—— 在真机上极难二分。
     *
     * @param prefix 每条 URI 要拼的前缀，形如 {@code http://127.0.0.1:PORT/s?u=}
     */
    static String[] rewriteM3u8(String text, String baseAbs, String prefix) {
        StringBuilder sb = new StringBuilder(text.length() + 256);
        String[] lines = text.split("\n");
        int rewrites = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            if (t.length() == 0) {
                sb.append(line).append('\n');
                continue;
            }
            if (t.startsWith("#")) {
                // 标签行不能整行放过：#EXT-X-KEY 的 URI 是 AES-128 的密钥地址，
                // #EXT-X-MAP 的 URI 是 fMP4 的 init segment。两者都可能是相对
                // 路径（URI="enc.key"），而 VLC 会拿它对着**中继地址**解析 ——
                // 相对解析丢掉 query，落到 http://127.0.0.1:PORT/enc.key，
                // 中继只认 /s?u=，回 404。症状是能开播但一直黑屏：解密失败不报错。
                String tag = rewriteTagUris(line, baseAbs, prefix);
                if (tag != null) {
                    sb.append(tag).append('\n');
                    rewrites++;
                } else {
                    sb.append(line).append('\n');
                }
                continue;
            }
            String abs;
            try {
                abs = new java.net.URI(baseAbs).resolve(t).toString();
            } catch (Exception e) {
                abs = t;
            }
            // 这里**不能**回头用 wrap()：它会把「直连表里的域名」退回原地址，
            // 而这条路上原地址就是 Cloudflare 的 403。必须让 VLC 的每一个
            // 请求都回到中继来。
            sb.append(prefix).append(encode(abs)).append('\n');
            rewrites++;
        }
        return new String[] { sb.toString(), String.valueOf(rewrites) };
    }

    // ── 插播广告剔除 ────────────────────────────────────────────────────
    //
    // 规则移植自两个开源实现（代码原创，判据照抄实测值）：
    //   · LibreSpark/LibreTV  src/lib/m3u8.ts —— stripMarkedAdGroups /
    //     stripInterstitialAdGroups / stripLeadAdGroup，作者在 dytt、phimgood、
    //     rycjapi 几个采集源上量过，注释里留着真实分段秒数。
    //   · ltxlong/cf-m3u8-filter-ad-api —— 用「ts 文件名序号断裂」抓同域名插段。
    //   · eraycc/m3u8-proxy-script —— 同思路的 worker 版，可当第二意见。
    //
    // 一个共同的血的教训（LibreTV 注释里写着）：**只删 #EXT-X-DISCONTINUITY 不删分片，
    // 会让播放器把整条流当连续时间轴**，PTS 基准跳变失效，症状是「只闻广告声不见画面」。
    // 所以这里一律整段连分片一起删，段内的 EXT-X-KEY / EXT-X-MAP 保留（误删会导致解不开）。

    /** 广告分片路径特征。LibreTV 实测 `adjump`（暴风采集云），其余是常见变体。 */
    private static final java.util.regex.Pattern AD_PATH = java.util.regex.Pattern.compile(
            "(?i)adjump|advert|/ads?/|/(ad|ads|adv|preroll|guanggao|gg_video)([/_.-]|$)");

    /** 中插广告的时长上限：超过这个数就不像「塞进来的一段」。 */
    private static final double AD_RUN_MAX_S = 90;
    /** 片头广告比这更保守。 */
    private static final double AD_HEAD_MAX_S = 90;
    /** 片头广告的分片数上限（LibreTV：20 片）。 */
    private static final int AD_HEAD_MAX_SEG = 20;
    /** 「两长夹一短」里的短：≤30 秒。 */
    private static final double AD_SHORT_MAX_S = 30;
    /** 「两长夹一短」里的长：前后邻段都得 ≥120 秒。 */
    private static final double AD_NEIGHBOR_MIN_S = 120;

    /** 一个 discontinuity 分组（组前的边界标签单独记）。 */
    private static final class AdGroup {
        int start;          // 组内容起始行（不含边界标签）
        int end;            // 组内容结束行（不含）
        int discLine;       // 组前的 #EXT-X-DISCONTINUITY 行号，首组 -1
        double seconds;
        int count;
        boolean allAdUrl;   // 组内每个分片都命中广告路径特征
        boolean onMainHost; // 至少一条分片在全表主域名上
        int firstNum = Integer.MIN_VALUE;   // 组首分片的文件名序号
        int lastNum = Integer.MIN_VALUE;    // 组尾分片的文件名序号
    }

    /**
     * 剔掉播到一半突然插进来的广告分段。
     *
     * <p>这不是 App 加的广告 —— 我方没有任何广告 SDK、播放器里也没有广告位。
     * 广告是站点塞在 m3u8 里的独立分段序列，前后用 {@code #EXT-X-DISCONTINUITY} 隔开。
     * 四条判据各自有实测出处，命中一条就删：
     * <ol>
     *   <li><b>路径特征</b>：组内分片全部命中 {@code adjump|advert|/ad/|/gg_video/} 之类；</li>
     *   <li><b>序号断裂</b>：全表分片是连续编号（plist0…plist885），组首号却没接上；</li>
     *   <li><b>两长夹一短</b>：段 ≤30 秒，且前后邻段都 ≥120 秒（正片段之间的插入物）；</li>
     *   <li><b>换了域名</b>：组内没有一条分片在主域名上，且后面还回主域名。</li>
     * </ol>
     * 片头额外一条：首个分片前就有 discontinuity、该段 ≤90 秒且 ≤20 片、后面还有分段 ——
     * 但如果这一组跟其余分组大小相当（采集站按固定块切的「多分组封装」），跳过，
     * 那种源里杀片头等于杀正片。
     *
     * <p>总闸：一次删掉的时长不超过全片 60%，删完剩下的分段不少于 6 条。
     * 宁可广告漏播，也不能把正片啃掉一块。
     *
     * @return {@code [新文本, 删掉的段数, 删掉的秒数, 结构摘要]}
     */
    static String[] stripAdRuns(String text, String baseAbs) {
        String[] lines = text.split("\n", -1);
        java.util.List<AdGroup> groups = scanAdGroups(lines, baseAbs);
        int totalSeg = 0;
        double totalSec = 0;
        for (AdGroup g : groups) { totalSeg += g.count; totalSec += g.seconds; }
        if (totalSeg < 6) return new String[] { text, "0", "0", "分段不足 6 条" };

        String mainHost = mainHostOf(lines, baseAbs);
        boolean sequential = numberedSequentially(lines, baseAbs, mainHost);
        int disc = 0;
        for (AdGroup g : groups) if (g.discLine >= 0) disc++;
        StringBuilder sum = new StringBuilder();
        sum.append(totalSeg).append(" 段 ").append((long) totalSec).append("s discontinuity=")
           .append(disc).append(" 主域名 ").append(mainHost)
           .append(sequential ? "（序号连续）" : "（无序号）");
        final String summary = sum.toString();

        int foreignGroups = 0;
        for (AdGroup g : groups) if (g.count > 0 && !g.onMainHost) foreignGroups++;
        boolean cdnRotation = foreignGroups >= 2;   // 两个以上外域组 = 多 CDN 轮换，不是广告

        boolean[] dropGroup = new boolean[groups.size()];
        int dropped = 0;
        double droppedSec = 0;
        int expected = Integer.MIN_VALUE;           // 下一个「应该」出现的序号
        for (int k = 0; k < groups.size(); k++) {
            AdGroup g = groups.get(k);
            if (g.count == 0) continue;
            boolean isLead = k == 0;
            boolean ad = false;
            String why = null;
            if (g.allAdUrl) { ad = true; why = "路径特征"; }
            else if (sequential && expected != Integer.MIN_VALUE && g.firstNum != Integer.MIN_VALUE
                    && g.firstNum != expected && g.seconds <= AD_RUN_MAX_S && g.count <= AD_HEAD_MAX_SEG) {
                ad = true; why = "序号断裂";
            }
            else if (!cdnRotation && !g.onMainHost && g.seconds <= AD_RUN_MAX_S
                    && returnsToMain(groups, k)) { ad = true; why = "换域名"; }
            if (!ad && !isLead && k > 0 && k + 1 < groups.size()) {
                AdGroup prev = groups.get(k - 1), next = groups.get(k + 1);
                if (g.seconds > 0 && g.seconds <= AD_SHORT_MAX_S
                        && prev.seconds >= AD_NEIGHBOR_MIN_S && next.seconds >= AD_NEIGHBOR_MIN_S) {
                    ad = true; why = "两长夹一短";
                }
            }
            if (!ad && isLead && g.discLine >= 0
                    && g.seconds > 0 && g.seconds <= AD_HEAD_MAX_S && g.count <= AD_HEAD_MAX_SEG
                    && !uniformGrouping(groups)) {
                ad = true; why = "片头插入段";
            }
            if (!ad) {
                if (g.lastNum != Integer.MIN_VALUE) expected = g.lastNum + 1;
                continue;
            }

            double afterS = droppedSec + g.seconds;
            int afterN = dropped + g.count;
            if (afterS > totalSec * 0.6 || totalSeg - afterN < 6) {
                if (g.lastNum != Integer.MIN_VALUE) expected = g.lastNum + 1;
                continue;
            }
            dropGroup[k] = true;
            dropped += g.count;
            droppedSec += g.seconds;
            log("广告分段命中：" + why + "（" + g.count + " 片 " + (long) g.seconds + "s）");
        }
        if (dropped == 0) return new String[] { text, "0", "0", summary };

        boolean[] dropLine = new boolean[lines.length];
        for (int k = 0; k < groups.size(); k++) {
            if (!dropGroup[k]) continue;
            AdGroup g = groups.get(k);
            // 边界标签一起删：留着它等于给播放器留一个空跳变点。
            if (g.discLine >= 0) dropLine[g.discLine] = true;
            for (int i = g.start; i < g.end; i++) {
                String t = lines[i].trim();
                // 解密/初始化配置留着 —— 正片若复用它，删了就解不开。
                if (t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP")) continue;
                dropLine[i] = true;
            }
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean seenSegment = false;
        for (int i = 0; i < lines.length; i++) {
            if (dropLine[i]) continue;
            String t = lines[i].trim();
            boolean seg = t.length() > 0 && !t.startsWith("#");
            if (seg) seenSegment = true;
            // 首分片之前那个 discontinuity 已经没内容可跳，删掉。
            if (!seenSegment && t.equals("#EXT-X-DISCONTINUITY")) continue;
            sb.append(lines[i]).append('\n');
        }
        return new String[] { sb.toString(), String.valueOf(dropped),
                String.valueOf((long) droppedSec), summary };
    }

    /** 按 discontinuity 切组，顺手把每组要用到的特征算好。 */
    private static java.util.List<AdGroup> scanAdGroups(String[] lines, String baseAbs) {
        String mainHost = mainHostOf(lines, baseAbs);
        java.util.List<AdGroup> groups = new java.util.ArrayList<>();
        java.util.List<Integer> discIdx = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("#EXT-X-DISCONTINUITY")) discIdx.add(i);
        }
        java.util.List<Integer> bounds = new java.util.ArrayList<>();
        bounds.add(0);
        bounds.addAll(discIdx);
        bounds.add(lines.length);
        double pendingDur = -1;
        for (int k = 0; k < bounds.size() - 1; k++) {
            AdGroup g = new AdGroup();
            g.discLine = k == 0 ? -1 : bounds.get(k);
            g.start = k == 0 ? 0 : bounds.get(k) + 1;
            g.end = bounds.get(k + 1);
            g.allAdUrl = true;
            g.onMainHost = false;
            int firstNum = Integer.MIN_VALUE;
            int lastNum = Integer.MIN_VALUE;
            for (int i = g.start; i < g.end; i++) {
                String t = lines[i].trim();
                if (t.length() == 0) continue;
                if (t.startsWith("#")) {
                    if (t.startsWith("#EXTINF:")) pendingDur = parseExtinf(t);
                    continue;
                }
                g.count++;
                g.seconds += pendingDur > 0 ? pendingDur : 0;
                pendingDur = -1;
                String abs = resolveAbs(t, baseAbs);
                if (!AD_PATH.matcher(pathOf(abs)).find()) g.allAdUrl = false;
                if (hostOf(abs).equals(mainHost)) g.onMainHost = true;
                int num = trailingNumber(abs);
                if (firstNum == Integer.MIN_VALUE) firstNum = num;
                if (num != Integer.MIN_VALUE) lastNum = num;
            }
            if (g.count == 0) { g.allAdUrl = false; }
            g.firstNum = firstNum;
            g.lastNum = lastNum;
            groups.add(g);
        }
        return groups;
    }

    /** 全表主域名：分片数最多的那个 host。 */
    private static String mainHostOf(String[] lines, String baseAbs) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.length() == 0 || t.startsWith("#")) continue;
            String h = hostOf(resolveAbs(t, baseAbs));
            count.put(h, count.containsKey(h) ? count.get(h) + 1 : 1);
        }
        String best = "";
        int n = -1;
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() > n) { n = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    /** 这组之后还有没有回到主域名的分片。 */
    private static boolean returnsToMain(java.util.List<AdGroup> groups, int k) {
        for (int i = k + 1; i < groups.size(); i++) {
            if (groups.get(i).onMainHost) return true;
        }
        return false;
    }

    /**
     * 多分组封装判定：除片头外的分组大小都差不多 ⇒ 采集站按固定块切的边界，
     * 不是广告。LibreTV 实测 rycjapi 每组 5 片、dytt 5~20 片，这两种源里
     * 把片头当广告删掉等于删正片。
     */
    private static boolean uniformGrouping(java.util.List<AdGroup> groups) {
        if (groups.size() < 4) return false;
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        for (int i = 1; i < groups.size(); i++) sizes.add(groups.get(i).count);
        java.util.Collections.sort(sizes);
        int median = sizes.get(sizes.size() / 2);
        int lead = groups.get(0).count;
        return Math.abs(lead - median) <= Math.max(3, median / 2);
    }

    /**
     * 全表分片是不是「末尾递增编号」（plist0.ts … plist885.ts）。
     * 只有这种形状才敢用「序号断裂」判据 —— 随机名的分片本来就对不上号。
     */
    private static boolean numberedSequentially(String[] lines, String baseAbs, String mainHost) {
        int numbered = 0, sequential = 0, prev = Integer.MIN_VALUE, total = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.length() == 0 || t.startsWith("#")) continue;
            // 只看主域名的分片：多 CDN 轮换的源里每组都从 0 重新编号，
            // 把外域组算进来会让「序号连续」这个前提自己先崩。
            if (!hostOf(resolveAbs(t, baseAbs)).equals(mainHost)) continue;
            total++;
            int num = trailingNumber(resolveAbs(t, baseAbs));
            if (num == Integer.MIN_VALUE) continue;
            numbered++;
            if (prev != Integer.MIN_VALUE && num == prev + 1) sequential++;
            prev = num;
        }
        // 阈值定得死一点：真出问题的源整集编号是连续的（886 段里 0 处断），
        // 而「每组从 0 重来」的封装源一眼就掉到 87% 以下，必须分得开。
        return total >= 6 && numbered >= total * 0.9 && sequential >= numbered * 0.98;
    }

    /** 地址里最后一个数字（文件名尾巴），拿不到返回 MIN_VALUE。 */
    private static int trailingNumber(String abs) {
        String p = pathOf(abs);
        int end = p.length() - 1;
        while (end >= 0 && !Character.isDigit(p.charAt(end))) end--;
        if (end < 0) return Integer.MIN_VALUE;
        int start = end;
        while (start >= 0 && Character.isDigit(p.charAt(start))) start--;
        // 只认文件名里的，别把 /20260815/ 这种日期目录当序号
        int slash = p.lastIndexOf('/', end);
        if (slash > start) return Integer.MIN_VALUE;
        try {
            return Integer.parseInt(p.substring(start + 1, end + 1));
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    private static String resolveAbs(String uri, String baseAbs) {
        try {
            return new java.net.URI(baseAbs).resolve(uri).toString();
        } catch (Exception e) {
            return uri;
        }
    }

    private static double parseExtinf(String tag) {
        try {
            int comma = tag.indexOf(',');
            String v = tag.substring(tag.indexOf(':') + 1, comma > 0 ? comma : tag.length()).trim();
            return Double.parseDouble(v);
        } catch (Exception e) {
            return 0d;
        }
    }

    private static String hostOf(String abs) {
        try {
            String h = new java.net.URI(abs).getHost();
            return h == null ? "" : h.toLowerCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    private static String pathOf(String abs) {
        try {
            String p = new java.net.URI(abs).getPath();
            return p == null ? "" : p;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 同 {@link #rewriteM3u8}，但**按每条 URI 自己的路由**决定绕不绕中继。
     *
     * <p>为什么要有这一版：直连源（源把播放域名声明成直连）的分片本来一条都不该
     * 进中继，可播放列表又必须进中继才能剔广告。以前 wrap() 见直连就直接放给 VLC，
     * 于是这类源的中继一次都看不到播放列表 —— 广告过滤对它们等于不存在。
     * 现在列表进中继、分片照旧直连，两者都拿到。
     */
    static String[] rewriteM3u8Smart(String text, String baseAbs, String prefix) {
        StringBuilder sb = new StringBuilder(text.length() + 256);
        String[] lines = text.split("\n");
        int rewrites = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            if (t.length() == 0) { sb.append(line).append('\n'); continue; }
            if (t.startsWith("#")) {
                String tag = rewriteTagUris(line, baseAbs, prefix);
                if (tag != null) { sb.append(tag).append('\n'); rewrites++; }
                else sb.append(line).append('\n');
                continue;
            }
            String abs = resolveAbs(t, baseAbs);
            // master 列表里的子列表也必须是中继地址 —— 否则 VLC 直接去拉媒体列表，
            // 中继只在 master 上转了一圈（实测 v4.ppqrrs.com：master 一眼过，
            // 真正的 886 段媒体列表全在直连里，剔广告等于没装）。
            if (needsRelayHop(abs) || isPlaylistUrl(abs)) {
                sb.append(prefix).append(encode(abs)).append('\n');
            } else {
                // 直连分片：写绝对地址，VLC 自己去拉，中继不掺和。
                sb.append(abs).append('\n');
            }
            rewrites++;
        }
        return new String[] { sb.toString(), String.valueOf(rewrites) };
    }

    /** 这条地址要不要绕中继？判据和 {@link #wrap} 同源，只是不分「是不是播放列表」。 */
    static boolean needsRelayHop(String abs) {
        if (abs == null || abs.length() == 0) return false;
        if (disguised(abs)) return true;
        if (AiiHttp.effectiveProxy().length() == 0) return false;
        try {
            String host = new java.net.URI(abs).getHost();
            return host != null && NetRoute.mustProxy(host);
        } catch (Exception e) {
            return false;
        }
    }

    /** 这条地址是不是一份播放列表（按路径后缀判，交给中继自己改写）。 */
    static boolean isPlaylistUrl(String url) {
        try {
            String p = new java.net.URI(url).getPath();
            if (p == null) return false;
            p = p.toLowerCase(Locale.US);
            return p.endsWith(".m3u8") || p.endsWith(".m3u");
        } catch (Exception e) {
            return false;
        }
    }

    /** {@code URI="…"} / {@code URI='…'}，HLS 标签里唯一会带出站地址的属性。 */
    private static final java.util.regex.Pattern TAG_URI =
            java.util.regex.Pattern.compile("URI\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    /**
     * 把标签行里的 {@code URI=} 值也包成中继地址。
     *
     * <p>返回 {@code null} 表示这一行没有 URI 属性（或都已经包过了），调用方原样保留。
     * 只在真的改动了属性时才算改写过 —— 否则 {@code #EXTINF}、{@code #EXT-X-ENDLIST}
     * 这类纯标记行会把改写条数撑大，日志读起来就失真了。
     *
     * <p>绝对地址照样包一层：{@code #EXT-X-KEY} 直接指向 CDN 的话，VLC 会绕过中继
     * 去连，而这个站的 CDN 域名既不在直连表里也没走代理，一样是连不上。
     */
    static String rewriteTagUris(String line, String baseAbs, String prefix) {
        java.util.regex.Matcher m = TAG_URI.matcher(line);
        if (!m.find()) return null;
        StringBuilder out = new StringBuilder(line.length() + 96);
        int last = 0;
        boolean changed = false;
        m.reset();
        while (m.find()) {
            String val = m.group(1) != null ? m.group(1) : m.group(2);
            String quote = m.group(1) != null ? "\"" : "'";
            if (val == null || val.length() == 0 || val.startsWith(prefix)) continue;
            String abs;
            try {
                abs = new java.net.URI(baseAbs).resolve(val).toString();
            } catch (Exception e) {
                abs = val;
            }
            out.append(line, last, m.start());
            out.append("URI=").append(quote).append(prefix).append(encode(abs)).append(quote);
            last = m.end();
            changed = true;
        }
        if (!changed) return null;
        out.append(line.substring(last));
        return out.toString();
    }

    /**
     * 透传给上游的头。
     *
     * 只搬媒体栈真正用得上的：Range 是播放和 seek 的命根子；UA/Referer 有些
     * CDN 会校验（VlcPlayerActivity 已经把它们塞进 payload，这里一并转过去）。
     * 其余（Accept-Encoding 之类）不搬 —— 我们要原样字节，不要上游压过的。
     */
    private static void applyHeaders(Request.Builder builder, Map<String, String> headers, String url) {
        applyHeaders(builder, headers, false, url);
    }

    /**
     * @param dropRange 播放列表传 true。播放列表是要整份改写的小文本，
     *                  带 Range 上游会回 206 只给一段，改写就落空。
     */
    private static void applyHeaders(Request.Builder builder, Map<String, String> headers,
                                     boolean dropRange, String url) {
        String range = headers.get("range");
        if (!dropRange && range != null && range.length() > 0) builder.header("Range", range);

        String ua = headers.get("user-agent");
        if (ua != null && ua.length() > 0) builder.header("User-Agent", ua);

        String referer = headers.get("referer");
        if (referer != null && referer.length() > 0) builder.header("Referer", referer);

        // 媒体栈要求原样字节（identity;q=1, *;q=0），别让上游压。
        builder.header("Accept-Encoding", "identity");

        // 最后套源声明的模板（UA / Referer），同名会覆盖上面 VLC 带来的那份 ——
        // 源自己写的才是权威，VLC 那个 UA 是自己的名字，防盗链一眼就拒。
        applySourceHeaders(builder, url);
    }

    /**
     * 把当前源的 headers 模板落到请求上，规则和 {@code AiiHttp.applyHeaders} 一致：
     * {@code {origin}} 换成源域名，换完是空串或光一个 {@code /} 的头直接不发
     * （缺 Referer 是 200，发个错的反而 403）。
     */
    private static void applySourceHeaders(Request.Builder builder, String url) {
        Outbound.applyHeaders(builder, sourceProfile, url);
    }

    /**
     * 写状态行 + 响应头。
     *
     * 200 和 206 都必须能把 Content-Length 带过去 —— VLC 靠它算总时长和
     * seek 范围。206 还要 Content-Range，少了它拖动进度会跳到错误位置。
     */
    private static void writeHead(OutputStream out, Response response) throws IOException {
        writeHeadAs(out, response, null);
    }

    /**
     * 同 {@link #writeHead}，但可以把 Content-Type 换掉。
     *
     * <p>给伪装后缀那条路用：上游把 MPEG-TS 标成 image/jpeg，照抄的话 VLC 会当
     * 图片处理（{@code Failed to create demuxer}）。{@code override} 非空就顶掉
     * 上游那个值，其余头（长度、Range、ETag）原样透传。
     *
     * @param override 新的 Content-Type；null = 照抄上游
     */
    private static void writeHeadAs(OutputStream out, Response response, String override) throws IOException {
        int code = response.code();
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(code).append(' ')
                .append(response.message() == null || response.message().length() == 0
                        ? reason(code) : response.message())
                .append("\r\n");

        append(head, "Content-Type", override != null ? override : response.header("Content-Type"));
        append(head, "Content-Length", response.header("Content-Length"));
        append(head, "Content-Range", response.header("Content-Range"));
        append(head, "Accept-Ranges", response.header("Accept-Ranges"));
        append(head, "Last-Modified", response.header("Last-Modified"));
        append(head, "ETag", response.header("ETag"));

        // 长度未知时也不能声明 chunked：serve() 里 body 是裸流直写、没有
        // chunk 分帧，VLC 按严格 chunk 解析必然失败。响应已带 Connection: close，
        // 就靠 EOF 定界。
        head.append("Connection: close\r\n\r\n");

        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** 前几个字节是不是某个 ASCII 串。给「自己判内容类型」用。 */
    private static boolean startsWith(byte[] data, String prefix) {
        if (data == null || prefix == null) return false;
        byte[] want = prefix.getBytes(StandardCharsets.US_ASCII);
        if (data.length < want.length) return false;
        for (int i = 0; i < want.length; i++) {
            if (data[i] != want[i]) return false;
        }
        return true;
    }

    private static void append(StringBuilder sb, String name, String value) {
        if (value == null || value.length() == 0) return;
        sb.append(name).append(": ").append(value).append("\r\n");
    }

    private static void writeSimple(OutputStream out, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            default: return "Status " + code;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 8192) return null;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String param(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Throwable ignored) {
        }
    }

    /** 日志里的地址：去掉 query（可能带签名），只留主机 + 路径尾巴。 */
    private static String shortUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        String s = q > 0 ? url.substring(0, q) : url;
        return s.length() <= 140 ? s : s.substring(0, 60) + "…" + s.substring(s.length() - 70);
    }

    // ── 上游状态码快照：给播放器说人话用 ───────────────────────────────

    /**
     * 最近一次上游返回的状态码 + 时间。
     *
     * <p>播放器只会拿到 VLC 的 {@code EncounteredError}，它自己分不清「地址没了」
     * 和「编码解不开」。可真机上这两件事该说的话完全不同：前者是源站数据坏了，
     * 换一集就好；后者才是播放器的问题。中继是唯一知道上游回了什么的地方，
     * 于是由它留一个 15 秒内有效的快照，播放器报错时来问一句。
     */
    private static volatile int lastUpstreamCode = 0;
    private static volatile long lastUpstreamAt = 0;

    private static void noteUpstream(int code) {
        lastUpstreamCode = code;
        lastUpstreamAt = System.currentTimeMillis();
    }

    /** 15 秒内刚发生过的非 2xx，返回状态码；否则 0。 */
    static int recentUpstreamFailure() {
        if (lastUpstreamCode < 200 || lastUpstreamCode >= 300) {
            return System.currentTimeMillis() - lastUpstreamAt < 15_000 ? lastUpstreamCode : 0;
        }
        return 0;
    }

    private static void log(String message) {
        try {
            android.util.Log.d("AIIvideo", TAG_PREFIX + message);
        } catch (Throwable ignored) {
        }
    }
}
