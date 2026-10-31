package com.huangguo.mobile;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * host 级路由记忆：连接层失败自动换路重试后，「这个域走哪条路成功过」记在这里，
 * TTL 之内同域请求直接按记忆走，不再试错。
 *
 * <p>══════ 它在路由体系里的位置（方案 A，2026-10-19）════════
 * 优先级：NetRoute 强制分支（directOnly / 声明源 mustProxy，作者显式）&gt; 本记忆
 * &gt; 老规则兜底（全局代理开且非白名单 → 代理）。强制分支在 {@link AiiHttp} 里
 * 短路，记忆只对「谁都不认识」的域发言。方案 B（geosite 分流）在核心层管
 * 「进了 SOCKS 的流量」，本类管「OkHttp 要不要进 SOCKS」，两者正交。
 *
 * <p>══════ 为什么是文件而不是 SharedPreferences ══════
 * spider 宿主在 {@code :spider} 独立进程里（SpiderService），它的 net 也走 AiiHttp、
 * 也要读到同一份记忆。SharedPreferences 跨进程不可靠（各进程各有一份缓存），
 * 普通文件的 mtime 检查天然跨进程：读之前 stat 一下，变了才重读。写走 tmp+rename
 * 原子替换，读容忍脏读（最坏多用一次旧路由）。
 *
 * <p>══════ TTL 与容量 ══════
 * TTL 24 小时：短了反复试错（每次首访多付一次失败延迟），长了站点换 CDN 后钝化。
 * 24h 是「一次使用会话」的量级——今天学到的今天不再问。容量 128 条：正常使用
 * 撑不满，超限丢最旧的（按 at），防止某个刷屏源把表撑爆。
 */
final class RouteMemory {

    private static final String TAG = "HG.Route";

    static final String ROUTE_DIRECT = "direct";
    static final String ROUTE_PROXY = "proxy";

    private static final long TTL_MS = 24L * 3600_000L;
    private static final int MAX_ENTRIES = 128;

    private static volatile File storeFile;
    private static volatile long loadedAt = -1;
    private static volatile Map<String, Entry> cache = new LinkedHashMap<>();

    private static final class Entry {
        String route;
        long at;
    }

    private RouteMemory() {
    }

    /** 应用启动时调一次（AiiHttp.init）。不调的话记忆静默关闭（opinion 恒 null）。 */
    static void init(File filesDir) {
        storeFile = filesDir == null ? null : new File(filesDir, "route-memory.json");
    }

    /** 便于测试：注入一个临时目录。 */
    static void initForTest(File filesDir) {
        cache = new LinkedHashMap<>();
        loadedAt = -1;
        init(filesDir);
    }

    /**
     * 这个域现在该走哪条路。没有记忆或记忆过期返回 {@code null}（由调用方落到
     * 老规则）。
     *
     * @param host 小写域名
     */
    static String opinion(String host) {
        if (host == null || host.length() == 0) return null;
        // 读也进同一把锁：否则首次加载的 reload 可能插在 learn 的
        // 「取快照 → 提交」之间，后提交者拿旧快照覆盖 cache，丢更新。
        synchronized (RouteMemory.class) {
            Entry e = entries().get(host);
            if (e == null) return null;
            if (System.currentTimeMillis() - e.at > TTL_MS) return null;
            return e.route;
        }
    }

    /** 记一条「这条路成功过」。原子写盘；写失败只丢记忆，不影响请求本身。 */
    static void learn(String host, String route) {
        if (host == null || host.length() == 0) return;
        if (!ROUTE_DIRECT.equals(route) && !ROUTE_PROXY.equals(route)) return;
        Map<String, Entry> map = entries();
        synchronized (RouteMemory.class) {
            Entry e = map.get(host);
            if (e == null) {
                e = new Entry();
                map.put(host, e);
            }
            e.route = route;
            e.at = System.currentTimeMillis();
            while (map.size() > MAX_ENTRIES) dropOldest(map);
            cache = map;
            persist(map);
        }
    }

    /** 清掉一个域的记忆（两条路都刚失败时用：记忆已不可信，回落到规则层）。 */
    static void forget(String host) {
        if (host == null || host.length() == 0) return;
        Map<String, Entry> map = entries();
        if (!map.containsKey(host)) return;
        synchronized (RouteMemory.class) {
            map.remove(host);
            cache = map;
            persist(map);
        }
    }

    /** 清空（恢复出厂 / 排查用）。 */
    static void clear() {
        synchronized (RouteMemory.class) {
            cache = new LinkedHashMap<>();
            loadedAt = -1;
            File f = storeFile;
            if (f != null && f.isFile()) f.delete();
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private static Map<String, Entry> entries() {
        File f = storeFile;
        if (f == null) return cache;
        long mtime = f.isFile() ? f.lastModified() : -1;
        if (mtime != loadedAt) {
            synchronized (RouteMemory.class) {
                if (f.lastModified() != loadedAt) {
                    cache = f.isFile() ? load(f) : new LinkedHashMap<String, Entry>();
                    loadedAt = f.lastModified();
                }
            }
        }
        return cache;
    }

    private static Map<String, Entry> load(File f) {
        Map<String, Entry> out = new LinkedHashMap<>();
        try {
            String json = readFully(f);
            if (json.length() == 0) return out;
            JSONObject root = new JSONObject(json);
            long now = System.currentTimeMillis();
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String host = keys.next();
                JSONObject one = root.optJSONObject(host);
                if (one == null) continue;
                String route = one.optString("route", "");
                long at = one.optLong("at", 0);
                if (at == 0 || now - at > TTL_MS) continue;
                if (!ROUTE_DIRECT.equals(route) && !ROUTE_PROXY.equals(route)) continue;
                Entry e = new Entry();
                e.route = route;
                e.at = at;
                out.put(host, e);
            }
        } catch (Throwable t) {
            // 文件坏了就当没学过：静默清空重来，不抛——路由记忆坏了不该影响请求。
            Log.w(TAG, "route-memory 读不出来，按空表算：" + t);
            out.clear();
        }
        return out;
    }

    private static void persist(Map<String, Entry> map) {
        File f = storeFile;
        if (f == null) return;
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Entry> e : map.entrySet()) {
                root.put(e.getKey(), new JSONObject()
                        .put("route", e.getValue().route)
                        .put("at", e.getValue().at));
            }
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Throwable e) {
            // 写失败只丢记忆（put 也声明 JSONException），不影响请求本身。
            Log.w(TAG, "route-memory 写不进去：" + e);
        }
    }

    private static void dropOldest(Map<String, Entry> map) {
        String oldest = null;
        long at = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> e : map.entrySet()) {
            if (e.getValue().at < at) {
                at = e.getValue().at;
                oldest = e.getKey();
            }
        }
        if (oldest != null) map.remove(oldest);
    }

    private static String readFully(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(2048);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
