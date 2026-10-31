package com.huangguo.mobile;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 首屏预热与它的两种缓存：在飞的那一次（{@link Warmup}）、已经落下的那份（{@link Prefetch}）。
 *
 * <p>2026-10-23 从 {@link AiiHttp} 拆出来。原来它散在四段：常量在文件头、{@code get()}
 * 里一段等待逻辑、{@code warmup()} 与三个私有函数在文件尾。四段之间只通过 static
 * 字段耦合，拆出来之后 {@code AiiHttp.get()} 只剩一行「有就拿来，没有就自己发」。
 *
 * <p>取数据那条路通过 {@link Fetcher} 注进来，所以这里不认识 okhttp、也不认识路由：
 * 换源代次（{@link #GENERATION}）照样生效，行为与拆之前一致。
 */
final class AiiHttpCache {

    /** 预热取数的那一下。实现由 {@link AiiHttp} 提供（{@code fetchUncached}）。 */
    interface Fetcher {
        byte[] fetch(String url, SiteProfile profile) throws Exception;
    }

    private static final String WARMUP_PATH = "/api/home";
    private static final long PREFETCH_TTL = 60 * 1000L;

    private static final class Prefetch {
        final byte[] body;
        final long at;
        Prefetch(byte[] body) { this.body = body; this.at = System.currentTimeMillis(); }
        boolean fresh() { return System.currentTimeMillis() - at < PREFETCH_TTL; }
    }
    private static final ConcurrentHashMap<String, Prefetch> PREFETCH = new ConcurrentHashMap<String, Prefetch>();

    // 正在飞的那一次预热。前端来要同一条时**等它**，而不是另开一次请求。
    //
    // 第一版没这个，结果是两路并发：前端发一次真的、预热发一次，谁先回来用谁的。
    // 实测前端的真请求在预热开始后约 300ms 就发出去了（闸门和 __source:web 那几步
    // 很快），而预热要跑 1080ms —— 于是真请求先完成，预热白跑一趟，字节丢掉。
    // 等它的话总共只发一次请求，拿到的还是最早开始的那次。
    private static final class Warmup {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final long at = System.currentTimeMillis();
        volatile byte[] body;
        /** 跟 Prefetch 一个道理：这份字节只在 PREFETCH_TTL 内算数。 */
        boolean fresh() { return System.currentTimeMillis() - at < PREFETCH_TTL; }
    }
    private static final ConcurrentHashMap<String, Warmup> WARMING = new ConcurrentHashMap<String, Warmup>();
    private static final int WARMUP_WAIT_SECONDS = 12;

    /**
     * 换源计数器。预热线程开工时记下代次，回来对不上就把字节丢掉。
     *
     * 预热是后台线程，跑完的时候源可能已经换了（换源只重建 Activity，进程还活着）。
     * 不做这一下的话，旧源那几百毫秒后落进 PREFETCH，新源的首页正好拿去用。
     */
    private static final java.util.concurrent.atomic.AtomicInteger GENERATION =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * 预热有没有现成的字节。等过在飞的那一次（12 秒），没有就取已经落下的那份。
     * 返回 null 表示没有，调用方照常发请求。
     */
    static byte[] take(String url) {
        final String hotKey = prefetchKey(url);
        Warmup inflight = WARMING.get(hotKey);
        if (inflight != null) {
            long waited = System.currentTimeMillis();
            boolean settled = false;
            try {
                settled = inflight.done.await(WARMUP_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (settled && inflight.body != null && inflight.fresh()) {
                // 消费式摘除：这份字节只服务这一次，后续来要的走下面 PREFETCH（同样
                // 一次性）。不摘的话条目在 fresh 窗口内一直躺着 —— settle 过的 latch
                // await 秒回，60 秒内每次 get 都回这份启动快照，前端下拉刷新拿到的
                // 也是旧字节，PREFETCH 的 remove-on-take 等于白设计。
                WARMING.remove(hotKey, inflight);
                log("等到预热结果 " + pathOfUrl(url) + " " + inflight.body.length + " 字节，等了 "
                        + (System.currentTimeMillis() - waited) + "ms");
                return inflight.body;
            }
            log("预热没等到（settled=" + settled + "），自己发一次");
        }

        Prefetch hot = PREFETCH.remove(hotKey);
        if (hot != null) {
            if (hot.fresh()) {
                log("预热结果直接命中 " + pathOfUrl(url) + " " + hot.body.length + " 字节");
                return hot.body;
            }
            log("预热结果已过期，照常请求 " + pathOfUrl(url));
        }
        return null;
    }

    static void warmup(final SiteProfile profile, final Fetcher fetcher) {
        if (profile == null) return;
        final String path = warmPath(profile);
        if (path == null) return;                                // 没有可预热的路径（spider 源）
        final String hotKey = prefetchKey(path);
        final int gen = GENERATION.get();
        Prefetch cached = PREFETCH.get(hotKey);
        if (cached != null && cached.fresh()) return;            // 刚预热过，别重复
        Warmup previous = WARMING.get(hotKey);
        if (previous != null) {
            if (previous.fresh()) return;                        // 在飞的 / 刚完成的
            WARMING.remove(hotKey, previous);                    // 过期的腾位子
        }
        final Warmup warm = new Warmup();
        if (WARMING.putIfAbsent(hotKey, warm) != null) return;   // 已经有人在预热了
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    long started = System.currentTimeMillis();
                    byte[] body = prefetchBody(profile, fetcher);
                    // 代次对不上 = 跑到一半换源了，这份字节属于上一个源，丢掉。
                    // 不丢的话它几百毫秒后落进 PREFETCH，新源的首页正好拿去用。
                    if (body != null && body.length > 0 && gen == GENERATION.get()) {
                        warm.body = body;
                        PREFETCH.put(hotKey, new Prefetch(body));
                        log("预热完成 " + path + " " + body.length + " 字节 "
                                + (System.currentTimeMillis() - started) + "ms");
                        // 成功收尾顺手清扫：跨源/跨代次的残留不会自己消失
                        //（take 只按 key 精确摘除），就在这里一并处理。
                        sweepStale();
                    } else if (body != null && body.length > 0) {
                        log("预热作废（期间换过源）" + body.length + " 字节");
                    }
                } catch (Throwable e) {
                    log("预热失败（前端会照常发真请求）：" + e.getClass().getSimpleName() + " " + e.getMessage());
                } finally {
                    // 无论成败都要放行，否则等在这条上的人会一直等到超时。
                    warm.done.countDown();
                }
            }
        }, "aii-warmup");
        worker.setDaemon(true);
        worker.start();
    }

    /** 包内可见：键归一化是纯函数，{@code get()} 与预热共用同一条规则。 */
    static String prefetchKey(String url) {
        if (url == null) return "";
        if (url.startsWith("/api/")) return url;
        int scheme = url.indexOf("://");
        if (scheme < 0) return url;
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url : url.substring(slash);
    }

    /** PREFETCH 的容量上限。超过时按落入时间留最新这几份。 */
    private static final int PREFETCH_KEEP = 4;

    /**
     * 预热成功收尾的顺手清扫。
     *
     * <ul>
     *   <li>WARMING：在飞记录只在 take/warmup 里按 key 精确摘除，换源之后旧 key
     *       再也没人来取，过期的条目就一直躺着 —— 这里把 fresh()==false 的清掉
     *       （remove(key, value) 只删还是原来那个的，不伤并发中新增的）。</li>
     *   <li>PREFETCH：按 key（路径）累积，旧源首页不会被取走只会越攒越多；
     *       超过 {@link #PREFETCH_KEEP} 份时按时间留最新几份，其余移除。</li>
     * </ul>
     */
    private static void sweepStale() {
        for (java.util.Map.Entry<String, Warmup> e : WARMING.entrySet()) {
            if (!e.getValue().fresh()) WARMING.remove(e.getKey(), e.getValue());
        }
        if (PREFETCH.size() <= PREFETCH_KEEP) return;
        java.util.ArrayList<java.util.Map.Entry<String, Prefetch>> all =
                new java.util.ArrayList<java.util.Map.Entry<String, Prefetch>>(PREFETCH.entrySet());
        // 排序基于各自的时间戳，与 ConcurrentHashMap 的遍历顺序无关。
        java.util.Collections.sort(all, new java.util.Comparator<java.util.Map.Entry<String, Prefetch>>() {
            @Override public int compare(java.util.Map.Entry<String, Prefetch> a,
                                         java.util.Map.Entry<String, Prefetch> b) {
                return Long.compare(b.getValue().at, a.getValue().at);
            }
        });
        for (int i = PREFETCH_KEEP; i < all.size(); i++) {
            PREFETCH.remove(all.get(i).getKey(), all.get(i).getValue());
        }
    }

