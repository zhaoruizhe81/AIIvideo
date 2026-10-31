package com.huangguo.mobile;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Locale;

import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 代理路由的值对象 —— 一串文本解析之后的唯一形态。
 *
 * ── 为什么需要这个类 ────────────────────────────────────────────────
 * 2026-10-18 之前，代理在系统里有两种表达：**字符串**（{@code "socks5://u:p@h:1"}
 * 存在 prefs 里、在 {@link UserSource} 里被比来比去）和 **{@link Proxy} + 账号密码
 * 三元组**（在 {@code AiiHttp} 内部临时拼出来）。两者之间的翻译散在六七处：
 *
 * <pre>
 *   parseProxy(String) → Route{proxy,user,pass}     AiiHttp 私有
 *   attachAuth(builder, route)                      每个建客户端的地方都调一次
 *   describe(route)                                 打日志
 *   effectiveJavaProxy()                            给 WebView 用，只取 proxy 那半
 * </pre>
 *
 * 边界靠约定：谁知道该在哪一步校验、哪一步挂认证，全看调用方记不记得。
 * 于是出现过两种不大但难查的错：认不出来的串被静默当直连（请求悄悄不走代理），
 * 以及 SOCKS 认证没挂上（服务端只回「authentication failed」）。
 *
 * 现在只有一种表达：**字符串进来，{@code ProxyRoute} 出去，之后没人再碰字符串**。
 * 校验、挂认证、打日志、给 WebView 取 {@link Proxy} 都在这一个类里。
 *
 * ── 不可变 ──────────────────────────────────────────────────────────
 * 全部字段 final，{@link #applyTo} 只读不改。这样它可以安全地当缓存键、
 * 可以跨线程传，也不会出现「某个调用方顺手改了 port」这种事。
 */
final class ProxyRoute {

    enum Kind { HTTP, SOCKS }

    final Kind kind;
    final String host;
    final int port;
    /** null 表示不需要认证。**不要打日志**：这是明文凭据。 */
    private final String user;
    private final String pass;

    private ProxyRoute(Kind kind, String host, int port, String user, String pass) {
        this.kind = kind;
        this.host = host;
        this.port = port;
        this.user = user;
        this.pass = pass == null ? "" : pass;
    }

    // ── 解析 ──────────────────────────────────────────────────────────

    /**
     * 解析结果。三态：直连 / 合法路由 / 认不出来（带一句给人看的错）。
     *
     * 三态是必要的 —— {@code setProxy("")} 是合法的（表示「清掉，直连」），
     * 而 {@code setProxy("乱写")} 必须报错。只返回 null 的话两者分不开，
     * 用户填错了会静默变直连，然后「明明填了代理还是连不上」。
     */
    static final class Parsed {
        final ProxyRoute route;
        final String error;
        private Parsed(ProxyRoute route, String error) { this.route = route; this.error = error; }

        boolean direct() { return route == null && error == null; }
        boolean ok() { return route != null || error == null; }
    }

    private static final Parsed DIRECT = new Parsed(null, null);

    /**
     * 认这些写法（管道类型看 scheme，不写默认 HTTP）：
     * <pre>
     *   host:port
     *   socks5://host:port        socks:// 也认
     *   user:pass@host:port
     *   http://user:pass@host:port
     *   socks5://user:pass@host:port
     * </pre>
     */
    static Parsed parse(String raw) {
        if (raw == null) return DIRECT;
        String text = raw.trim();
        if (text.length() == 0) return DIRECT;

        String bad = "格式看不懂。可以填 host:port、socks5://host:port，"
                + "带账号密码写成 user:pass@host:port";

        Kind kind = Kind.HTTP;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("socks5://") || lower.startsWith("socks://")) {
            kind = Kind.SOCKS;
            text = text.substring(text.indexOf("://") + 3);
        } else if (lower.startsWith("http://")) {
            text = text.substring(7);
        } else if (lower.startsWith("https://")) {
            // 以前这里是剥掉 scheme 按明文 HTTP 代理用 —— 那等于拿明文 CONNECT
            // 去怼一个 TLS 端口，连不上还看不出是这层意思。明确拒绝，别静默错走。
            return new Parsed(null, "暂不支持 TLS 包裹的 HTTP 代理（https:// 前缀），"
                    + "请填 http:// 或 socks5:// 形式的地址");
        }

        // 账号密码在 @ 之前。用 lastIndexOf：密码里带 @ 也不怕
        // （user:p@ss@host:port 要切成 user:p@ss 和 host:port）。
        String user = null;
        String pass = "";
        int at = text.lastIndexOf('@');
        if (at >= 0) {
            String credentials = text.substring(0, at);
            text = text.substring(at + 1);
            int sep = credentials.indexOf(':');
            if (sep < 0) {
                user = credentials;
            } else {
                user = credentials.substring(0, sep);
                pass = credentials.substring(sep + 1);
            }
            if (user.length() == 0) return new Parsed(null, bad);
        }

        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) return new Parsed(null, bad);
        String host = text.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(text.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return new Parsed(null, bad);
        }
        // host 里不能有斜杠和空白。少了这一条，「://1.2.3.4:1080」这种残片会被
        // 当成 host 收下（createUnresolved 不做校验），然后在发请求时炸成一句
        // 看不出所以然的错。宁可在这里拒绝。
        if (port <= 0 || port > 65535 || host.length() == 0) return new Parsed(null, bad);
        if (host.indexOf('/') >= 0 || host.indexOf('\\') >= 0) return new Parsed(null, bad);
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) return new Parsed(null, bad);
        }
        return new Parsed(new ProxyRoute(kind, host, port, user, pass), null);
    }

    // ── 对外形态 ──────────────────────────────────────────────────────

    /** java.net.Proxy。给 OkHttp 和 {@code SiteFetchWebView} 的 ProxyController 用。 */
    Proxy javaProxy() {
        Proxy.Type type = kind == Kind.SOCKS ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(type, InetSocketAddress.createUnresolved(host, port));
    }

    boolean needsAuth() { return user != null && user.length() > 0; }

    /**
     * 缓存键。<b>不含密码</b> —— 它会被放进 client 缓存表的 key 里，
     * 而那张表可能被日志打出来。
     */
    String key() {
        return kind + "|" + host + ":" + port + "|" + (needsAuth() ? user : "-");
    }

    /** 日志用。**不打印账号密码** —— 这行会进 logcat，而凭据是明文的。 */
    String describe() {
        return kind + " " + host + ":" + port + (needsAuth() ? "（带认证）" : "");
    }

    // ── 挂到客户端上 ──────────────────────────────────────────────────

    /**
     * 把这个路由挂到 builder 上，含认证。
     *
     * 认证有两个口子，取决于管道类型 —— 这件事以前在 AiiHttp 里，
     * 每个建客户端的地方都要自己记得调一次：
     * <ul>
     *   <li>HTTP 代理：OkHttp 的 {@code proxyAuthenticator}</li>
     *   <li>SOCKS：只能挂 {@code java.net.Authenticator}，见 {@link #applySocksAuth}</li>
     * </ul>
     */
    void applyTo(OkHttpClient.Builder builder) {
        builder.proxy(javaProxy());
        if (!needsAuth()) return;
        if (kind == Kind.HTTP) {
            builder.proxyAuthenticator((okhttp3.Route r, Response response) -> {
                if (response.request().header("Proxy-Authorization") != null) return null;
                Request rebuilt = response.request().newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(user, pass))
                        .build();
                return rebuilt;
            });
        } else {
            applySocksAuth();
        }
    }

    /**
     * SOCKS 代理的认证。
     *
     * OkHttp 只给了 HTTP 代理的钩子（proxyAuthenticator），SOCKS 那条路走的是
     * java.net.Socket 内部，认证要挂全局的 java.net.Authenticator —— 没有别的口子。
     * 设之前先看 requestorType，只回代理那一路的凭据，别把别的认证也一并接管了。
     */
    private void applySocksAuth() {
        try {
            final String u = user;
            final String p = pass;
            java.net.Authenticator.setDefault(new java.net.Authenticator() {
                @Override protected java.net.PasswordAuthentication getPasswordAuthentication() {
                    // ⚠ Java 的 SOCKS 实现用的是 RequestorType.SERVER，**不是 PROXY**。
                    // 按直觉写成 `!= PROXY 就 return null` 的话，凭据永远回不去，
                    // 服务端只会收到空认证，报「SOCKS : authentication failed」——
                    // 而同一个密码用 curl -x socks5h:// 完全通，很容易怀疑到密码头上去。
                    // 判断条件用「协议 + 目标地址」，比 RequestorType 可靠，
                    // 也不会顺手把别的认证请求接管了。
                    // 实测日志：type=SERVER proto=SOCKS5 host=64.64.224.121:19108
                    if (!"SOCKS5".equalsIgnoreCase(String.valueOf(getRequestingProtocol()))) return null;
                    if (!String.valueOf(getRequestingHost()).equals(host)) return null;
                    if (getRequestingPort() != port) return null;
                    return new java.net.PasswordAuthentication(u, p.toCharArray());
                }
            });
        } catch (Throwable e) {
            UserSource.log("SOCKS 认证挂不上（" + e.getClass().getSimpleName() + "），"
                    + "带账号的 SOCKS 代理会连不上");
        }
    }
}
