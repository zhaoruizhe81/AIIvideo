package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 解析：系统 DNS、DoH、配置里钉死的 IP，三者在 {@link SmartDns} 里排优先级。
 *
 * <p>2026-10-23 从 {@link AiiHttp} 拆出来。它原来是那个类里的两段（DNS 那 267 行 +
 * 磁盘缓存那 60 行），跟传输、路由、代理挤在一起；现在搬出来只依赖
 * {@code Context}（SharedPreferences）、{@link SiteProfile} 和 okhttp，没别的。
 *
 * <p>行为没动：日志仍走 {@code AiiHttp.log}（同一个 {@code [http]} 前缀，
 * logcat 里按时间对照的用法不变），缓存键与 TTL 也没动。
 */
final class AiiHttpDns {

    private static final int DOH_TIMEOUT = 5;
    private static final long TTL_OK = 10 * 60 * 1000L;
    private static final long TTL_FAIL = 60 * 1000L;

    /** DoH 查询共用的底座 client（超时在此定）；查询时只换 DNS，复用连接池。 */
    private static final OkHttpClient DOH_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(DOH_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DOH_TIMEOUT, TimeUnit.SECONDS)
            .build();

    /** 解析缓存：host → IP 列表 + 到期时刻。 */
    private static final class Entry {
        final List<String> ips;
        final long expireAt;
        Entry(List<String> ips, long expireAt) { this.ips = ips; this.expireAt = expireAt; }
    }

    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<String, Entry>();

    /** 磁盘缓存要 SharedPreferences；由 {@link AiiHttp#init} 一并传进来。 */
    private static volatile Context appContext;

    static void init(Context context) {
        if (context != null && appContext == null) appContext = context.getApplicationContext();
    }

    /**
     * 这个 client 身上的解析器是不是我们的。
     *
     * <p>{@code AiiHttp} 里那条「已经有自定义解析就别再盖一层」的判据原来写的是
     * {@code instanceof SmartDns}；拆出来之后 SmartDns 不再可见，收进这个方法来问。
     */
    static boolean hasOwnDns(Dns dns) {
        return dns instanceof SmartDns;
    }

    /** 给 okhttp 的解析钩子。profile 为 null 时退回系统解析。 */
    static Dns dns(SiteProfile profile) {
        return new SmartDns(profile);
    }

    /**
     * client 缓存键里 DNS 那一段的身份串。
     *
     * <p>SmartDns 的行为由 profile 决定（钉死的 hosts / DoH 配置全挂在上面），
     * 只记类名的话所有源共用同一个键 —— A 源的请求会拿到按 B 源配置建好的 client。
     * 所以 SmartDns 按 profile 的 fingerprint 区分；别的实现退化成类全名。
     */
    static String identity(Dns dns) {
        if (dns instanceof SmartDns) {
            SiteProfile p = ((SmartDns) dns).profile;
            return "SmartDns:" + (p == null || p.fingerprint == null ? "" : p.fingerprint);
        }
        return dns.getClass().getName();
    }

    private static void log(String message) {
        AiiHttp.log(message);
    }

    // ── DoH 结果的磁盘缓存（③） ───────────────────────────────────────
    //
    // 内存里那份 TTL 十分钟，但**冷启动就没了**，每次都要重问一遍 DoH —— 而 DoH
    // 在整条链的最前面，它慢 300ms 后面全跟着慢。落到 SharedPreferences 之后
    // 冷启动直接拿上次的地址开连，同时把这次的结果写回去。
    //
    // TTL 给 30 分钟：Cloudflare 的 anycast 地址不会几分钟就换，但也不是永久 ——
    // 域名换 CDN、运营商的就近路由变了都会让旧地址变差。过了期就重新问。
    private static final String DNS_STORE = "aii-dns";
    private static final long DISK_TTL = 30 * 60 * 1000L;
    private static final String SEP = "\u0001";



    // ── DoH 结果的磁盘缓存 ────────────────────────────────────────────

    private static SharedPreferences store() {
        Context context = appContext;
        return context == null ? null : context.getSharedPreferences(DNS_STORE, Context.MODE_PRIVATE);
    }