private static byte[] prefetchBody(SiteProfile profile, Fetcher fetcher) throws Exception {
        String path = warmPath(profile);
        if (path == null) throw new IOException("这个源没有可预热的首页（spider 源）");
        // ⚠️ **只预热首页。** 有些站的播放地址带一次性签名、会过期，预热出来的
        //    地址等用户真去点的时候可能已经废了 —— 那种地址必须实时取。
        //    首页没有这个问题（它只是元数据），预热是安全的。
        // 绝对地址直接用，相对路径才拼 origin —— 和 SiteRepository.detail 那条规矩一致。
        if (path.startsWith("https://")) return fetcher.fetch(path, profile);
        String origin = profile == null ? null : profile.origin;
        if (origin == null || origin.length() == 0) throw new IOException("配置里没有 origin，不预热");
        return fetcher.fetch(origin + path, profile);
    }

private static String warmPath(SiteProfile profile) {
        try {
            JSONObject web = profile == null ? null : profile.object("web");
            JSONObject paths = web == null ? null : web.optJSONObject("paths");
            String home = paths == null ? "" : paths.optString("home", "");
            if (home.length() > 0) return home;
            // spider 源没有 REST 首页。老代码在这里回落 /api/home，于是每次启动都朝
            // spider 宿主白敲一条不存在的路径（dozagonza 实测
            //   预热失败（前端会照常发真请求）：IOException HTTP 404
            //   @ https://www.dozagonza.com/api/home
            // ），白付一次 TLS + 一次 404，还往日志里塞噪音。没写 home 就当没有。
            Object spider = web == null ? null : web.opt("spider");
            boolean hasSpider = spider != null && spider != JSONObject.NULL
                    && String.valueOf(spider).length() > 0;
            if (hasSpider) return null;
        } catch (Throwable ignored) {}
        return WARMUP_PATH;
    }

static int[] reset() {
        int wasWarming = WARMING.size();
        int wasPrefetch = PREFETCH.size();
        GENERATION.incrementAndGet();
        WARMING.clear();
        PREFETCH.clear();
        return new int[]{wasWarming, wasPrefetch};
    }

    private static String pathOfUrl(String url) {
        try { return new java.net.URL(url).getPath(); } catch (Exception e) { return url; }
    }

    private static void log(String message) {
        AiiHttp.log(message);
    }
}
