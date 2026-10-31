package com.huangguo.mobile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 网络路由的**唯一决策点** —— 「这个地址该走代理还是直连」只在这里回答。
 *
 * ── 为什么要有这个类 ────────────────────────────────────────────────
 * 2026-10-18 接樱花动漫，播放全线 403 / TLS 被掐。根因不是适配器：内置核心
 * 开在荷兰节点，而这个站的播放 CDN 全在国内 —— 走外网节点反而被掐。当时的
 * 修法是往 {@link DirectHosts} 里逐个手填域名，一共填了 16 个。
 *
 * 填完立刻看出这是错的方向：**站点的采集站域名天天换**，白名单永远追不上，
 * 而且下一家站点还得再填一遍。真正的规律是反向的 ——
 *
 *   墙只挡「站点的入口域」，播放 CDN 九成在境内，直连最快。
 *
 * 所以路由应该跟着**源**走，缺省直连：
 *
 * <pre>
 *   "net": { "proxy": ["yhdm.one"] }
 * </pre>
 *
 * 有 {@code net.proxy} → 只有表里这些域走代理，其余（封面、分片、新的 CDN）全直连。
 * 没有 → 维持老行为：{@link DirectHosts} 白名单命中直连，其余跟全局开关。
 *
 * 后者必须留着：AGE动漫、91porn 那些源是在这套机制之前接的，它们依赖
 * 「全局代理 + 白名单例外」的语义。
 *
 * ── 谁问它 ────────────────────────────────────────────────────────
 * 和 {@link DirectHosts} 一样是三个出口，但只回答一个问题：
 *
 * <pre>
 *   AiiHttp     rawWith / getWith / getOnce / plainClient   → 要不要挂代理
 *   VlcRelay    wrap()                                       → 要不要绕中继
 *   XrayConfig  routing()                                    → 不走这条（核心启动时生成）
 * </pre>
 *
 * Xray 那条出口**故意不接**：核心的 routing 是启动时固化的，加进来只会造成
 * 「配置改了但核心不知道」的新漂移。不需要它 —— 走 SOCKS 的只有 OkHttp 判定
 * 该走代理的流量，判定在 OkHttp 那一步已经做完了。
 */
final class NetRoute {

    /**
     * 一次 {@link #configure} 的不可变快照：代理域表 + 是否声明，两件事绑在一个
     * 对象里，用**一个** volatile 引用发布。之前是两个 volatile 各写各的，读侧
     * 可能拿到「新表 + 旧 declared」的撕裂视图 —— directOnly 与 mustProxy 会
     * 对同一个域给出相反的判定。
     */
    private static final class Snapshot {
        final List<String> proxyHosts;
        final boolean declared;
        Snapshot(List<String> proxyHosts, boolean declared) {
            this.proxyHosts = proxyHosts;
            this.declared = declared;
        }
    }

    private static volatile Snapshot snapshot = new Snapshot(new ArrayList<String>(), false);

    /**
     * 当前源声明的代理域名表（未声明返回空表）。给 {@link XrayConfig#routing} 用：
     * 核心侧的 routing 规则要把这些域放到 proxy 出站，且优先级压过 geosite:cn。
     *
     * <p>为什么从静态表抄而不走参数：SiteProfile 构造时 {@link #configure} 已经装好
     * 了这张表（全 App 唯一的组装点），核心启动时读一次即可，不值得为它把 build()
     * 的签名一路改穿 ProxyConsole。
     */
    static List<String> declaredProxyHosts() {
        return new ArrayList<String>(snapshot.proxyHosts);
    }

    private NetRoute() {}

