package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 封面缓存键的自适应易变参数记忆（方案 §4.4）。
 *
 * <p>══════ 解决什么 ══════════════════════════════════════════════
 * {@code SiteRepository.SIGNATURE_PARAMS} 那七个名字是**本次观测到的**签名参数。
 * 下一个图床用 {@code e=} / {@code X-Oss-Expires} 之流，缓存键就把签名算进去
 * —— 每 90 分钟整座图床重下一遍。这里按 (源 fingerprint, path) 记录每个 query
 * 参数的最近取值，出现过两个不同值就判定「易变」，从缓存键剔除并持久化。
 * 种子名单保留（冷启动立刻生效），自适应在其上增量。
 *
 * <p>══════ 误判的代价 ════════════════════════════════════════════
 * 把稳定参数误判成易变，只损失缓存命中（多下载一次图），不影响正确性 ——
 * 所以判定阈值可以松（两个不同值就够），不需要等三次。
 *
 * <p>存储形态与 {@link CoverTrustStore} 同款：SharedPreferences 文件
 * {@code cover-param-memory}，恢复出厂自动清（clearPreferences 目录遍历），
 * 静态内存层带 reset 口子；context == null 时纯内存（单测）。
 */
final class CoverParamMemory {

    /** 每个参数记最近几个取值。 */
    static final int HISTORY = 3;

    private static final String PREFS = "cover-param-memory";
    private static final Map<String, SourceState> BY_SOURCE = new LinkedHashMap<>();
    private static SharedPreferences prefs;
    private static boolean diskLoaded;

    private static final class SourceState {
        /** path → (param → 最近取值，≤HISTORY 个，新在前)。 */
        final Map<String, Map<String, JSONArray>> seen = new HashMap<>();
        /** path → 已判易变的参数名（小写）。持久化的判定，观察不推翻。 */
        final Map<String, Set<String>> volatileByPath = new HashMap<>();
    }

    private CoverParamMemory() {
    }

    /**
     * 这个 (源, path) 下已经被判易变的参数名。返回的集合直接喂给
     * {@code SiteRepository.cacheKeyQuery}，空集是常态。
     */
    static Set<String> volatileParams(Context context, String fingerprint, String path) {
        if (fingerprint == null || path == null) return Collections.emptySet();
        synchronized (CoverParamMemory.class) {
            ensureLoaded(context);
            SourceState state = stateFor(fingerprint);
            Set<String> decided = state.volatileByPath.get(path);
            return decided != null ? new HashSet<String>(decided) : Collections.<String>emptySet();
        }
    }

    /**
     * 记一笔观测：这次封面 URL 的 query 参数。某参数在记忆里出现过**不同的值**
     * 当场判易变并落盘 —— 本请求的键就该剔除它，等下一次就晚了。
     */
    static void observe(Context context, String fingerprint, String path, Map<String, String> params) {
        if (fingerprint == null || path == null || params == null || params.isEmpty()) return;
        synchronized (CoverParamMemory.class) {
            ensureLoaded(context);
            SourceState state = stateFor(fingerprint);
            boolean changed = false;
            for (Map.Entry<String, String> e : params.entrySet()) {
                String name = e.getKey().toLowerCase(java.util.Locale.ROOT);
                String value = e.getValue();
                if (value == null) continue;
                // 值太长不原样记：记忆是给「变没变」用的，不是存档。截到 64 字符，
                // 够看出「同一个参数换值」了。
                if (value.length() > 64) value = value.substring(0, 64);
                Map<String, JSONArray> byParam = state.seen.get(path);
                if (byParam == null) {
                    byParam = new LinkedHashMap<>();
                    state.seen.put(path, byParam);
                }
                JSONArray history = byParam.get(name);
                if (history == null) {
                    history = new JSONArray();
                    byParam.put(name, history);
                }
                // 已判易变的参数不再记历史 —— 判定不因后续观察翻案，也别让记忆白长。
                if (containsString(history, value)) {
                    continue;
                }
                if (history.length() > 0) {
                    // 历史里已经有别的值：这就是「易变」的定义。落判定。
                    Set<String> decided = state.volatileByPath.get(path);
                    if (decided == null) {
                        decided = new HashSet<>();
                        state.volatileByPath.put(path, decided);
                    }
                    if (decided.add(name)) {
                        changed = true;
                    }
                    continue;
                }
                history.put(value);
                changed = true;
                // 每条记忆有界：参数维度跟着 path 走，path 数量本身有封面缓存trim兜着，
                // 这里只防单条 path 里参数爆炸（正常 URL 也就三五个参数）。
                while (byParam.size() > 16) {
                    Iterator<String> it = byParam.keySet().iterator();
                    it.next();
                    it.remove();
                }
            }
            if (changed) {
                persist(fingerprint, state);
            }
        }
    }

