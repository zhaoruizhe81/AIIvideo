package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 内置免费节点池的**热更新**。
 *
 * ── 为什么必须有 ──
 * 免费节点是会死的。assets/free-nodes.json 打进 APK，用户装的是哪天那一份，
 * 那些公开节点大概活不过几周。等节点死光了，用户看到的是「12 个全不通」，
 * 而维护者唯一的补救办法是发一个新版本让所有用户重新下载 —— 46MB 的 APK，
 * 为了换几十行 JSON。这条路径不可接受。
 *
 * ── 三级回退 ──
 *   1. 本地缓存  filesDir/free-nodes.json （上次成功拉到的）
 *   2. 远端      Endpoints.NODES_MANIFEST 里逐个试
 *   3. assets    打进包里的那份（永远存在的兜底）
 *
 * 永远有东西可用：缓存坏了用 assets，网络断了用缓存。不会出现「一个节点都没有」。
 *
 * ── 格式 ──
 * 和 assets 里那份**完全一致**：{updated, links:[...]}。维护者改节点就是编辑
 * 这一个 JSON、提交、推送，App 下次检查时自己拿到。不需要版本号，不需要签名 ——
 * 因为它只影响"用哪些公开节点"，不执行任何代码。
 *
 * （热更那套签名机制是给 ui/*.js 用的：那是会被执行的代码。节点列表是纯数据，
 * 拿到最坏情况也就是连不上，没有引入新的攻击面。）
 */
final class NodesFeed {

    /** 缓存文件名。放在 filesDir 根下而不是 hot-updates 里 —— 那份目录会被热更回滚逻辑整体接管。 */
    static final String CACHE_NAME = "free-nodes.json";

    /** 元信息。和节点数据分开存：数据是文件（可能几万字），元信息是几个标量。 */
    private static final String STORE = "aii-nodes";
    private static final String KEY_FETCHED_AT = "fetchedAt";
    private static final String KEY_UPDATED = "updated";
    private static final String KEY_COUNT = "count";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_FAILED_AT = "failedAt";
    private static final String KEY_FAILED_WHY = "failedWhy";

    /**
     * 多久算「该检查一次了」。
     *
     * 6 小时：免费节点的生死周期是「天」级的，一天查两三次足够；
     * 更频繁只是白白多发几个请求，对这个场景没有任何收益。
     */
    static final long TTL_MS = 6 * 60 * 60 * 1000L;

    /**
     * 失败之后多久再试。
     *
     * 10 分钟，**不是 TTL**。这一条是实测出来必须分开的：
     * 2026-10-18 装机后第一次自动刷新，正好赶上手机刚开机、DNS 还没起来，
     * 报 `Unable to resolve host`。如果失败也按 6 小时冷却，用户接下来 6 小时
     * 都不会再重试 —— 一个瞬时故障变成了半天的功能缺失。
     *
     * 手机开局那几十秒是最不可靠的时候（Wi-Fi 还在关联、移动数据还在注册），
     * 短冷却能把这种噪音自动抹掉，而对真正的长期故障（服务器挂了）多试几次
     * 也不亏：一次请求几十字节。
     */
    static final long RETRY_MS = 10 * 60 * 1000L;

    /** 单份上限。正常 178 条大约 20KB，给 4MB 已经很宽松。 */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    /**
     * 单次下载超时。
     *
     * 5 秒而不是 12 秒：候选地址有三个，最坏情况 15 秒也要收工。
     * 后台刷新没人等，但用户点「检查更新」时是要等的，不能让他盯着转圈半分钟。
     */
    private static final int TIMEOUT_SECONDS = 5;

    /**
     * 一份 feed 至少要有这么多条才算「像样的」。
     *
     * 防的是「服务器返回了一个 200 的维护页 / 半截文件」——那种东西 parse 出来
     * 是 0 条，如果直接写进缓存，用户下次打开就没有节点可测了。
     * 3 是底线：真维护节点池的人不会只留两条。
     */
    private static final int MIN_NODES = 3;

    private NodesFeed() {}

    /** 一次操作的结果，也是给前端看的那份状态。 */
    static final class Status {
        boolean ok;
        /** 数据来源：cache / assets / remote。 */
        String source = "assets";
        /** feed 里写的维护日期，形如 2026-10-18。 */
        String updated = "";
        /** 这份 feed 里有多少条链接。 */
        int links;
        /** 其中能解析成节点的是多少条（会少于 links，因为有不支持的协议）。 */
        int nodes;
        /** 上次成功拉取的时刻（毫秒），0 表示从没拉过。 */
        long fetchedAt;
        /** 上次失败的说明，成功后清空。 */
        String error = "";
        long failedAt;

