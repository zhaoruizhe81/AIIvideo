package com.huangguo.mobile;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 把媒体请求从 WebView 手里接管过来，走我们自己的 OkHttp。
 *
 * ── 为什么需要这个 ────────────────────────────────────────────────────
 * 实测（2026-10-16，真机 + WebView 自己的 CDP）：
 *
 *   bilibili  upos-sz-mirrorhw.bilivideo.com   签名地址   HTTP/2  → 403
 *   同一台手机、同一个 IPv6、同一组请求头、同一个地址
 *   bilibili  同一个地址                                  HTTP/1.1 → 206
 *   xigua     v16.toutiao50.com                           HTTP/1.1 → 206
 *
 * 两次响应的后端根本不是同一个服务：
 *
 *   403:  x-service-module: hw-h2-server    via: ...CACHE45[0]
 *   206:  x-service-module: hw-h1-server    via: ...CACHE2[67],...CACHE10[0,TCP_HIT,62]
 *
 * 也就是说**这台 CDN 的 HTTP/2 后端不认 bilibili 的 upsig 签名**。
 * 而 `<video>` 用什么协议是 WebView 自己协商的，WebSettings 里没有开关，
 * 前端也无从干涉 —— 所以只能把请求接过来自己发，指定 HTTP/1.1。
 *
 * 排除过的（都不是原因）：Referer（加 no-referrer 照样 403）、User-Agent、
 * sec-ch-ua*、Accept-Encoding、Range 的六种形态、IPv4/IPv6、容器格式
 * （ftypisom + avc1 + H.264 High，Chrome 里 loadedmetadata 1920x1080 正常）。
 *
 * ── 怎么接 ────────────────────────────────────────────────────────────
 * MainActivity$1 的 shouldInterceptRequest 里，先问一句 handles()，
 * 是媒体域名就 fetch() 拿一个 WebResourceResponse 回去，否则照旧交给
 * WebViewAssetLoader。
 *
 * 只接管下面那三个域名，不做「所有媒体都走代理」—— 别的 CDN 现在好好的，
 * 没有理由把它们也拽进这条新路径上来试错。
 */
public final class MediaProxy {

    /**
     * 要接管的域名（后缀匹配）。
     *
     * bilibili 那一族是实测在 h2 下会 403 的。
     *
     * 白名单里那个视频 CDN 域（2026-10-16 加）**成因不一样**，别混为一谈：
     *
     *   · 它服务器侧完全正常 —— 裸 curl、带 Referer、带 Origin、换 UA，
     *     五种组合全部 206 video/mp4（在手机上量的，不是本机）。
     *   · 编码也正常 —— ftyp isom + avc1（H.264），moov 在文件头（faststart），
     *     是兼容性最好的组合，不是 HEVC 那种 WebView 盲区。
     *   · 但 WebView 直接拉它 → MediaError 4（SRC_NOT_SUPPORTED），
     *     而同一台手机上，别的源播 m3u8 是好的。
     *
     * 所以它挂的是「WebView 自己发的那个请求」，不是这个地址本身。接过来
     * 用 OkHttp 发（HTTP/1.1、我们自己的头），是唯一能改的那一层。
     * 真机日志里修完之后会看到 `[media] 媒体代理 206 http/1.1`。
     *
     * ⚠️ 加新域名之前先量一次。判据：**服务器侧正常、只有 WebView 发才挂** ——
     *    如果服务器侧本身就出错（403 / 404），那接管过来也救不了，
     *    往里加只会让日志多一层噪音。
     */
    private static final String[] HOSTS = {
            "bilivideo.com",
            "bilivideo.cn",
            "bilibili.com",
            "hembed.com",
    };

