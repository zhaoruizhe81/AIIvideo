package com.huangguo.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 订阅下载与解析。
 *
 * 「订阅」在网络代理这个圈子里指的是**一个装着很多节点的文本**，格式有三种：
 *
 *   1. 裸节点列表   每行一条 vmess:// / vless:// / trojan:// / ss://
 *   2. base64 包着  整段 base64，解开之后就是上面那种
 *   3. Clash YAML   `proxies:` 下的一串映射
 *
 * 三种都支持。Clash YAML 不引 YAML 库，只按缩进把 `proxies:` 读成一张张扁平表，
 * 再转成上面那两种链接走同一套解析（见 clashToLinks）。
 *
 * ── 为什么不用现成的订阅转换服务 ──
 * 那些服务（subconverter 之类）要跑一个常驻进程，而且会把节点地址过一遍
 * 别人的服务器。下载 + 解析在本地做完，既没有额外依赖，也不用把用户的
 * 节点交给第三方。
 */
final class Subscription {

    /** 单份订阅最多收这么多条。防止有人塞个几万行的文件把 App 卡死。 */
    private static final int MAX_NODES = 400;

    /** 下载上限。正常的订阅都在 200KB 以内，给到 4MB 已经很宽松。 */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private Subscription() {}

    /** 解析结果：成功的节点 + 失败条数与原因摘要。 */
    static final class Result {
        final List<XrayConfig.Node> nodes = new ArrayList<XrayConfig.Node>();
        /** 原样的分享链接，和 nodes 一一对应。存原文是因为「再启动一次」要用它。 */
        final List<String> links = new ArrayList<String>();
        int failed;
        /** 认得协议名但不支持的（hysteria2 / tuic / ssr 之类）。单独计数，
         *  因为这不是「格式坏了」，是「这个协议我们没实现」—— 提示语不一样。 */
        int unsupported;
        /** 不支持的协议名，用于提示（去重，最多几个）。 */
        final java.util.Set<String> unsupportedNames = new LinkedHashSet<String>();
        /**
         * 同一份配置重复出现的条数（机场拿同一条线路复制好几份凑数）。
         * 从结果里剔掉，只计数 —— 全留着的话测速会把同一条测五六遍。
         */
        int duplicates;
        /** 被认成公告的条数（「剩余流量：50GB」「套餐到期」「拆盲盒」这类）。 */
        int notices;
        String firstError;

        int size() {
            return nodes.size();
        }
    }

    /**
     * 下载并解析。
     *
     * 用 AiiHttp.getRaw 而不是自己开连接：下载订阅这件事该走用户配的代理
     * （很多人订阅地址本身就要翻墙才能访问），而 AiiHttp 已经带着 effectiveProxy。
     * 走同一个客户端也省得再处理一遍 DoH / 重试 / 用户代理。
     */
    /**
     * 内置的免费节点。
     *
     * ── 现在这份是**可热更新**的 ──
     * 以前这里直接读 assets/free-nodes.json，意味着打进 APK 的那 178 条链接
     * 会一天天烂掉，而修复手段只有发新版。现在走 NodesFeed：
     * 缓存优先 → 远端 → assets，见那个类的注释。
     *
     * 这个方法的语义没变（「给用户一组开箱即用的节点」），变的是数据来源，
     * 所以调用方一处都不用改。
     *
     * 为什么不用 assets 里直接塞原始订阅文本：那样维护的人得懂 base64，
     * 而且改一条要重算整个编码。JSON 数组是所有人都能直接改的形态。
     */
    static Result builtin(Context context) {
        Result empty = new Result();
        try {
            String text = NodesFeed.text(context);
            if (text == null || text.trim().isEmpty()) {
                empty.firstError = "内置节点文件读不出来";
                return empty;
            }
            JSONObject root = new JSONObject(text);
            JSONArray links = root.optJSONArray("links");
            if (links == null) {
                empty.firstError = "内置节点文件里没有 links 数组";
                return empty;
            }
            StringBuilder joined = new StringBuilder(links.length() * 120);
            for (int i = 0; i < links.length(); i++) {
                String line = links.optString(i, "").trim();
                if (line.length() > 0) joined.append(line).append('\n');
            }
            // 直接复用 parse：去重、逐条校验、统计失败数都是现成的。
            // 这样「内置」和「订阅」走的是同一套解析，不会出现两份行为不一致。
            return parse(joined.toString());
        } catch (Throwable e) {
            String m = e.getMessage();
            empty.firstError = m == null || m.isEmpty()
                    ? "内置节点读不出来（" + e.getClass().getSimpleName() + "）" : m;
            return empty;
        }
    }