        JSONObject toJson() throws Exception {
            JSONObject out = new JSONObject();
            out.put("ok", ok);
            out.put("source", source);
            out.put("updated", updated);
            out.put("links", links);
            out.put("nodes", nodes);
            out.put("fetchedAt", fetchedAt);
            out.put("error", error == null ? "" : error);
            out.put("failedAt", failedAt);
            out.put("stale", fetchedAt <= 0 || System.currentTimeMillis() - fetchedAt > TTL_MS);
            return out;
        }
    }

    static File cacheFile(Context context) {
        return new File(context.getFilesDir(), CACHE_NAME);
    }

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    // ── 读 ────────────────────────────────────────────────────────────

    /**
     * 当前该用的那份文本：缓存优先，没有就用 assets。
     *
     * 缓存读出来还要**再过一遍校验**，因为它可能是上个版本写坏的（磁盘满、
     * 进程被杀在写一半）。校验不过就当它不存在，退回 assets —— 不删文件，
     * 下次刷新成功了自然会覆盖掉。
     */
    static String text(Context context) {
        String cached = readCache(context);
        if (cached != null) return cached;
        return assetText(context);
    }

    /** 缓存内容，无效时返回 null。 */
    static String readCache(Context context) {
        File file = cacheFile(context);
        if (!file.isFile()) return null;
        try {
            String raw = new String(readFully(file), StandardCharsets.UTF_8);
            if (validate(raw) == null) return null;
            return raw;
        } catch (Throwable e) {
            return null;
        }
    }