    private static void remember(String host, List<String> ips) {
        SharedPreferences store = store();
        if (store == null || ips == null || ips.isEmpty()) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ips.size(); i++) {
                if (sb.length() > 0) sb.append(';');
                sb.append(ips.get(i));
            }
            store.edit().putString(host, System.currentTimeMillis() + ";" + sb).apply();
        } catch (Throwable ignored) {}
    }

    private static List<String> recall(String host) {
        SharedPreferences store = store();
        if (store == null) return null;
        try {
            String raw = store.getString(host, null);
            if (raw == null) return null;
            String[] parts = raw.split(";");
            if (parts.length < 2) return null;
            if (System.currentTimeMillis() - Long.parseLong(parts[0]) > DISK_TTL) return null;
            List<String> ips = new ArrayList<String>();
            for (int i = 1; i < parts.length; i++) if (parts[i].length() > 0) ips.add(parts[i]);
            return ips.isEmpty() ? null : ips;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 磁盘里那份直接拿来用，同时**后台重问一次**。
     *
     * 不这么做的话，一旦地址真的变了，用户会一直连到旧地址上失败 —— 磁盘这份
     * 是「省一次 DoH」，不是「永远信它」。后台刷新保证下一次冷启动拿到的是新的。
     */
    private static void refreshInBackground(final String host, final SiteProfile profile) {
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<String> fresh = lookupDoh(host, profile);
                    if (fresh.isEmpty()) return;
                    CACHE.put(host, new Entry(fresh, System.currentTimeMillis() + TTL_OK));
                    remember(host, fresh);
                    log("DoH 后台刷新 " + host + " → " + fresh.size() + " 个地址");
                } catch (Throwable ignored) {}
            }
        }, "aii-doh-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * OkHttp 的解析钩子。返回**多个**地址时 OkHttp 会自己轮着试 ——
     * 这是选 OkHttp 而不是 HttpURLConnection 的另一个理由：后者只试第一个。
     */
    private static final class SmartDns implements Dns {
        private final SiteProfile profile;
        SmartDns(SiteProfile profile) { this.profile = profile; }

        @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            // ── 配置里钉死的地址优先 ──────────────────────────────────────
            // 有些入口域名，三条解析路径给出的都是不能用的答案：
            //   · 运营商 DNS  → 一个证书已过期的 IP
            //   · 阿里 DoH    → 0.0.0.0
            //   · 腾讯 DoH    → 5 个真地址，但只有一两个偶尔能连上
            // 这种时候任何「解析」都是碰运气，唯一能做的是把能用那个写死。
            // 配置顶层写：  "hosts": { "例子.com": ["1.2.3.4", "5.6.7.8"] }
            List<String> pinned = pinned(hostname, profile);
            // 钉 IP 是「优先提示」不是「唯一答案」（2026-10-19 教训）：
            // 之前 pinned 命中就直接 return —— 钉的 IP 哪天过期（站点换 CDN 是
            // 常态），域名就安静地死掉，没有任何回落。现在解析出全部候选后把
            // 钉住的地址排在**最前面**，OkHttp 按顺序试：钉 IP 活着就是最快路径，
            // 死了自动落回 DoH/系统解析，慢一点但不会再死。
            List<InetAddress> pinnedAddrs = new ArrayList<InetAddress>();
            if (!pinned.isEmpty()) {
                for (int i = 0; i < pinned.size(); i++) {
                    try { pinnedAddrs.add(InetAddress.getByName(pinned.get(i))); } catch (Exception ignored) {}
                }
            }
            if (pinnedAddrs.isEmpty()) {
                // 没有可用钉 IP：走原有路径（DoH/系统）并原样返回。
                List<InetAddress> plain = resolveFallback(hostname, profile);
                if (plain != null) return plain;
                return Dns.SYSTEM.lookup(hostname);
            }
            List<InetAddress> resolved = resolveFallback(hostname, profile);
            if (resolved == null || resolved.isEmpty()) {
                log("钉住 " + hostname + " → " + pinnedAddrs.size() + " 个地址（解析无结果，直接用钉 IP）");
                return pinnedAddrs;
            }
            // 钉 IP 在前，解析结果在后；去重后交给 OkHttp 按序试。
            List<InetAddress> out = new ArrayList<InetAddress>(pinnedAddrs.size() + resolved.size());
            out.addAll(pinnedAddrs);
            for (InetAddress addr : resolved) {
                if (!out.contains(addr)) out.add(addr);
            }
            log("钉住 " + hostname + " → " + pinnedAddrs.size() + " 个优先 + 解析 "
                    + resolved.size() + " 个候选");
            return out;
        }
    }

    /**
     * DoH/系统解析的老路径，从 lookup 里拆出来给「钉 IP 优先 + 回落」复用。
     * 返回 null 表示 DoH 没开（调用方走系统解析）；空表 = DoH 无结果（同样回落）。
     */
    private static List<InetAddress> resolveFallback(String hostname, SiteProfile profile) throws UnknownHostException {
        if (!dohEnabled(profile)) return null;
        List<String> ips = resolve(hostname, profile);
        if (ips.isEmpty()) return null;
        List<InetAddress> out = new ArrayList<InetAddress>(ips.size());
        for (int i = 0; i < ips.size(); i++) {
            try {
                out.add(InetAddress.getByName(ips.get(i)));
            } catch (Exception e) {
                log("地址无法构造：" + ips.get(i));
            }
        }
        return out;
    }

    /**
     * 配置里有没有给这个域名钉死地址。
     *
     * 读的是顶层 `hosts`：`{ "域名": ["1.2.3.4", …] }`。空/没写都返回空表，
     * 走原来的 DoH 那条路 —— 这是个例外通道，不该影响别的源。
     */
    private static List<String> pinned(String host, SiteProfile profile) {
        List<String> out = new ArrayList<String>();
        if (profile == null || host == null) return out;
        try {
            JSONObject hosts = profile.object("hosts");
            if (hosts == null) return out;
            JSONArray list = hosts.optJSONArray(host);
            if (list == null) return out;
            for (int i = 0; i < list.length(); i++) {
                String ip = list.optString(i, "").trim();
                if (ip.length() > 0 && usable(ip)) out.add(ip);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    static List<String> resolve(String host, SiteProfile profile) {
        Entry cached = CACHE.get(host);
        if (cached != null && System.currentTimeMillis() < cached.expireAt) return cached.ips;

        // 内存里没有就翻磁盘。冷启动那一次 DoH 在整条链的最前面，
        // 省掉它等于把首屏的起跑线往前挪 200~500ms。
        List<String> fromDisk = recall(host);
        if (fromDisk != null) {
            CACHE.put(host, new Entry(fromDisk, System.currentTimeMillis() + TTL_OK));
            log("DoH 磁盘命中 " + host + " → " + fromDisk.size() + " 个地址");
            refreshInBackground(host, profile);
            return fromDisk;
        }

        List<String> fromDoh = lookupDoh(host, profile);
        long ttl = fromDoh.isEmpty() ? TTL_FAIL : TTL_OK;
        CACHE.put(host, new Entry(fromDoh, System.currentTimeMillis() + ttl));
        if (!fromDoh.isEmpty()) remember(host, fromDoh);
        return fromDoh;
    }

    private static List<String> lookupDoh(String host, SiteProfile profile) {
        JSONArray servers = dohServers(profile);
        LinkedHashSet<String> merged = new LinkedHashSet<String>();
        if (servers == null) return new ArrayList<String>(merged);

        // 多问一家再合并：实测阿里和腾讯在同一时刻会给不同答案（就近路由），
        // 只信一家的结果集可能不全，漏掉的那个恰好能用就亏了。
        for (int i = 0; i < servers.length(); i++) {
            JSONObject server = servers.optJSONObject(i);
            if (server == null) continue;
            try {
                merged.addAll(queryOne(server, host, profile));
            } catch (Exception e) {
                log("DoH " + server.optString("name", "?") + " 失败：" + e);
            }
            if (merged.size() >= 3) break;
        }
        return new ArrayList<String>(merged);
    }

    /**
     * 查一家 DoH。这里走的是**另一条 Dns**：把该服务商的域名钉死到配置里的 IP。
     *
     * 这正是 OkHttp 好用的地方 —— URL 写的是 https://dns.alidns.com/resolve，
     * 域名还在，所以 SNI 和证书校验都是对的；只有「解析」被换成了配置里的地址。
     * 换成 HttpURLConnection 就得自己拼 SNI，也就是之前失败的那条路。
     */
    private static List<String> queryOne(final JSONObject server, final String host, SiteProfile profile)
            throws Exception {
        String url = server.optString("url", "");
        JSONArray ips = server.optJSONArray("ips");
        if (url.length() == 0 || ips == null || ips.length() == 0) throw new IOException("DoH 配置不完整");

        final List<InetAddress> pinned = new ArrayList<InetAddress>();
        for (int i = 0; i < ips.length(); i++) {
            String ip = ips.optString(i, "");
            if (ip.length() == 0) continue;
            try { pinned.add(InetAddress.getByName(ip)); } catch (Exception ignored) {}
        }
        if (pinned.isEmpty()) throw new IOException("DoH 的 ips 全是无效地址");

        // 钉死的解析器：只认配置里的地址，不查系统。DoH 服务器必须先把 IP 写好，
        // 否则「解析一个域名」的前提是「先解析 DoH 服务器的域名」，递归了。
        Dns pinnedDns = new Dns() {
            @Override public List<InetAddress> lookup(String hostname) {
                return pinned;
            }
        };

        // DoH 查询的共享 client：连接池/执行器只建这一份，各服务商只覆写
        // 自己的钉死解析。之前每次查询都 new 一个全量 client 且用完不关，
        // 线程和连接都在悄悄泄漏。
        OkHttpClient dohClient = DOH_CLIENT.newBuilder().dns(pinnedDns).build();

        String endpoint = url + (url.indexOf('?') >= 0 ? "&" : "?") + "name=" + host + "&type=A";
        Request request = new Request.Builder()
                .url(endpoint)
                .header("accept", "application/dns-json")
                .build();

        Response response = dohClient.newCall(request).execute();
        try {
            if (!response.isSuccessful()) throw new IOException("DoH HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) return new ArrayList<String>();
            return parseAnswers(body.string());
        } finally {
            response.close();
        }
    }

    /** 解析 RFC 8484 的 JSON 应答：只看 Answer[] 里 type==1 的。 */
    /** 包内可见：解析与筛选是纯函数，单测直接喂应答体。 */
    static List<String> parseAnswers(String body) {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray answer = new JSONObject(body).optJSONArray("Answer");
            if (answer == null) return out;
            for (int i = 0; i < answer.length(); i++) {
                JSONObject record = answer.optJSONObject(i);
                if (record == null) continue;
                if (record.optInt("type", 0) != 1) continue;
                String data = record.optString("data", "");
                // 只要 IPv4：这条链路上没通 v6，返回去反而让 OkHttp 白试一轮。
                if (data.length() == 0 || data.indexOf(':') >= 0) continue;
                if (!usable(data)) continue;
                if (!out.contains(data)) out.add(data);
            }
        } catch (Exception e) {
            log("DoH 应答解析失败：" + e);
        }
        return out;
    }

    /**
     * 这个 A 记录能不能拿去连。
     *
     * ── 为什么必须筛 ────────────────────────────────────────────────────
     * 实测阿里 DoH（dns.alidns.com）对 `cab3f.nt17g20k.cc` 的应答是
     * **`0.0.0.0`** —— 那不是「没查到」，那是运营商的毒答案，把它当地址用
     * 只会去连一个必然失败的地方。
     *
     * 而 lookupDoh 是「多问一家再合并」，阿里先回 0.0.0.0 会让它凑不满 3 个，
     * 于是继续问腾讯、把腾讯那几个真地址也加进来 —— 结果列表变成
     * `[0.0.0.0, 43.229.39.228, …]`，**毒答案排在第一个**，每次请求都先白试
     * 一遍它。筛掉之后 OkHttp 才会从能用那个开始试。
     */
    /** 包内可见：同上，毒答案的判据要能被断言。 */
    static boolean usable(String ip) {
        if (ip == null) return false;
        String s = ip.trim();
        if (s.length() == 0) return false;
        if ("0.0.0.0".equals(s) || "127.0.0.1".equals(s)) return false;
        if (s.startsWith("0.") || s.startsWith("127.")) return false;
        // 255.255.255.255 之类：首字节全 1 的广播地址
        if (s.startsWith("255.")) return false;
        return true;
    }

    private static JSONArray dohServers(SiteProfile profile) {
        JSONObject doh = dohConfig(profile);
        return doh == null ? null : doh.optJSONArray("servers");
    }

    private static JSONObject dohConfig(SiteProfile profile) {
        if (profile == null) return null;
        try {
            return profile.object("doh");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * DoH 默认关闭。当前 origin（cab3f.nt17g20k.cc）在手机系统解析上是干净的，
     * 五个入口 IP 也全部可达，开着只是多一跳延迟。
     * 什么时候该打开：origin 换成被污染的域名（比如 huangguoai.com）之后，
     * 至少还能拿到完整的地址列表而不是系统给的那一个。
     */
    private static boolean dohEnabled(SiteProfile profile) {
        JSONObject doh = dohConfig(profile);
        return doh != null && doh.optBoolean("enabled", false);
    }
}