    /**
     * 装载一个源的路由声明。{@link SiteProfile} 构造时调 —— 那是整个 App
     * 里唯一一处「当前源长什么样」的组装点，别在别处再造第二份。
     *
     * @param net    源配置里的 net 块，可以为 null（null = 不声明，用老规则）
     * @param origin 站点根，兜底加进去：接口域永远该跟源同路，忘写就是全站连不上
     */
    static void configure(org.json.JSONObject net, String origin) {
        List<String> hosts = new ArrayList<String>();
        boolean has = false;
        if (net != null) {
            org.json.JSONArray list = net.optJSONArray("proxy");
            if (list != null) {
                has = true;
                for (int i = 0; i < list.length(); i++) {
                    String h = list.optString(i, "").trim().toLowerCase(Locale.ROOT);
                    if (h.length() > 0) hosts.add(h);
                }
            }
            if (net.optBoolean("declared", false)) has = true;
        }
        // origin 兜底进代理表 —— **只在作者真的列了代理域时**。
        //
        // 2026-10-20 真机（黄果）：源写的是 `{declared:true, proxy:[]}`，按规范
        // §7.1 那是「显式全直连」。但这里原来无条件把 origin 塞进表里，于是
        // huangguoai.com 被判成「必须代理」，日志里 `路由 → 代理` 和配置上写的
        // 「全直连」正好相反。当时的表现是每次请求 62 秒超时。
        //
        // 空表不是「忘了写」，是「一个都不想代理」—— 这两种语义必须分开：
        //   proxy: ["yhdm.one"]  → 列了的走代理；origin 若漏了补上（防手误）
        //   proxy: []            → 全直连，origin 也不碰
        // `net` 整个不写（has=false）时依旧走老规则，这条兜底不参与。
        if (has && !hosts.isEmpty() && origin != null) {
            String host = hostOf(origin);
            if (host.length() > 0 && !hosts.contains(host)) hosts.add(host);
        }
        // 整份快照一次发布：读侧要么看到旧的一对，要么看到新的一对。
        snapshot = new Snapshot(hosts, has);
    }