    static String assetText(Context context) {
        try {
            InputStream in = context.getAssets().open(Subscription.BUILTIN_ASSET);
            return new String(Assets.read(in, MAX_BYTES), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 校验一段 feed 文本，通过则返回解析出的统计，不通过返回 null。
     *
     * 三重检查，缺一不可：
     *   1. 是合法 JSON 且有 links 数组     —— 挡掉 HTML 错误页
     *   2. links 里有足够多的非空字符串     —— 挡掉空壳文件
     *   3. 真能解析出至少 MIN_NODES 个节点  —— 挡掉「格式对但内容全废」的
     *
     * 第 3 步花几毫秒（178 条纯字符串解析），但它挡的是最难受的那种情况：
     * 缓存写进去了、日志显示「更新成功」、用户点测速一片红。
     */
    static Stats validate(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(text);
            JSONArray links = root.optJSONArray("links");
            if (links == null) return null;

            StringBuilder joined = new StringBuilder(links.length() * 120);
            int nonEmpty = 0;
            for (int i = 0; i < links.length(); i++) {
                String line = links.optString(i, "").trim();
                if (line.isEmpty()) continue;
                nonEmpty++;
                joined.append(line).append('\n');
            }
            if (nonEmpty < MIN_NODES) return null;

            Subscription.Result parsed = Subscription.parse(joined.toString());
            if (parsed == null || parsed.size() < MIN_NODES) return null;

            Stats stats = new Stats();
            stats.updated = root.optString("updated", "").trim();
            stats.links = nonEmpty;
            stats.nodes = parsed.size();
            return stats;
        } catch (Throwable e) {
            return null;
        }
    }

    static final class Stats {
        String updated = "";
        int links;
        int nodes;
    }

    // ── 写 ────────────────────────────────────────────────────────────

    /**
     * 拉一次远端，成功则落盘。
     *
     * 候选地址逐个试，第一个成功的就停 —— 顺序在 Endpoints 里定义。
     * 全部失败**不动缓存**：旧的节点再差也比没有强。
     *
     * 整个方法锁在类对象上：后台自动刷新（proxyBoot 的守护线程）和用户点
     * 「检查更新」（桥线程）可能同时进来，两边写同一个 .tmp 会交错出一份坏
     * 缓存 —— 读侧虽然会校验失败回退 assets 自愈，但那等于把一次成功的更新
     * 无声丢掉。
     */
    static synchronized Status refresh(Context context) {
        Status status = status(context);
        List<String> candidates = Endpoints.nodesUrls();
        List<String> tried = new ArrayList<String>();
        String lastError = "";

        for (String url : candidates) {
            tried.add(url);
            try {
                byte[] body = AiiHttp.getOnceRouted(url, TIMEOUT_SECONDS);
                if (body == null || body.length == 0) {
                    lastError = "返回空内容";
                    continue;
                }
                if (body.length > MAX_BYTES) {
                    lastError = "文件太大（" + (body.length / 1024) + "KB）";
                    continue;
                }
                String raw = new String(body, StandardCharsets.UTF_8);
                Stats stats = validate(raw);
                if (stats == null) {
                    lastError = "内容不像节点池（JSON 或链接不合法）";
                    continue;
                }
                writeCache(context, raw);
                long now = System.currentTimeMillis();
                store(context).edit()
                        .putLong(KEY_FETCHED_AT, now)
                        .putString(KEY_UPDATED, stats.updated)
                        .putInt(KEY_COUNT, stats.nodes)
                        .putString(KEY_SOURCE, "remote")
                        .remove(KEY_FAILED_AT)
                        .remove(KEY_FAILED_WHY)
                        .apply();

                status.ok = true;
                status.source = "remote";
                status.updated = stats.updated;
                status.links = stats.links;
                status.nodes = stats.nodes;
                status.fetchedAt = now;
                status.error = "";
                status.failedAt = 0;
                return status;
            } catch (Throwable e) {
                String m = e.getMessage();
                lastError = (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : trim(m);
            }
        }

        // 全挂了。记下来，但**保留旧的 fetchedAt** —— 「上次成功是什么时候」
        // 和「这次为什么失败」是两件事，混在一个时间戳里会让人以为缓存过期了。
        long now = System.currentTimeMillis();
        store(context).edit()
                .putLong(KEY_FAILED_AT, now)
                .putString(KEY_FAILED_WHY, lastError.isEmpty() ? "拿不到节点池" : lastError)
                .apply();
        status.ok = false;
        status.error = lastError.isEmpty() ? "拿不到节点池" : lastError;
        status.failedAt = now;
        // source / fetchedAt 保持上一次的结论：失败不代表缓存没了。
        return status;
    }

    /** 原子写：先写 .tmp 再 rename。断电 / 被杀在半路时旧文件仍然完整。 */
    private static void writeCache(Context context, String raw) throws Exception {
        File target = cacheFile(context);
        File tmp = new File(target.getParentFile(), CACHE_NAME + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } finally {
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
        if (!tmp.renameTo(target)) {
            // rename 失败（少见：跨文件系统或目标被占用）时退回直接覆盖，
            // 总比丢了这次结果强。
            FileOutputStream direct = new FileOutputStream(target);
            try {
                direct.write(raw.getBytes(StandardCharsets.UTF_8));
            } finally {
                try {
                    direct.close();
                } catch (Throwable ignored) {
                }
            }
            tmp.delete();
        }
    }

    /** 删缓存，回到 APK 自带的那份。 */
    static Status reset(Context context) {
        cacheFile(context).delete();
        new File(cacheFile(context).getParentFile(), CACHE_NAME + ".tmp").delete();
        store(context).edit()
                .remove(KEY_FETCHED_AT)
                .remove(KEY_UPDATED)
                .remove(KEY_COUNT)
                .remove(KEY_SOURCE)
                .remove(KEY_FAILED_AT)
                .remove(KEY_FAILED_WHY)
                .apply();
        Status status = status(context);
        status.source = "assets";
        status.fetchedAt = 0;
        status.ok = true;
        return status;
    }

    // ── 状态 ──────────────────────────────────────────────────────────

    /**
     * 当前状态，**不联网**。
     *
     * 讲清楚「手上这份是哪来的、什么时候的、有多少条」就够了 ——
     * 前端据此决定要不要提示「可以更新」。
     */
    static Status status(Context context) {
        Status status = new Status();
        SharedPreferences prefs = store(context);
        status.fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0);
        status.failedAt = prefs.getLong(KEY_FAILED_AT, 0);
        status.error = prefs.getString(KEY_FAILED_WHY, "");
        if (status.error == null) status.error = "";

        String raw = readCache(context);
        boolean cached = raw != null;
        if (!cached) raw = assetText(context);

        Stats stats = validate(raw);
        if (stats != null) {
            status.links = stats.links;
            status.nodes = stats.nodes;
            status.updated = stats.updated;
        }
        status.source = cached ? "cache" : "assets";
        status.ok = stats != null;
        if (!status.ok && status.error.isEmpty()) {
            status.error = "节点文件读不出来";
        }
        return status;
    }

    /**
     * 该不该自动查一次。
     *
     * 成功和失败用**两套冷却**：
     *   距上次成功不足 TTL（6h）     → 不用查，手上这份还新鲜
     *   上次成功后失败过、且在 10 分钟内 → 不用查，刚试过
     *   其余                          → 查
     *
     * 关键在于失败不该跟着 TTL 走。见 RETRY_MS 的注释。
     */
    static boolean due(Context context) {
        long now = System.currentTimeMillis();
        long fetchedAt = store(context).getLong(KEY_FETCHED_AT, 0);
        long failedAt = store(context).getLong(KEY_FAILED_AT, 0);
        if (fetchedAt > 0 && now - fetchedAt < TTL_MS) return false;
        if (failedAt > 0 && now - failedAt < RETRY_MS) return false;
        return true;
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    private static byte[] readFully(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new Exception("缓存文件太大");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String trim(String s) {
        s = s.replace('\n', ' ').trim();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
