package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封面观测集：哪些图床主机「裸拉成功过」，按源隔离地记在这里。
 *
 * <p>══════ 它解决什么 ══════════════════════════════════════════════
 * {@code allowedHosts} 是加源那一刻的配置快照，而图床的二级域六个随机字符
 * 两周一轮换 —— 名单跟不上轮换，一次抖动按死整页五分钟（2.0.28 的教训）。
 * 这里的输入不是人拍的名单，是源自己的响应：C 档（名单外）主机裸拉成功一次，
 * 就升进观测集，后续按 B 档带源 header 正常取。名单退化为安全地板，
 * 「我就是要钉死这一族」的人才去动配置。
 *
 * <p>══════ 为什么它不会变成「第二次把事实存死」 ══════════════════
 * 方案（docs/history/2026-10-31-封面信任模型改造方案.md §4.2）的三件套：
 * <b>有界</b>（32 条，满了删 lastSeenAt 最老的）、<b>有 TTL</b>（7 天未命中
 * 即清理）、<b>失败降级</b>（连续 3 次失败降回 C 档重新裸拉验证，不直接删 ——
 * 直接删会把「网络抖三下」放大成「重新裸拉还可能撞防盗链」）。
 *
 * <p>══════ 存储形态 ══════════════════════════════════════════════
 * SharedPreferences 文件 {@code cover-trust}，每个源 fingerprint 一个键。
 * 用 prefs 而不是文件：「恢复出厂设置」的 {@code clearPreferences()} 遍历
 * shared_prefs 目录，新文件免登记自动清掉；静态内存层另给 reset 口子。
 * 只在主进程读写（coverData 在 coverRequests 池里），没有 RouteMemory 那种
 * 跨进程诉求，不需要 mtime 检查那套。全部方法锁类实例 —— 封面是 2 线程并发的。
 *
 * <p>时钟可注入（带 now 的包可见重载），持久化在 context == null 时自动跳过
 * —— 单测在纯 JVM 上跑，不打 Android。
 */
final class CoverTrustStore {

    /** 观测集容量，按源计。满了删 lastSeenAt 最老的。 */
    static final int CAPACITY = 32;
    /** 多久没命中就算过期。7 天 = 图床一轮换族周期的量级，过期后重新裸拉。 */
    static final long TTL_MS = 7L * 24 * 3600_000L;
    /** 连续失败多少次降回 C 档。 */
    static final int FAIL_LIMIT = 3;

    private static final String PREFS = "cover-trust";
    /** 静态内存层：fingerprint → (host → entry)。懒加载自 prefs。 */
    private static final Map<String, Map<String, Entry>> BY_SOURCE = new LinkedHashMap<>();
    private static SharedPreferences prefs;
    /** 盘上所有源是否已一次性读进内存。换源是 recreate，静态内存层跨 Activity 存活。 */
    private static boolean diskLoaded;

    private static final class Entry {
        long firstSeenAt;
        long lastSeenAt;
        int ok;
        int fail;
        boolean trusted;
    }

    private CoverTrustStore() {
    }

    /**
     * 这个主机现在是不是 B 档（观测信任、未过期）。
     * 过期条目惰性剔除 —— 下一次写入时自然被 TTL 分支带走，不单独扫表。
     */
    static boolean isObserved(Context context, String fingerprint, String host) {
        if (fingerprint == null || host == null || host.length() == 0) return false;
        synchronized (CoverTrustStore.class) {
            Entry e = sourceFor(context, fingerprint).get(host.toLowerCase(java.util.Locale.ROOT));
            return e != null && e.trusted
                    && System.currentTimeMillis() - e.lastSeenAt <= TTL_MS;
        }
    }