    /**
     * 从 URL 取小写 host。取不到返回空串。
     *
     * 这一段是本类唯一的字符串活，所以就地写 —— 不借用任何适配器的静态方法。
     * 之前这里调的是 {@code YhdmSite.hostOf}：路由层反过来依赖一个具体适配器，
     * 意味着「删掉樱花动漫」会连带编译不过，而它们本来毫无关系。
     */
    private static String hostOf(String url) {
        try {
            String h = new java.net.URI(url.trim()).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** 当前源有没有声明路由。没声明时调用方退回老规则。 */
    static boolean declared() {
        return snapshot.declared;
    }

    /**
     * 同 {@link #declared()}，给 {@link AiiHttp} 的路由自愈（方案 A）用：
     * 声明了 net 块的源，{@code mustProxy=false} 的域就是作者要的直连——
     * 那是显式决定，连接层失败也不该被路由记忆改写。
     */
    static boolean isDeclared() {
        return snapshot.declared;
    }

    /**
     * 这个 host 是否必须直连（摘掉代理、绕开中继）。
     *
     * <p>判定顺序：
     * <ol>
     *   <li>源声明了 net.proxy → 在表里才走代理，其余全部直连</li>
     *   <li>源没声明 → 命中 {@link DirectHosts} 直连，其余按全局开关</li>
     * </ol>
     *
     * @param host 小写域名
     */
    static boolean directOnly(String host) {
        if (host == null || host.length() == 0) return false;
        String h = host.toLowerCase(Locale.ROOT).trim();
        // 同一次判定里表和 declared 必须出自同一份快照。
        Snapshot s = snapshot;
        boolean direct;
        if (s.declared) {
            // 「声明了要代理，但根本没代理可用」按直连处理 —— 理由见 proxyUnavailable。
            direct = !matches(s.proxyHosts, h) || proxyUnavailable(s, h);
        } else {
            direct = DirectHosts.isDirect(h);
        }
        trace(h, direct);
        return direct;
    }

    /**
     * 这个 host 是不是「源说要走代理，但眼下根本没有代理可走」。
     *
     * ── 为什么需要这一条 ──────────────────────────────────────────────
     * 2026-10-19 真机：装好 yhdm 源，首页转 45 秒超时，日志只有
     *
     * <pre>
     *   [source] net yhdm.one → 代理 profile=有 mode=direct proxy=空
     * </pre>
     *
     * {@code mode=direct} 是全局设置（默认值），而源的 net.proxy 说 yhdm.one
     * 必须走代理。于是两边互相甩锅：
     *
     * <ul>
     *   <li>{@link #directOnly} 说「不在直连表 → 不该直连」（false）</li>
     *   <li>{@link #mustProxy} 说「在代理表 → 该走代理」（true）</li>
     *   <li>但 {@code effectiveProxy()} 是空串，谁也给不出一个代理</li>
     * </ul>
     *
     * 结果 {@code rawWith} 走到 {@code mustProxy && proxy == null} 那条兜底，
     * 去问 {@code proxiedClient(null, …)}；而 direct 模式下 {@code routeForProxyHost()}
     * 同样返回 null。请求就这么空着，直到调用方超时。
     *
     * <p><b>裁决规则</b>：没有代理可用时，直连是唯一能发出去的方式 —— 可以失败，
     * 但不能什么都不做。所以这种情况按直连处理，并在日志里说清楚
     * 「这里本该走代理，但没有代理」——否则用户看到的是「这个源坏了」，
     * 而真相是「你没开代理」。
     */
    static boolean proxyUnavailable(String host) {
        return proxyUnavailable(snapshot, host);
    }

    private static boolean proxyUnavailable(Snapshot s, String host) {
        if (host == null || host.length() == 0) return false;
        if (!s.declared) return false;                  // 没声明 → 老规则自己会处理
        String h = host.toLowerCase(Locale.ROOT).trim();
        if (!matches(s.proxyHosts, h)) return false;    // 不在代理表，本来就该直连
        return AiiHttp.effectiveProxy().length() == 0;
    }

    /**
     * 每个 host 只打一次路由日志。
     *
     * 这一行看着多余，但今天排查那 75 秒超时全靠它：日志里只有
     * 「Failed to connect to yhdm.one/[IPv6]」，看不出是路由判错了还是代理没生效。
     * 有了这一行，「这个域走的是直连还是代理」就是明确的。
     *
     * 用 ConcurrentHashMap 而不是 synchronized Set：这条路径是热路径
     * （每个请求都走），锁争用会拖慢首屏。
     */
    private static final java.util.Map<String, Boolean> LOGGED =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

    private static void trace(String host, boolean direct) {
        if (LOGGED.putIfAbsent(host, direct) == null) {
            UserSource.log("路由 " + host + " → " + (direct ? "直连" : "代理"));
        }
    }

    /**
     * 这个 host 是否**必须**走代理（源声明了 net.proxy 且在表里）。
     *
     * <p>VlcRelay 用它：只有明确该走代理的地址才值得绕一趟本地中继，
     * 直连的直接把原始地址交给 VLC，省一跳。
     */
    static boolean mustProxy(String host) {
        if (host == null || host.length() == 0) return false;
        String h = host.toLowerCase(Locale.ROOT).trim();
        Snapshot s = snapshot;
        if (s.declared) return matches(s.proxyHosts, h);
        // 没声明：沿用老语义 —— 只要全局代理开着，非直连域就该走代理。
        if (AiiHttp.effectiveProxy().length() == 0) return false;
        return !DirectHosts.isDirect(h);
    }

    /** 子域后缀匹配。表里写 yhdm.one，www.yhdm.one 也算。 */
    private static boolean matches(List<String> list, String host) {
        for (int i = 0; i < list.size(); i++) {
            String entry = list.get(i);
            if (host.equals(entry) || host.endsWith("." + entry)) return true;
        }
        return false;
    }

    /** 给日志用：当前路由的一句话摘要。 */
    static String describe() {
        Snapshot s = snapshot;
        if (!s.declared) return "未声明（老规则：DirectHosts + 全局开关）";
        return "代理域 " + s.proxyHosts.size() + " 个，其余直连：" + s.proxyHosts;
    }
}