    /** 是否由我们接管。不看路径 —— 同一个域下的分片、主播放列表都得走同一条路。 */
    public static boolean handles(String url) {
        if (url == null) return false;
        try {
            String host = new java.net.URI(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            for (int i = 0; i < HOSTS.length; i++) {
                String h = HOSTS[i];
                if (host.equals(h) || host.endsWith("." + h)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 强制 HTTP/1.1 的客户端。
     *
     * **不能复用 AiiHttp 的 plainClient** —— 它默认允许 h2，而 h2 正是要绕开的
     * 那个东西。这里 .protocols() 只给 HTTP_1_1，OkHttp 就不会去 ALPN 谈 h2 了。
     *
     * 超时给得比一般请求长：一集 90 MB，虽然每次只取一个 Range，但播放途中
     * 连着取的时候别让读超时把连接掐了。
     *
     * ── 代理（为什么跟随 AiiHttp.effectiveProxy()）────────────────────
     * 这 4 个域是视频 CDN。以前 MediaProxy 是完全直连的，因为当初接手它们
     * 只是为了绕开 h2 那个 403，跟路由无关。
     *
     * 但内置核心一起来，只代理 API 不代理 CDN 是个半吊子状态：封面、接口都通了，
     * 视频仍卡在同一个连不上的 CDN 上，用户看到的是「能刷出列表但点开就转圈」，
     * 比全不通还难解释。所以这里跟随同一条代理串。
     *
     * 代价说清楚：CDN 本地能直连时，这么走是绕路。要直连就别启动内置核心 ——
     * 那是唯一开关，没有「核心开、媒体却直连」的组合。真需要那种组合的话，
     * 在这里加判断即可（UrlPattern 之外，AiiHttp 已经有了 host 的形态）。
     */
    private static volatile OkHttpClient mediaClient;
    private static volatile String mediaAppliedProxy = null;

    /** 代理来源变了（内置核心启停 / 用户改手填地址）时调用，下一条请求重建。 */
    static void invalidate() {
        synchronized (MediaProxy.class) {
            mediaClient = null;
            mediaAppliedProxy = null;
            relayClient = null;
            relayAppliedProxy = null;
            relayClientDirect = null;
        }
    }

    private static OkHttpClient client() {
        final String want = AiiHttp.effectiveProxy();
        OkHttpClient c = mediaClient;
        if (c != null && want.equals(mediaAppliedProxy)) return c;
        synchronized (MediaProxy.class) {
            if (mediaClient == null || !want.equals(mediaAppliedProxy)) {
                OkHttpClient.Builder builder = new OkHttpClient.Builder()
                        .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(15, TimeUnit.SECONDS);
                // 解析和鉴权复用 AiiHttp 那一套：外部代理的写法（socks5://、user:pass@）
                // 只该有一个实现，否则两边认得的格式会慢慢分叉。
                String broken = AiiHttp.attachProxy(builder, want);
                if (broken != null) log("代理地址认不出来，媒体直连：" + broken);
                mediaClient = builder.build();
                mediaAppliedProxy = want;
            }
            return mediaClient;
        }
    }

    /**
     * 给 {@link VlcRelay} 用的客户端：同一套代理，但读超时放得很长。
     *
     * 为什么不能直接复用 {@link #client()}：那个 readTimeout 是 30 秒，是按
     * 「WebView 每次取一个 Range」设计的。VlcRelay 是把整条流顺序搬给 libVLC，
     * 一部片可能连着读几十分钟，中间只要有一次 30 秒没吐字节就会被掐断。
     *
     * 读超时给 120 秒，不是 0（无限）：0 的话上游 CDN 一旦半开/停滞，中继线程
     * 会永远卡在 read 上，线程和连接都不释放 —— VlcRelay 那头的 SO_TIMEOUT 只管
     * 从 VLC 读请求，管不到上游这一段。120 秒足够盖过正常的慢段；真停滞由
     * 超时收尾，VLC 会自己换一条连接重试。
     *
     * 注意不要把它和 mediaClient 混用同一个字段：两者的超时语义不同，
     * 谁覆盖谁都会让另一方行为出错。
     */
    private static volatile OkHttpClient relayClient;
    private static volatile String relayAppliedProxy = null;

    /** 直连那一份：同样的超时语义，只是不挂代理。 */
    private static volatile OkHttpClient relayClientDirect;

    /**
     * 给 {@link VlcRelay} 用的客户端，按「这条地址该不该走代理」分两份。
     *
     * <p>这一层原来不存在：中继只会收 {@code mustProxy} 的地址（见
     * VlcRelay.wrap 的老逻辑），所以无条件跟随 {@link AiiHttp#effectiveProxy()}
     * 是对的。2.0.16 把播放列表改成「一律绕中继」（要拿列表才能剔广告、才能
     * 改写相对地址）之后，那道闸被绕开了 —— 直连域名也进了中继，又被这里
     * 塞回代理节点。
     *
     * <p>真机上的样子（2026-10-21，用户报「AGE动漫一个都看不了」）：AGE 的
     * ffm3u8 线路解出 {@code vip.ffzy-plays.com}，而 DirectHosts 里明明白白
     * 写着它该直连（樱花同款采集站 CDN）。走代理出去是境外 IP，CDN 直接回
     * {@code 403 The region has been denied} —— 一个源的所有集一起挂。
     *
     * <p>语义收敛成一句：**代理只给 mustProxy 的地址用**。改前靠 wrap 拦，
     * 现在播放列表绕开了 wrap，就得在这里判。
     *
     * @param viaProxy 这条地址要不要走代理（调用方用 NetRoute.mustProxy 算）
     */
    static synchronized OkHttpClient relayClient(boolean viaProxy) {
        if (!viaProxy) {
            OkHttpClient direct = relayClientDirect;
            if (direct != null) return direct;
            direct = new OkHttpClient.Builder()
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(0, TimeUnit.MILLISECONDS)
                    .build();
            relayClientDirect = direct;
            return direct;
        }
        final String want = AiiHttp.effectiveProxy();
        OkHttpClient c = relayClient;
        if (c != null && want.equals(relayAppliedProxy)) return c;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS);
        String broken = AiiHttp.attachProxy(builder, want);
        if (broken != null) log("中继代理地址认不出来，媒体直连：" + broken);
        relayClient = builder.build();
        relayAppliedProxy = want;
        return relayClient;
    }

    /**
     * 转发一次。拿不到响应就返回 null，调用方回落到 WebView 自己的路径 ——
     * 代理是来补洞的，不该变成新的单点。
     *
     * **必须原样带上客户端的 Range。** 媒体栈靠它做 seek 和拖进度；抽掉的话
     * 要么整集从头读，要么直接判「不支持」。
     */
    public static WebResourceResponse fetch(WebResourceRequest request) {
        Response response = null;
        try {
            String url = request.getUrl().toString();
            Request.Builder builder = new Request.Builder().url(url).get();

            Map<String, String> asked = request.getRequestHeaders();
            String range = asked == null ? null : asked.get("Range");
            if (range != null && range.length() > 0) builder.header("Range", range);
            // 媒体栈要求原样字节（identity;q=1, *;q=0），转过去。
            builder.header("Accept-Encoding", "identity");

            response = client().newCall(builder.build()).execute();
            ResponseBody body = response.body();
            if (body == null) {
                response.close();
                return null;
            }

            // 只透传播放器真正用得上的几个。响应头里那串 via / Age / X-CCDN-*
            // 是 CDN 自己的事，透过去没有意义，反而可能撞上 WebView 的解析。
            Map<String, String> headers = new LinkedHashMap<String, String>();
            copy(response, headers, "Content-Type");
            copy(response, headers, "Content-Length");
            copy(response, headers, "Content-Range");
            copy(response, headers, "Accept-Ranges");
            copy(response, headers, "Last-Modified");
            copy(response, headers, "ETag");

            String mime = response.header("Content-Type", "video/mp4");
            // encoding 传 null —— 二进制流，写 "utf-8" 会让 WebView 去做文本解码。
            WebResourceResponse out = new WebResourceResponse(
                    mime, null, response.code(), reason(response.code()), headers, body.byteStream());
            log("媒体代理 " + response.code() + " " + response.protocol()
                    + " " + url.substring(0, Math.min(90, url.length())));
            // 不 close()：流要一直活到 WebView 读完。它读完会把流关掉，
            // OkHttp 那边跟着释放连接。
            return out;
        } catch (Throwable e) {
            // execute() 成功后、构造/返回段抛上来的异常也落在这儿：response 已
            // 占着连接池一条连接，必须在这儿关掉，否则越积越多。正常返回的流
            // 要活到 WebView 读完，所以只有这个异常分支允许 close。
            if (response != null) {
                try { response.close(); } catch (Throwable ignored) {}
            }
            log("媒体代理失败，回落到 WebView：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    private static void copy(Response response, Map<String, String> out, String name) {
        String v = response.header(name);
        if (v != null && v.length() > 0) out.put(name, v);
    }

    private static void log(String message) {
        try { android.util.Log.d("AIIvideo", "[media] " + message); } catch (Throwable ignored) {}
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            // 空串不行：WebResourceResponse 的 reasonPhrase 为空在部分实现上直接抛
            // IllegalArgumentException，这条代理对未列举的状态码就整个失效了。
            default:  return "Status " + code;
        }
    }
}