    /** 内置节点文件的资产名。 */
    static final String BUILTIN_ASSET = "free-nodes.json";

    static Result fetch(String url, int timeoutSeconds) throws Exception {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("订阅地址是空的");
        }
        String target = url.trim();
        String lower = target.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("订阅地址要以 http:// 或 https:// 开头");
        }

        byte[] body;
        try {
            body = AiiHttp.getOnceRouted(target, timeoutSeconds);
        } catch (Exception first) {
            // 机场的 TTFB 实测能到 9~10 秒，第一次偶尔正好撞上节点抖动。
            // 重试一次比让用户手点第二次强 —— 这条路上失败一次就得重粘地址。
            UserSource.log("订阅第一次没下来（" + first.getMessage() + "），重试一次");
            body = AiiHttp.getOnceRouted(target, timeoutSeconds);
        }
        if (body == null || body.length == 0) {
            throw new Exception("订阅返回空内容");
        }
        if (body.length > MAX_BYTES) {
            throw new Exception("订阅太大了（" + (body.length / 1024) + " KB），可能是网页而不是订阅");
        }
        return parse(new String(body, StandardCharsets.UTF_8));
    }

    /**
     * 解析一段订阅文本。
     *
     * ── 为什么不做 Clash YAML ──
     * Android 上没有 yaml 解析器，为这个引一个依赖不划算；而 Clash 格式的
     * 节点和 base64 格式通常是**同一批**（同一个机场两种导出），拿 base64
     * 那份就够。真遇到只给 YAML 的源，让用户改用它的「V2Ray 订阅」链接即可。
     *
     * 所以这里只认两件事：行分隔的节点链接，以及「整段 base64」。
     */
    static Result parse(String raw) {
        Result result = new Result();
        if (raw == null) return result;

        String text = raw.trim();
        if (text.isEmpty()) return result;

        // Clash 的 YAML：能认出来但明确说不支持，比让它当成「0 个节点」好 ——
        // 后者用户会以为是订阅坏了。
        // Clash YAML：以前直接拒（「请在机场后台改用 V2Ray 订阅」），但很多公开
        // 订阅只给 YAML。转成我们自己的链接格式再走下面同一套解析 —— 不引 YAML
        // 库，只认 proxies: 下面那几个字段。
        String clashHint = null;
        if (looksLikeClash(text)) {
            List<String> converted = clashToLinks(text, result);
            if (!converted.isEmpty()) {
                StringBuilder joined = new StringBuilder();
                for (String link : converted) joined.append(link).append('\n');
                text = joined.toString();
            } else {
                // 有 proxies: 段但一条都没转出来。不急着报错退回 —— 原文里可能
                // 混着裸链接，按普通节点列表再走一遍还能捞回来。一条都捞不到时
                // 用这句兜底，它比 base64 分支那句「不是 base64」更有方向感。
                clashHint = "这是 Clash 配置（YAML），但没有能识别的代理";
            }
        }

        // 整段 base64：解开后如果出现协议前缀，就当节点列表重走一遍。
        if (!containsScheme(text)) {
            String decoded = tryDecodeBase64(text);
            if (decoded != null && containsScheme(decoded)) {
                text = decoded;
            } else if (clashHint != null) {
                // Clash 转换失败且原文也不是节点列表。addClashLink 若记过更具体
                // 的原因就保留它，否则用带方向的那句。
                if (result.firstError == null) result.firstError = clashHint;
                return result;
            } else if (decoded == null) {
                result.firstError = "内容既不是节点列表也不是 base64";
                return result;
            }
        }

        // 去重：同一份订阅里重复节点很常见（多个分组会互相包含）。
        Set<String> seen = new LinkedHashSet<String>();
        // 配置指纹 → 它在 result.nodes 里的下标。重复条目要靠它决定「留哪个名字」。
        java.util.HashMap<String, Integer> keptAt = new java.util.HashMap<String, Integer>();
        for (String line : text.split("\\r?\\n")) {
            if (result.nodes.size() >= MAX_NODES) break;
            String item = line.trim();
            if (item.isEmpty() || item.startsWith("#") || item.startsWith("//")) continue;
            if (!looksLikeLink(item)) {
                // 认得出协议名但不在支持列表里（hysteria2 / tuic / ssr …）。
                // 静默丢掉会让用户以为「我明明有 47 个节点，怎么只出来 23 个」。
                String scheme = schemeOf(item);
                if (scheme != null) {
                    result.unsupported++;
                    if (result.unsupportedNames.size() < 3) result.unsupportedNames.add(scheme);
                }
                continue;
            }
            if (!seen.add(item)) continue;
            try {
                XrayConfig.Node node = XrayConfig.parseShareLink(item);
                // 解析出来还要能生成配置，否则是「解析器认得但 Xray 吃不下」的畸形节点。
                XrayConfig.buildString(node, 20480);
                String signature = signatureOf(node);
                Integer kept = keptAt.get(signature);
                if (kept != null) {
                    // 同一份配置又出现一次。机场很爱把一条线路复制五份、再夹几条
                    // 「剩余流量：50GB」「套餐到期」当节点塞进来 —— 这些条目的
                    // 配置和真节点一模一样，名字才是区别。
                    XrayConfig.Node first = result.nodes.get(kept.intValue());
                    boolean notice = looksLikeNotice(node.name);
                    if (notice) {
                        result.notices++;
                    } else if (looksLikeNotice(first.name)) {
                        // 第一次遇到这份配置时拿到的名字是公告（订阅经常把
                        // 「剩余流量」排在第一条），真名字在后面才出现 —— 用它覆盖，
                        // 否则列表里那条节点就叫「剩余流量：50 GB」。
                        first.name = node.name;
                        result.links.set(kept.intValue(), item);
                        result.notices++;
                    } else {
                        result.duplicates++;
                    }
                    continue;
                }
                keptAt.put(signature, Integer.valueOf(result.nodes.size()));
                result.nodes.add(node);
                result.links.add(item);
            } catch (Throwable e) {
                result.failed++;
                if (result.firstError == null) {
                    String m = e.getMessage();
                    result.firstError = m == null || m.isEmpty()
                            ? e.getClass().getSimpleName() : m;
                }
            }
        }
        // 回退路径一条链接都没捞到、也没有更具体的错：把 Clash 那句方向性提示补上。
        if (result.size() == 0 && result.firstError == null && clashHint != null) {
            result.firstError = clashHint;
        }
        return result;
    }

    /**
     * 一份配置的指纹：协议 + 服务器 + 端口 + 凭据 + 传输层的 path / Host / SNI。
     *
     * 不能用 host:port 当指纹 —— 同一台机器上跑着十个不同 uuid 的节点是正常配置，
     * 那样会把真节点误删。反过来只看协议也不行。这里取的是「连上去以后行为会不会
     * 一样」的那几个字段。
     */
    private static String signatureOf(XrayConfig.Node node) {
        return node.protocol + '|' + node.server + ':' + node.port + '|'
                + node.uuid + '|' + node.network + '|' + node.path + '|' + node.host
                + '|' + node.tls + '|' + node.serviceName
                // sni / flow / reality 三件套 / 指纹同样改握手行为：漏了的话，
                // 同机同 uuid 仅差这几个字段的节点会被误当重复剔掉。口径与
                // ProxyCore.sameNode 一致 —— 那边漏比会拿缺参配置重启核心，
                // 这里漏比会让真节点被去重吞掉。
                + '|' + node.sni + '|' + node.flow + '|' + node.fingerprint
                + '|' + node.publicKey + '|' + node.shortId;
    }

    /**
     * 名字看起来像不像机场的公告条目。
     *
     * 只在「配置与前面某条重复」时才用它 —— 万一某个真节点就叫「到期提醒」，
     * 而它是唯一一条，那它还是节点，照样留着。
     */
    private static boolean looksLikeNotice(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        String[] keys = {"剩余流量", "流量", "到期", "套餐", "订阅", "官网", "客服", "加群",
                "群组", "频道", "电报", "tg", "购买", "续费", "公告", "盲盒", "解锁", "倍率"};
        for (String key : keys) {
            if (n.contains(key)) return true;
        }
        return false;
    }

    private static boolean containsScheme(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        // https:// 必须在这里出现。looksLikeLink() 认它，但这一版漏了 ——
        // 于是一份**全是 https 代理**的节点池会被当成「不是节点列表」，
        // 走 base64 解码那条路然后报「内容既不是节点列表也不是 base64」。
        // 2026-10-18 把免费节点全换成 https 代理之后当场踩中。
        //
        // 这份名单和 looksLikeLink()、XrayConfig.parseShareLink() 三处必须同步。
        // 2026-10-31 加 hy2 时就漏了后两处，一份只有 hy2 的订阅被判成
        // 「内容既不是节点列表也不是 base64」。
        return t.contains("vmess://") || t.contains("vless://")
                || t.contains("trojan://") || t.contains("ss://")
                || t.contains("hy2://") || t.contains("hysteria2://")
                || t.contains("socks5://") || t.contains("socks://")
                || t.contains("http://") || t.contains("https://");
    }

    /** `xxx://...` 里的 `xxx`，取不到返回 null。 */
    private static String schemeOf(String line) {
        int mark = line.indexOf("://");
        if (mark <= 0 || mark > 20) return null;
        String scheme = line.substring(0, mark).toLowerCase(Locale.ROOT);
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')) return null;
        }
        return scheme;
    }

    /**
     * 这一行是不是一条**我们认得并能解析**的节点链接。
     *
     * <p>它和 {@link XrayConfig#parseShareLink} 的支持列表必须同步 —— 不同步
     * 的症状是「协议明明支持了，订阅里却还是被算成不支持」。2026-10-31 就踩了
     * 一次：`hy2://` 加进了解析器，忘了加这里，于是那条 hy2 节点在
     * {@code looksLikeLink} 就被判掉，连解析器都没进，界面上写「1 个不支持（hy2）」。
     */
    private static boolean looksLikeLink(String line) {
        String t = line.toLowerCase(Locale.ROOT);
        return t.startsWith("vmess://") || t.startsWith("vless://")
                || t.startsWith("trojan://") || t.startsWith("ss://")
                || t.startsWith("hy2://") || t.startsWith("hysteria2://")
                || t.startsWith("socks5://") || t.startsWith("socks://")
                || t.startsWith("http://") || t.startsWith("https://");
    }

    /**
     * Clash YAML → 我们的链接格式。
     *
     * 只解 `proxies:` 这一段，字段也只认这几个：type / server / port / tls /
     * username / password / cipher。**不是 YAML 解析器** —— 不处理锚点、多行、
     * 引号转义那套，遇到不认识的行直接跳过。够用就好：公开订阅的 proxies
     * 段就是一堆扁平的 key: value。
     *
     * 认得的 type：
     *   http / https  → http:// 或 https://host:port     （TLS 的就是 https://）
     *   socks5        → socks5://host:port
     *   ss            → ss://method:pass@host:port
     *   trojan        → trojan://pass@host:port?security=tls
     * vmess / vless 在 YAML 里字段更多（ws-opts / grpc-opts 嵌套），这一版不做，
     * 会计进 unsupported，让用户知道「有 N 条没认」。
     */
    static List<String> clashToLinks(String yaml, Result result) {
        List<String> out = new ArrayList<String>();
        boolean inProxies = false;
        java.util.Map<String, String> cur = null;

        for (String rawLine : yaml.split("\r?\n")) {
            String line = rawLine;
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;

            if (!inProxies) {
                if (line.trim().startsWith("proxies:")) inProxies = true;
                continue;
            }
            // 顶格的别的键（proxy-groups: / rules: …）意味着 proxies 段结束
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))
                    && !line.startsWith("-")) {
                inProxies = false;
                continue;
            }

            String t = line.trim();
            if (t.startsWith("- ")) {
                if (cur != null) addClashLink(out, cur, result);
                cur = new java.util.LinkedHashMap<String, String>();
                t = t.substring(2).trim();
                // 行内写法：`- {name: x, server: y, port: 443}` 一行就是一条完整
                // 代理。摊平进 cur 后这条就齐了，直接收尾 —— 不摊平的话下面的
                // key: value 解析会把它切碎成 "{name" 这类垃圾键，整条静默消失。
                if (t.startsWith("{")) {
                    putFlow(cur, "", t);
                    addClashLink(out, cur, result);
                    cur = null;
                    continue;
                }
            }
            if (cur == null || t.isEmpty()) continue;

            int colon = t.indexOf(':');
            if (colon <= 0) continue;
            String key = t.substring(0, colon).trim();
            String value = t.substring(colon + 1).trim();
            if (value.startsWith("{")) {
                // 行内写法（YAML flow mapping）：ws-opts: {path: /ws, headers: {Host: x}}
                // 不展开的话这一行只有一个键 `ws-opts`，pick(p,"path") 拿不到东西。
                putFlow(cur, key, value);
                continue;
            }
            if (value.length() >= 2 && ((value.startsWith("'") && value.endsWith("'"))
                    || (value.startsWith("\"") && value.endsWith("\"")))) {
                value = value.substring(1, value.length() - 1);
            }
            cur.put(key, value);
        }
        if (cur != null) addClashLink(out, cur, result);
        return out;
    }

    /**
     * 摊平一行里的**行内**映射（YAML flow mapping）。
     *
     * <p>上面的循环只认「整行 key: value」，所以
     * {@code ws-opts: {path: /ws, headers: {Host: zhaoruizhe.site}}} 这行进去只有
     * 一个键 {@code ws-opts}，值是那整段花括号文本 —— {@code pick(p, "path")} 自然
     * 取不到，{@code vlessOrVmessLink} 于是不写 {@code &path=}，Xray 按默认的
     * {@code /} 去拨 {@code wss://host/}。
     *
     * <p>2026-10-31 真机就是栽在这里：订阅里 8 条节点全是
     * {@code ws-opts: {path: /ws, …}}，nginx 上只有 {@code location = /ws}，
     * 于是**每一条出站都 404**，而测速因为另一个 bug 一直给绿灯。
     *
     * <p>摊平规则：叶键名进顶层（{@code path} / {@code Host}），带父键的全名也进
     * 一份（{@code ws-opts.path} / {@code headers.Host}）—— {@link #pick} 两种
     * 写法都在试，两边都给最省事。递归深度不设限，切逗号时看花括号/方括号深度，
     * 所以 {@code {path: /ws, headers: {Host: x}}} 里那个逗号不会被切坏。
     */
    private static void putFlow(java.util.Map<String, String> into, String prefix, String value) {
        String v = value.trim();
        while (v.startsWith("{")) v = v.substring(1);
        while (v.endsWith("}")) v = v.substring(0, v.length() - 1);
        for (String part : splitTopLevel(v)) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            String k = unquoteSimple(part.substring(0, colon));
            if (k.isEmpty()) continue;
            String val = part.substring(colon + 1).trim();
            if (val.startsWith("{")) {
                putFlow(into, prefix.isEmpty() ? k : prefix + "." + k, val);
                continue;
            }
            val = unquoteSimple(val);
            into.put(k, val);
            if (!prefix.isEmpty()) into.put(prefix + "." + k, val);
        }
    }

    /** 按顶层逗号切，忽略花括号/方括号里的那些。 */
    private static java.util.List<String> splitTopLevel(String s) {
        java.util.List<String> out = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static String unquoteSimple(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() >= 2) {
            char a = t.charAt(0);
            char b = t.charAt(t.length() - 1);
            if ((a == '\'' && b == '\'') || (a == '"' && b == '"')) {
                return t.substring(1, t.length() - 1);
            }
        }
        return t;
    }

    /** {@code "50 Mbps"} → {@code "50"}；认不出返回空串（调用方据此不写这个键）。 */
    private static String leadingDigits(String value) {
        String t = value == null ? "" : value.trim();
        int end = 0;
        while (end < t.length() && Character.isDigit(t.charAt(end))) end++;
        return end == 0 ? "" : t.substring(0, end);
    }

    private static void addClashLink(List<String> out, java.util.Map<String, String> p, Result result) {
        String type = lower(p.get("type"));
        String server = p.get("server");
        String port = p.get("port");
        if (type.isEmpty() || server == null || server.isEmpty() || port == null) {
            // 认得出是条代理但缺 server/port：这条映射本身是坏的。记进 failed，
            // 别静默消失 —— 用户数着「列表里有 10 条只出来 7 条」却找不到原因最难受。
            result.failed++;
            if (result.firstError == null) {
                result.firstError = "Clash 代理缺 server/port"
                        + (type.isEmpty() ? "" : "（" + type + "）");
            }
            return;
        }

        String name = p.get("name");
        String hash = (name == null || name.isEmpty()) ? "" : "#" + urlEncode(name);
        boolean tls = "true".equalsIgnoreCase(p.get("tls"));
        String user = p.get("username");
        String pass = p.get("password");
        String cred = "";
        if (pass != null && !pass.isEmpty() && !"None".equals(pass)) {
            cred = (user == null || user.isEmpty() ? "" : urlEncode(user) + ":") + urlEncode(pass) + "@";
        }

        if ("http".equals(type) || "https".equals(type) || "http-proxy".equals(type)) {
            String scheme = (tls || "https".equals(type)) ? "https" : "http";
            out.add(scheme + "://" + cred + server + ":" + port + hash);
            return;
        }
        if ("socks5".equals(type) || "socks".equals(type)) {
            out.add("socks5://" + cred + server + ":" + port + hash);
            return;
        }
        if ("hysteria2".equals(type) || "hy2".equals(type)) {
            String password = pick(p, "password", "auth", "auth-str");
            if (password.isEmpty()) {
                // 缺密码的映射转出去也连不上，记进 failed 让摘要对得上总数。
                result.failed++;
                if (result.firstError == null) result.firstError = "hysteria2 节点缺密码";
                return;
            }
            // 核心（v2fly v5 的 transport/internet/hysteria2）没有 obfs 实现，
            // Config 只有 password / congestion / ignore_client_bandwidth /
            // use_udp_extension 四项。带 salamander 的节点转出去也连不上，还会
            // 让用户看着「测得通」却上不了网 —— 明确记账，别静默吞掉。
            String obfs = lower(pick(p, "obfs", "obfs-type"));
            if (!obfs.isEmpty() && !"none".equals(obfs)) {
                result.unsupported++;
                if (result.unsupportedNames.size() < 3) {
                    result.unsupportedNames.add("hysteria2(" + obfs + ")");
                }
                return;
            }
            StringBuilder q = new StringBuilder();
            String sni = pick(p, "sni", "servername");
            if (!sni.isEmpty()) q.append("&sni=").append(urlEncode(sni));
            if ("true".equalsIgnoreCase(pick(p, "skip-cert-verify"))) q.append("&insecure=1");
            String up = leadingDigits(pick(p, "up", "up-mbps"));
            String down = leadingDigits(pick(p, "down", "down-mbps"));
            if (!up.isEmpty()) q.append("&up=").append(up);
            if (!down.isEmpty()) q.append("&down=").append(down);
            out.add("hy2://" + urlEncode(password) + "@" + server + ":" + port
                    + (q.length() == 0 ? "" : "?" + q.substring(1)) + hash);
            return;
        }
        if ("ss".equals(type)) {
            String cipher = p.get("cipher");
            if (cipher == null || cipher.isEmpty()) {
                // 缺加密方式的映射同样记账，别静默消失。
                result.failed++;
                if (result.firstError == null) result.firstError = "ss 节点缺加密方式（cipher）";
                return;
            }
            // 密码必须编码：带 @ / # 时会把后面那段错切成 host（hy2 分支同款写法）。
            out.add("ss://" + cipher + ":" + (pass == null ? "" : urlEncode(pass))
                    + "@" + server + ":" + port + hash);
            return;
        }
        if ("trojan".equals(type)) {
            out.add("trojan://" + (pass == null ? "" : urlEncode(pass)) + "@" + server + ":" + port
                    + "?security=tls" + hash);
            return;
        }
        if ("vless".equals(type) || "vmess".equals(type) || "v2ray".equals(type)) {
            String link = vlessOrVmessLink(type, p, server, port, hash);
            // 缺 uuid 之类的不算「协议不支持」—— 这条映射本身是坏的，
            // 记进 failed 才和摘要里的「看不懂被跳过」对得上。
            if (link != null) out.add(link);
            else result.failed++;
            return;
        }
        // 认得出类型但这一版不转的（hysteria2 / tuic / ssr …）。
        // 它们不是「格式没写好」，是 XrayConfig 里没有对应的 outbound。
        result.unsupported++;
        if (result.unsupportedNames.size() < 3) result.unsupportedNames.add(type);
    }

    /**
     * Clash 的 vmess / vless 映射 → 同协议的分享链接。
     *
     * ── 为什么这两条非做不可 ──
     * Clash Verge、CMFA、机场后台的「Clash 订阅」里，vmess 和 vless 占了绝大多数。
     * 只转 ss/trojan/http 的话，用户把一份正常的 Clash 配置导进来会看到「0 个节点」，
     * 然后以为是订阅坏了。
     *
     * ── 字段名映射 ──
     * Clash 用连字符、分享链接用驼峰或缩写，两边对不上，逐个抄在这里：
     *   network            → type
     *   servername / sni   → sni
     *   client-fingerprint → fp
     *   reality-opts.public-key / short-id → pbk / sid（嵌套层会被摊平成顶层键，
     *                        见 clashToLinks 的注释：解析器只认「最后一段冒号前的键」）
     *   ws-opts.path / ws-opts.headers.Host → path / host
     *   grpc-service-name  → serviceName
     *   cipher（vmess）    → scy
     *
     * 返回值 null 表示这条映射缺关键字段（没 uuid 或者没 server），静默跳过即可。
     */
    private static String vlessOrVmessLink(String type, java.util.Map<String, String> p,
                                           String server, String port, String hash) {
        String uuid = pick(p, "uuid", "id", "password");
        if (uuid.isEmpty()) return null;

        String network = lower(pick(p, "network", "net"));
        if (network.isEmpty()) network = "tcp";
        // h2 在分享链接里写 h2，Clash 里写 http —— 统一成前者。
        if ("http".equals(network)) network = "h2";

        String publicKey = pick(p, "public-key", "pbk");
        boolean reality = !publicKey.isEmpty();
        boolean tlsOn = "true".equalsIgnoreCase(pick(p, "tls")) || reality;
        String sni = pick(p, "servername", "sni");
        String wsHost = pick(p, "host", "Host", "ws-host", "headers.Host");
        String wsPath = pick(p, "path");
        String grpcName = pick(p, "grpc-service-name", "servicename");
        // vmess 的 grpc 把 serviceName 写在 path 上，和其它协议的习惯一致。
        if ("grpc".equals(network) && grpcName.isEmpty() && !wsPath.isEmpty()) {
            grpcName = wsPath;
        }

        if ("vmess".equals(type) || "v2ray".equals(type)) {
            JSONObject json = new JSONObject();
            try {
                json.put("v", "2");
                json.put("ps", p.get("name") == null ? "" : p.get("name"));
                json.put("add", server);
                json.put("port", port);
                json.put("id", uuid);
                // alterId 的键名在 Clash 里是 alterId，机场偶尔写 alterid。
                String aid = pick(p, "alterId", "alterid", "aid");
                json.put("aid", aid.isEmpty() ? "0" : aid);
                String scy = pick(p, "cipher");
                json.put("scy", scy.isEmpty() ? "auto" : scy);
                json.put("net", network);
                json.put("type", "none");
                json.put("host", wsHost);
                json.put("path", "grpc".equals(network) ? grpcName : wsPath);
                json.put("tls", tlsOn ? "tls" : "none");
                json.put("sni", sni);
            } catch (Exception e) {
                return null;
            }
            // parseVmess 只吃 base64(JSON)，不吃明文。
            String body = java.util.Base64.getEncoder()
                    .encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
            return "vmess://" + body + hash;
        }

        StringBuilder q = new StringBuilder("encryption=none");
        q.append("&security=").append(reality ? "reality" : (tlsOn ? "tls" : "none"));
        q.append("&type=").append(network);
        if (!wsPath.isEmpty() && !"/".equals(wsPath) && !"grpc".equals(network)) {
            q.append("&path=").append(urlEncode(wsPath));
        }
        if (!wsHost.isEmpty()) q.append("&host=").append(urlEncode(wsHost));
        if (!sni.isEmpty()) q.append("&sni=").append(urlEncode(sni));
        String fp = pick(p, "client-fingerprint");
        if (!fp.isEmpty()) q.append("&fp=").append(urlEncode(fp));
        if (reality) {
            q.append("&pbk=").append(urlEncode(publicKey));
            String sid = pick(p, "short-id", "sid");
            if (!sid.isEmpty()) q.append("&sid=").append(urlEncode(sid));
        }
        String flow = pick(p, "flow");
        if (!flow.isEmpty()) q.append("&flow=").append(urlEncode(flow));
        if (!grpcName.isEmpty()) q.append("&serviceName=").append(urlEncode(grpcName));

        return "vless://" + urlEncode(uuid) + "@" + server + ":" + port + "?" + q + hash;
    }

    /** 按顺序取第一个非空的键。Clash 各家的键名写法不统一，全都试一遍。 */
    private static String pick(java.util.Map<String, String> p, String... keys) {
        for (String key : keys) {
            String value = p.get(key);
            if (value == null || value.isEmpty() || "None".equals(value)) continue;
            String trimmed = value.trim();
            if (trimmed.length() >= 2 && ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                    || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    private static String lower(String v) {
        return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
    }

    private static String urlEncode(String v) {
        try {
            return java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return v;
        }
    }

    private static boolean looksLikeClash(String text) {
        // 必须真的有 proxies: 这一行才算。以前用 contains 做全文子串判定，一条
        // 备注里带「rules:」字样的普通订阅会被误判成 Clash，然后整份按 YAML
        // 解析丢光。逐行看行首（允许缩进）—— 没有 proxies: 段的文本里根本没有
        // 代理可转，proxy-groups / rules 等其余键不构成依据。
        for (String line : text.split("\r?\n")) {
            if (line.trim().toLowerCase(Locale.ROOT).startsWith("proxies:")) return true;
        }
        return false;
    }

    /**
     * 试着按 base64 解。解不出或解出来不是文本就返回 null。
     *
     * 不抛异常：这个函数是「猜」的，猜不中很正常，调用方据此决定下一步。
     */
    private static String tryDecodeBase64(String text) {
        try {
            String compact = text.replaceAll("\\s", "");
            while (compact.length() % 4 != 0) compact = compact + "=";
            byte[] raw = java.util.Base64.getDecoder()
                    .decode(compact.replace('-', '+').replace('_', '/'));
            String decoded = new String(raw, StandardCharsets.UTF_8);
            // 解出来一堆替换字符说明这根本不是 base64 文本
            if (decoded.indexOf('\uFFFD') >= 0) return null;
            return decoded;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 给 UI 看的一句话摘要。 */
    static String summary(Result result) {
        if (result == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(result.size()).append(" 个节点");
        if (result.duplicates > 0) sb.append("，").append(result.duplicates).append(" 条重复已合并");
        if (result.notices > 0) sb.append("，").append(result.notices).append(" 条公告已忽略");
        if (result.failed > 0) sb.append("，").append(result.failed).append(" 个看不懂被跳过");
        if (result.unsupported > 0) {
            sb.append("，").append(result.unsupported).append(" 个不支持（");
            boolean first = true;
            for (String name : result.unsupportedNames) {
                if (!first) sb.append("/");
                sb.append(name);
                first = false;
            }
            sb.append("）");
        }
        return sb.toString();
    }

    static List<XrayConfig.Node> emptyList() {
        return Collections.emptyList();
    }
}
