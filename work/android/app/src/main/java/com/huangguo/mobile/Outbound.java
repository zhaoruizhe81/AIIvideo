package com.huangguo.mobile;

import java.util.Iterator;
import java.net.URL;
import org.json.JSONObject;
import okhttp3.Request;

/**
 * 出站请求的唯一装配点。
 *
 * <p>这个类只回答两个问题，任何要发出去的请求都该来这里问：
 *
 * <ol>
 *   <li><b>带什么头</b> —— 源声明的那份模板（UA / Referer，{@code {origin}} 换成源域名）；</li>
 *   <li><b>走不走代理</b> —— {@link NetRoute#mustProxy} 那张表，和 {@code VlcRelay.wrap}
 *       同一个判据。</li>
 * </ol>
 *
 * <p>为什么要收在一处（2026-10-21 一天里踩的两个 403）：
 *
 * <p>这个 App 有两条出站通道 —— {@code AiiHttp}（接口、封面、spider 的 net）和
 * {@code VlcRelay}（播放取流）。两边各自判过一遍，于是同一天出了两个内容不同、
 * 根因相同的故障：
 *
 * <ul>
 *   <li>AGE 动漫：中继把该直连的 CDN 塞回代理节点，CDN 按地区回 403（路由判漏）；</li>
 *   <li>Pornhub：中继没套源声明的 Referer/UA，分片一律 403（请求头判漏）。</li>
 * </ul>
 *
 * <p>两次都是「另一条路没人回头看」。规范文档里写着「headers 是 App 侧（封面、
 * <b>播放取流</b>）发的请求头」，而实现里只有封面接上了 —— 文档描述的是一个
 * 不存在的统一层，现在把它补出来。
 *
 * <p>收进来之后，新加任何一种请求（字幕、解密密钥、封面兜底）只需要在这里确认
 * 一次判据，不必再各自抄一遍。
 */
final class Outbound {

    /**
     * 请求用途。目前不影响判断，只进日志 —— 出问题时能一眼看出是「哪个环节」
     * 被判成了直连/代理，而不是对着一串 URL 猜。
     */
    enum Kind {
        API("接口"),
        COVER("封面"),
        PLAYLIST("播放列表"),
        SEGMENT("分片");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private Outbound() {
    }

    /**
     * 把源声明的 headers 模板落到请求上。
     *
     * <p>规则（逐条都有真机代价，别简化）：
     *
     * <ul>
     *   <li>{@code {origin}} 换成<b>源自己</b>的域名，不是目标 URL 的域名 ——
     *       取封面时目标是图床，发图床的 Referer 会被防盗链 403；</li>
     *   <li>源没声明 origin（spider 源就是这样，站点域写在 js 里）时退回目标 URL 的 origin；</li>
     *   <li>换完是空串、或者光一个 {@code /} 的头<b>不发</b> —— 缺 Referer 是 200，
     *       发个错的反而 403。</li>
     * </ul>
     *
     * <p>调用方先设自己的头、再调这里：同名会被模板覆盖。源写的才是权威 ——
     * VLC 那个 {@code VLC/3.0.x} 的 UA 递到 CDN 面前一眼就被拒。
     *
     * @param profile 当前源；null 什么都不做（首启还没配源时就是 null）
     * @param url     目标地址，只用于 origin 缺失时的兜底
     */
    static void applyHeaders(Request.Builder builder, SiteProfile profile, String url) {
        if (builder == null || profile == null) return;
        try {
            JSONObject template = profile.object("headers");
            if (template == null) return;
            String siteOrigin = profile.origin == null ? "" : profile.origin.trim();
            String origin = siteOrigin.length() > 0 ? siteOrigin : originOf(url);
            Iterator<String> keys = template.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = template.optString(key, "");
                if (value == null || value.length() == 0) continue;
                String filled = value.replace("{origin}", origin);
                if (filled.length() == 0 || filled.equals("/")) continue;
                builder.header(key, filled);
            }
        } catch (Throwable e) {
            log("源请求头模板读取失败，按空头发送：" + e);
        }
    }

    /**
     * 从 URL 提取小写 host。这是**唯一**的 host 提取实现 —— 接口通道（AiiHttp）与
     * 中继通道（VlcRelay）都来这里问，别再各写一份。
     *
     * <p>优先 OkHttp 的 {@code HttpUrl}：它宽松（原始中文、空格、竖线这类未转义
     * 字符都收），而且就是真正发请求的那个解析器 —— 用它判路由，判出来的口径和
     * 请求实际打出去的口径必然一致。它解析不出（无 scheme 等）再退严格
     * {@code java.net.URI}；两条路都失败返回空串，调用方按各自的兜底语义处理
     * （{@link #viaProxy} 是带代理，{@code needsRelayHop} 是不绕中继）。
     *
     * <p>原来中继侧自己 {@code new URI} 判：同一个含未转义字符的地址，AiiHttp
     * 侧经 URL/HttpUrl 判直连、中继侧解析失败被判代理 —— 路由正好相反，症状
     * 是「该直连的 CDN 被塞回境外节点 403」，和 2026-10-21 AGE 那次同型。
     *
     * @param url 目标地址；null 或完全解析不出返回空串
     */
    static String hostOf(String url) {
        if (url == null || url.length() == 0) return "";
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
        if (parsed != null) return parsed.host();
        try {
            String host = new java.net.URI(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 这条地址该不该走代理。
     *
     * <p>判据只有一处：{@link NetRoute#mustProxy}（源声明了 net 就按声明，没声明就
     * 跟全局开关 + DirectHosts 白名单）。中继和接口请求都问它，别各判各的。
     *
     * <p>解析不出 host 时按「带代理」处理：宁可多绕一跳，也别把本该走代理的地址
     * 放成直连 —— 后者是连接超时，比 403 还难解释。
     */
    static boolean viaProxy(String url) {
        if (url == null || url.length() == 0) return false;
        String host = hostOf(url);
        if (host.length() == 0) return true;
        return NetRoute.mustProxy(host);
    }

    /**
     * 协议 + 主机 + 端口，不带路径。
     *
     * <p>取不出返回<b>空串</b>，不是原样返回 —— 原样返回会让 {@code {origin}/}
     * 拼成一个垃圾 Referer 发出去，而 CDN 对错的 Referer 是直接 403 的；
     * 空串会走到「换完是空串就不发」那条，缺 Referer 反而放行。
     */
    static String originOf(String url) {
        try {
            URL parsed = new URL(url);
            String port = parsed.getPort() > 0 ? (":" + parsed.getPort()) : "";
            return parsed.getProtocol() + "://" + parsed.getHost() + port;
        } catch (Exception e) {
            return "";
        }
    }

    private static void log(String message) {
        android.util.Log.d("AIIvideo", "[outbound] " + message);
    }
}