    /**
     * 记一条「裸拉成功了」：C 档升 B（或给降级过的主机重新验明正身）。
     *
     * @return 进入前观测集里有没有这台主机 —— 首次进入时调用方上报一条
     *         {@code cover_host_trusted}，服务端据此看轮换族，不用人翻日志。
     */
    static boolean record(Context context, String fingerprint, String host) {
        if (fingerprint == null || host == null || host.length() == 0) return false;
        String key = host.toLowerCase(java.util.Locale.ROOT);
        synchronized (CoverTrustStore.class) {
            Map<String, Entry> source = sourceFor(context, fingerprint);
            long now = System.currentTimeMillis();
            Entry existing = source.get(key);
            boolean first = existing == null;
            Entry e = existing != null ? existing : new Entry();
            if (first) {
                e.firstSeenAt = now;
                // 满了先淘汰再插入，保证容量不超。删 lastSeenAt 最老的：最近还在
                // 用的轮换主机不该被一个三年前的僵尸挤出去。
                while (source.size() >= CAPACITY) dropOldest(source);
            }
            e.lastSeenAt = now;
            e.ok++;
            e.fail = 0;
            e.trusted = true;
            source.put(key, e);
            persist(fingerprint, source);
            return first;
        }
    }

    /** B 档取数成功：刷新命中时间，失败计数清零。 */
    static void recordOk(Context context, String fingerprint, String host) {
        mutate(context, fingerprint, host, new Mutate() {
            @Override void apply(Entry e, long now) {
                e.lastSeenAt = now;
                e.ok++;
                e.fail = 0;
            }
        });
    }

    /** B 档取数失败：连续 FAIL_LIMIT 次降回 C 档（重新裸拉验证），条目保留。 */
    static void recordFail(Context context, String fingerprint, String host) {
        mutate(context, fingerprint, host, new Mutate() {
            @Override void apply(Entry e, long now) {
                e.lastSeenAt = now;
                e.fail++;
                if (e.fail >= FAIL_LIMIT) {
                    e.trusted = false;
                    e.fail = 0;
                }
            }
        });
    }

    /** 换源：这个源的观测集整份作废（方案 §4.2「换源 reset」）。 */
    static void reset(Context context, String fingerprint) {
        if (fingerprint == null) return;
        synchronized (CoverTrustStore.class) {
            BY_SOURCE.remove(fingerprint);
            SharedPreferences p = prefsOf(context);
            if (p != null) {
                p.edit().remove(fingerprint).apply();
            }
        }
    }

    /** 清空（恢复出厂的静态内存层兜底；prefs 那份由 clearPreferences() 目录遍历顺带清）。 */
    static void resetAll(Context context) {
        synchronized (CoverTrustStore.class) {
            BY_SOURCE.clear();
            diskLoaded = true; // prefs 已 clear，重读只会拿到空表，别再扫一遍。
            SharedPreferences p = prefsOf(context);
            if (p != null) {
                p.edit().clear().apply();
            }
        }
    }