    /** 换源：这个源的参数记忆整份作废（与观测集同一条 reset 路径）。 */
    static void reset(Context context, String fingerprint) {
        if (fingerprint == null) return;
        synchronized (CoverParamMemory.class) {
            BY_SOURCE.remove(fingerprint);
            SharedPreferences p = prefsOf(context);
            if (p != null) {
                p.edit().remove(fingerprint).apply();
            }
        }
    }

    /** 清空（恢复出厂兜底；prefs 那份由 clearPreferences() 顺带清）。 */
    static void resetAll(Context context) {
        synchronized (CoverParamMemory.class) {
            BY_SOURCE.clear();
            diskLoaded = true;
            SharedPreferences p = prefsOf(context);
            if (p != null) {
                p.edit().clear().apply();
            }
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private static boolean containsString(JSONArray arr, String value) {
        for (int i = 0; i < arr.length(); i++) {
            if (value.equals(arr.optString(i))) return true;
        }
        return false;
    }

    private static void ensureLoaded(Context context) {
        if (!diskLoaded && context != null) {
            synchronized (CoverParamMemory.class) {
                if (!diskLoaded) {
                    SharedPreferences p = prefsOf(context);
                    if (p != null) {
                        java.util.Map<String, ?> all = p.getAll();
                        for (java.util.Map.Entry<String, ?> kv : all.entrySet()) {
                            BY_SOURCE.put(kv.getKey(), parse(String.valueOf(kv.getValue())));
                        }
                    }
                    diskLoaded = true;
                }
            }
        }
    }

    /** 按 fingerprint 取状态；缺就建空的。 */
    private static SourceState stateFor(String fingerprint) {
        SourceState state = BY_SOURCE.get(fingerprint);
        if (state == null) {
            state = new SourceState();
            BY_SOURCE.put(fingerprint, state);
        }
        return state;
    }

    private static SourceState parse(String json) {
        SourceState state = new SourceState();
        try {
            JSONObject root = new JSONObject(json);
            JSONObject volatileRoot = root.optJSONObject("volatile");
            if (volatileRoot != null) {
                Iterator<String> paths = volatileRoot.keys();
                while (paths.hasNext()) {
                    String path = paths.next();
                    JSONArray names = volatileRoot.optJSONArray(path);
                    Set<String> decided = new HashSet<>();
                    for (int i = 0; names != null && i < names.length(); i++) {
                        decided.add(names.optString(i));
                    }
                    if (!decided.isEmpty()) {
                        state.volatileByPath.put(path, decided);
                    }
                }
            }
            JSONObject seenRoot = root.optJSONObject("seen");
            if (seenRoot != null) {
                Iterator<String> paths = seenRoot.keys();
                while (paths.hasNext()) {
                    String path = paths.next();
                    JSONObject byParam = seenRoot.optJSONObject(path);
                    if (byParam == null) continue;
                    Map<String, JSONArray> history = new LinkedHashMap<>();
                    Iterator<String> names = byParam.keys();
                    while (names.hasNext()) {
                        String name = names.next();
                        history.put(name, byParam.optJSONArray(name));
                    }
                    state.seen.put(path, history);
                }
            }
        } catch (Throwable ignored) {
            // 坏了当空表：只损失缓存命中，不影响出图。
        }
        return state;
    }

    private static void persist(String fingerprint, SourceState state) {
        SharedPreferences p = prefs;
        if (p == null) return;
        try {
            JSONObject root = new JSONObject();
            JSONObject volatileRoot = new JSONObject();
            for (Map.Entry<String, Set<String>> e : state.volatileByPath.entrySet()) {
                volatileRoot.put(e.getKey(), new JSONArray(new java.util.ArrayList<Object>(e.getValue())));
            }
            root.put("volatile", volatileRoot);
            JSONObject seenRoot = new JSONObject();
            for (Map.Entry<String, Map<String, JSONArray>> e : state.seen.entrySet()) {
                JSONObject byParam = new JSONObject();
                for (Map.Entry<String, JSONArray> h : e.getValue().entrySet()) {
                    byParam.put(h.getKey(), h.getValue());
                }
                seenRoot.put(e.getKey(), byParam);
            }
            root.put("seen", seenRoot);
            p.edit().putString(fingerprint, root.toString()).apply();
        } catch (Throwable ignored) {
            // 写失败只丢记忆。
        }
    }

    private static SharedPreferences prefsOf(Context context) {
        if (prefs == null && context != null) {
            prefs = context.getApplicationContext().getSharedPreferences(PREFS, 0);
        }
        return prefs;
    }

    /** 只给单测：清静态内存 + 掐掉 prefs 引用。 */
    static void resetForTest() {
        synchronized (CoverParamMemory.class) {
            BY_SOURCE.clear();
            diskLoaded = false;
            prefs = null;
        }
    }
}