    /** 诊断：这个源的观测集快照（含降级中的条目）。 */
    static JSONArray dump(Context context, String fingerprint) throws org.json.JSONException {
        JSONArray out = new JSONArray();
        synchronized (CoverTrustStore.class) {
            for (Map.Entry<String, Entry> e : sourceFor(context, fingerprint).entrySet()) {
                Entry v = e.getValue();
                out.put(new JSONObject()
                        .put("host", e.getKey())
                        .put("firstSeenAt", v.firstSeenAt)
                        .put("lastSeenAt", v.lastSeenAt)
                        .put("ok", v.ok)
                        .put("fail", v.fail)
                        .put("trusted", v.trusted));
            }
        }
        return out;
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private abstract static class Mutate {
        abstract void apply(Entry e, long now);
    }

    private static void mutate(Context context, String fingerprint, String host, Mutate mutation) {
        if (fingerprint == null || host == null || host.length() == 0) return;
        String key = host.toLowerCase(java.util.Locale.ROOT);
        synchronized (CoverTrustStore.class) {
            Map<String, Entry> source = sourceFor(context, fingerprint);
            Entry e = source.get(key);
            if (e == null) {
                // 没记录过的主机失败不该凭空造一条 B 档档案 —— C 档的失败由
                // 负缓存（SiteRepository.rememberFailure）管，这里只管观测集。
                return;
            }
            mutation.apply(e, System.currentTimeMillis());
            persist(fingerprint, source);
        }
    }

    /** 懒加载：把盘上所有源的观测集一次性读进内存。context == null 时跳过（单测）。 */
    private static Map<String, Map<String, Entry>> loaded(Context context) {
        if (!diskLoaded && context != null) {
            synchronized (CoverTrustStore.class) {
                if (!diskLoaded) {
                    SharedPreferences p = prefsOf(context);
                    if (p != null) {
                        java.util.Map<String, ?> all = p.getAll();
                        for (Map.Entry<String, ?> kv : all.entrySet()) {
                            BY_SOURCE.put(kv.getKey(), parse(String.valueOf(kv.getValue())));
                        }
                    }
                    diskLoaded = true;
                }
            }
        }
        return BY_SOURCE;
    }

    /** 取（或建）某个源的观测集视图。所有公开方法都经它，保证不为 null。 */
    private static Map<String, Entry> sourceFor(Context context, String fingerprint) {
        Map<String, Entry> source = loaded(context).get(fingerprint);
        if (source == null) {
            source = new LinkedHashMap<>();
            BY_SOURCE.put(fingerprint, source);
        }
        return source;
    }

    private static Map<String, Entry> parse(String json) {
        Map<String, Entry> out = new LinkedHashMap<>();
        try {
            JSONObject root = new JSONObject(json);
            Iterator<String> keys = root.keys();
            long now = System.currentTimeMillis();
            while (keys.hasNext()) {
                String host = keys.next();
                JSONObject one = root.optJSONObject(host);
                if (one == null) continue;
                Entry e = new Entry();
                e.firstSeenAt = one.optLong("firstSeenAt", 0);
                e.lastSeenAt = one.optLong("lastSeenAt", 0);
                e.ok = one.optInt("ok", 0);
                e.fail = one.optInt("fail", 0);
                e.trusted = one.optBoolean("trusted", false);
                // 落盘时就过期的条目直接不进内存 —— 省得 isObserved 每次都判。
                if (now - e.lastSeenAt > TTL_MS) continue;
                out.put(host, e);
            }
        } catch (Throwable ignored) {
            // 坏了当空表：观测集坏了只影响「要不要再裸拉一次」，不影响出图。
            out.clear();
        }
        return out;
    }

    private static void persist(String fingerprint, Map<String, Entry> source) {
        SharedPreferences p = prefs;
        if (p == null) return;
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Entry> e : source.entrySet()) {
                Entry v = e.getValue();
                root.put(e.getKey(), new JSONObject()
                        .put("firstSeenAt", v.firstSeenAt)
                        .put("lastSeenAt", v.lastSeenAt)
                        .put("ok", v.ok)
                        .put("fail", v.fail)
                        .put("trusted", v.trusted));
            }
            p.edit().putString(fingerprint, root.toString()).apply();
        } catch (Throwable ignored) {
            // 写失败只丢记忆，不影响请求本身。
        }
    }

    private static SharedPreferences prefsOf(Context context) {
        if (prefs == null && context != null) {
            prefs = context.getApplicationContext().getSharedPreferences(PREFS, 0);
        }
        return prefs;
    }

    private static void dropOldest(Map<String, Entry> source) {
        String oldest = null;
        long at = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> e : source.entrySet()) {
            if (e.getValue().lastSeenAt < at) {
                at = e.getValue().lastSeenAt;
                oldest = e.getKey();
            }
        }
        if (oldest != null) source.remove(oldest);
    }

    /** 只给单测：清静态内存 + 掐掉 prefs 引用，用例之间不互相污染。 */
    static void resetForTest() {
        synchronized (CoverTrustStore.class) {
            BY_SOURCE.clear();
            diskLoaded = false;
            prefs = null;
        }
    }

    /**
     * 只给单测：手工种一条观测记录。时钟没有注入口（每次都取
     * System.currentTimeMillis），TTL / 降级这两类用例靠直接种时间戳来造状态 ——
     * 和 SiteRepository.rememberHostFailureForTest 同一个思路。
     */
    static void seedForTest(String fingerprint, String host, long lastSeenAt,
                            boolean trusted, int ok, int fail) {
        synchronized (CoverTrustStore.class) {
            Map<String, Entry> source = sourceFor(null, fingerprint);
            Entry e = new Entry();
            e.firstSeenAt = lastSeenAt;
            e.lastSeenAt = lastSeenAt;
            e.ok = ok;
            e.fail = fail;
            e.trusted = trusted;
            source.put(host.toLowerCase(java.util.Locale.ROOT), e);
        }
    }
}
